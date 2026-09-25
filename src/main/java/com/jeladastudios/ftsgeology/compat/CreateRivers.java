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
    public static FluidStack drawFromRiver(Object pipe) {
        if (WORLD == null) return null;
        try {
            Level level = (Level) WORLD.invokeExact(pipe);
            BlockPos at = (BlockPos) OUTPUT.invokeExact(pipe);
            if (level == null || at == null || !level.isLoaded(at)) return null;
            FluidState state = level.getFluidState(at);
            if (!state.isSource() || !isRiver(state.getType())) return null;
            return new FluidStack(Fluids.WATER, 1000);
        } catch (Throwable t) {
            return null;
        }
    }
}
