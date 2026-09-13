package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import com.jeladastudios.ftsgeology.volcano.VolcanoPlan.Ctx;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A volcano that rises from the sea floor, written column by column while the chunk generates.
 *
 * <p>The shape follows Hawaii. A shield grows gently above the sea and steeply under it, since lava that meets
 * the water shatters and piles up instead of running on, so its flank breaks at the shoreline. Under the sea it is
 * pillow lava, round the shore a wedge of glassy rubble, above it thin flows. It stretches along two or three rift
 * zones. Where a flow reaches the sea it builds a bench of new land over black sand.</p>
 *
 * <p>Carried off its plume it dies and sinks. Rain cuts valleys into its windward side, the waves cut the shore
 * back into cliffs, a flank may have fallen into the sea and left an amphitheatre above a field of hummocks on
 * the sea floor. In warm water a reef grows round it and stays at the surface as the island goes down, until only
 * a ring of reef round a lagoon is left; in cold water the waves plane its top flat and it drowns as a guyot.</p>
 *
 * <p>Every height here is a function of the column's position and the plan alone, so any chunk, in any order,
 * builds the same island.</p>
 */
public final class OceanEdifice {

    private OceanEdifice() {}

    /** Above this climate temperature the sea is warm enough for reefs: vanilla's lukewarm and warm ocean bands. */
    static final double REEF_TEMPERATURE = 0.2;
    /** The coolest sea an atoll grows in: the lukewarm band too, as Bermuda's reefs do at 32 degrees north. */
    static final double ATOLL_TEMPERATURE = -0.15;

    /** How far the thin apron of debris runs over the sea floor past the foot. */
    static final int APRON = 30;
    /** The flank under the sea: steepest just under the shore, easing to the floor. Under 1.6 it stood as a wall. */
    static final double SUBMARINE_EXP = 1.25;
    /** How far anything of an island may reach from its centre, inside the field's cell margin. */
    static final int MAX_REACH = 596;

    /** How far a lava delta may build out past the shoreline. */
    private static final double DELTA_LEN = 8.0;

    /** A caldera wall's rounded lip, in blocks. */
    private static final double LIP = 2.0;
    /** Half-widths of the saddle a strait brings a caldera's ring down to, and of the channel through it. */
    private static final double BROAD_HALF = 45.0, CHANNEL_HALF = 12.0;
    /** How far the sand behind an atoll's reef runs down into the lagoon. */
    private static final double BACK_REEF = 10.0;

    /** What shaped a column, for its top block. */
    private static final int REEF = 1, LAGOON = 2, RIM = 4, MOTU = 8, DELTA = 16, CONE = 32, RING = 64, TOP = 128,
            CLIFF = 256, KAMENI = 512, POND = 1024;

    /** The ocean half of a plan. */
    static final class Isle {
        VolcanoSetting setting;
        /** 0 over the plume, rising along its track. */
        double age;
        /** The sea level: its top water block is one below this. */
        int seaY;
        /** Climate temperature at the centre, which picks the reef, the beach and the trees. */
        double seaTemp;
        int floorY;
        /** Height over the sea the island grew to. */
        int h0;
        /** How far it has gone down since. */
        double sunk;
        /** Run per block of height above the sea, and per block of depth below it. */
        double subaerial, submarine;
        /** Where the grown island met the sea and the sea floor, before rift zones and a ragged shore. */
        double shoreR0, footR;
        /** Where the flank meets the sea floor now it has sunk; the grown foot on a live island. */
        double footNow;
        double[] armAim = new double[0];
        /** Where the weather comes from: valleys run deeper and cliffs back further on that side. */
        double windAim;
        /** A fallen flank's bearing and half its width in radians; 0 wide means none. */
        double slideAim, slideHalf;
        int debrisLen;
        boolean tuffRing;
        double tuffAim, tuffX, tuffZ, tuffBase;
        /** Gaps through a reef, by bearing. */
        double[] passAim = new double[0];
        /** An atoll's rim radius and width, and its lagoon's depth. */
        double ringR, rimW, lagoonDepth;
        /** How far a guyot's planed top is under the sea. */
        double topDepth;
        /** Littoral cones where flows entered the sea. */
        double[] coneX = new double[0], coneZ = new double[0];
        /** A flooded caldera's young cone in the middle: its radius and its height over the sea. */
        double coneR, coneH;
        int noise;
        int edifice, reach;
        /** One bit a bearing, {@link #COAST_BEARINGS} round: set where the generator's own land meets the coast. */
        long coastLand;

        boolean warm() {
            return seaTemp > REEF_TEMPERATURE;
        }
    }

    // === Plan ===============================================================

    /**
     * Turns a plan made on the sea floor into an island. Draws from the plan's own random source after the plan has,
     * so the same site always gives the same island.
     */
    static void plan(Ctx c, RandomSource rng, VolcanoSetting setting, double age, int seaY, double seaTemp) {
        Isle k = new Isle();
        c.isle = k;
        boolean cone = c.type == VolcanoType.STRATOVOLCANO;
        boolean caldera = c.type == VolcanoType.CALDERA;
        k.setting = setting;
        k.age = age;
        k.seaY = seaY;
        k.seaTemp = seaTemp;
        k.floorY = c.baseY;
        // A flooded caldera's ring, some 250 blocks across: the land-sized collapse would not fit an island.
        double calderaRing = Mth.clamp(c.craterR * 0.6, 100.0, 140.0);
        // The land-sized cone the plan rolled, less of it above the sea: the rest is under water. A caldera's rim
        // stands higher the bigger the eruption that emptied it.
        // An old cone is worn low and round, not the sharp peak it was.
        boolean worn = cone && setting == VolcanoSetting.ERODED;
        k.h0 = caldera ? 18 + (int) Math.round(2.5 * Math.max(0, c.magnitude - 22))
                : Math.max(24, (int) Math.round(c.coneHeight * (worn ? 0.5 : cone ? 0.6 : 0.8)));
        k.subaerial = caldera ? 3.0 : cone ? 2.0 : 3.6;
        // Submarine flanks run out gently, some 1:2.5 on a cone and 1:3 on a shield.
        k.submarine = cone || caldera ? 2.5 : 3.0;
        k.shoreR0 = caldera ? calderaRing + 8 + k.h0 * k.subaerial : c.craterR + k.h0 * k.subaerial;
        double depth = Math.max(6, seaY - c.baseY);
        double stretchMax = (cone || caldera ? 1.0 : 1.36) * 1.11;
        // Kept inside the cell margin: a deep floor would otherwise push the foot past what a chunk may write.
        double footMax = (MAX_REACH - APRON - 4) / stretchMax;
        k.submarine = Math.min(k.submarine, Math.max(1.5, (footMax - k.shoreR0) / depth));
        k.footR = k.shoreR0 + depth * k.submarine;
        k.noise = rng.nextInt(1 << 16);

        // Two rift zones roughly opposite, or three roughly a third apart; a stratocone and a caldera have none.
        int arms = cone || caldera ? 0 : 2 + rng.nextInt(2);
        k.armAim = new double[arms];
        double first = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < arms; i++) {
            k.armAim[i] = first + i * Math.PI * 2 / arms + (rng.nextDouble() - 0.5) * 0.7;
        }
        k.windAim = rng.nextDouble() * Math.PI * 2;
        double slideRoll = rng.nextDouble();
        k.slideAim = rng.nextDouble() * Math.PI * 2;
        double slideWidth = 0.35 + 0.25 * rng.nextDouble();
        // Every old Hawaiian island has lost a flank; a young shield now and then shows a slump.
        k.slideHalf = setting == VolcanoSetting.ERODED
                || (setting == VolcanoSetting.ISLAND && !cone && !caldera && slideRoll < 0.35) ? slideWidth : 0.0;
        k.tuffRing = setting == VolcanoSetting.ERODED && rng.nextDouble() < 0.4;
        k.tuffAim = rng.nextDouble() * Math.PI * 2;
        int passes = 2 + rng.nextInt(2);
        k.passAim = new double[passes];
        for (int i = 0; i < passes; i++) k.passAim[i] = rng.nextDouble() * Math.PI * 2;
        k.rimW = 10 + 6 * rng.nextDouble();
        k.lagoonDepth = 6 + 4 * rng.nextDouble();
        double flowRoll = rng.nextDouble();
        double coneRoll = rng.nextDouble(), coneHeightRoll = rng.nextDouble();

