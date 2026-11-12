"""Seion UI icon set: every glyph built here from lines, circular arcs, ellipses and rounded polygons.

Standalone (Python 3, no packages). Run from the repo root:
    python3 -B design/icons/gen_icons.py [--svg DIR]
It rewrites the UI vectors in app/src/main/res/drawable/ (the launcher layers, the notification icon, the feature
emblems and the no-cover glyph have their own sources and are not touched). With --svg it also writes one SVG per icon
into DIR for review.

House style (see README.md):
- 24 x 24 viewport, live area 3..21; stroke 1.75, round caps and joins, containers rounded at 2.5 to 3.
- Where one part of a glyph meets another (a lens and its handle, a lid and a bin, a band and its ear cups), the parts
  stay apart by a 1.25 unit gap instead of touching: the set's signature.
- Note heads are filled tilted ellipses; transport glyphs are filled shapes with rounded corners.
- White on transparent, so every icon is coloured by the caller's tint. The transport glyphs keep the odd viewports the
  Now playing layout sizes them by (the ring buttons, the shuffle and repeat slots, the play/pause morph).
"""
import math
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable')

W = 1.75          # house stroke
GAP = 1.25        # visible gap between parts that would otherwise touch
SEP = GAP + W     # centreline distance that leaves GAP between two strokes
TW = 2.0          # transport stroke (shuffle, repeat), sized against the 1.75 dp button rings
WHITE, CLEAR = '#FFFFFFFF', '#00000000'


# ------------------------------------------------------------------------------------------------------------ numbers

def f(v):
    s = f'{v:.3f}'.rstrip('0').rstrip('.')
    return '0' if s in ('-0', '') else s


def pt(p):
    return f'{f(p[0])},{f(p[1])}'


def polar(c, r, deg):
    a = math.radians(deg)
    return (c[0] + r * math.cos(a), c[1] + r * math.sin(a))


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1])


def add(a, b):
    return (a[0] + b[0], a[1] + b[1])


def mul(a, k):
    return (a[0] * k, a[1] * k)


def norm(a):
    d = math.hypot(*a)
    return (a[0] / d, a[1] / d)


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def angle_of(c, p):
    return math.degrees(math.atan2(p[1] - c[1], p[0] - c[0]))


def circle_x(c1, r1, c2, r2):
    """The two intersections of two circles (ordered by the cross product, so the first is 'left' of c1 -> c2)."""
    d = math.hypot(c2[0] - c1[0], c2[1] - c1[1])
    a = (r1 * r1 - r2 * r2 + d * d) / (2 * d)
    h = math.sqrt(max(0.0, r1 * r1 - a * a))
    m = (c1[0] + a * (c2[0] - c1[0]) / d, c1[1] + a * (c2[1] - c1[1]) / d)
    ox, oy = -(c2[1] - c1[1]) / d * h, (c2[0] - c1[0]) / d * h
    return (m[0] - ox, m[1] - oy), (m[0] + ox, m[1] + oy)


# ------------------------------------------------------------------------------------------------------------- shapes
# Each returns SVG/Android path data (absolute commands only).

def line(*pts):
    return 'M' + ' L'.join(pt(p) for p in pts)


def arc_to(r, end, large, sweep):
    return f' A{f(r)},{f(r)} 0 {large},{sweep} {pt(end)}'


def arc(c, r, a0, a1, move=True):
    """An open circular arc from angle a0 to a1 (degrees, y down: increasing angles turn clockwise)."""
    out = ('M' + pt(polar(c, r, a0))) if move else ''
    span = a1 - a0
    steps = 1 if abs(span) <= 180 else 2
    for i in range(1, steps + 1):
        e = polar(c, r, a0 + span * i / steps)
        out += arc_to(r, e, 0, 1 if span > 0 else 0)
    return out


def arc_via(c, r, a0, a1, via, move=True):
    """The arc from a0 to a1 that passes through angle via (clockwise or counter-clockwise, whichever does)."""
    cw = (a1 - a0) % 360
    if (via - a0) % 360 <= cw:
        return arc(c, r, a0, a0 + cw, move)
    return arc(c, r, a0, a0 - (360 - cw), move)


