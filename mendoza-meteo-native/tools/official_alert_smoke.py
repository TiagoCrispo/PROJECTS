#!/usr/bin/env python3
"""Live contract smoke for official alert sources.

A day with zero active alerts is valid. TLS verification is never disabled. Direct SMN
origins are preferred. Some cloud/datacenter IPs are intentionally blocked by SMN with
HTTP 403; in that case this gate verifies the WMO Alerting Authority registration and
probes the WMO SWIC aggregate independently. The output explicitly remains RESTRICTED,
never pretending that an SMN alert payload was fetched when it was not.
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
WMO_REGISTRY = "https://alertingauthority.wmo.int/authorities.php?recId=4"
WMO_SWIC_ALL = "https://severeweather.wmo.int/v2/json/all.json"
MENDOZA_DCC = "https://www.contingencias.mendoza.gov.ar/alerta/"
JWT = re.compile(r"eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
TOKEN_PATTERNS = [
    re.compile(r"localStorage\.setItem\(['\"]token['\"]\s*,\s*['\"]([^'\"]+)['\"]"),
    re.compile(r"[\"']token[\"']\s*:\s*[\"']([^\"']+)[\"']"),
    re.compile(r"token\s*=\s*[\"']([^\"']+)[\"']"),
]


class FetchFailure(RuntimeError):
    def __init__(self, url: str, last: Exception):
        super().__init__(f"official source unavailable: {url}: {last}")
        self.url = url
        self.last = last
        self.status = getattr(last, "code", None)


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
    assert last is not None
    raise FetchFailure(url, last)


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


def verify_wmo_registration() -> None:
    body, content_type, final_url = fetch(WMO_REGISTRY, attempts=3, timeout=20)
    lower = body.lower()
    required = ("argentina", "servicio meteorologico nacional", "ssl.smn.gob.ar/cap/ar.php")
    if not all(token in lower for token in required):
        preview = re.sub(r"\s+", " ", body[:1200])
        raise RuntimeError(f"WMO Argentina authority record changed: {preview!r}")
    if not final_url.lower().startswith("https://"):
        raise RuntimeError("WMO registry redirected to non-HTTPS")
    print(f"OFFICIAL_SMOKE_OK wmo_registry bytes={len(body)} type={content_type!r}")


def probe_wmo_swic() -> None:
    try:
        body, content_type, final_url = fetch(WMO_SWIC_ALL, attempts=2, timeout=20)
        data = json.loads(body)
        if not isinstance(data, (dict, list)):
            raise RuntimeError("WMO SWIC aggregate is not JSON object/list")
        if not final_url.lower().startswith("https://"):
            raise RuntimeError("WMO SWIC aggregate redirected to non-HTTPS")
        serialized = json.dumps(data, ensure_ascii=False).lower()
        argentina_present = "argentina" in serialized or "ar-smn" in serialized or "smn.gob.ar" in serialized
        shape = list(data.keys())[:12] if isinstance(data, dict) else f"list[{len(data)}]"
        print(
            "OFFICIAL_SMOKE_INFO wmo_swic "
            f"bytes={len(body)} type={content_type!r} shape={shape!r} argentina_active={argentina_present}"
        )
    except Exception as error:
        # SWIC aggregate is supplemental: the authoritative registry is the contract gate.
        print(f"OFFICIAL_SMOKE_INFO wmo_swic_unavailable {type(error).__name__}: {error}")


def verify_smn() -> None:
    cap_error: Exception | None = None
    api_error: Exception | None = None
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
    except Exception as error:
        cap_error = error
        print(f"OFFICIAL_SMOKE_DEGRADED smn_cap {type(error).__name__}: {error}")

    try:
        verify_smn_api()
        return
    except Exception as error:
        api_error = error
        print(f"OFFICIAL_SMOKE_DEGRADED smn_api {type(error).__name__}: {error}")

    # Only classify this as an environmental restriction when both direct official
    # entry points explicitly returned HTTP 403. Other failures still fail the gate.
    cap_403 = isinstance(cap_error, FetchFailure) and cap_error.status == 403
    api_403 = isinstance(api_error, FetchFailure) and api_error.status == 403
    if not (cap_403 and api_403):
        raise RuntimeError(f"SMN direct contracts failed unexpectedly: CAP={cap_error}; API={api_error}")

    verify_wmo_registration()
    probe_wmo_swic()
    print("OFFICIAL_SMOKE_RESTRICTED smn_origin_http403_from_github_runner=true payload_live_tested=false")


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
    failures: list[str] = []
    for name, check in (("smn", verify_smn), ("mendoza_dcc", verify_mendoza)):
        try:
            check()
        except Exception as exc:
            failures.append(f"{name}: {type(exc).__name__}: {exc}")
            print(f"OFFICIAL_SMOKE_FAIL_SOURCE {failures[-1]}", file=sys.stderr)
    if failures:
        raise RuntimeError("; ".join(failures))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"OFFICIAL_SMOKE_FAIL {type(exc).__name__}: {exc}", file=sys.stderr)
        raise
