#!/usr/bin/env python3
from pathlib import Path
import sys
R=Path(__file__).resolve().parents[1]
def t(p): return (R/p).read_text(encoding='utf-8')
def need(c,m):
    if not c: print('BLOCK7_FAIL:',m,file=sys.stderr); raise SystemExit(1)
b=t('app/build.gradle.kts'); gp=t('gradle.properties'); pro=t('app/proguard-rules.pro'); ci=t('.github/workflows/android-ci.yml'); ign=t('.gitignore'); bv=t('tools/build_and_verify.sh')
need('versionCode = 81' in b and 'versionName = "0.5.31"' in b,'version 0.5.31/81')
need('compileSdk = 36' in b and 'targetSdk = 36' in b and 'buildToolsVersion = "35.0.0"' in b,'Android SDK contract')
need('isMinifyEnabled = true' in b and 'isShrinkResources = true' in b,'release R8/shrink enabled')
need('proguard-android-optimize.txt' in b,'optimized ProGuard config')
need('android.r8.optimizedResourceShrinking=true' in gp,'optimized resource shrinking flag')
need('-keepattributes SourceFile,LineNumberTable' in pro,'retrace metadata')
for env in ('WA_VAULT_KEYSTORE_FILE','WA_VAULT_KEYSTORE_PASSWORD','WA_VAULT_KEY_ALIAS','WA_VAULT_KEY_PASSWORD'):
    need(env in b,'env-driven signing '+env)
need('storePassword = "' not in b and 'keyPassword = "' not in b,'no hard-coded signing password')
for bad in ('*.jks','*.keystore','*.apk','*.aab','*.dex','*.class'):
    need(bad in ign,'gitignore '+bad)
for action in ('actions/checkout@v4','actions/setup-java@v4','gradle/actions/setup-gradle@v4','actions/upload-artifact@v4'):
    need(action in ci,'CI action '+action)
need('gradle-version: "8.13"' in ci,'CI Gradle 8.13')
need('platforms;android-36' in ci and 'build-tools;35.0.0' in ci,'CI SDK packages')
need('tools/build_and_verify.sh' in ci and 'tools/verify_reproducible_release.sh' in ci,'CI validation/repro jobs')
for artifact in ('assembleRelease','bundleRelease','mapping.txt','lintRelease'):
    need(artifact in bv,'build verifier '+artifact)
for p in R.rglob('*'):
    if p.is_file() and p.suffix.lower() in {'.apk','.aab','.dex','.class','.jks','.keystore','.p12','.pfx','.pem','.key'}:
        need(False,'forbidden source artifact '+str(p.relative_to(R)))
print('v0.5.31 BLOCK7 release regression PASS')
