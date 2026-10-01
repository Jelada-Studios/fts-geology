package com.jeladastudios.ftsgeology.gas.event;

import com.jeladastudios.ftsgeology.gas.GasConfig;
import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.gas.command.GasCommand;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.registry.GasTags;
import com.jeladastudios.ftsgeology.gas.world.GasChunkData;
import com.jeladastudios.ftsgeology.gas.world.GasHealth;
import com.jeladastudios.ftsgeology.gas.world.GasManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = GeysersMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonEvents {
    private static final ResourceLocation GAS_CAP_ID = new ResourceLocation(GeysersMod.MODID, "gas");

    private CommonEvents() {
    }

    /** The gases at all (see {@link GasConfig#ENABLED}); read on the server only, where the setting lives. */
    private static boolean on() {
        return GasConfig.ENABLED.get();
    }

    // ---------------------------------------------------------------- storage & ticking

    @SubscribeEvent
    public static void attachChunkCaps(AttachCapabilitiesEvent<LevelChunk> event) {
        LevelChunk chunk = event.getObject();
        if (chunk.getLevel() == null || chunk.getLevel().isClientSide) return;
        GasChunkData data = new GasChunkData(chunk);
        event.addCapability(GAS_CAP_ID, data);
        event.addListener(data::invalidate);
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        chunk.getCapability(GasChunkData.CAPABILITY).ifPresent(d -> {
            // Saved before the gases came: an old world's chunk keeps its air as it was.
            java.util.Set<Long> old = OLD.get(level.dimension());
            if (old != null && old.remove(chunk.getPos().toLong())) d.seeded = true;
            GasManager.get(level).onChunkLoad(d);
        });
    }

    /** Chunks read from disk without any gas data of theirs: saved before the gases were in the game. */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, java.util.Set<Long>> OLD =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Off the server thread too: chunks are read from disk on the workers. */
    @SubscribeEvent
    public static void onChunkDataLoad(net.minecraftforge.event.level.ChunkDataEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        net.minecraft.nbt.CompoundTag tag = event.getData();
        if (!"minecraft:full".equals(tag.getString("Status")) && !"full".equals(tag.getString("Status"))) return;
        if (tag.getCompound("ForgeCaps").contains(GAS_CAP_ID.toString())) return;
        OLD.computeIfAbsent(level.dimension(), k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(event.getChunk().getPos().toLong());
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        GasManager.get(level).onChunkUnload(chunk);
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || !on()) return;
        GasManager.get(level).tick();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) GasManager.unload(level);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        OLD.clear();
        com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.gas.world.GasFields.summary());
        com.jeladastudios.ftsgeology.gas.world.GasFields.clear();
        GasManager.clearAll();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        GasCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level().isClientSide || !on()) return;
        GasHealth.tick(event.getEntity());
    }

    // ---------------------------------------------------------------- ignition sources

    private static boolean isIgniter(ItemStack stack) {
        return stack.getItem() instanceof FlintAndSteelItem || stack.getItem() instanceof FireChargeItem;
    }

    /** Striking flint and steel (a "lighter") on a block. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isIgniter(event.getItemStack()) || !on()) return;
        GasManager mgr = GasManager.get(level);
        Direction face = event.getFace();
        BlockPos pos = event.getPos();
        Player player = event.getEntity();
        spark(mgr, player, face != null ? pos.relative(face) : pos);
    }

    /**
     * A spark at a place lights the nearest flammable gas round it, a block either way, else round the striker's head;
     * where there is gas that will not burn, the striker is told why.
     */
    private static void spark(GasManager mgr, Player player, BlockPos at) {
        double[] why = new double[2];
        GasManager.Spark s = mgr.spark(at, 1, player, why);
        if (s == GasManager.Spark.LIT) return;
        double[] why2 = new double[2];
        GasManager.Spark t = mgr.spark(BlockPos.containing(player.getEyePosition()), 1, player, why2);
        if (t == GasManager.Spark.LIT) return;
        if (s == GasManager.Spark.NONE) {
            s = t;
            why = why2;
        }
        if (s == GasManager.Spark.NONE) return;
        player.displayClientMessage(Component.translatable(s == GasManager.Spark.LEAN ? "message.fts_geology.gas.spark_lean"
                        : "message.fts_geology.gas.spark_rich", String.format(java.util.Locale.ROOT, "%.1f", why[0] * 100),
                String.format(java.util.Locale.ROOT, "%.0f", why[1] * 100)).withStyle(net.minecraft.ChatFormatting.YELLOW), true);
    }

    /** Striking flint and steel into the air still makes sparks. */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getItemStack().getItem() instanceof FlintAndSteelItem) || !on()) return;
        Player player = event.getEntity();
        Vec3 spark = player.getEyePosition().add(player.getLookAngle().scale(0.8));
        level.playSound(null, player.blockPosition(), SoundEvents.FLINTANDSTEEL_USE, SoundSource.PLAYERS, 1.0f, 1.0f + level.random.nextFloat() * 0.3f);
        level.sendParticles(ParticleTypes.SMALL_FLAME, spark.x, spark.y, spark.z, 3, 0.05, 0.05, 0.05, 0.01);
        GasManager mgr = GasManager.get(level);
        // No wear: vanilla's flint and steel only wears on what it lights.
        spark(mgr, player, BlockPos.containing(spark));
    }

    /** Placing a torch, candle, campfire... in or next to flammable gas. */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !on()) return;
        GasManager mgr = GasManager.get(level);
        if (!mgr.isIgnitionSource(event.getPlacedBlock())) return;
        BlockPos pos = event.getPos();
        if (mgr.ignite(pos, event.getEntity())) return;
        for (Direction d : Direction.values()) {
            if (mgr.ignite(pos.relative(d), event.getEntity())) return;
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !on()) return;
        // An oil field's rock broken into lets its gas out, whoever breaks it.
        com.jeladastudios.ftsgeology.gas.world.GasFields.broken(level, event.getPos(), event.getState());
        Player player = event.getPlayer();
        if (player.isCreative()) return;
        GasManager mgr = GasManager.get(level);
        BlockState state = event.getState();
        BlockPos pos = event.getPos();

        // Coal seams hold adsorbed methane that escapes when the coal is broken.
        if (GasConfig.COAL_RELEASES_METHANE.get() && state.is(BlockTags.COAL_ORES) && pos.getY() < 48) {
            double depth = 1.0 + Math.max(0, 16 - pos.getY()) / 24.0;
            double amount = (0.5 + level.random.nextDouble() * 2.0) * depth;
            if (pos.getY() < 0 && level.random.nextDouble() < GasConfig.OUTBURST_CHANCE.get()) {
                amount = 80 + level.random.nextDouble() * 220;
                level.playSound(null, pos, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 1.5f, 0.5f);
                level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 25, 0.4, 0.4, 0.4, 0.15);
                player.displayClientMessage(Component.translatable("fts_geology.gas.outburst").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
            }
            GasMix gas = new GasMix();
            gas.add(Gas.METHANE, amount);
            gas.add(Gas.CARBON_DIOXIDE, amount * 0.03);
            mgr.releaseLater(pos, gas, 1);
        }

        // Frictional sparks from the pick.
        if (state.is(GasTags.SPARKING_BLOCKS) && level.random.nextDouble() < GasConfig.MINING_SPARK_CHANCE.get()) {
            for (Direction d : Direction.values()) {
                if (mgr.ignite(pos.relative(d), player)) {
                    level.sendParticles(ParticleTypes.LAVA, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0);
                    return;
                }
            }
        }
    }

    /** A block changed: the sleeping gas at it and beside it is simulated again (it may have room, or less). */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !on()) return;
        GasManager.get(level).wakeAround(event.getPos());
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !on()) return;
        GasManager.get(level).igniteAround(BlockPos.containing(event.getExplosion().getPosition()), 3, null);
        // A blast that is not the gas's own blows out the standing flames it reaches, as a well fire is put out.
        if (!event.getExplosion().getDamageSource().is(com.jeladastudios.ftsgeology.gas.registry.GasDamageTypes.GAS_EXPLOSION)) {
            double reach = 2.0;
            for (BlockPos p : event.getAffectedBlocks()) reach = Math.max(reach, p.getCenter().distanceTo(event.getExplosion().getPosition()));
            GasManager.get(level).flames().blowOut(event.getExplosion().getPosition(), Math.min(reach, 12.0));
        }
        int n = 0;
        for (BlockPos p : event.getAffectedBlocks()) {
            if (n++ > 256) break;
            com.jeladastudios.ftsgeology.gas.world.GasFields.broken(level, p, level.getBlockState(p));
        }
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile proj = event.getProjectile();
        if (!(proj.level() instanceof ServerLevel level) || !on()) return;
        if (!(proj instanceof AbstractHurtingProjectile) && !proj.isOnFire()) return;
        GasManager.get(level).igniteAround(BlockPos.containing(event.getRayTraceResult().getLocation()), 1, proj.getOwner());
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof LightningBolt bolt && event.getLevel() instanceof ServerLevel level && on()) {
            GasManager.get(level).igniteAround(bolt.blockPosition(), 3, null);
        }
    }
}
