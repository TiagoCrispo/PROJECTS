from pathlib import Path
files={
 'A53Performance/app/src/main/java/com/fer/a53performance/StorageRepository.java': [('List.of()','Collections.emptyList()'),('Set.of()','Collections.emptySet()')],
 'A53Performance/app/src/main/java/com/fer/a53performance/StorageAnalyzer.java': [('List.of()','Collections.emptyList()')],
}
for name,repls in files.items():
 p=Path(name); s=p.read_text()
 for old,new in repls: s=s.replace(old,new)
 p.write_text(s)
 print('fixed',name)
