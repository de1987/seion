#!/usr/bin/env python3
"""Generates a fictional demo library for screenshots and demo clips.

Silent tracks with realistic durations (screenshots are silent; FLAC silence is tiny), plausible but FICTIONAL artist,
album and track names in a few genres, and procedural album covers (gradients, shapes, noise) embedded as the front
cover. Never use real artists' names or art in the screenshots.

  python3 -m venv <scratch>/venv && <scratch>/venv/bin/pip install pillow numpy soundfile mutagen
  <scratch>/venv/bin/python tools/dev/demo_library.py <scratch>/demo
  adb push "<scratch>/demo/Seion Demo" /sdcard/Music/

Output: <out>/Seion Demo/<Artist>/<Album>/NN Title.(flac|mp3) and <out>/covers/*.png. Keep it out of the repo.
"""

import io
import math
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from mutagen.flac import FLAC, Picture
from mutagen.id3 import APIC, ID3, TALB, TCON, TDRC, TIT2, TPE1, TPE2, TRCK
from PIL import Image, ImageDraw, ImageFilter

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "out") / "Seion Demo"
SIZE = 1000

ALBUMS = [
    dict(artist="Lumen Harbor", album="Tidal Glass", genre="Ambient", year=2024, style="blobs",
         palette=[(18, 52, 86), (0, 168, 181), (122, 226, 207), (255, 214, 165)], fmt="flac",
         tracks=["Low Tide Lanterns", "Glasswater", "Northern Pier", "Salt Light", "The Long Swell", "Driftline"]),
    dict(artist="Velvet Cassini", album="Neon Orchards", genre="Synthpop", year=2025, style="sun",
         palette=[(36, 10, 64), (255, 77, 141), (255, 162, 66), (255, 236, 130)], fmt="mp3",
         tracks=["Orchard Lights", "Cherry Voltage", "Night Bus to Nowhere", "Palms on Fire", "Afterglow FM"]),
    dict(artist="Mira Solenne", album="Paper Lanterns", genre="Indie Folk", year=2023, style="bauhaus",
         palette=[(244, 233, 214), (214, 96, 52), (35, 70, 92), (232, 178, 64)], fmt="flac",
         tracks=["Kite String", "Paper Lanterns", "Under the Linden", "Small Fires", "Letters to the River"]),
    dict(artist="Kestrel Avenue Quartet", album="Midnight Transit", genre="Jazz", year=2022, style="lines",
         palette=[(10, 18, 40), (30, 60, 120), (212, 175, 90), (240, 230, 200)], fmt="flac",
         tracks=["Platform Nine", "Blue Hour Shuffle", "Late Train Waltz", "Streetlamp Ballad", "Terminus"]),
    dict(artist="Odessa Fernwood", album="Greenhouse Hymns", genre="Dream Pop", year=2025, style="leaves",
         palette=[(14, 46, 34), (64, 145, 96), (186, 224, 120), (250, 240, 200)], fmt="mp3",
         tracks=["Fern Light", "Glass Roof Rain", "Moss & Morning", "Hothouse Heart", "Seedling"]),
    dict(artist="Orbit Choir", album="Satellite Lullabies", genre="Chillwave", year=2024, style="rings",
         palette=[(24, 14, 60), (112, 64, 200), (210, 120, 255), (120, 220, 255)], fmt="flac",
         tracks=["Low Orbit", "Signal from Home", "Stargazer Tape", "Perigee", "Quiet Telemetry", "Re-entry"]),
    dict(artist="Saltwater Radio", album="Coastline Static", genre="Indie Rock", year=2023, style="noise",
         palette=[(60, 12, 20), (214, 54, 56), (255, 128, 92), (255, 220, 190)], fmt="mp3",
         tracks=["Coastline Static", "Red Flag Beach", "Breakwater", "Sunburnt Radio", "Undertow"]),
    dict(artist="Juniper & Ash", album="Embers in Winter", genre="Acoustic", year=2021, style="embers",
         palette=[(30, 16, 10), (196, 84, 20), (255, 160, 60), (255, 230, 170)], fmt="flac",
         tracks=["First Frost", "Embers", "Woodsmoke", "Cabin Window", "Snowlight"]),
]


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def vgrad(c1, c2, size=SIZE):
    t = np.linspace(0, 1, size)[:, None, None]
    arr = np.array(c1)[None, None, :] * (1 - t) + np.array(c2)[None, None, :] * t
    arr = np.repeat(arr, size, axis=1)
    return Image.fromarray(arr.astype(np.uint8), "RGB")