def circle(c, r):
    return 'M' + pt((c[0] - r, c[1])) + arc_to(r, (c[0] + r, c[1]), 1, 1) + arc_to(r, (c[0] - r, c[1]), 1, 1) + ' Z'


def ellipse(c, rx, ry, deg):
    """A closed tilted ellipse (two elliptical arcs)."""
    a = math.radians(deg)
    p0 = (c[0] + rx * math.cos(a), c[1] + rx * math.sin(a))
    p1 = (c[0] - rx * math.cos(a), c[1] - rx * math.sin(a))
    e = f' A{f(rx)},{f(ry)} {f(deg)} 1,1 '
    return 'M' + pt(p0) + e + pt(p1) + e + pt(p0) + ' Z'


def corner(prev, v, nxt, r):
    """The fillet of radius r at v: (start, end, sweep, c1, c2) with circular-arc cubic controls."""
    ui, uo = norm(sub(v, prev)), norm(sub(nxt, v))
    cos = max(-1.0, min(1.0, ui[0] * uo[0] + ui[1] * uo[1]))
    phi = math.acos(cos)
    if r <= 0 or phi < 1e-6:
        return v, v, 1, v, v
    t = r * math.tan(phi / 2)
    a, b = sub(v, mul(ui, t)), add(v, mul(uo, t))
    k = 4 / 3 * math.tan(phi / 4) * r
    sweep = 1 if ui[0] * uo[1] - ui[1] * uo[0] > 0 else 0
    return a, b, sweep, add(a, mul(ui, k)), sub(b, mul(uo, k))


def rpoly(pts, radii, closed=True):
    """A polygon (closed) or polyline (open) with a circular fillet of radii[i] at vertex i."""
    n = len(pts)
    if isinstance(radii, (int, float)):
        radii = [radii] * n
    out = []
    rng = range(n) if closed else range(1, n - 1)
    for i in rng:
        v, r = pts[i], radii[i]
        a, b, sweep, _, _ = corner(pts[i - 1], v, pts[(i + 1) % n], r)
        out.append(('L', a))
        if r > 0:
            out.append(('A', r, b, sweep))
    if closed:
        d = 'M' + pt(out[-1][2] if out[-1][0] == 'A' else out[-1][1])
    else:
        d = 'M' + pt(pts[0])
    for s in out:
        d += (' L' + pt(s[1])) if s[0] == 'L' else arc_to(s[1], s[2], 0, s[3])
    return d + (' Z' if closed else ' L' + pt(pts[-1]))


def rrect(x0, y0, x1, y1, r):
    return rpoly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], r)


def slots(pts, radii):
    """A closed shape as 4 fixed 'L + C' corner slots (r 0 gives a degenerate corner): morphable path data."""
    segs = [corner(pts[i - 1], pts[i], pts[(i + 1) % 4], radii[i]) for i in range(4)]
    d = 'M' + pt(segs[-1][1])
    for a, b, _, c1, c2 in segs:
        d += f' L{pt(a)} C{pt(c1)} {pt(c2)} {pt(b)}'
    return d + ' Z'


def chevron(tip, deg, arm=2.6, spread=45):
    """An open arrowhead whose tip points along deg."""
    back = deg + 180
    return line(polar(tip, arm, back - spread), tip, polar(tip, arm, back + spread))


def cubic_split(p, t):
    """de Casteljau: the cubic p (4 points) split at t."""
    a, b, c, d = p
    ab, bc, cd = lerp(a, b, t), lerp(b, c, t), lerp(c, d, t)
    abc, bcd = lerp(ab, bc, t), lerp(bc, cd, t)
    m = lerp(abc, bcd, t)
    return (a, ab, abc, m), (m, bcd, cd, d)


def cubic(p, move=True):
    return ('M' + pt(p[0]) if move else '') + f' C{pt(p[1])} {pt(p[2])} {pt(p[3])}'


# --------------------------------------------------------------------------------------------------------- motifs

