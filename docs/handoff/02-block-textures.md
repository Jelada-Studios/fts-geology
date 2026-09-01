# Handoff: block textures for FT's Geology

Repo root: `C:\Users\ileti\OneDrive\Desktop\Yazılım Geliştirme\geysersmod`
Mod: Minecraft Forge 1.20.1, modId `fts_geology`, JDK 17.
Build with `./gradlew build --no-daemon` from the repo root.

## The problem

The mod registers 12 blocks but ships only 6 textures. The other 6 borrow vanilla ones through
their model JSON, and two of them borrow the *same* one:

| block | currently shows | why that is wrong |
|---|---|---|
| `geyser_igniter` | `minecraft:block/magma` | identical to `volcano_igniter` in the creative menu |
| `volcano_igniter` | `minecraft:block/magma` | identical to `geyser_igniter` in the creative menu |
| `hot_spring` | `minecraft:block/calcite` | a signature block of the mod, reads as plain calcite |
| `geyser_core` | `minecraft:block/deepslate` | indistinguishable from the rock around it |
| `geyser_chamber` | `minecraft:block/deepslate` | same |
| `volcano_core` | `minecraft:block/basalt_top` | same |

The two igniters being visually identical is the worst of these: a player cannot tell them apart
in the creative inventory, and they do completely different things.

## What to produce

Six 16×16 PNG files with an alpha channel, written to:

```
src/main/resources/assets/fts_geology/textures/block/geyser_igniter.png
src/main/resources/assets/fts_geology/textures/block/volcano_igniter.png
src/main/resources/assets/fts_geology/textures/block/hot_spring.png
src/main/resources/assets/fts_geology/textures/block/geyser_core.png
src/main/resources/assets/fts_geology/textures/block/geyser_chamber.png
src/main/resources/assets/fts_geology/textures/block/volcano_core.png
```

Then point each block model at its new texture. For example
`src/main/resources/assets/fts_geology/models/block/hot_spring.json` becomes:

```json
{
  "parent": "minecraft:block/cube_all",
  "textures": {
    "all": "fts_geology:block/hot_spring"
  }
}
```

Change only the `textures` value in those six files. Do not touch the `parent`, and do not touch
the blockstates, the item models, or any Java.

## House style, measured from the existing six textures

Every existing texture was generated procedurally, and they share a signature you should match:

| texture | colours in 256 px | luminance range |
|---|---|---|
| `sinter.png` | 72 | 186..254 |
| `native_sulfur.png` | 97 | 153..237 |
| `microbial_mat_orange.png` | 76 | 97..149 |
| `microbial_mat_yellow.png` | 74 | 134..214 |
| `microbial_mat_green.png` | 59 | 75..115 |
| `microbial_mat_brown.png` | 62 | 68..113 |

So: **many closely spaced shades inside a narrow luminance band**, no hard outlines, no black.
Roughly 60–100 distinct colours per tile, spanning about 40–80 luminance levels. That is a
mineral-crust look, and it is right for the organic surface blocks.

**Do not use that look for all six.** Split them:

**Surface / geological blocks — keep the procedural crust style above:**

- `hot_spring` — the bed of a hot spring pool. Sits under water, radiates warmth, and players
  build spas out of it. Wet pale mineral crust: think travertine terrace, warm off-white to pale
  ochre, faint concentric banding. Related to `sinter.png` but warmer and darker; the two must not
  be confusable side by side.
- `volcano_core` — buried inside a volcano's throat. Dark basaltic rock with heat still in it:
  near-black grey with a few dull red-orange cracks. Read it as hot rock, not as lava.

**Technical blocks — fewer colours, deliberately synthetic, clearly not natural stone:**

- `geyser_core` — the block entity that runs the whole geyser simulation, buried at the bottom of
  the vent. Should read as machinery-in-rock: dark deepslate ground with a pale mineral-filled
  vein pattern, a distinct geometric structure rather than noise.
- `geyser_chamber` — a passive marker lining the water chamber. The same family as `geyser_core`
  but plainly quieter: same ground, weaker and sparser veining, no focal point. A player who sees
  both should read chamber as "more of the same" and core as "the important one".

**Player-placed igniters — these two must be readable at 16 px and must not resemble each other:**

- `geyser_igniter` — placed by hand, smokes for a few seconds, then builds a geyser below and
  removes itself. Suggest pressurised water and steam: cool blue-white, a bright centre, pale
  vapour. Cool palette.
- `volcano_igniter` — the same idea for a volcano. Suggest magma: deep red-orange, a hot glowing
  centre, dark crust around it. Warm palette.

Cool-vs-warm is the main thing separating them; do not rely on shape alone, because at 16 px in an
inventory slot the hue is what a player actually registers.

## Hard constraints

1. **Exactly 16×16.** Not 32×32, not 64×64.
2. **PNG with an alpha channel**, every pixel fully opaque (alpha 255). These are solid cubes.
3. **Must tile seamlessly.** All six use `minecraft:block/cube_all`, so they are placed edge to
   edge. The left column must continue into the right column, and the top row into the bottom row.
   Verify this — do not assume it.
4. **No pure black (#000000) and no pure white (#FFFFFF).** Nothing in vanilla uses them and they
   read as holes.
5. Every texture must stay distinguishable from the vanilla block it currently borrows, and the
   two igniters must stay distinguishable from each other.

## Also in this handoff: one icon revision

`C:\Users\ileti\.gemini\antigravity\scratch\geology_icons\field_guide.png` needs redoing. The other
three icons in that folder are fine and should be left alone.

Two problems with the current one: the book occupies only x=3..11 of the 16-pixel frame, so it
looks small and floats; and the cover is rendered as speckled brown, which at 16 px reads as a
cookie rather than as leather. Fill more of the frame (roughly x=2..14), and replace the speckle
with a plain leather field plus either a highlight along the spine or a couple of stitch lines.
Keep it 16×16 with real transparency, as the current one is.

## How to check your work before reporting done

1. `./gradlew build --no-daemon` from the repo root exits 0.
2. All six PNGs are exactly 16×16 and fully opaque.
3. Each of the six model JSONs references `fts_geology:block/<name>`, and no `minecraft:block/`
   texture reference remains in those six files.
4. Tiling: place two copies of each texture side by side and top to bottom, and confirm no seam.
5. Put `geyser_igniter.png` and `volcano_igniter.png` next to each other at 16 px and confirm you
   can tell which is which without reading the filenames.

Report what you changed, and say explicitly which of the five checks you actually ran.

## Do not

- Do not change any `.java` file.
- Do not change any blockstate JSON or item model JSON.
- Do not add, remove or rename blocks.
- Do not touch `src/main/resources/assets/fts_geology/lang/` — the localisation there is finished
  and correct, and any edit to it will be reverted.
- Do not touch `docs/site/` or `docs/trailer-cards/`.
