#!/usr/bin/env python3
from __future__ import annotations

import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
SELF = Path(__file__).resolve()
WRAPPER_SHA256 = "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def verify_version() -> None:
    gradle = read("app/build.gradle")
    require(re.search(r"\bversionCode\s*=\s*68\b", gradle) is not None, "versionCode must be 68")
    require("versionName = '6.8-native-dev'" in gradle, "versionName must be 6.8-native-dev")
    require("-Xlint:all,-deprecation" in gradle,
            "framework deprecations must stay isolated while classfile warnings remain audited")
    require("-classfile" not in gradle,
            "classfile warnings must not be globally suppressed")
    require("-Werror" in gradle, "Java warnings must fail compilation")
    require("compileOnly 'androidx.room:room-common:2.7.0'" in gradle,
            "WorkManager Room annotation metadata must be present on the compile classpath")
    require("buildConfig = true" in gradle, "BuildConfig generation must remain enabled for synchronized User-Agent versioning")

    weather_exception = read("app/src/main/java/com/mendozameteo/x10/WeatherException.java")
    require("serialVersionUID" in weather_exception, "WeatherException must not emit serialization warnings")

    forbidden = (
        "6.7-native-dev", "versionCode 67", "versionCode = 67", "v6.7",
        "6.6-native-dev", "versionCode 66", "versionCode = 66", "v6.6",
        "6.5-native-dev", "versionCode 65", "versionCode = 65", "v6.5",
        "6.4-native-dev", "versionCode 64", "versionCode = 64", "v6.4",
        "6.3-native-dev", "versionCode 63", "versionCode = 63", "v6.3",
        "6.2-native-dev", "versionCode 62", "versionCode = 62", "v6.2",
        "MendozaMeteoX10/6-native-dev",
    )
    offenders: list[str] = []
    for path in ROOT.rglob("*"):
        if path.resolve() == SELF:
            continue
        if not path.is_file() or path.suffix.lower() not in {".java", ".xml", ".gradle", ".md", ".py", ".properties", ".yml", ".yaml"}:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for token in forbidden:
            if token in text:
                offenders.append(f"{path.relative_to(ROOT)}: {token}")
    require(not offenders, "stale version references found: " + "; ".join(offenders))


def verify_manifest() -> None:
    manifest = read("app/src/main/AndroidManifest.xml")
    for permission in (
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
    ):
        require(permission in manifest, f"missing required permission {permission}")

    for forbidden in (
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.USE_EXACT_ALARM",
    ):
        require(forbidden not in manifest, f"forbidden permission present: {forbidden}")

    require('android:usesCleartextTraffic="false"' in manifest, "cleartext traffic must remain disabled")
    require('android:name=".LauncherActivity"' in manifest, "LauncherActivity missing")
    require('android:name=".MainActivity"' in manifest, "MainActivity missing")
    require('android:name=".WeatherWidgetProvider"' in manifest, "WeatherWidgetProvider missing")


def verify_widget_contract() -> None:
    info = read("app/src/main/res/xml/weather_widget_info.xml")
    require('android:targetCellWidth="2"' in info, "widget target width must remain 2 cells")
    require('android:targetCellHeight="2"' in info, "widget target height must remain 2 cells")
    require('android:minWidth="110dp"' in info, "widget minWidth contract changed")
    require('android:minHeight="110dp"' in info, "widget minHeight contract changed")
    require('android:updatePeriodMillis="1800000"' in info, "widget periodic update must remain 30 min")

    layout = read("app/src/main/res/layout/weather_widget.xml")
    for view_id in ("current_temp", "day1", "day7", "temp1", "temp7", "rain1", "rain7"):
        require(f"@+id/{view_id}" in layout, f"widget view missing: {view_id}")

    provider = read("app/src/main/java/com/mendozameteo/x10/WeatherWidgetProvider.java")
    require("NotificationLocation.load" in provider, "widget must use background-safe stored location policy")
    require("WidgetContextPolicy.forecastCacheKey" in provider, "widget must separate local/UTN repository cache")
    require("WidgetContextPolicy.sameContext" in provider, "widget cache must be location-bound")
    require('PREFS = "widget_cache_v7"' in provider, "widget cache schema must be v7")
    require("LocationManager" not in provider, "widget must not query device location in background")
    require("getLastKnownLocation" not in provider, "widget must not query last-known location directly")


def verify_background_contract() -> None:
    scheduler = read("app/src/main/java/com/mendozameteo/x10/NotificationScheduler.java")
    require("30, TimeUnit.MINUTES" in scheduler, "notification periodic interval must remain 30 minutes")
    require("NetworkType.CONNECTED" in scheduler, "notification worker must require connectivity")
    require("ExistingPeriodicWorkPolicy.UPDATE" in scheduler, "periodic work must update in place")

    worker = read("app/src/main/java/com/mendozameteo/x10/NotificationUpdateWorker.java")
    require("NotificationLocation.load" in worker, "notification worker must use stored location")
    require("point.personalized ? \"local\" : \"utn\"" in worker,
            "notification worker must isolate local and UTN caches")

    notification_location = read("app/src/main/java/com/mendozameteo/x10/NotificationLocation.java")
    location_resolver = read("app/src/main/java/com/mendozameteo/x10/LocationResolver.java")
    location_policy = read("app/src/main/java/com/mendozameteo/x10/LocationPolicy.java")
    require("wallClockAgeMillis(savedAt, nowMillis)" in notification_location,
            "background location must reject invalid persisted timestamps")
    require("wallClockAgeMillis(savedAt, now)" in location_resolver,
            "foreground saved location must reject invalid persisted timestamps")
    require("wallClockAgeMillis(location.getTime(), nowWall)" in location_resolver,
            "device wall-clock location fallback must reject invalid future timestamps")
    require("MAX_FUTURE_SKEW_MILLIS" in location_policy,
            "location clock-skew policy missing")


