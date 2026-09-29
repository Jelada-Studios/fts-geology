package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Power to Electrodynamics' wires.
 *
 * <p>Electrodynamics' wires and machines speak their own electricity -- joules at a voltage -- through a capability of
 * Voltaic's, its library, and a wire takes nothing from a block that offers Forge Energy only: a wire laid to the
 * turbine carried nothing. Where Voltaic is installed the turbine offers that capability too, as a generator at the
 * standard voltage, and hands its energy to a wire or a machine beside it the way it hands Forge Energy to others. The
 * well pump offers it as a machine that takes power at that voltage.</p>
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
    private record Api(Capability<?> capability, Class<?> electrodynamic, Method joulesVoltage, Method joules,
                       Method voltage, Method receive, Object empty) {
        static Api find() {
            if (!ModList.get().isLoaded("voltaic")) return null;
            try {
                Class<?> caps = Class.forName("voltaic.registers.VoltaicCapabilities");
                Class<?> electrodynamic = Class.forName("voltaic.api.electricity.ICapabilityElectrodynamic");
                Class<?> pack = Class.forName("voltaic.prefab.utilities.object.TransferPack");
                Capability<?> capability = (Capability<?>) caps.getField("CAPABILITY_ELECTRODYNAMIC_BLOCK").get(null);
                return new Api(capability, electrodynamic, pack.getMethod("joulesVoltage", double.class, double.class),
                        pack.getMethod("getJoules"), pack.getMethod("getVoltage"),
                        electrodynamic.getMethod("receivePower", pack, boolean.class),
                        pack.getField("EMPTY").get(null));
            } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                GeysersMod.LOGGER.warn("Voltaic is installed but its electricity was not found; the turbine and the pump speak Forge Energy only: {}", e.toString());
                return null;
            }
        }
    }

    /** Whether a capability asked of a block is Voltaic's electricity. */
    public static boolean is(Capability<?> cap) {
        return API != null && cap == API.capability();
    }

    /**
     * A generator's side of Voltaic's electricity over a store of energy: it gives and never takes. The wires draw on it
     * through Voltaic's own defaults, which read and set the store through these.
     */
    public static LazyOptional<Object> generator(IntSupplier stored, IntSupplier capacity, IntConsumer set, Runnable changed) {
        if (API == null) return LazyOptional.empty();
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
            case "toString" -> "geothermal turbine";
            default -> m.isDefault() ? InvocationHandler.invokeDefault(proxy, m, args) : null;
        };
        Object proxy = Proxy.newProxyInstance(API.electrodynamic().getClassLoader(), new Class<?>[]{API.electrodynamic()}, handler);
        return LazyOptional.of(() -> proxy);
    }

    /**
     * A machine's side of Voltaic's electricity over a store of energy: it takes, at the standard voltage or under, as much
     * as {@code room} says it has room for, and never gives. A higher voltage it refuses and hands to {@code tooHigh},
     * rather than burning out as Electrodynamics' own machines do.
     */
    public static LazyOptional<Object> receiver(IntSupplier stored, IntSupplier capacity, IntSupplier room, IntConsumer set,
                                                Runnable changed, DoubleConsumer tooHigh) {
        if (API == null) return LazyOptional.empty();
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
            case "toString" -> "well pump";
            default -> m.isDefault() ? InvocationHandler.invokeDefault(proxy, m, args) : null;
        };
        Object proxy = Proxy.newProxyInstance(API.electrodynamic().getClassLoader(), new Class<?>[]{API.electrodynamic()}, handler);
        return LazyOptional.of(() -> proxy);
    }

    /**
     * Hands up to {@code offered} joules to Voltaic's electricity on a block's side, if it has it there: a wire, or a
     * machine that takes power. Returns the joules taken, or -1 where the block has no such side, so the caller offers
     * it Forge Energy instead.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int give(BlockEntity other, Direction side, int offered) {
        if (API == null) return -1;
        Object into = other.getCapability((Capability) API.capability(), side).resolve().orElse(null);
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
