import com.fer.wavault.DeletionGuard;

public final class DeletionGuardSelfTest {
    private static void need(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        need(!DeletionGuard.absenceConfirmsDeletion(20, 0), "absence != deletion");
        need(!DeletionGuard.canEvaluateDeletionMarker(DeletionGuard.Phase.BASELINE_BUILDING,
                DeletionGuard.Source.REAL_POST, false, 2000L, 1000L), "closed baseline");
        need(!DeletionGuard.canEvaluateDeletionMarker(DeletionGuard.Phase.LIVE,
                DeletionGuard.Source.POLL_SYNC, true, 2000L, 1000L), "poll not delete");
        need(!DeletionGuard.canEvaluateDeletionMarker(DeletionGuard.Phase.LIVE,
                DeletionGuard.Source.REAL_REMOVE, true, 2000L, 1000L), "removal not delete");
        need(!DeletionGuard.canEvaluateDeletionMarker(DeletionGuard.Phase.LIVE,
                DeletionGuard.Source.REAL_POST, true, 900L, 1000L), "stale replay not delete");
        need(DeletionGuard.canEvaluateDeletionMarker(DeletionGuard.Phase.LIVE,
                DeletionGuard.Source.REAL_POST, true, 1100L, 1000L), "fresh real post gate");
        need(DeletionGuard.canConfirmSingularMessage(true,1,true,1), "strong 1:1 correlation");
        need(!DeletionGuard.canConfirmSingularMessage(true,1,false,1), "unstable timestamp unknown");
        need(!DeletionGuard.canConfirmSingularMessage(true,2,true,2), "plural unknown");
        need(!DeletionGuard.canConfirmSingularMessage(true,1,true,0), "no timestamp match unknown");
        need(!DeletionGuard.canConfirmSingularMessage(true,1,true,2), "ambiguous timestamp unknown");
        need(!DeletionGuard.canConfirmSingularMessage(false,1,true,1), "untrusted baseline unknown");
        need(DeletionGuard.safelyMappableCount(20,20,true)==0, "count-only mapping disabled");
        need(DeletionGuard.unresolvedMarkerCount(1,0)==1, "unresolved accounting bounded");
        System.out.println("DeletionGuardSelfTest PASS");
    }
}
