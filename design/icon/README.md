# Launcher icon sources

`slots/` holds the launcher icon that ships. `sweep/` is an alternative design that is kept around but not used.
All coordinates are on the 108 x 108 adaptive-icon canvas; the launcher shows the centre 72 x 72 and the safe zone is
the centred 66 dp circle.

| Path | Contents |
|---|---|
| `slots/` | SVG layers (`background`, `foreground`, `monochrome`, `full`) and `android/ic_launcher_*.xml`, identical to `app/src/main/res/drawable/` |
| `sweep/` | The same set for the alternative design |
| `gen_slots.py` | Regenerates the Slots layers, the launcher XMLs and the notification icon, and prints the measurements |

## Slots

An amber-to-coral disc cut by two round-ended slots, one coming in from the right above the centre and one from the
left below it, so what is left of the disc reads as an S.

- Disc: centre (54, 54), radius 23.375.
- Slots: 7.14 wide with round ends, centrelines 7.82 above and below the centre.
- The S bands are at least 8.5 wide, so the mark still reads at 24 px.
- Colours: amber `#FFC453` to coral `#FF7F5C` along the diagonal, on an ink tile `#16171A`. The lowest contrast
  against the tile is about 7.2:1.
- The silhouette is one baked path (arcs and lines, no clip paths). The same path is used for the monochrome themed
  icon, `ic_notification` (flat white) and the splash screen.

## Sweep

A single round-capped stroke (6.6 wide): a flat lead-in, a slanted S and a flat exit, coloured from amber on the
right to coral on the left, on the same ink tile.

To switch to it, copy `sweep/android/ic_launcher_*.xml` over `app/src/main/res/drawable/` and redraw
`ic_notification` from the Sweep path. Don't add the unused design's drawables to `app/src/main/res`; lint flags
unused resources.
