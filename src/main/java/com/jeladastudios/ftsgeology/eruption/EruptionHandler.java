package com.jeladastudios.ftsgeology.eruption;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;

import com.jeladastudios.ftsgeology.blockentity.GeyserCoreBlockEntity;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

/**
 * The physical side effects of a geyser eruption, as stateless helpers; {@link GeyserCoreBlockEntity}
 * owns the state. Every effect takes the live vent mouth, re-traced each second by
 * {@link VentPathfinder}, so effects follow wherever the water is currently getting out.
 */
public final class EruptionHandler {

    private EruptionHandler() {}


    /** Ceiling on the upward velocity any geyser gives an entity per tick, so overlapping vents cannot launch it. */
    private static final double MAX_UPDRAFT = 1.3;


    // === Vent geometry ======================================================

    private static boolean isSoftRock(BlockState s) {
        return s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.COBBLED_DEEPSLATE);
    }

    // === HEATING / PRESSURIZING visuals =====================================

    /** Cosy steam smoke leaking from the vent mouth. Intensity scales 0..1. */
    public static void emitSteamWisps(ServerLevel level, BlockPos mouth, float intensity) {
        int count = Math.max(1, Math.round(4 * intensity));
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                mouth.getX() + 0.5, mouth.getY() + 1.0, mouth.getZ() + 0.5, count,
                0.25, 0.1, 0.25, 0.02);
    }

    /**
     * Pre-eruption "fumarole" hazard. While the chamber is pressurising, superheated steam seeps
     * from the vent mouth; entities lingering in the escape zone are scalded and shoved. Damage
     * scales with how close pressure sits to the eruption threshold.
     */
    public static void tickFumaroleHazard(ServerLevel level, BlockPos mouth, GeyserCoreBlockEntity be) {
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                mouth.getX() + 0.5, mouth.getY() + 1.05, mouth.getZ() + 0.5, 3, 0.18, 0.02, 0.18, 0.03);
        level.sendParticles(ParticleTypes.CLOUD,
                mouth.getX() + 0.5, mouth.getY() + 1.2, mouth.getZ() + 0.5, 2, 0.1, 0.1, 0.1, 0.02);

        double ratio = be.getPressure() / GeyserConfig.PRESSURE_ERUPTION_THRESHOLD.get();
        float dmg = (float) Mth.clamp(ratio * 3.0, 1.0, 4.0);

        AABB scaldZone = new AABB(mouth.above()).inflate(0.35, 0.75, 0.35);
        for (Entity e : level.getEntities((Entity) null, scaldZone, EruptionHandler::isScaldable)) {
            ((LivingEntity) e).hurt(level.damageSources().hotFloor(), dmg);
            e.setSecondsOnFire(1);
            Vec3 v = e.getDeltaMovement();
            e.setDeltaMovement(v.x, Math.max(v.y, 0) + 0.35, v.z);
            e.hurtMarked = true;
        }
    }

    private static boolean isScaldable(Entity e) {
        return e instanceof LivingEntity && e.isAlive() && !e.fireImmune();
    }

    /**
     * Weak secondary fumaroles at the branch tips of the root-vent network. Puffs steam while the
     * system is hot; when {@code erupting} it also lightly scalds and nudges anything over a tip.
     */
    public static void tickBranchFumaroles(ServerLevel level, long[] tips, boolean erupting) {
        for (long packed : tips) {
            BlockPos p = BlockPos.of(packed);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                    erupting ? 3 : 1, 0.12, 0.08, 0.12, 0.01);
            // A hot fumarole crusts its rim with sulfur over time - the acidic counterpart to the
            // travertine that alkaline geyser runoff lays down downstream.
            SulfurDeposits.depositAround(level, p);
            if (!erupting) continue;

            AABB zone = new AABB(p).inflate(0.2, 0.5, 0.2);
            for (Entity e : level.getEntities((Entity) null, zone, EruptionHandler::isScaldable)) {
                ((LivingEntity) e).hurt(level.damageSources().hotFloor(), 1.0f);
                Vec3 v = e.getDeltaMovement();
                e.setDeltaMovement(v.x, Math.max(v.y, 0) + 0.2, v.z);
                e.hurtMarked = true;
            }
        }
    }

    /**
     * Cracks the solid rock cap directly above the mouth into gravel/cobbled deepslate as pressure
     * rises — the visible "the ground is about to give" cue on still-buried vents. Never edits at
     * or above the safety ceiling.
     */
    public static void erodeCrust(ServerLevel level, BlockPos mouth) {
        BlockPos capPos = mouth.above();
        if (capPos.getY() >= GeyserConfig.RETROGEN_MAX_Y.get()) return;
        BlockState cap = level.getBlockState(capPos);
        if (!cap.isSolidRender(level, capPos)) return;
        if (isPlayerPlaced(cap)) return;

        if (cap.is(Blocks.DEEPSLATE) || cap.is(Blocks.STONE)) {
            level.setBlock(capPos, TfcCompat.translate(level, capPos, Blocks.COBBLED_DEEPSLATE.defaultBlockState()), 3);
        } else if (isSoftRock(cap)) {
            level.setBlock(capPos, TfcCompat.translate(level, capPos, Blocks.GRAVEL.defaultBlockState()), 3);
        }
    }

    // === Eruption onset =====================================================

    /** Non-destructive blast at the mouth (knockback + sound only) for natural geysers. */
    public static void primaryBlast(ServerLevel level, BlockPos mouth, int magnitude) {
        double power = GeyserConfig.EXPLOSION_POWER.get() * (0.6 + magnitude / 12.0);
        level.explode(null, mouth.getX() + 0.5, mouth.getY() + 0.5, mouth.getZ() + 0.5,
                (float) power, Level.ExplosionInteraction.NONE);
    }

    /**
     * Block-breaking burst for an emergent (player-built) eruption — the "it blows its lid" payoff.
     * Punches a narrow vertical column <em>upward</em> through whatever is capping the vent (the
     * blocks on top), rather than detonating a big sphere. Breaks blocks directly so it works even
     * underwater (where a normal explosion is absorbed). Never breaks bedrock or fluids.
     */
    public static void destructiveBurst(ServerLevel level, BlockPos mouth, int magnitude) {
        int rad = Mth.clamp(GeyserConfig.EMERGENT_DESTROY_RADIUS.get(), 0, 3); // half-width of the throat
        int height = 3 + magnitude / 3;                                            // how far up it clears
        for (int dy = 0; dy <= height; dy++) {
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    BlockPos p = mouth.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || s.is(Blocks.BEDROCK)) continue;
                    if (!s.getFluidState().isEmpty()) continue;   // leave water/lava; only clear solids
                    level.destroyBlock(p, false);                 // no drops; unaffected by water
                }
            }
        }
        level.explode(null, mouth.getX() + 0.5, mouth.getY() + 1.0, mouth.getZ() + 0.5,
                1.0F, Level.ExplosionInteraction.NONE);           // small pop for sound/knockback
    }

    /**
     * The violent first instant of an eruption. Flings any water already sitting at the mouth (a
     * pond the vent surfaced into), flashes some to steam, hurls nearby entities sharply upward
     * (a one-time ~30-40 block launch) and singes them. The {@link #tickJetField} burst decay
     * then takes over and it settles into the steady spout.
     */
    public static void onsetWaterBurst(ServerLevel level, BlockPos mouth, int magnitude) {
        double sizeScale = 0.7 + magnitude / 20.0;
        int radius = Mth.clamp(magnitude / 3, 2, 8);

        double launch = Math.min(GeyserConfig.ONSET_LAUNCH_VELOCITY.get() * sizeScale, MAX_UPDRAFT);
        AABB blastDome = new AABB(mouth).inflate(radius, radius + 2.0, radius);
        for (Entity e : level.getEntities((Entity) null, blastDome, EruptionHandler::isScaldable)) {
            double dist = Math.sqrt(e.distanceToSqr(mouth.getX() + 0.5, mouth.getY() + 0.5, mouth.getZ() + 0.5));
            double falloff = Math.max(0.25, 1.0 - dist / (radius + 3.0));
            Vec3 v = e.getDeltaMovement();
            e.setDeltaMovement(v.x, Math.min(Math.max(v.y, launch * falloff), MAX_UPDRAFT), v.z);
            e.hurtMarked = true;
            e.fallDistance = 0;
            ((LivingEntity) e).hurt(level.damageSources().hotFloor(), (float) (3.0 + magnitude * 0.2));
            e.setSecondsOnFire(2);
        }

        int scatter = (int) Math.round(GeyserConfig.ONSET_WATER_SCATTER.get() * sizeScale);
        int flung = 0;
        for (int attempt = 0; attempt < scatter * 3 && flung < scatter; attempt++) {
            int dx = level.random.nextInt(radius * 2 + 1) - radius;
            int dz = level.random.nextInt(radius * 2 + 1) - radius;
            int dy = level.random.nextInt(3);
            BlockPos p = mouth.offset(dx, dy, dz);
            if (level.getBlockState(p).getFluidState().is(FluidTags.WATER)) {
                level.setBlock(p, TfcCompat.translate(level, p, Blocks.AIR.defaultBlockState()), 2);
                level.sendParticles(ParticleTypes.SPLASH,
                        p.getX() + 0.5, p.getY() + 0.8, p.getZ() + 0.5, 10, 0.25, 0.35, 0.25, 0.25);
                level.sendParticles(ParticleTypes.CLOUD,
                        p.getX() + 0.5, p.getY() + 1.2, p.getZ() + 0.5, 4, 0.2, 0.2, 0.2, 0.05);
                flung++;
            }
        }

        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                mouth.getX() + 0.5, mouth.getY() + 3.0, mouth.getZ() + 0.5, 12, 0.15, 1.2, 0.15, 0.12);
        level.sendParticles(ParticleTypes.SPLASH,
                mouth.getX() + 0.5, mouth.getY() + 2.0, mouth.getZ() + 0.5, 12, 0.15, 1.0, 0.15, 0.15);
    }

    // === Per-tick jet field (ERUPTING) ======================================

    /**
     * The "jet effect": each tick, impose an updraft on entities in the vent column below the
     * mouth and keep the mouth cell water-filled so surface fluid can't drain back mid-eruption.
     */
    public static void tickJetField(ServerLevel level, BlockPos core, BlockPos mouth, GeyserCoreBlockEntity be) {
        double sizeScale = 0.7 + be.getMagnitude() / 20.0;
        double decay = Math.exp(-be.getEruptionTicks() / (double) GeyserConfig.JET_BURST_DECAY_TICKS.get());
        double sustained = GeyserConfig.JET_UPWARD_VELOCITY.get() * sizeScale;
        double burst = GeyserConfig.JET_BURST_VELOCITY.get() * sizeScale * decay;
        double vy = Math.min(sustained + burst, MAX_UPDRAFT);

        // The updraft runs the whole shaft, capped per tick, so anything dropped in rides up to the mouth.
        double bottom = core.getY();
        AABB column = new AABB(
                mouth.getX(), bottom, mouth.getZ(),
                mouth.getX() + 1.0, mouth.getY() + 2.0, mouth.getZ() + 1.0
        ).inflate(0.35, 0.0, 0.35);

        for (Entity e : level.getEntities((Entity) null, column, ent -> true)) {
            Vec3 v = e.getDeltaMovement();
            // Cap the imposed updraft so overlapping vents can't compound into a mega-launch.
            e.setDeltaMovement(v.x * 0.9, Math.min(Math.max(v.y, vy), MAX_UPDRAFT), v.z * 0.9);
            e.hurtMarked = true;
            e.fallDistance = 0;
        }

        // The mouth source is placed once a second; the flowing runoff sheet is re-laid every tick.
        refreshRunoff(level, mouth);

        // Particles fired from the mouth with real upward velocity, spreading into fog as they slow.
        double x = mouth.getX() + 0.5, z = mouth.getZ() + 0.5;
        double y = mouth.getY() + 1.0;
        double power = (0.9 + 1.4 * decay) * sizeScale;

        for (int i = 0; i < 6; i++) {
            // sendParticles with count 0 is the only way to give a particle a chosen velocity: the
            // offsets are read as the motion vector instead of as a spread. Hence one call each.
            level.sendParticles(com.jeladastudios.ftsgeology.registry.ModParticles.GEYSER_MIST.get(),
                    x + (level.random.nextDouble() - 0.5) * 0.4,
                    y,
                    z + (level.random.nextDouble() - 0.5) * 0.4,
                    0,
                    (level.random.nextDouble() - 0.5) * 0.06,
                    power * (0.75 + level.random.nextDouble() * 0.5),
                    (level.random.nextDouble() - 0.5) * 0.06,
                    1.0);
        }
        // Boiling at the mouth itself, where the water is still liquid.
        level.sendParticles(ParticleTypes.SPLASH, x, y, z, 4, 0.25, 0.05, 0.25, 0.02);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y + 0.5, z, 2, 0.2, 0.2, 0.2, 0.03);
    }

    /**
     * Ejects a real water source block at the mouth so surface mods (Flowing Fluids / Water
     * Erosion) get genuine fluid. Only ever fills air, so it never overwrites terrain or builds.
     */
    public static void ventEruption(ServerLevel level, BlockPos mouth, LongOpenHashSet spilled) {
        // Fill the mouth, and once in vanilla lay a small patch of sources on the ground around it, each
        // recorded in spilled so the eruption can take it back and no infinite pool is left.
        if (level.getBlockState(mouth).isAir()) {
            level.setBlock(mouth, TfcCompat.translate(level, mouth, Blocks.WATER.defaultBlockState()), 3);
            level.scheduleTick(mouth, Fluids.WATER, 5);
        }

        // Vanilla only, and only on the first second; finite-water mods get the mouth alone.
        if (hasFiniteWater() || spilled == null || !spilled.isEmpty()) return;
        if (!level.getBlockState(mouth.above()).isAir()) return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int cx = mouth.getX() + dx, cz = mouth.getZ() + dz;
                int cy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, cx, cz);
                if (cy > mouth.getY() + 1 || cy < mouth.getY() - 24) continue;   // downhill only
                BlockPos p = new BlockPos(cx, cy, cz);
                BlockState below = level.getBlockState(p.below());
                if (!level.getBlockState(p).isAir()) continue;
                if (below.isAir() || !below.getFluidState().isEmpty()) continue;
                level.setBlock(p, TfcCompat.translate(level, p, Blocks.WATER.defaultBlockState()), 3);
                spilled.add(p.asLong());
            }
        }
    }

    /** True when a mod that gives water real physics (Flowing Fluids) is installed. Resolved once. */
    private static Boolean finiteWater;

    public static boolean hasFiniteWater() {
        if (finiteWater == null) {
            finiteWater = net.minecraftforge.fml.ModList.get().isLoaded("flowing_fluids");
        }
        return finiteWater;
    }

    /**
     * Keeps a sheet of flowing water around the vent while it spouts. Flowing, not sources: two sources
     * side by side make vanilla water infinite, and the geyser would never stop running.
     */
    public static void refreshRunoff(ServerLevel level, BlockPos mouth) {
        // With finite water the pool is that mod's to move.
        if (hasFiniteWater()) return;

        // Only once the vent is open at the surface, or water would stand in a column.
        if (!level.getBlockState(mouth.above()).isAir()) return;

        BlockState flowing = Blocks.WATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 1);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int cx = mouth.getX() + dx, cz = mouth.getZ() + dz;
                int cy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, cx, cz);
                if (cy > mouth.getY() + 1 || cy < mouth.getY() - 24) continue; // downhill only
                BlockPos p = new BlockPos(cx, cy, cz);
                BlockState at = level.getBlockState(p);
                // Never touch standing water that was already there.
                if (!at.isAir() && !(at.is(Blocks.WATER) && !at.getFluidState().isSource())) continue;
                BlockState below = level.getBlockState(p.below());
                if (below.isAir() || !below.getFluidState().isEmpty()) continue;
                level.setBlock(p, TfcCompat.translate(level, p, flowing), 2);
            }
        }
    }

    // === Eruption teardown ==================================================

    /**
     * Takes back the water the eruption put down, so the geyser stops flowing when it stops: only cells
     * this eruption filled, plus water vanilla promoted between two of them.
     */
    public static void removeJetField(ServerLevel level, BlockPos mouth, LongOpenHashSet spilled) {
        if (level.getBlockState(mouth).getFluidState().is(FluidTags.WATER)) {
            level.setBlock(mouth, TfcCompat.translate(level, mouth, Blocks.AIR.defaultBlockState()), 3);
        }
        if (spilled == null) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (long key : spilled) {
            int bx = BlockPos.getX(key), by = BlockPos.getY(key), bz = BlockPos.getZ(key);
            // Take the cell back, and any neighbour vanilla promoted to a source between two of ours.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    m.set(bx + dx, by, bz + dz);
                    if (!level.hasChunkAt(m)) continue;
                    boolean ours = dx == 0 && dz == 0;
                    if (!ours && !spilled.contains(m.asLong()) && !isPromoted(level, m, spilled)) continue;
                    if (level.getBlockState(m).is(Blocks.WATER)) {
                        level.setBlock(m.immutable(), TfcCompat.translate(level, m.immutable(), Blocks.AIR.defaultBlockState()), 3);
                    }
                }
            }
        }
        spilled.clear();
    }

    /** A water cell touching two cells this eruption filled is water this eruption is answerable for. */
    private static boolean isPromoted(ServerLevel level, BlockPos p, LongOpenHashSet spilled) {
        if (!level.getBlockState(p).is(Blocks.WATER)) return false;
        int touching = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (spilled.contains(p.relative(d).asLong())) touching++;
        }
        return touching >= 2;
    }

    // === RECHARGING =========================================================

    /** Pulls a fraction of surface water at the mouth down into the vent to cool the chamber. */
    public static void drawSurfaceWaterIn(ServerLevel level, BlockPos mouth, double intakeFraction) {
        boolean surfaceHasWater = level.getBlockState(mouth.above()).getFluidState().is(FluidTags.WATER);
        if (surfaceHasWater && level.random.nextDouble() < intakeFraction) {
            if (level.getBlockState(mouth).isAir()) {
                level.setBlock(mouth, TfcCompat.translate(level, mouth, Blocks.WATER.defaultBlockState()), 3);
            }
        }
    }

    // === Calcite chimney / cave sealing =====================================

    /** Takes any tree off the top of a vent, which the chimney rim would otherwise leave balanced over the mouth. */
    public static void clearVentCanopy(ServerLevel level, BlockPos mouth) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 12; dy++) {
                    BlockPos p = mouth.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(p);
                    if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)
                            || s.is(Blocks.MANGROVE_ROOTS)) {
                        level.setBlock(p, TfcCompat.translate(level, p, Blocks.AIR.defaultBlockState()), 2);
                    }
                }
            }
        }
    }

    /**
     * Walls the sides of the vent mouth, and a few blocks below it, with calcite where they are open,
     * water or soft rock: a breakout into a cave is boxed in and forced upward, and a climbing vent grows
     * a continuous sinter tube. The top stays open.
     */
    public static void buildChimneyRim(ServerLevel level, BlockPos mouth) {
        clearVentCanopy(level, mouth);
        for (int dy = 0; dy <= CHIMNEY_WALL_SPAN; dy++) {
            BlockPos ring = mouth.below(dy);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos p = ring.relative(d);
                BlockState s = level.getBlockState(p);
                if (isPlayerPlaced(s)) continue;             // never wall over a build
                if (s.is(Blocks.BEDROCK)) continue;
                boolean fillable = s.isAir()
                        || s.getFluidState().is(FluidTags.WATER)
                        || isSoftRock(s);
                if (fillable) {
                    level.setBlock(p, TfcCompat.translate(level, p, Blocks.CALCITE.defaultBlockState()), 3);
                }
            }
        }
    }

    /** How far below the mouth {@link #buildChimneyRim} also walls, to keep the tube continuous. */
    private static final int CHIMNEY_WALL_SPAN = 4;

    // === Mineral cone (F) ===================================================

    /**
     * After enough eruptions, ring the vent mouth with Tuff/Calcite, building a raised cone that
     * diverts most water to run off (feeding erosion mods) while a little drips back in. Radius
     * scales with magnitude.
     */
    public static void depositMineralCone(ServerLevel level, BlockPos mouth, int magnitude) {
        int radius = Mth.clamp(magnitude / 5, 1, 4);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (dx * dx + dz * dz > radius * radius + radius) continue;
                BlockPos rim = mouth.offset(dx, 0, dz);
                BlockState s = level.getBlockState(rim);
                if (isPlayerPlaced(s)) continue;
                if (s.isAir() || isSoftRock(s) || s.getFluidState().is(FluidTags.WATER)) {
                    boolean calcite = level.random.nextBoolean();
                    level.setBlock(rim, TfcCompat.translate(level, rim, (calcite ? Blocks.CALCITE : Blocks.TUFF).defaultBlockState()), 3);
                }
            }
        }
    }

    // === Travertine / sinter runoff (F) =====================================

    /**
     * Precipitates travertine (Calcite/Tuff) at the edges of the runoff pools around the mouth,
     * building sinter terraces over long eruptions.
     *
     * <p><b>Water Erosion compatibility:</b> deposits happen only under settled <em>source</em>
     * water at the rim, never the fast flowing channel that Water Erosion carves — and Calcite/Tuff
     * aren't in that mod's erodable set, so the two never fight.</p>
     */
    public static void depositTravertineRunoff(ServerLevel level, BlockPos mouth, int magnitude) {
        if (!GeyserConfig.TRAVERTINE_ENABLED.get()) return;
        int radius = Mth.clamp(magnitude / 2, 3, 12);
        double chance = GeyserConfig.TRAVERTINE_DEPOSIT_CHANCE.get();

        int dx = level.random.nextInt(radius * 2 + 1) - radius;
        int dz = level.random.nextInt(radius * 2 + 1) - radius;
        if (dx == 0 && dz == 0) return;

        for (int dy = 1; dy >= -2; dy--) {
            BlockPos p = mouth.offset(dx, dy, dz);
            FluidState fs = level.getBlockState(p).getFluidState();
            if (!(fs.is(FluidTags.WATER) && fs.isSource())) continue;
            BlockPos ground = p.below();
            BlockState g = level.getBlockState(ground);
            if (g.is(Blocks.CALCITE) || g.is(Blocks.TUFF)) return;
            if (g.isAir() || !isNaturalTerrain(g)) return;
            if (level.random.nextDouble() < chance) {
                boolean calcite = level.random.nextBoolean();
                level.setBlock(ground, TfcCompat.translate(level, ground, (calcite ? Blocks.CALCITE : Blocks.TUFF).defaultBlockState()), 3);
            }
            return;
        }
    }

    // === Block classification ===============================================

    /**
     * Wide "untouched world, not a build?" check for surface terrain / a full-height column.
     * Accepts all naturally-generated terrain — vanilla and modded — via {@link #isNaturalMatrix}.
     * Anything else (planks, glass, concrete, bricks, logs, etc.) is treated as a build.
     */
    public static boolean isNaturalTerrain(BlockState s) {
        return isNaturalMatrix(s);
    }

    // === Build-protection heuristic =========================================

    /**
     * Did a player place this? The inverse of {@link #isNaturalMatrix}: natural rock, soil, sand, fluid,
     * vegetation and trees, vanilla or modded by tag, never count as a build.
     */
    public static boolean isPlayerPlaced(BlockState s) {
        if (s.isAir()) return false;
        return !isNaturalMatrix(s);
    }

    /**
     * The broad recognizer for naturally-generated world material (vanilla + modded). Leans on
     * tag families so modded stones/soils that opt in are covered, plus explicit vanilla blocks
     * that aren't reliably tagged. Any fluid (source or flowing) counts as natural.
     */
    private static boolean isNaturalMatrix(BlockState s) {
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty()) return true; // any water/lava, source or flowing
        // Ground cover is landscape, not a build.
        if (com.jeladastudios.ftsgeology.worldgen.TerrainProbe.isVegetation(s)) return true;
        if (TfcCompat.isGround(s)) return true;
        // Trees are landscape too, or quakes leave forests hanging; a log cabin counts as terrain as a result.
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.WART_BLOCKS)
                || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MUSHROOM_STEM)
                || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK)) {
            return true;
        }
        // The mod's own raw deposits and rocks are landscape. Machinery (cores, chambers, igniters) and
        // worked forms (polished, slabs, stairs) are not. A new raw block must be added here, or quakes
        // and eruptions will treat it as a build.
        if (s.is(ModBlocks.HOT_SPRING.get())
                || s.is(ModBlocks.SINTER.get())
                || s.is(ModBlocks.SINTER_CRUST.get())
                || s.is(ModBlocks.MUD_POT.get())
                || s.is(ModBlocks.STEAM_VENT.get())
                || s.is(ModBlocks.VOLCANIC_ASH.get())
                || s.is(ModBlocks.NATIVE_SULFUR.get())
                || s.is(ModBlocks.COOLING_LAVA_CRUST.get())
                || s.is(ModBlocks.MICROBIAL_MAT_ORANGE.get())
                || s.is(ModBlocks.MICROBIAL_MAT_YELLOW.get())
                || s.is(ModBlocks.MICROBIAL_MAT_BROWN.get())
                || s.is(ModBlocks.MICROBIAL_MAT_GREEN.get())) {
            return true;
        }
        // The rock family and the ores that go with it: raw stone the world is made of.
        if (s.is(ModBlocks.TRAVERTINE.get()) || s.is(ModBlocks.RHYOLITE.get())
                || s.is(ModBlocks.GABBRO.get()) || s.is(ModBlocks.PERIDOTITE.get())
                || s.is(ModBlocks.SERPENTINITE.get()) || s.is(ModBlocks.SCHIST.get())
                || s.is(ModBlocks.GNEISS.get()) || s.is(ModBlocks.SLATE.get())
                || s.is(ModBlocks.MARBLE.get()) || s.is(ModBlocks.QUARTZITE.get())
                || s.is(ModBlocks.SHALE.get()) || s.is(ModBlocks.CHERT.get())
                || s.is(ModBlocks.PYRITE.get()) || s.is(ModBlocks.CHALCOPYRITE.get())
                || s.is(ModBlocks.MALACHITE.get()) || s.is(ModBlocks.AZURITE.get())
                || s.is(ModBlocks.QUARTZ_VEIN.get()) || s.is(ModBlocks.CINNABAR.get())
                || s.is(ModBlocks.GALENA.get())) {
            return true;
        }
        if (s.is(BlockTags.BASE_STONE_OVERWORLD)      // stone, granite, diorite, andesite, tuff, deepslate (+modded)
                || s.is(BlockTags.BASE_STONE_NETHER)  // netherrack, basalt, blackstone (+modded)
                || s.is(BlockTags.DIRT)
                || s.is(BlockTags.SAND)
                || s.is(BlockTags.SNOW)
                || s.is(BlockTags.ICE)
                || s.is(BlockTags.TERRACOTTA)         // badlands
                || s.is(BlockTags.STONE_ORE_REPLACEABLES)
                || s.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || s.is(Tags.Blocks.STONE)            // Forge stone tag (broad modded coverage)
                || s.is(Tags.Blocks.COBBLESTONE)      // the boulders of an old-growth taiga
                || s.is(Tags.Blocks.GRAVEL)
                || s.is(Tags.Blocks.SAND)
                || s.is(Tags.Blocks.ORES)) {
            return true;
        }
        // Common vanilla naturals that aren't reliably inside the tag families above.
        return s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.MUD)
                || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.MYCELIUM)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(Blocks.CALCITE) || s.is(Blocks.TUFF) || s.is(Blocks.COBBLED_DEEPSLATE)
                || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE)
                || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.OBSIDIAN)
                || s.is(Blocks.BLACKSTONE) || s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT)
                || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.POINTED_DRIPSTONE)
                || s.is(Blocks.AMETHYST_BLOCK) || s.is(Blocks.BUDDING_AMETHYST) || s.is(Blocks.AMETHYST_CLUSTER)
                || s.is(Blocks.LARGE_AMETHYST_BUD) || s.is(Blocks.MEDIUM_AMETHYST_BUD) || s.is(Blocks.SMALL_AMETHYST_BUD)
                || s.is(Blocks.MUDDY_MANGROVE_ROOTS) || s.is(Blocks.BONE_BLOCK)
                || s.is(Blocks.SCULK) || s.is(Blocks.SCULK_VEIN) || s.is(Blocks.SCULK_CATALYST)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE);
    }
}
