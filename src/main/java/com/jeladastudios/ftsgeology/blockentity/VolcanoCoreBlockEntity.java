package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.volcano.VolcanoEruption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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
    /** Flank fumarole chimneys, so an eruption can blow filthy smoke out of them. */
    private long[] fumaroles = new long[0];
    /** Cells that must stay lava between eruptions; see setMoltenCells. */
    private long[] moltenCells = new long[0];
    private int craterR = 3;

    /**
     * A dormant volcano: its crater crusted over and its vent plugged. The crater cells turn to lava when it wakes and
     * crust over again when the eruption ends; between times it sleeps far longer than a live one.
     */
    private boolean sealed;
    private long[] sealCells = new long[0];

    /** Seals the core of a dormant volcano over these crater cells and puts it to sleep. */
    public void setSealed(List<BlockPos> cells, ServerLevel level) {
        sealed = true;
        sealCells = new long[cells.size()];
        for (int i = 0; i < sealCells.length; i++) sealCells[i] = cells.get(i).asLong();
        timer = sealedRoll(level);
        setChanged();
    }

    private static int sealedRoll(ServerLevel level) {
        return (int) Math.min(Integer.MAX_VALUE / 2, dormantRoll(level) * GeyserConfig.DORMANT_VOLCANO_QUIET_FACTOR.get());
    }

    /** Wakes a sealed volcano: the plug over its vent goes and its crater fills with lava. */
    private void unseal(ServerLevel level, BlockPos pos) {
        BlockPos plug = pos.above(2);
        BlockState ps = level.getBlockState(plug);
        if (!ps.is(Blocks.BEDROCK) && !ps.hasBlockEntity() && !EruptionHandler.isPlayerPlaced(ps)) {
            level.setBlock(plug, Blocks.AIR.defaultBlockState(), 3);
        }
        for (long cell : sealCells) {
            BlockPos p = BlockPos.of(cell);
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.BEDROCK) || s.hasBlockEntity() || EruptionHandler.isPlayerPlaced(s)) continue;
            level.setBlock(p, Blocks.LAVA.defaultBlockState(), 3);
        }
        com.jeladastudios.ftsgeology.util.Diagnostics.info("Dormant volcano at {} wakes: its crater opens ({} cells)", pos, sealCells.length);
    }

    /** After an eruption a sealed volcano crusts over again: its crater cools and its vent is plugged. */
    private void reseal(ServerLevel level, BlockPos pos) {
        for (long cell : sealCells) {
            BlockPos p = BlockPos.of(cell);
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.getFluidState().is(FluidTags.LAVA)) {
                level.setBlock(p, com.jeladastudios.ftsgeology.registry.ModBlocks.COOLING_LAVA_CRUST.get()
                        .defaultBlockState(), 3);
            }
        }
        BlockPos plug = pos.above(2);
        BlockState ps = level.getBlockState(plug);
        if (ps.isAir() || ps.getFluidState().is(FluidTags.LAVA)) {
            level.setBlock(plug, com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.translate(level, plug, Blocks.BLACKSTONE.defaultBlockState()), 3);
        }
        com.jeladastudios.ftsgeology.util.Diagnostics.info("Dormant volcano at {} crusts over again", pos);
    }

    public VolcanoCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.VOLCANO_CORE.get(), pos, state);
    }

    public void setMagnitude(int m) {
        this.magnitude = Mth.clamp(m, 4, 40);
        setChanged();
    }

    public void setCraterRadius(int r) { this.craterR = Math.max(1, r); setChanged(); }

    /** Sequence number of the last quake this volcano has already recharged for. */
    private long rechargedFor = Long.MIN_VALUE;

    /** Sequence number of the last quake this volcano has already rolled against for an eruption. */
    private long triggeredFor = Long.MIN_VALUE;

    /**
     * A large quake nearby can set a volcano off: the shaking lets gas out of the magma, as the 1960 Chile quake did
     * at Cordón Caulle a day and a half later. Asked once per quake; if it takes, the quiet left is cut to minutes.
     */
    private void answerQuake(ServerLevel level, BlockPos pos) {
        if (!GeyserConfig.QUAKE_TRIGGERS_ERUPTIONS.get()) return;
        com.jeladastudios.ftsgeology.quake.QuakeQuiet.Trigger q =
                com.jeladastudios.ftsgeology.quake.QuakeQuiet.trigger(level, pos.getX(), pos.getZ());
        if (q == null || q.sequence() <= triggeredFor) return;
        triggeredFor = q.sequence();
        setChanged();
        double chance = GeyserConfig.QUAKE_ERUPTION_CHANCE.get()
                * Mth.clamp((q.magnitude() - 6.5) / 2.5, 0.0, 1.0)
                * Mth.clamp(1.0 - q.distance() / q.reach(), 0.0, 1.0);
        GeysersMod.LOGGER.debug("Volcano at {} felt an M{} quake {} blocks away: chance {}, quiet left {} ticks", pos,
                String.format(java.util.Locale.ROOT, "%.1f", q.magnitude()), (int) Math.round(q.distance()),
                String.format(java.util.Locale.ROOT, "%.2f", chance), timer);
        if (level.random.nextDouble() >= chance) return;
        int min = GeyserConfig.QUAKE_ERUPTION_DELAY_MIN_TICKS.get();
        int max = Math.max(min + 1, GeyserConfig.QUAKE_ERUPTION_DELAY_MAX_TICKS.get());
        int delay = min + level.random.nextInt(max - min);
        if (delay >= timer) return;
        timer = delay;
        com.jeladastudios.ftsgeology.util.Diagnostics.info("Volcano at {} woken by an M{} quake {} blocks away, erupting in {} s", pos,
                String.format(java.util.Locale.ROOT, "%.1f", q.magnitude()), (int) Math.round(q.distance()), delay / 20);
    }

    /** What this mountain was before anything happened to it, so it can be raised again. */
    private com.jeladastudios.ftsgeology.volcano.VolcanoType type;
    private BlockPos originalBase;
    private int originalSummitY = Integer.MIN_VALUE;

    private com.jeladastudios.ftsgeology.volcano.VolcanoSize size =
            com.jeladastudios.ftsgeology.volcano.VolcanoSize.SMALL;

    /**
     * NBT flag on a core world generation left in a large volcano's chamber. Such a core is a marker:
     * it finishes the summit and is then filled over. See {@code VolcanoBuilder.finishFieldVolcano}.
     */
    public static final String FIELD_PENDING = "FieldPending";
    private boolean fieldPending;

    /** Called by the builder as the core is planted. */
    public void setShape(com.jeladastudios.ftsgeology.volcano.VolcanoType t,
                         com.jeladastudios.ftsgeology.volcano.VolcanoSize s, BlockPos base, int summitY) {
        this.type = t;
        this.size = s;
        this.originalBase = base.immutable();
        this.originalSummitY = summitY;
        setChanged();
    }

    /**
     * Raises the mountain again after an earthquake flattened it, once per quake. Stamped before the
     * job is queued, since the job outlives many of these checks.
     */
    private void rebuildAfterQuake(ServerLevel level, BlockPos pos, long quake) {
        if (type == null || originalBase == null || originalSummitY == Integer.MIN_VALUE) return;
        // Is there actually a mountain missing? If the summit is still standing, leave it be.
        int here = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(
                level, originalBase.getX(), originalBase.getZ());
        if (here != Integer.MIN_VALUE && here >= originalSummitY - 2) return;

        rebuiltFor = quake;
        setChanged();
        if (com.jeladastudios.ftsgeology.volcano.VolcanoBuilder.rebuildEdifice(
                level, originalBase, magnitude, type, originalSummitY, size)) {
            com.jeladastudios.ftsgeology.util.Diagnostics.info("Volcano at {} rebuilding after a quake ({} -> {})",
                    pos, here, originalSummitY);
        }
    }

    /** Sequence number of the last quake this volcano has already rebuilt for. */
    private long rebuiltFor = Long.MIN_VALUE;

    /**
     * The shape this core was built as, from NBT, or a guess for an older save: a fissure has a tiny
     * crater and many ponds, a caldera the widest crater. Null means it will not raise itself again.
     */
    private static com.jeladastudios.ftsgeology.volcano.VolcanoType readType(CompoundTag tag) {
        if (tag.contains("Type")) {
            try {
                return com.jeladastudios.ftsgeology.volcano.VolcanoType
                        .valueOf(tag.getString("Type"));
            } catch (IllegalArgumentException ignored) {
                // A type name from a future or renamed build. Fall through to the guess.
            }
        }
        int crater = tag.getInt("CraterR");
        int vents = tag.contains("SurfaceVents") ? tag.getLongArray("SurfaceVents").length : 0;
        int molten = tag.contains("MoltenCells") ? tag.getLongArray("MoltenCells").length : 0;
        if (crater <= 2 && vents > 20) return com.jeladastudios.ftsgeology.volcano.VolcanoType.FISSURE;
        if (crater >= 10 && molten > 30) return com.jeladastudios.ftsgeology.volcano.VolcanoType.CALDERA;
        return null;
    }

    /**
     * Puts the magma back after an earthquake has taken it, once per quake. Restores
     * {@code moltenCells}, the cells the builder said must stay lava; without this a quake that
     * sheared the lava from the core left the volcano cold for good.
     */
    private void refillAfterQuake(ServerLevel level, BlockPos pos) {
        long quake = com.jeladastudios.ftsgeology.quake.QuakeQuiet.released(
                level, pos.getX(), pos.getZ());
        if (quake == 0L || quake <= rechargedFor) return;
        // Judged by the lake itself, not by the core's neighbours: one lava cell left beside the core read
        // as an untouched lake while the rest of it had been filled with rock.
        if (moltenIntact(level) && hasLava(level, pos)) {
            stampRecharge(quake);
            return;
        }
        if (lastRefillTry != Long.MIN_VALUE && level.getGameTime() - lastRefillTry < REFILL_RETRY_TICKS) return;
        lastRefillTry = level.getGameTime();

        // Cool what the quake flung about first; coolScatteredLava only runs while erupting.
        VolcanoEruption.coolScatteredLava(level, pos.above(), craterR, 20 + magnitude,
                surfaceVents, moltenCells);

        int restored = refill(level, moltenCells);

        // The list can be empty on older saves, so the throat above the core is always tried too.
        if (restored == 0 && !hasLava(level, pos)) {
            restored = refill(level, new long[]{pos.above().asLong()});
        }

        if (restored > 0) {
            com.jeladastudios.ftsgeology.util.Diagnostics.info("Volcano at {} recharged after a quake: {} cells", pos, restored);
            stampRecharge(quake);
        } else if (++refillTries >= REFILL_TRIES) {
            // Nothing would take lava in three tries: said once, and no more sweeps for this quake.
            com.jeladastudios.ftsgeology.util.Diagnostics.info("Volcano at {} could not refill its lake after a quake ({} molten cells recorded)",
                    pos, moltenCells.length);
            stampRecharge(quake);
        }
    }

    /** Tries at the current quake's refill and when the last was; not saved, so a reload allows a few more. */
    private int refillTries;
    private long lastRefillTry = Long.MIN_VALUE;
    private static final int REFILL_TRIES = 3;
    private static final long REFILL_RETRY_TICKS = 200L;

    private void stampRecharge(long quake) {
        rechargedFor = quake;
        refillTries = 0;
        lastRefillTry = Long.MIN_VALUE;
        setChanged();
    }

    /** Whether nine in ten of the cells meant to be molten still are. True when none are recorded. */
    private boolean moltenIntact(ServerLevel level) {
        if (moltenCells.length == 0) return true;
        int lava = 0;
        for (long c : moltenCells) {
            if (level.getBlockState(BlockPos.of(c)).getFluidState().is(FluidTags.LAVA)) lava++;
        }
        return lava * 10 >= moltenCells.length * 9;
    }

    /**
     * Puts lava back into cells a quake emptied or filled with rock, and takes off the rock it pushed over
     * them, so the lake is open again. Returns how many took it. Lava goes in only where it stays put:
     * <ol>
     *   <li>something solid underneath, or the core itself;</li>
     *   <li>all four horizontal neighbours solid or already lava;</li>
     *   <li>at or below local ground, never on a sheared-off pinnacle.</li>
     * </ol>
     */
    private int refill(ServerLevel level, long[] cells) {
        int restored = 0;
        for (long c : cells) {
            BlockPos p = BlockPos.of(c);
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty()) continue;                  // already lava or water
            if (EruptionHandler.isPlayerPlaced(s) || s.hasBlockEntity()) continue;
            if (!contained(level, p)) continue;
            level.setBlock(p, Blocks.LAVA.defaultBlockState(), 2);
            restored++;
            for (int up = 1; up <= 3; up++) {
                BlockPos a = p.above(up);
                BlockState over = level.getBlockState(a);
                if (over.isAir() || !over.getFluidState().isEmpty()) break;
                if (EruptionHandler.isPlayerPlaced(over) || over.hasBlockEntity() || over.is(Blocks.BEDROCK)) break;
                level.setBlock(a, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        return restored;
    }

    /** Would lava placed here stay where it was put? See {@link #refill}. */
    private boolean contained(ServerLevel level, BlockPos p) {
        BlockPos below = p.below();
        boolean floored = below.equals(worldPosition)
                || level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                || !level.getBlockState(below).getFluidState().isEmpty();
        if (!floored) return false;

        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            BlockState ns = level.getBlockState(n);
            if (!ns.getFluidState().isEmpty()) continue;                 // lava next door is fine
            if (!ns.isFaceSturdy(level, n, d.getOpposite())) return false;
        }

        int ground = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(
                level, p.getX(), p.getZ());
        int ceiling = Math.max(ground == Integer.MIN_VALUE ? p.getY() : ground,
                worldPosition.getY() + 1);
        return p.getY() <= ceiling;
    }

    public void setSurfaceVents(List<BlockPos> vents) {
        long[] arr = new long[vents.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = vents.get(i).asLong();
        this.surfaceVents = arr;
        setChanged();
    }

    public void setFumaroles(List<BlockPos> vents) {
        long[] arr = new long[vents.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = vents.get(i).asLong();
        this.fumaroles = arr;
        setChanged();
    }

    /**
     * Cells meant to stay lava between eruptions: summit pool, caldera lake, fissure ponds. Kept as a
     * list because a radius cannot express those shapes.
     */
    public void setMoltenCells(List<BlockPos> cells) {
        long[] arr = new long[cells.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = cells.get(i).asLong();
        this.moltenCells = arr;
        setChanged();
    }

    /** Tells nearby players what this volcano is doing, flank chimneys included, so their client can draw the smoke. */
    private void broadcastEruption(ServerLevel level, BlockPos summit) {
        byte code = phase == Phase.ERUPTING ? (byte) 2 : phase == Phase.RUMBLING ? (byte) 1 : (byte) 0;
        double[] wind = VolcanoEruption.wind(summit);
        com.jeladastudios.ftsgeology.network.ModNetwork.sendEruption(level,
                new com.jeladastudios.ftsgeology.network.EruptionPacket(summit, code, magnitude,
                        (float) wind[0], (float) wind[1], GeyserConfig.VOLCANIC_ASHFALL.get(), fumaroles));
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, VolcanoCoreBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        // A marker world generation left for a large volcano. It is not a volcano yet: it finishes the
        // summit once the ground around it has loaded, and the chamber's lava then takes its place.
        if (be.fieldPending) {
            if (server.getGameTime() % 20L == 0L) {
                com.jeladastudios.ftsgeology.volcano.VolcanoBuilder.finishFieldVolcano(server, pos);
            }
            return;
        }
        BlockPos summit = pos.above(); // the crater vent sits just above the core

        // Per-tick spectacle.
        if (be.phase == Phase.RUMBLING) {
            VolcanoEruption.rumble(server, summit, server.getGameTime());
        } else if (be.phase == Phase.ERUPTING) {
            be.eruptionTicks++;
            VolcanoEruption.tickEruption(server, summit, be.magnitude, be.eruptionTicks);
        }
        // The heartbeat the client draws the smoke from. Every two seconds is plenty: the client
        // holds the state for longer than that, and lets the smoke die only once beats stop coming.
        if (be.phase != Phase.DORMANT && server.getGameTime() % 40L == 0L) be.broadcastEruption(server, summit);

        if (server.getGameTime() % 20L != 0L) return; // the cycle ticks once a second

        switch (be.phase) {
            case DORMANT -> {
                // No new work in ground a quake is still moving; an eruption under way may finish.
                if (com.jeladastudios.ftsgeology.quake.QuakeQuiet.isQuiet(server, pos)) return;
                // Restore magma a quake took, or hasLava stays false for good.
                long quake = com.jeladastudios.ftsgeology.quake.QuakeQuiet.released(
                        server, pos.getX(), pos.getZ());
                // Zero means no quake was released here; without the check a new volcano rebuilt itself at once.
                if (quake != 0L && quake > be.rebuiltFor) be.rebuildAfterQuake(server, pos, quake);
                be.refillAfterQuake(server, pos);
                if (!hasLava(server, pos)) return; // dead until it has lava again
                be.answerQuake(server, pos);
                be.idleSmoke(server, summit, 0.4f, true); // lazy smoke off the crater + a vent or two
                if ((be.timer -= 20) <= 0) {
                    GeysersMod.LOGGER.debug("Volcano at {} begins to rumble", pos);
                    if (be.sealed) be.unseal(server, pos);
                    be.phase = Phase.RUMBLING;
                    be.timer = GeyserConfig.VOLCANO_RUMBLE_TICKS.get();
                    be.broadcastEruption(server, summit);
                }
            }
            case RUMBLING -> {
                be.idleSmoke(server, summit, 1.0f, true); // building: heavier smoke from pool + all vents
                if ((be.timer -= 20) <= 0) {
                    be.phase = Phase.ERUPTING;
                    be.eruptionTicks = 0;
                    be.spilled = 0;
                    be.timer = GeyserConfig.VOLCANO_ERUPT_TICKS.get();
                    be.broadcastEruption(server, summit);
                }
            }
            case ERUPTING -> {
                // The flank vents smoke hardest while erupting.
                be.idleSmoke(server, summit, 1.0f, false);
                // Well lava up the crater, to a budget, so the flow is a tongue and not a flood.
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
                    if (be.sealed) {
                        // A sleeping volcano's crater does not stay a lake: all of it cools but the vent, which is plugged.
                        VolcanoEruption.coolScatteredLava(server, summit, 0, 20 + be.magnitude,
                                be.surfaceVents, be.moltenCells);
                        be.reseal(server, pos);
                    } else {
                        VolcanoEruption.formCrater(server, summit, be.craterR, be.moltenCells);
                        // Everything spilled OUTSIDE the crater cools to basalt/tuff; the crater lake
                        // stays molten, and the outlet trickles cool too.
                        VolcanoEruption.coolScatteredLava(server, summit, be.craterR, 20 + be.magnitude,
                                be.surfaceVents, be.moltenCells);
                    }
                    for (long v : be.surfaceVents) {
                        VolcanoEruption.dryVent(server, BlockPos.of(v).above());
                    }
                    be.phase = Phase.DORMANT;
                    be.timer = be.sealed ? sealedRoll(server) : dormantRoll(server);
                    be.broadcastEruption(server, summit);   // tells the client it is over
                }
            }
        }
        be.setChanged();
    }

    /**
     * Black smoke off the crater lake and the surface vents; intensity 0..1.
     *
     * @param deposit whether vents also lay sulfur; only while quiet, since sulfur is a fumarole
     *                product and an erupting vent pours lava
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
        if (fumaroles.length > 0) tag.putLongArray("Fumaroles", fumaroles);
        if (moltenCells.length > 0) tag.putLongArray("MoltenCells", moltenCells);
        tag.putLong("RechargedFor", rechargedFor);
        tag.putLong("RebuiltFor", rebuiltFor);
        tag.putLong("TriggeredFor", triggeredFor);
        if (sealed) tag.putBoolean("Sealed", true);
        if (sealCells.length > 0) tag.putLongArray("SealCells", sealCells);
        if (type != null) tag.putString("Type", type.name());
        if (originalBase != null) tag.putLong("OriginalBase", originalBase.asLong());
        tag.putInt("OriginalSummitY", originalSummitY);
        tag.putString("Size", size.name());
        if (fieldPending) tag.putBoolean(FIELD_PENDING, true);
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
        // Absent on worlds from before flank fumaroles; empty means none.
        fumaroles = tag.contains("Fumaroles") ? tag.getLongArray("Fumaroles") : new long[0];
        moltenCells = tag.contains("MoltenCells") ? tag.getLongArray("MoltenCells") : new long[0];
        rechargedFor = tag.contains("RechargedFor") ? tag.getLong("RechargedFor") : Long.MIN_VALUE;
        rebuiltFor = tag.contains("RebuiltFor") ? tag.getLong("RebuiltFor") : Long.MIN_VALUE;
        triggeredFor = tag.contains("TriggeredFor") ? tag.getLong("TriggeredFor") : Long.MIN_VALUE;
        sealed = tag.getBoolean("Sealed");
        sealCells = tag.contains("SealCells") ? tag.getLongArray("SealCells") : new long[0];
        type = readType(tag);
        originalBase = tag.contains("OriginalBase") ? BlockPos.of(tag.getLong("OriginalBase")) : null;
        originalSummitY = tag.contains("OriginalSummitY")
                ? tag.getInt("OriginalSummitY") : Integer.MIN_VALUE;
        // Every core saved before sizes existed was a small one.
        size = com.jeladastudios.ftsgeology.volcano.VolcanoSize.SMALL;
        if (tag.contains("Size")) {
            try {
                size = com.jeladastudios.ftsgeology.volcano.VolcanoSize.valueOf(tag.getString("Size"));
            } catch (IllegalArgumentException ignored) {
                // A size from a future build: treat it as small, the one that is never rebuilt wrong.
            }
        }
        fieldPending = tag.getBoolean(FIELD_PENDING);
    }
}
