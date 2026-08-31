# FT's Geology — CurseForge page copy

*Draft for the alpha listing. Everything below is ready to paste; trim the sections you do not want.*

---

## Short description (one line)

Real plate tectonics for Minecraft: fault lines, earthquakes that build landforms, volcanoes shaped
by their setting, and geysers that actually run on heat and pressure.

---

## Description

**FT's Geology** puts a working geological model underneath your world.

It starts with tectonic plates. The world is divided into plates that each drift in their own
direction, and where two of them meet, what happens depends on how they are moving relative to each
other — exactly as it does on Earth. Everything else the mod does follows from that one fact.

### What you will find

**Plate boundaries you can map.** `/geology map` hands you a filled map painted with the fault
network, so you can see where the boundaries run, where they curve, and where three of them meet.

**Four kinds of boundary, four different landscapes.**

| Boundary | On the surface | Underground |
|---|---|---|
| **Spreading rift** | Fissure volcanism, geysers, hot springs, a graben valley | A swarm of vertical basalt dykes cutting the whole crust |
| **Subduction arc** | Stratovolcanoes, the richest geothermal ground | The descending slab, magma chambers above it, granite roots under the arc |
| **Continental collision** | One asymmetric range: steep front, broad plateau behind, foreland basin at its foot. Hot springs, and pointedly **no** volcanoes | A thick root of folded marble and gneiss |
| **Transform fault** | Hot springs, offset streams and ridges | A narrow scar of shattered rock |

That table is the mod's argument. The Himalaya has the thickest crust on Earth and not one volcano;
Tibet is covered in hot springs anyway. The San Andreas has hot springs and no volcanism. The model
knows the difference, and so will anyone who plays with it.

**Earthquakes that leave something behind.** A rupture follows the real curve of the fault, its
length comes from the standard surface-rupture scaling law, and its magnitude is drawn from the
Gutenberg–Richter distribution — so small quakes are common and the giants are rare and worth
travelling for. What it does to the ground depends on the boundary: a rift drops a graben and opens
a fissure, a subduction margin digs a long trench against a rising volcanic arc, a collision belt
throws up a single range with a steep front and a broad plateau behind it, a strike-slip fault
carries the landscape sideways and cuts anything crossing it. The whole boundary moves together,
slowly, over a couple of minutes — and afterwards the ground settles: raw spikes fall, crests shed
talus at their feet, and the forest reseats itself. A tree whose ground dropped a little comes down
with it, roots and all; where the slope actually failed, the scarp is left bare.

**Volcanoes that are the shape their setting makes them.** A steep layered stratocone with a funnel
crater at a subduction arc. A vast low shield over a hotspot. A line of spatter ramparts along a
rift. A collapsed caldera with a ring-fault scarp, a resurgent dome and geyser basins around the
rim. You can tell where you are standing by the shape of the mountain.

**Geysers with actual thermodynamics.** A geyser is a real chamber: water over a heat source under a
rock cap. It heats, it pressurises, it erupts, it drains, it refills, and it cools. Mine the heat
out and it dies. It bores its own vent upward over time, and if it breaks into a cave on the way it
erupts *there* first.

**Hot springs with the colours.** The rings around a spring are alive — each band is a community of
microorganisms that can only survive in its own temperature range, so the colours are a thermometer
you can see. Sinter at the lip, then orange, yellow, brown and green outward as the runoff cools.
Sulfur crusts the acidic fumaroles instead, so the ground tells you the chemistry.

**Mid-ocean ridges.** Most of the planet's volcanism is underwater, so the sea floor gets it too: a
ridge with an axial rift down its crest, pillow lava, black smokers, and a sediment blanket that
thickens away from the axis — dig a trench across one and you can read the age of the crust. Set off
a rift earthquake down there and the gap does not stay a hole: new basalt freezes into it, which is
sea-floor spreading happening in front of you.

### Compatibility

The tectonic model **reads** your world rather than replacing it. No worldgen is overridden, no
biome source is taken over, no noise settings are touched — so it stacks on top of **Terralith** and
**Tectonic** instead of fighting them. Plate crust types are read from whatever biome source is
installed, and if a terrain mod has already painted a Yellowstone or a caldera, the model settles on
it rather than putting its own somewhere else.

Safe on existing worlds: nothing above Y=-30 is disturbed in natural generation, and **player-placed
blocks are never broken** — by generation, by an eruption, or by an earthquake.

### Commands

| Command | What it does |
|---|---|
| `/geology map` | A filled map painted with the fault network |
| `/geology plate` | Full readout for your column: plate, crust, drift, boundary, stress |
| `/geology suitability` | Why a geyser, spring or volcano can or cannot form here |
| `/geology column` | The vertical section under you, bedrock to surface |
| `/geology find <setting> [tp]` | Locate the nearest subduction, rift, collision, transform or hotspot |
| `/geology quake [type] [magnitude]` | Trigger an earthquake, or force a style anywhere to compare them |
| `/geology place <feature>` | Build a geyser, hot spring, or any of the four volcano types |
| `/geology deepgen [radius]` | Regenerate the deep boundary geology around you |

### Configuration

Everything is tunable in `config/fts_geology.toml`: plate size, fault width, hotspot density,
earthquake recurrence and severity, how far generation may reach, and per-tick budgets for every
heavy operation. The mod is written to slow itself down rather than ever stall a tick.

### Alpha notes

This is an early release, and it is being tuned against screenshots from real play. Known limits:

- Balance numbers — feature density, recurrence intervals, how big a landform a given magnitude
  makes — will keep moving.
- Earthquake deformation is deliberately conservative near anything player-built.
- The mod is single-player and dedicated-server safe, but has had far more hours on the former.

Bug reports with a screenshot and the relevant lines from `latest.log` are enormously useful — the
mod logs what it does, on purpose.

---

## Tags

`worldgen` `adventure` `library-api: no` `1.20.1` `Forge`
