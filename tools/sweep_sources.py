#!/usr/bin/env python3
"""Проходит по всем источникам в Tadami и снимает состояние списка.

Для каждого источника: открыть, дождаться загрузки, собрать тексты и
скриншот, закрыть. Результат — JSON с вердиктом ok/empty/error.
"""

import json
import re
import subprocess
import sys
import time
from pathlib import Path

S = "127.0.0.1:5555"
OUT = Path("/tmp/sweep")
NAMES = [
    "AComics", "AllHentai", "AstraManga", "Com-X", "Desu", "HenChan", "HentaiLib",
    "InkStory", "Manga-shi", "MangaBuff", "MangaChan", "Mangahub", "MangaLib",
    "MangaLove", "MangaMen", "MangaPoisk", "MintManga", "NineGrid", "Nude-Moon",
    "PureManga", "ReadManga", "SeiManga", "SelfManga", "Senkognito", "Senkuro",
    "SlashLib", "Tomilo-lib", "UniComics", "Usagi", "WaManga", "YagamiProject",
    "YaoiChan",
]
CHROME = {
    "Популярное", "Последние", "Фильтр", "Лента", "Русский", "Все", "Обзор",
    "Главная", "Тайтлы", "Новое", "Больше", "Отмена", "Повторить", "Назад",
}


def sh(*args):
    for attempt in range(3):
        r = subprocess.run(["adb", "-s", S] + list(args), capture_output=True, text=True, timeout=120)
        if r.returncode == 0 and "not found" not in r.stderr:
            return r.stdout
        subprocess.run(["adb", "connect", S], capture_output=True, text=True)
        time.sleep(1)
    return ""


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/u.xml")
    return sh("shell", "cat", "/sdcard/u.xml")


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def rows(xml):
    result = []
    for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        text = m.group(1).strip()
        if not text:
            continue
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        result.append((text, (x1 + x2) // 2, (y1 + y2) // 2))
    return result


def main():
    OUT.mkdir(exist_ok=True)
    visited = set()
    report = []

    for _ in range(80):
        xml = dump()
        source = None
        for text, x, y in rows(xml):
            if text in NAMES and text not in visited:
                source = (text, x, y)
                break

        if source is None:
            sh("shell", "input", "swipe", "360", "950", "360", "350", "300")
            time.sleep(2)
            continue

        name, x, y = source
        visited.add(name)
        tap(x, y)
        time.sleep(7)
        page = dump()
        texts = [t for t, _, _ in rows(page)]
        shot = f"/sdcard/{abs(hash(name)) % 10**8}.png"
        sh("shell", "screencap", "-p", shot)
        subprocess.run(["adb", "-s", S, "pull", shot, str(OUT / f"{name.replace('/', '_')}.png")],
                       capture_output=True, text=True, timeout=120)

        if any("Нет результатов" in t for t in texts):
            verdict = "empty"
        elif any(k in text for text in texts for k in ("Ошибка", "Повторить", "Не удалось")):
            verdict = "error"
        else:
            candidates = [t for t in texts if t not in CHROME and len(t) > 8]
            verdict = "ok" if len(candidates) >= 5 else "suspicious"

        report.append({"source": name, "verdict": verdict, "texts": texts[:12]})
        print(name, verdict, flush=True)
        sh("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(3)

    (OUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    ok = sum(1 for r in report if r["verdict"] == "ok")
    print(f"total={len(report)} ok={ok}")


if __name__ == "__main__":
    sys.exit(main())
