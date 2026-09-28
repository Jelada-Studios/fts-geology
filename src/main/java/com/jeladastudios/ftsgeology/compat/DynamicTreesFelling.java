package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Trees of Dynamic Trees brought down by an earthquake. A Dynamic Trees tree is one piece: taken at its lowest branch
 * it comes down whole, falling over the way a felled one does, and leaves its logs where it lands.
 *
 * <p>Dynamic Trees is not a dependency (MIT): its branch block, the tree's destruction and the falling tree are looked
 * up by name once, and it is felled the way Dynamic Trees fells a tree cut by a player, without the player. Without
 * Dynamic Trees none of this is ever called.</p>
 */
public final class DynamicTreesFelling {

    private DynamicTreesFelling() {}

    private static final Api API = Api.find();

    private record Api(Class<?> branch, Method destroy, Field species, Field wood, Method drops,
                       Method drop, Object harvest) {
        static Api find() {
            if (!ModList.get().isLoaded("dynamictrees")) return null;
            try {
                Class<?> branch = Class.forName("com.ferreusveritas.dynamictrees.block.branch.BranchBlock");
                Class<?> data = Class.forName("com.ferreusveritas.dynamictrees.util.BranchDestructionData");
                Class<?> species = Class.forName("com.ferreusveritas.dynamictrees.tree.species.Species");
                Class<?> volume = Class.forName("com.ferreusveritas.dynamictrees.systems.nodemapper.NetVolumeNode$Volume");
                Class<?> falling = Class.forName("com.ferreusveritas.dynamictrees.entity.FallingTreeEntity");
                Class<?> type = Class.forName("com.ferreusveritas.dynamictrees.entity.FallingTreeEntity$DestroyType");
                Object harvest = null;
                for (Object c : type.getEnumConstants()) if (((Enum<?>) c).name().equals("HARVEST")) harvest = c;
                if (harvest == null) throw new NoSuchFieldException("DestroyType.HARVEST");
                return new Api(branch,
                        branch.getMethod("destroyBranchFromNode", Level.class, BlockPos.class, Direction.class, boolean.class, LivingEntity.class),
                        data.getField("species"), data.getField("woodVolume"),
                        species.getMethod("getBranchesDrops", Level.class, volume),
                        falling.getMethod("dropTree", Level.class, data, List.class, type), harvest);
            } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                GeysersMod.LOGGER.warn("Dynamic Trees is installed but its trees were not found; earthquakes leave them standing: {}", e.toString());
                return null;
            }
        }
    }

    public static boolean present() {
        return API != null;
    }

    /** Whether a block is a branch of a Dynamic Trees tree. */
    public static boolean isBranch(BlockState state) {
        return API != null && API.branch().isInstance(state.getBlock());
    }

    /** Dynamic Trees' switch for what a branch taken away does to its tree, and the setting that leaves the tree be. */
    private static final Field DESTROY_MODE;
    private static final Object LEAVE_BE;

    static {
        Field mode = null;
        Object ignore = null;
        if (API != null) {
            try {
                mode = API.branch().getField("destroyMode");
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object value = Enum.valueOf((Class) mode.getType(), "IGNORE");
                ignore = value;
            } catch (ReflectiveOperationException | IllegalArgumentException | ClassCastException e) {
                mode = null;
                ignore = null;
            }
        }
        DESTROY_MODE = mode;
        LEAVE_BE = ignore;
    }

    /**
     * Writes over a Dynamic Trees branch without its tree coming down. A branch replaced by anything but another branch
     * is taken for one broken, and Dynamic Trees tears the whole tree down with it; a quake moving a tree block by block
     * replaces its trunk's foot first, and the tree was gone before it had risen a block. Dynamic Trees' own deliberate
     * breaks switch the same setting round their write.
     */
    public static <T> T quietly(java.util.function.Supplier<T> write) {
        if (DESTROY_MODE == null) return write.get();
        Object was;
        try {
            was = DESTROY_MODE.get(null);
            DESTROY_MODE.set(null, LEAVE_BE);
        } catch (IllegalAccessException e) {
            return write.get();
        }
        try {
            return write.get();
        } finally {
            try {
                DESTROY_MODE.set(null, was);
            } catch (IllegalAccessException ignored) {
                // Set a moment ago, so it can be set back.
            }
        }
    }

    /** Dynamic Trees' own tag for its rooted soils; empty, and never matched, without Dynamic Trees. */
    private static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> ROOTY =
            net.minecraft.tags.BlockTags.create(new net.minecraft.resources.ResourceLocation("dynamictrees", "rooty_soil"));

    /**
     * Whether a block is any part of a Dynamic Trees tree: branch, leaves, rooted soil, the shell round a thick trunk,
     * a root along the ground. Only some of them carry vanilla's tree tags. Told by Dynamic Trees' own block classes,
     * so the trees its add-ons bring (Terralith's, for one) count too; a block of its namespace that is none of these,
     * a sapling or a fruit, counts as well.
     */
    public static boolean isTreeBlock(BlockState state) {
        if (API == null) return false;
        net.minecraft.world.level.block.Block block = state.getBlock();
        for (Class<?> c : TREE_CLASSES) {
            if (c.isInstance(block)) return true;
        }
        return "dynamictrees".equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getNamespace());
    }

    /** Dynamic Trees' block classes a tree is made of; empty without it. */
    private static final Class<?>[] TREE_CLASSES = treeClasses();

    private static Class<?>[] treeClasses() {
        if (!ModList.get().isLoaded("dynamictrees")) return new Class<?>[0];
        List<Class<?>> out = new java.util.ArrayList<>();
        for (String n : new String[]{"block.branch.BranchBlock", "block.branch.TrunkShellBlock", "block.branch.SurfaceRootBlock",
                "block.leaves.DynamicLeavesBlock", "block.rooty.RootyBlock"}) {
            try {
                out.add(Class.forName("com.ferreusveritas.dynamictrees." + n));
            } catch (ClassNotFoundException | LinkageError e) {
                // A class a later Dynamic Trees renamed: its blocks are still told by their namespace.
            }
        }
        return out.toArray(new Class<?>[0]);
    }

    /** Whether a block is the rooted soil a Dynamic Trees tree stands in. A tag read, cheap enough for every column. */
    public static boolean isRooty(BlockState state) {
        return state.is(ROOTY);
    }

    /**
     * Fells the tree whose lowest branch is at {@code base}, falling away from {@code from}: a horizontal side, the way
     * a cut is struck. Whether it came down.
     */
    public static boolean fell(ServerLevel level, BlockPos base, Direction from) {
        if (API == null) return false;
        BlockState state = level.getBlockState(base);
        if (!API.branch().isInstance(state.getBlock())) return false;
        // A tree near the edge of what is loaded is left standing: working it out would load the chunks next door.
        if (!com.jeladastudios.ftsgeology.quake.QuakeWrites.around(level, base)) return false;
        try {
            Object data = API.destroy().invoke(state.getBlock(), level, base, from, false, null);
            if (data == null) return false;
            Object species = API.species().get(data);
            @SuppressWarnings("unchecked")
            List<Object> drops = (List<Object>) API.drops().invoke(species, level, API.wood().get(data));
            API.drop().invoke(null, level, data, drops, API.harvest());
            return true;
        } catch (ReflectiveOperationException | ClassCastException e) {
            GeysersMod.LOGGER.debug("Dynamic Trees tree at {} not felled: {}", base, e.toString());
            return false;
        }
    }
}
