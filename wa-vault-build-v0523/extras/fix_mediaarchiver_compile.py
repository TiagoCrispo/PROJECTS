from pathlib import Path
p=Path('app/src/main/java/com/fer/wavault/MediaArchiver.java')
s=p.read_text(encoding='utf-8')
old='''        if (n > 0) {\n            context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE).edit().putLong("voice_bank_last_kept_at", System.currentTimeMillis()).putInt("voice_bank_last_kept_count", n).apply();\n            try {\n                for (VaultDb.Media m : db.listMediaForMessage(messageId,messageTime,8))\n            } catch(Throwable ignored) {}\n        }\n'''
new='''        if (n > 0) {\n            context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE).edit().putLong("voice_bank_last_kept_at", System.currentTimeMillis()).putInt("voice_bank_last_kept_count", n).apply();\n        }\n'''
if old not in s:
    raise SystemExit('MediaArchiver compile-fix target not found')
p.write_text(s.replace(old,new),encoding='utf-8')
