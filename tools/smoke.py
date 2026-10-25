#!/usr/bin/env python3
"""Smoke-тест русских расширений без эмулятора: разбирает baseUrl и селекторы
из модуля, тянем страницы и проверяем, что контент реально есть.

Запуск: python3 tools/smoke.py [--json]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UA = (
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)


def module_dir(name: str) -> Path:
    return ROOT / "src" / "ru" / name


def read_sources(path: Path) -> str:
    return "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in path.rglob("*.kt"))


def base_url(src: str) -> str:
    for pattern in (
        r'baseUrl\s*=\s*"([^"]+)"',
        r'custom\("([^"]+)"\)',
        r'baseUrl\s*\{\s*custom\("([^"]+)"\)',
    ):
        match = re.search(pattern, src)
        if match:
            return match.group(1).rstrip("/")
    return ""


def fetch(url: str, timeout: int = 25) -> tuple[int, str]:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language": "ru,en;q=0.8",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read(400_000)
            return response.status, body.decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, ""
    except Exception as error:  # noqa: BLE001
        return 0, str(error)


def check(name: str) -> dict:
    directory = module_dir(name)
    gradle = directory / "build.gradle.kts"
    if not gradle.exists():
        return {"module": name, "status": "no-module"}
    gradle_text = gradle.read_text(encoding="utf-8")
    src = read_sources(directory)
    base = base_url(gradle_text) or base_url(src)
    if not base:
        return {"module": name, "status": "no-base-url"}

    result = {"module": name, "base": base}
    status, html = fetch(base)
    result["home"] = status
    if status != 200 or not html:
        result["status"] = "site-down"
        return result

    # Ищем признаки каталога: количество ссылок на карточки
    links = re.findall(r'href="(/[^"#]{3,80})"', html)
    result["links"] = len(set(links))
    result["title"] = (re.search(r"<title>([^<]{0,80})", html) or [None, ""])[1].strip()
    result["status"] = "alive" if len(set(links)) > 10 else "suspicious"
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    parser.add_argument("names", nargs="*")
    args = parser.parse_args()

    names = args.names or sorted(p.name for p in (ROOT / "src" / "ru").iterdir() if p.is_dir())
    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(check, names))

    if args.json:
        print(json.dumps(results, ensure_ascii=False, indent=2))
        return 0

    for item in results:
        print(
            f"{item['module']:<14} {item['status']:<11} "
            f"{item.get('home', '-'):<4} links={item.get('links', '-'):<4} {item.get('base', '')}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
