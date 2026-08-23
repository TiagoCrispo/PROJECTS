#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import pathlib
import sys

EXPECTED_BEFORE_SHA256 = "2003cc4bb5a368f1d962d20060096153e6bf574c9c2f7de7e3242045c8505367"

OLD_WINDOW = '''    private void configureWindow() {
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(Color.rgb(250, 248, 245));
            getWindow().setNavigationBarColor(Color.rgb(250, 248, 245));
        }
        getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(appearance, appearance);
        } else {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }
'''

NEW_WINDOW = '''    private void configureWindow() {
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(Color.rgb(250, 248, 245));
            getWindow().setNavigationBarColor(Color.rgb(250, 248, 245));
        }
        getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT < 30) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void configureSystemBarAppearanceAfterDecor() {
        if (Build.VERSION.SDK_INT < 30) return;
        int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        View decor = getWindow().getDecorView();
        WindowInsetsController controller = decor.getWindowInsetsController();
        if (controller != null) controller.setSystemBarsAppearance(appearance, appearance);
    }
'''

OLD_CONTENT = '''        setContentView(scroller);
        installSystemBarInsets();
'''

NEW_CONTENT = '''        setContentView(scroller);
        configureSystemBarAppearanceAfterDecor();
        installSystemBarInsets();
'''

OLD_API_DECL = '''    private static final String DEFAULT_API_BASE = BuildConfig.API_BASE_URL;
'''
NEW_API_DECL = '''    private static final String DEFAULT_API_BASE = BuildConfig.API_BASE_URL;
    private static final int MAX_CREATE_NETWORK_FAILURES = 3;
'''

OLD_START = '''    private void startJob() {
        if (selectedUri == null || !createInFlight.compareAndSet(false, true)) return;
        primary.setEnabled(false);
'''
NEW_START = '''    private void startJob() {
        if (selectedUri == null || !createInFlight.compareAndSet(false, true)) return;
        // Internal RC builds deliberately use a non-routable .invalid backend. Fail fast instead
        // of presenting fake progress/retry forever. Production builds inject a real HTTPS API.
        if (DEFAULT_API_BASE.contains(".invalid")) {
            clearPendingCreatePrefs();
            networkFailures = 0;
            createInFlight.set(false);
            showError(getString(R.string.processing_failed));
            return;
        }
        primary.setEnabled(false);
'''

OLD_NETWORK_CATCH = '''                } catch (Exception networkError) {
                    // Keep the idempotency key. If the server accepted the upload but the response
                    // was lost, this retry returns the already-created job instead of billing twice.
                    networkFailures++;
                    long delay = Math.min(8000L, 1200L * (1L << Math.min(3, networkFailures - 1)));
                    main.post(() -> {
                        if (destroyed || selectedUri == null) return;
                        subtitle.setText(R.string.connection_retrying);
                        main.postDelayed(this::startJob, delay);
                    });
                }
'''
NEW_NETWORK_CATCH = '''                } catch (Exception networkError) {
                    // Keep the idempotency key. If the server accepted the upload but the response
                    // was lost, a manual retry can still recover the same job without double-billing.
                    // Automatic retries are bounded so an unreachable backend can never trap the UI.
                    networkFailures++;
                    if (networkFailures >= MAX_CREATE_NETWORK_FAILURES) {
                        markCreateAttemptStoppedKeepKey();
                        main.post(() -> {
                            if (!destroyed) showError(getString(R.string.processing_failed));
                        });
                    } else {
                        long delay = Math.min(8000L, 1200L * (1L << Math.min(3, networkFailures - 1)));
                        main.post(() -> {
                            if (destroyed || selectedUri == null) return;
                            subtitle.setText(R.string.connection_retrying);
                            main.postDelayed(this::startJob, delay);
                        });
                    }
                }
'''


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def replace_exactly_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_android35_insets_fix.py <MainActivity.java>")
    path = pathlib.Path(sys.argv[1])
    raw = path.read_bytes()
    before = sha256(raw)
    if before != EXPECTED_BEFORE_SHA256:
        raise SystemExit(
            f"refusing to transform unexpected MainActivity.java: {before} != {EXPECTED_BEFORE_SHA256}"
        )
    text = raw.decode("utf-8")
    text = replace_exactly_once(text, OLD_WINDOW, NEW_WINDOW, "configureWindow")
    text = replace_exactly_once(text, OLD_CONTENT, NEW_CONTENT, "setContentView")
    text = replace_exactly_once(text, OLD_API_DECL, NEW_API_DECL, "API declaration")
    text = replace_exactly_once(text, OLD_START, NEW_START, "startJob fail-fast")
    text = replace_exactly_once(text, OLD_NETWORK_CATCH, NEW_NETWORK_CATCH, "bounded create retry")
    out = text.encode("utf-8")
    after = sha256(out)
    path.write_bytes(out)
    print(f"before_sha256={before}")
    print(f"after_sha256={after}")
    print("android35_system_bars_fix=true")
    print("bounded_create_network_failures=3")
    print("invalid_backend_fail_fast=true")


if __name__ == "__main__":
    main()