        // Off its plume the island sinks under its own weight. A guyot was planed at the surface before it drowned.
        k.sunk = switch (setting) {
            case ERODED, ATOLL -> k.h0 * (0.25 + 0.95 * age);
            case GUYOT -> k.h0 * 0.8;
            default -> 0.0;
        };
        // Minecraft's seas are shallow: a guyot's top sits 6 to 14 blocks down, deeper the older it is.
        k.topDepth = setting == VolcanoSetting.GUYOT ? 6 + 8 * Mth.clamp((age - 0.55) / 0.45, 0.0, 1.0) : 0.0;
        k.footNow = footNow(c, k);
        // A reef that stayed at the surface stands where the shore was when the island had sunk a quarter of its height.
        k.ringR = c.craterR + (k.shoreR0 - c.craterR) * Math.pow(0.75, 1.0 / 1.3);
        if (caldera) {
            // The ring of cliffs round the drowned floor, and the young cone the vent has built since.
            k.ringR = calderaRing;
            k.rimW = 8;
            k.lagoonDepth += 6;
            k.coneR = 18 + 10 * coneRoll;
            k.coneH = 10 + 8 * coneHeightRoll;
        }

        k.edifice = (int) Math.ceil(k.footR * stretchMax) + 4;
        // Inside the field's cell margin, so neighbouring volcanoes never meet.
        k.debrisLen = k.slideHalf > 0 ? Math.max(0, Math.min(150, MAX_REACH - k.edifice - APRON)) : 0;
        k.reach = k.edifice + APRON + k.debrisLen;

        c.summitY = switch (setting) {
            case ISLAND -> seaY + k.h0;
            case ERODED -> seaY + (int) Math.round(k.h0 - k.sunk);
            case ATOLL -> seaY + 1;
            default -> seaY - 1 - (int) Math.round(k.topDepth);
        };
        c.coneHeight = Math.max(1, c.summitY - c.baseY);
        c.coneBaseR = (int) Math.round(k.footR);
        // A live island's flows reach the sea.
        c.flowReach = k.shoreR0 * (1.02 + 0.18 * flowRoll);
        c.apronLen = APRON;
        c.apronReach = k.reach;
        c.clearReach = k.reach;

