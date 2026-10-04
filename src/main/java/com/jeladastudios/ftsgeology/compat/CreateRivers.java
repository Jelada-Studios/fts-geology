package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.registry.ModFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * A river's water to Create's pumps is water.
 *
 * <p>A river is filled with the mod's own fluid so that it can stand in steps and never spread, but Create knows water
 * only as vanilla's: a pump drew "river water" out of a river, a boiler would not take it, and every bucket's worth
 * pulled out left a hollow in the river that nothing filled. An open pipe end in a river now draws plain water and
 * leaves the river as it was -- a river does not run dry for a pump on its bank -- and a hose pulley's water comes
 * up as plain water too.</p>
 *
 * <p>Create is not a dependency: the pipe is reached through its public methods, looked up once, and without Create
 * none of this is ever called.</p>
 */
public final class CreateRivers {

    private CreateRivers() {}

    private static final MethodHandle WORLD, OUTPUT;

    static {
        MethodHandle world = null, output = null;
        try {
            Class<?> pipe = Class.forName("com.simibubi.create.content.fluids.OpenEndedPipe");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            world = lookup.findVirtual(pipe, "getWorld", MethodType.methodType(Level.class)).asType(MethodType.methodType(Level.class, Object.class));
            output = lookup.findVirtual(pipe, "getOutputPos", MethodType.methodType(BlockPos.class)).asType(MethodType.methodType(BlockPos.class, Object.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            world = output = null;
        }
        WORLD = world;
        OUTPUT = output;
    }

    /** Whether a fluid is the river's own, as a stack of it or a block of it in the world. */
    public static boolean isRiver(Fluid fluid) {
        return fluid == ModFluids.RIVER_WATER.get() || fluid == ModFluids.FLOWING_RIVER_WATER.get();
    }

    /** The fluid a pump is to carry: water for the river's, anything else as it is. */
    public static Fluid asWater(Fluid fluid) {
        return isRiver(fluid) ? Fluids.WATER : fluid;
    }

    /**
     * What an open pipe end draws from the block in front of it when that block is a river's water: a bucket of plain
     * water, the river left standing. Null where it is anything else, and the pipe goes on as Create has it.
     */
    public static FluidStack drawFromRiver(Object pipe, boolean simulate) {
        if (WORLD == null) return null;
        try {
            Level level = (Level) WORLD.invokeExact(pipe);
            BlockPos at = (BlockPos) OUTPUT.invokeExact(pipe);
            if (level == null || at == null || !com.jeladastudios.ftsgeology.util.Loaded.at(level, at)) return null;
            FluidState state = level.getFluidState(at);
            if (!state.isSource() || !isRiver(state.getType())) return null;
            // Where another mod runs the river's water, what it gives is what there is (see HydraulicsHooks).
            int got = fromHydraulics(level, at, simulate);
            if (got >= 0) return got == 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, got);
            return new FluidStack(Fluids.WATER, 1000);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether a pipe's open end, facing {@code at}, is buried in ground the groundwater fills: natural rock or soil,
     * a block or more under the water table as it stands now. Such an end draws from the aquifer ({@link #drawFromGround}).
     */
    public static boolean buriedInWater(net.minecraft.world.level.BlockGetter reader, BlockPos at) {
        if (!(reader instanceof net.minecraft.server.level.ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())
                || !com.jeladastudios.ftsgeology.config.GeyserConfig.SOIL_WATER.get()
                || !com.jeladastudios.ftsgeology.util.Loaded.at(level, at)) {
            return false;
        }
        if (com.jeladastudios.ftsgeology.hydrology.Aquifer.rockOf(level.getBlockState(at)) == null) return false;
        return com.jeladastudios.ftsgeology.hydrology.Aquifer.waterY(level, at.getX(), at.getZ()) >= at.getY() + 1;
    }

    /** What each buried end may still draw, in millibuckets, and the tick it was last asked, by where it faces. */
    private static final java.util.Map<Long, double[]> OWED = new java.util.HashMap<>();

    /**
     * What an open pipe end buried in saturated ground draws: a bucket of water at a time, as fast as the rock round it
     * gives water up with the water standing over it, taken from the aquifer as a well's is. Null where it is not so
     * buried, and the pipe goes on as Create has it.
     */
    public static FluidStack drawFromGround(Object pipe, boolean simulate) {
        if (WORLD == null) return null;
        try {
            Level level = (Level) WORLD.invokeExact(pipe);
            BlockPos at = (BlockPos) OUTPUT.invokeExact(pipe);
            if (!(level instanceof net.minecraft.server.level.ServerLevel server) || at == null || !buriedInWater(server, at)) return null;
            var rock = com.jeladastudios.ftsgeology.hydrology.Aquifer.rockAt(server, at);
            double head = com.jeladastudios.ftsgeology.hydrology.Aquifer.waterY(server, at.getX(), at.getZ()) - at.getY();
            double perTick = com.jeladastudios.ftsgeology.hydrology.Aquifer.millibucketsPerTick(
                    com.jeladastudios.ftsgeology.hydrology.Aquifer.capacity(rock, head));
            long now = server.getGameTime();
            double[] owed = OWED.computeIfAbsent(at.asLong(), k -> new double[]{0, now});
            owed[0] = Math.min(owed[0] + perTick * Math.max(0, now - owed[1]), 2000);
            owed[1] = now;
            if (owed[0] < 1000) return FluidStack.EMPTY;
            if (!simulate) {
                owed[0] -= 1000;
                com.jeladastudios.ftsgeology.hydrology.Aquifer.draw(server, at, 1.0);
            }
            return new FluidStack(Fluids.WATER, 1000);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Millibuckets a registered hydraulics gives up out of a river block for a whole bucket asked, or -1 where none runs
     * that chunk's water and the river is drawn on as before.
     */
    public static int fromHydraulics(Level level, BlockPos at, boolean simulate) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)) return -1;
        com.jeladastudios.ftsgeology.api.Hydraulics h = com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.of(server);
        if (h == null || !com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.owns(server, at.getX() >> 4, at.getZ() >> 4)) return -1;
        try {
            return Math.max(0, Math.min(1000, h.drain(server, at.immutable(), 1000, simulate)));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static java.lang.reflect.Method behaviourWorld;
    private static boolean behaviourLooked;

    /**
     * What a hose pulley draws from a river another mod runs: a whole block's worth from the hydraulics, or nothing; -1
     * where its hose does not end in such a river, and the pulley goes on as Create has it. Only a whole block is taken,
     * so nothing is lost to a pull that falls short.
     */
    public static int pulleyFromHydraulics(Object behaviour, BlockPos root, boolean simulate) {
        try {
            if (!behaviourLooked) {
                behaviourLooked = true;
                behaviourWorld = behaviour.getClass().getMethod("getWorld");
            }
            if (behaviourWorld == null || root == null) return -1;
            Object w = behaviourWorld.invoke(behaviour);
            if (!(w instanceof net.minecraft.server.level.ServerLevel level)) return -1;
            BlockPos at = root;
            if (!isRiver(level.getFluidState(at).getType())) {
                at = root.below();
                if (!isRiver(level.getFluidState(at).getType())) return -1;
            }
            int can = fromHydraulics(level, at, true);
            if (can < 0) return -1;
            if (can < 1000) return 0;
            return simulate ? 1000 : Math.max(0, fromHydraulics(level, at, false));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /** The server is going down: what buried ends were owed goes with it. */
    public static void clear() {
        OWED.clear();
    }
}