def eighth_note(head, rx, ry, top, flag, tilt=-22):
    """A single eighth note: a filled tilted head, a stem rising from the head's right edge and one swung flag.

    Returns (fill, stroke). flag = (dx, dy): where the flag's tail lands relative to the stem top."""
    a = math.radians(tilt)
    # the head's rightmost point (where the ellipse's tangent is vertical)
    t = math.atan2(-ry * math.sin(a), rx * math.cos(a))
    x = head[0] + rx * math.cos(t) * math.cos(a) - ry * math.sin(t) * math.sin(a)
    y = head[1] + rx * math.cos(t) * math.sin(a) + ry * math.sin(t) * math.cos(a)
    sx = x - W / 2
    st = (sx, top)
    tail = (sx + flag[0], top + flag[1])
    stem = line((sx, y), st)
    # the flag leaves the stem along it, swells out to the right and settles downward into the tail
    fl = cubic((st, (sx + 0.1 * flag[0], top + 0.42 * flag[1]), (tail[0] + 0.05 * flag[0], top + 0.3 * flag[1]),
                tail))
    return ellipse(head, rx, ry, tilt), stem + ' ' + fl


def lens(c, r, toward, gap=GAP):
    """A magnifier: the ring, and a handle that starts a gap away from it."""
    d = norm(sub(toward, c))
    return circle(c, r) + ' ' + line(add(c, mul(d, r + W + gap)), toward)


# ---------------------------------------------------------------------------------------------------------- icons
# Each icon: dict(desc, size=(w, h) dp, view=(vw, vh), layers=[('s', d[, width]) | ('f', d)]).

ICONS = {}


def icon(name, desc, layers, size=(24, 24), view=(24, 24)):
    ICONS[name] = dict(desc=desc, layers=layers, size=size, view=view)


def s(*ds, w=W):
    return ('s', ' '.join(ds), w)


def fl(*ds):
    return ('f', ' '.join(ds))


C = (12, 12)

# --- navigation and dialogs
icon('ic_add', 'add: a plus', [s(line((12, 4.75), (12, 19.25)), line((4.75, 12), (19.25, 12)))])
icon('ic_close', 'close: a cross', [s(line((6.5, 6.5), (17.5, 17.5)), line((17.5, 6.5), (6.5, 17.5)))])
icon('ic_check', 'check: a tick', [s(line((4.75, 12.75), (9.5, 17.25), (19.25, 6.75)))])
icon('ic_back', 'back: a left chevron', [s(line((14.75, 5.25), (8, 12), (14.75, 18.75)), w=2)])
icon('ic_chevron_right', 'chevron right', [s(line((9.75, 6.25), (15.5, 12), (9.75, 17.75)))])
icon('ic_drag', 'drag handle: two short rails', [s(line((5.75, 9.75), (18.25, 9.75)), line((5.75, 14.25), (18.25, 14.25)))])
icon('ic_more', 'overflow: three dots in a column',
     [fl(*(circle((2.5, 2.45 + 7.35 * i), 2.2) for i in range(3)))], size=(5, 19.6), view=(5, 19.6))
icon('ic_search', 'search: a lens with a detached handle', [s(lens((10.25, 10.25), 6.25, (20.25, 20.25)))])


def info_glyph():
    return [s(circle(C, 9), line((12, 10.75), (12, 15.6)) + ' ' + arc((13.6, 15.6), 1.6, 180, 90)),
            fl(circle((12, 7.6), 1.2))]


icon('ic_info', 'info: a ring around a hooked i', info_glyph())
icon('ic_set_info', 'about: a ring around a hooked i', info_glyph())

icon('ic_lock', 'lock: a rounded body, the shackle and a keyhole',
     [s(rrect(5, 10.5, 19, 20.5, 2.75), line((8.25, 10.5), (8.25, 7.75)) + arc((12, 7.75), 3.75, 180, 360, False)
        + ' L15.75,10.5', line((12, 14.75), (12, 17.25))), fl(circle((12, 14.6), 1.35))])

# --- library tabs
icon('ic_album', 'album: a disc, its hub and one groove highlight',
     [s(circle(C, 9), circle(C, 2.4), arc(C, 5.9, 195, 255))])
icon('ic_artist', 'artist: a head above a rounded bust, apart',
     [s(circle((12, 7.75), 3.6),
        'M6.25,20 H17.75 A1.5,1.5 0 0,0 19.25,18.5 C19.25,15.9 16,14.25 12,14.25 C8,14.25 4.75,15.9 4.75,18.5 '
        'A1.5,1.5 0 0,0 6.25,20 Z')])