        if (caldera) {
            // The live vent is the young cone's pond: the summit step seats the core under it.
            c.lakeX = c.x;
            c.lakeZ = c.z;
            c.lakeR = 3.5;
            c.lakeOuter = c.lakeR;
            c.calderaFloorY = seaY + (int) Math.round(k.coneH);
            c.craterR = (int) Math.ceil(k.coneR);
        }
        if (setting == VolcanoSetting.ISLAND && !cone && !caldera) {
            // A littoral cone at every other flow's mouth.
            int n = (c.flows + 1) / 2;
            k.coneX = new double[n];
            k.coneZ = new double[n];
            for (int j = 0; j < n; j++) {
                int i = j * 2;
                double rt = Math.min(c.flowReach, k.shoreR0 + 5);
                double a = c.flowAim[i] + VolcanoEdifice.flowWander(c, i, rt) / rt;
                double world = rt * stretch(k, a);
                k.coneX[j] = c.x + Math.cos(a) * world;
                k.coneZ[j] = c.z + Math.sin(a) * world;
            }
        }
        if (k.tuffRing) {
            // Rejuvenated-stage vents on an old island are small tuff rings by the coast, as at Diamond Head.
            double rt = shoreNow(c, k) - retreat(k, k.tuffAim) - 14;
            if (rt > c.craterR + 20) {
                double world = rt * stretch(k, k.tuffAim);
                k.tuffX = c.x + Math.cos(k.tuffAim) * world;
                k.tuffZ = c.z + Math.sin(k.tuffAim) * world;
                k.tuffBase = grown(c, k, rt) - k.sunk;
            } else {
                k.tuffRing = false;
            }
        }
    }

    // === Shape ==============================================================

    /** A column's place round the island, worked out once and shared by its shape and its blocks. */
    static final class Probe {
        double dist, ang, r;
        int bits;
    }

    /** How far the island reaches at a bearing against its round plan: longer along its rift zones, with a ragged shore. */
    static double stretch(Isle k, double ang) {
        double s = 1.0;
        for (double aim : k.armAim) {
            double d = Math.atan2(Math.sin(ang - aim), Math.cos(ang - aim)) / 0.4;
            s += 0.35 * Math.exp(-d * d);
        }
        double coast = VolcanoEdifice.polarNoise(ang, 0.0, k.shoreR0, k.noise, 1.0, 60.0)
                + 0.35 * VolcanoEdifice.polarNoise(ang, 0.0, k.shoreR0, k.noise + 977, 1.0, 17.0);
        return s * (1.0 + 0.08 * coast / 1.35);
    }

    /** Height of the grown island at a radius: a gentle shield above the sea and a steeper flank below it. */
    static double grown(Ctx c, Isle k, double r) {
        if (r <= c.craterR) return k.seaY + k.h0;
        if (r <= k.shoreR0) {
            double s = (r - c.craterR) / Math.max(1.0, k.shoreR0 - c.craterR);
            // A live cone is steep; an old one has been rounded off.
            return c.type == VolcanoType.STRATOVOLCANO
                    ? k.seaY + k.h0 * Math.pow(1.0 - s, k.setting == VolcanoSetting.ERODED ? 1.15 : 1.5)
                    : k.seaY + k.h0 * (1.0 - Math.pow(s, 1.3));
        }
        double u = Math.min(1.0, (r - k.shoreR0) / Math.max(1.0, k.footR - k.shoreR0));
        return k.seaY - (k.seaY - k.floorY) * (1.0 - Math.pow(1.0 - u, SUBMARINE_EXP));
    }

    /** Where a sunken shield's shore is now, as a radius. */
    static double shoreNow(Ctx c, Isle k) {
        if (k.sunk <= 0) return k.shoreR0;
        if (k.sunk >= k.h0) return c.craterR;
        return c.craterR + Math.pow(1.0 - k.sunk / k.h0, 1.0 / 1.3) * (k.shoreR0 - c.craterR);
    }

    /**
     * Where a sunken island's flank meets the sea floor, as a radius: well inside the foot it grew to, since the
     * whole mountain has gone down. Worked out from the profile in {@link #grown}.
     */
    static double footNow(Ctx c, Isle k) {
        if (k.sunk <= 0) return k.footR;
        double depth = k.seaY - k.floorY;
        if (k.sunk < depth) {
            return k.shoreR0 + (1.0 - Math.pow(k.sunk / depth, 1.0 / SUBMARINE_EXP)) * (k.footR - k.shoreR0);
        }
        // Sunk further than the sea is deep: what was dry flank now meets the floor.
        double above = k.sunk - depth;
        if (above >= k.h0) return c.craterR;
        return c.craterR + Math.pow(1.0 - above / k.h0, 1.0 / 1.3) * (k.shoreR0 - c.craterR);
    }

    /**
     * How far from the centre the coast lies at a bearing, in blocks, reef included; 0 for a guyot, which has none.
     * The site check wants open sea just past it, or the island would be joined to the shore.
     */
    static double coastAt(Ctx c, double ang) {
        Isle k = c.isle;
        double r = switch (k.setting) {
            case GUYOT -> 0.0;
            case ATOLL -> k.ringR * 1.06 + k.rimW * 0.5;
            case ERODED -> shoreNow(c, k) + (k.warm() ? 11.0 + 20.0 * k.age : 0.0);
            default -> k.shoreR0 + (c.type == VolcanoType.SHIELD ? DELTA_LEN : 0.0);
        };
        return r * stretch(k, ang);
    }

    /** How many bearings {@link Isle#coastLand} covers. */
    static final int COAST_BEARINGS = 32;

    /**
     * Whether the generator's own land stands at the coast on this bearing or the one either side of it, so the
     * strand keeps off it and the natural shore stays the shore there.
     */
    static boolean landAtCoast(Isle k, double ang) {
        if (k.coastLand == 0) return false;
        int i = (int) Math.floor((ang < 0 ? ang + Math.PI * 2 : ang) / (Math.PI * 2) * COAST_BEARINGS);
        for (int o = -1; o <= 1; o++) {
            if ((k.coastLand >>> Math.floorMod(i + o, COAST_BEARINGS) & 1L) != 0) return true;
        }
        return false;
    }

    /** How far the waves have cut an old island's shore back at a bearing. */
    static double retreat(Isle k, double ang) {
        return (6.0 + 26.0 * k.age) * (1.0 + 0.6 * Math.max(0.0, Math.cos(ang - k.windAim)));
    }

    /** The surface this column is shaped to, before anything laid on its own ground, and what shaped it. */
    static double shape(Ctx c, int gx, int gz, Probe p) {
        Isle k = c.isle;
        int dx = gx - c.x, dz = gz - c.z;
        p.dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        p.ang = Math.atan2(dz, dx);
        p.r = p.dist / stretch(k, p.ang);
        p.bits = 0;
        int water = k.seaY - 1;
        return switch (k.setting) {
            case GUYOT -> guyot(c, k, gx, gz, p, water);
            case ATOLL -> atoll(c, k, gx, gz, p, water);
            default -> c.type == VolcanoType.CALDERA ? flooded(c, k, gx, gz, p, water) : island(c, k, gx, gz, p, water);
        };
    }

    /**
     * A caldera the sea has flooded, as at Santorini: a ring of island round a drowned floor, broken by straits,
     * its cliffs facing inward, and in the middle the young cone the vent has built since, its crater crusted over
     * between eruptions, as Nea Kameni's is.
     */
    private static double flooded(Ctx c, Isle k, int gx, int gz, Probe p, int water) {
        double r = p.r, ang = p.ang;
        // Towards a strait the whole ring comes down, so the sea breaks in over a saddle rather than through a slot.
        double h = k.h0 * (1.0 - 0.85 * passShare(k, ang, p.dist, r, BROAD_HALF));
        double rimTop = k.seaY + h;
        double floor = water - k.lagoonDepth + 1.2 * ValueNoise.noise(gx + k.noise, gz - k.noise, 11.0);
        double y;
        if (r < k.ringR) {
            // The caldera wall: steep, its steepness changing round the ring, cut by gullies, with a rounded lip and
            // a fan of fallen rock at its foot.
            double steep = 1.8 + 0.4 * VolcanoEdifice.polarNoise(ang, 0.0, k.ringR, k.noise + 211, 1.0, 30.0);
            double in = k.ringR - r;
            double wall = rimTop - steep * Math.max(0.0, in - LIP * (1.0 - Math.exp(-in / LIP))) - gully(k, ang, in);
            double drop = Math.max(0.0, rimTop - floor);
            double fanLen = 13.0 + 3.0 * VolcanoEdifice.polarNoise(ang, 0.0, k.ringR, k.noise + 223, 1.0, 20.0);
            double past = in - (drop / steep + LIP) + fanLen * 0.35;
            double fan = floor + Math.min(10.0, drop * 0.35) * Math.pow(Mth.clamp(1.0 - past / fanLen, 0.0, 1.0), 2.0);
            if (wall > fan && wall > floor) {
                y = wall;
                p.bits |= CLIFF;
            } else {
                y = Math.max(fan, floor);
                p.bits |= LAGOON;
            }
        } else if (r <= k.shoreR0) {
            // Over the crest and down the outer slope, gentle at the top and at the sea: welded ash and pumice.
            double s = (r - k.ringR) / Math.max(1.0, k.shoreR0 - k.ringR);
            y = k.seaY + h * (1.0 - smooth(s));
        } else {
            double u = Math.min(1.0, (r - k.shoreR0) / Math.max(1.0, k.footR - k.shoreR0));
            y = k.seaY - (k.seaY - k.floorY) * (1.0 - Math.pow(1.0 - u, SUBMARINE_EXP));
        }
        if (r >= k.ringR - 2) y += VolcanoEdifice.surfaceNoise(c, gx, gz) * 1.8 * Mth.clamp((y - water) / 6.0, 0.0, 1.0);
        // The strait itself: a channel with sloping sides, deepest in its middle.
        double channel = passShare(k, ang, p.dist, r, CHANNEL_HALF);
        double strait = water - 7 + ValueNoise.noise(gx, gz - k.noise, 5.0);
        if (channel > 0 && strait < y) {
            y = Mth.lerp(channel, y, strait);
            if (channel > 0.5) p.bits = (p.bits & ~CLIFF) | LAGOON;
        }
        // The young cone, which owns its columns, with a pond held a block under its rim, and steep flanks under the
        // water down to the drowned floor.
        if (p.dist < k.coneR + 24) {
            double top = p.dist < k.coneR ? k.seaY + k.coneH * (1.0 - Math.pow(p.dist / k.coneR, 1.3))
                    : k.seaY - (p.dist - k.coneR) / 1.4;
            if (p.dist <= c.lakeR) {
                p.bits = KAMENI | POND;
                return c.calderaFloorY - 1;
            }
            if (p.dist <= c.lakeR + 1.5) {
                p.bits = KAMENI;
                return c.calderaFloorY;
            }
            if (top > y) {
                p.bits = KAMENI;
                return top;
            }
        }
        // No broad strand where the island meets a coast: the profile crosses the sea level and the coast keeps its shore.
        if ((p.bits & (CLIFF | LAGOON)) == 0 && !landAtCoast(k, ang)) y = beach(y, water);
        return y;
    }

    private static double island(Ctx c, Isle k, int gx, int gz, Probe p, int water) {
        double r = p.r, ang = p.ang;
        double y = grown(c, k, r) - k.sunk;
        // Rough ground above the sea, smooth at the shore so a beach stays a beach.
        if (r > c.craterR) {
            double land = Mth.clamp((y - water) / 6.0, 0.0, 1.0);
            y += VolcanoEdifice.surfaceNoise(c, gx, gz) * Math.min(3.0, 1.0 + k.h0 * 0.02) * land;
        }
        if (c.type == VolcanoType.STRATOVOLCANO && c.ridgeHeight > 0 && r > c.craterR && r < k.shoreR0) {
            double s = (r - c.craterR) / (k.shoreR0 - c.craterR);
            y += c.ridgeHeight * VolcanoEdifice.radialRidges(c, ang, p.dist) * 4.0 * s * (1.0 - s);
        }
        // Hummocky sea floor on the flank below the surf.
        double deep = Mth.clamp((water - 4 - y) / 8.0, 0.0, 1.0);
        y += 1.2 * ValueNoise.noise(gx + k.noise, gz - k.noise, 7.0) * deep;

        if (k.setting == VolcanoSetting.ERODED) {
            double now = shoreNow(c, k);
            if (y > water) y = Math.max(water - 2, y - valley(c, k, ang, r));
            y = slide(c, k, ang, r, y, water);
            if (r >= now - retreat(k, ang) && y > water - 2) {
                // Cut back by the waves: a cliff, and a platform at its foot.
                y = water - 2 + 0.6 * ValueNoise.noise(gx - k.noise, gz + k.noise, 5.0);
                p.bits |= CLIFF;
            }
            if (k.warm()) {
                double gap = 6 + 16 * k.age, width = 5 + 4 * k.age, out = r - now;
                if (!inPass(k, ang, p.dist)) {
                    if (out > 0 && out < gap) {
                        y = Math.min(y, water - 3 - 1.2 * (1 + ValueNoise.noise(gx, gz + 3 * k.noise, 8.0)) * 0.5);
                        p.bits |= LAGOON;
                    } else if (out >= gap && out < gap + width) {
                        y = Math.max(y, water - (ValueNoise.noise(gx - 5 * k.noise, gz, 6.0) < -0.3 ? 2 : 1));
                        p.bits |= REEF;
                    }
                }
            }
            if (k.tuffRing) {
                double d = Math.hypot(gx - k.tuffX, gz - k.tuffZ);
                if (d < 20) {
                    if (d < 6.5) y = Math.max(water + 2, k.tuffBase - 2 + d * 0.3);
                    else y = Math.max(y, k.tuffBase + 11.0 * Math.exp(-Math.pow((d - 9.0) / 4.0, 2)));
                    p.bits |= RING;
                }
            }
        } else {
            y = slide(c, k, ang, r, y, water);
            if (c.type == VolcanoType.SHIELD && y < water + 3 && r > k.shoreR0 * 0.8 && r < k.shoreR0 + DELTA_LEN
                    && VolcanoEdifice.flowAt(c, ang, r)) {
                // A flow that reached the sea: a bench of new land over its own shattered glass.
                y = Math.max(y, water + 1);
                p.bits |= DELTA;
            }
            for (int j = 0; j < k.coneX.length; j++) {
                double d = Math.hypot(gx - k.coneX[j], gz - k.coneZ[j]);
                if (d >= 4.0) continue;
                y = Math.max(y, water + 1 + 3.0 * (1.0 - d / 4.0));
                p.bits |= CONE;
            }
        }
        if ((p.bits & (REEF | RIM | MOTU | DELTA | CONE | RING | CLIFF | LAGOON)) == 0 && !landAtCoast(k, ang)) {
            y = beach(y, water);
        }
        return y;
    }

    /** Squeezes the ground towards the waterline, so a gentle shore is a wide strand that rises smoothly behind. */
    static double beach(double y, int water) {
        double h = y - (water + 0.5);
        if (h >= 5.5 || h <= -3.5) return y;
        double span = h >= 0 ? 5.5 : 3.5;
        return water + 0.5 + Math.signum(h) * Math.pow(Math.abs(h) / span, 1.8) * span;
    }

    /** Depth of an amphitheatre-headed valley at this point of an old island, deepest on the windward side. */
    static double valley(Ctx c, Isle k, double ang, double r) {
        double s = (r - c.craterR) / Math.max(1.0, k.shoreR0 - c.craterR);
        if (s <= 0.15) return 0.0;
        double v = VolcanoEdifice.polarNoise(ang, r, 40.0, k.noise + 101, 6.0, 14.0);
        double cut = (-v - 0.25) / 0.75;
        if (cut <= 0) return 0.0;
        double head = smooth(Mth.clamp((s - 0.15) / 0.2, 0.0, 1.0));
        double wind = 1.0 + 0.6 * Math.max(0.0, Math.cos(ang - k.windAim));
        double depth = (8.0 + 10.0 * Mth.clamp(k.age * 2.5, 0.0, 1.0)) * wind;
        // An old cone's flanks are cut deeper still: a stratocone's ash gullies faster than a shield's lava.
        if (c.type == VolcanoType.STRATOVOLCANO) depth *= 1.4;
        return depth * Math.pow(cut, 0.7) * head;
    }

    /**
     * The flank a collapse took away: an amphitheatre open to the sea, as the Nuʻuanu Pali is. A steep headwall
     * under the summit, a floor that runs from its foot down to the sea at the coast, and walls either side
     * that spread as the slide did. Returns the ground with the scar cut into it.
     */
    static double slide(Ctx c, Isle k, double ang, double r, double y, int water) {
        if (k.slideHalf <= 0) return y;
        // Measured against the shore as it is now: on a sunken island the shore it grew to is out under the sea,
        // and a scar set out from that began at the coast and never showed.
        double shore = k.setting == VolcanoSetting.ERODED ? shoreNow(c, k) : k.shoreR0;
        double head = c.craterR + (shore - c.craterR) * 0.3;
        if (r <= head) return y;
        double along = Mth.clamp((r - head) / Math.max(1.0, shore - head), 0.0, 1.0);
        double half = k.slideHalf * (1.0 + 0.6 * along);
        double d = Math.abs(Math.atan2(Math.sin(ang - k.slideAim), Math.cos(ang - k.slideAim)));
        double side = Mth.clamp((half - d) / (half * 0.3), 0.0, 1.0);
        if (side <= 0) return y;
        // Half of what stands over the sea today, so the floor starts a few blocks up and runs down to the water.
        double depth = k.setting == VolcanoSetting.ISLAND ? k.h0 * 0.35 : Math.max(6.0, (k.h0 - k.sunk) * 0.5);
        double headwall = smooth(Mth.clamp((r - head) / 6.0, 0.0, 1.0));
        // The scar floor: under the headwall, then down to the sea at the coast; never above the old ground.
        double top = Math.max(water - 1.0, grown(c, k, head) - k.sunk - depth);
        double floor = r <= shore ? Mth.lerp(along, top, water - 1.0)
                : Mth.lerp(Mth.clamp((r - shore) / Math.max(1.0, k.footR - shore), 0.0, 1.0), water - 1.0, k.floorY);
        double scar = Math.min(y, floor);
        return y - (y - scar) * headwall * smooth(side);
    }

    /** How far into a strait through a caldera's ring a point is, 0..1, across a half-width of {@code half} blocks. */
    static double passShare(Isle k, double ang, double dist, double r, double half) {
        double inward = smooth(Mth.clamp((r - k.ringR * 0.8) / 14.0, 0.0, 1.0));
        if (inward <= 0) return 0.0;
        double best = 0.0;
        for (double aim : k.passAim) {
            double d = Math.abs(Math.atan2(Math.sin(ang - aim), Math.cos(ang - aim))) * dist;
            best = Math.max(best, smooth(Mth.clamp(1.0 - d / half, 0.0, 1.0)));
        }
        return best * inward;
    }

    /** How deep a gully cuts a caldera wall here: notches a few blocks wide running down the cliff, none at its lip. */
    static double gully(Isle k, double ang, double in) {
        double v = VolcanoEdifice.polarNoise(ang, 0.0, k.ringR, k.noise + 401, 1.0, 7.0);
        double cut = (v - 0.4) / 0.6;
        if (cut <= 0) return 0.0;
        return 4.0 * Math.pow(cut, 0.8) * Mth.clamp(in / 5.0, 0.0, 1.0);
    }

    /** Height of the hummocks the fallen flank left on the sea floor, which shrink with distance. */
    static double debris(Isle k, int gx, int gz, Probe p) {
        if (k.debrisLen <= 0) return 0.0;
        double t = (p.r - k.footNow * 0.9) / k.debrisLen;
        if (t <= 0 || t >= 1) return 0.0;
        double d = Math.abs(Math.atan2(Math.sin(p.ang - k.slideAim), Math.cos(p.ang - k.slideAim)));
        double half = k.slideHalf * (1.0 + 0.6 * t);
        double side = Mth.clamp((half - d) / (half * 0.35), 0.0, 1.0);
        if (side <= 0) return 0.0;
        double bump = Math.max(0.0, ValueNoise.noise(gx + 3 * k.noise, gz - 3 * k.noise, 10.0) - 0.05) / 0.95;
        return (1.0 + 8.0 * Math.pow(1.0 - t, 1.3)) * bump * smooth(side);
    }

    /** The thin skin of debris the flank sheds over the sea floor, from where the flank meets the floor now. */
    static double apron(Isle k, Probe p) {
        double t = apronShare(k, p);
        if (t <= 0 || t >= 1) return 0.0;
        return 2.2 * Math.pow(1.0 - t, 1.5);
    }

    /** How far across the apron a point is: 0 at its inner edge, 1 at its outer edge, outside 0..1 off it. */
    static double apronShare(Isle k, Probe p) {
        double start = k.footNow * 0.85, end = k.footNow + APRON;
        return (p.r - start) / (end - start);
    }

    /** True in one of the gaps through a reef. */
    static boolean inPass(Isle k, double ang, double dist) {
        return inPass(k, ang, dist, 4.5);
    }

    /** True within {@code half} blocks of one of the gaps through a reef or a caldera's ring. */
    static boolean inPass(Isle k, double ang, double dist, double half) {
        for (double aim : k.passAim) {
            double d = Math.abs(Math.atan2(Math.sin(ang - aim), Math.cos(ang - aim)));
            if (d * dist < half) return true;
        }
        return false;
    }

    /** A reef ring round a lagoon over a drowned island, with sand islets on the rim and passes through it. */
    private static double atoll(Ctx c, Isle k, int gx, int gz, Probe p, int water) {
        double base = grown(c, k, p.r) - k.sunk + 1.2 * ValueNoise.noise(gx + k.noise, gz - k.noise, 7.0);
        double ring = k.ringR * (1.0 + 0.06 * VolcanoEdifice.polarNoise(p.ang, 0.0, k.ringR, k.noise + 331, 1.0, 40.0));
        double d = p.r - ring;
        double half = k.rimW * 0.5;
        double n = ValueNoise.noise(gx - 2 * k.noise, gz + 2 * k.noise, 9.0);
        if (d < -half) {
            double lagoon = Math.max(base, water - k.lagoonDepth + 1.5 * n);
            // Sand the waves wash over the rim slopes down into the lagoon.
            double back = smooth(Mth.clamp((d + half + BACK_REEF) / BACK_REEF, 0.0, 1.0));
            double y = Math.max(lagoon, Mth.lerp(back, lagoon, water - 1.5));
            // Patch reefs: mounds of coral rising from the lagoon floor.
            double head = (ValueNoise.noise(gx + 5 * k.noise, gz, 6.0) - 0.55) / 0.4;
            if (head > 0) {
                double top = lagoon + (water - 3 - lagoon) * Math.min(1.0, head);
                if (top > y) {
                    p.bits |= REEF;
                    return top;
                }
            }
            p.bits |= LAGOON;
            return y;
        }
        if (d <= half) {
            if (inPass(k, p.ang, p.dist)) {
                p.bits |= LAGOON;
                return Math.max(base, water - 4 + n);
            }
            double motu = VolcanoEdifice.polarNoise(p.ang, 0.0, k.ringR, k.noise + 557, 1.0, 22.0);
            double across = 1.0 - Math.abs(d) / half;
            if (motu > 0.2 && across > 0.35) {
                p.bits |= MOTU;
                return water + 1 + (motu > 0.5 && across > 0.6 ? 1 : 0);
            }
            p.bits |= RIM;
            return water - (n > 0.3 ? 0 : 1);
        }
        // The outer reef wall, steep down to where it meets the drowned flank.
        double wall = water - 1 - 0.9 * Math.pow(d - half, 1.25);
        if (wall > base) {
            p.bits |= REEF;
            return wall;
        }
        return base;
    }

    /** A drowned island whose top the waves planed flat before it sank. */
    private static double guyot(Ctx c, Isle k, int gx, int gz, Probe p, int water) {
        double planed = smoothMin(grown(c, k, p.r) - k.sunk, water, 4.0);
        if (planed > water - 2) p.bits |= TOP;
        return planed - k.topDepth + 0.6 * ValueNoise.noise(gx + k.noise, gz - k.noise, 6.0);
    }

    static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    static double smoothMin(double a, double b, double w) {
        double h = Mth.clamp(0.5 + 0.5 * (b - a) / w, 0.0, 1.0);
        return Mth.lerp(h, b, a) - w * h * (1.0 - h);
    }

    // === Writing ============================================================

    /** Writes one column of the island. Reads and writes nothing outside the column. */
    static void column(LevelAccessor level, Ctx c, int gx, int gz, RandomSource rng) {
        Isle k = c.isle;
        int dx = gx - c.x, dz = gz - c.z;
        if ((long) dx * dx + (long) dz * dz > (long) (k.reach + 1) * (k.reach + 1)) return;
        int bed = seabed(level, gx, gz);
        if (bed == Integer.MIN_VALUE) return;
        Probe p = new Probe();
        double shaped = shape(c, gx, gz, p);
        int water = k.seaY - 1;
        if (p.r > k.shoreR0 && bed < k.floorY) {
            // The flank under the sea runs down to the floor the plan was made on. Where the real floor lies
            // deeper, the flank follows it down instead of ending on a shelf with a wall under it.
            double u = Mth.clamp((p.r - k.shoreR0) / Math.max(1.0, k.footR - k.shoreR0), 0.0, 1.0);
            shaped += (bed - k.floorY) * smooth(u);
        }
        int target = (int) Math.round(shaped);
        // Pillow lava piles up in lumps on a young flank.
        if (k.setting == VolcanoSetting.ISLAND && target < water - 12 && pillow(k, gx, gz)) target++;
        if (target < bed && c.type == VolcanoType.CALDERA && (p.bits & LAGOON) != 0
                && !level.getBlockState(new BlockPos(gx, bed + 1, gz)).getFluidState().isEmpty()) {
            // The collapse took the floor down with it: a flooded caldera is dug below the sea floor round it.
            for (int y = bed; y > target; y--) {
                BlockPos pos = new BlockPos(gx, y, gz);
                BlockState s = level.getBlockState(pos);
                if (s.is(Blocks.BEDROCK) || com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(s)) break;
                level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            VolcanoSummit.setRock(level, new BlockPos(gx, target, gz), surface(c, k, rng, gx, target, gz, p.bits, 0.0, p));
            return;
        }
        double spread = apron(k, p), rubble = debris(k, gx, gz, p);
        double laid = Math.max(spread, rubble);
        boolean onBed = bed + (int) Math.round(laid) > target;
        if (onBed) target = bed + (int) Math.round(laid);
        if (target <= bed) return;

        int surfaceTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, gx, gz);
        boolean dry = bed >= k.seaY && level.getBlockState(new BlockPos(gx, bed + 1, gz)).getFluidState().isEmpty();
        double slope = onBed ? 0.0 : slope(c, gx, gz);
        int bits = onBed ? 0 : p.bits;
        // Towards its outer edge the apron keeps the sea floor's own top, so it thins into the floor instead of
        // ending on a line.
        BlockState floorTop = onBed && rubble < spread && rng.nextDouble() < apronShare(k, p) * 0.9
                ? level.getBlockState(new BlockPos(gx, bed, gz)) : null;
        for (int y = bed + 1; y <= target; y++) {
            BlockState b = y < target ? body(c, k, rng, gx, y, gz, bits)
                    : floorTop != null ? floorTop : surface(c, k, rng, gx, y, gz, bits, slope, p);
            VolcanoSummit.setRock(level, new BlockPos(gx, y, gz), b);
        }
        if ((bits & POND) != 0) {
            // The young cone's crater, on a basalt floor, where the summit step will seat the core: molten on a live
            // cone, crusted over on a sleeping one.
            VolcanoSummit.setRock(level, new BlockPos(gx, target - 1, gz), Blocks.BASALT.defaultBlockState());
            VolcanoSummit.setRock(level, new BlockPos(gx, target, gz), c.activity == VolcanoActivity.DORMANT
                    ? (rng.nextBoolean() ? Blocks.BLACKSTONE : Blocks.BASALT).defaultBlockState()
                    : Blocks.LAVA.defaultBlockState());
        }
        if ((bits & (REEF | RIM)) != 0 && target < water && rng.nextInt(4) == 0) {
            BlockPos above = new BlockPos(gx, target + 1, gz);
            if (level.getBlockState(above).is(Blocks.WATER)) level.setBlock(above, coralPlant(gx, gz, rng), 2);
        }
        clearAbove(level, gx, target + 1, gz, Math.max(surfaceTop, target + 1), k.seaY);
        if (dry) VolcanoEdifice.clearCover(level, gx, target, gz);
    }

    /** The largest height difference across a column's shaped neighbours, per block. */
    static double slope(Ctx c, int gx, int gz) {
        Probe q = new Probe();
        double east = shape(c, gx + 1, gz, q), west = shape(c, gx - 1, gz, q);
        double south = shape(c, gx, gz + 1, q), north = shape(c, gx, gz - 1, q);
        return Math.max(Math.abs(east - west), Math.abs(south - north)) * 0.5;
    }

    /** True on a pillow: lumps a few blocks apart, from a jittered grid, so neighbouring chunks agree. */
    static boolean pillow(Isle k, int gx, int gz) {
        int cx = Math.floorDiv(gx, 5), cz = Math.floorDiv(gz, 5);
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                long h = SeedHash.hash(k.noise, cx + ox, cz + oz, 0x9111L);
                double px = (cx + ox) * 5 + 1 + SeedHash.rand01(h) * 3;
                double pz = (cz + oz) * 5 + 1 + SeedHash.rand01(SeedHash.mix(h)) * 3;
                double ex = gx + 0.5 - px, ez = gz + 0.5 - pz;
                if (ex * ex + ez * ez < 2.3) return true;
            }
        }
        return false;
    }

    /** The first real ground under the sea or under an iceberg, or {@link Integer#MIN_VALUE}. */
    public static int seabed(LevelAccessor level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = top; y > level.getMinBuildHeight() && y > top - 200; y--) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir() || !s.getFluidState().isEmpty() || frozen(s) || loose(s)) continue;
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean frozen(BlockState s) {
        return s.is(Blocks.ICE) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE) || s.is(Blocks.SNOW_BLOCK);
    }

    private static boolean loose(BlockState s) {
        return TerrainProbe.isVegetation(s) || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS);
    }

    /** Over a column's new top: sea up to the sea level, nothing above it; ice and plants left there go. */
    static void clearAbove(LevelAccessor level, int x, int from, int z, int to, int seaY) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = from; y <= Math.min(to, from + 64); y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            boolean clear = frozen(s) || loose(s);
            if (y < seaY) {
                if (s.isAir() || clear) level.setBlock(m, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
                else if (s.getFluidState().isEmpty()) return;
            } else {
                if (clear) level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                else if (!s.isAir()) return;
            }
        }
    }

    // === Plants =============================================================

    /** In one column of an island's dry ground in a hundred, roughly, a tree; oftener in a warm sea. */
    private static final int TREE_ODDS_WARM = 26, TREE_ODDS = 40;

    /**
     * Trees and plants on the dry ground an island laid in this chunk. An ocean biome grows none, so the island
     * does: forest in a warm sea, oak and birch in a temperate one, spruce in a cold one, nothing on a frozen one,
     * where the biome's snow comes instead. Trunks stay three blocks inside the chunk, so no crown reaches a
     * neighbour that is built after it.
     */
    static void plant(net.minecraft.world.level.WorldGenLevel level,
                      net.minecraft.world.level.chunk.ChunkGenerator generator, Ctx c,
                      net.minecraft.world.level.ChunkPos cp, long seed) {
        Isle k = c.isle;
        if (k.setting == VolcanoSetting.GUYOT || k.seaTemp < -0.45) return;
        net.minecraft.core.Registry<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> features =
                level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE);
        RandomSource rng = RandomSource.create(0L);
        long reach2 = (long) k.edifice * k.edifice;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int gx = cp.getMinBlockX() + lx, gz = cp.getMinBlockZ() + lz;
                long dx = gx - c.x, dz = gz - c.z;
                if (dx * dx + dz * dz > reach2) continue;
                int g = TerrainProbe.groundY(level, gx, gz);
                if (g < k.seaY + 1) continue;
                BlockPos ground = new BlockPos(gx, g, gz);
                if (!level.getBlockState(ground).is(Blocks.GRASS_BLOCK) || !level.getBlockState(ground.above()).isAir()) continue;
                rng.setSeed(SeedHash.columnSeed(seed ^ 0x7EE5L, gx, gz));
                boolean inside = lx >= 3 && lx <= 12 && lz >= 3 && lz <= 12;
                int odds = k.seaTemp > REEF_TEMPERATURE ? TREE_ODDS_WARM : TREE_ODDS;
                if (inside && rng.nextInt(odds) == 0) {
                    features.getHolder(treeFor(k, rng)).ifPresent(tree ->
                            tree.value().place(level, generator, rng, ground.above()));
                    continue;
                }
                int roll = rng.nextInt(12);
                if (roll < 2) level.setBlock(ground.above(), Blocks.GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                else if (roll == 2 && k.seaTemp > -0.15) {
                    level.setBlock(ground.above(), Blocks.FERN.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** Which tree grows on an island, by how warm its sea is. */
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>>
            treeFor(Isle k, RandomSource rng) {
        int roll = rng.nextInt(10);
        if (k.seaTemp > 0.55) {
            return roll < 6 ? net.minecraft.data.worldgen.features.TreeFeatures.JUNGLE_TREE_NO_VINE
                    : net.minecraft.data.worldgen.features.TreeFeatures.JUNGLE_BUSH;
        }
        if (k.seaTemp > REEF_TEMPERATURE) {
            return roll < 5 ? net.minecraft.data.worldgen.features.TreeFeatures.JUNGLE_BUSH
                    : net.minecraft.data.worldgen.features.TreeFeatures.OAK;
        }
        if (k.seaTemp > -0.15) {
            return roll < 6 ? net.minecraft.data.worldgen.features.TreeFeatures.OAK
                    : net.minecraft.data.worldgen.features.TreeFeatures.BIRCH;
        }
        return net.minecraft.data.worldgen.features.TreeFeatures.SPRUCE;
    }

    // === Blocks =============================================================

    /** Rock inside the island, by depth: thin flows above the sea, glassy rubble round the shore, pillow lava below. */
    static BlockState body(Ctx c, Isle k, RandomSource rng, int gx, int y, int gz, int bits) {
        int water = k.seaY - 1;
        if ((bits & KAMENI) != 0) {
            // Dark young lava.
            return (rng.nextInt(3) == 0 ? Blocks.BASALT : Blocks.BLACKSTONE).defaultBlockState();
        }
        if (c.type == VolcanoType.CALDERA && y > water - 4) {
            // The ring's cliffs show the eruptions that built it in beds: lava, red scoria, grey tuff, and on top the
            // white pumice of the one that emptied it. The beds dip gently away from the vent, swell and thin, and
            // pinch out into the tuff round them.
            int cap = 1 + (int) Math.round(3.0 * (0.5 + 0.5 * ValueNoise.noise(gx + 7 * k.noise, gz, 23.0)));
            if (y > k.seaY + k.h0 - cap) {
                return (rng.nextInt(5) == 0 ? Blocks.TUFF : Blocks.CALCITE).defaultBlockState();
            }
            double out = Math.hypot(gx - c.x, gz - c.z) - k.ringR;
            double v = y + 0.12 * out + 2.5 * ValueNoise.noise(gx - k.noise, gz, 40.0)
                    + ValueNoise.noise(gx, gz + k.noise, 13.0);
            double thick = 3.0 + ValueNoise.noise(gx + 3 * k.noise, gz - 3 * k.noise, 60.0);
            int band = Math.floorMod((int) Math.floor(v / thick), 6);
            if (band != 0 && ValueNoise.noise(gx + 17 * band * k.noise, gz - 13 * band, 28.0) < -0.45) band = 0;
            int roll = rng.nextInt(10);
            Block b = switch (band) {
                case 1 -> roll < 7 ? Blocks.BLACKSTONE : Blocks.BASALT;
                case 3 -> roll < 5 ? Blocks.RED_TERRACOTTA : roll < 8 ? Blocks.BROWN_TERRACOTTA : Blocks.TUFF;
                case 4 -> roll < 6 ? Blocks.SMOOTH_BASALT : roll < 8 ? Blocks.BASALT : Blocks.TUFF;
                default -> roll < 9 ? Blocks.TUFF : Blocks.ANDESITE;
            };
            return b.defaultBlockState();
        }
        if ((k.setting == VolcanoSetting.ATOLL && y >= water - 14) || ((bits & (REEF | RIM | MOTU)) != 0 && y >= water - 8)) {
            // Reef limestone.
            return (rng.nextInt(4) == 0 ? Blocks.SAND : Blocks.CALCITE).defaultBlockState();
        }
        if ((bits & (CONE | RING)) != 0 && y > water - 2) {
            return (rng.nextInt(4) == 0 ? Blocks.GRAVEL : Blocks.TUFF).defaultBlockState();
        }
        boolean cone = c.type == VolcanoType.STRATOVOLCANO;
        int roll = rng.nextInt(20);
        if (y > water + 1 && (bits & DELTA) == 0) {
            return cone ? VolcanoEdifice.stratoRock(rng, c, gx, y, gz)
                    : (roll < 7 ? Blocks.SMOOTH_BASALT : Blocks.BASALT).defaultBlockState();
        }
        if (y >= water - 12) {
            if (cone) return (roll < 9 ? Blocks.TUFF : roll < 14 ? Blocks.ANDESITE : roll < 18 ? Blocks.GRAVEL
                    : ModBlocks.VOLCANIC_BLACK_SAND.get()).defaultBlockState();
            return roll < 8 ? Blocks.TUFF.defaultBlockState()
                    : roll < 12 ? ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState()
                    : (roll < 15 ? Blocks.GRAVEL : Blocks.BASALT).defaultBlockState();
        }
        if (cone) return (roll < 8 ? Blocks.TUFF : roll < 15 ? Blocks.ANDESITE : roll < 18 ? Blocks.GRAVEL
                : Blocks.BASALT).defaultBlockState();
        return (roll < 10 ? Blocks.BASALT : roll < 16 ? Blocks.SMOOTH_BASALT : roll < 18 ? Blocks.BLACKSTONE
                : Blocks.TUFF).defaultBlockState();
    }

    /** A column's top block. */
    static BlockState surface(Ctx c, Isle k, RandomSource rng, int gx, int y, int gz, int bits, double slope, Probe p) {
        int water = k.seaY - 1;
        boolean young = k.setting == VolcanoSetting.ISLAND;
        if ((bits & KAMENI) != 0) {
            int roll = rng.nextInt(10);
            return roll < 6 ? Blocks.BLACKSTONE.defaultBlockState()
                    : roll < 9 ? Blocks.BASALT.defaultBlockState() : VolcanoEdifice.flowRock(c);
        }
        if (c.type == VolcanoType.CALDERA && (bits & LAGOON) != 0) {
            // The drowned caldera floor: ash, pumice gravel and black sand.
            int roll = rng.nextInt(10);
            return roll < 4 ? Blocks.TUFF.defaultBlockState()
                    : roll < 7 ? Blocks.GRAVEL.defaultBlockState() : ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState();
        }
        if ((bits & DELTA) != 0) return rng.nextInt(5) < 3 ? VolcanoEdifice.flowRock(c) : Blocks.BASALT.defaultBlockState();
        if ((bits & CONE) != 0) {
            return rng.nextInt(3) == 0 ? ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState() : Blocks.TUFF.defaultBlockState();
        }
        if ((bits & RING) != 0) {
            return (y > water + 1 && slope < 0.9 && rng.nextInt(3) > 0 ? Blocks.GRASS_BLOCK : Blocks.TUFF).defaultBlockState();
        }
        if ((bits & MOTU) != 0) return (y >= water + 2 ? Blocks.GRASS_BLOCK : Blocks.SAND).defaultBlockState();
        if ((bits & (REEF | RIM)) != 0) {
            if (y > water) return Blocks.SAND.defaultBlockState();
            if (y >= water - 1) {
                // Awash: rubble, sand and coral killed by the sun at low water. The living reef is below.
                int roll = rng.nextInt(10);
                return roll < 4 ? deadCoral(gx, gz) : (roll < 8 ? Blocks.SAND : Blocks.GRAVEL).defaultBlockState();
            }
            return y < water - 14 ? deadCoral(gx, gz) : coral(gx, gz);
        }
        if ((bits & LAGOON) != 0) return (rng.nextInt(8) == 0 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState();
        if ((bits & TOP) != 0) {
            // The planed top keeps the shallow-water limestone and sand that capped it before it drowned.
            int roll = rng.nextInt(10);
            if (roll < 4) return Blocks.SAND.defaultBlockState();
            if (roll < 6) return Blocks.CALCITE.defaultBlockState();
            return roll < 9 ? deadCoral(gx, gz) : Blocks.GRAVEL.defaultBlockState();
        }
        if (y >= water + 2) {
            BlockState dry = land(c, k, rng, gx, y, gz, slope, p);
            // A young island's black sand runs well up behind the strand and grows over along a ragged line, as at
            // Reynisfjara: bare at the waves, dune grass and scrub behind.
            return young && c.type != VolcanoType.CALDERA && slope < 1.6
                    ? VolcanoEdifice.shoreSkin(k.seaY, rng, gx, y, gz, dry, 3.5, 2.0, 4.0) : dry;
        }
        // A caldera's island is mostly pumice and ash, pale down to the water and under it.
        boolean pumice = c.type == VolcanoType.CALDERA;
        if (y >= water - 1) {
            // The strand, or boulders where the shore is steep.
            if (slope >= 0.9) {
                int roll = rng.nextInt(10);
                if (pumice) return (roll < 5 ? Blocks.TUFF : roll < 8 ? Blocks.ANDESITE : Blocks.GRAVEL).defaultBlockState();
                return (roll < 4 ? Blocks.BASALT : roll < 6 ? Blocks.BLACKSTONE : roll < 8 ? Blocks.GRAVEL
                        : Blocks.SMOOTH_BASALT).defaultBlockState();
            }
            if (pumice) {
                int roll = rng.nextInt(10);
                return (roll < 4 ? Blocks.SAND : roll < 7 ? Blocks.GRAVEL : Blocks.TUFF).defaultBlockState();
            }
            if (young) return ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState();
            // Old reefs grind down to white sand; a cold sea leaves shingle and black sand.
            if (k.warm()) return Blocks.SAND.defaultBlockState();
            return rng.nextBoolean() ? Blocks.GRAVEL.defaultBlockState() : ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState();
        }
        if (y >= water - 12) {
            int roll = rng.nextInt(10);
            if (pumice) return (roll < 4 ? Blocks.GRAVEL : roll < 7 ? Blocks.SAND : Blocks.TUFF).defaultBlockState();
            if (young) return roll < 5 ? ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState()
                    : (roll < 7 ? Blocks.TUFF : Blocks.BASALT).defaultBlockState();
            if (k.warm()) return (roll < 7 ? Blocks.SAND : Blocks.GRAVEL).defaultBlockState();
            return (roll < 5 ? Blocks.GRAVEL : roll < 8 ? Blocks.SAND : Blocks.CLAY).defaultBlockState();
        }
        // Deep: fresh pillows on a live island, a drape of sediment thickening with age on an old one. A guyot's
        // gentler flanks are all under sediment; the basalt shows only where it is steep.
        if (!young && (rng.nextDouble() < 0.35 + 0.5 * k.age || (k.setting == VolcanoSetting.GUYOT && slope < 1.0))) {
            int roll = rng.nextInt(10);
            return (roll < 5 ? Blocks.SAND : roll < 8 ? Blocks.CLAY : Blocks.GRAVEL).defaultBlockState();
        }
        int roll = rng.nextInt(20);
        if (pumice) return (roll < 10 ? Blocks.TUFF : roll < 16 ? Blocks.GRAVEL : Blocks.BASALT).defaultBlockState();
        if (c.type == VolcanoType.STRATOVOLCANO) {
            return (roll < 9 ? Blocks.TUFF : roll < 15 ? Blocks.ANDESITE : Blocks.GRAVEL).defaultBlockState();
        }
        return (roll < 11 ? Blocks.BASALT : roll < 18 ? Blocks.SMOOTH_BASALT : Blocks.BLACKSTONE).defaultBlockState();
    }

    /** Dry ground: a live shield's skin and flows, a stratocone's, or an old island weathered to soil to its top. */
    static BlockState land(Ctx c, Isle k, RandomSource rng, int gx, int y, int gz, double slope, Probe p) {
        BlockState rock = body(c, k, rng, gx, y, gz, 0);
        // Cliffs and valley walls show the lava beds they were cut through; an old island is soil to a steeper pitch.
        if (slope >= (k.setting == VolcanoSetting.ISLAND ? 1.6 : 2.0)) return rock;
        if (c.type == VolcanoType.CALDERA) {
            // The ring's outer slopes: grass on weathered ash, and here and there pale pumice where the cover is thin,
            // in small ragged patches with ash and scree round them.
            if (slope >= 1.0 && rng.nextInt(3) == 0) return rock;
            double n = ValueNoise.noise(gx + 11 * k.noise, gz, 24.0) + 0.3 * ValueNoise.noise(gx - 5 * k.noise, gz, 5.0);
            if (n < -0.65) {
                int roll = rng.nextInt(10);
                return (roll < 5 ? Blocks.CALCITE : roll < 8 ? Blocks.TUFF : Blocks.WHITE_TERRACOTTA).defaultBlockState();
            }
            if (n < -0.38) return (rng.nextInt(3) == 0 ? Blocks.TUFF : Blocks.COARSE_DIRT).defaultBlockState();
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
        double h = (y - (k.seaY - 1)) / (double) Math.max(1, k.h0);
        if (k.setting == VolcanoSetting.ISLAND) {
            if (c.type == VolcanoType.STRATOVOLCANO) return VolcanoEdifice.stratoSkin(rng, c, gx, gz, h, rock);
            if (VolcanoEdifice.flowAt(c, p.ang, p.r)) return VolcanoEdifice.flowRock(c);
            return VolcanoEdifice.shieldSkin(rng, c, gx, gz, h, rock);
        }
        // An old island, cone or shield, has weathered to soil to its top.
        if (slope >= 1.0 && rng.nextInt(3) == 0) return rock;
        double n = ValueNoise.noise(gx + 7 * k.noise, gz, 30.0);
        if (n < -0.55) return Blocks.COARSE_DIRT.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    /** A living reef block, one kind in patches a few blocks across. */
    static BlockState coral(int gx, int gz) {
        Block b = switch (coralKind(gx, gz)) {
            case 0 -> Blocks.TUBE_CORAL_BLOCK;
            case 1 -> Blocks.BRAIN_CORAL_BLOCK;
            case 2 -> Blocks.BUBBLE_CORAL_BLOCK;
            case 3 -> Blocks.FIRE_CORAL_BLOCK;
            default -> Blocks.HORN_CORAL_BLOCK;
        };
        return b.defaultBlockState();
    }

    static BlockState deadCoral(int gx, int gz) {
        Block b = switch (coralKind(gx, gz)) {
            case 0 -> Blocks.DEAD_TUBE_CORAL_BLOCK;
            case 1 -> Blocks.DEAD_BRAIN_CORAL_BLOCK;
            case 2 -> Blocks.DEAD_BUBBLE_CORAL_BLOCK;
            case 3 -> Blocks.DEAD_FIRE_CORAL_BLOCK;
            default -> Blocks.DEAD_HORN_CORAL_BLOCK;
        };
        return b.defaultBlockState();
    }

    static BlockState coralPlant(int gx, int gz, RandomSource rng) {
        boolean fan = rng.nextBoolean();
        Block b = switch (coralKind(gx, gz)) {
            case 0 -> fan ? Blocks.TUBE_CORAL_FAN : Blocks.TUBE_CORAL;
            case 1 -> fan ? Blocks.BRAIN_CORAL_FAN : Blocks.BRAIN_CORAL;
            case 2 -> fan ? Blocks.BUBBLE_CORAL_FAN : Blocks.BUBBLE_CORAL;
            case 3 -> fan ? Blocks.FIRE_CORAL_FAN : Blocks.FIRE_CORAL;
            default -> fan ? Blocks.HORN_CORAL_FAN : Blocks.HORN_CORAL;
        };
        return b.defaultBlockState();
    }

    private static int coralKind(int gx, int gz) {
        return (int) Math.floorMod(SeedHash.hash(0x0C0A1L, gx >> 2, gz >> 2, 0x5EAL), 5L);
    }
}
