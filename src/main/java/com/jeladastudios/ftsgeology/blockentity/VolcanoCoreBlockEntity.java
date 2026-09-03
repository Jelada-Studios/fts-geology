package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.volcano.VolcanoEruption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The brain of a volcano. A long, simple cycle:
 * <ul>
 *   <li><b>DORMANT</b> — quiet for 10-30 min (config), just the odd wisp of smoke.</li>
 *   <li><b>RUMBLING</b> — a ~30s warning: thick black smoke and a low rumble.</li>
 *   <li><b>ERUPTING</b> — ~1 min of lava fountaining from the crater, spilling down the slopes,
 *       hurling volcanic bombs, then it deepens the crater and goes back to sleep.</li>
 * </ul>
 * It only counts down while it actually has lava to draw on (its crater / magma chamber), so
 * mining the lava out puts it back to sleep.
 */
public class VolcanoCoreBlockEntity extends BlockEntity {

    public enum Phase { DORMANT, RUMBLING, ERUPTING }

    private Phase phase = Phase.DORMANT;
    private int timer = 200;          // ticks remaining in the current phase
    private int eruptionTicks = 0;
    private int magnitude = 12;
    /** Lava cells poured so far this eruption, so a flow is a tongue and not a flood. */
    private int spilled = 0;
    /** Surface lava outlets across the mountainside: they smoke idle and seep lava mid-eruption. */
    private long[] surfaceVents = new long[0];
    /** Cells that must stay lava between eruptions; see setMoltenCells. */
    private long[] moltenCells = new long[0];
    private int craterR = 3;

