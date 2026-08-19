from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
new = (ROOT / 'app/src/main/java/com/fer/wavault/VaultDb.java').read_text()

# Canonical schema fixture from WA Vault DB v13. Keeping the minimal schema here
# makes this migration regression self-contained and portable across CI/worktrees.
V13_MESSAGES_SCHEMA = '''CREATE TABLE messages(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL,
    conversation BLOB NOT NULL,
    sender BLOB NOT NULL,
    body BLOB NOT NULL,
    timestamp INTEGER NOT NULL,
    notification_key TEXT,
    fingerprint TEXT UNIQUE,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    is_group INTEGER NOT NULL DEFAULT 0,
    deletion_state INTEGER NOT NULL DEFAULT 0,
    message_index INTEGER NOT NULL DEFAULT 0
)'''


def need(condition, message):
    if not condition:
        raise AssertionError(message)


conn = sqlite3.connect(':memory:')
conn.execute(V13_MESSAGES_SCHEMA)
conn.execute(
    'insert into messages(package_name,conversation,sender,body,timestamp,notification_key,fingerprint,is_deleted,is_group,deletion_state,message_index) '
    'values(?,?,?,?,?,?,?,?,?,?,?)',
    ('com.whatsapp', b'c', b's', b'b', 123, 'nk', 'msgfp_old', 0, 0, 0, 7),
)
conn.commit()

start = new.index('if (oldVersion < 14)')
end = new.index('if (oldVersion < 15)', start)
branch = new[start:end]
sqls = re.findall(r'db\.execSQL\("([^"]+)"\)', branch)
need(len(sqls) >= 4, 'v14 migration statements incomplete')
for sql in sqls:
    conn.execute(sql.replace('\\"', '"'))
conn.commit()

cols = {row[1]: row for row in conn.execute('pragma table_info(messages)')}
need('identity_slot' in cols, 'identity_slot not added')
need(conn.execute('select identity_slot from messages where id=1').fetchone()[0] == 1,
     'old row did not receive slot=1 default')
need(conn.execute("select count(*) from sqlite_master where type='table' and name='deletion_evidence'").fetchone()[0] == 1,
     'deletion_evidence table missing')
need(conn.execute("select count(*) from sqlite_master where type='index' and name='idx_messages_identity_lookup'").fetchone()[0] == 1,
     'identity index missing')
need(conn.execute("select count(*) from sqlite_master where type='index' and name='idx_deletion_evidence_time'").fetchone()[0] == 1,
     'evidence index missing')
need(conn.execute('pragma integrity_check').fetchone()[0] == 'ok', 'integrity_check failed after migration')
need(conn.execute('select count(*) from messages').fetchone()[0] == 1, 'migration lost existing message row')
print('BLOCK3_DB_V13_TO_V14_MIGRATION_PASS')
print('existing rows preserved; identity_slot defaults to 1; evidence ledger/indexes created; integrity_check=ok')