icon('ic_genre', 'genre: a label pointing left, with its eyelet',
     [s(rpoly([(3.25, 12), (8.5, 6.25), (20.75, 6.25), (20.75, 17.75), (8.5, 17.75)], [1.2, 1.2, 2.5, 2.5, 1.2]),
        circle((9, 12), 1.45))])
icon('ic_folder', 'folder: a rounded front panel and the tabbed back edge above it',
     [s(rrect(3, 8.5, 21, 19.75, 2.5), rpoly([(4.5, 5.5), (4.5, 4.25), (9, 4.25), (10.5, 5.5), (19.5, 5.5)],
                                             [0, 1, 1, 1, 0], closed=False))])

nf, ns = eighth_note((9.25, 17.6), 3.1, 2.3, 4.25, (5.25, 7.25))
icon('ic_note', 'songs: a single eighth note with a swung flag', [s(ns), fl(nf)])
icon('ic_music_off', 'no music: the eighth note struck through',
     [s(ns, line((3.75, 3.75), (20.25, 20.25))), fl(nf)])

pf, ps = eighth_note((16.6, 18.4), 2.45, 1.85, 9.25, (3.6, 4.6))
icon('ic_playlist', 'playlists: three ragged lines and an eighth note',
     [s(line((3.5, 5.5), (20.5, 5.5)), line((3.5, 10.75), (14.5, 10.75)), line((3.5, 16), (10.5, 16)), ps), fl(pf)])
icon('ic_queue', 'play queue: three ragged lines and a rounded play triangle',
     [s(line((3.5, 5.5), (20.5, 5.5)), line((3.5, 10.75), (20.5, 10.75)), line((3.5, 16), (10.25, 16))),
      fl(rpoly([(13.75, 13.6), (21, 17.6), (13.75, 21.6)], 1.1))])
icon('ic_recent', 'recently added: a clock whose rim opens for a plus',
     [s(arc((11.25, 12.75), 8.25, -18, 288), line((11.25, 8.25), (11.25, 12.75), (14.25, 14.75)),
        line((19, 2.75), (19, 7.75)), line((16.5, 5.25), (21.5, 5.25)))])

# The library button on Now playing: a stack of album covers receding up and to the left, a disc on the front one.
def shelf():
    x0, y0, x1, y1 = 10, 8.25, 24.25, 22.25            # the front cover (square)
    step = (-3.75, -3.25)                              # each cover behind shows its left and top edges
    layers = []
    for i in (1, 2):
        ox, oy = x0 + step[0] * i, y0 + step[1] * i
        right = x1 + step[0] * i
        bottom = y1 + step[1] * i
        layers.append(rpoly([(ox, bottom), (ox, oy), (right, oy)], [0, 2.25, 0], closed=False))
    c = ((x0 + x1) / 2, (y0 + y1) / 2)
    return [s(rrect(x0, y0, x1, y1, 2.5), circle(c, 3.6), *layers, w=2), fl(circle(c, 1.1))]


icon('ic_shelf', 'library: a stack of album covers, a disc on the front one', shelf(),
     size=(26, 23.5), view=(26, 23.5))

# --- storage
icon('ic_sd', 'SD card: a chamfered card with three contacts',
     [s(rpoly([(5, 7.5), (9.75, 2.75), (19, 2.75), (19, 21.25), (5, 21.25)], [1, 1, 2.5, 2.5, 2.5]),
        line((10.75, 6.5), (10.75, 9)), line((13.5, 6.5), (13.5, 9)), line((16.25, 6.5), (16.25, 9)))])
icon('ic_storage', 'internal storage: a drive with two vents and a status light',
     [s(rrect(3, 6.75, 21, 17.25, 3), line((6.75, 10.5), (12, 10.5)), line((6.75, 13.5), (9.75, 13.5))),
      fl(circle((16.75, 12), 1.35))])

