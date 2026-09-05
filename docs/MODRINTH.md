# FT's Geology — Real Plate Tectonics, Landforms & Geophysics

[![CurseForge](https://img.shields.io/badge/CurseForge-Available-orange)](https://curseforge.com)
[![Modrinth](https://img.shields.io/badge/Modrinth-Available-green)](https://modrinth.com)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20Forge-blue)](https://minecraft.net)

Most world-generation mods decide what a place looks like. **FT's Geology decides *why*.**

Instead of scattering volcanoes, springs, and quakes across the world by random chance, FT's Geology puts a working plate tectonic simulation beneath your feet. Landforms are derived directly from the crustal physics of the plate boundary they sit on. The goal is a world you can explore, survey, and understand.

---

## 📸 Screenshots

| Geothermal Basin Floor with Mineral Springs | Stratovolcano Erupting at Dusk |
| :---: | :---: |
| ![Geothermal Basin](https://github.com/jeladastudios/geysersmod/raw/main/docs/screenshots/01_geothermal_basin_springs.jpg) | ![Stratovolcano](https://github.com/jeladastudios/geysersmod/raw/main/docs/screenshots/02_stratovolcano_erupting.jpg) |
| *Concentric microbial mats, travertine terraces & sinter* | *Ash plume, incandescent fissure glow & basaltic lava* |

| Rift Scarp After an Earthquake | High-Altitude Fumarole Field |
| :---: | :---: |
| ![Rift Scarp](https://github.com/jeladastudios/geysersmod/raw/main/docs/screenshots/03_rift_scarp_after_quake.jpg) | ![Fumarole Field](https://github.com/jeladastudios/geysersmod/raw/main/docs/screenshots/04_fumarole_field.jpg) |
| *Offset strata; ground subsides intact without block deletion* | *Tapering chimneys venting steam and native sulfur* |

| The Geologist's Scientific Toolkit |
| :---: |
| ![Geology Instruments](https://github.com/jeladastudios/geysersmod/raw/main/docs/screenshots/05_geology_instruments_in_hand.jpg) |
| *Fault compass, geologist's hammer, mechanical drum seismograph & field guide* |

---

## 🌟 What Makes FT's Geology Unique?

### 1. Plate Tectonic Simulation
- **Rigid Lithospheric Plates**: The entire world map is partitioned into distinct oceanic and continental tectonic plates with continuous drift velocities and headings.
- **Boundaries**: Continental collisions, extensional rift valleys, transform strike-slip shear faults, and oceanic subduction zones.
- **Subterranean Stratigraphy**: Digging beneath an arc yields real geological rock types (gabbro, rhyolite, serpentinite, schist, gneiss, marble, and chert).

### 2. Volcanoes Shaped by Chemistry
Magma viscosity is governed by silica content ($SiO_2$), which is dictated by the plate setting:
- **Shield Volcanoes**: Formed over mantle hotspots and oceanic rifts from fluid basaltic lava flows.
- **Stratovolcanoes**: Steep composite cones along subduction arcs that burst with explosive ash columns and incandescent flows.
- **Calderas**: Massive summit collapse depressions formed by the evacuation of shallow magma chambers.
- **Fissure Eruptions**: Extended crustal dikes along rift valleys flooding valleys with basalt.

### 3. Earthquakes & Ground Physics
- **Realistic Wave Propagation**: Seismographs record the primary (P) compressional wave and provide **~10 seconds of early warning** before secondary (S) shear waves arrive.
- **True Ground Physics**:
  > **Nothing is destroyed, but unsupported blocks fall.**  
  > An unsupported block or house foundation sinks like sand and survives; it is never deleted or turned into crater holes. Both settings (`quakesBreakBuilds` and `fallingIncludesBuilds`) are fully configurable in `fts_geology.toml`.

### 4. Hydrothermal Evolution
Hot springs progress through four distinct natural stages:
1. **Nascent Pool**: Superheated clear water (>85°C) scours a bedrock pool.
2. **Microbial Mats**: Temperature-zoned extremophile bacteria form concentric color rings (yellow/orange carotenoids in warm water, green/brown in cooler shallows).
3. **Terraces & Sinter**: Porous chalky sinter sheets and stepped carbonate travertine dams form alongside bubbling mud pots.
4. **Senescent Fumaroles**: When water drops below the heat source, tapering fumarole chimneys vent pressurized steam and deposit bright yellow native sulfur crusts.

---

## 🧰 The Scientific Instruments

- **Geologist's Hammer**: Knock fresh faces off outcrops to identify rock origins (volcanic, plutonic, sedimentary, metamorphic). Striking the ground measures the stratigraphic section below in real scaled metres.
- **Fault Compass**: A Brunton structural compass. Measures boundary strike, plate velocity, drift direction, and whether the boundary is locked (storing strain) or creeping.
- **Seismograph**: Rotating drum recorder that charts earthquake arrivals. S-P interval gives exact hypocenter distance; trace amplitude calculates Richter magnitude.
- **Geological Field Guide**: In-game 18-page manual explaining landscape reading, instruments, hot springs, volcanoes, earthquakes, and commands in both English and Turkish.

---

## 🔌 Compatibility

- **No Hard Dependencies**: Runs standalone on standard Forge 1.20.1.
- **Terralith & Tectonic**: Stacks seamlessly on top of custom biome providers by sampling the underlying biome source.
- **Flowing Fluids & Water Erosion**: Automatically detected; defers surface fluid dynamics to finite water mods for natural drainage.
- **Retrogen-Safe**: Easily bolts onto existing server worlds without terrain corruption.
