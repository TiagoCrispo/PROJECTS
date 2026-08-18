#!/usr/bin/env python3
"""Live contract smoke for official alert sources.

This deliberately validates availability/shape only. A day with zero active alerts is valid.
It never disables TLS verification and never requires a specific alert to exist.
"""
from __future__ import annotations

import ssl
import sys
import time
import urllib.error
import urllib.request

UA = "MendozaMeteoX10-contract/6-native-dev"
SMN_CAP = "https://ssl.smn.gob.ar/CAP/AR.php"
MENDOZA_DCC = "https://www.contingencias.mendoza.gov.ar/alerta/"


def fetch(url: str, attempts: int = 2, timeout: int = 15) -> tuple[str, str, str]:
    last: Exception | None = None
    ctx = ssl.create_default_context()
    for attempt in range(1, attempts + 1):
        request = urllib.request.Request(
            url,
            headers={
                "User-Agent": UA,
                "Accept": "application/xml,text/xml,application/rss+xml,text/html;q=0.8,*/*;q=0.2",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout, context=ctx) as response:
                body = response.read(2 * 1024 * 1024 + 1)
                if len(body) > 2 * 1024 * 1024:
                    raise RuntimeError(f"response too large from {url}")
                text = body.decode("utf-8", errors="replace")
                return text, response.headers.get("Content-Type", ""), response.geturl()
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as exc:
            last = exc
            if attempt < attempts:
                time.sleep(attempt)
    raise RuntimeError(f"official source unavailable: {url}: {last}")


def verify_smn() -> None:
    body, content_type, final_url = fetch(SMN_CAP)
    lower = body.lower()
    recognizable = any(token in lower for token in (
        "<alert", "urn:oasis:names:tc:emergency:cap", ".xml", "<rss", "<feed", "cap"
    ))
    if not body.strip() or not recognizable:
        preview = body[:400].replace("\n", " ")
        raise RuntimeError(
            f"SMN CAP endpoint returned unrecognized payload; content-type={content_type!r}; preview={preview!r}"
        )
    if not final_url.lower().startswith("https://"):
        raise RuntimeError(f"SMN CAP redirected to non-HTTPS URL: {final_url}")
    print(f"OFFICIAL_SMOKE_OK smn_cap bytes={len(body)} type={content_type!r} final={final_url}")


def verify_mendoza() -> None:
    body, content_type, final_url = fetch(MENDOZA_DCC)
    lower = body.lower()
    if not body.strip() or not any(token in lower for token in ("mendoza", "contingencia", "alerta", "meteor")):
        preview = body[:400].replace("\n", " ")
        raise RuntimeError(
            f"Mendoza official alert page returned unrecognized payload; content-type={content_type!r}; preview={preview!r}"
        )
    if not final_url.lower().startswith("https://"):
        raise RuntimeError(f"Mendoza official page redirected to non-HTTPS URL: {final_url}")
    print(f"OFFICIAL_SMOKE_OK mendoza_dcc bytes={len(body)} type={content_type!r} final={final_url}")


def main() -> int:
    verify_smn()
    verify_mendoza()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"OFFICIAL_SMOKE_FAIL {type(exc).__name__}: {exc}", file=sys.stderr)
        raise