def grain(img, rng, amount=10):
    a = np.asarray(img).astype(np.int16)
    n = rng.normal(0, amount, a.shape[:2])[:, :, None]
    return Image.fromarray(np.clip(a + n, 0, 255).astype(np.uint8), "RGB")


def cover_blobs(p, rng):
    img = vgrad(p[0], lerp(p[0], p[1], 0.4))
    layer = Image.new("RGB", (SIZE, SIZE), p[0])
    d = ImageDraw.Draw(layer)
    for _ in range(9):
        c = p[rng.integers(1, 4)]
        r = rng.integers(160, 380)
        x, y = rng.integers(0, SIZE, 2)
        d.ellipse([x - r, y - r, x + r, y + r], fill=c)
    layer = layer.filter(ImageFilter.GaussianBlur(110))
    img = Image.blend(img, layer, 0.85)
    d = ImageDraw.Draw(img)
    d.line([(120, 640), (880, 640)], fill=p[3], width=3)
    return grain(img, rng, 7)


def cover_sun(p, rng):
    img = vgrad(p[0], lerp(p[1], p[0], 0.3))
    d = ImageDraw.Draw(img)
    cx, cy, r = SIZE // 2, 520, 300
    for y in range(cy - r, cy + r):
        t = (y - (cy - r)) / (2 * r)
        c = lerp(p[3], p[1], t)
        half = int(math.sqrt(max(r * r - (y - cy) ** 2, 0)))
        if y > cy and ((y - cy) // 18) % 2 == 1 and (y - cy) > 40:
            continue
        d.line([(cx - half, y), (cx + half, y)], fill=c)
    # grid floor
    for i in range(14):
        y = 720 + int((i / 13) ** 2 * 280)
        d.line([(0, y), (SIZE, y)], fill=lerp(p[1], p[0], 0.4), width=2)
    for i in range(-10, 11):
        d.line([(cx + i * 30, 720), (cx + i * 160, SIZE)], fill=lerp(p[1], p[0], 0.4), width=2)
    d.rectangle([0, 700, SIZE, 720], fill=p[0])
    return grain(img.filter(ImageFilter.GaussianBlur(1.2)), rng, 6)


def cover_bauhaus(p, rng):
    img = Image.new("RGB", (SIZE, SIZE), p[0])
    d = ImageDraw.Draw(img)
    d.rectangle([0, 600, SIZE, SIZE], fill=p[2])
    d.ellipse([140, 160, 620, 640], fill=p[1])
    d.pieslice([420, 380, 900, 860], 180, 360, fill=p[3])
    d.rectangle([700, 120, 760, 560], fill=p[2])
    for i in range(5):
        d.ellipse([160 + i * 70, 760, 200 + i * 70, 800], fill=p[0])
    return grain(img, rng, 9)


def cover_lines(p, rng):
    img = vgrad(p[0], p[1])
    d = ImageDraw.Draw(img)
    for i in range(46):
        y = 140 + i * 16
        amp = 60 * math.sin(i / 46 * math.pi)
        pts = [(x, y + amp * math.sin(x / 120 + i * 0.25)) for x in range(80, 921, 8)]
        c = lerp(p[2], p[3], i / 46)
        d.line(pts, fill=c, width=2)
    d.ellipse([700, 120, 820, 240], fill=p[3])
    return grain(img, rng, 6)


def cover_leaves(p, rng):
    img = vgrad(p[0], lerp(p[0], p[1], 0.5))
    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for _ in range(60):
        x, y = rng.integers(-50, SIZE + 50, 2)
        w, h = rng.integers(60, 200), rng.integers(160, 420)
        c = p[rng.integers(1, 3)]
        leaf = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(leaf).ellipse([0, 0, w - 1, h - 1], fill=c + (int(rng.integers(120, 230)),))
        leaf = leaf.rotate(int(rng.integers(0, 180)), expand=True)
        layer.alpha_composite(leaf, (int(x), int(y)))
    img = Image.alpha_composite(img.convert("RGBA"), layer.filter(ImageFilter.GaussianBlur(2))).convert("RGB")
    d = ImageDraw.Draw(img)
    d.ellipse([380, 380, 620, 620], fill=p[3])
    return grain(img, rng, 7)


def cover_rings(p, rng):
    img = vgrad(p[0], lerp(p[0], p[1], 0.35))
    d = ImageDraw.Draw(img)
    cx, cy = 560, 470
    for i in range(22, 0, -1):
        r = i * 22
        c = lerp(p[2], p[3], i / 22) if i % 2 else lerp(p[1], p[0], 0.2)
        d.ellipse([cx - r, cy - r * 0.35, cx + r, cy + r * 0.35], outline=c, width=3)
    d.ellipse([cx - 120, cy - 120, cx + 120, cy + 120], fill=p[2])
    for _ in range(180):
        x, y = rng.integers(0, SIZE, 2)
        s = rng.integers(1, 4)
        d.ellipse([x, y, x + s, y + s], fill=(255, 255, 255))
    return grain(img.filter(ImageFilter.GaussianBlur(0.8)), rng, 6)


def cover_noise(p, rng):
    small = rng.random((24, 24))
    big = Image.fromarray((small * 255).astype(np.uint8), "L").resize((SIZE, SIZE), Image.BICUBIC)
    a = np.asarray(big).astype(np.float32) / 255
    a = np.clip((a - 0.2) / 0.6, 0, 1)
    stops = [p[0], p[1], p[2], p[3]]
    out = np.zeros((SIZE, SIZE, 3), np.float32)
    seg = np.clip((a * 3).astype(int), 0, 2)
    t = a * 3 - seg
    for s in range(3):
        m = seg == s
        for ch in range(3):
            out[..., ch][m] = stops[s][ch] * (1 - t[m]) + stops[s + 1][ch] * t[m]
    img = Image.fromarray(out.astype(np.uint8), "RGB")
    d = ImageDraw.Draw(img)
    for y in range(0, SIZE, 6):
        d.line([(0, y), (SIZE, y)], fill=p[0], width=1)
    return grain(img, rng, 14)


def cover_embers(p, rng):
    img = vgrad(p[0], lerp(p[0], p[1], 0.25))
    layer = Image.new("RGB", (SIZE, SIZE), (0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([200, 600, 800, 1200], fill=p[1])
    d.ellipse([330, 700, 670, 1050], fill=p[2])
    layer = layer.filter(ImageFilter.GaussianBlur(90))
    img = Image.fromarray(np.clip(np.asarray(img).astype(int) + np.asarray(layer), 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    for _ in range(240):
        x = rng.normal(500, 180)
        y = rng.uniform(80, 760)
        s = rng.uniform(1.5, 5)
        d.ellipse([x, y, x + s, y + s], fill=lerp(p[2], p[3], rng.random()))
    return grain(img, rng, 7)


STYLES = dict(blobs=cover_blobs, sun=cover_sun, bauhaus=cover_bauhaus, lines=cover_lines, leaves=cover_leaves,
              rings=cover_rings, noise=cover_noise, embers=cover_embers)


def jpeg(img):
    b = io.BytesIO()
    img.save(b, "JPEG", quality=88)
    return b.getvalue()


def main():
    covers = OUT.parent / "covers"
    covers.mkdir(parents=True, exist_ok=True)
    rate = 44100
    for idx, a in enumerate(ALBUMS):
        rng = np.random.default_rng(1000 + idx)
        img = STYLES[a["style"]](a["palette"], rng)
        img.save(covers / f"{idx:02d}-{a['style']}.png")
        art = jpeg(img)
        folder = OUT / a["artist"] / a["album"]
        folder.mkdir(parents=True, exist_ok=True)
        for ti, title in enumerate(a["tracks"], 1):
            secs = int(rng.integers(165, 320))
            data = np.zeros((secs * rate, 2), np.float32)
            name = f"{ti:02d} {title}.{a['fmt']}"
            path = folder / name
            if a["fmt"] == "flac":
                sf.write(path, data, rate, format="FLAC", subtype="PCM_16")
                f = FLAC(path)
                f["title"], f["artist"], f["album"] = title, a["artist"], a["album"]
                f["albumartist"], f["genre"], f["date"] = a["artist"], a["genre"], str(a["year"])
                f["tracknumber"] = str(ti)
                pic = Picture()
                pic.type, pic.mime, pic.desc, pic.data = 3, "image/jpeg", "Front", art
                pic.width = pic.height = SIZE
                pic.depth = 24
                f.add_picture(pic)
                f.save()
            else:
                sf.write(path, data, rate, format="MP3", subtype="MPEG_LAYER_III")
                t = ID3()
                t.add(TIT2(encoding=3, text=title))
                t.add(TPE1(encoding=3, text=a["artist"]))
                t.add(TPE2(encoding=3, text=a["artist"]))
                t.add(TALB(encoding=3, text=a["album"]))
                t.add(TCON(encoding=3, text=a["genre"]))
                t.add(TDRC(encoding=3, text=str(a["year"])))
                t.add(TRCK(encoding=3, text=str(ti)))
                t.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="Front", data=art))
                t.save(path, v2_version=3)
            print(path, path.stat().st_size)


if __name__ == "__main__":
    main()
