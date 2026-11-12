# Seion "no cover" tile

The placeholder shown wherever a track or album has no art (rows, the albums grid, the album header, the mini-player,
Now playing and the media-session artwork). All coordinates are on the glyph's 100 × 100 viewport, drawn at the full
tile size.

| File | What is in it |
|---|---|
| `gen_no_cover.py` | Generates the shipped glyph (`ic_no_cover_glyph.xml`, `no_cover.svg`), prints the measurements, and with `--gallery DIR` writes a gallery with every variant (S, T, U) |
| `no_cover.svg` | The shipped tile (ink square and the gradient note), for reference |

```sh
python3 -B design/no-cover/gen_no_cover.py /tmp/no-cover-out [--gallery /tmp/no-cover-gallery]
cp /tmp/no-cover-out/ic_no_cover_glyph.xml app/src/main/res/drawable/
```

## The shipped tile (variant S, "Single swing")

- **Tile:** the launcher ink `#16171A` (`NoCoverColors.Tile`, the same value as `seion_icon_tile`), edge to edge, no
  border or hairline; the corner is the caller's (the cover's own).
- **Mark:** a single eighth note, the same motif as the UI icon set's `ic_note` (`design/icons/`). The stem and the
  flag are one round-capped stroke, **5 units** wide (2 dp on a 40 dp row tile): the stem rises from the head, turns
  over through a quarter circle (r 3.2) and the flag swings out and down in one cubic, ending in a soft inward hook, so
  there are no sharp joins. The notehead is a filled ellipse (8.6 × 6.4, tilted −25°); the stem's outer edge meets the
  head's rightmost point, so stem and head read as one shape.
- **Size:** the mark is about 35 × 54 % of the tile, its bounding box centred.
- **Colour:** the launcher's amber `#FFC453` → coral `#FF7F5C`, linear, from the mark's top-left to its bottom-right
  corner (the same gradient on the head's fill and the line's stroke, `aapt:attr` in the drawable). The drawable is
  drawn untinted by both `NoCoverTile` (Compose) and `NoCoverArt.bitmap` (media session), so they match.
- **Contrast** on the ink (WCAG): **7.21:1** (coral) to **11.33:1** (amber), every point of the gradient ≥ 7.2:1.

The variants in the gallery: S (shipped), T (a longer, steeper flag), U (stroke 6.2 and a larger head).

## Ambient and accent

Seion Infinite without a cover is mostly black: the field is the tile's ink `#16171A`, so the tile sits in it without
a seam, and a soft light in the gradient's midpoint (`#FFA258`, the mix of `Amber` and `Coral` computed at render time)
glows at the top of the screen: a top-anchored elliptical falloff, peak opacity 0.38, fading smoothly to nothing before
mid-screen, dithered against banding (`ui/ambient/NoCoverGlow.kt`). The mini-player's backdrop uses the same rule over
its bar. Change `NoCoverColors` and the glow follows (`AmbientNoCoverGlowTest`). The accent and classic base still come
from the midpoint through the cover rules (`NoCoverColors.Accent` #EBA670, `ClassicBase` #C25600). The shared
ambient's key is `no-cover:v3`.
