#!/usr/bin/env python3
"""Доверяет все установленные расширения в Tadami через UI (adb, redroid)."""

import re
import subprocess
import sys
import time

SERIAL = "127.0.0.1:5555"
ADB = ["adb", "-s", SERIAL]


def _run(args):
    return subprocess.run(ADB + args, capture_output=True, text=True, timeout=90)


def sh(*args: str) -> str:
    for attempt in range(3):
        result = _run(list(args))
        if result.returncode == 0 and "not found" not in result.stderr:
            return result.stdout
        subprocess.run(["adb", "connect", SERIAL], capture_output=True, text=True, timeout=30)
        time.sleep(1 + attempt)
    return ""


def dump() -> str:
    sh("shell", "uiautomator", "dump", "/sdcard/u.xml")
    return sh("shell", "cat", "/sdcard/u.xml")


def nodes(xml: str):
    for match in re.finditer(r"<node[^>]*>", xml):
        tag = match.group(0)
        text = (re.search(r'text="([^"]*)"', tag) or [None, ""])[1]
        desc = (re.search(r'content-desc="([^"]*)"', tag) or [None, ""])[1]
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not bounds:
            continue
        x1, y1, x2, y2 = map(int, bounds.groups())
        yield text, desc, (x1, y1, x2, y2)


def tap(x: int, y: int) -> None:
    sh("shell", "input", "tap", str(x), str(y))


def main() -> int:
    trusted = 0
    empty_scrolls = 0
    for _ in range(200):
        xml = dump()
        untrusted = [b for t, d, b in nodes(xml) if t.strip() == "НЕНАДЁЖНОЕ"]
        if not untrusted:
            empty_scrolls += 1
            if empty_scrolls >= 4:
                break
            sh("shell", "input", "swipe", "360", "1000", "360", "600", "250")
            time.sleep(2)
            continue
        empty_scrolls = 0
        x1, y1, x2, y2 = untrusted[0]
        tap(640, (y1 + y2) // 2)
        time.sleep(2)
        dialog = dump()
        button = None
        for text, desc, bounds in nodes(dialog):
            if text.strip() == "Надёжное":
                bx1, by1, bx2, by2 = bounds
                button = ((bx1 + bx2) // 2, (by1 + by2) // 2)
                break
        if button is None:
            print("диалог доверия не найден, пропуск", file=sys.stderr)
            sh("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(1)
            continue
        tap(*button)
        trusted += 1
        time.sleep(2)
    print(f"trusted={trusted}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
