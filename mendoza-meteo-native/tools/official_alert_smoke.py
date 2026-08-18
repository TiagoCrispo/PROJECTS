#!/usr/bin/env python3
"""Live contract smoke for official alert sources.

A day with zero active alerts is valid. TLS verification is never disabled. SMN CAP is
preferred; if its WMO-registered endpoint blocks automated clients, the smoke validates
the official ws2/ws1 channel used by the SMN web application instead.
"""
from __future__ import annotations

import json
import re
import ssl
import sys
import time
import urllib.error
import urllib.request

UA = "MendozaMeteoX10-contract/6-native-dev"
SMN_CAP = "https://ssl.smn.gob.ar/CAP/AR.php"
SMN_TOKEN = "https://ws2.smn.gob.ar/"
SMN_COORD = "https://ws1.smn.gob.ar/v1/georef/location/coord?lat=-32.896748&lon=-68.853418"
SMN_ALERT_BASE = "https://ws1.smn.gob.ar/v1/warning/alert/location"
MENDOZA_DCC = "https://www.contingencias.mendoza.gov.ar/alerta/"
JWT = re.compile(r"eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
TOKEN_PATTERNS = [
    re.compile(r"localStorage\.setItem\(['\"]token['\"]\s*,\s*['\"]([^'\"]+)['\"]"),
    re.compile(r"[\"']token[\"']\s*:\s*[\"']([^\"']+)[\"']"),
    re.compile(r"token\s*=\s*[\"']([^\"']+)[\"']"),
]


def fetch(url: str, attempts: int = 2, timeout: int = 15, headers: dict[str, str] | None = None) -> tuple[str, str, str]:
    last: Exception | None = None
    ctx = ssl.create_default_context()
    merged = {
        "User-Agent": UA,
        "Accept": "application/json,application/xml,text/xml,application/rss+xml,text/html;q=0.8,*/*;q=0.2",
    }
    if headers:
        merged.update(headers)
    for attempt in range(1, attempts + 1):
        request = urllib.request.Request(url, headers=merged)
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


def token_from_html(html: str) -> str:
    for pattern in TOKEN_PATTERNS:
        match = pattern.search(html)
        if match and match.group(1).startswith("eyJ"):
            return match.group(1)
    match = JWT.search(html)
    if match:
        return match.group(0)
    raise RuntimeError("temporary SMN web JWT not found")


def verify_smn_api() -> None:
    html, _, token_final = fetch(SMN_TOKEN)
    if not token_final.lower().startswith("https://"):
        raise RuntimeError(f"SMN token page redirected to non-HTTPS: {token_final}")
    token = token_from_html(html)
    auth = {"Authorization": f"JWT {token}", "Accept": "application/json"}
    coord_text, _, coord_final = fetch(SMN_COORD, headers=auth)
    location = json.loads(coord_text)
    location_id = location.get("id")
    if location_id is None:
        raise RuntimeError(f"SMN coord contract missing id: {coord_text[:300]!r}")
    alert_text, content_type, alert_final = fetch(f"{SMN_ALERT_BASE}/{location_id}", headers=auth)
    data = json.loads(alert_text)
    if not isinstance(data, dict) or "warnings" not in data or "reports" not in data:
        raise RuntimeError(f"SMN alert API contract changed: {alert_text[:500]!r}")
    if not coord_final.lower().startswith("https://") or not alert_final.lower().startswith("https://"):
        raise RuntimeError("SMN API redirected to non-HTTPS")
    print(
        "OFFICIAL_SMOKE_OK smn_api "
        f"location_id={location_id} warnings={len(data.get('warnings') or [])} "
        f"reports={len(data.get('reports') or [])} type={content_type!r}"
    )


def verify_smn() -> None:
    try:
        body, content_type, final_url = fetch(SMN_CAP)
        lower = body.lower()
        recognizable = any(token in lower for token in (
            "<alert", "urn:oasis:names:tc:emergency:cap", ".xml", "<rss", "<feed", "cap"
        ))
        if not body.strip() or not recognizable:
            raise RuntimeError(f"unrecognized SMN CAP payload type={content_type!r}")
        if not final_url.lower().startswith("https://"):
            raise RuntimeError(f"SMN CAP redirected to non-HTTPS URL: {final_url}")
        print(f"OFFICIAL_SMOKE_OK smn_cap bytes={len(body)} type={content_type!r} final={final_url}")
        return
    except Exception as cap_error:
        print(f"OFFICIAL_SMOKE_DEGRADED smn_cap {type(cap_error).__name__}: {cap_error}")
    verify_smn_api()


def verify_mendoza() -> None:
    body, content_type, final_url = fetch(MENDOZA_DCC, attempts=3, timeout=20)
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
