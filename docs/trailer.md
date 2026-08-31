# FT's Geology — trailer shot list

A running order for a 60–90 second cut. Each shot names the command that sets it up, so you can film
them in any order and assemble afterwards. Shoot in creative with flight, render distance 16+, and
turn **smooth lighting on / clouds off** so the terrain reads clearly.

Useful before you start:

```
/gamerule doDaylightCycle false
/time set noon
```

Set the time per shot instead — several of these want a low sun for the shadows, and the geyser and
the black smokers want night.

---

## 1. Cold open — the map (0:00–0:06)

```
/geology map
```

Hold the filled map up. Slow push in on the painted fault network. This is the thesis of the whole
mod in one frame: the world has boundaries, and they run somewhere specific.

**Time:** noon. **Shot:** first person, map in hand, no HUD.

---

## 2. The boundary from the air (0:06–0:14)

```
/geology find subduction tp
```

Fly along the fault at about Y+60, camera tilted 30° down, moving parallel to the boundary. You want
the arc high ground on one side and the trench on the other in the same frame.

**Time:** early morning — a low sun rakes across the relief and the asymmetry reads instantly.

---

## 3. The earthquake (0:14–0:34) — the centrepiece

```
/geology quake subduction 8.5
```

Stand about 40 blocks off the fault, on the uplifted side, looking along it. The whole boundary
starts moving at once and keeps going for around two minutes, so you have plenty of material:

- **0:14** the announcement in chat, the ground starts to shift
- **0:20** cut to an aerial tracking shot down the length of the rupture
- **0:28** cut back to ground level at the trench edge, looking up at the new scarp

Let a few seconds of the weathering run before you cut — the crests visibly shed and talus gathers.

**Time:** late afternoon. **Tip:** film it twice; the second take is always better framed.

---

## 4. Underground (0:34–0:42)

```
/geology find collision tp
/geology deepgen 4
```

Dig or `/tp` into a horizontal tunnel around Y=-40 and pan along the wall. The folded marble and
gneiss banding is the shot. Optionally cut in `/geology column` output for a beat — it reads as a
scientific instrument, which is the tone you want.

**Time:** irrelevant, but bring torches or turn the brightness up.

---

## 5. Yellowstone (0:42–0:56)

```
/geology find hotspot tp
```

Aerial, straight down, then descend into it. The coloured rings around a spring are the prettiest
thing the mod makes — sinter, orange, yellow, brown, green — and they photograph best from directly
above at midday, then again low and level so the steam catches the light.

**Time:** midday for the colours, then dawn for the steam. Two shots, same location.

---

## 6. A geyser at night (0:56–1:06)

```
/geology place geyser
```

Wait for the eruption, or place several and pick the best. Film from low and to one side so the
column is against the sky. Night with a clear moon.

---

## 7. Volcano (1:06–1:18)

```
/geology place strato
```

Wide establishing shot of the cone, then wait for an eruption: the summit fountains, bombs arc out,
and a tongue of lava runs down the flank and turns to rock behind its own front.

**Time:** dusk — the lava reads far better against a dark sky.

---

## 8. Under the sea (1:18–1:26)

```
/geology find rift tp
```

Find the submerged part of the boundary. Swim down the axial valley to a black smoker with its plume
rising. Use night vision; do **not** use a resource pack that clears the water fog — the murk is
what sells the depth.

---

## 9. Close — the map again (1:26–1:32)

Return to the fault map, then cut to the mod name. Bookending on the map says: everything you just
watched came from that one picture.

---

## Notes

- **No HUD.** F1. Take the map shot before you hide it, or hold the map and press F1 after.
- **Smooth camera.** Fly with the mouse, not the keyboard, or use a replay/camera mod for the
  tracking shots.
- **Let things breathe.** The earthquake is slow on purpose; a two-second cut wastes it.
- **Sound.** The earthquake rumble and the eruption boom are already in the mod — record game audio
  and lay music underneath rather than over.
