# FT's Geology

A Minecraft mod that builds the world from plate tectonics. Plates drift, and their edges become mountain belts, volcanic arcs, rifts and ocean ridges. The same model then runs the living parts of the world: earthquakes come from its faults, volcanoes sit where magma would rise, hot springs and geysers follow the heat, rivers drain the land it made, and the weather moves over it.

![A wooded mountain range above a winding river](https://raw.githubusercontent.com/wiki/Jelada-Studios/fts-geology/images/01-mountain-range.jpg)

**Minecraft 1.20.1, Forge 47 or newer. No other mods needed.** A NeoForge 1.21.1 version is on the way.

The [wiki](https://github.com/Jelada-Studios/fts-geology/wiki) explains everything in detail. In game, a 52-page field guide (book + copper ingot) does the same.

## What it adds

- **Terrain.** A new default world type, 960 blocks tall and wider to match: 10 m to a block both ways. Mountain belts are cut from real elevation data of the Alps, the Caucasus, the Himalaya, the Karakoram and the Appalachians, and the tall world has one Everest, one K2 and one Matterhorn. A normal-height version is under World Type.
- **Plate boundaries.** Rifts, subduction zones, collision belts and transform faults, each with its own landforms, rock layers and ore deposits.
- **Earthquakes.** Each stretch of a fault builds up strain and breaks in its own time. P and S waves, aftershocks, liquefaction on wet sand, landslides and rockfall. How badly a build is hit depends on what it is made of and what it stands on.
- **Volcanoes.** Stratovolcanoes, shields, calderas and fissure eruptions, in three sizes and three states. They warn before they erupt with quake swarms and swelling ground. Eruptions bring lava, bombs, pyroclastic flows, gas and ashfall.
- **Hot springs and geysers.** Geothermal basins with springs in four stages, mud pots, fumaroles and geysers on a cycle.
- **Rivers.** A river network traced from the land. Streams widen as they join, and every river reaches a lake or the sea. River water is its own block: it steps down the valley in small falls and still works as water for boats, farms and Create.
- **Water and soil.** Soil wets in the rain and dries in the sun, and the grass colour shows it. Pumping a well lowers the water table around it. Heavy rain on soaked ground floods the rivers, and a thin dam breaks.
- **Weather.** Rain comes with storms that drift on the wind instead of falling everywhere at once: showers, fronts and long wet spells, with rain shadows behind the ranges. With Serene Seasons, rainfall and rivers follow the seasons.
- **Gases.** Firedamp by coal seams, carbon dioxide in craters and caves, natural gas and hydrogen sulfide over oil fields. Nine gases in all, with pipes, tanks, an electrolyser, a gas engine and a gas heater.
- **Instruments.** Geologist's hammer, fault compass, core drill, geothermal probe, geological map, seismograph network, weather station and volcano observatory. 18 advancements.
- **Power.** A geothermal turbine on a well drilled into hot ground, the gas engine, and with Create a crankshaft.

## Older worlds

The new terrain needs a new world. A world made with an older version keeps its own terrain, in new chunks as well. Earthquakes, volcanoes, weather, gases and the instruments work in it.

## Off by default

Two settings in the world's `serverconfig/fts_geology.toml` stay off until you turn them on:

- `soilWaterChangesGround`: droughts change the ground. Grass dies back in a long drought, a field away from water stays moist while its soil is wet, and soil soaks up water poured on it.
- `emergentEnabled`: player-built geysers. Water over rock over lava turns into a live geyser, which can blow up a base. Natural geysers do not need it.

## Works with

Terralith (its biomes grow on the mod's terrain), TerraFirmaCraft, Distant Horizons, Create, Electrodynamics, Serene Seasons, Tough As Nails, Flowing Fluids and Oculus. With Tectonic, TerraBlender, Biomes O' Plenty or BYG installed, the default world stays theirs and the mod's world is under World Type. See [Compatibility](https://github.com/Jelada-Studios/fts-geology/wiki/Compatibility).

**FT's Geology: Feel the Nature** is a separate addon. Among other things it plays recorded rain and wind.

## Bug reports

Open an [issue](https://github.com/Jelada-Studios/fts-geology/issues) with the mod version, your other mods and `logs/latest.log`. For terrain, add the world seed and the coordinates.

## Building

Java 17. Run `./gradlew build` and take the jar from `build/libs/`. Use `build`, not `jar`: the game needs the reobfuscated jar.

## License

Copyright (C) 2026 Jelada Studios. FT's Geology is free software under the GNU General Public License v3.0: see `LICENSE`. Versions up to 0.10 were released under the MIT License. Third-party data and sounds are credited in `CREDITS.md`.

The code, this README and the wiki were written with the help of an AI model.
