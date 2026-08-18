#!/usr/bin/env python3
import unittest
from dataclasses import dataclass, field

INIT, BUILD, LIVE, DISC = 'INITIALIZATION','BASELINE_BUILDING','LIVE','DISCONNECTED'
BASELINE, POLL, POST, REMOVE = 'BASELINE_SYNC','POLL_SYNC','REAL_POST','REAL_REMOVE'

@dataclass(frozen=True)
class Msg:
    mid: str
    ts: int

@dataclass
class VaultModel:
    phase: str = INIT
    baseline_ready: bool = False
    baseline_ready_at: int = 0
    trusted: bool = False
    baseline: list[Msg] = field(default_factory=list)
    records: dict[str,Msg] = field(default_factory=dict)
    deleted: set[str] = field(default_factory=set)
    downloads: set[str] = field(default_factory=set)
    evidence: set[tuple] = field(default_factory=set)

    def persist(self, msgs):
        for m in msgs: self.records[m.mid] = m

    def establish(self, active, now, read_ok=True):
        self.phase=BUILD; self.baseline_ready=False; self.trusted=False
        if not read_ok: return False
        active=list(active or [])
        self.persist(active); self.baseline=active; self.trusted=bool(active)
        self.baseline_ready_at=now; self.baseline_ready=True; self.phase=LIVE
        return True

    def positive_presence(self, msgs):
        self.persist(msgs)
        if self.baseline_ready and self.phase==LIVE and msgs:
            self.baseline=list(msgs); self.trusted=True

    def marker(self, current, count, marker_ts, stable=True, source=POST, post_time=1, evidence='e'):
        can=(self.baseline_ready and self.phase==LIVE and source==POST and post_time>=self.baseline_ready_at and count>0)
        token=(evidence,post_time,count)
        if not can or token in self.evidence: return 0,'REJECTED'
        self.evidence.add(token)
        cur={m.mid for m in current}; missing=[m for m in self.baseline if m.mid not in cur]
        exact=[m for m in missing if stable and m.ts==marker_ts]
        if self.trusted and count==1 and stable and len(exact)==1:
            self.deleted.add(exact[0].mid); self.baseline=list(current); return 1,'CONFIRMED'
        return 0,'UNKNOWN'

    def media(self, mid):
        if mid in self.downloads: return False
        self.downloads.add(mid); return True

class DeletionLifecycleRegression(unittest.TestCase):
    def setUp(self):
        self.msgs=[Msg(f'm{i}',10000+i) for i in range(20)]

    def fresh(self, now=20000):
        m=VaultModel(); m.persist(self.msgs); m.establish(self.msgs,now); return m

    def test_coldStartDoesNotGenerateDeletions(self):
        m=self.fresh(); self.assertEqual(set(),m.deleted)

    def test_warmStartDoesNotGenerateDeletions(self):
        m=self.fresh(); m.establish(self.msgs,21000); self.assertEqual(set(),m.deleted)

    def test_processDeathDoesNotGenerateDeletions(self):
        m=self.fresh(); m.establish([],22000); self.assertEqual(set(),m.deleted)

    def test_forceStopDoesNotGenerateDeletions(self):
        m=self.fresh(); m.phase=DISC; m.baseline_ready=False; m.establish(self.msgs,23000); self.assertFalse(m.deleted)

    def test_serviceRestartDoesNotGenerateDeletions(self):
        m=self.fresh(); m.establish(self.msgs,24000); self.assertFalse(m.deleted)

    def test_listenerReconnectDoesNotGenerateDeletions(self):
        m=self.fresh(); m.establish(self.msgs,25000); self.assertFalse(m.deleted)

    def test_rebootDoesNotGenerateDeletions(self):
        m=self.fresh(); m.phase=INIT; m.establish(self.msgs,26000); self.assertFalse(m.deleted)

    def test_baselineMissingDoesNotGenerateDeletions(self):
        m=VaultModel(); m.persist(self.msgs); self.assertFalse(m.establish(None,27000,False)); self.assertFalse(m.deleted); self.assertFalse(m.baseline_ready)

    def test_emptySnapshotDoesNotGenerateDeletions(self):
        m=self.fresh(); m.positive_presence([]); self.assertFalse(m.deleted)

    def test_notificationRemovedDoesNotMeanDeleted(self):
        m=self.fresh(); n,c=m.marker([],1,self.msgs[-1].ts,True,REMOVE,28100,'remove'); self.assertEqual((0,'REJECTED'),(n,c))

    def test_oneMarkerDoesNotDeleteEntireSnapshot(self):
        m=self.fresh(); n,c=m.marker([],1,999999,True,POST,29100,'ambiguous'); self.assertEqual((0,'UNKNOWN'),(n,c)); self.assertFalse(m.deleted)

    def test_duplicateNotificationIsIgnored(self):
        m=self.fresh(); cur=self.msgs[:-1]
        self.assertEqual(1,m.marker(cur,1,self.msgs[-1].ts,True,POST,30100,'same')[0])
        self.assertEqual(0,m.marker(cur,1,self.msgs[-1].ts,True,POST,30100,'same')[0])
        self.assertEqual(1,len(m.deleted))

    def test_existingMediaIsNotDownloadedAgain(self):
        m=self.fresh(); self.assertTrue(m.media('XYZ')); self.assertFalse(m.media('XYZ')); self.assertEqual(1,len(m.downloads))

    def test_realDeletionOnlyAffectsMatchingMessage(self):
        m=self.fresh(); n,c=m.marker(self.msgs[:-1],1,self.msgs[-1].ts,True,POST,31100,'real'); self.assertEqual((1,'CONFIRMED'),(n,c)); self.assertEqual({self.msgs[-1].mid},m.deleted)

    def test_ambiguousDeletionFailsClosed(self):
        m=self.fresh(); n,c=m.marker([],20,self.msgs[-1].ts,True,POST,32100,'batch'); self.assertEqual((0,'UNKNOWN'),(n,c)); self.assertFalse(m.deleted)

    def test_twentyRestartsRemainIdempotent(self):
        m=self.fresh()
        for i in range(20): m.establish(self.msgs,33000+i)
        self.assertEqual(20,len(m.records)); self.assertFalse(m.deleted); self.assertFalse(m.downloads)

    def test_thousandMessagesRemainConsistent(self):
        big=[Msg(f'id{i}',100000+i) for i in range(1000)]; m=VaultModel(); m.persist(big)
        for i in range(20): m.establish(big,200000+i)
        self.assertEqual(1000,len(m.records)); self.assertFalse(m.deleted); self.assertFalse(m.downloads)

    def test_pollPresenceCanRecoverWithoutDeletingAbsence(self):
        m=self.fresh(); new=self.msgs+[Msg('m20',20000)]; m.positive_presence(new); self.assertIn('m20',m.records); self.assertFalse(m.deleted)

    def test_userTypedDeletePhraseDoesNotConfirmWithoutExactIdentity(self):
        m=self.fresh(); n,c=m.marker(self.msgs[:-1],1,777777,True,POST,35100,'typed'); self.assertEqual((0,'UNKNOWN'),(n,c)); self.assertFalse(m.deleted)

if __name__=='__main__':
    unittest.main(verbosity=2)
