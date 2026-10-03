package com.jeladastudios.ftsgeology.gas.block.entity;

import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasFurnaces;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.GasText;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * A gas space heater, a room's own fire: it burns methane, biogas or hydrogen from a pipe or a tank beside it at a few
 * kilowatts, with the air of the room it faces. While it burns it is a source of warmth (Tough As Nails counts it as a
 * campfire). What it burns to goes up its flue where a pipe is fitted on top, or else into the room: water from
 * hydrogen, water and carbon dioxide from methane, a room shut on every side filling with them as with any flueless
 * heater. Like a real one it shuts itself off when the room's air runs short of oxygen, before the flame turns sooty
 * and gives off carbon monoxide.
 * <ul>
 *   <li>Front: the room. Air in, heat out.</li>
 *   <li>Back, sides, bottom: fuel in, from pipes or a tank.</li>
 *   <li>Top: the flue, into a pipe if one is fitted.</li>
 * </ul>
 * A redstone signal turns it off.
 */
public class GasHeaterBlockEntity extends GasMachineBlockEntity {
    /** What it gives off while it burns, kW: a room heater's. */
    public static final double POWER = 3.0;
    /** Ticks between burns; each burns that many ticks' fuel. */
    private static final int EVERY = 10;
    /** Oxygen share of the room's air under which it shuts off, as an oxygen depletion sensor does. */
    public static final double LEAST_OXYGEN = 0.18;

    /**
     * Room air it draws in at a time, mol: half a minute's burning. Its air goes in and its fumes come out in breaths
     * this size, not a little every half second: a room's gas only keeps a change bigger than a trace, and a closed
     * room has to fill with what the heater gives off and run short of oxygen.
     */
    private static final double DRAW = 1.0;

    private final GasTank fuel = new GasTank(0.2, 10).onChange(this::setChanged);
    private final GasTank flue = new GasTank(0.1, 5).onChange(this::setChanged);
    /** The air drawn in and what it has burnt to, until it is let out. */
    private final GasMix draft = new GasMix();
    /** Why it is out, a key under the block's name, or empty while it burns. */
    private String out = "fuel";
    private double lastMol;
    private Gas lastFuel;

    public GasHeaterBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.GAS_HEATER.get(), pos, state);
    }

    @Override
    public @Nullable GasTank tankFor(@Nullable Direction side) {
        if (side == null) return fuel;
        if (side == facing()) return null;
        if (side == Direction.UP) return flue;
        return fuel;
    }

    @Override
    public List<Port> ports() {
        return List.of(new Port("fuel", null, true, backSidesBottom(), fuel),
                new Port("room", null, true, front(), null),
                new Port("flue", null, false, List.of(Direction.UP), flue));
    }

    @Override
    protected @Nullable String idle() {
        return out.isEmpty() ? null : out;
    }

    @Override
    public void serverTick() {
        boolean fitted = ventIfUnconnected(flue, Direction.UP, 1.05);
        if (level.getGameTime() % EVERY != 0) return;
        lastMol = 0;
        if (level.hasNeighborSignal(worldPosition)) {
            stop("redstone");
            return;
        }
        Direction front = facing();
        if (!worldOpen(front)) {
            stop("blocked");
            return;
        }
        double kj = POWER * EVERY / 20.0;
        double want = GasFurnaces.molesFor(fuel.gas, kj);
        if (want <= 0 || fuel.total() < want || !Combustion.canSustainJet(fuel.gas)) {
            stop("fuel");
            return;
        }
        BlockPos room = worldPosition.relative(front);
        if (gas().sample(room).fraction(Gas.OXYGEN) < LEAST_OXYGEN) {
            stop("air");
            return;
        }
        double o2 = want * Combustion.o2Required(fuel.gas) / fuel.total();
        if (draft.m[Gas.O2] < o2 * 1.05) {
            // Short of oxygen: out with the fumes, in with a breath of the room.
            exhale(room, fitted);
            GasMix air = gas().getOrCreateCell(room);
            if (air == null) {
                stop("air");
                return;
            }
            draft.add(gas().extract(room, DRAW));
        }
        lastFuel = mainFuel(fuel.gas);
        GasMix burnt = fuel.extract(want);
        Combustion.burnWithAir(burnt, draft, draft.total());
        draft.add(burnt);
        lastMol = want;
        out = "";
        setLit(true);
        if (level instanceof ServerLevel server && server.random.nextInt(4) == 0) {
            server.sendParticles(ParticleTypes.SMALL_FLAME, room.getX() + 0.5 - front.getStepX() * 0.45,
                    worldPosition.getY() + 0.35, room.getZ() + 0.5 - front.getStepZ() * 0.45, 1, 0.12, 0.05, 0.12, 0.0);
        }
    }

    private void stop(String why) {
        out = why;
        setLit(false);
        if (!draft.isEmpty() && worldOpen(facing())) exhale(worldPosition.relative(facing()), hasFlue());
    }

    /** Lets out what it drew in and burnt: up the flue where one is fitted and has room, else into the room. */
    private void exhale(BlockPos room, boolean fitted) {
        if (draft.isEmpty()) return;
        if (fitted && flue.room() >= draft.total()) flue.insert(draft);
        else gas().release(room, draft);
        draft.clear();
        setChanged();
    }

    private boolean hasFlue() {
        var h = com.jeladastudios.ftsgeology.gas.registry.GasCapabilities.handlerAt(level, worldPosition.above(), Direction.DOWN);
        return h != null && h.getTank(Direction.DOWN) != null;
    }

    /** The fuel the stream holds most of, for the panel. */
    private static @Nullable Gas mainFuel(GasMix mix) {
        Gas best = null;
        double most = 0;
        for (Gas g : Gas.FUELS) {
            double m = mix.get(g);
            if (m > most) {
                most = m;
                best = g;
            }
        }
        return best;
    }

    @Override
    public List<Component> status() {
        List<Component> o = super.status();
        if (out.isEmpty()) {
            o.add(Component.translatable("block.fts_geology.gas_heater.burning",
                    String.format(Locale.ROOT, "%.1f", POWER),
                    lastFuel == null ? "-" : lastFuel.formula,
                    String.format(Locale.ROOT, "%.4f", lastMol * 20.0 / EVERY)).withStyle(ChatFormatting.GOLD));
        }
        o.add(Component.translatable("block.fts_geology.gas_heater.help").withStyle(ChatFormatting.DARK_GRAY));
        return o;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("fuel", fuel.save());
        tag.put("flue", flue.save());
        tag.put("draft", draft.save());
        tag.putString("out", out);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        fuel.load(tag.getCompound("fuel"));
        flue.load(tag.getCompound("flue"));
        draft.clear();
        draft.add(GasMix.load(tag.getCompound("draft")));
        if (tag.contains("out")) out = tag.getString("out");
    }

    @Override
    public void onRemoved() {
        for (GasTank t : new GasTank[]{fuel, flue}) {
            if (!t.gas.isEmpty()) gas().release(worldPosition, t.gas);
        }
        if (!draft.isEmpty()) gas().release(worldPosition, draft);
    }
}
