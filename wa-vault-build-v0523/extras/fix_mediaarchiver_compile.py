from pathlib import Path

# Remove the now-empty automatic Gallery loop left behind when Gallery export became manual-only.
p=Path('app/src/main/java/com/fer/wavault/MediaArchiver.java')
s=p.read_text(encoding='utf-8')
old='''        if (n > 0) {\n            context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE).edit().putLong("voice_bank_last_kept_at", System.currentTimeMillis()).putInt("voice_bank_last_kept_count", n).apply();\n            try {\n                for (VaultDb.Media m : db.listMediaForMessage(messageId,messageTime,8))\n            } catch(Throwable ignored) {}\n        }\n'''
new='''        if (n > 0) {\n            context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE).edit().putLong("voice_bank_last_kept_at", System.currentTimeMillis()).putInt("voice_bank_last_kept_count", n).apply();\n        }\n'''
if old not in s:
    raise SystemExit('MediaArchiver compile-fix target not found')
p.write_text(s.replace(old,new),encoding='utf-8')

# Android SDK 35 does not expose Os.unlink in this Java API surface. File.delete + opaque rename fallback is sufficient.
p=Path('app/src/main/java/com/fer/wavault/MediaCrypto.java')
s=p.read_text(encoding='utf-8')
old_comment='/** Remove the recognizable old filename; if unlink is blocked, rename the encrypted orphan to an opaque name. */'
new_comment='/** Remove the recognizable old filename; if direct deletion is blocked, rename the encrypted orphan to an opaque name. */'
s=s.replace(old_comment,new_comment)
old_line='        try{android.system.Os.unlink(source.getAbsolutePath());if(!source.exists())return true;}catch(Throwable ignored){}\n'
if old_line not in s:
    raise SystemExit('MediaCrypto compile-fix target not found')
p.write_text(s.replace(old_line,''),encoding='utf-8')
