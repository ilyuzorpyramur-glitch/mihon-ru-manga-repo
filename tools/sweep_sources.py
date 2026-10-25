#!/usr/bin/env python3
"""Обход всех источников Tadami: открыть, проверить список, закрыть.

Проверка надёжная: считаем источник открытым только если на экране есть
чипы «Популярное/Последние/Фильтр». Вердикт: ok / empty / error / not-open.
"""

import json
import re
import subprocess
import sys
import time
from pathlib import Path

S = "127.0.0.1:5555"
OUT = Path("/tmp/sweep2")

NAMES = [
    "AComics", "AllHentai", "AstraManga", "Com-X", "Desu", "HenChan", "HentaiLib",
    "InkStory", "Manga-shi", "MangaBuff", "MangaChan", "Mangahub", "MangaLib",
    "MangaLove", "MangaMen", "MangaPoisk", "MintManga", "NineGrid", "Nude-Moon",
    "PureManga", "ReadManga", "SeiManga", "SelfManga", "Senkognito", "Senkuro",
    "SlashLib", "Tomilo-lib", "UniComics", "Usagi", "WaManga", "YagamiProject",
    "YaoiChan",
]


def sh(*args, retries=3):
    for attempt in range(retries):
        r = subprocess.run(["adb", "-s", S] + list(args), capture_output=True, text=True, timeout=120)
        if r.returncode == 0 and "not found" not in r.stderr:
            return r.stdout
        subprocess.run(["adb", "connect", S], capture_output=True, text=True, timeout=30)
        time.sleep(1 + attempt)
    return ""


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/u.xml")
    return sh("shell", "cat", "/sdcard/u.xml", retries=2)


def texts(xml):
    out = []
    for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        value = m.group(1).strip()
        if value:
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            out.append((value, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def ensure_app():
    """Возвращает приложение на экран «Обзор → Манга → Доступные источники»."""
    for attempt in range(3):
        xml = dump()
        names = [t for t, _, _ in texts(xml)]
        if "Доступные источники" in names:
            return True
        focus = sh("shell", "dumpsys window | grep -m1 mCurrentFocus")
        if "com.tadami.aurora" not in focus or "launcher" in focus:
            sh("shell", "am", "start", "-n", "com.tadami.aurora/eu.kanade.tachiyomi.ui.main.MainActivity")
            time.sleep(8)
        sh("shell", "input", "tap", "488", "1141")
        time.sleep(3)
        sh("shell", "input", "tap", "361", "254")
        time.sleep(3)
    return False


def back_to_list():
    for _ in range(4):
        xml = dump()
        names = [t for t, _, _ in texts(xml)]
        if "Доступные источники" in names:
            return True
        sh("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(2)
    return False


def open_source(name):
    for _ in range(16):
        xml = dump()
        for text, x, y in texts(xml):
            if text == name:
                sh("shell", "input", "tap", str(x), str(y))
                time.sleep(8)
                page = dump()
                page_texts = [t for t, _, _ in texts(page)]
                if any(t in ("Популярное", "Последние") for t in page_texts):
                    return page_texts
                return []
        sh("shell", "input", "swipe", "360", "950", "360", "350", "300")
        time.sleep(2)
    return []


def main():
    OUT.mkdir(exist_ok=True)
    report = []
    ensure_app()
    back_to_list()

    only = sys.argv[1:]
    for name in (only or NAMES):
        ensure_app()
        page = open_source(name)
        if not page:
            verdict = "not-open"
        elif any("Нет результатов" in t for t in page):
            verdict = "empty"
        elif any(k in t for k in ("Ошибка", "Повторить", "Не удалось") for t in page):
            verdict = "error"
        else:
            long_texts = [t for t in page if len(t) > 8]
            verdict = "ok" if len(long_texts) >= 5 else "suspicious"

        shot = OUT / f"{name.replace('/', '_')}.png"
        sh("shell", "screencap", "-p", "/sdcard/s.png")
        subprocess.run(["adb", "-s", S, "pull", "/sdcard/s.png", str(shot)],
                       capture_output=True, text=True, timeout=120)
        report.append({"source": name, "verdict": verdict, "texts": page[:10]})
        print(name, verdict, flush=True)

        if verdict != "not-open":
            sh("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(3)
        back_to_list()
        sh("shell", "input", "swipe", "360", "400", "360", "950", "200")
        time.sleep(1)

    (OUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    ok = sum(1 for r in report if r["verdict"] == "ok")
    print(f"ИТОГО {len(report)}: ok={ok}")


if __name__ == "__main__":
    sys.exit(main())