# --- settings rows
def cloud_outline(dy):
    l, rl = (7.25, 13.25 + dy), 3.5
    m, rm = (12.25, 9.75 + dy), 5
    r, rr = (17, 12.75 + dy), 4
    base = l[1] + rl
    lm = circle_x(l, rl, m, rm)[0]
    mr = circle_x(m, rm, r, rr)[0]
    d = 'M' + pt((l[0], base))
    d += arc(l, rl, 90, 360 + angle_of(l, lm) if angle_of(l, lm) < 90 else angle_of(l, lm), False)
    a0, a1 = angle_of(m, lm), angle_of(m, mr)
    d += arc(m, rm, a0, a1 if a1 > a0 else a1 + 360, False)
    b0 = angle_of(r, mr)
    d += arc(r, rr, b0, 90 if b0 < 90 else 450, False)
    return d, base, l[0], r[0]


_cd, _base, _cl, _cr = cloud_outline(-1)
icon('ic_set_cloud', 'covers from the internet: a cloud, the arrow dropping through its base',
     [s(_cd + f' L{f(12 + SEP - 0.5)},{f(_base)}', line((12 - SEP + 0.5, _base), (_cl, _base)),
        line((12, 10.75), (12, 20.75)), chevron((12, 20.75), 90, 2.6))])

icon('ic_set_code', 'source code: two angle brackets apart from the slash between them',
     [s(line((8.25, 6.75), (3.5, 12), (8.25, 17.25)), line((15.75, 6.75), (20.5, 12), (15.75, 17.25)),
        line((13.5, 5.25), (10.5, 18.75)))])

icon('ic_set_cover_search', 'cover search: a picture whose frame opens for a lens',
     [s(rpoly([(21, 11.25), (21, 4), (3, 4), (3, 20), (11, 20)], [0, 2.5, 2.5, 2.5, 0], closed=False),
        line((3.25, 16.75), (8, 12), (11.25, 15.25)), circle((16, 8.5), 1.5), lens((15.25, 15.25), 3, (21.25, 21.25), 1))])
icon('ic_set_doc', 'licences: a page with its lower corner folded up and three lines',
     [s(rpoly([(5, 3), (19, 3), (19, 15.75), (13.75, 21), (5, 21)], [2.5, 2.5, 0, 0, 2.5]),
        rpoly([(19, 15.75), (15, 15.75), (13.75, 17), (13.75, 21)], [0, 1.25, 0, 0], closed=False),
        line((8.5, 7.75), (15.5, 7.75)), line((8.5, 11.25), (15.5, 11.25)), line((8.5, 14.75), (11, 14.75)))])
icon('ic_set_headphones', 'headphones: a semicircular band over two ear cups, apart',
     [s(arc((12, 12.25), 7.75, 180, 360), rrect(2.25, 12.25 + SEP, 6.25, 21.25, 2), rrect(17.75, 12.25 + SEP, 21.75, 21.25, 2))])

idf, ids = eighth_note((6.75, 17.5), 2.6, 1.95, 4.25, (4.25, 5.75))
icon('ic_set_identify', 'identify: an eighth note and a lens', [s(ids, lens((15.5, 14.75), 3, (21, 20.25), 1)), fl(idf)])
icon('ic_set_image', 'album art: a frame, a sun and a two-peaked ridge',
     [s(rrect(3, 4, 21, 20, 3), circle((16, 8.75), 1.75),
        line((3.5, 17.25), (8.75, 12), (12.75, 16), (15.25, 13.5), (20.5, 18.75)))])
_lat = math.sqrt(81 - 3.5 ** 2)
icon('ic_set_language', 'language: a globe with one meridian and two parallels',
     [s(circle(C, 9), ellipse(C, 9, 3.9, 90), line((12 - _lat, 8.5), (12 + _lat, 8.5)),
        line((12 - _lat, 15.5), (12 + _lat, 15.5)))])
icon('ic_set_motion', 'ambient drift: a ball trailing three motion lines',
     [s(circle((16.25, 12), 4.5), line((3.75, 8.25), (10.25, 8.25)), line((2.25, 12), (8.75, 12)),
        line((5.25, 15.75), (10.25, 15.75)))])


def palette():
    c, r = (12, 12), 9
    n, rn = (5.75, 17.25), 3.6
    p, q = circle_x(c, r, n, rn)
    toward_n = angle_of(c, n)
    # the rim the long way round (away from the notch), then the notch the short way (inside the rim)
    d = arc_via(c, r, angle_of(c, p), angle_of(c, q), toward_n + 180)
    d += arc_via(n, rn, angle_of(n, q), angle_of(n, p), angle_of(n, c), False) + ' Z'
    dots = [circle(x, 1.4) for x in ((9.25, 7.75), (13.75, 6.75), (17.25, 9.75), (17.5, 14.25))]
    return [s(d), fl(*dots)]


