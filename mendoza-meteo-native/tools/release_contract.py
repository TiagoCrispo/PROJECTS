#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
SELF = Path(__file__).resolve()


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def verify_version() -> None:
    gradle = read("app/build.gradle")
    require(re.search(r"\bversionCode\s+64\b", gradle) is not None, "versionCode must be 64")
    require("versionName '6.4-native-dev'" in gradle, "versionName must be 6.4-native-dev")
    require("-Xlint:deprecation" in gradle, "Java deprecation audit must stay enabled")

    forbidden = (
        "6.3-native-dev", "versionCode 63",
        "6.2-native-dev", "versionCode 62",
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


def verify_ci_contract() -> None:
    workflow = (REPO / ".github/workflows/mendoza-meteo-native.yml").read_text(encoding="utf-8")
    require("actions/checkout@v7" in workflow, "CI checkout action must remain on Node-24 generation")
    require("android-actions/setup-android@v4" in workflow, "Android setup action must remain on Node 24")
    require("gradle/actions/setup-gradle@v6" in workflow, "Gradle setup action must remain on Node 24")
    require("cache-provider: basic" in workflow, "Gradle cache must remain on the open-source basic provider")
    require("--warning-mode all" in workflow, "Gradle deprecation audit must remain visible")
    require("versionCode='64'" in workflow, "APK CI contract must inspect versionCode 64")
    require("versionName='6.4-native-dev'" in workflow, "APK CI contract must inspect v6.4")


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
    verify_ci_contract()
    verify_no_signing_material()
    print("RELEASE_CONTRACT_OK version=6.4-native-dev code=64 widget=2x2 location_bound=true clock_skew_guard=true node24_ci=true background_location=false")


if __name__ == "__main__":
    main()
