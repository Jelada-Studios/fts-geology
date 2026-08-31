package com.pandabear.geysers.blockentity;

import com.pandabear.geysers.config.GeyserConfig;
import com.pandabear.geysers.eruption.EruptionHandler;
import com.pandabear.geysers.eruption.VentPathfinder;
import com.pandabear.geysers.registry.ModBlockEntities;
import com.pandabear.geysers.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The thermodynamic core of a geyser system.
 *
 * <p>Runs a five-phase state machine. Heavy thermodynamics recompute once per second
 * (every 20 ticks); the eruption jet field runs every tick while {@link Phase#ERUPTING}
 * so it can keep re-filling surface fluid from re-entering the vent.</p>
 *
 * <h2>Pressure model</h2>
 * <pre>P = (T &times; V_water) / V_room</pre>
 * where {@code V_water} is measured in "steam-equivalent" units — each real water block
 * flashing to steam contributes {@code steamExpansionRatio} (~1600) of expansion.
 */
public class GeyserCoreBlockEntity extends BlockEntity {

    public enum Phase {
        /** Below boiling; heat source slowly warming the chamber. */
        HEATING,
        /** Boiling; converting water to latent steam pressure, cracking the crust. */
        PRESSURIZING,
        /** P exceeded threshold; venting steam + water upward, jet field active. */
        ERUPTING,
        /** P vented below safe; surface water draining back in (Flowing Fluids). */
        RECHARGING,
        /** Cold-shocked; heat source re-warming on the cooldown timer. */
        COOLING
    }

    // --- Simulated state ----------------------------------------------------
    private double temperatureC = 20.0;     // T
    private double waterVolume = 0.0;        // V_su  (real water blocks tracked in chamber)
    private double roomVolume = 1.0;         // V_oda (air/void inside chamber; never 0)
    private double pressure = 0.0;           // P
    private double latentSteam = 0.0;        // accumulated steam-equivalent pressure points
    private Phase phase = Phase.HEATING;

    private int eruptionCount = 0;
    private int cooldownTimer = 0;           // ticks remaining in COOLING
    private int eruptionTicks = 0;           // ticks spent in current eruption
    private int eruptionTargetTicks = 0;     // how long this eruption should last (from magnitude)

    /** Diagnostic: total server ticks this core has processed. If it stays 0, the ticker is dead. */
    private int tickCount = 0;

    /**
     * Size tier of the system, ~ the chamber radius in blocks. Assigned at generation.
     * Drives everything the player sees: bigger magnitude → bigger chamber survey, longer
     * eruptions, stronger blast/jet, wider mineral cone. A radius-5 vent is a short 5–10 min
     * spouter; a radius-20 vent erupts for the better part of an hour.
     */
    private int magnitude = MIN_MAGNITUDE;
    public static final int MIN_MAGNITUDE = 5;
    public static final int MAX_MAGNITUDE = 20;

    /**
     * Y of the vent's open mouth (the surface opening for a shaft-connected geyser). Stamped at
     * generation when the shaft is carved and the surface Y is known. {@link #UNKNOWN_MOUTH_Y}
     * means "buried / not stamped" — the mouth is then found dynamically each tick.
     */
    private int ventMouthY = UNKNOWN_MOUTH_Y;
    private static final int UNKNOWN_MOUTH_Y = Integer.MIN_VALUE;
    /** Max blocks the vent may rise above the original ground surface — the surface chimney height. */
    private static final int SURFACE_CHIMNEY_HEIGHT = 2;

    /**
     * The highest cell the vent has bored up to so far — its "frontier". Persisted so the vent
     * keeps drilling upward <em>cumulatively</em> across eruptions: each active trace resumes from
     * here (a few blocks of new progress per second) instead of re-climbing from the core, which is
     * what makes the vent slowly work its way to daylight rather than stalling just above the core.
     */
    private int ventTopY = UNKNOWN_MOUTH_Y;

    /** Last mouth resolved by the pathfinder (transient cache for the per-tick jet). */
    private transient BlockPos currentMouth;
    /** Ceiling used by the last resolve — the target surface Y. Water only spouts once we reach it. */
    private transient int currentCeilingY = Integer.MAX_VALUE;

    /**
     * Packed positions of secondary fumaroles — the cave/air breakthroughs of the root-vent
     * network (see {@code VentNetwork}). The core puffs weak steam from these while hot, and a
     * gentle scald when erupting. Empty for geysers with no branches that reached open space.
     */
    private long[] fumaroleTips = new long[0];

    /**
     * True for a geyser that formed from a <em>player-built</em> water-over-rock-over-lava
     * setup (see {@code EmergentGeyserHandler}). When {@code emergentDestructive} is on, its
     * eruption uses a block-breaking blast — the "it blows up your house" behaviour — instead
     * of the build-safe non-destructive one natural geysers use.
     */
    private boolean emergent = false;

    /**
     * Cached interior chamber cells (air/water), discovered once via a bounded flood-fill
     * that treats {@code GeyserChamberBlock} markers and solid rock as walls. Persisted in
     * NBT so a chunk reload doesn't re-scan. Invalidated (set null) whenever the vent
     * geometry changes — i.e. at each eruption boundary.
     */
    private long[] chamberCells = null;
    /** Baseline cell budget (small geysers); scaled up by magnitude, hard-capped below. */
    private static final int MAX_CHAMBER_CELLS = 512;
    /** Absolute ceiling on surveyed cells regardless of magnitude — protects TPS. */
    private static final int MAX_CHAMBER_CELLS_HARD_CAP = 4096;
    /** Largest half-extent (in blocks) the flood-fill may wander from the core on X/Z. */
    private static final int MAX_CHAMBER_SCAN_HALF_EXTENT = 24;

    public GeyserCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GEYSER_CORE.get(), pos, state);
    }

    // === Ticking ============================================================

    public static void serverTick(Level level, BlockPos pos, BlockState state, GeyserCoreBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        be.tickCount++; // diagnostic heartbeat

        // Per-tick work: only the eruption jet needs sub-second resolution. It uses the mouth
        // cached by the once-per-second resolve so we don't re-trace/breach every tick.
        if (be.phase == Phase.ERUPTING) {
            be.eruptionTicks++;
            EruptionHandler.tickJetField(server, pos, be.cachedMouth(server, pos), be);
        }

        // Pre-eruption warning: steam seepage + scald hazard while pressurising.
        // Twice per second is responsive enough without spamming particles/damage.
        if (be.phase == Phase.PRESSURIZING && server.getGameTime() % 10L == 0L) {
            EruptionHandler.tickFumaroleHazard(server, be.cachedMouth(server, pos), be);
        }

        // Thermodynamics recompute once per second.
        if (server.getGameTime() % 20L != 0L) return;

        be.surveyChamber(server, pos);
        be.advanceThermodynamics(server, pos);
        be.runStateMachine(server, pos);
        be.setChanged();
    }

    // === Environment survey =================================================

    /**
     * Measures the current chamber: counts real water blocks (V_su) and air cells (V_oda)
     * over the <em>cached interior cell set</em> only, plus adjacent heat sources.
     *
     * <p>Cost is O(cells) with a hard {@link #MAX_CHAMBER_CELLS} ceiling — independent of any
     * fixed cube radius — because the {@code GeyserChamberBlock} markers wall the flood-fill
     * in tightly. This is the TPS-friendly path versus the old (2r+1)³ cube scan.</p>
     */
    private void surveyChamber(ServerLevel level, BlockPos pos) {
        if (chamberCells == null) {
            chamberCells = discoverChamberCells(level, pos);
        }

        double water = 0.0;
        int air = 0;
        for (long packed : chamberCells) {
            BlockPos p = BlockPos.of(packed);
            BlockState s = level.getBlockState(p);
            FluidState fs = s.getFluidState();
            if (fs.is(FluidTags.WATER)) {
                // Finite-water aware: a partial Flowing Fluids cell (level 1..8) counts
                // fractionally rather than as a whole block.
                water += Math.max(1, fs.getAmount()) / 8.0;
            } else if (s.isAir()) {
                air++;
            }
        }

        // Heat sources: the immediate 6-neighborhood, plus the 3x3 pool directly beneath (a wide
        // lava reservoir gives more heat and thus more thermal mass / faster recharge).
        double heat = 0.0;
        for (Direction d : Direction.values()) {
            heat += heatWeightAt(level.getBlockState(pos.relative(d)));
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue; // centre already counted as the 'below' neighbour
                heat += heatWeightAt(level.getBlockState(pos.offset(dx, -1, dz))) * 0.25;
            }
        }

        this.waterVolume = Math.min(water, 48.0); // cap so an aquifer leak can't over-read water
        // Clamp the room volume: a big (or leaky) air pocket would otherwise dilute P = T*V_su/V_room
        // to nearly nothing and the geyser would never reach the eruption threshold.
        this.roomVolume = Mth.clamp(air, 1.0, 10.0);
        this.cachedHeatWeight = heat;
    }

    private transient double cachedHeatWeight = 0.0;

    /**
     * Heat contribution of one neighbouring block. Flowing Fluids can leave lava in partial
     * states (levels 1..8) or flowing across the chunk; we key off the {@link FluidTags#LAVA}
     * tag — which matches source <em>and</em> flowing lava at any fill — and scale by amount
     * so even a thin lava trickle still heats the chamber, just less than a full source.
     * A magma block counts as a full heat unit.
     */
    private static double heatWeightAt(BlockState s) {
        if (s.is(Blocks.MAGMA_BLOCK)) return 1.0;
        FluidState fs = s.getFluidState();
        if (fs.is(FluidTags.LAVA)) {
            return Mth.clamp(fs.getAmount() / 8.0, 0.15, 1.0);
        }
        return 0.0;
    }

    /**
     * Bounded breadth-first flood-fill outward from the cell above the core. Expansion stops
     * at any non-interior block — {@code GeyserChamberBlock} markers, solid rock, etc. — so the
     * shell built during (retro)generation naturally delimits the volume. Additional guards
     * cap the extent on all axes, never cross the safety ceiling, and bound total cells.
     */
    private long[] discoverChamberCells(ServerLevel level, BlockPos core) {
        List<Long> cells = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        // Extent and cell cap scale with magnitude so a big basin is fully surveyed, but both
        // stay bounded to protect TPS.
        int extent = Mth.clamp(magnitude, 6, MAX_CHAMBER_SCAN_HALF_EXTENT);
        int cellCap = Mth.clamp(magnitude * magnitude * 4, MAX_CHAMBER_CELLS, MAX_CHAMBER_CELLS_HARD_CAP);

        int maxY = GeyserConfig.RETROGEN_MAX_Y.get();
        int topBound = core.getY() + GeyserConfig.CHAMBER_TARGET_HEIGHT.get() + magnitude + 3;
        int bottomBound = core.getY() - 2;

        BlockPos start = core.above();
        queue.add(start);
        seen.add(start.asLong());

        while (!queue.isEmpty() && cells.size() < cellCap) {
            BlockPos p = queue.poll();
            BlockState s = level.getBlockState(p);
            boolean interior = s.isAir() || s.getFluidState().is(FluidTags.WATER);
            if (!interior) continue; // walls (rock / chamber markers) bound the volume

            cells.add(p.asLong());

            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (n.getY() >= maxY || n.getY() < bottomBound || n.getY() > topBound) continue;
                if (Math.abs(n.getX() - core.getX()) > extent) continue;
                if (Math.abs(n.getZ() - core.getZ()) > extent) continue;
                if (seen.add(n.asLong())) {
                    queue.add(n);
                }
            }
        }

        long[] out = new long[cells.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = cells.get(i);
        }
        return out;
    }

    /** Forces a chamber re-scan on the next tick (call when vent geometry changes). */
    private void invalidateChamberCache() {
        this.chamberCells = null;
    }

    /**
     * Emits the "pressure building" cue: bubbles rising off the chamber water (more, and joined by
     * boiling splashes, the hotter it gets) plus a hiss that rises in volume/pitch as pressure
     * nears the eruption threshold. Watch the water froth and listen for the whistle — that's your
     * warning to clear the area.
     */
    private void emitPressureCue(ServerLevel level) {
        if (chamberCells == null) return;
        double boiling = GeyserConfig.BOILING_POINT_C.get();
        if (temperatureC < boiling * 0.6) return; // nothing to show until it's genuinely warming

        double ratio = Mth.clamp(pressure / GeyserConfig.PRESSURE_ERUPTION_THRESHOLD.get(), 0.0, 1.5);
        int cap = 3 + (int) (ratio * 6);
        int emitted = 0;
        for (long packed : chamberCells) {
            if (emitted >= cap) break;
            BlockPos p = BlockPos.of(packed);
            if (level.getBlockState(p).getFluidState().is(FluidTags.WATER)) {
                level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP,
                        p.getX() + 0.5, p.getY() + 0.6, p.getZ() + 0.5, 2, 0.2, 0.1, 0.2, 0.02);
                if (temperatureC >= boiling) {
                    level.sendParticles(ParticleTypes.SPLASH,
                            p.getX() + 0.5, p.getY() + 0.9, p.getZ() + 0.5, 1, 0.2, 0.05, 0.2, 0.01);
                }
                emitted++;
            }
        }

        // Rising steam-whistle once we're past the halfway mark, roughly twice a second.
        if (ratio >= 0.5 && level.getGameTime() % 30L < 20L) {
            float p01 = (float) Mth.clamp(ratio, 0.0, 1.0);
            level.playSound(null, worldPosition, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS,
                    0.3f + 0.7f * p01, 0.5f + 0.9f * p01);
        }
    }

    /**
     * Removes the highest {@code count} water cells from the chamber — the basin visibly empties
     * as its water is spouted out. Returns how many were removed. Draining the top first makes
     * the water level fall naturally.
     */
    private int drainChamberWater(ServerLevel level, int count) {
        if (chamberCells == null) return 0;
        int removed = 0;
        for (int k = 0; k < count; k++) {
            long best = 0L;
            int bestY = Integer.MIN_VALUE;
            for (long packed : chamberCells) {
                BlockPos p = BlockPos.of(packed);
                if (p.getY() > bestY && level.getBlockState(p).getFluidState().is(FluidTags.WATER)) {
                    bestY = p.getY();
                    best = packed;
                }
            }
            if (bestY == Integer.MIN_VALUE) break; // basin is dry
            level.setBlock(BlockPos.of(best), Blocks.AIR.defaultBlockState(), 2);
            removed++;
        }
        return removed;
    }

    /** Refills the lowest empty chamber cell with water — groundwater/intake between eruptions. */
    private boolean refillChamberWater(ServerLevel level) {
        if (chamberCells == null) return false;
        long best = 0L;
        int bestY = Integer.MAX_VALUE;
        for (long packed : chamberCells) {
            BlockPos p = BlockPos.of(packed);
            if (p.getY() < bestY && level.getBlockState(p).isAir()) {
                bestY = p.getY();
                best = packed;
            }
        }
        if (bestY == Integer.MAX_VALUE) return false; // chamber already full
        level.setBlock(BlockPos.of(best), Blocks.WATER.defaultBlockState(), 2);
        return true;
    }

    // === Thermodynamics =====================================================

    private void advanceThermodynamics(ServerLevel level, BlockPos pos) {
        double heatGain = cachedHeatWeight * GeyserConfig.HEAT_PER_LAVA_NEIGHBOR.get() * 20.0; // per-second
        double cooling = GeyserConfig.AMBIENT_COOLING_PER_TICK.get() * 20.0;

        // Cold surface water flooding in during recharge is a strong heat sink.
        if (phase == Phase.RECHARGING && waterVolume > 0) {
            cooling += waterVolume * 2.0;
        }

        temperatureC += heatGain - cooling;
        temperatureC = clamp(temperatureC, 20.0, GeyserConfig.MAX_TEMPERATURE_C.get());

        double boiling = GeyserConfig.BOILING_POINT_C.get();

        // Above boiling: flash water into latent steam pressure points.
        if (temperatureC >= boiling && waterVolume > 0) {
            double flashed = Math.min(waterVolume, temperatureC / boiling); // hotter => flashes faster
            latentSteam += flashed * GeyserConfig.STEAM_EXPANSION_RATIO.get();
        }

        // P = (T * V_su) / V_room, augmented by already-latent steam.
        double base = (temperatureC * (waterVolume + latentSteam / GeyserConfig.STEAM_EXPANSION_RATIO.get()))
                / roomVolume;
        this.pressure = base;
    }

    // === State machine ======================================================

    private void runStateMachine(ServerLevel level, BlockPos pos) {
        double boiling = GeyserConfig.BOILING_POINT_C.get();
        double erupt = GeyserConfig.PRESSURE_ERUPTION_THRESHOLD.get();
        double safe = GeyserConfig.PRESSURE_SAFE_THRESHOLD.get();
        double crackP = GeyserConfig.CRUST_EROSION_PRESSURE.get();
        // Re-trace the live vent mouth once per second. While erupting the trace is allowed to
        // breach/reroute, so a plugged vent blasts back open and a capped one force-breaches.
        BlockPos mouth = resolveMouth(level, pos, phase == Phase.ERUPTING);

        // Secondary fumaroles puff whenever the system is hot; they bite (lightly) mid-eruption.
        if ((phase == Phase.PRESSURIZING || phase == Phase.ERUPTING) && fumaroleTips.length > 0) {
            EruptionHandler.tickBranchFumaroles(level, fumaroleTips, phase == Phase.ERUPTING);
        }

        // Visible/audible "it's building" cue: the chamber water bubbles harder and hisses
        // louder as it heats and pressurises, so you can tell it's about to blow.
        if (phase == Phase.HEATING || phase == Phase.PRESSURIZING) {
            emitPressureCue(level);
        }

        switch (phase) {
            case COOLING -> {
                // Residual steam while it's still warm — a dormant vent keeps wisping, no water.
                if (temperatureC > boiling * 0.4) {
                    EruptionHandler.emitSteamWisps(level, mouth, 0.25f);
                }
                if (level.getGameTime() % GeyserConfig.CHAMBER_REFILL_INTERVAL_TICKS.get() < 20L) {
                    refillChamberWater(level); // basin slowly refills while dormant
                }
                if (--cooldownTimer <= 0) {
                    phase = Phase.HEATING;
                }
            }
            case HEATING -> {
                EruptionHandler.emitSteamWisps(level, mouth, 0.3f);
                if (temperatureC >= boiling) {
                    phase = Phase.PRESSURIZING;
                }
            }
            case PRESSURIZING -> {
                EruptionHandler.emitSteamWisps(level, mouth, 1.0f);
                if (pressure >= crackP) {
                    EruptionHandler.erodeCrust(level, mouth, pressure);
                }
                if (pressure >= erupt) {
                    beginEruption(level, pos, mouth);
                }
                // If we lost heat before erupting, fall back.
                if (temperatureC < boiling) {
                    phase = Phase.HEATING;
                }
            }
            case ERUPTING -> {
                // Venting bleeds latent steam and pressure each second.
                double vented = Math.max(latentSteam * 0.25, 500.0);
                latentSteam = Math.max(0.0, latentSteam - vented);
                // Grow the calcite chimney and seal any cave the vent broke into: wall the mouth's
                // sides every second so water can't run off sideways, and a sinter tube rises with
                // the climbing vent (visible from the first eruption, while it's still flowing).
                EruptionHandler.buildChimneyRim(level, mouth);
                // Only spout real water once the vent has actually bored through to its opening —
                // otherwise a deep vent leaves a tall standing water column as its mouth climbs.
                // (While still boring upward it just breaches + steams.) And only while the basin
                // has water (conservation).
                // Spout water wherever the vent currently OPENS (the block above the mouth is
                // air) — the rig's own hole for a sealed rig, a cave, or the surface. While the
                // vent is still boring through solid rock its mouth is capped, so no water is
                // placed there and no tall column builds.
                // Natural geysers are fed by the water table, so they always have water to spout
                // while erupting (even if their visible basin momentarily leaked into a cave).
                // Only a sealed player rig (emergent) can genuinely run dry.
                boolean hasWater = !emergent || waterVolume >= 1.0;
                boolean atExit = level.getBlockState(mouth.above()).isAir();
                if (hasWater && atExit) {
                    EruptionHandler.ventEruption(level, mouth);
                    EruptionHandler.depositTravertineRunoff(level, mouth, magnitude);
                    // A sealed rig (emergent) drains ONLY while it's actually spouting — so its
                    // water isn't wasted during the boring phase — and dies when the basin empties.
                    if (emergent && level.getGameTime() % GeyserConfig.CHAMBER_DRAIN_INTERVAL_TICKS.get() < 20L) {
                        drainChamberWater(level, 1);
                    }
                }
                // A natural vent is groundwater-fed and keeps its basin topped up.
                if (!emergent && level.getGameTime() % GeyserConfig.CHAMBER_REFILL_INTERVAL_TICKS.get() < 20L) {
                    refillChamberWater(level);
                }

                boolean basinDry = emergent && atExit && waterVolume < 1.0;
                // End on: magnitude-scaled duration, a drained sealed rig, or the chamber going
                // cold (heat source removed) with pressure collapsed.
                if (eruptionTicks >= eruptionTargetTicks || basinDry
                        || (temperatureC < boiling && pressure < safe)) {
                    endEruption(level, pos, mouth);
                }
            }
            case RECHARGING -> {
                // Water has stopped, but the vent keeps venting weaker steam as it settles.
                EruptionHandler.emitSteamWisps(level, mouth, 0.45f);
                EruptionHandler.drawSurfaceWaterIn(level, mouth, GeyserConfig.RECHARGE_INTAKE_FRACTION.get());
                if (level.getGameTime() % GeyserConfig.CHAMBER_REFILL_INTERVAL_TICKS.get() < 20L) {
                    refillChamberWater(level); // basin refills as surface water drains back in
                }
                // Thermal shock: cold intake drops us below boiling -> cooldown.
                if (temperatureC < boiling) {
                    enterCooldown(level);
                }
            }
        }
    }

    private void beginEruption(ServerLevel level, BlockPos pos, BlockPos mouth) {
        phase = Phase.ERUPTING;
        eruptionTicks = 0;
        // Water phase length scales with magnitude but is capped so no geyser floods the world:
        // a short, punchy burst (up to ~2 min), then it winds down to residual steam and cools.
        eruptionTargetTicks = Math.min(magnitude * GeyserConfig.ERUPTION_TICKS_PER_MAGNITUDE.get(),
                GeyserConfig.WATER_SPOUT_MAX_TICKS.get());
        eruptionCount++;
        // Only blow the lid if the vent is actually capped — if the roof is already open, there's
        // nothing to break, so don't fire a pointless explosion.
        BlockState above = level.getBlockState(mouth.above());
        boolean capped = !above.isAir() && above.getFluidState().isEmpty();
        if (emergent && GeyserConfig.EMERGENT_DESTRUCTIVE.get() && capped) {
            EruptionHandler.destructiveBurst(level, mouth, magnitude); // breaks blocks (even underwater)
        } else if (!emergent) {
            EruptionHandler.primaryBlast(level, mouth, magnitude);
        }
        // Violent first instant: fling the pool the vent broke into + launch entities high.
        EruptionHandler.onsetWaterBurst(level, mouth, magnitude);
        invalidateChamberCache(); // breach opens new cells; re-survey next tick
        if (eruptionCount % GeyserConfig.CONE_BUILD_ERUPTIONS.get() == 0) {
            EruptionHandler.depositMineralCone(level, mouth, magnitude);
        }
    }

    private void endEruption(ServerLevel level, BlockPos pos, BlockPos mouth) {
        phase = Phase.RECHARGING;
        EruptionHandler.removeJetField(level, mouth);
        invalidateChamberCache(); // geometry settled; refresh cell set for the recharge survey
    }

    /**
     * Traces the live vent mouth from the chamber via {@link VentPathfinder}, caching the result
     * for the per-tick jet. {@code active} (erupting) lets the trace breach/reroute obstructions;
     * otherwise it only reports where the vent is currently blocked.
     */
    private BlockPos resolveMouth(ServerLevel level, BlockPos pos, boolean active) {
        // Ceiling: a FIXED target captured once — the original ground surface plus a short chimney.
        // If it isn't stamped yet (a geyser made before stamping existed, an emergent rig, etc.) we
        // capture it the first time we resolve and freeze it. This is the fix for vents that grew a
        // calcite chimney straight up to the world height limit: because the chimney lifts the
        // WORLD_SURFACE heightmap, a live re-read would make the vent chase its own tower upward
        // forever. Freezing the ceiling once stops that for good — new AND already-placed geysers.
        //
        // The surface is measured with TerrainProbe, NOT with the WORLD_SURFACE heightmap. That
        // heightmap counts leaves and logs, so a geyser that happened to land under a spruce froze
        // its ceiling at the top of the tree and then bored a calcite chimney up into the canopy —
        // which is the "some geysers stand way too high off the ground, sometimes" report. Under a
        // tree it was wrong by the height of the tree; in the open it was right, which is exactly the
        // "sometimes". TerrainProbe walks past vegetation and answers with the actual ground.
        if (ventMouthY == UNKNOWN_MOUTH_Y) {
            ventMouthY = probedCeiling(level, pos);
            setChanged();
        } else if (ventTopY == UNKNOWN_MOUTH_Y) {
            // Existing worlds carry the old, tree-inflated stamp in their chunk data. Safe to
            // re-measure it only while the vent has never bored anything: once a chimney exists it
            // IS ground, and probing would simply read the tower straight back.
            int probed = probedCeiling(level, pos);
            if (ventMouthY > probed + 1) {
                ventMouthY = probed;
                setChanged();
            }
        }
        int ceilingY = ventMouthY;
        // Resume from the frontier we last bored to, not the core — so the per-second rise cap in
        // VentPathfinder limits NEW upward progress and the vent climbs gradually. First time, start
        // at the top of the sealed chamber (the natural rock cap is right above it).
        int startY = ventTopY != UNKNOWN_MOUTH_Y
                ? ventTopY
                : pos.getY() + GeyserConfig.CHAMBER_TARGET_HEIGHT.get();
        BlockPos mouth = VentPathfinder.trace(level, pos, startY, ceilingY, pressure, active);
        if (active && (ventTopY == UNKNOWN_MOUTH_Y || mouth.getY() > ventTopY)) {
            ventTopY = mouth.getY(); // advance the frontier so next second resumes here
        }
        this.currentMouth = mouth;
        this.currentCeilingY = ceilingY;
        return mouth;
    }

    /**
     * Where the vent is allowed to open: the real ground over the core, plus a short chimney.
     *
     * <p>{@link TerrainProbe#groundY} rather than the WORLD_SURFACE heightmap, because that heightmap
     * stops at the first leaf. The heightmap is kept only as a fallback for a column with no ground
     * in it at all, which is a cave roof or the void and either way not somewhere a geyser opens.</p>
     */
    private static int probedCeiling(ServerLevel level, BlockPos pos) {
        int g = TerrainProbe.groundY(level, pos.getX(), pos.getZ());
        if (g == Integer.MIN_VALUE) {
            g = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        }
        return g + SURFACE_CHIMNEY_HEIGHT;
    }

    /** Last-resolved mouth for per-tick use; resolves passively on first access if needed. */
    private BlockPos cachedMouth(ServerLevel level, BlockPos pos) {
        return currentMouth != null ? currentMouth : resolveMouth(level, pos, false);
    }

    private void enterCooldown(ServerLevel level) {
        phase = Phase.COOLING;
        int min = GeyserConfig.COOLDOWN_TICKS_MIN.get();
        int max = GeyserConfig.COOLDOWN_TICKS_MAX.get();
        this.cooldownTimer = min + level.random.nextInt(Math.max(1, max - min));
        this.latentSteam = 0.0;
        this.pressure = 0.0;
    }

    // === Accessors used by EruptionHandler ==================================

    public Phase getPhase() { return phase; }
    public double getPressure() { return pressure; }
    public double getTemperatureC() { return temperatureC; }
    public int getEruptionCount() { return eruptionCount; }
    public int getMagnitude() { return magnitude; }
    public int getEruptionTicks() { return eruptionTicks; }
    public double getWaterVolume() { return waterVolume; }
    public double getRoomVolume() { return roomVolume; }
    public double getHeatWeight() { return cachedHeatWeight; }
    public int getVentMouthYRaw() { return ventMouthY; }
    public int getVentTopY() { return ventTopY; }
    public int getTickCount() { return tickCount; }
    public boolean isEmergent() { return emergent; }
    public void setEmergent(boolean e) { this.emergent = e; setChanged(); }

    /** Assigned once at generation time. Clamped to [{@link #MIN_MAGNITUDE}, {@link #MAX_MAGNITUDE}]. */
    public void setMagnitude(int m) {
        this.magnitude = Mth.clamp(m, MIN_MAGNITUDE, MAX_MAGNITUDE);
        invalidateChamberCache(); // extent depends on magnitude; force a re-survey
        setChanged();
    }

    public int getVentMouthY() { return ventMouthY; }

    /** Stamped once at generation when the surface shaft is carved. */
    public void setVentMouthY(int y) {
        this.ventMouthY = y;
        setChanged();
    }

    /** Records the secondary-fumarole positions produced by the branching-vent carver. */
    public void setFumaroleTips(List<BlockPos> tips) {
        long[] arr = new long[tips.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = tips.get(i).asLong();
        }
        this.fumaroleTips = arr;
        setChanged();
    }

    // === Persistence ========================================================

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble("Temperature", temperatureC);
        tag.putDouble("WaterVolume", waterVolume);
        tag.putDouble("RoomVolume", roomVolume);
        tag.putDouble("Pressure", pressure);
        tag.putDouble("LatentSteam", latentSteam);
        tag.putInt("Phase", phase.ordinal());
        tag.putInt("EruptionCount", eruptionCount);
        tag.putInt("CooldownTimer", cooldownTimer);
        tag.putInt("Magnitude", magnitude);
        tag.putInt("EruptionTargetTicks", eruptionTargetTicks);
        tag.putInt("EruptionTicks", eruptionTicks);
        if (ventMouthY != UNKNOWN_MOUTH_Y) {
            tag.putInt("VentMouthY", ventMouthY);
        }
        if (ventTopY != UNKNOWN_MOUTH_Y) {
            tag.putInt("VentTopY", ventTopY);
        }
        tag.putBoolean("Emergent", emergent);
        if (fumaroleTips.length > 0) {
            tag.putLongArray("FumaroleTips", fumaroleTips);
        }
        if (chamberCells != null) {
            tag.putLongArray("ChamberCells", chamberCells);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        temperatureC = tag.getDouble("Temperature");
        waterVolume = tag.getDouble("WaterVolume");
        roomVolume = Math.max(1.0, tag.getDouble("RoomVolume"));
        pressure = tag.getDouble("Pressure");
        latentSteam = tag.getDouble("LatentSteam");
        phase = Phase.values()[Math.floorMod(tag.getInt("Phase"), Phase.values().length)];
        eruptionCount = tag.getInt("EruptionCount");
        cooldownTimer = tag.getInt("CooldownTimer");
        magnitude = tag.contains("Magnitude")
                ? Mth.clamp(tag.getInt("Magnitude"), MIN_MAGNITUDE, MAX_MAGNITUDE)
                : MIN_MAGNITUDE;
        eruptionTargetTicks = tag.getInt("EruptionTargetTicks");
        eruptionTicks = tag.getInt("EruptionTicks");
        ventMouthY = tag.contains("VentMouthY") ? tag.getInt("VentMouthY") : UNKNOWN_MOUTH_Y;
        ventTopY = tag.contains("VentTopY") ? tag.getInt("VentTopY") : UNKNOWN_MOUTH_Y;
        emergent = tag.getBoolean("Emergent");
        fumaroleTips = tag.contains("FumaroleTips") ? tag.getLongArray("FumaroleTips") : new long[0];
        chamberCells = tag.contains("ChamberCells") ? tag.getLongArray("ChamberCells") : null;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
