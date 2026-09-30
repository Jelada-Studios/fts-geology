package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.Tags;

import java.util.Locale;

/**
 * Puddles after rain, where the ground would hold them, when Puddles &amp; Floods is not there to draw its own (with it,
 * that mod's puddles are told where the ground holds water instead: {@code ClientSoilWet}).
 *
 * <p>Each cell of the ground's water ({@link SoilWater}) says how likely a puddle is on it: water standing on it, or rain
 * falling now on ground that cannot drink it -- rock, clay, a path, loam already soaked -- and never sand, which drinks
 * it all. Within the cell a puddle takes the hollows, where the ground lies lower than round it, in ragged shapes a
 * noise draws, larger as the ground gets wetter; as it dries they shrink from their edges and go. They lie on ground
 * nobody built, open to the sky, a pixel of water ({@link com.jeladastudios.ftsgeology.block.PuddleBlock}) that a shader
 * draws as water.</p>
 */
public final class Puddles {

    private Puddles() {}

    /** Ground hours a puddle on ground that does not drink it lasts after the rain, halving. */
    private static final double LINGER = 30;

    private static long laid, dried;
    private static Boolean otherMod;

    /** Whether puddles are the mod's to lay: the setting on, and Puddles &amp; Floods not installed. */
    static boolean on() {
        if (otherMod == null) otherMod = net.minecraftforge.fml.ModList.get().isLoaded("puddleflood");
        return GeyserConfig.PUDDLES.get() && !otherMod;
    }

    /**
     * Lays and takes back the puddles of a chunk, from its cells after a look: {@code rain} is how hard it rains over
     * it now, {@code hours} the ground's time since the last look.
     */
    static void update(ServerLevel level, LevelChunk chunk, SoilWater.Cells c, float rain, double hours) {
        boolean on = on();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        LongSet placed = null;
        BlockState puddle = ModBlocks.PUDDLE.get().defaultBlockState();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double fade = Math.exp(-Math.max(0, hours) / LINGER);
        for (int i = 0; i < 16; i++) {
            double wet = 0;
            if (on && c.kind[i] >= 0) {
                SoilWater.Soil s = SoilWater.Soil.values()[c.kind[i]];
                if (s.ground() && s != SoilWater.Soil.SAND) {
                    double pond = Mth.clamp(c.pond[i] / 8.0, 0, 1);
                    double top = s.top > 0 ? Mth.clamp(c.top[i] / s.top, 0, 1) : 0;
                    double drinks = switch (s) {
                        case ROCK -> 1.0;
                        case CLAY -> 0.9;
                        case LOAM -> top >= 0.85 ? 0.7 : 0.15;
                        default -> 0.0;
                    };
                    // Rain only where rain falls: not in a desert or a badlands under a passing storm, nor as snow.
                    boolean rains = rain >= 0.15f && rainsAt(chunk, x0 + (i & 3) * 4 + 2, z0 + (i >> 2) * 4 + 2);
                    wet = Math.max(pond, rains ? rain * drinks : 0);
                }
            }
            c.puddle[i] = (float) Math.max(c.puddle[i] * fade, wet);
            double p = c.puddle[i];
            int cx = (i & 3) * 4, cz = (i >> 2) * 4;
            for (int dx = 0; dx < 4; dx++) {
                for (int dz = 0; dz < 4; dz++) {
                    int lx = cx + dx, lz = cz + dz, x = x0 + lx, z = z0 + lz;
                    int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                    BlockState at = chunk.getBlockState(m.set(x, top, z));
                    boolean there = at.is(ModBlocks.PUDDLE.get());
                    int g = there ? top - 1 : top;
                    boolean want = false;
                    if (p > 0.05) {
                        if (placed == null) placed = com.jeladastudios.ftsgeology.quake.PlayerBuilt.inChunk(level, chunk.getPos().x, chunk.getPos().z);
                        BlockState ground = chunk.getBlockState(m.set(x, g, z));
                        if (holds(level, m, ground) && !placed.contains(m.asLong())) {
                            // A hollow: lower than the ground on more sides round it than not.
                            int lower = 0;
                            for (Direction d : Direction.Plane.HORIZONTAL) {
                                int nx = lx + d.getStepX(), nz = lz + d.getStepZ();
                                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                                int nt = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, nx, nz);
                                if (chunk.getBlockState(m.set(x0 + nx, nt, z0 + nz)).is(ModBlocks.PUDDLE.get())) nt--;
                                if (nt > g) lower++;
                            }
                            double shape = 0.7 * ValueNoise.noise(x + 911, z - 911, 4.5) + 0.3 * ValueNoise.noise(x - 77, z + 77, 1.7);
                            double value = 0.8 * shape + 0.2 * (lower / 4.0);
                            want = value > 0.75 - 0.3 * p && level.canSeeSky(m.set(x, g + 1, z));
                        }
                    }
                    if (want && !there) {
                        m.set(x, g + 1, z);
                        if (chunk.getBlockState(m).isAir()) {
                            level.setBlock(m, puddle, Block.UPDATE_CLIENTS);
                            laid++;
                        }
                    } else if (!want && there) {
                        level.setBlock(m.set(x, top, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                        dried++;
                    }
                }
            }
        }
    }

    /** Whether what falls at a column of a chunk is rain: its biome has any, and it is warm enough there not to snow. */
    static boolean rainsAt(LevelChunk chunk, int x, int z) {
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1;
        BlockPos pos = new BlockPos(x, y, z);
        var biome = chunk.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), net.minecraft.core.QuartPos.fromBlock(y),
                net.minecraft.core.QuartPos.fromBlock(z)).value();
        return biome.getPrecipitationAt(pos) == net.minecraft.world.level.biome.Biome.Precipitation.RAIN;
    }

    /** Ground a puddle lies on: flat and firm on top, not sand or gravel, not tilled, not snow or ice, nothing that holds contents. */
    private static boolean holds(ServerLevel level, BlockPos pos, BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty() || s.hasBlockEntity()) return false;
        if (s.is(BlockTags.SAND) || s.is(Tags.Blocks.GRAVEL) || s.is(Blocks.FARMLAND) || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE)
                || s.is(BlockTags.LEAVES) || s.is(SoilBlocks.FARMLAND)) {
            return false;
        }
        if (com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(s) && !s.is(Blocks.DIRT_PATH)
                && !s.is(SoilBlocks.NATURAL_GROUND)) {
            return false;
        }
        return s.isFaceSturdy(level, pos, Direction.UP);
    }

    public static String summary() {
        return String.format(Locale.ROOT, "puddles: %d laid, %d dried", laid, dried);
    }

    public static boolean any() {
        return laid + dried > 0;
    }

    public static void clear() {
        laid = dried = 0;
        otherMod = null;
    }
}
