# Seion UI icons

Every UI glyph in `app/src/main/res/drawable/` (library tabs, transport, queue, settings rows, navigation, dialogs)
is built by `gen_icons.py` from primitives: lines, circular arcs, tilted ellipses and rounded polygons. No path data
comes from an icon library.

```sh
python3 -B design/icons/gen_icons.py              # rewrites the UI vectors in app/src/main/res/drawable/
python3 -B design/icons/gen_icons.py --svg DIR    # also writes one SVG per icon into DIR for review
```

Edit the generator, not the XML. Not generated here: the launcher layers and the notification icon (`design/icon/`),
the no-cover glyph (`design/no-cover/`) and the two feature emblems `ic_brand_*`.

## House style

- **Grid:** 24 × 24 viewport, live area 3–21. Stroke **1.75**, round caps and joins; containers rounded at 2.5–3.
- **Signature gap:** where one part of a glyph would touch another (a lens and its handle, a bin and its lid, a band
  and its ear cups, a cloud's base and the arrow through it), the parts stay **1.25** apart.
- **Notes:** a single eighth note with a filled head tilted −22°, a stem from the head's right edge and one swung flag
  (`ic_note`, `ic_music_off`, `ic_playlist`, `ic_set_identify`; the no-cover glyph is the same note drawn large).
- **Transport:** filled shapes with rounded corners (play, pause, previous, next); shuffle and repeat are stroke 2 with
  open chevron arrowheads, matching the 1.75 dp rings of the Now playing buttons.
- **Colour:** white on transparent, coloured by the caller's tint.
- **Viewports kept from the layout:** `ic_play` and `avd_play_pause` 22 × 25, `ic_pause` 21.7 × 22.5, `ic_prev` and
  `ic_next` 33 × 16, `ic_shuffle` 24 × 18, `ic_repeat*` 24 × 17, `ic_shelf` 26 × 23.5, `ic_more` 5 × 19.6. Now playing
  sizes these boxes in code, so their aspect ratios stay.
- **Play/pause morph:** both ends of `avd_play_pause` are two shapes of four fixed "line + cubic" corner slots, so the
  path data stay compatible for a `pathType` animation (each half of the rounded triangle becomes one rounded bar).
