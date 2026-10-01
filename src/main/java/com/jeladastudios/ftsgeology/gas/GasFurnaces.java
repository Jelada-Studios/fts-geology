package com.jeladastudios.ftsgeology.gas;

import com.jeladastudios.ftsgeology.gas.world.GasManager;
import com.jeladastudios.ftsgeology.mixin.AbstractFurnaceAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;

/**
 * Gas burnt under a furnace, a smoker or a blast furnace: from a lit burner (a gas valve) facing it, or from a gas pipe
 * run straight into it, its own burner, as a gas oven has. While it has something to cook it burns as much fuel as gives
 * the heat a furnace's fire does -- a hundred kilowatts, twice that for the smoker and the blast furnace, which cook
 * twice as fast -- with air from round it, and the fumes leave by its top or a side. No coal goes in, and with nothing to
 * cook no gas is spent. Methane, biogas and hydrogen all do: hydrogen gives less heat a mole, so more of it goes.
 */
public final class GasFurnaces {

    private GasFurnaces() {}

    /** Heat a furnace takes while it cooks, kJ a tick. */
    static final double HEAT = 5.0;

    /** The sides air comes in by and the fumes go out by: the top first, as a flue. */
    private static final Direction[] FLUE = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    /** Heat this furnace takes while cooking, kJ a tick. */
    public static double demand(AbstractFurnaceBlockEntity furnace) {
        return furnace instanceof BlastFurnaceBlockEntity || furnace instanceof SmokerBlockEntity ? HEAT * 2 : HEAT;
    }

    /** Whether the furnace has something to cook now: an input its kind of cooking takes, and room for what comes out. */
    public static boolean wantsHeat(Level level, AbstractFurnaceBlockEntity furnace) {
        ItemStack in = furnace.getItem(0);
        if (in.isEmpty()) return false;
        var recipe = ((AbstractFurnaceAccessor) furnace).fts_geology$quickCheck().getRecipeFor(furnace, level);
        if (recipe.isEmpty()) return false;
        AbstractCookingRecipe r = recipe.get();
        ItemStack result = r.assemble(furnace, level.registryAccess());
        if (result.isEmpty()) return false;
        ItemStack out = furnace.getItem(2);
        if (out.isEmpty()) return true;
        if (!ItemStack.isSameItemSameTags(out, result)) return false;
        return out.getCount() + result.getCount() <= Math.min(out.getMaxStackSize(), furnace.getMaxStackSize());
    }

    /** Furnaces already given their heat this tick: two burners on one furnace do not double it. */
    private static final java.util.Map<BlockPos, Long> FED = new java.util.HashMap<>();

    /** Whether this furnace still wants its heat this tick; it is counted as given from here. */
    public static boolean takeTurn(Level level, BlockPos pos) {
        long now = level.getGameTime();
        Long was = FED.put(pos.immutable(), now);
        if (FED.size() > 256) FED.values().removeIf(t -> t < now - 1);
        return was == null || was != now;
    }

    /** Moles of a stream like this one that give so many kJ burnt; 0 where it holds no fuel. */
    public static double molesFor(GasMix stream, double kj) {
        double total = stream.total();
        if (total <= 1e-9) return 0;
        double perMole = Combustion.heatContent(stream) / total;
        return perMole > 1e-9 ? kj / perMole : 0;
    }

    /**
     * Burns {@code fuel} under the furnace for one tick with air from round it, the fumes let out there; keeps its fire
     * going where enough heat came out. Returns the heat released, kJ, or 0 where it has no air (shut in on every side).
     */
    public static double burn(ServerLevel level, BlockPos pos, AbstractFurnaceBlockEntity furnace, GasMix fuel) {
        GasManager gm = GasManager.get(level);
        BlockPos flue = null;
        double heat = 0;
        // Air drawn in by every open side, as a fire draws it, the fumes up the first (the top, as a chimney): one cell's
        // air alone ran short of oxygen in a minute and the fire went by fits.
        for (Direction d : FLUE) {
            BlockPos p = pos.relative(d);
            if (!gm.isLoaded(p) || gm.isGasTight(level.getBlockState(p), p)) continue;
            GasMix air = gm.getOrCreateCell(p);
            if (air == null) continue;
            if (flue == null) flue = p;
            heat += Combustion.burnWithAir(fuel, air, air.total() * 0.25);
            gm.markDirty(p);
            if (Combustion.fuelMoles(fuel) < 1e-6) break;
        }
        if (flue == null) return 0;
        if (!fuel.isEmpty()) gm.release(flue, fuel);
        if (heat >= demand(furnace) * 0.5) keepLit(furnace);
        return heat;
    }

    /** The furnace's fire kept going a few ticks more, its flame drawn full: no fuel item is burnt while it is. */
    private static void keepLit(AbstractFurnaceBlockEntity furnace) {
        AbstractFurnaceAccessor f = (AbstractFurnaceAccessor) furnace;
        if (f.fts_geology$litTime() < 3) f.fts_geology$setLitTime(3);
        f.fts_geology$setLitDuration(3);
        furnace.setChanged();
    }
}
