# FT's Geology — Realistic Plate Tectonics, Landforms & Geophysics

Most world-generation mods decide what a place looks like. **FT's Geology decides *why*.**

Instead of scattering volcanoes, geysers, and fault lines across a world with arbitrary spawn chances, FT's Geology runs a true plate tectonic simulation beneath your feet. Rigid lithospheric plates drift, collide, shear, and pull apart across the mantle. Every landform in the world is derived directly from its tectonic setting:
- **A volcano's shape** is not picked from a list — it emerges from the chemistry of the plate boundary it sits on.
- **Earthquakes** strike where locked faults accumulate elastic strain, giving ~10 seconds of early warning on a mechanical seismograph before rupture waves hit.
- **Geothermal basins** flow with boiling springs, stepped travertine terraces, zoned microbial mats, bubbling mud pots, and steaming fumaroles.
- **The subterranean crust** is stratified with real geological rock beds (gabbro, rhyolite, serpentinite, schist, gneiss, marble, and more) waiting to be surveyed with a rock hammer.

The result is a world you can genuinely **read and understand**.

---

## 🌋 Tectonic Features

### 1. Volcanoes Governed by Tectonic Chemistry
In the real Earth, silica content ($SiO_2$) controls magma viscosity, and tectonic boundaries dictate silica content:
- **Shield Volcanoes**: Formed over mantle hotspots and oceanic rifts. Low-silica, fluid basalt flows for thousands of blocks, building massive, gently sloping shield mountains.
- **Stratovolcanoes**: Formed along subduction zones. Gas-rich, viscous andesite and rhyolite explode violently, building steep composite cones of alternating tephra, ash, and blocky lava.
- **Calderas**: Catastrophic collapse basins formed when massive shallow magma chambers evacuate rapidly, causing the entire volcanic summit to collapse inward.
- **Fissure Eruptions**: Linear rift fractures where basalt fountains along miles of crustal dike, flooding valleys without building a central peak.

### 2. Earthquakes with Early Warning & Physics
- **Living Fault Lines**: Continental collision zones, extensional rifts, transform strike-slip faults, and megathrust subduction margins.
- **Elastic Rebound**: Locked faults store tectonic strain over time until friction fails.
- **Early Warning**: Seismographs detect the fast primary (P) compressional wave and trigger sirens ~10 seconds before the slower, destructive secondary (S) shear waves arrive.
- **Realistic Ground Displacement**:
  > **Nothing is destroyed, but unsupported blocks fall.**  
  > An unsupported block or building foundation sinks like sand and survives intact; it is not deleted or blown up. Both behaviors are fully customizable via config toggles:
  > - `quakesBreakBuilds`: whether earthquakes can deform player blocks.
  > - `fallingIncludesBuilds`: whether unsupported player structures drop with the settling ground.

### 3. Hydrothermal Systems & Geothermal Basins
Hot springs evolve through four distinct stages:
1. **Nascent Pool**: Superheated water (>85°C) scours a clear, mineral-dense pool through bedrock.
2. **Flowing Mat Spring**: Extremophile bacteria colonize thermal outflow zones, creating vivid concentric rings (yellow/orange carotenoids in warm water, green/brown cyanobacteria in cooler outflows).
3. **Terraces & Sinter**: Rapid chemical precipitation deposits wide sheets of porous limestone sinter and stepped carbonate travertine dams, accompanied by bubbling acidic mud pots.
4. **Senescent Fumaroles**: As thermal energy wanes or water tables drop, pools boil dry into steaming fumarole chimneys crusted with bright yellow native sulfur crystals.

---

## 🛠️ The Geologist's Scientific Toolkit

No magic wands or fantasy scanners. The mod provides authentic field instruments:
- **Geologist's Hammer**: Knock fresh faces off outcrops to identify lithology (volcanic, plutonic, sedimentary, metamorphic). Strike the ground to drill a stratigraphic section, reading layer thicknesses in real scaled metres rather than arbitrary blocks.
- **Fault Compass**: Modelled on a geologist's Brunton field compass. Samples the underlying plate, reading boundary strike, drift speed (cm/yr), heading, distance to fault, and whether the boundary is locked (storing strain) or creeping.
- **Seismograph**: Fixed recording station with a rotating mechanical drum. Measures S-P arrival time intervals to calculate distance to the focus and trace amplitude for Richter magnitude. One station gives a distance circle; three fix the epicentre.
- **Geological Field Guide**: In-game 18-page field handbook documenting landscape reading, instruments, hot spring stages, volcano types, earthquake mechanics, and simulation commands.

---

## ⚙️ Compatibility & Mod Integrations

- **No Hard Dependencies**: FT's Geology runs completely standalone on bare Forge 1.20.1.
- **Terralith & Tectonic**: FT's Geology reads the world's biome source rather than overwriting worldgen, stacking cleanly on top of Terralith, Tectonic, and vanilla world generation.
- **Flowing Fluids & Water Erosion**: Automatically detected! FT's Geology defers surface fluid dynamics to Flowing Fluids (finite water) for realistic drainage and erosion without conflict.
- **Retrogen-Safe**: Automatically bolts onto existing server saves without corrupting old chunks.