    public VolcanoCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.VOLCANO_CORE.get(), pos, state);
    }

    public void setMagnitude(int m) {
        this.magnitude = Mth.clamp(m, 4, 40);
        setChanged();
    }

    public void setCraterRadius(int r) { this.craterR = Math.max(1, r); setChanged(); }

    public void setSurfaceVents(List<BlockPos> vents) {
        long[] arr = new long[vents.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = vents.get(i).asLong();
        this.surfaceVents = arr;
        setChanged();
    }

    /**
     * The cells that are meant to be lava between eruptions - the summit pool, a caldera's lake, a
     * fissure's ponds.
     *
     * <p>Kept as an explicit list because the cooling sweep used to protect them with a radius, and
     * a radius is the wrong shape for a crescent or a line of ponds: most of the lava a volcano was
     * built with got turned to basalt after its first eruption and was never refilled.</p>
     */
    public void setMoltenCells(List<BlockPos> cells) {
        long[] arr = new long[cells.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = cells.get(i).asLong();
        this.moltenCells = arr;
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, VolcanoCoreBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        BlockPos summit = pos.above(); // the crater vent sits just above the core

        // Per-tick spectacle.
        if (be.phase == Phase.RUMBLING) {
            VolcanoEruption.rumble(server, summit, be.magnitude, server.getGameTime());
        } else if (be.phase == Phase.ERUPTING) {
            be.eruptionTicks++;
            VolcanoEruption.tickEruption(server, summit, be.magnitude, be.eruptionTicks);
        }

        if (server.getGameTime() % 20L != 0L) return; // the cycle ticks once a second

        switch (be.phase) {
            case DORMANT -> {
                if (!hasLava(server, pos)) return; // dead until it has lava again
                be.idleSmoke(server, summit, 0.4f, true); // lazy smoke off the crater + a vent or two
                if ((be.timer -= 20) <= 0) {
                    be.phase = Phase.RUMBLING;
                    be.timer = GeyserConfig.VOLCANO_RUMBLE_TICKS.get();
                }
            }
            case RUMBLING -> {
                be.idleSmoke(server, summit, 1.0f, true); // building: heavier smoke from pool + all vents
                if ((be.timer -= 20) <= 0) {
                    be.phase = Phase.ERUPTING;
                    be.eruptionTicks = 0;
                    be.spilled = 0;
                    be.timer = GeyserConfig.VOLCANO_ERUPT_TICKS.get();
                }
            }
            case ERUPTING -> {
                // The mountainside smokes hardest while it is actually erupting. It used to smoke
                // only when DORMANT and RUMBLING: at the one moment the flank outlets are pouring
                // lava they were completely silent, so the vents ran dry-looking while the summit
                // had the whole show to itself.
                be.idleSmoke(server, summit, 1.0f, false);
                // Well lava up the crater so it spills down the mountain - but only so much of it.
                // A real flow chills against the ground and stops; without a budget the mountain
                // simply kept pouring until the whole flank was molten.
                if (be.spilled < GeyserConfig.VOLCANO_LAVA_BUDGET.get()
                        && VolcanoEruption.spillLava(server, summit)) {
                    be.spilled++;
                }
                // Flows petrify as they advance rather than waiting for the eruption to end, so
                // what you watch is a lava tongue turning to rock behind its own front.
                if (be.eruptionTicks % 60 == 0) {
                    VolcanoEruption.coolScatteredLava(server, summit, be.craterR,
                            20 + be.magnitude, be.surfaceVents, be.moltenCells);
                }
                // The outlets trickle gently while it erupts — one random outlet each second.
                if (be.surfaceVents.length > 0) {
                    VolcanoEruption.seepVent(server,
                            BlockPos.of(be.surfaceVents[server.random.nextInt(be.surfaceVents.length)]).above());
                }
                if ((be.timer -= 20) <= 0) {
                    VolcanoEruption.formCrater(server, summit, be.craterR, be.moltenCells);
                    // Everything spilled OUTSIDE the crater cools to basalt/tuff; the crater lake
                    // stays molten, and the outlet trickles cool too.
                    VolcanoEruption.coolScatteredLava(server, summit, be.craterR, 20 + be.magnitude,
                            be.surfaceVents, be.moltenCells);
                    for (long v : be.surfaceVents) {
                        VolcanoEruption.dryVent(server, BlockPos.of(v).above());
                    }
                    be.phase = Phase.DORMANT;
                    be.timer = dormantRoll(server);
                }
            }
        }
        be.setChanged();
    }

    /**
     * Black smoke off the crater lava lake itself and off the surface vents; intensity 0..1.
     *
     * @param deposit whether the vents also lay down sulfur. Only while the volcano is quiet:
     *                sulfur is a fumarole product, laid by gas escaping through a cool opening. An
     *                erupting vent is pouring lava, not fuming - and at one attempt per vent per
     *                second, a twenty-minute eruption across nineteen outlets would have painted
     *                the whole mountain yellow.
     */
    private void idleSmoke(ServerLevel level, BlockPos summit, float intensity, boolean deposit) {
        // A few random samples of the crater lava pool.
        int puffs = 1 + Math.round(intensity * 3);
        for (int i = 0; i < puffs; i++) {
            int dx = level.random.nextInt(craterR * 2 + 1) - craterR;
            int dz = level.random.nextInt(craterR * 2 + 1) - craterR;
            BlockPos p = new BlockPos(summit.getX() + dx, summit.getY(), summit.getZ() + dz);
            if (level.getBlockState(p).getFluidState().is(FluidTags.LAVA)) {
                VolcanoEruption.smokeAt(level, p);
            }
        }
        // Smoke from the mountainside vents (all when rumbling, a random one when dormant).
        if (intensity >= 1.0f) {
            for (long v : surfaceVents) {
                BlockPos vp = BlockPos.of(v);
                VolcanoEruption.smokeAt(level, vp);
                if (deposit) {
                    com.jeladastudios.ftsgeology.eruption.SulfurDeposits.depositAround(level, vp.above());
                }
            }
        } else if (surfaceVents.length > 0 && level.random.nextInt(3) == 0) {
            BlockPos v = BlockPos.of(surfaceVents[level.random.nextInt(surfaceVents.length)]);
            VolcanoEruption.smokeAt(level, v);
            // Escaping gas oxidises at the opening and leaves a yellow sulfur crust behind.
            if (deposit) {
                com.jeladastudios.ftsgeology.eruption.SulfurDeposits.depositAround(level, v.above());
            }
        }
    }

    private static int dormantRoll(ServerLevel level) {
        int min = GeyserConfig.VOLCANO_DORMANT_MIN_TICKS.get();
        int max = GeyserConfig.VOLCANO_DORMANT_MAX_TICKS.get();
        return min + level.random.nextInt(Math.max(1, max - min));
    }

    /** Is there lava adjacent to (or just above) the core to feed the eruption? */
    private static boolean hasLava(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos.above()).getFluidState().is(FluidTags.LAVA)) return true;
        for (Direction d : Direction.values()) {
            if (level.getBlockState(pos.relative(d)).getFluidState().is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Phase", phase.ordinal());
        tag.putInt("Timer", timer);
        tag.putInt("EruptionTicks", eruptionTicks);
        tag.putInt("Magnitude", magnitude);
        tag.putInt("CraterR", craterR);
        if (surfaceVents.length > 0) tag.putLongArray("SurfaceVents", surfaceVents);
        if (moltenCells.length > 0) tag.putLongArray("MoltenCells", moltenCells);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        phase = Phase.values()[Math.floorMod(tag.getInt("Phase"), Phase.values().length)];
        timer = tag.getInt("Timer");
        eruptionTicks = tag.getInt("EruptionTicks");
        magnitude = tag.contains("Magnitude") ? tag.getInt("Magnitude") : 12;
        craterR = tag.contains("CraterR") ? tag.getInt("CraterR") : 3;
        surfaceVents = tag.contains("SurfaceVents") ? tag.getLongArray("SurfaceVents") : new long[0];
        moltenCells = tag.contains("MoltenCells") ? tag.getLongArray("MoltenCells") : new long[0];
    }
}
