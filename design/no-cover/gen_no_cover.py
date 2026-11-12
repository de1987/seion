"""Seion: the "no cover" placeholder's mark, a single eighth note drawn as one smooth line on the launcher ink.

The note is the eighth note of the UI icon set (design/icons/), drawn large: a filled notehead tilted -25 degrees, a stem
whose outer edge meets the head's rightmost point, and a flag that leaves the stem top through a round turn and swings
down to the right in one stroke, ending in a soft inward hook. Stem and flag are one round-capped line of a single
weight with no sharp joins. The mark is painted with the launcher icon's amber-to-coral gradient (#FFC453 -> #FF7F5C,
top-left to bottom-right of the mark) on the icon's ink tile #16171A.

Standalone (Python 3, no packages). Run from the repo root:
    python3 -B design/no-cover/gen_no_cover.py OUT_DIR [--gallery GALLERY_DIR]
It writes ic_no_cover_glyph.xml (the shipped variant, viewport 100 x 100) and no_cover.svg into OUT_DIR and prints
the measurements; copy the XML to app/src/main/res/drawable/ by hand. With --gallery it also writes index.html with
every variant on the tile at 40, 64, 160 and 320 px.
"""
import math
import os
import sys

AMBER, CORAL, INK = '#FFC453', '#FF7F5C', '#16171A'
SHIPPED = 'S'


# ---------------------------------------------------------------------------------------------------------- geometry

def n(v):
    s = f'{v:.2f}'.rstrip('0').rstrip('.')
    return '0' if s in ('-0', '') else s


def ellipse(cx, cy, a, b, deg):
    """A closed tilted ellipse as four cubics."""
    th = math.radians(deg)
    ca, sa = math.cos(th), math.sin(th)
    k = 4 / 3 * (math.sqrt(2) - 1)

    def P(x, y):
        return (cx + x * ca - y * sa, cy + x * sa + y * ca)
    q = [((a, 0), (a, -k * b), (k * a, -b), (0, -b)),
         ((0, -b), (-k * a, -b), (-a, -k * b), (-a, 0)),
         ((-a, 0), (-a, k * b), (-k * a, b), (0, b)),
         ((0, b), (k * a, b), (a, k * b), (a, 0))]
    return [('C', P(*s[0]), P(*s[1]), P(*s[2]), P(*s[3])) for s in q]


def ellipse_right(cx, cy, a, b, deg):
    """The rightmost point of the tilted ellipse (where its tangent is vertical), in closed form."""
    th = math.radians(deg)
    t = math.atan2(-b * math.sin(th), a * math.cos(th))
    return (cx + a * math.cos(t) * math.cos(th) - b * math.sin(t) * math.sin(th),
            cy + a * math.cos(t) * math.sin(th) + b * math.sin(t) * math.cos(th))


def to_d(segs, close=False):
    out, last = [], None
    for s in segs:
        p0 = s[1]
        if last is None or math.hypot(p0[0] - last[0], p0[1] - last[1]) > 1e-6:
            out.append(f'M{n(p0[0])},{n(p0[1])}')
        if s[0] == 'L':
            out.append(f'L{n(s[2][0])},{n(s[2][1])}')
            last = s[2]
        else:
            out.append('C' + ' '.join(f'{n(p[0])},{n(p[1])}' for p in s[2:]))
            last = s[4]
    if close:
        out.append('Z')
    return ' '.join(out)


def sample(segs, steps=32):
    pts = []
    for s in segs:
        for i in range(steps + 1):
            t = i / steps
            if s[0] == 'L':
                pts.append((s[1][0] + (s[2][0] - s[1][0]) * t, s[1][1] + (s[2][1] - s[1][1]) * t))
            else:
                p0, c1, c2, p3 = s[1:]
                mt = 1 - t
                pts.append(tuple(mt ** 3 * p0[j] + 3 * mt * mt * t * c1[j] + 3 * mt * t * t * c2[j] + t ** 3 * p3[j]
                                 for j in range(2)))
    return pts


# ---------------------------------------------------------------------------------------------------------- the note

def note(v):
    """One variant: dict(line=segs, heads=[segs], w=stroke width), centred on the 100 x 100 tile."""
    w = v['w']
    a, b, tilt = v['head']
    hx, hy = v['head_at']
    xr, yr = ellipse_right(hx, hy, a, b, tilt)
    sx = xr - w / 2                                   # the stem's outer edge meets the head's rightmost point
    top, r = v['top'], v['turn']
    k = 4 / 3 * (math.sqrt(2) - 1) * r                # quarter-circle cubic
    fx, fy = v['flag']                                # where the flag's tail lands, relative to the turn's end
    hook = v['hook']
    t0 = (sx + r, top)
    tail = (t0[0] + fx, t0[1] + fy)
    line = [('L', (sx, yr), (sx, top + r)),
            # the round turn at the stem top: up, then heading right
            ('C', (sx, top + r), (sx, top + r - k), (t0[0] - k, top), t0),
            # the swing: out to the right, then down, easing into the tail with a slight inward hook
            ('C', t0, (t0[0] + 0.55 * fx, top), (tail[0] + hook, t0[1] + 0.45 * fy), tail)]
    heads = [ellipse(hx, hy, a, b, tilt)]
    g = dict(line=line, heads=heads, w=w)
    # centre the mark's bounding box on the tile
    x0, y0, x1, y1 = bbox(g)
    dx, dy = 50 - (x0 + x1) / 2, 50 - (y0 + y1) / 2
    return dict(line=shift(line, dx, dy), heads=[shift(h, dx, dy) for h in heads], w=w)


