from pathlib import Path
import re,sqlite3
ROOT=Path(__file__).resolve().parents[1]
s=(ROOT/'app/src/main/java/com/fer/wavault/VaultDb.java').read_text()
def need(c,m):
    if not c: raise AssertionError(m)
conn=sqlite3.connect(':memory:')
# Minimal v14-compatible columns used by the v15 migration statement.
conn.executescript('''
CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT);
CREATE TABLE media(id INTEGER PRIMARY KEY AUTOINCREMENT, linked_message_id INTEGER NOT NULL DEFAULT 0, captured_at INTEGER NOT NULL DEFAULT 0);
INSERT INTO messages(id) VALUES(11),(22);
INSERT INTO media(id,linked_message_id,captured_at) VALUES(1,11,1000),(2,0,2000),(3,22,3000);
''')
start=s.index('if (oldVersion < 15)')
end=s.index('\n        }\n    }',start)+10
branch=s[start:end]
sqls=re.findall(r'db\.execSQL\("([^"]+)"\)',branch)
need(len(sqls)>=4,'v15 migration statements incomplete')
for sql in sqls: conn.execute(sql.replace('\\"','"'))
conn.commit()
need(conn.execute("select count(*) from sqlite_master where type='table' and name='media_message_links'").fetchone()[0]==1,'link table missing')
need(conn.execute('select count(*) from media_message_links').fetchone()[0]==2,'legacy linked_message_id rows not migrated')
need(conn.execute('select count(*) from media_message_links where media_id=1 and message_id=11').fetchone()[0]==1,'media 1 link lost')
need(conn.execute('select count(*) from media_message_links where media_id=3 and message_id=22').fetchone()[0]==1,'media 3 link lost')
# Re-running upgrade SQL must be idempotent.
for sql in sqls: conn.execute(sql.replace('\\"','"'))
conn.commit()
need(conn.execute('select count(*) from media_message_links').fetchone()[0]==2,'migration replay duplicated links')
need(conn.execute('pragma integrity_check').fetchone()[0]=='ok','integrity check failed')
print('BLOCK4_DB_V14_TO_V15_MIGRATION_PASS')
print('legacy primary links preserved; migration replay idempotent; integrity_check=ok')
