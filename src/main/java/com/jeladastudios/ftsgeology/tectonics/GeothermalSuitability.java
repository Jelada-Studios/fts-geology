package com.jeladastudios.ftsgeology.tectonics;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

/**
 * Decides how likely a volcano, a geyser or a hot spring is at a given column, from the tectonic
 * setting. This is the single gate worldgen asks before placing any geothermal feature.
 *
 * <h2>The geology it encodes</h2>
 * Not every plate boundary is geothermally alive, and the three features have different
 * requirements:
 *
 * <ul>
 *   <li><b>Volcanoes need magma.</b> Magma is generated where a slab subducts (water lowers the
 *       melting point of the mantle wedge) and where plates rift apart (decompression melting), and
 *       over a mantle plume. Two colliding continents produce none - the Himalaya has no volcanoes
 *       at all - and neither does a strike-slip fault like the San Andreas or the North Anatolian.</li>
 *   <li><b>Geysers need magma AND water AND tight plumbing.</b> They are genuinely rare: roughly a
 *       thousand exist worldwide, in about five fields. So they are restricted to the magmatic
 *       settings, and are strongest over hotspots - Yellowstone alone holds around half of them.</li>
 *   <li><b>Hot springs only need water to circulate deep along faults.</b> That happens at every
 *       kind of boundary, magmatic or not: Tibet is covered in them despite the Himalaya having no
 *       volcanism, and there are hot springs all along the North Anatolian and San Andreas faults.
 *       So collision and transform zones get hot springs but never geysers - which is exactly the
 *       distinction that makes the model worth teaching from.</li>
 * </ul>
 */
public final class GeothermalSuitability {

    private GeothermalSuitability() {}

    /**
     * Placement multipliers for one column, applied on top of the configured base spawn chance.
     * Volcano is 0..1; geyser and hot spring may exceed 1 inside a hotspot geyser basin, where the
     * ground really is several times more thermally active than anywhere else on the planet.
     * {@code reasonKey} explains the verdict in plain language for the inspection command. It is a
     * translation key rather than a finished sentence: this class runs on the server and has no
     * business deciding what language the player reads, and the text has to be reachable from a
     * lang file for the mod to be translatable at all.
     */
    public record Suitability(double volcano, double geyser, double hotSpring, String reasonKey) {

        public boolean anything() {
            return volcano > 0 || geyser > 0 || hotSpring > 0;
        }
    }

    public static Suitability at(ServerLevel level, int x, int z) {
        PlateSample plate = TectonicMap.sampleCached(level, x, z);
        HotspotMap.Hotspot hot = HotspotMap.sample(level, x, z);

        // --- Boundary contribution ------------------------------------------
        // Stress already folds in distance to the fault and how hard the plates work, so it makes
        // activity fade out naturally as you walk away from the line.
        double s = plate.stress();
        double volcano = 0, geyser = 0, hotSpring = 0;
        String reasonKey;

        switch (plate.faultType()) {
            case CONVERGENT_SUBDUCTION -> {
                // The classic volcanic arc: Andes, Cascades, Japan, Kamchatka.
                volcano = 1.00 * s;
                geyser = 0.90 * s;
                hotSpring = 1.00 * s;
                reasonKey = "command.fts_geology.suitability.reason.subduction";
            }
            case DIVERGENT -> {
                // Rift / spreading ridge: Iceland, East African Rift.
                volcano = 0.75 * s;
                geyser = 1.00 * s;
                hotSpring = 1.00 * s;
                reasonKey = "command.fts_geology.suitability.reason.rift";
            }
            case CONVERGENT_COLLISION -> {
                // Himalaya: enormous mountains and abundant hot springs, but no magma at all.
                volcano = 0.0;
                geyser = 0.0;
                hotSpring = 0.65 * s;
                reasonKey = "command.fts_geology.suitability.reason.collision";
            }
            case TRANSFORM -> {
                // San Andreas, North Anatolian: faults conduct water, but generate no melt.
                volcano = 0.0;
                geyser = 0.0;
                hotSpring = 0.55 * s;
                reasonKey = "command.fts_geology.suitability.reason.transform";
            }
            case INTERIOR -> {
                // Deep sedimentary basins still host warm springs (Bath, Hungary), just barely.
                hotSpring = 0.05;
                reasonKey = "command.fts_geology.suitability.reason.interior";
            }
            default -> reasonKey = "command.fts_geology.suitability.reason.unknown";
        }

        // --- Hotspot contribution -------------------------------------------
        // A plume works independently of any boundary, so it competes rather than adds: whichever
        // setting is more active wins. This is what puts a Yellowstone in the middle of a plate.
        if (hot.strength() > 0.0) {
            double h = hot.strength();
            // Inside the dome, geysers cluster into BASINS rather than spreading evenly. Spreading
            // them evenly over a 700-block plume gave roughly one vent per twenty chunks, which is a
            // scatter, not Yellowstone. The boost applies only inside a basin, so the field has
            // crowded hot ground in some places and quiet country in between - as the real one does.
            double basin = HotspotMap.basinStrength(level, x, z);
            double boost = 1.0 + (GeyserConfig.HOTSPOT_FEATURE_BOOST.get() - 1.0) * basin;
            // A biome that is already a collapsed caldera keeps its heat but gets no new cone: the
            // edifice is standing there in the terrain, and building another one inside it would be
            // nonsense. Yellowstone is exactly this case - a caldera, no active volcano, and half
            // the geysers on the planet.
            if (ThermalBiomes.allowsVolcano(level, x, z)) volcano = Math.max(volcano, 1.00 * h);
            geyser = Math.max(geyser, h * boost);   // the richest geyser fields on Earth
            hotSpring = Math.max(hotSpring, h * boost);
            reasonKey = basin > 0.15
                    ? "command.fts_geology.suitability.reason.hotspot_basin"
                    : "command.fts_geology.suitability.reason.hotspot";
        } else if (hot.onTrail()) {
            // The extinct chain the plate carried off the plume: old cones, no live heat.
            double remaining = 1.0 - hot.trailAge();
            volcano = Math.max(volcano, 0.35 * remaining);
            hotSpring = Math.max(hotSpring, 0.30 * remaining);
            reasonKey = "command.fts_geology.suitability.reason.hotspot_trail";
        }

        // Volcano stays a plain 0..1 probability multiplier. Geyser and hot-spring may exceed 1
        // inside a geyser basin: they multiply the configured base chance, and capping them at 1
        // would silently throw the whole point of the basin away.
        double ceiling = Math.max(1.0, GeyserConfig.HOTSPOT_FEATURE_BOOST.get());
        return new Suitability(
                Mth.clamp(volcano, 0.0, 1.0),
                Mth.clamp(geyser, 0.0, ceiling),
                Mth.clamp(hotSpring, 0.0, ceiling),
                reasonKey);
    }
}
