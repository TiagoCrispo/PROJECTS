from pathlib import Path
p=Path('app/src/main/java/com/fer/wavault/MainActivity.java')
s=p.read_text()
s=s.replace('b.append("version=").append(BuildConfig.VERSION_NAME).append(\'\\n\');','b.append("version=").append(appVersionName()).append(\'\\n\');')
anchor='    private String buildTechnicalDiagnostics(){\n'
helper='    private String appVersionName(){try{String v=getPackageManager().getPackageInfo(getPackageName(),0).versionName;return v==null||v.isEmpty()?"unknown":v;}catch(Throwable t){return "unknown";}}\n\n'
if helper not in s:s=s.replace(anchor,helper+anchor)
p.write_text(s)

t=Path('tools/v0527_regression_test.py')
r=t.read_text()
r=r.replace("need('append(BuildConfig.VERSION_NAME)' in main", "need('append(appVersionName())' in main and 'getPackageManager().getPackageInfo(getPackageName(),0).versionName' in main")
t.write_text(r)
