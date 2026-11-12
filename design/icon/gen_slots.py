"""Generates the Slots launcher icon: an amber-to-coral disc cut by two round-ended slots.

The silhouette is baked into one closed path (two disc arcs, two slot edges, two round slot ends each), so the same
path data serves the launcher foreground (gradient), the monochrome layer, the notification icon and the design
sources.

Standalone (Python 3, no packages). Run from the repo root:
    python3 -B design/icon/gen_slots.py OUT_DIR
It writes the four SVG layers, the three launcher XMLs, ic_notification.xml and path.txt to OUT_DIR and prints the
measurements; copy what changed into design/icon/slots/ and app/src/main/res/drawable/ by hand."""
import os
import sys
from math import sqrt, hypot


def _lum(hexc):
    def ch(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (int(hexc.lstrip('#')[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def contrast(a, b):
    la, lb = sorted((_lum(a), _lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def paint_at(box, x, y):
    """The two-stop gradient at (x, y), interpolated in sRGB like Skia."""
    x1, y1, x2, y2 = box
    dx, dy = x2 - x1, y2 - y1
    t = max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)))
    a = [int(AMBER[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(CORAL[i:i + 2], 16) for i in (1, 3, 5)]
    return '#' + ''.join('%02X' % round(a[k] + (b[k] - a[k]) * t) for k in range(3))


def edge_contrast(box, step=0.3):
    """Min/max WCAG contrast of the mark against the ink tile over every edge pixel (0.3 dp grid, visible 72 dp)."""
    xs = [19 + i * step for i in range(int(70 / step) + 1)]
    me = [[inside(x, y) for x in xs] for y in xs]
    crs = []
    for j in range(1, len(xs) - 1):
        for i in range(1, len(xs) - 1):
            if me[j][i] and not (me[j][i + 1] and me[j][i - 1] and me[j + 1][i] and me[j - 1][i]):
                crs.append(contrast(paint_at(box, xs[i], xs[j]), INK))
    return min(crs), max(crs)

C = 54.0
SCALE = 0.85
R0, WS0, DY0, CAP0 = 27.5, 8.4, 9.2, 7.5       # base geometry before the 0.85 scale
AMBER, CORAL, INK = '#FFC453', '#FF7F5C', '#16171A'


def n(v):
    s = f'{v:.3f}'.rstrip('0').rstrip('.')
    return '0' if s == '-0' else s


def geometry(cx, cy, k):
    """Disc radius, slot half-width, slot centreline offset and cap offset at scale k of the base geometry, centred at cx, cy."""
    return dict(cx=cx, cy=cy, r=R0 * k, h=WS0 / 2 * k, dy=DY0 * k, cap=CAP0 * k)


def s_path(cx, cy, k):
    g = geometry(cx, cy, k)
    r, h, dy, cap = g['r'], g['h'], g['dy'], g['cap']
    t_top, t_bot = cy - dy - h, cy - dy + h          # top slot edges
    b_top, b_bot = cy + dy - h, cy + dy + h          # bottom slot edges
    xo = lambda y: sqrt(r * r - (y - cy) ** 2)      # half chord at y
    A = (cx + xo(t_top), t_top)
    B = (cx + xo(t_bot), t_bot)
    Cp = (cx - xo(b_bot), b_bot)
    D = (cx - xo(b_top), b_top)
    p = lambda q: f'{n(q[0])},{n(q[1])}'
    return (f'M{p(A)} L{p((cx - cap, t_top))} A{n(h)},{n(h)} 0 0,0 {p((cx - cap, t_bot))} L{p(B)} '
            f'A{n(r)},{n(r)} 0 0,1 {p(Cp)} L{p((cx + cap, b_bot))} A{n(h)},{n(h)} 0 0,0 {p((cx + cap, b_top))} '
            f'L{p(D)} A{n(r)},{n(r)} 0 0,1 {p(A)} Z')


def inside(x, y, cx=C, cy=C, k=SCALE):
    g = geometry(cx, cy, k)
    if hypot(x - cx, y - cy) > g['r']:
        return False

    def seg(px, py, a, b):
        (ax, ay), (bx, by) = a, b
        dx, dy = bx - ax, by - ay
        t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        return hypot(px - ax - t * dx, py - ay - t * dy)
    s1 = ((cx + 36 * k, cy - g['dy']), (cx - g['cap'], cy - g['dy']))
    s2 = ((cx - 36 * k, cy + g['dy']), (cx + g['cap'], cy + g['dy']))
    return seg(x, y, *s1) > g['h'] and seg(x, y, *s2) > g['h']


def grad_box(cx=C, cy=C, k=SCALE):
    d = R0 * k * 0.72                                 # the gradient spans the disc's diagonal
    return (cx - d, cy - d, cx + d, cy + d)


HDR = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
       '    xmlns:aapt="http://schemas.android.com/aapt"\n'
       '    android:width="108dp" android:height="108dp"\n'
       '    android:viewportWidth="108" android:viewportHeight="108">\n')
HDR_PLAIN = HDR.replace('    xmlns:aapt="http://schemas.android.com/aapt"\n', '')


def vd_gradient_fill(d, box, ind='    '):
    x1, y1, x2, y2 = box
    return (f'{ind}<path\n{ind}    android:pathData="{d}">\n'
            f'{ind}    <aapt:attr name="android:fillColor">\n'
            f'{ind}        <gradient android:type="linear" android:startX="{n(x1)}" android:startY="{n(y1)}" '
            f'android:endX="{n(x2)}" android:endY="{n(y2)}">\n'
            f'{ind}            <item android:offset="0" android:color="#FF{AMBER[1:]}" />\n'
            f'{ind}            <item android:offset="1" android:color="#FF{CORAL[1:]}" />\n'
            f'{ind}        </gradient>\n{ind}    </aapt:attr>\n{ind}</path>\n')


def main(out):
    os.makedirs(out, exist_ok=True)
    d = s_path(C, C, SCALE)
    box = grad_box()
    g = geometry(C, C, SCALE)
    xml = '<?xml version="1.0" encoding="utf-8"?>\n'
    files = {
        'ic_launcher_foreground.xml': xml + (
            '<!-- Seion launcher icon \"Slots\": an amber-to-coral disc cut by two slots that leave an S\n'
            f'     (disc r {n(g["r"])}, slots {n(2 * g["h"])} wide). One baked path, inside\n'
            '     the 66 dp safe zone. Sources and notes: design/icon/. -->\n') + HDR + vd_gradient_fill(d, box) + '</vector>\n',
        'ic_launcher_background.xml': xml + '<!-- Seion launcher tile: ink #16171A (design/icon/). -->\n' + HDR_PLAIN + (
            '    <path\n        android:pathData="M0,0 H108 V108 H0 Z"\n        android:fillColor="#FF16171A" />\n</vector>\n'),
        'ic_launcher_monochrome.xml': xml + '<!-- Slots monochrome layer (themed icons): the same silhouette as the foreground. -->\n'
            + HDR_PLAIN + f'    <path\n        android:pathData="{d}"\n        android:fillColor="#FF000000" />\n</vector>\n',
    }
    # Notification small icon: 24 dp, the disc 20 dp across (2 dp padding), flat white.
    kn = 10 / R0
    files['ic_notification.xml'] = xml + (
        '<!-- Media notification small icon: the Slots silhouette, flat white, 20 dp disc in the 24 dp box. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp" android:height="24dp"\n    android:viewportWidth="24" android:viewportHeight="24">\n'
        f'    <path\n        android:pathData="{s_path(12, 12, kn)}"\n        android:fillColor="#FFFFFFFF" />\n</vector>\n')
    for k, v in files.items():
        open(os.path.join(out, k), 'w').write(v)
    # SVG layers (108 canvas, unmasked) for design/icon/slots/
    lg = (f'<defs><linearGradient id="g" x1="{n(box[0])}" y1="{n(box[1])}" x2="{n(box[2])}" y2="{n(box[3])}" '
          f'gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="{AMBER}"/><stop offset="1" stop-color="{CORAL}"/>'
          '</linearGradient></defs>')
    bg = f'<path d="M0,0 H108 V108 H0 Z" fill="{INK}"/>'
    fg = f'{lg}<path d="{d}" fill="url(#g)"/>'
    mono = f'<path d="{d}" fill="#000000"/>'

    def svg(body, what):
        return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">\n'
                f'<!-- Seion icon "Slots": {what} (108 x 108 adaptive canvas, unmasked). -->\n{body}\n</svg>\n')
    for nm, body in (('background.svg', bg), ('foreground.svg', fg), ('monochrome.svg', mono), ('full.svg', bg + fg)):
        open(os.path.join(out, nm), 'w').write(svg(body, nm[:-4]))
    open(os.path.join(out, 'path.txt'), 'w').write(d + '\n')

    # Measurements
    mn, mx = edge_contrast(box)
    worst = min(contrast(AMBER, INK), contrast(CORAL, INK))
    bands = {'top band (disc top to top slot)': (C - g['dy'] - g['h']) - (C - g['r']),
             'middle band (between the slots)': 2 * (g['dy'] - g['h']),
             'slot width': 2 * g['h']}
    print('path', d)
    print(f'disc r {g["r"]:.3f} (d {2 * g["r"]:.3f}) dp; was r {R0}')
    for k_, v in bands.items():
        print(f'{k_}: {v:.2f} dp (at 24 px: {v / 3:.2f} px)')
    print(f'gradient box {box}')
    print(f'contrast mark vs ink along the edge: min {mn:.2f} max {mx:.2f}; worst stop pair {worst:.2f}')
    return d


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'out')
