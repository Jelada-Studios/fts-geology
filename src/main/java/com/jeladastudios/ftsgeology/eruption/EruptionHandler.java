package com.jeladastudios.ftsgeology.eruption;

import com.jeladastudios.ftsgeology.blockentity.GeyserCoreBlockEntity;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
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
 * All spatial/physical side effects of an eruption. Kept stateless (static helpers) — the
 * {@link GeyserCoreBlockEntity} owns simulation state and calls into here.
 *
 * <h2>The vent mouth</h2>
 * Every effect takes a {@link BlockPos} {@code mouth}: the live exit of the vent, resolved each
 * second by {@link VentPathfinder} by tracing the actual open path from the chamber. Because it
 * is re-traced, the mouth <em>moves</em> in response to the player — plug the throat and it
 * blasts back through; cap it and it reroutes or force-breaches. Effects therefore always happen
 * wherever the water is currently getting out.
 */
public final class EruptionHandler {

    private EruptionHandler() {}

    /** Max height the fallback dynamic finder walks above the core before giving up. */
    private static final int MAX_VENT_HEIGHT = 384;

    /**
     * Hard ceiling on the upward velocity any geyser may impose on an entity, per tick. Even if
     * several vents overlap, nothing gets flung past ~10-12 blocks — this is the safety net that
     * keeps a cluster of vents from launching you 100+ blocks into the sky.
     */
    private static final double MAX_UPDRAFT = 1.3;

    /**
     * The jet only grabs entities within this many blocks below the mouth. Without this, a tall
     * surface vent would carry you up its entire shaft (tens of blocks); localising it to the
     * exit keeps the launch to a sane ~8-10 blocks regardless of how deep the vent is.
     */
    private static final int JET_REACH = 8;

    // === Vent geometry ======================================================

    /**
     * Fallback ceiling finder for buried geysers with no stamped surface Y: the Y of the highest
     * open cell straight above the core before the first solid cap. Used as the pathfinder's
     * ceiling when a surface opening wasn't stamped at generation.
     */
    public static int findVentMouthYDynamic(ServerLevel level, BlockPos core) {
        int y = core.getY() + 1;
        int topLimit = Math.min(core.getY() + MAX_VENT_HEIGHT, level.getMaxBuildHeight() - 1);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(core.getX(), y, core.getZ());
        while (y < topLimit) {
            m.setY(y);
            BlockState s = level.getBlockState(m);
            if (s.isSolidRender(level, m) && !isSoftRock(s)) break;
            y++;
        }
        return y - 1;
    }

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
    public static void erodeCrust(ServerLevel level, BlockPos mouth, double pressure) {
        BlockPos capPos = mouth.above();
        if (capPos.getY() >= GeyserConfig.RETROGEN_MAX_Y.get()) return;
        BlockState cap = level.getBlockState(capPos);
        if (!cap.isSolidRender(level, capPos)) return;
        if (isPlayerPlaced(cap)) return;

        if (cap.is(Blocks.DEEPSLATE) || cap.is(Blocks.STONE)) {
            level.setBlock(capPos, Blocks.COBBLED_DEEPSLATE.defaultBlockState(), 3);
        } else if (isSoftRock(cap)) {
            level.setBlock(capPos, Blocks.GRAVEL.defaultBlockState(), 3);
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
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
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

        // The steam pushes you up the ENTIRE shaft: the updraft column runs from the core all the
        // way to the mouth, so if you drop in at the very bottom the jet carries you to the top.
        // The per-tick MAX_UPDRAFT cap still bounds your speed, so this is a steady ride up rather
        // than a launch — no matter how deep the vent is.
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

        // (Water is placed by ventEruption once per second, and only after the mouth reaches the
        // surface — never here per-tick — so a climbing vent doesn't leave a tall water column.)

        // A tight vertical fountain — narrow column, not a wide cloud.
        double x = mouth.getX() + 0.5, z = mouth.getZ() + 0.5;
        int plumeHeight = (int) Math.round((2.0 + 6.0 * decay) * sizeScale);
        for (int i = 0; i <= plumeHeight; i++) {
            level.sendParticles(ParticleTypes.SPLASH, x, mouth.getY() + 1.0 + i, z, 1, 0.06, 0.05, 0.06, 0.01);
        }
        level.sendParticles(ParticleTypes.CLOUD, x, mouth.getY() + 1.0 + plumeHeight, z, 2, 0.08, 0.08, 0.08, 0.02);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, mouth.getY() + 1.5, z, 3, 0.08, 0.25, 0.08, 0.06);
    }

