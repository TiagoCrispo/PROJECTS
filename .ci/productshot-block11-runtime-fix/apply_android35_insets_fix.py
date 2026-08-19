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
    out = text.encode("utf-8")
    path.write_bytes(out)
    print(f"before_sha256={before}")
    print(f"after_sha256={sha256(out)}")


if __name__ == "__main__":
    main()
