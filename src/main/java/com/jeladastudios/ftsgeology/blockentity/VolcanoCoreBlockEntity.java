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

    /** What this mountain was before anything happened to it, so it can be raised again. */
    private com.jeladastudios.ftsgeology.volcano.VolcanoType type;
    private BlockPos originalBase;
    private int originalSummitY = Integer.MIN_VALUE;

    /** Called by the builder as the core is planted. */
    public void setShape(com.jeladastudios.ftsgeology.volcano.VolcanoType t, BlockPos base,
                         int summitY) {
        this.type = t;
        this.originalBase = base.immutable();
        this.originalSummitY = summitY;
        setChanged();
    }

    /**
     * Raises the mountain again after an earthquake flattened it, once per quake.
     *
     * <p>Stamped <b>before</b> the job is queued rather than after. A volcano job takes hundreds of
     * ticks to drain and this method is reached once a second, so stamping afterwards would let
     * several rebuilds of the same mountain pile into the queue while the first was still running.
     * </p>
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
                level, originalBase, magnitude, type, originalSummitY)) {
            GeysersMod.LOGGER.info("Volcano at {} rebuilding after a quake ({} -> {})",
                    pos, here, originalSummitY);
        }
    }

    /** Sequence number of the last quake this volcano has already rebuilt for. */
    private long rebuiltFor = Long.MIN_VALUE;

    /**
     * The shape this core was built as, from NBT - or a reasonable guess for a world saved before
     * the shape was recorded.
     *
     * <p>The guess reads what the core already stores. A fissure keeps its crater tiny and its ponds
     * many; a caldera has the widest crater and a lake's worth of molten cells. Anything else is
     * left null, which simply means that volcano will not raise itself again - a quiet no-op rather
     * than a wrong mountain.</p>
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
     * Puts the magma back after an earthquake has taken it, once per quake.
     *
     * <h2>Why a volcano could die permanently</h2>
     * The dormant branch begins {@code if (!hasLava(server, pos)) return;}, and {@link #hasLava}
     * looks at the six cells touching the core. A quake that shears that magma out, or cools it
     * against water it has just let in, makes that test false - and no code in the mod ever put lava
     * back. The countdown to the next eruption stopped being decremented, and the mountain sat there
     * for the rest of the world's life with a cold crater. Testing found exactly that.
     *
     * <p>The cells to restore are already recorded: {@code moltenCells} is the set the builder said
     * must stay lava between eruptions. Until now only {@code coolScatteredLava} read it, and only to
     * decide what <i>not</i> to cool - nothing read it to put anything back.</p>
     */
    private void refillAfterQuake(ServerLevel level, BlockPos pos) {
        long quake = com.jeladastudios.ftsgeology.quake.QuakeQuiet.released(
                level, pos.getX(), pos.getZ());
        if (quake == 0L || quake <= rechargedFor) return;
        if (hasLava(level, pos)) {                        // the quake left the magma alone
            rechargedFor = quake;
            setChanged();
            return;
        }

        // Cool whatever the quake flung about before putting the proper lava back. Nothing else
        // does it: coolScatteredLava only runs from the erupting phases, so a volcano the quake
        // killed never tidied up after itself and its lava sat there for good.
        VolcanoEruption.coolScatteredLava(level, pos.above(), craterR, 20 + magnitude,
                surfaceVents, moltenCells);

        int restored = refill(level, moltenCells);

        // The recorded list is not always enough. It is filled by carveCalderaRow, carveFunnelPit
        // and carveLavaLake - but carveFissureLine records nothing, so a fissure volcano carries an
        // empty list and this would restore precisely zero cells and then mark the quake answered.
        // The throat above the core is the one cell every volcano has, whatever its shape.
        if (restored == 0) {
            restored = refill(level, new long[]{pos.above().asLong()});
        }

        // Stamped on success, not on the attempt.
        //
        // Stamping first made a transient failure permanent: nothing restored, quake marked as
        // handled, and the mountain cold for the rest of the world's life. If this run found
        // nothing to fill, the next check is welcome to try again.
        if (restored == 0) return;
        rechargedFor = quake;
        setChanged();
        GeysersMod.LOGGER.info("Volcano at {} recharged after a quake: {} cells", pos, restored);
    }

    /**
     * Puts lava back into cells a quake emptied. Returns how many took it.
     *
     * <h2>Air is allowed, and that is the whole point</h2>
     * This refused air, with the comment "never into open sky" - and that single line is why the
     * fallback never worked. A quake <b>opens</b> the throat, so the cell above the core is air, and
     * the one place the fallback exists to fill was the one place it skipped. It restored nothing
     * for ever, silently.
     *
     * <p>The worry behind that line was real though, so it is answered properly rather than by
     * refusing air outright. Lava goes in only where it would stay put:</p>
     * <ol>
     *   <li>something solid underneath, or the core itself;</li>
     *   <li>all four horizontal neighbours solid or already lava - one open side and it pours down
     *       the mountainside instead of filling;</li>
     *   <li>at or below local ground, so a summit the quake sheared off does not get lava standing
     *       on a pinnacle in the open air.</li>
     * </ol>
     */
    private int refill(ServerLevel level, long[] cells) {
        int restored = 0;
        for (long c : cells) {
            BlockPos p = BlockPos.of(c);
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty()) continue;                  // already lava or water
            if (EruptionHandler.isPlayerPlaced(s)) continue;
            if (s.isAir() && !contained(level, p)) continue;
            level.setBlock(p, Blocks.LAVA.defaultBlockState(), 2);
            restored++;
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
     * The flank fumaroles blowing hard, while the mountain is erupting.
     *
     * <h2>Why the smoke comes from here rather than from the chimney block</h2>
     * {@code SteamVentBlock} draws its own thread of steam in {@code animateTick}, which is a client
     * random tick and costs the server nothing - exactly right for the idle state. But the client
     * has no idea whether the volcano it is standing on is erupting, and telling it would mean a
     * second packet and a piece of synced state for a puff of smoke.
     *
     * <p>The core already knows, and it already has the positions, so during an eruption it simply
     * sends the heavy smoke itself. The chimney keeps its quiet wisp; this is laid over the top of
     * it and stops the moment the eruption does, with nothing to reset.</p>
     */
    private void fumaroleSmoke(ServerLevel level) {
        if (fumaroles.length == 0) return;
        for (long packed : fumaroles) {
            BlockPos p = BlockPos.of(packed);
            // The chimney is three blocks of stack, so the smoke leaves from above the cap.
            double x = p.getX() + 0.5, y = p.getY() + 2.2, z = p.getZ() + 0.5;
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                    x, y, z, 6, 0.35, 0.25, 0.35, 0.06);
            if (level.random.nextInt(3) == 0) {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                        x, y + 0.6, z, 2, 0.3, 0.3, 0.3, 0.03);
            }
        }
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
            // Every few ticks, not every one: this is one packet per chimney and a big cone carries
            // ten of them.
            if (be.eruptionTicks % 3 == 0) be.fumaroleSmoke(server);
        }

        if (server.getGameTime() % 20L != 0L) return; // the cycle ticks once a second

        switch (be.phase) {
            case DORMANT -> {
                // Does not wake up into ground a quake is still moving. An eruption already under
                // way is left to finish - it is the starting of new work that produces the ruin,
                // not the finishing of old.
                if (com.jeladastudios.ftsgeology.quake.QuakeQuiet.isQuiet(server, pos)) return;
                // A quake that took the magma away used to kill the volcano outright: hasLava went
                // false, this branch returned on every tick from then on, and nothing anywhere put
                // lava back. That is the crater that cools after an earthquake and never refills.
                long quake = com.jeladastudios.ftsgeology.quake.QuakeQuiet.released(
                        server, pos.getX(), pos.getZ());
                if (quake > be.rebuiltFor) be.rebuildAfterQuake(server, pos, quake);
                be.refillAfterQuake(server, pos);
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
        if (fumaroles.length > 0) tag.putLongArray("Fumaroles", fumaroles);
        if (moltenCells.length > 0) tag.putLongArray("MoltenCells", moltenCells);
        tag.putLong("RechargedFor", rechargedFor);
        tag.putLong("RebuiltFor", rebuiltFor);
        if (type != null) tag.putString("Type", type.name());
        if (originalBase != null) tag.putLong("OriginalBase", originalBase.asLong());
        tag.putInt("OriginalSummitY", originalSummitY);
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
        // Absent on any world built before flank fumaroles existed; an empty array simply means
        // that mountain has none, which is the right answer rather than a crash.
        fumaroles = tag.contains("Fumaroles") ? tag.getLongArray("Fumaroles") : new long[0];
        moltenCells = tag.contains("MoltenCells") ? tag.getLongArray("MoltenCells") : new long[0];
        rechargedFor = tag.contains("RechargedFor") ? tag.getLong("RechargedFor") : Long.MIN_VALUE;
        rebuiltFor = tag.contains("RebuiltFor") ? tag.getLong("RebuiltFor") : Long.MIN_VALUE;
        type = readType(tag);
        originalBase = tag.contains("OriginalBase") ? BlockPos.of(tag.getLong("OriginalBase")) : null;
        originalSummitY = tag.contains("OriginalSummitY")
                ? tag.getInt("OriginalSummitY") : Integer.MIN_VALUE;
    }
}