icon('ic_set_palette', 'background style: a round palette with a thumb notch and four paints', palette())
icon('ic_set_refresh', 'refresh: an open ring with a tangential arrowhead',
     [s(arc(C, 7.75, 25, 300), chevron(polar(C, 7.75, 300), 300 + 90 - 8, 3.0))])
icon('ic_set_sliders', 'sound: three faders with solid knobs',
     [s(line((6, 4), (6, 20)), line((12, 4), (12, 20)), line((18, 4), (18, 20))),
      fl(circle((6, 14.25), 2.4), circle((12, 8.5), 2.4), circle((18, 12.75), 2.4))])


def stopwatch():
    c, r = (12, 13.25), 7.75
    a0, a1 = -90, 0
    wedge = 'M' + pt(c) + ' L' + pt(polar(c, 5.1, a0)) + arc(c, 5.1, a0, a1, False) + ' Z'
    return [s(circle(c, r), line((12, c[1] - r), (12, 3)), line((9.75, 3), (14.25, 3))), fl(wedge)]


icon('ic_set_timer', 'short tracks: a stopwatch with the elapsed sector filled', stopwatch())
icon('ic_set_trash', 'remove: a lid with an arched grip over a tapered bin, apart',
     [s(line((3.75, 6.25), (20.25, 6.25)), arc((12, 6.25), 2.5, 180, 360),
        rpoly([(5.75, 6.25 + SEP), (6.9, 20.5), (17.1, 20.5), (18.25, 6.25 + SEP)], [0, 2, 2, 0], closed=False),
        line((10.25, 12), (10.25, 17)), line((13.75, 12), (13.75, 17)))])
icon('ic_set_wave', 'spectrum: five bars standing on one line',
     [s(*(line((x, 19.5), (x, t)) for x, t in ((4.5, 13.5), (8.25, 7), (12, 10.25), (15.75, 4.75), (19.5, 14))))])
_wc = (12, 18.5)
icon('ic_set_wifi', 'Wi-Fi: three arcs around a dot',
     [s(*(arc(_wc, r, 270 - 42, 270 + 42) for r in (4.5, 9, 13.5))), fl(circle(_wc, 1.4))])


def crescent():
    o, ro = (11.25, 12.75), 8.25
    i, ri = (15.25, 8.5), 6.6
    p, q = circle_x(o, ro, i, ri)
    a0, a1 = angle_of(o, p), angle_of(o, q)
    # the outer rim the long way round, then the inner bite back
    while a1 < a0:
        a1 += 360
    if a1 - a0 < 180:
        p, q = q, p
        a0, a1 = angle_of(o, p), angle_of(o, q)
        while a1 < a0:
            a1 += 360
    b0, b1 = angle_of(i, q), angle_of(i, p)
    while b1 > b0:
        b1 -= 360
    d = arc(o, ro, a0, a1) + arc(i, ri, b0, b1, False) + ' Z'
    return [s(d, line((19.5, 2.75), (19.5, 6.25)), line((17.75, 4.5), (21.25, 4.5)))]


icon('ic_sleep_timer', 'sleep timer: a crescent moon and a small star', crescent())

# --- transport (filled, rounded; viewports fixed by the Now playing layout)
PLAY = [(1.25, 1.0), (21.25, 12.5), (1.25, 24.0)]
PLAY_R = 2.25
icon('ic_play', 'play: a rounded triangle', [fl(rpoly(PLAY, PLAY_R))], size=(22, 25), view=(22, 25))

PAUSE_R = 2.0
icon('ic_pause', 'pause: two rounded bars',
     [fl(rrect(0.35, 0.25, 7.35, 22.25, PAUSE_R)), fl(rrect(14.35, 0.25, 21.35, 22.25, PAUSE_R))],
     size=(21.7, 22.5), view=(21.7, 22.5))


