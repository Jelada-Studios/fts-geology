package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.blockentity.SpringSourceBlockEntity;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.List;
import static com.jeladastudios.ftsgeology.worldgen.RetrogenHandler.*;
import static com.jeladastudios.ftsgeology.worldgen.SurfaceFeatures.*;

/** Hot spring systems: siting, terraces, plumbing, and the thermal bands and halo around the pools. */
public final class HotSpringSites {

    private HotSpringSites() {}

    /**
     * Builds an irregular hot-spring pool at the surface of the given column, ringed with calcite.
     * Public so volcanoes can dot their slopes with thermal pools. Returns true if one was placed.
     */
    public static boolean placeHotSpringAt(ServerLevel level, int x, int z) {
        return placeHotSpringAt(level, x, z, HotSpringShape.MAX_STAGE);
    }

    /** @param stage how old the springs come out; the generator always asks for a finished one. */
    public static boolean placeHotSpringAt(ServerLevel level, int x, int z, int stage) {
        // --- Site analysis ---------------------------------------------------
        // Everything that went wrong before came from skipping this step: pools appeared on
        // shorelines, half in the sea, with their magma bed hanging out of a cliff and the water
        // draining away. So the ground is inspected first and unsuitable spots are simply refused.
        int centre = TerrainProbe.groundY(level, x, z);
        if (centre == Integer.MIN_VALUE) return false;
        if (centre <= level.getSeaLevel() + 2) return false;      // no beaches, no sea floor
        if (centre <= level.getMinBuildHeight() + 8) return false;

        int scan = 8;
        int lo = centre, hi = centre;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                int g = TerrainProbe.groundY(level, x + dx, z + dz);
                if (g == Integer.MIN_VALUE) return false;          // a cliff edge or open air
                if (TerrainProbe.hasFluidAbove(level, x + dx, z + dz)) return false;  // lake or sea
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
            }
        }
        int relief = hi - lo;
        // A spring system needs level ground, but the old six-block limit rejected almost every
        // wooded hillside - which is why springs away from a volcano were so scarce. Broken ground
        // now gets a chain of smaller terraces instead of a flat refusal.
        if (relief > 12) return false;

        // --- Layout: one broad basin on the flat, terraces on a slope ---------
        // Flat ground gives a single wide pool; a gentle slope gives the stepped travertine
        // terraces you see at Pamukkale, each pool a little lower than the one above it.
        //
        // Two blocks of relief is noise on any forest floor, not a slope. Chaining on that put a
        // terrace system on ground that had nowhere to step down to, which is what drove the
        // descent below into the ground.
        // A geyser basin gives one broad pool, whatever the ground is doing.
        //
        // Two thresholds stacked up to make stage 4 springs essentially nonexistent. "Terraced"
        // asked for under four blocks of relief across seventeen, which Minecraft terrain hardly
        // ever has outside flat plains; and the relaxed version of it only applied within about
        // 40 to 90 blocks of a basin centre, in the 30% of cells that hold a basin at all. So the
        // richest ground in the world produced the same modest stepped pools as any hillside, and
        // testing reported seeing no mature springs anywhere - twice.
        //
        // Inside a basin there is now no terracing at all. Ground rougher than 12 is refused higher
        // up regardless, and on a slope the pool trims itself against the rising land, so a broad
        // pool on uneven ground is self-limiting rather than dangerous. With no chain there is also
        // no neighbour to overlap.
        double basinHere = com.jeladastudios.ftsgeology.tectonics.HotspotMap
                .basinStrength(level, x, z);
        boolean inBasin = basinHere > 0.2;
        boolean terraced = !inBasin && relief >= 6;
        int terraces = terraced ? 2 + level.random.nextInt(3) : 1;

        // Downhill direction, as an ANGLE rather than a compass point.
        //
        // This used to be two calls to Integer.compare, so the step was one of -1, 0, +1 on each
        // axis: eight possible directions, and the chain then walked that heading in a dead straight
        // line with a near-constant stride. Seen from above, a field of springs came out in rows and
        // columns, which is the "sıra sıra, sütun sütun" report - the regularity was not in where
        // the systems were placed, it was inside each one.
        //
        // The gradient gives a real bearing, and the walk below re-reads it at every step, so a
        // chain now follows the actual fall of the hill and wanders as the hill does.
        int gxPlus = TerrainProbe.groundY(level, x + scan, z);
        int gxMinus = TerrainProbe.groundY(level, x - scan, z);
        int gzPlus = TerrainProbe.groundY(level, x, z + scan);
        int gzMinus = TerrainProbe.groundY(level, x, z - scan);
        double bearing = Math.atan2(gzMinus - gzPlus, gxMinus - gxPlus);
        if (gxPlus == gxMinus && gzPlus == gzMinus) {
            bearing = level.random.nextDouble() * Math.PI * 2;   // dead flat: any way will do
        }

        int placed = 0;
        int px = x, pz = z;
        int ground = centre, waterY = centre - 1;       // recessed: water sits BELOW the rim
        BlockPos firstBed = null;

        // How old a pool in this system is allowed to get, and therefore how wide.
        //
        // A chain caps at stage 2 and a lone pool on the flat does not. This is the whole fix for
        // the terraces that deleted each other's water: the spacing below is derived from the SAME
        // number, so a chain cannot grow into itself no matter what the ground does. The previous
        // code spaced pools from a local `radius` of 2 to 7 that was handed to a setter nothing
        // read, while every pool grew to stage 4 at radius 10 - so the gap was 6 to 20 blocks
        // between pools 21 blocks across, and every consecutive pair overlapped.
        int chainStage = terraced ? Math.min(3, stage) : stage;
        int matureR = HotSpringShape.radiusFor(chainStage);

        for (int i = 0; i < terraces; i++) {
            // Every pool is built by a mineral water line, at generation exactly as after a quake -
            // the line is seated, its vent opened, and then run straight to maturity. There is no
            // second pool builder any more, so the two paths cannot drift apart, and what a new
            // world contains is what a recovered spring grows back into.
            if (openSpring(level, px, pz, chainStage, stage)) placed++;
            // Step downhill for the next pool in the chain, clear of the pool this one will grow
            // into. The bearing is nudged and the stride varied at every step so the chain reads as
            // a stream of pools following a slope rather than a row of them on a ruler.
            //
            // The wobbled edge reaches 1.34 times the nominal radius, so the gap allows for it:
            // two pools that just cleared on paper still met in the bulges.
            int stride = (int) Math.ceil(matureR * 1.34) * 2 + 2 + level.random.nextInt(4);
            bearing += (level.random.nextDouble() - 0.5) * 0.9;
            px += (int) Math.round(Math.cos(bearing) * stride);
            pz += (int) Math.round(Math.sin(bearing) * stride);
            int nextGround = TerrainProbe.groundY(level, px, pz);
            if (nextGround == Integer.MIN_VALUE) break;
            // The chain follows the hill; it never digs one for itself.
            //
            // It used to drop the water a block per terrace whatever the ground did
            // (`min(waterY - 1, nextGround - 1)`), while carveTerrace only ever opens TWO blocks
            // above the water. On level ground the third pool onward was therefore roofed over by
            // the original surface: the water was cut, it just had a lid on it, and the colour
            // bands were then painted across that lid. That is the "terraced springs are dry"
            // report - and, further down the chain, the pit in the ground.
            //
            // Now every pool sits exactly one block under ITS OWN ground, so two blocks of
            // clearance is always enough, and the chain simply stops where the hill does.
            if (nextGround >= ground) break;
            ground = nextGround;
            waterY = nextGround - 1;
            if (waterY <= lo - 6) break;
        }
        if (placed == 0) return false;
        // Says which stage this site actually got and why, so "there are no mature springs" can be
        // counted from a log rather than argued about. It has come up twice now with both of us
        // guessing at the distribution.
        GeysersMod.LOGGER.info(
                "Hot spring at {},{}: {} pool(s), stage {}, relief {}, basin {}",
                x, z, placed, chainStage, relief, String.format("%.2f", basinHere));
        return true;
    }

    /**
     * How far under the pool the source sits.
     *
     * <p>Must clear the quake's reach, which is {@code QuakePlanner.MAX_CAPTURE_DEPTH} = 24. It sits
     * at twice that rather than just past it, so that the deep end reads as deep when a player digs
     * for it, and so a quake that drops the surface still cannot get near it.</p>
     */
    static final int SOURCE_DEPTH = 50;

    /** The shallowest the source may sit under the surface and still be out of a quake's reach. */
    static final int MIN_SOURCE_DEPTH = 28;

    /**
     * Puts in a mineral water line at this spot and runs it up to a finished spring.
     *
     * <p>This is the only way a hot spring is ever built. At generation it is run to maturity at
     * once, so a new world has old springs in it rather than a field of day-old puddles; after an
     * earthquake the same line grows the same spring back over a few in-game days. One builder, so
     * a repaired spring and a generated one cannot look different.</p>
     */
    /**
     * Puts one spring here at exactly the stage asked for, with no terrace chain around it.
     *
     * <h2>Why the command does not go through {@link #placeHotSpringAt}</h2>
     * That builds a whole system, and a system caps its pools at stage 3 wherever the ground has
     * four blocks of relief across seventeen - which is almost anywhere. So
     * {@code /geology place hotspring 3} and {@code ... 4} produced the same spring, and the command
     * stopped being able to show the difference between two stages. It is meant to be the tool that
     * tests the shape function on its own, so it builds exactly one spring at exactly one stage.
     */
    public static boolean placeSingleSpringAt(ServerLevel level, int x, int z, int stage) {
        return openSpring(level, x, z, stage, stage);
    }

    static boolean openSpring(ServerLevel level, int x, int z, int maxStage, int stage) {
        int ground = TerrainProbe.groundY(level, x, z);
        if (ground == Integer.MIN_VALUE) return false;
        if (ground <= level.getSeaLevel() + 2) return false;

        BlockPos source = place(level, new BlockPos(x, ground, z), ground, maxStage);
        if (source == null) return false;
        if (!(level.getBlockEntity(source) instanceof SpringSourceBlockEntity be)) return false;

        BlockPos vent = new BlockPos(x, ground, z);
        be.setVent(vent);
        boolean built = be.growTo(level, stage);
        if (built) boreConduit(level, source, ground);
        return built;
    }

    /**
     * Cuts the channel from the reservoir up to the pool.
     *
     * <h2>Why it has to be cut here</h2>
     * The mod says a hot spring is water rising from a chamber below, and until now that was only
     * ever said. {@code openSpring} built the reservoir 28 blocks down and then wrote the vent
     * straight to the surface, so nothing touched the rock in between: dig under a spring and there
     * was no channel, because there was no channel. The conduit is bored gradually by
     * {@link com.jeladastudios.ftsgeology.eruption.VentPathfinder} for a spring that has to work its
     * way up after being blocked, but a spring that arrives with the world has already done that
     * work, so it is cut in one go.
     *
     * <h2>Why the top of it is choked</h2>
     * A real vent narrows towards its mouth, because the mineral coming out of solution is deposited
     * fastest where the water first meets the air. Leaving it open would also drop anything that dug
     * into it straight down onto the magma bed, which is a nastier surprise than the feature is
     * worth.
     */
    static void boreConduit(ServerLevel level, BlockPos source, int groundY) {
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState choke = Blocks.CALCITE.defaultBlockState();
        int x = source.getX(), z = source.getZ();

        for (int y = source.getY() + 4; y < groundY - 1; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) continue;
            // The last few blocks under the pool floor are the throat, sealed with the spring's own
            // deposit. Everything below that is the water column itself.
            level.setBlock(p, y >= groundY - 4 ? choke : water, 2);
            // Skin the wall so the column does not open into a cave it happens to pass.
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos w = p.relative(d);
                BlockState ws = level.getBlockState(w);
                if (ws.isAir() || !ws.getFluidState().isEmpty()) {
                    if (!EruptionHandler.isPlayerPlaced(ws)) level.setBlock(w, choke, 2);
                }
            }
        }
    }

    /**
     * Seats a mineral water line where there is no spring yet, so it can open one for itself.
     *
     * <p>Used when an earthquake has changed the plumbing somewhere that now qualifies. There is no
     * vent to inherit, so the line bores its way up and then grows a spring through its stages -
     * which is why a quake-opened spring announces itself as a small pool that gets bigger over the
     * following days rather than appearing finished.</p>
     */
    public static BlockPos seedSourceAt(ServerLevel level, int x, int z, int groundY) {
        return place(level, new BlockPos(x, groundY, z), groundY,
                5 + level.random.nextInt(3));
    }

    /**
     * Puts the deep end of a spring in, well below anything that can disturb it.
     *
     * @param at     the surface point the line belongs to
     * @param groundY surface height there, which fixes how deep the line is seated
     */
    static BlockPos place(ServerLevel level, BlockPos at, int groundY, int maxStage) {
        // Pulled up rather than refused where the world floor is close: a shallow site still gets a
        // spring, just one whose deep end is nearer. It is only abandoned if it cannot be seated
        // deeper than a quake can dig.
        int y = Math.max(groundY - SOURCE_DEPTH, level.getMinBuildHeight() + 5);
        if (groundY - y < MIN_SOURCE_DEPTH) return null;           // too shallow a world here
        BlockPos src = new BlockPos(at.getX(), y, at.getZ());
        BlockState existing = level.getBlockState(src);
        if (existing.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(existing)) return null;

        buildReservoir(level, src, ModBlocks.SPRING_SOURCE.get().defaultBlockState(), 2, 3);
        if (level.getBlockEntity(src) instanceof SpringSourceBlockEntity be) {
            be.setMaxStage(maxStage);
        }
        return src;
    }

    /**
     * The reservoir under a geothermal feature: rock floor, magma heat bed, a walled body of water,
     * and a natural rock cap over it.
     *
     * <h2>Why a spring has one at all</h2>
     * A hot spring and a geyser are the same machine. Both are water sitting on hot rock with a way
     * up; the only difference is that a geyser's conduit is constricted enough for pressure to build
     * before it lets go, and a spring's is not - so a spring simply seeps, steadily, forever. Giving
     * a spring only a marker block was the thing that made every version of it feel invented: the
     * pool had nothing feeding it, so it had to be conjured at the surface instead of arriving from
     * below.
     *
     * <p>There is deliberately no pre-carved shaft. The cap is natural rock, and the conduit is bored
     * upward a few blocks at a time by {@link com.jeladastudios.ftsgeology.eruption.VentPathfinder} -
     * which is also what lets a blocked spring go looking for a different way out.</p>
     */
    static void buildReservoir(ServerLevel level, BlockPos core, BlockState coreBlock,
                               int rad, int chamberH) {
        int waterDepth = Math.max(1, chamberH - 1);

        // Containment floor, then a SOLID magma bed. Fluid lava would mix with the chamber water
        // into cobblestone, or drain away into a cave, and take the heat with it.
        fillLayer(level, core.below(2), rad + 1, Blocks.DEEPSLATE);
        fillLayer(level, core.below(1), rad, Blocks.MAGMA_BLOCK);
        MagmaSealing.sealSlab(level, core.below(1), rad);

        // Core level: a rock ring keeping the heat off the water, with the core in the middle.
        fillLayer(level, core, rad, Blocks.DEEPSLATE);
        level.setBlock(core, coreBlock, 2);

        // The water itself, walled so it cannot leak into a cave alongside.
        for (int dy = 1; dy <= chamberH; dy++) {
            fillLayer(level, core.above(dy), rad, dy <= waterDepth ? Blocks.WATER : Blocks.AIR);
            ringWall(level, core.above(dy), rad + 1);
        }
    }

    /** Lays the microbial colour bands around a finished pool. Called by the spring line. */
    public static void paintRings(ServerLevel level, List<BlockPos> pool, int cx, int cz, int waterY, int stage) {
        paintThermalRings(level, pool, cx, cz, waterY, stage);
    }

    /**
     * Strips the canopy off a terrace before it is cut.
     *
     * <p>{@link TerrainProbe#clearVegetation} deliberately never touches logs or leaves - a cabin is
     * made of logs - and it stops dead at the first block that is not ground cover. It was the only
     * clearing a spring ever did, so a wooded site kept its trees: {@code groundY} walks past a
     * trunk, so the basin was cut <em>underneath</em> one, and the crowns roofed the whole colour
     * field over. Both are visible in the test shots.</p>
     *
     * <p>The edge frays the same way a volcano's does: the basin is taken outright, and further out
     * more and more trees are left alone. A spared tree is spared <b>whole</b> - stripping the
     * leaves and leaving the trunk was tried and reads as a bug rather than as dead timber - and the
     * decision comes from a smooth field rather than a per-column roll, because a tree covers a
     * dozen columns and rolling per column would leave half a canopy standing.</p>
     */
    public static void clearCanopy(ServerLevel level, int cx, int cz, int radius) {
        double solid = radius * 0.5;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (dist > radius) continue;
                int gx = cx + dx, gz = cz + dz;
                int g = TerrainProbe.groundY(level, gx, gz);
                if (g == Integer.MIN_VALUE) continue;

                // The further out, the likelier a whole tree survives.
                double out = Mth.clamp((dist - solid) / Math.max(1.0, radius - solid), 0.0, 1.0);
                if (dist > solid && spareField(gx, gz) < out) continue;

                // Stop at the top of whatever actually stands here rather than always walking a
                // fixed 24 blocks of air: on open ground that is one lookup instead of two dozen,
                // and a spring field is mostly open ground.
                int top = Math.min(g + 24, level.getHeight(Heightmap.Types.WORLD_SURFACE, gx, gz));
                for (int y = g + 1; y <= top; y++) {
                    BlockPos p = new BlockPos(gx, y, gz);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    boolean tree = s.is(net.minecraft.tags.BlockTags.LOGS)
                            || s.is(net.minecraft.tags.BlockTags.LEAVES);
                    if (!tree && !TerrainProbe.isVegetation(s)) break;   // something real: stop
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * A smooth 0..1 field used to decide whether a tree is spared, coherent over roughly twenty
     * blocks so a whole tree lands on one side of the threshold.
     */
    static double spareField(int x, int z) {
        double n = Math.sin(x * 0.17) * Math.cos(z * 0.21)
                + 0.5 * Math.sin((x + z) * 0.09)
                + 0.35 * Math.sin((x - z) * 0.29);
        return Mth.clamp((n + 1.85) / 3.7, 0.0, 1.0);
    }

    /**
     * Rings a hot spring with the coloured bands that make one recognisable from the air.
     *
     * <h2>The colours are alive</h2>
     * Nothing grows in the boiling centre, so the water there is clear blue. Working outward the
     * runoff cools through one temperature range after another, and each range belongs to a
     * different community of heat-loving microorganisms with its own pigment: orange nearest the
     * heat, then yellow, brown, and finally ordinary green algae at the cool edge. The rings are a
     * thermometer you can see - which is exactly why Grand Prismatic looks the way it does, and why
     * these are laid down in order rather than scattered.
     *
     * <p>Band widths are rolled per spring and the edges wobble with the angle, so no two rings look
     * alike. Ground more than a couple of blocks off the water's level is skipped, so the bands stay
     * on the apron instead of climbing a bank.</p>
     */
    static void paintThermalRings(ServerLevel level, List<BlockPos> pool,
                                          int cx, int cz, int waterY, int stage) {
        if (pool.isEmpty()) return;

        // Band order runs OUTWARD from the water, and it is the order Grand Prismatic actually
        // shows: a white sinter shelf, a narrow green fringe at the waterline, then yellow, then
        // orange, and rust-brown at the dry edge.
        //
        // It used to end on green, which put the brightest colour in the mod hard against the
        // grass and made the whole spring read as painted on. In the ground the outermost ring is
        // the brown one, and it blends into bare earth on its own - the transition needs no help
        // once the order is right. The green fringe is kept narrow because in a real spring it is
        // not really a mat at all: it is blue water seen shallow over the yellow one.
        // How many of those bands exist depends on how old the spring is.
        //
        // A mat is a living thing. It needs a pool that has been warm, wet and the same shape for
        // long enough to be colonised, and the outer bands need the widest, coolest, most settled
        // fringe of all - so on a spring that opened a few days ago there is nothing but its own
        // bare deposit. The colours arriving one at a time is what makes the stages read as ages
        // rather than as the same spring at different sizes.
        int[] band = {
                1 + level.random.nextInt(2),   // the sinter shelf
                stage >= 2 ? 1 + level.random.nextInt(2) : 0,   // green, the shallow fringe
                stage >= 3 ? 2 + level.random.nextInt(3) : 0,   // yellow
                stage >= 4 ? 2 + level.random.nextInt(3) : 0,   // orange
                stage >= 4 ? 2 + level.random.nextInt(4) : 0,   // brown, coolest and driest
        };
        int bandReach = 0;
        for (int b : band) bandReach += b;
        // Beyond the last mat, the ground a spring poisons.
        //
        // The colours used to stop dead and ordinary soil began at the next block, which read as
        // the spring having been dropped onto the landscape. The bare ring is real - silica,
        // sulfate and arsenic in the runoff kill the soil and the heat finishes the roots - but it
        // has to LOOK killed rather than look unfinished. So it is a pale crusted skin that thins
        // outward into whatever was there, and the trees standing in it are dead.
        int halo = stage >= 4 ? 5 + level.random.nextInt(6) : 0;
        int reach = bandReach + halo;
        double phase = level.random.nextDouble() * Math.PI * 2;

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : pool) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }

        for (int x = minX - reach; x <= maxX + reach; x++) {
            for (int z = minZ - reach; z <= maxZ + reach; z++) {
                double nearest = Double.MAX_VALUE;
                for (BlockPos p : pool) {
                    double dx = x - p.getX(), dz = z - p.getZ();
                    nearest = Math.min(nearest, dx * dx + dz * dz);
                }
                double d = Math.sqrt(nearest);
                if (d < 0.9) continue;                       // that is the pool itself

                // Irregular edges, so the rings are not a bullseye.
                double ang = Math.atan2(z - cz, x - cx);
                double wobble = 1.0 + 0.28 * Math.sin(3 * ang + phase)
                        + 0.15 * Math.sin(5 * ang - phase);
                double scaled = d / Math.max(0.4, wobble);
                Block b = bandFor(scaled, band);
                // Past the last mat: the sterile halo, thinning out into the countryside.
                boolean inHalo = false;
                if (b == null) {
                    double out = (scaled - bandReach) / halo;   // 0 at the brown edge, 1 outside
                    if (out < 0.0 || out > 1.0) continue;
                    // Fades rather than ends: nearly every cell right against the mats, hardly any
                    // at the far edge, so the crust breaks up instead of drawing another ring.
                    if (level.random.nextDouble() > 1.0 - out) continue;
                    b = haloBlock(level);
                    inHalo = true;
                }

                int g = TerrainProbe.groundY(level, x, z);
                if (g == Integer.MIN_VALUE) continue;
                // The mats keep to the flat apron. The halo may climb a little further, because
                // ground poisoned by the runoff does not stop at a contour line.
                if (Math.abs(g - waterY) > (inHalo ? 4 : 2)) continue;
                // Never repaint the floor of standing water. groundY walks past a fluid, so the
                // cell it returns inside a neighbouring pool is that pool's calcite bed - painting
                // a mat there leaves the water sitting on a microbial mat instead of sinter.
                if (!level.getBlockState(new BlockPos(x, g + 1, z)).getFluidState().isEmpty()) continue;
                BlockPos p = new BlockPos(x, g, z);
                BlockState s = level.getBlockState(p);
                if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) continue;
                if (!s.getFluidState().isEmpty()) continue;
                TerrainProbe.clearVegetation(level, x, g, z, 2);
                level.setBlock(p, b.defaultBlockState(), 2);
                if (inHalo && level.random.nextInt(30) == 0) deadTree(level, p);
            }
        }
    }

    /**
     * The crust of the sterile halo: pale, dry and broken.
     *
     * <p>No new block for it. What is wanted is bare poisoned ground, and coarse dirt with gravel
     * through it and the odd patch of the spring's own sinter already reads as exactly that -
     * lighter and drier than the soil around it, without being another flat colour.</p>
     */
    static Block haloBlock(ServerLevel level) {
        int r = level.random.nextInt(10);
        if (r < 4) return Blocks.COARSE_DIRT;
        if (r < 7) return Blocks.GRAVEL;
        if (r < 9) return ModBlocks.SINTER.get();
        return Blocks.TUFF;
    }

    /**
     * A dead tree standing in the halo.
     *
     * <h2>Why bare trunks are right here and were wrong around a volcano</h2>
     * Stripping the leaves off a volcano's trees and leaving the trunks read as a bug, and it was
     * removed. The difference is the ground: a leafless oak standing in green grass looks like
     * something failed to finish, while a barkless, bleached trunk standing on pale dead crust is
     * the single most recognisable thing about a geothermal basin - Yellowstone's "bobby socks"
     * trees, killed by silica-laden water and left white to the knee where it wicked up the wood.
     *
     * <p>So this only ever runs on a cell that has just been turned into halo crust, and it uses
     * stripped logs, which are already pale and barkless, with sinter around the base.</p>
     */
    static void deadTree(ServerLevel level, BlockPos ground) {
        Block trunk = level.random.nextBoolean() ? Blocks.STRIPPED_SPRUCE_LOG : Blocks.STRIPPED_OAK_LOG;
        int height = 3 + level.random.nextInt(4);
        for (int h = 1; h <= height; h++) {
            BlockPos p = ground.above(h);
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !TerrainProbe.isVegetation(s)) return;   // something is in the way
            level.setBlock(p, trunk.defaultBlockState(), 2);
        }
        // The white foot: silica drawn up out of the ground, which is where the name comes from.
        level.setBlock(ground, ModBlocks.SINTER.get().defaultBlockState(), 2);
    }

    /** Which band a given distance from the water falls in, or null past the last one. */
    static Block bandFor(double d, int[] band) {
        double edge = band[0];
        if (d <= edge) return ModBlocks.SINTER.get();
        edge += band[1];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_GREEN.get();
        edge += band[2];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_YELLOW.get();
        edge += band[3];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_ORANGE.get();
        edge += band[4];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_BROWN.get();
        return null;
    }
}
