package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.gas.block.entity.GasValveBlockEntity;
import com.jeladastudios.ftsgeology.gas.registry.GasBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * A gas burner as the firebox of Create's steam boiler (Create is MIT): a lit gas valve standing under a boiler's fluid
 * tank and facing up into it heats it as a blaze burner does, a fierce flame as a superheated one (see
 * {@link GasValveBlockEntity#boilerHeat}). Gas is the real fuel of a boiler and a steam engine; here it drives Create's.
 *
 * <p>Create is not a dependency: its public heater registry is reached by name and given a proxy for the valve, once,
 * where Create is installed.</p>
 */
public final class CreateBoiler {

    private CreateBoiler() {}

    private static final ResourceLocation TANK = new ResourceLocation("create", "fluid_tank");

    /** Puts the gas valve in Create's registry of boiler heaters. */
    public static void register() {
        if (!ModList.get().isLoaded("create")) return;
        try {
            Class<?> heater = Class.forName("com.simibubi.create.api.boiler.BoilerHeater");
            Object registry = heater.getField("REGISTRY").get(null);
            InvocationHandler handler = (proxy, m, args) -> switch (m.getName()) {
                case "getHeat" -> args[0] instanceof Level level && level.getBlockEntity((BlockPos) args[1]) instanceof GasValveBlockEntity valve
                        ? valve.boilerHeat() : -1f;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "gas burner";
                default -> m.isDefault() ? InvocationHandler.invokeDefault(proxy, m, args) : null;
            };
            Object burner = Proxy.newProxyInstance(heater.getClassLoader(), new Class<?>[]{heater}, handler);
            Method register = registry.getClass().getMethod("register", Object.class, Object.class);
            register.setAccessible(true);
            register.invoke(registry, GasBlocks.GAS_VALVE.get(), burner);
            GeysersMod.LOGGER.info("Gas burners heat Create's boilers");
        } catch (ReflectiveOperationException | RuntimeException e) {
            GeysersMod.LOGGER.warn("Create's boiler heaters not found as expected; gas burners will not heat its boilers: {}", e.toString());
        }
    }

    /** Whether a block is one of Create's fluid tanks, which a boiler is built from. */
    public static boolean isTank(Level level, BlockPos pos) {
        if (!ModList.get().isLoaded("create")) return false;
        return TANK.equals(ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock()));
    }

    /** The heat Create reads off a block, as it reads it under a boiler: -1 none. For checking it from a command. */
    public static float heatAt(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        if (!ModList.get().isLoaded("create")) return -1;
        try {
            Class<?> heater = Class.forName("com.simibubi.create.api.boiler.BoilerHeater");
            return (Float) heater.getMethod("findHeat", Level.class, BlockPos.class, net.minecraft.world.level.block.state.BlockState.class)
                    .invoke(null, level, pos, level.getBlockState(pos));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -2;
        }
    }

    /** The heat under a boiler changed: the tank is told, and works out its boiler again. */
    public static void heatChanged(Level level, BlockPos tank) {
        if (!isTank(level, tank)) return;
        BlockPos below = tank.below();
        level.neighborChanged(tank, level.getBlockState(below).getBlock(), below);
    }
}