def verify_freshness_contract() -> None:
    freshness = read("app/src/main/java/com/mendozameteo/x10/ForecastFreshness.java")
    require("MAX_FUTURE_SKEW_MILLIS" in freshness,
            "forecast cache must defend against impossible future timestamps")
    require("fetchedAtMillis > nowMillis + MAX_FUTURE_SKEW_MILLIS" in freshness,
            "forecast future-timestamp rejection missing")


def verify_user_agent_contract() -> None:
    json_transport = read("app/src/main/java/com/mendozameteo/x10/HttpJsonTransport.java")
    text_transport = read("app/src/main/java/com/mendozameteo/x10/HttpTextTransport.java")
    provider_smoke = read("tools/provider_smoke.py")
    require("BuildConfig.VERSION_NAME" in json_transport,
            "weather User-Agent must derive its version from BuildConfig")
    require("BuildConfig.VERSION_NAME" in text_transport,
            "official User-Agent must derive its version from BuildConfig")
    require("app/build.gradle" in provider_smoke and "versionName" in provider_smoke,
            "provider smoke must derive its version from app/build.gradle")


def verify_wrapper_contract() -> None:
    gradlew = ROOT / "gradlew"
    bat = ROOT / "gradlew.bat"
    jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    props_path = ROOT / "gradle/wrapper/gradle-wrapper.properties"
    for path in (gradlew, bat, jar, props_path):
        require(path.is_file(), f"missing Gradle Wrapper file: {path.relative_to(ROOT)}")
    require(os.access(gradlew, os.X_OK), "gradlew must be executable in Git")
    props = props_path.read_text(encoding="utf-8")
    require("gradle-9.5.0-bin.zip" in props, "wrapper must remain pinned to Gradle 9.5.0")
    require(f"distributionSha256Sum={WRAPPER_SHA256}" in props,
            "Gradle 9.5.0 distribution checksum changed")
    require("validateDistributionUrl=true" in props, "wrapper distribution URL validation must remain enabled")


def verify_ci_contract() -> None:
    workflow_path = REPO / ".github/workflows/mendoza-meteo-native.yml"
    workflow = workflow_path.read_text(encoding="utf-8")
    require("actions/checkout@v7" in workflow, "CI checkout action must remain on Node-24 generation")
    require("android-actions/setup-android@v4" in workflow, "Android setup action must remain on Node 24")
    require("gradle/actions/setup-gradle@v6" in workflow, "Gradle setup action must remain on Node 24")
    require("actions/upload-artifact@v7" in workflow, "CI must publish the installable test APK with the current artifact action")
    require("cache-provider: basic" in workflow, "Gradle cache must remain on the open-source basic provider")
    require("--warning-mode fail" in workflow, "Gradle warnings/deprecations must fail CI")
    require("./gradlew" in workflow, "CI must build through the committed Gradle Wrapper")
    require("gradle-version:" not in workflow, "CI must not provision a separate global Gradle version")
    require("wrapper-bootstrap:" not in workflow, "one-shot wrapper bootstrap job must be removed")
    require("versionCode='68'" in workflow, "APK CI contract must inspect versionCode 68")
    require("versionName='6.8-native-dev'" in workflow, "release APK CI contract must inspect v6.8")
    require("versionName='6.8-native-dev-debug'" in workflow, "debug APK CI contract must inspect v6.8 debug build")
    require("MendozaMeteo-X10-v6.8-TEST" in workflow, "installable test artifact name must stay synchronized")
    require(not (REPO / ".github/workflows/bootstrap-gradle-wrapper.yml").exists(),
            "temporary wrapper bootstrap workflow must be deleted")


def verify_no_signing_material() -> None:
    forbidden_suffixes = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"}
    offenders = [str(p.relative_to(ROOT)) for p in ROOT.rglob("*") if p.is_file() and p.suffix.lower() in forbidden_suffixes]
    require(not offenders, "signing/private-key material must not be committed: " + ", ".join(offenders))


def main() -> None:
    verify_version()
    verify_manifest()
    verify_widget_contract()
    verify_background_contract()
    verify_freshness_contract()
    verify_user_agent_contract()
    verify_wrapper_contract()
    verify_ci_contract()
    verify_no_signing_material()
    print("RELEASE_CONTRACT_OK version=6.8-native-dev code=68 warnings_are_errors=true compatibility_deprecations_isolated=true third_party_classfile_metadata_resolved=true wrapper=gradle-9.5.0 checksum_locked=true user_agent_synced=true widget=2x2 location_bound=true clock_skew_guard=true node24_ci=true background_location=false test_apk_artifact=true")


if __name__ == "__main__":
    main()
