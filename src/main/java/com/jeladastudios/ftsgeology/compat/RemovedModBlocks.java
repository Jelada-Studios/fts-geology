package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The blocks a mod laid in the ground, when the mod is taken out of the world.
 *
 * <p>A chunk keeps its blocks by name. Once a mod is gone, the game reads the names it no longer knows as air, and the
 * next save makes the hole for good: fossil and ore pockets, crystals, the mod's own stone, all left empty in the rock
 * and in cave walls. Here, as a chunk loads, the names the game does not know are looked up in the chunk as it was
 * saved and what stood there is put back as rock: the rock round it, or deepslate for a deepslate one. What reads as a
 * made thing -- bricks, planks, glass, lamps, doors -- or as a plant goes, as it would have. A name that says neither
 * becomes rock only where rock closes round it on every side, as a mod's own stone does; anything standing in a cave
 * goes too. A player's own rules come first.</p>
 *
 * <p>Only what the chunk still has on disk can come back: ground loaded and saved without the mod has lost it, and
 * {@code /geology fillvoids} closes the small holes it left.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class RemovedModBlocks {

    private RemovedModBlocks() {}

    private enum Way { KNOWN, LEAVE, ROCK, CLOSED, GIVEN }

    private record Choice(Way way, BlockState given) {}

    private static final Choice KNOWN = new Choice(Way.KNOWN, null), LEAVE = new Choice(Way.LEAVE, null),
            ROCK = new Choice(Way.ROCK, null), CLOSED = new Choice(Way.CLOSED, null);

    /** Words in a missing block's name that say it was part of the ground. */
    private static final Set<String> GROUND = Set.of("ore", "ores", "fossil", "fossils", "stone", "rock", "rocks",
            "deepslate", "slate", "gravel", "dirt", "soil", "clay", "sand", "crystal", "crystals", "amber", "geode",
            "deposit", "vein", "salt", "marble", "granite", "basalt", "limestone", "shale", "tuff", "mineral", "gem",
            "matrix", "bone", "bones");
    /** Words that say it was made, or grew: it goes, as it would have. */
    private static final Set<String> MADE = Set.of("brick", "bricks", "tile", "tiles", "polished", "smooth", "cut",
            "chiseled", "planks", "concrete", "glazed", "glass", "pane", "lamp", "lantern", "torch", "candle", "door",
            "trapdoor", "table", "chair", "chest", "barrel", "crate", "sign", "button", "lever", "pipe", "cable",
            "wire", "machine", "generator", "bed", "banner", "window", "fence", "gate", "rail", "ladder", "slab",
            "stairs", "wall", "pillar", "carved", "leaves", "log", "wood", "sapling", "flower", "plant", "vine", "fern",
            "grass", "mushroom", "egg", "eggs", "nest", "wool", "carpet", "incubator", "display", "cage", "root", "roots",
            "fungus", "fungi", "moss", "spore", "spores", "sporophyte", "lichen", "algae", "kelp", "coral", "bush", "shrub",
            "berry", "berries", "crop", "crops", "stem", "cap", "sprout", "petals", "lily", "reed", "reeds", "cactus",
            "bamboo", "cane", "seaweed", "web", "cobweb", "mycelium");

    private static final Map<String, Choice> CHOSEN = new ConcurrentHashMap<>();
    /** How many of each missing block came back as rock, and how many went. */
    private static final Map<String, int[]> COUNTS = new ConcurrentHashMap<>();
    private static volatile Map<String, Choice> rules;

    @SubscribeEvent
    public static void onChunkLoad(ChunkDataEvent.Load event) {
        if (!GeyserConfig.REMOVED_MOD_BLOCKS.get()) return;
        CompoundTag tag = event.getData();
        if (tag == null || !tag.contains("sections", Tag.TAG_LIST)) return;
        ChunkAccess chunk = event.getChunk();
        ListTag sections = tag.getList("sections", Tag.TAG_COMPOUND);
        boolean changed = false;
        for (int si = 0; si < sections.size(); si++) {
            CompoundTag s = sections.getCompound(si);
            if (!s.contains("block_states", Tag.TAG_COMPOUND)) continue;
            CompoundTag states = s.getCompound("block_states");
            ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
            int n = palette.size();
            Choice[] plan = null;
            String[] names = null;
            for (int i = 0; i < n; i++) {
                String name = palette.getCompound(i).getString("Name");
                Choice c = choose(name);
                if (c == KNOWN) continue;
                if (plan == null) {
                    plan = new Choice[n];
                    names = new String[n];
                }
                plan[i] = c;
                names[i] = name;
            }
            if (plan == null) continue;
            int index = chunk.getSectionIndexFromSectionY(s.getByte("Y"));
            if (index < 0 || index >= chunk.getSectionsCount()) continue;
            int[] at = indices(states, n);
            if (at == null) continue;
            changed |= mend(chunk, index, at, plan, names);
        }
        if (changed) chunk.setUnsaved(true);
    }

    /** The palette index of each of a section's 4096 blocks, as the chunk was saved, or null if it cannot be read. */
    private static int[] indices(CompoundTag states, int n) {
        int[] out = new int[4096];
        if (n <= 1) return out;
        long[] data = states.getLongArray("data");
        int bits = Math.max(4, Mth.ceillog2(n));
        int per = 64 / bits;
        if (data.length != (4096 + per - 1) / per) return null;
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < 4096; i++) {
            int v = (int) (data[i / per] >>> ((i % per) * bits) & mask);
            if (v >= n) return null;
            out[i] = v;
        }
        return out;
    }

    /** Puts back what a section lost: rock where it was ground, the player's choice where they made one. */
    private static boolean mend(ChunkAccess chunk, int index, int[] at, Choice[] plan, String[] names) {
        LevelChunkSection section = chunk.getSection(index);
        boolean[] missing = new boolean[4096];
        for (int i = 0; i < 4096; i++) missing[i] = plan[at[i]] != null && plan[at[i]].way() != Way.LEAVE;
        BlockState common = null;
        boolean changed = false;
        for (int i = 0; i < 4096; i++) {
            Choice c = plan[at[i]];
            if (c == null) continue;
            String name = names[at[i]];
            int[] count = COUNTS.computeIfAbsent(name, k -> new int[2]);
            int x = i & 15, z = i >> 4 & 15, y = i >> 8;
            BlockState put = null;
            if (c.way() == Way.GIVEN) {
                put = c.given();
            } else if (c.way() == Way.ROCK || c.way() == Way.CLOSED && closed(section, missing, x, y, z)) {
                put = rockBeside(section, x, y, z);
                if (put == null) {
                    if (common == null) common = commonRock(chunk, index);
                    put = common;
                }
                if (name.contains("deepslate") && !put.is(Blocks.DEEPSLATE) && (put.is(Blocks.STONE) || put.is(Blocks.TUFF))) {
                    put = Blocks.DEEPSLATE.defaultBlockState();
                }
            }
            if (put == null || put.isAir()) {
                count[1]++;
                continue;
            }
            section.setBlockState(x, y, z, put, false);
            count[0]++;
            changed = true;
        }
        return changed;
    }

    /**
     * Whether rock closes round a place on every side: a pocket in the rock. Another missing block counts as rock; a
     * side past the section, which cannot be read yet, not at all. A plant or anything else standing in a cave has a
     * side open, and goes.
     */
    private static boolean closed(LevelChunkSection section, boolean[] missing, int x, int y, int z) {
        int known = 0, open = 0;
        for (int[] d : SIDES) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) continue;
            known++;
            if (missing[ny << 8 | nz << 4 | nx]) continue;
            BlockState s = section.getBlockState(nx, ny, nz);
            if (s.isAir() || !s.getFluidState().isEmpty() || !s.isSolid()) open++;
        }
        return known >= 3 && open == 0;
    }

    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** The rock most of a place's sides are, or null if none is rock. */
    private static BlockState rockBeside(LevelChunkSection section, int x, int y, int z) {
        BlockState best = null;
        int bestN = 0;
        BlockState[] seen = new BlockState[6];
        int[] n = new int[6];
        int kinds = 0;
        for (int[] d : SIDES) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) continue;
            BlockState s = section.getBlockState(nx, ny, nz);
            if (!isRock(s)) continue;
            int k = 0;
            while (k < kinds && seen[k] != s) k++;
            if (k == kinds) seen[kinds++] = s;
            if (++n[k] > bestN) {
                bestN = n[k];
                best = s;
            }
        }
        return best;
    }

    /** The rock most of a section is, or of the sections over and under it; failing all, stone or deepslate by depth. */
    private static BlockState commonRock(ChunkAccess chunk, int index) {
        for (int d : new int[]{0, -1, 1, -2, 2}) {
            int k = index + d;
            if (k < 0 || k >= chunk.getSectionsCount()) continue;
            Map<BlockState, Integer> count = new HashMap<>();
            chunk.getSection(k).getStates().count((s, c) -> {
                if (isRock(s)) count.merge(s, c, Integer::sum);
            });
            BlockState best = null;
            int bestN = 0;
            for (Map.Entry<BlockState, Integer> e : count.entrySet()) {
                if (e.getValue() > bestN) {
                    bestN = e.getValue();
                    best = e.getKey();
                }
            }
            if (best != null) return best;
        }
        int y = chunk.getMinBuildHeight() + index * 16;
        return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    /** Country rock: what a hole in the ground is filled with. */
    static boolean isRock(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Tags.Blocks.STONE) || TfcCompat.isRock(s)
                || s.is(BlockTags.BASE_STONE_NETHER) || s.is(Blocks.END_STONE)
                || s.is(Blocks.CALCITE) || s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.BLACKSTONE)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(BlockTags.TERRACOTTA);
    }

    /** What a block's name comes back as: KNOWN for a block the game has. */
    private static Choice choose(String name) {
        Choice c = CHOSEN.get(name);
        if (c != null) return c;
        c = decide(name);
        CHOSEN.put(name, c);
        if (c != KNOWN) {
            GeysersMod.LOGGER.info("A block of a removed mod, {}, {}", name, switch (c.way()) {
                case LEAVE -> "goes, as a build or a plant";
                case ROCK -> "comes back as the rock round it";
                case CLOSED -> "comes back as rock where rock closes round it";
                case GIVEN -> "comes back as " + ForgeRegistries.BLOCKS.getKey(c.given().getBlock());
                default -> "";
            });
        }
        return c;
    }

    private static Choice decide(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null || ForgeRegistries.BLOCKS.containsKey(id)) return KNOWN;
        Map<String, Choice> r = rules();
        Choice given = r.get(id.toString());
        if (given == null) given = r.get(id.getNamespace() + ":*");
        if (given != null) return given;
        boolean ground = id.getPath().contains("fossil");
        for (String word : id.getPath().toLowerCase(Locale.ROOT).split("[_/.\\-]")) {
            if (MADE.contains(word)) return LEAVE;
            if (GROUND.contains(word)) ground = true;
        }
        return ground ? ROCK : CLOSED;
    }

    private static Map<String, Choice> rules() {
        Map<String, Choice> r = rules;
        if (r != null) return r;
        r = new HashMap<>();
        for (String line : (List<? extends String>) GeyserConfig.REMOVED_MOD_BLOCK_RULES.get()) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String block = line.substring(0, eq).trim(), to = line.substring(eq + 1).trim();
            Choice c;
            if (to.equalsIgnoreCase("air")) {
                c = LEAVE;
            } else if (to.equalsIgnoreCase("rock")) {
                c = ROCK;
            } else {
                ResourceLocation t = ResourceLocation.tryParse(to);
                if (t == null || !ForgeRegistries.BLOCKS.containsKey(t)) {
                    GeysersMod.LOGGER.warn("removedModBlockRules: {} is not a block; {} is left to the rules", to, block);
                    continue;
                }
                c = new Choice(Way.GIVEN, ForgeRegistries.BLOCKS.getValue(t).defaultBlockState());
            }
            r.put(block, c);
        }
        rules = r;
        return r;
    }

    /** Most blocks in a pocket closed all round that is filled; a larger one is a cave. */
    private static final int POCKET = 32;

    /** What {@link #fillVoids} did: pockets closed, the blocks that took, niches filled. */
    public record Filled(int pockets, int blocks, int niches) {}

    /**
     * Closes the small holes a removed mod's blocks left in the ground, round a place: pockets of air of up to
     * {@link #POCKET} blocks that natural ground closes in on every side, and one-block niches in cave walls with
     * ground on five sides. They are filled with the rock round them. Only loaded ground under the surface, never
     * where a player built, nor a pocket shaped like a dug room or tunnel; a pocket or niche the world made itself goes
     * too, which does no harm.
     */
    public static Filled fillVoids(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos centre, int r) {
        int pockets = 0, blocks = 0, niches = 0;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        Map<Long, it.unimi.dsi.fastutil.longs.LongSet> built = new HashMap<>();
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = centre.getX() - r; x <= centre.getX() + r; x++) {
            for (int z = centre.getZ() - r; z <= centre.getZ() + r; z++) {
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, x, z)) continue;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z) - 3;
                for (int y = level.getMinBuildHeight() + 1; y < top; y++) {
                    m.set(x, y, z);
                    if (!level.getBlockState(m).isAir()) continue;
                    java.util.List<net.minecraft.core.BlockPos> cells;
                    if (niche(level, m, built)) {
                        cells = java.util.List.of(m.immutable());
                        niches++;
                    } else {
                        if (seen.contains(m.asLong())) continue;
                        cells = pocket(level, m.immutable(), centre, r, seen, built);
                        if (cells == null) continue;
                        pockets++;
                    }
                    BlockState rock = rockRound(level, cells);
                    for (net.minecraft.core.BlockPos p : cells) level.setBlock(p, rock, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                    blocks += cells.size();
                }
            }
        }
        return new Filled(pockets, blocks, niches);
    }

    /**
     * The air joined to a place, if natural ground closes it in on every side and it is no more than
     * {@link #POCKET} blocks, or it is one block with ground on five sides; null otherwise.
     */
    private static java.util.List<net.minecraft.core.BlockPos> pocket(net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos start, net.minecraft.core.BlockPos centre, int r,
            it.unimi.dsi.fastutil.longs.LongOpenHashSet seen, Map<Long, it.unimi.dsi.fastutil.longs.LongSet> built) {
        java.util.List<net.minecraft.core.BlockPos> cells = new java.util.ArrayList<>();
        java.util.ArrayDeque<net.minecraft.core.BlockPos> todo = new java.util.ArrayDeque<>();
        todo.add(start);
        seen.add(start.asLong());
        boolean sealed = true;
        while (!todo.isEmpty()) {
            net.minecraft.core.BlockPos p = todo.poll();
            cells.add(p);
            if (cells.size() > POCKET) return null;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                net.minecraft.core.BlockPos q = p.relative(d);
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, q)) return null;
                BlockState s = level.getBlockState(q);
                if (s.isAir()) {
                    if (Math.abs(q.getX() - centre.getX()) > r || Math.abs(q.getZ() - centre.getZ()) > r) return null;
                    if (seen.add(q.asLong())) todo.add(q);
                } else if (!natural(level, q, s, built)) {
                    sealed = false;
                }
            }
        }
        if (sealed && !dug(cells)) return cells;
        return null;
    }

    /**
     * Whether a pocket reads as dug: a room or a tunnel a player cut, walled up since. What a removed mod leaves is the
     * shape of its ore, its fossil or its crystal, ragged; a dug space stands on a flat floor, at least two blocks high,
     * and fills the box round it.
     */
    private static boolean dug(java.util.List<net.minecraft.core.BlockPos> cells) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (net.minecraft.core.BlockPos p : cells) {
            x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
        }
        if (y1 - y0 < 1) return false;
        int box = (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (cells.size() < 0.9 * box) return false;
        // Every column of it reaches down to the same floor.
        it.unimi.dsi.fastutil.longs.LongOpenHashSet columns = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(), floor = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (net.minecraft.core.BlockPos p : cells) {
            long c = net.minecraft.world.level.ChunkPos.asLong(p.getX(), p.getZ());
            columns.add(c);
            if (p.getY() == y0) floor.add(c);
        }
        return floor.size() == columns.size();
    }

    /** Whether an air block has natural ground on five sides and air on the sixth: a niche in a cave wall. */
    private static boolean niche(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos p,
                                 Map<Long, it.unimi.dsi.fastutil.longs.LongSet> built) {
        int ground = 0, air = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            net.minecraft.core.BlockPos q = p.relative(d);
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, q)) return false;
            BlockState s = level.getBlockState(q);
            if (s.isAir()) air++;
            else if (natural(level, q, s, built)) ground++;
        }
        return ground == 5 && air == 1;
    }

    /** Natural ground no player put there: rock, ore, soil, gravel, sand. */
    private static boolean natural(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos q, BlockState s,
                                   Map<Long, it.unimi.dsi.fastutil.longs.LongSet> built) {
        if (!s.getFluidState().isEmpty()) return false;
        if (!(isRock(s) || s.is(Tags.Blocks.ORES) || s.is(BlockTags.DIRT) || s.is(Tags.Blocks.GRAVEL)
                || s.is(BlockTags.SAND) || s.is(Blocks.CLAY) || TfcCompat.isGround(s))) {
            return false;
        }
        long chunk = net.minecraft.world.level.ChunkPos.asLong(q.getX() >> 4, q.getZ() >> 4);
        it.unimi.dsi.fastutil.longs.LongSet placed = built.computeIfAbsent(chunk,
                c -> com.jeladastudios.ftsgeology.quake.PlayerBuilt.inChunk(level, q.getX() >> 4, q.getZ() >> 4));
        return placed == null || !placed.contains(q.asLong());
    }

    /** The rock the walls of a pocket are mostly made of; stone or deepslate by depth where they are ore or soil. */
    private static BlockState rockRound(net.minecraft.server.level.ServerLevel level, java.util.List<net.minecraft.core.BlockPos> cells) {
        Map<BlockState, Integer> count = new HashMap<>();
        for (net.minecraft.core.BlockPos p : cells) {
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                BlockState s = level.getBlockState(p.relative(d));
                if (isRock(s)) count.merge(s, 1, Integer::sum);
            }
        }
        BlockState best = null;
        int bestN = 0;
        for (Map.Entry<BlockState, Integer> e : count.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        if (best != null) return best;
        return cells.get(0).getY() < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        for (Map.Entry<String, int[]> e : COUNTS.entrySet()) {
            GeysersMod.LOGGER.info("Blocks of a removed mod: {}, {} back as rock, {} gone", e.getKey(),
                    e.getValue()[0], e.getValue()[1]);
        }
        COUNTS.clear();
        CHOSEN.clear();
        rules = null;
    }
}