def skip(mirror):
    tri = [[(0.75, 0.75), (15, 8), (0.75, 15.25)], [(15, 0.75), (29.25, 8), (15, 15.25)]]
    bar = [(30, 0.75), (32.5, 0.75), (32.5, 15.25), (30, 15.25)]
    if mirror:
        tri = [[(33 - x, y) for x, y in t] for t in tri]
        bar = [(33 - x, y) for x, y in bar]
    return [fl(rpoly(tri[0], 1.6)), fl(rpoly(tri[1], 1.6)), fl(rpoly(bar, 1.1))]


icon('ic_next', 'next: two rounded triangles into a bar', skip(False), size=(28.6, 13.9), view=(33, 16))
icon('ic_prev', 'previous: a bar and two rounded triangles', skip(True), size=(28.6, 13.9), view=(33, 16))


# Shuffle and repeat (the Now playing toggles and the shuffle pills): own loops, open chevrons, stroke TW.
def shuffle():
    x0, x1, y0, y1 = 2.25, 18.75, 4.25, 13.75
    tip = 21.5
    a = ((x0 + 1.5, y0), (x0 + 8.5, y0), (x1 - 8.5, y1), (x1 - 1.5, y1))    # under strand: top-left to bottom-right
    b = ((x0 + 1.5, y1), (x0 + 8.5, y1), (x1 - 8.5, y0), (x1 - 1.5, y0))    # over strand, broken at the crossing
    b1, rest = cubic_split(b, 0.38)
    _, b2 = cubic_split(rest, (0.62 - 0.38) / (1 - 0.38))
    under = line((x0, y0), a[0]) + cubic(a, False) + f' L{f(tip)},{f(y1)}'
    over1 = line((x0, y1), b1[0]) + cubic(b1, False)
    over2 = cubic(b2) + f' L{f(tip)},{f(y0)}'
    return [s(over1, over2, under, chevron((tip, y0), 0, 2.7), chevron((tip, y1), 0, 2.7), w=TW)]


icon('ic_shuffle', 'shuffle: two strands crossing over and under, open arrowheads', shuffle(),
     size=(22.3, 15.6), view=(24, 18))


def repeat_loop():
    cy, hh = 8.5, 5.25
    lc, rc = (6.5, cy), (17.5, cy)
    top, bot = cy - hh, cy + hh
    a = arc(lc, hh, 140, 270) + f' L16.5,{f(top)}'
    b = arc(rc, hh, -40, 90) + f' L7.5,{f(bot)}'
    return [a, b, chevron((16.5, top), 0, 2.6), chevron((7.5, bot), 180, 2.6)]


icon('ic_repeat', 'repeat: a stadium loop in two arrowed halves', [s(*repeat_loop(), w=TW)],
     size=(20.8, 14.7), view=(24, 17))
icon('ic_repeat_one', 'repeat one: the loop with a 1 inside',
     [s(*repeat_loop(), w=TW), s(line((10.6, 6.7), (12.3, 5.6), (12.3, 11.4)), w=1.8)],
     size=(20.8, 14.7), view=(24, 17))


# ------------------------------------------------------------------------------------------------- play/pause morph

def avd():
    """Play (start) morphs into pause (end): each half of the rounded triangle becomes one rounded bar.

    Both ends are two shapes of four 'L + C' corner slots, so the path data are compatible for pathType animation."""
    (tl, tip, bl), r = PLAY, PLAY_R
    xm = 11.25
    ytop = tl[1] + (tip[1] - tl[1]) * (xm - tl[0]) / (tip[0] - tl[0])
    ybot = bl[1] - (bl[1] - tip[1]) * (xm - bl[0]) / (tip[0] - bl[0])
    left = slots([tl, (xm, ytop), (xm, ybot), bl], [r, 0, 0, r])
    # right half: A, the rounded tip, a degenerate slot where the fillet ends, B
    _, tip_end, _, _, _ = corner((xm, ytop), tip, (xm, ybot), r)
    right_pts = [(xm, ytop), tip, tip_end, (xm, ybot)]
    segs = [corner((xm, ybot), right_pts[0], tip, 0), corner((xm, ytop), tip, (xm, ybot), r),
            (tip_end, tip_end, 1, tip_end, tip_end), corner(tip, right_pts[3], (xm, ytop), 0)]
    right = 'M' + pt(segs[-1][1])
    for a, b, _, c1, c2 in segs:
        right += f' L{pt(a)} C{pt(c1)} {pt(c2)} {pt(b)}'
    right += ' Z'
    pr = PAUSE_R
    bars = [(1.0, 8.0), (14.0, 21.0)]
    y0, y1 = 1.5, 23.5
    pause = [slots([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], [pr] * 4) for x0, x1 in bars]
    return left + ' ' + right, pause[0] + ' ' + pause[1]


