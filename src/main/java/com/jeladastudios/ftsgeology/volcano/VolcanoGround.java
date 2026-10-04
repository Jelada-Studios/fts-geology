package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.volcano.VolcanoPlan.Ctx;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyBiomeSource;
import com.jeladastudios.ftsgeology.worldgen.terrain.WorldgenRevision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A large volcano's own ground, set once its mountain stands in a chunk (worlds made since {@link
 * WorldgenRevision#VOLCANO_GROUND}). The biome source cannot ask where the volcanoes are -- finding one asks the
 * structures, and they ask the biomes -- so the volcano writes its biome over its own footprint, as {@code /fillbiome}
 * does, before the chunk is ever sent:
 * <ul>
 *   <li>its crater and the young lava the skin lays down a live mountain stay the bare volcanic highland, and so does the
 *       fresh tephra a live one has strewn downwind of itself (Etna's east flank, Sakurajima, St Helens' blast zone);</li>
 *   <li>the rest of the mountain is belted by height as a volcanic arc is, wherever it stands: a mountain whose foot runs
 *       out of the arc's strip, or that stands on a plain, is forest, heath, scree and snow up its flank and not the
 *       plain's grassland to its summit; a shield in a dry arc's highland, bare tuff and ash gravel to its foot, stays the
 *       volcanic highland.</li>
 * </ul>
 */
public final class VolcanoGround {

    private VolcanoGround() {}

    /** The climate's humidity under which an arc stays bare, its ash unweathered (as the biome source has it). */
    private static final double ARC_DRY = -0.35;
    /** Half the width of the downwind fan of fresh tephra, as the cosine of its angle off the wind (35 degrees). */
    private static final double FAN = 0.82;
    /** How much tephra makes a column of it, and a deep one. */
    private static final double TEPHRA = 0.35, DEEP = 0.7;

    /** Whether an arc's volcano stands in a climate wet enough for its flank to grass over, in a world made for it. */
    static boolean greenArc(ServerLevel level, int x, int z) {
        if (!WorldgenRevision.has(WorldgenRevision.VOLCANO_GROUND)) return false;
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        return Climate.unquantizeCoord(sampler.sample(QuartPos.fromBlock(x), 0, QuartPos.fromBlock(z)).humidity()) >= ARC_DRY;
    }

    /**
     * Whether a column of a mountain is bare volcanic ground: the crater and its rim of a live one, and the young lava its
     * skin lays down (a stratocone's dark tongues above its tree line, a shield's younger flows and its fresh summit).
     */
    static boolean bare(Ctx c, int gx, int gz, int surfaceY) {
        if (c.activity == VolcanoActivity.EXTINCT) return false;
        double d = Math.hypot(gx - c.x, gz - c.z);
        if (d <= c.craterR + c.rimWidth) return true;
        if (d > c.coneBaseR || c.size == VolcanoSize.SMALL) return false;
        double h = (surfaceY - c.baseY) / (double) Math.max(1, c.coneHeight);
        return switch (c.type) {
            case STRATOVOLCANO -> h > VolcanoEdifice.stratoLine(c, gx, gz) && VolcanoEdifice.oldLava(c, gx, gz) > 0.32;
            case SHIELD -> VolcanoEdifice.shieldTongue(c, gx, gz) > 0.55 || h > VolcanoEdifice.shieldLine(c, gx, gz) + 0.18;
            default -> false;
        };
    }

    /**
     * Whether a mountain's whole flank is bare: a shield in a dry arc's highland keeps the tuff, ash gravel and old basalt
     * of the country round it ({@link VolcanoEdifice#highlandSkin}); any other volcano grasses over below its tree line,
     * whatever the climate, and is belted.
     */
    static boolean bareFlank(Ctx c) {
        return c.type == VolcanoType.SHIELD && c.highland && !c.greenFlank;
    }

