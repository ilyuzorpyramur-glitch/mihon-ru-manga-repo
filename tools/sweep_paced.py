#!/usr/bin/env python3
"""Размеренный обход источников Tadami: по одному, с перезапуском при сбое.

Приложение перезапускается только если оно упало или ушло с экрана,
поэтому обход не «сбивает» Tadami и не ловит краш в HomeHubTab.
"""

import json
import re
import subprocess
import sys
import time
from pathlib import Path

S = "127.0.0.1:5555"
OUT = Path("/tmp/paced")
LIST_MARK = "Доступные источники"


def sh(*args):
    for attempt in range(3):
        r = subprocess.run(["adb", "-s", S] + list(args), capture_output=True, text=True, timeout=120)
        if r.returncode == 0 and "not found" not in r.stderr:
            return r.stdout
        subprocess.run(["adb", "connect", S], capture_output=True, text=True, timeout=30)
        time.sleep(1 + attempt)
    return ""


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/u.xml")
    return sh("shell", "cat", "/sdcard/u.xml")


def texts(xml):
    out = []
    for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        value = m.group(1).strip()
        if value:
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            out.append((value, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def crashed(xml):
    return "Поделиться журналом сбоев" in xml or "Перезапустить приложение" in xml


def restart():
    sh("shell", "am", "force-stop", "com.tadami.aurora")
    time.sleep(3)
    sh("shell", "am", "start", "-n", "com.tadami.aurora/eu.kanade.tachiyomi.ui.main.MainActivity")
    time.sleep(12)


def goto_list():
    for _ in range(4):
        xml = dump()
        if LIST_MARK in [t for t, _, _ in texts(xml)]:
            return True
        if crashed(xml):
            restart()
            continue
        sh("shell", "input", "tap", "488", "1141")
        time.sleep(3)
        sh("shell", "input", "tap", "361", "254")
        time.sleep(3)
    return False


def scroll_top():
    for _ in range(8):
        sh("shell", "input", "swipe", "360", "400", "360", "1000", "200")
        time.sleep(1)


def open_source(name):
    for _ in range(14):
        xml = dump()
        if crashed(xml):
            restart()
            goto_list()
            continue
        for text, x, y in texts(xml):
            if text == name:
                sh("shell", "input", "tap", str(x), str(y))
                time.sleep(9)
                page = dump()
                page_texts = [t for t, _, _ in texts(page)]
                if any(t in ("Популярное", "Последние") for t in page_texts):
                    return page_texts
                return []
        sh("shell", "input", "swipe", "360", "950", "360", "400", "300")
        time.sleep(2)
    return []


def main():
    OUT.mkdir(exist_ok=True)
    names = sys.argv[1:]
    report = []
    restart()
    goto_list()

    for name in names:
        if not goto_list():
            restart()
            goto_list()
        scroll_top()
        page = open_source(name)

        if not page:
            verdict = "not-open"
        elif any("Нет результатов" in t for t in page):
            verdict = "empty"
        elif any(k in t for k in ("Ошибка", "Повторить", "Не удалось") for t in page):
            verdict = "error"
        else:
            verdict = "ok" if len([t for t in page if len(t) > 8]) >= 5 else "suspicious"

        sh("shell", "screencap", "-p", "/sdcard/p.png")
        subprocess.run(["adb", "-s", S, "pull", "/sdcard/p.png", str(OUT / f"{name}.png")],
                       capture_output=True, text=True, timeout=120)
        report.append({"source": name, "verdict": verdict, "texts": page[:8]})
        print(name, verdict, flush=True)

        sh("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(4)

    (OUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("ГОТОВО", len(report))


if __name__ == "__main__":
    sys.exit(main())