    /**
     * Ejects a real water source block at the mouth so surface mods (Flowing Fluids / Water
     * Erosion) get genuine fluid. Only ever fills air, so it never overwrites terrain or builds.
     */
    public static void ventEruption(ServerLevel level, BlockPos mouth) {
        // Fill the mouth, then lay a small water patch over the surrounding surface — each column at
        // ITS OWN surface height (so it works on slopes) — so a visible pool forms and runs off,
        // rather than the mouth water just draining straight down the open shaft.
        if (level.getBlockState(mouth).isAir()) {
            level.setBlock(mouth, Blocks.WATER.defaultBlockState(), 3);
            level.scheduleTick(mouth, Fluids.WATER, 5);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (level.random.nextDouble() < 0.25) continue; // ~25% less spread water than before
                int cx = mouth.getX() + dx, cz = mouth.getZ() + dz;
                int cy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, cx, cz);
                if (cy > mouth.getY() + 1 || cy < mouth.getY() - 24) continue; // downhill only
                BlockPos p = new BlockPos(cx, cy, cz);
                BlockState below = level.getBlockState(p.below());
                if (level.getBlockState(p).isAir() && !below.isAir() && below.getFluidState().isEmpty()) {
                    level.setBlock(p, Blocks.WATER.defaultBlockState(), 3);
                    level.scheduleTick(p, Fluids.WATER, 5);
                }
            }
        }
    }

    // === Eruption teardown ==================================================

    /** Clears the water source we forced at the mouth so backflow resumes. */
    public static void removeJetField(ServerLevel level, BlockPos mouth) {
        if (level.getBlockState(mouth).getFluidState().is(FluidTags.WATER)) {
            level.setBlock(mouth, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    // === RECHARGING =========================================================

    /** Pulls a fraction of surface water at the mouth down into the vent to cool the chamber. */
    public static void drawSurfaceWaterIn(ServerLevel level, BlockPos mouth, double intakeFraction) {
        boolean surfaceHasWater = level.getBlockState(mouth.above()).getFluidState().is(FluidTags.WATER);
        if (surfaceHasWater && level.random.nextDouble() < intakeFraction) {
            if (level.getBlockState(mouth).isAir()) {
                level.setBlock(mouth, Blocks.WATER.defaultBlockState(), 3);
            }
        }
    }

    // === Calcite chimney / cave sealing =====================================

    /**
     * Walls the four horizontal sides of the vent mouth with calcite (where they're open, water, or
     * soft rock — never a build, never solid natural rock). Called every second while erupting, so:
     * <ul>
     *   <li>when the vent breaks into a <b>cave</b>, the water spilling out is boxed in and can't run
     *       across the cave floor — it's forced to keep rising instead;</li>
     *   <li>as the mouth climbs, each level it leaves behind is ringed, growing a calcite <b>chimney</b>
     *       (a sinter tube) — visible from the very first eruption, while it's still flowing.</li>
     * </ul>
     * The top is always left open, so the spout itself is never capped.
     *
     * <p>Walls a short vertical <em>span</em> down from the mouth — not just the top cell — so a
     * fast-climbing vent (a few blocks per second) still leaves a CONTINUOUS calcite tube instead
     * of disconnected rings floating in a cave.</p>
     */
    public static void buildChimneyRim(ServerLevel level, BlockPos mouth) {
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
                    level.setBlock(p, Blocks.CALCITE.defaultBlockState(), 3);
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
                    level.setBlock(rim, (calcite ? Blocks.CALCITE : Blocks.TUFF).defaultBlockState(), 3);
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
                level.setBlock(ground, (calcite ? Blocks.CALCITE : Blocks.TUFF).defaultBlockState(), 3);
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
     * Conservative "did a player place this?" check. The logical inverse of
     * {@link #isNaturalMatrix}: any naturally-generated rock/soil/sand/fluid (vanilla <em>and</em>
     * modded, recognised by tag) is never treated as a build, so the geyser reliably forms in
     * badlands terracotta, blackstone, dripstone caves and modded biomes — not just vanilla
     * deepslate. Air is carvable, so it is never a "build" either.
     *
     * <p>This is the fix for the long-standing "no water/steam" bug: the old whitelist only knew a
     * handful of vanilla deep stones, so in any other terrain {@code fillLayer} skipped the magma
     * bed and chamber water (thinking they were player blocks), leaving a lone, dead core.</p>
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
        // Ground cover is part of the landscape, not somebody building. Leaving it out was the bug
        // that made earthquakes refuse to move grassy terrain and left gaps in hot-spring walls:
        // a tuft of grass on top of the soil read as a player block, so every safety check bailed.
        if (com.jeladastudios.ftsgeology.worldgen.TerrainProbe.isVegetation(s)) return true;
        // Trees are grown by the world, not built by anybody. Leaving them out made every log and
        // leaf read as a build, which had three visible consequences: earthquakes left forests
        // hanging in mid-air because the rule that puts them back down refused to touch them,
        // volcanoes built their cone around stray trunks instead of through them, and lava stopped
        // at the tree line. The cost is that a log cabin now reads as terrain too - accepted
        // deliberately, since a floating forest is the worse outcome of the two.
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.WART_BLOCKS)
                || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MUSHROOM_STEM)
                || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK)) {
            return true;
        }
        // The mod's own deposits are ground the mod itself laid down, so they have to read as
        // terrain or every safety check treats them as somebody's build and works around them.
        // That is why an earthquake ran straight past a hot-spring field and left it standing on an
        // untouched island: sinter, the mats and the spring bed were all "player blocks" to this.
        //
        // The five functional blocks are deliberately NOT in here. A geyser core, its chamber and
        // the two igniters are machinery rather than landscape; leaving them protected means a
        // quake cannot cut a working geyser in half and leave an orphaned chamber behind.
        if (s.is(ModBlocks.HOT_SPRING.get())
                || s.is(ModBlocks.SINTER.get())
                || s.is(ModBlocks.NATIVE_SULFUR.get())
                || s.is(ModBlocks.MICROBIAL_MAT_ORANGE.get())
                || s.is(ModBlocks.MICROBIAL_MAT_YELLOW.get())
                || s.is(ModBlocks.MICROBIAL_MAT_BROWN.get())
                || s.is(ModBlocks.MICROBIAL_MAT_GREEN.get())) {
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
                || s.is(Tags.Blocks.ORES)) {
            return true;
        }
        // Common vanilla naturals that aren't reliably inside the tag families above.
        return s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.MUD)
                || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.MYCELIUM)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(Blocks.CALCITE) || s.is(Blocks.TUFF) || s.is(Blocks.COBBLED_DEEPSLATE)
                || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.OBSIDIAN)
                || s.is(Blocks.BLACKSTONE) || s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT)
                || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.POINTED_DRIPSTONE)
                || s.is(Blocks.SCULK) || s.is(Blocks.SCULK_VEIN) || s.is(Blocks.SCULK_CATALYST)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE);
    }
}