    /**
     * How thick the fresh tephra lies on a column, 0 to 1: only round a live mountain, in a fan downwind of it (its
     * prevailing wind, the one its eruptions drop their ash by), thick over the cone and thinning out over its apron.
     */
    static double tephra(Ctx c, int gx, int gz) {
        if (c.activity != VolcanoActivity.ACTIVE || c.size == VolcanoSize.SMALL || c.coneBaseR <= 0) return 0;
        double dx = gx - c.x, dz = gz - c.z, d = Math.hypot(dx, dz);
        if (d < 1) return 0;
        double[] wind = VolcanoEruption.wind(new BlockPos(c.x, 0, c.z));
        double along = (dx * wind[0] + dz * wind[1]) / d;
        if (along <= FAN) return 0;
        double fan = (along - FAN) / (1 - FAN);
        double out = c.clearReach > c.coneBaseR ? (d - c.coneBaseR) / (c.clearReach - c.coneBaseR) : 0;
        double radial = d <= c.coneBaseR ? 1 : Math.max(0, 1 - out);
        return Math.sqrt(fan) * radial * (0.75 + 0.25 * ValueNoise.noise(gx + 913, gz - 913, 12.0));
    }

    /** Lays the fresh tephra and writes the mountain's biomes over its footprint in this chunk, once its columns stand. */
    static void paint(WorldGenLevel level, ChunkGenerator generator, ChunkPos cp, Ctx c) {
        if (!com.jeladastudios.ftsgeology.config.GeyserConfig.VOLCANO_GROUND_BIOME.get()
                || !WorldgenRevision.has(WorldgenRevision.VOLCANO_GROUND) || c.isle != null || c.coneHeight <= 0) return;
        ChunkAccess chunk = level.getChunk(cp.x, cp.z);
        // The tephra first, column by column, so the biome below knows which columns it covers.
        boolean[] ashen = new boolean[256];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int gx = cp.getMinBlockX() + lx, gz = cp.getMinBlockZ() + lz;
                double t = tephra(c, gx, gz);
                if (t > TEPHRA) ashen[lx * 16 + lz] = strew(level, gx, gz, t);
            }
        }
        if (!(generator.getBiomeSource() instanceof GeologyBiomeSource source)) return;
        Climate.Sampler sampler = level.getLevel().getChunkSource().randomState().sampler();
        // Each quart column: 0 left as it is, 1 bare volcanic highland, 2 belted by height; from which quart up.
        int[] mode = new int[16], from = new int[16];
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            for (int k = 0; k < 4; k++) {
                int lx = i * 4 + 2, lz = k * 4 + 2;
                int gx = cp.getMinBlockX() + lx, gz = cp.getMinBlockZ() + lz;
                double d = Math.hypot(gx - c.x, gz - c.z);
                int surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, lx, lz);
                int m = bare(c, gx, gz, surface) || ashen[lx * 16 + lz] || d <= c.coneBaseR && bareFlank(c) ? 1 : d <= c.coneBaseR ? 2 : 0;
                mode[i * 4 + k] = m;
                from[i * 4 + k] = QuartPos.fromBlock(surface - 8);
                any |= m != 0;
            }
        }
        if (!any) return;
        int qx0 = QuartPos.fromBlock(cp.getMinBlockX()), qz0 = QuartPos.fromBlock(cp.getMinBlockZ());
        chunk.fillBiomesFromNoise((qx, qy, qz, s) -> {
            int j = (qx - qx0) * 4 + (qz - qz0);
            int m = j >= 0 && j < 16 && qy >= from[j] ? mode[j] : 0;
            Holder<Biome> painted = m == 0 ? null : source.volcanoGround(qx, qy, qz, sampler, m == 1);
            return painted != null ? painted : chunk.getNoiseBiome(qx, qy, qz);
        }, sampler);
    }

    /** Fresh tephra over a column's soil, black sand, scoria and ash gravel; true if it took. */
    private static boolean strew(WorldGenLevel level, int gx, int gz, double t) {
        int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR, gx, gz) - 1;
        BlockPos at = new BlockPos(gx, top, gz);
        BlockState ground = level.getBlockState(at);
        if (!ground.is(BlockTags.DIRT) || !level.getFluidState(at.above()).isEmpty()) return false;
        int r = Math.floorMod((int) (com.jeladastudios.ftsgeology.util.SeedHash.columnSeed(0x7E9A, gx, gz) >>> 3), 8);
        BlockState ash = (r < 4 ? ModBlocks.VOLCANIC_BLACK_SAND.get() : r < 6 ? Blocks.GRAVEL : Blocks.TUFF).defaultBlockState();
        level.setBlock(at, ash, Block.UPDATE_CLIENTS);
        if (t > DEEP && level.getBlockState(at.below()).is(BlockTags.DIRT)) {
            level.setBlock(at.below(), ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        // Whatever grew there is buried.
        BlockState over = level.getBlockState(at.above());
        if (!over.isAir() && over.canBeReplaced()) level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        return true;
    }
}
