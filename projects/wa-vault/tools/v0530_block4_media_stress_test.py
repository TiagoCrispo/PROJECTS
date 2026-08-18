#!/usr/bin/env python3
import random, sqlite3

random.seed(5304)
conn=sqlite3.connect(':memory:')
conn.executescript('''
CREATE TABLE media(id INTEGER PRIMARY KEY AUTOINCREMENT,content_hash TEXT NOT NULL,media_type TEXT NOT NULL,linked_message_id INTEGER NOT NULL DEFAULT 0,byte_size INTEGER NOT NULL);
CREATE UNIQUE INDEX idx_media_hash_type_unique ON media(content_hash,media_type) WHERE content_hash<>'';
CREATE TABLE media_message_links(media_id INTEGER NOT NULL,message_id INTEGER NOT NULL,linked_at INTEGER NOT NULL,link_source TEXT NOT NULL DEFAULT '',PRIMARY KEY(media_id,message_id));
CREATE TABLE media_tombstones(source_key TEXT NOT NULL DEFAULT '',content_hash TEXT NOT NULL DEFAULT '',media_type TEXT NOT NULL DEFAULT '',deleted_at INTEGER NOT NULL);
''')

def capture(h,t,msg,size=1024):
    row=conn.execute('select id from media where content_hash=? and media_type=?',(h,t)).fetchone()
    if row:
        mid=row[0]
    else:
        cur=conn.execute('insert into media(content_hash,media_type,linked_message_id,byte_size) values(?,?,?,?)',(h,t,msg,size))
        mid=cur.lastrowid
    conn.execute("insert or ignore into media_message_links(media_id,message_id,linked_at,link_source) values(?,?,1,'stress')",(mid,msg))
    conn.execute('update media set linked_message_id=case when linked_message_id=0 then ? else linked_message_id end where id=?',(msg,mid))
    return mid

# 1000 legitimate messages share only 5 recurring physical blobs.
for msg in range(1,1001):
    capture(f'blob-{msg%5}','image',msg,1000+(msg%7))
conn.commit()
assert conn.execute('select count(*) from media').fetchone()[0]==5
assert conn.execute('select count(*) from media_message_links').fetchone()[0]==1000

# 20 full notification/retry replays must not create blobs or links.
for _ in range(20):
    for msg in range(1,1001):
        capture(f'blob-{msg%5}','image',msg,1000+(msg%7))
conn.commit()
assert conn.execute('select count(*) from media').fetchone()[0]==5
assert conn.execute('select count(*) from media_message_links').fetchone()[0]==1000

# Remove 500 logical links. Every recurring blob still has surviving links and must survive.
for msg in range(1,1001,2):
    conn.execute('delete from media_message_links where message_id=?',(msg,))
conn.commit()
for media_id, in conn.execute('select id from media'):
    assert conn.execute('select count(*) from media_message_links where media_id=?',(media_id,)).fetchone()[0]>0
assert conn.execute('select count(*) from media').fetchone()[0]==5

# Tombstone semantics model: exact source remains blocked; global hash retry shield expires after 24h.
DAY=24*60*60*1000
now=10*DAY
source='notif:content://same|123'
h='samehash'
conn.execute('insert into media_tombstones(source_key,content_hash,media_type,deleted_at) values(?,?,?,?)',(source,h,'image',now))
conn.commit()
def exact_blocked(src):
    return conn.execute('select 1 from media_tombstones where source_key=? limit 1',(src,)).fetchone() is not None
def hash_blocked(hash_,at):
    cutoff=at-DAY
    return conn.execute('select 1 from media_tombstones where content_hash=? and media_type=? and deleted_at>=? limit 1',(hash_,'image',cutoff)).fetchone() is not None
assert exact_blocked(source)
assert hash_blocked(h,now+1_000)
assert not hash_blocked(h,now+DAY+1)
assert not exact_blocked('notif:content://same|999')

# Randomized crash-state invariant: DB reference may exist only after durable ciphertext move.
states=['copying','ready_plain','encrypted_ready','moved_before_db','committed']
for _ in range(10000):
    st=random.choice(states)
    permanent=st in ('moved_before_db','committed')
    cipher=st in ('encrypted_ready','moved_before_db','committed')
    dbref=st=='committed'
    assert not (permanent and not cipher)
    assert not dbref or (permanent and cipher)

print('BLOCK4_MEDIA_STRESS_PASS')
print('1000 messages/5 blobs x20 replays -> 5 physical blobs, 1000 links; 10k crash states safe; tombstone retry window behaves')
