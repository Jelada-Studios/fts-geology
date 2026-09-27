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

    private record Api(Class<?> branch, Method isRooty, Method destroy, Field species, Field wood, Method drops,
                       Method drop, Object harvest) {
        static Api find() {
            if (!ModList.get().isLoaded("dynamictrees")) return null;
            try {
                Class<?> branch = Class.forName("com.ferreusveritas.dynamictrees.block.branch.BranchBlock");
                Class<?> helper = Class.forName("com.ferreusveritas.dynamictrees.api.TreeHelper");
                Class<?> data = Class.forName("com.ferreusveritas.dynamictrees.util.BranchDestructionData");
                Class<?> species = Class.forName("com.ferreusveritas.dynamictrees.tree.species.Species");
                Class<?> volume = Class.forName("com.ferreusveritas.dynamictrees.systems.nodemapper.NetVolumeNode$Volume");
                Class<?> falling = Class.forName("com.ferreusveritas.dynamictrees.entity.FallingTreeEntity");
                Class<?> type = Class.forName("com.ferreusveritas.dynamictrees.entity.FallingTreeEntity$DestroyType");
                Object harvest = null;
                for (Object c : type.getEnumConstants()) if (((Enum<?>) c).name().equals("HARVEST")) harvest = c;
                if (harvest == null) throw new NoSuchFieldException("DestroyType.HARVEST");
                return new Api(branch, helper.getMethod("isRooty", BlockState.class),
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

    /** Whether a block is the rooted soil a Dynamic Trees tree stands in. */
    public static boolean isRooty(BlockState state) {
        if (API == null) return false;
        try {
            return (Boolean) API.isRooty().invoke(null, state);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return false;
        }
    }

    /**
     * Fells the tree whose lowest branch is at {@code base}, falling away from {@code from}: a horizontal side, the way
     * a cut is struck. Whether it came down.
     */
    public static boolean fell(ServerLevel level, BlockPos base, Direction from) {
        if (API == null) return false;
        BlockState state = level.getBlockState(base);
        if (!API.branch().isInstance(state.getBlock())) return false;
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