# ------------------------------------------------------------------------------------------------------------ output

HEADER = '<?xml version="1.0" encoding="utf-8"?>\n'
NOTE = 'Generated by design/icons/gen_icons.py; edit the generator, not this file.'


def path_xml(layer, indent='    '):
    if layer[0] == 's':
        return (f'{indent}<path\n{indent}    android:pathData="{layer[1]}"\n{indent}    android:fillColor="{CLEAR}"\n'
                f'{indent}    android:strokeColor="{WHITE}"\n{indent}    android:strokeWidth="{f(layer[2])}"\n'
                f'{indent}    android:strokeLineCap="round"\n{indent}    android:strokeLineJoin="round" />\n')
    return f'{indent}<path\n{indent}    android:pathData="{layer[1]}"\n{indent}    android:fillColor="{WHITE}" />\n'


def vector_xml(name, ic):
    (w, h), (vw, vh) = ic['size'], ic['view']
    return (HEADER + f'<!-- Seion icon, {ic["desc"]}. {NOTE} -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{f(w)}dp" android:height="{f(h)}dp"\n'
            f'    android:viewportWidth="{f(vw)}" android:viewportHeight="{f(vh)}">\n'
            + ''.join(path_xml(lay) for lay in ic['layers']) + '</vector>\n')


def avd_xml():
    start, end = avd()
    return (HEADER + f'<!-- Play (start) morphs into pause (end): each half of the rounded triangle becomes one rounded bar\n'
            f'     (200 ms). {NOTE} -->\n'
            '<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt">\n'
            '    <aapt:attr name="android:drawable">\n'
            '        <vector\n'
            '            android:width="22dp"\n'
            '            android:height="25dp"\n'
            '            android:viewportWidth="22"\n'
            '            android:viewportHeight="25">\n'
            '            <path\n'
            '                android:name="glyph"\n'
            f'                android:fillColor="{WHITE}"\n'
            f'                android:pathData="{start}" />\n'
            '        </vector>\n'
            '    </aapt:attr>\n'
            '    <target android:name="glyph">\n'
            '        <aapt:attr name="android:animation">\n'
            '            <objectAnimator\n'
            '                android:duration="200"\n'
            '                android:interpolator="@android:interpolator/fast_out_slow_in"\n'
            '                android:propertyName="pathData"\n'
            f'                android:valueFrom="{start}"\n'
            f'                android:valueTo="{end}"\n'
            '                android:valueType="pathType" />\n'
            '        </aapt:attr>\n'
            '    </target>\n'
            '</animated-vector>\n')


def svg(ic, colour='#000'):
    (vw, vh) = ic['view']
    body = ''
    for lay in ic['layers']:
        if lay[0] == 's':
            body += (f'<path d="{lay[1]}" fill="none" stroke="{colour}" stroke-width="{f(lay[2])}" '
                     'stroke-linecap="round" stroke-linejoin="round"/>')
        else:
            body += f'<path d="{lay[1]}" fill="{colour}"/>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{f(vw * 4)}" height="{f(vh * 4)}" '
            f'viewBox="0 0 {f(vw)} {f(vh)}">{body}</svg>\n')


def main(argv):
    for name, ic in ICONS.items():
        with open(os.path.join(OUT, name + '.xml'), 'w') as fh:
            fh.write(vector_xml(name, ic))
    with open(os.path.join(OUT, 'avd_play_pause.xml'), 'w') as fh:
        fh.write(avd_xml())
    print(f'{len(ICONS) + 1} drawables written to {os.path.relpath(OUT, ROOT)}')
    if '--svg' in argv:
        out = argv[argv.index('--svg') + 1]
        os.makedirs(out, exist_ok=True)
        for name, ic in ICONS.items():
            with open(os.path.join(out, name + '.svg'), 'w') as fh:
                fh.write(svg(ic))
        print(f'SVG previews in {out}')


if __name__ == '__main__':
    main(sys.argv)
