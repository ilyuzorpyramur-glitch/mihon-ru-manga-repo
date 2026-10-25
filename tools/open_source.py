#!/usr/bin/env python3
"""Открывает источник в Tadami по имени (adb, redroid).

Использование: python3 tools/open_source.py "MangaLove" [shot.png]
"""

import re
import subprocess
import sys
import time

S = "127.0.0.1:5555"


def sh(*args):
    for attempt in range(3):
        r = subprocess.run(["adb", "-s", S] + list(args), capture_output=True, text=True, timeout=90)
        if r.returncode == 0 and "not found" not in r.stderr:
            return r.stdout
        subprocess.run(["adb", "connect", S], capture_output=True, text=True)
        time.sleep(1)
    return ""


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/u.xml")
    return sh("shell", "cat", "/sdcard/u.xml")


def main() -> int:
    name = sys.argv[1]
    shot = sys.argv[2] if len(sys.argv) > 2 else ""
    for _ in range(16):
        xml = dump()
        for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
            if m.group(1).strip() == name:
                x = (int(m.group(2)) + int(m.group(4))) // 2
                y = (int(m.group(3)) + int(m.group(5))) // 2
                sh("shell", "input", "tap", str(x), str(y))
                time.sleep(9)
                if shot:
                    sh("shell", "screencap", "-p", "/sdcard/shot.png")
                    subprocess.run(["adb", "-s", S, "pull", "/sdcard/shot.png", shot],
                                   capture_output=True, text=True, timeout=120)
                print("opened", name)
                return 0
        sh("shell", "input", "swipe", "360", "950", "360", "350", "300")
        time.sleep(2)
    print("not found", name)
    return 1


if __name__ == "__main__":
    sys.exit(main())
