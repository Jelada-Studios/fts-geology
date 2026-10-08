package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiFunction;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Power to Electrodynamics' wires.
 *
 * <p>Electrodynamics' wires and machines speak their own electricity -- joules at a voltage -- through a block capability
 * of Voltaic's, its library, and a wire takes nothing from a block that offers Forge Energy only. Where Voltaic is
 * installed the turbine and the thermoelectric generator offer that capability too, as generators at the standard
 * voltage, and hand their energy to a wire or a machine beside them the way they hand Forge Energy to others. The well
 * pump and the gas machines offer it as machines that take power at that voltage.</p>
 *
 * <p>Voltaic is not a dependency: its capability, its interface and its packet of power are looked up by name once,
 * the interface is answered with a proxy, and without Voltaic none of this is ever called.</p>
 */
public final class ElectrodynamicsPower {

    private ElectrodynamicsPower() {}

    /** The voltage Electrodynamics' own low-voltage machines run at, and the one it counts Forge Energy devices at. */
    private static final double VOLTS = 120.0;

    private static final Api API = Api.find();

    /** Voltaic's pieces, found by name, or null where Voltaic is not installed or not as expected. */
    private record Api(BlockCapability<?, Direction> capability, Class<?> electrodynamic, Method joulesVoltage, Method joules,
                       Method voltage, Method receive, Object empty) {
        @SuppressWarnings("unchecked")
        static Api find() {
            if (!ModList.get().isLoaded("voltaic")) return null;
            try {
                Class<?> caps = Class.forName("voltaic.registers.VoltaicCapabilities");
                Class<?> electrodynamic = Class.forName("voltaic.api.electricity.ICapabilityElectrodynamic");
                Class<?> pack = Class.forName("voltaic.prefab.utilities.object.TransferPack");
                BlockCapability<?, Direction> capability =
                        (BlockCapability<?, Direction>) caps.getField("CAPABILITY_ELECTRODYNAMIC_BLOCK").get(null);
                return new Api(capability, electrodynamic, pack.getMethod("joulesVoltage", double.class, double.class),
                        pack.getMethod("getJoules"), pack.getMethod("getVoltage"),
                        electrodynamic.getMethod("receivePower", pack, boolean.class),
                        pack.getField("EMPTY").get(null));
            } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                GeysersMod.LOGGER.warn("Voltaic is installed but its electricity was not found; the machines speak Forge Energy only: {}", e.toString());
                return null;
            }
        }
    }

    /**
     * Offers Voltaic's electricity on a block entity type's sides, as {@code provider} gives it for a side (null where
     * that side has none). Nothing where Voltaic is not installed.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T extends BlockEntity> void register(RegisterCapabilitiesEvent event, BlockEntityType<T> type,
                                                        BiFunction<T, Direction, Object> provider) {
        if (API == null) return;
        event.registerBlockEntity((BlockCapability) API.capability(), type, (be, side) -> provider.apply((T) be, (Direction) side));
    }

    /**
     * A generator's side of Voltaic's electricity over a store of energy: it gives and never takes. The wires draw on it
     * through Voltaic's own defaults, which read and set the store through these. Null where Voltaic is not installed.
     */
    @Nullable
    public static Object generator(IntSupplier stored, IntSupplier capacity, IntConsumer set, Runnable changed) {
        if (API == null) return null;
        InvocationHandler handler = (proxy, m, args) -> switch (m.getName()) {
            case "getJoulesStored" -> (double) stored.getAsInt();
            case "getMaxJoulesStored" -> (double) capacity.getAsInt();
            case "setJoulesStored" -> {
                set.accept((int) Math.max(0, Math.floor((Double) args[0])));
                yield null;
            }
            case "isEnergyReceiver" -> false;
            case "isEnergyProducer" -> true;
            case "onChange" -> {
                changed.run();
                yield null;
            }
            case "getVoltage", "getMinimumVoltage", "getMaximumVoltage" -> VOLTS;
            case "receivePower", "getConnectedLoad" -> API.empty();
            case "overVoltage" -> null;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "geothermal generator";
            default -> m.isDefault() ? InvocationHandler.invokeDefault(proxy, m, args) : null;
        };
        return Proxy.newProxyInstance(API.electrodynamic().getClassLoader(), new Class<?>[]{API.electrodynamic()}, handler);
    }

    /**
     * A machine's side of Voltaic's electricity over a store of energy: it takes, at the standard voltage or under, as much
     * as {@code room} says it has room for, and never gives. A higher voltage it refuses and hands to {@code tooHigh},
     * rather than burning out as Electrodynamics' own machines do. Null where Voltaic is not installed.
     */
    @Nullable
    public static Object receiver(IntSupplier stored, IntSupplier capacity, IntSupplier room, IntConsumer set,
                                  Runnable changed, DoubleConsumer tooHigh) {
        if (API == null) return null;
        InvocationHandler handler = (proxy, m, args) -> switch (m.getName()) {
            case "getJoulesStored" -> (double) stored.getAsInt();
            case "getMaxJoulesStored" -> (double) capacity.getAsInt();
            case "setJoulesStored" -> {
                set.accept((int) Math.max(0, Math.floor((Double) args[0])));
                yield null;
            }
            case "isEnergyReceiver" -> true;
            case "isEnergyProducer" -> false;
            case "onChange" -> {
                changed.run();
                yield null;
            }
            case "getVoltage", "getMinimumVoltage", "getMaximumVoltage" -> VOLTS;
            case "receivePower" -> {
                double joules = (Double) API.joules().invoke(args[0]), volts = (Double) API.voltage().invoke(args[0]);
                if (joules <= 0) yield API.empty();
                if (volts > VOLTS + 1e-6) {
                    tooHigh.accept(volts);
                    yield API.empty();
                }
                int take = (int) Math.min(room.getAsInt(), Math.floor(joules));
                if (take <= 0) yield API.empty();
                if (!(Boolean) args[1]) {
                    set.accept(stored.getAsInt() + take);
                    changed.run();
                }
                yield API.joulesVoltage().invoke(null, (double) take, volts);
            }
            case "getConnectedLoad" -> API.joulesVoltage().invoke(null, (double) Math.max(0, room.getAsInt()), VOLTS);
            case "extractPower" -> API.empty();
            case "overVoltage" -> {
                tooHigh.accept((Double) API.voltage().invoke(args[0]));
                yield null;
            }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "powered machine";
            default -> m.isDefault() ? InvocationHandler.invokeDefault(proxy, m, args) : null;
        };
        return Proxy.newProxyInstance(API.electrodynamic().getClassLoader(), new Class<?>[]{API.electrodynamic()}, handler);
    }

    /**
     * Hands up to {@code offered} joules to Voltaic's electricity on a block's side, if it has it there: a wire, or a
     * machine that takes power. Returns the joules taken, or -1 where the block has no such side, so the caller offers
     * it Forge Energy instead.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int give(BlockEntity other, Direction side, int offered) {
        if (API == null || other.getLevel() == null) return -1;
        Object into = other.getLevel().getCapability((BlockCapability) API.capability(), other.getBlockPos(), side);
        if (into == null) return -1;
        try {
            Object pack = API.joulesVoltage().invoke(null, (double) offered, VOLTS);
            Object took = API.receive().invoke(into, pack, false);
            double joules = took == null ? 0.0 : (Double) API.joules().invoke(took);
            return (int) Math.max(0, Math.min(offered, Math.floor(joules)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }
}
