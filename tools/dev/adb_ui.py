#!/usr/bin/env python3
"""Tiny uiautomator driver for a device or emulator: tap by resource-id (Compose test tag), type, swipe, shoot.

Compose test tags are exposed as resource ids (MainActivity sets testTagsAsResourceId), so every interactive element
can be driven from adb. Set ANDROID_SERIAL to pick a device when more than one is connected.

Usage: tools/dev/adb_ui.py <cmd> [args...]; commands can be chained with ' -- '.
  tap <id> [nth]       tap the element whose resource-id == id (or startswith id when it ends with *)
  has <id>             exit 0 if the element is on screen, 1 otherwise
  text <id>            print the text / content-desc under the element (its own or its children's)
  taptext <text>       tap the element whose text/content-desc == text
  findtap <text>       tap the first element whose text/content-desc contains text, scrolling down to find it
  type <text>          input text (spaces ok)
  key <keycode>        e.g. BACK, ENTER, MEDIA_NEXT
  swipe x1 y1 x2 y2 [ms]
  drag <id> dy [ms]    press the element's centre and move it dy px (slow swipe: drag handles, sheets)
  shot <path>          screenshot
  ids                  list resource-ids on screen with their bounds and text
  wait <seconds>
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = [os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")), "platform-tools", "adb")]
if os.environ.get("ANDROID_SERIAL"):
    ADB += ["-s", os.environ["ANDROID_SERIAL"]]


def adb(*args, capture=False):
    r = subprocess.run(ADB + list(args), capture_output=True)
    return r.stdout if capture else None


def dump():
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", capture=True)
        raw = adb("shell", "cat", "/sdcard/ui.xml", capture=True)
        try:
            return ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(0.5)
    raise SystemExit("could not dump UI")


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def id_pred(rid):
    if rid.endswith("*"):
        return lambda n: (n.get("resource-id") or "").startswith(rid[:-1])
    return lambda n: n.get("resource-id") == rid


def find(pred, nth=0):
    nodes = [n for n in dump().iter("node") if pred(n)]
    return nodes[nth] if len(nodes) > nth else None


def wait_for(pred, nth=0, tries=10):
    for _ in range(tries):
        n = find(pred, nth)
        if n is not None:
            return n
        time.sleep(0.6)
    return None


def all_text(node):
    out = []
    for n in node.iter("node"):
        t = n.get("text") or n.get("content-desc")
        if t:
            out.append(t)
    return " | ".join(out)


def run(cmd, args):
    if cmd == "tap":
        rid = args[0]
        n = wait_for(id_pred(rid), int(args[1]) if len(args) > 1 else 0)
        if n is None:
            raise SystemExit(f"not found: {rid}")
        adb("shell", "input", "tap", *map(str, center(n)))
        return
    if cmd == "has":
        sys.exit(0 if find(id_pred(args[0])) is not None else 1)
    if cmd == "text":
        n = wait_for(id_pred(args[0]), tries=4)
        print(all_text(n) if n is not None else "")
        return
    if cmd == "taptext":
        t = " ".join(args)
        n = wait_for(lambda n: n.get("text") == t or n.get("content-desc") == t)
        if n is None:
            raise SystemExit(f"not found: {t}")
        adb("shell", "input", "tap", *map(str, center(n)))
        return
    if cmd == "findtap":
        t = " ".join(args)
        for _ in range(8):
            root = dump()
            # Rows under the mini-player are in the tree but not tappable: scroll them up first.
            limit = 10**6
            for m in root.iter("node"):
                if m.get("resource-id") == "mini_player":
                    limit = int(re.findall(r"\d+", m.get("bounds"))[1])
            hits = [
                n for n in root.iter("node")
                if (t in (n.get("content-desc") or "") or t in (n.get("text") or "")) and center(n)[1] < limit - 10
            ]
            if hits:
                adb("shell", "input", "tap", *map(str, center(hits[0])))
                return
            adb("shell", "input", "swipe", "360", "900", "360", "500", "300")
            time.sleep(0.8)
        raise SystemExit(f"not found: {t}")
    if cmd == "type":
        adb("shell", "input", "text", " ".join(args).replace(" ", "%s"))
        return
    if cmd == "key":
        adb("shell", "input", "keyevent", "KEYCODE_" + args[0])
        return
    if cmd == "swipe":
        adb("shell", "input", "swipe", *args)
        return
    if cmd == "drag":
        n = wait_for(id_pred(args[0]))
        if n is None:
            raise SystemExit(f"not found: {args[0]}")
        x, y = center(n)
        ms = args[2] if len(args) > 2 else "1200"
        adb("shell", "input", "swipe", str(x), str(y), str(x), str(y + int(args[1])), ms)
        return
    if cmd == "shot":
        png = adb("exec-out", "screencap", "-p", capture=True)
        with open(args[0], "wb") as f:
            f.write(png)
        return
    if cmd == "ids":
        for n in dump().iter("node"):
            rid = n.get("resource-id")
            if rid:
                print(rid, n.get("bounds"), (n.get("text") or n.get("content-desc") or "")[:50])
        return
    if cmd == "wait":
        time.sleep(float(args[0]))
        return
    raise SystemExit(f"unknown command {cmd}")


def main():
    chunks, cur = [], []
    for a in sys.argv[1:]:
        if a == "--":
            chunks.append(cur)
            cur = []
        else:
            cur.append(a)
    if cur:
        chunks.append(cur)
    for c in chunks:
        run(c[0], c[1:])


if __name__ == "__main__":
    main()
