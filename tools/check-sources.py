#!/usr/bin/env python3
"""Проверка работоспособности источников репозитория.

Читает index.json, опрашивает базовый URL каждого источника и печатает
сводку: OK / CLOUDFLARE / DDOS-GUARD / HTTP-ошибка / НЕТ DNS.

Запуск:  python3 tools/check-sources.py [--json out.json] [--workers 12]
"""
import argparse
import json
import socket
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")

BLOCK_MARKERS = {
    "cloudflare": ("just a moment", "cf-browser-verification", "cf_chl_opt"),
    "ddos-guard": ("ddos-guard", "ddos guard"),
    "qrator": ("qrator", ),
}


def classify(code: str, body: str, err: str) -> str:
    if err:
        return "НЕТ DNS" if "Name or service not known" in err else "НЕДОСТУПЕН"
    low = body[:6000].lower()
    for name, markers in BLOCK_MARKERS.items():
        if any(m in low for m in markers):
            return name.upper()
    if code.startswith("5"):
        return "ОШИБКА СЕРВЕРА"
    if code.startswith("4"):
        return "HTTP " + code
    return "OK"


def probe(item):
    name, url = item
    host = urlparse(url).hostname or ""
    try:
        socket.getaddrinfo(host, 443)
    except OSError as e:
        return {"name": name, "url": url, "status": "НЕТ DNS", "code": "", "detail": str(e)}
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ru,en;q=0.8"})
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            body = r.read(20000).decode("utf-8", "replace")
            return {"name": name, "url": url, "status": classify(str(r.status), body, ""),
                    "code": str(r.status), "final": r.geturl(), "detail": ""}
    except urllib.error.HTTPError as e:
        body = e.read(20000).decode("utf-8", "replace")
        return {"name": name, "url": url, "status": classify(str(e.code), body, ""),
                "code": str(e.code), "final": e.geturl(), "detail": e.reason or ""}
    except Exception as e:  # noqa: BLE001
        return {"name": name, "url": url, "status": "НЕДОСТУПЕН", "code": "", "detail": str(e)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--index", default="index.json")
    ap.add_argument("--json", default="")
    ap.add_argument("--workers", type=int, default=12)
    a = ap.parse_args()

    data = json.load(open(a.index, encoding="utf-8"))
    targets = []
    meta = {}
    for ext in data:
        for s in ext.get("sources", []):
            targets.append((f"{ext['name']} ({s['lang']})", s["baseUrl"]))
            meta[(f"{ext['name']} ({s['lang']})")] = ext

    with ThreadPoolExecutor(max_workers=a.workers) as pool:
        results = list(pool.map(probe, targets))

    width = max(len(r["name"]) for r in results) + 2
    bad = 0
    for r in sorted(results, key=lambda x: (x["status"] != "OK", x["name"])):
        if r["status"] not in ("OK",):
            bad += 1
        print(f"{r['name']:<{width}} {r['status']:<14} {r['code']:<4} {r['url']}")
    print(f"\nВсего источников: {len(results)} · требуют внимания: {bad}")

    if a.json:
        json.dump(results, open(a.json, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print("JSON ->", a.json)
    return 0


if __name__ == "__main__":
    sys.exit(main())