def shift(segs, dx, dy):
    return [(s[0],) + tuple((p[0] + dx, p[1] + dy) for p in s[1:]) for s in segs]


VARIANTS = {
    'S': dict(name='S · Single swing', w=5.0, head=(8.6, 6.4, -25), head_at=(40.0, 70.0), top=25.0, turn=3.2,
              flag=(14.5, 25.0), hook=4.5,
              blurb='One eighth note: the flag leaves the stem through a round turn and swings down into a soft hook.'),
    'T': dict(name='T · Taller swing', w=5.0, head=(8.2, 6.1, -25), head_at=(40.0, 71.0), top=24.0, turn=3.6,
              flag=(13.0, 30.0), hook=3.0,
              blurb='S with a longer, steeper flag; slimmer at 40 px.'),
    'U': dict(name='U · Bold', w=6.2, head=(9.2, 6.9, -25), head_at=(40.0, 70.0), top=25.0, turn=3.4,
              flag=(14.5, 25.0), hook=4.5,
              blurb='S weighted for small sizes: stroke 6.2 and a larger head; heavier at full size.'),
}


# ---------------------------------------------------------------------------------------------------------- colour

def _lum(hexc):
    def ch(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (int(hexc.lstrip('#')[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def contrast(a, b):
    la, lb = sorted((_lum(a), _lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def mix(t):
    a = [int(AMBER[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(CORAL[i:i + 2], 16) for i in (1, 3, 5)]
    return '#' + ''.join('%02X' % round(a[k] + (b[k] - a[k]) * t) for k in range(3))


def bbox(g):
    pts = sample(g['line'])
    hw = g['w'] / 2
    xs = [p[0] - hw for p in pts] + [p[0] + hw for p in pts]
    ys = [p[1] - hw for p in pts] + [p[1] + hw for p in pts]
    for h in g['heads']:
        for p in sample(h):
            xs.append(p[0])
            ys.append(p[1])
    return min(xs), min(ys), max(xs), max(ys)


def measure(g):
    x0, y0, x1, y1 = bbox(g)
    crs = [contrast(mix(i / 50), INK) for i in range(51)]
    return dict(bbox=(x0, y0, x1, y1), width=x1 - x0, height=y1 - y0, cmin=min(crs), cmax=max(crs))


# ---------------------------------------------------------------------------------------------------------- output

def svg_body(g, gid):
    x0, y0, x1, y1 = bbox(g)
    defs = (f'<defs><linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{n(x0)}" y1="{n(y0)}" x2="{n(x1)}" '
            f'y2="{n(y1)}"><stop offset="0" stop-color="{AMBER}"/><stop offset="1" stop-color="{CORAL}"/></linearGradient></defs>')
    heads = ''.join(f'<path d="{to_d(h, True)}" fill="url(#{gid})"/>' for h in g['heads'])
    line = (f'<path d="{to_d(g["line"])}" fill="none" stroke="url(#{gid})" stroke-width="{n(g["w"])}" '
            'stroke-linecap="round" stroke-linejoin="round"/>')
    return defs + heads + line


def vector_xml(g):
    x0, y0, x1, y1 = bbox(g)

    def grad(attr):
        return (f'        <aapt:attr name="android:{attr}">\n'
                f'            <gradient android:type="linear" android:startX="{n(x0)}" android:startY="{n(y0)}" '
                f'android:endX="{n(x1)}" android:endY="{n(y1)}">\n'
                f'                <item android:offset="0" android:color="#FF{AMBER[1:]}" />\n'
                f'                <item android:offset="1" android:color="#FF{CORAL[1:]}" />\n'
                '            </gradient>\n        </aapt:attr>\n')
    heads = ' '.join(to_d(h, True) for h in g['heads'])
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- The "no cover" placeholder\'s mark: a single eighth note, one round-capped line (stem and swung flag)\n'
            '     rising from a tilted notehead, painted with the launcher icon\'s amber-to-coral gradient across the\n'
            '     mark. Drawn at the full tile size on the ink tile (NoCoverColors.Tile); not tinted.\n'
            '     Generated by design/no-cover/gen_no_cover.py (variant ' + SHIPPED + '); do not edit by hand. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="24dp" android:height="24dp"\n'
            '    android:viewportWidth="100" android:viewportHeight="100">\n'
            f'    <path android:pathData="{heads}">\n' + grad('fillColor') + '    </path>\n'
            f'    <path android:pathData="{to_d(g["line"])}"\n'
            f'        android:strokeWidth="{n(g["w"])}" android:strokeLineCap="round" android:strokeLineJoin="round">\n'
            + grad('strokeColor') + '    </path>\n</vector>\n')


SIZES = (40, 64, 160, 320)


def tile(px, body, bg=INK):
    r = n(max(3, px * 0.06) * 100 / px)
    return (f'<svg width="{px}" height="{px}" viewBox="0 0 100 100" role="img"><rect width="100" height="100" rx="{r}" '
            f'fill="{bg}"/>{body}</svg>')


def gallery(out):
    os.makedirs(out, exist_ok=True)
    rows = []
    for key, v in VARIANTS.items():
        g = note(v)
        m = measure(g)
        cells = ''.join(f'<figure>{tile(s, svg_body(g, f"g{key}{s}"))}<figcaption>{s} px</figcaption></figure>'
                        for s in SIZES)
        chosen = ' chosen' if key == SHIPPED else ''
        rows.append(
            f'<section class="v{chosen}"><header><h2>{v["name"]}{" — shipped" if chosen else ""}</h2>'
            f'<p>{v["blurb"]}</p><p class="m">stroke {n(v["w"])} / 100 (= {v["w"] * 0.4:.1f} dp at 40 dp) · mark '
            f'{m["width"]:.0f} × {m["height"]:.0f} % of the tile · contrast on ink {m["cmin"]:.2f}–{m["cmax"]:.2f}:1</p>'
            f'</header><div class="row">{cells}</div></section>')
    g = note(VARIANTS[SHIPPED])
    ctx = ''.join(f'<div class="item">{tile(40, svg_body(g, f"x{i}"))}<div><b>{t}</b><br><span>Unknown artist · {d}</span>'
                  '</div></div>' for i, (t, d) in enumerate((('Untitled track', '3:41'), ('Field recording 04', '1:12'))))
    html = f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>No Cover</title>
<style>
:root{{--page:#0e0f12;--panel:#2b2e35;--line:#3a3e46;--ink:#e9eaee;--muted:#9aa0aa;--accent:#FFA258}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--page);color:var(--ink);font:15px/1.55 Roboto,"Noto Sans",system-ui,sans-serif}}
.page{{max-width:1180px;margin:0 auto;padding:40px 16px 80px}}
h1{{font-weight:400;font-size:24px;margin:0 0 8px}} h2{{font-weight:500;font-size:17px;margin:0}}
.lead{{color:var(--muted);max-width:860px}}
.v{{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:18px 18px 10px;margin:18px 0}}
.v.chosen{{border-color:var(--accent)}}
.v p{{margin:4px 0;color:var(--muted)}} .v p.m{{font-size:13px}}
.row{{display:flex;flex-wrap:wrap;align-items:flex-end;gap:22px;margin-top:14px}}
figure{{margin:0;text-align:center}} figcaption{{font-size:12px;color:var(--muted);margin-top:4px}}
svg{{display:block;max-width:100%;height:auto}}
.ctx{{background:#121317;border-radius:12px;padding:8px 12px;max-width:380px}}
.item{{display:flex;gap:12px;align-items:center;padding:8px 0}} .item span{{color:var(--muted);font-size:13px}}
</style></head><body><div class="page">
<h1>No cover</h1>
<p class="lead">The placeholder tile: the launcher ink <b>#16171A</b> with a single eighth note drawn as one smooth
single-weight line (round caps, a round turn at the stem top, a flag that swings into a soft hook) and painted with the
icon's amber→coral gradient. Generated by <code>design/no-cover/gen_no_cover.py --gallery</code>.</p>
{''.join(rows)}
<section class="v"><header><h2>In context (40 px rows)</h2></header><div class="ctx">{ctx}</div></section>
</div></body></html>
'''
    with open(os.path.join(out, 'index.html'), 'w') as fh:
        fh.write(html)


def main(argv):
    out = argv[1] if len(argv) > 1 and not argv[1].startswith('--') else 'out'
    os.makedirs(out, exist_ok=True)
    g = note(VARIANTS[SHIPPED])
    with open(os.path.join(out, 'ic_no_cover_glyph.xml'), 'w') as fh:
        fh.write(vector_xml(g))
    with open(os.path.join(out, 'no_cover.svg'), 'w') as fh:
        fh.write('<svg xmlns="http://www.w3.org/2000/svg" width="320" height="320" viewBox="0 0 100 100">\n'
                 f'<!-- Seion "no cover" tile, variant {SHIPPED}: ink tile, amber-to-coral eighth note. -->\n'
                 f'<rect width="100" height="100" fill="{INK}"/>{svg_body(g, "g")}\n</svg>\n')
    m = measure(g)
    print(f'variant {SHIPPED}: stroke {g["w"]}, bbox ' + ', '.join(f'{v:.2f}' for v in m['bbox']))
    print(f'mark {m["width"]:.1f} x {m["height"]:.1f} of 100; contrast on ink {m["cmin"]:.2f}..{m["cmax"]:.2f}:1')
    if '--gallery' in argv:
        gallery(argv[argv.index('--gallery') + 1])


if __name__ == '__main__':
    main(sys.argv)
