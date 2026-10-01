package com.jeladastudios.ftsgeology.gas.block.entity;

import com.jeladastudios.ftsgeology.gas.GasConfig;
import com.jeladastudios.ftsgeology.gas.block.GasValveBlock;
import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.GasText;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import com.jeladastudios.ftsgeology.gas.registry.GasDamageTypes;
import com.jeladastudios.ftsgeology.gas.world.GasManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public class GasValveBlockEntity extends GasMachineBlockEntity {
    /** Max flow from a pressurised source at full throttle (mol/tick). */
    private static final double MAX_FLOW = 2.0;
    /** Flow per atm of pressure difference from a pipe or tank (mol/tick). */
    private static final double PIPE_CONDUCTANCE = 0.25;
    /** Draw from the room behind the valve per atm of room pressure (mol/tick). */
    private static final double WORLD_DRAW = 0.6;

    private int throttle = 4; // quarters
    private boolean powered;
    private double lastFlow;
    private double lastHeat;
    private int jetLength;
    private GasMix lastStream = new GasMix();

    public GasValveBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.GAS_VALVE.get(), pos, state);
    }

    @Override
    public @Nullable GasTank tankFor(@Nullable Direction side) {
        return null; // a valve has no volume of its own; it only connects pipes visually
    }

    @Override
    public boolean connectsOn(@Nullable Direction side) {
        return side != null && side.getAxis() == facing().getAxis();
    }

    private boolean isOpen() {
        return getBlockState().getValue(GasValveBlock.OPEN);
    }

    private boolean isLit() {
        return getBlockState().getValue(GasValveBlock.LIT);
    }

    private void setOpen(boolean open) {
        BlockState s = getBlockState();
        if (s.getValue(GasValveBlock.OPEN) == open) return;
        s = s.setValue(GasValveBlock.OPEN, open);
        if (!open) s = s.setValue(GasValveBlock.LIT, false);
        level.setBlock(worldPosition, s, 3);
        level.playSound(null, worldPosition, open ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.6f, 1.4f);
    }

    public void onRedstone(boolean signal) {
        if (signal != powered) {
            powered = signal;
            setOpen(signal);
            setChanged();
        }
    }

    @Override
    public InteractionResult onUse(Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof FlintAndSteelItem || held.getItem() instanceof FireChargeItem) {
            tryLight(player, held, hand);
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown()) {
            throttle = throttle % 4 + 1;
            setChanged();
            player.displayClientMessage(Component.translatable("block.fts_geology.gas_valve.throttle", throttle * 25), true);
            return InteractionResult.CONSUME;
        }
        if (held.isEmpty()) {
            setOpen(!isOpen());
            player.displayClientMessage(Component.translatable(isOpen() ? "block.fts_geology.gas_valve.opened" : "block.fts_geology.gas_valve.closed"), true);
            return InteractionResult.CONSUME;
        }
        return super.onUse(player, hand, hit);
    }

    private void tryLight(Player player, ItemStack held, InteractionHand hand) {
        level.playSound(null, worldPosition, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0f, 1.0f);
        if (held.getItem() instanceof FlintAndSteelItem) held.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
        else if (!player.getAbilities().instabuild) held.shrink(1);
        Direction f = facing();
        // The spark itself may set off gas already in front of the nozzle.
        gas().ignite(worldPosition.relative(f), player);
        if (!isOpen()) {
            player.displayClientMessage(Component.translatable("block.fts_geology.gas_valve.need_open").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        GasMix preview = sourcePreview();
        if (preview == null || !Combustion.canSustainJet(preview)) {
            player.displayClientMessage(Component.translatable("block.fts_geology.gas_valve.no_fuel").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        setLit(true);
        level.playSound(null, worldPosition, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.0f, 0.8f);
    }

    /** What the valve would currently draw, without drawing it. */
    @Nullable
    private GasMix sourcePreview() {
        Direction back = facing().getOpposite();
        GasTank src = neighbourTank(back);
        if (src != null) return src.gas;
        if (worldOpen(back)) return gas().sample(worldPosition.relative(back));
        return null;
    }

    @Override
    public void serverTick() {
        if (!isOpen()) {
            lastFlow = 0;
            lastHeat = 0;
            jetLength = 0;
            return;
        }
        GasManager gm = gas();
        Direction f = facing();
        Direction back = f.getOpposite();
        BlockPos backPos = worldPosition.relative(back);
        BlockPos frontPos = worldPosition.relative(f);
        double t = throttle * 0.25;

        GasTank dst = neighbourTank(f);
        boolean nozzle = dst == null;
        if (nozzle && !worldOpen(f)) {
            // Outlet blocked (wall, water...): nothing can flow, and a flame dies.
            if (isLit()) setLit(false);
            lastFlow = 0;
            return;
        }
        double pOut = nozzle ? 1.0 : dst.pressure();

        GasTank src = neighbourTank(back);
        boolean worldSource = false;
        GasMix stream;
        if (src != null) {
            double dp = src.pressure() - pOut;
            double flow = dp <= 0 ? 0 : Math.min(MAX_FLOW * t, PIPE_CONDUCTANCE * dp * t);
            // Never overshoot the pressure balance.
            double balance = dp * Gas.MOL_PER_BLOCK * (nozzle ? src.volume : src.volume * dst.volume / (src.volume + dst.volume));
            flow = Math.min(flow, Math.max(0, balance * 0.5));
            stream = flow > 1e-6 ? src.extract(flow) : new GasMix();
        } else if (worldOpen(back)) {
            worldSource = true;
            double pCell = gm.sample(backPos).total() / Gas.MOL_PER_BLOCK;
            double flow = nozzle ? WORLD_DRAW * t * pCell : Math.max(0, PIPE_CONDUCTANCE * t * (pCell - pOut) * 4);
            stream = flow > 1e-6 ? gm.extract(backPos, flow) : new GasMix();
        } else {
            stream = new GasMix();
        }
        lastFlow = stream.total();
        lastStream = stream.copy();

        if (!nozzle) {
            dst.insert(stream);
            if (!stream.isEmpty()) {
                if (src != null) src.insert(stream);
                else gm.release(backPos, stream);
            }
            if (isLit()) setLit(false);
            return;
        }

        if (isLit()) {
            burnJet(gm, stream, f, backPos, worldSource);
        } else {
            if (!stream.isEmpty()) {
                gm.release(frontPos, stream);
                if (level.getGameTime() % 30 == 0 && lastFlow > 0.05) {
                    level.playSound(null, worldPosition, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.15f, 1.9f);
                }
            }
            // A flame placed in front of an open valve lights it.
            if (level.getGameTime() % 10 == 0 && Combustion.canSustainJet(lastStream)
                    && gm.isIgnitionSource(level.getBlockState(frontPos))) {
                setLit(true);
            }
            jetLength = 0;
            lastHeat = 0;
        }
    }

    private void burnJet(GasManager gm, GasMix stream, Direction f, BlockPos backPos, boolean worldSource) {
        ServerLevel sl = (ServerLevel) level;
        if (stream.isEmpty() || !Combustion.canSustainJet(stream)) {
            extinguish(gm, stream);
            return;
        }
        // Flashback: a premixed flammable source lets the flame run back through the valve.
        if (worldSource && sl.random.nextDouble() < GasConfig.FLASHBACK_CHANCE.get() / 20.0
                && Combustion.isFlammable(gm.sample(backPos))) {
            gm.ignite(backPos, null);
        }

        double heat = Combustion.heatContent(stream);
        int length = (int) Math.max(1, Math.min(8, Math.round(1 + Math.sqrt(heat / 50.0))));
        double released = 0;
        int reach = 0;
        for (int k = 1; k <= length; k++) {
            BlockPos p = worldPosition.relative(f, k);
            if (!gm.isLoaded(p) || gm.isGasTight(level.getBlockState(p), p)) break;
            if (k > 1 && !gm.canFlow(worldPosition.relative(f, k - 1), f)) break;
            GasMix cell = gm.getOrCreateCell(p);
            if (cell == null) break;
            reach = k;
            released += Combustion.burnWithAir(stream, cell, cell.total() * 0.35);
            gm.markDirty(p);
            if (Combustion.fuelMoles(stream) < 1e-5) break;
        }
        if (reach == 0 || released < heat * 0.05) {
            // No oxygen to burn with: the flame chokes.
            extinguish(gm, stream);
            return;
        }
        // Hot products (and unburnt fuel if air ran short) leave at the tip of the flame.
        gm.release(worldPosition.relative(f, reach), stream);
        lastHeat = released;
        jetLength = reach;
        jetEffects(sl, f, reach, lastStream);
    }

    private void extinguish(GasManager gm, GasMix stream) {
        setLit(false);
        if (!stream.isEmpty()) gm.release(worldPosition.relative(facing()), stream);
        level.playSound(null, worldPosition, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1.0f);
        lastHeat = 0;
        jetLength = 0;
    }

    private void jetEffects(ServerLevel sl, Direction f, int reach, GasMix fuel) {
        double fuelMoles = Combustion.fuelMoles(fuel);
        boolean hydrogen = fuelMoles > 0 && fuel.get(Gas.HYDROGEN) / fuelMoles > 0.6;
        Vec3 dir = new Vec3(f.getStepX(), f.getStepY(), f.getStepZ());
        Vec3 nozzle = Vec3.atCenterOf(worldPosition).add(dir.scale(0.55));
        double speed = 0.25 + reach * 0.06;
        for (int k = 0; k < reach * 2; k++) {
            double along = sl.random.nextDouble() * reach * 0.6;
            Vec3 p = nozzle.add(dir.scale(along)).add((sl.random.nextDouble() - 0.5) * 0.25, (sl.random.nextDouble() - 0.5) * 0.25, (sl.random.nextDouble() - 0.5) * 0.25);
            ParticleOptions type;
            if (hydrogen) {
                if (sl.random.nextInt(4) != 0) continue;
                type = ParticleTypes.SMALL_FLAME;
            } else {
                type = along < reach * 0.25 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME;
            }
            sl.sendParticles(type, p.x, p.y, p.z, 0, dir.x, dir.y + 0.02, dir.z, speed);
        }
        if (sl.getGameTime() % 3 == 0) {
            Vec3 tip = nozzle.add(dir.scale(reach));
            sl.sendParticles(ParticleTypes.SMOKE, tip.x, tip.y + 0.2, tip.z, 1, 0.2, 0.2, 0.2, 0.01);
        }
        if (sl.getGameTime() % 20 == 0) {
            sl.playSound(null, worldPosition, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 0.8f + reach * 0.15f, 0.6f);
            sl.playSound(null, worldPosition, SoundEvents.BLAZE_BURN, SoundSource.BLOCKS, 0.3f + reach * 0.05f, 0.5f);
        }
        if (sl.getGameTime() % 5 == 0) {
            Vec3 end = nozzle.add(dir.scale(reach));
            AABB box = new AABB(nozzle, end).inflate(0.45);
            for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, box)) {
                if (e.fireImmune()) continue;
                e.setSecondsOnFire(5);
                e.hurt(GasDamageTypes.source(sl, GasDamageTypes.BURNER_FLAME), 3.0f + reach * 0.5f);
            }
        }
        if (sl.getGameTime() % 10 == 0) {
            // The flame lights any gas cloud it reaches, and flammable blocks at its tip.
            BlockPos tipPos = worldPosition.relative(f, reach);
            gas().igniteAround(tipPos, 1, null);
            if (sl.random.nextFloat() < 0.15f) {
                for (Direction d : Direction.values()) {
                    BlockPos n = tipPos.relative(d);
                    if (sl.getBlockState(n).isFlammable(sl, n, d.getOpposite()) && sl.isEmptyBlock(tipPos)
                            && BaseFireBlock.canBePlacedAt(sl, tipPos, d.getOpposite())) {
                        sl.setBlockAndUpdate(tipPos, BaseFireBlock.getState(sl, tipPos));
                        break;
                    }
                }
            }
        }
    }

    @Override
    public List<Component> status() {
        List<Component> out = super.status();
        Component state = Component.translatable(isOpen() ? "block.fts_geology.gas_valve.state_open" : "block.fts_geology.gas_valve.state_closed")
                .withStyle(isOpen() ? ChatFormatting.GREEN : ChatFormatting.RED);
        out.add(Component.translatable("block.fts_geology.gas_valve").withStyle(ChatFormatting.GOLD).append(": ").append(state)
                .append(Component.literal(String.format(Locale.ROOT, "  %d%%  %.2f mol/s", throttle * 25, lastFlow * 20)).withStyle(ChatFormatting.WHITE)));
        if (isLit()) {
            out.add(Component.translatable("block.fts_geology.gas_valve.flame", jetLength,
                    String.format(Locale.ROOT, "%.0f kW", lastHeat * 20)).withStyle(ChatFormatting.GOLD));
        }
        if (!lastStream.isEmpty()) GasText.appendComposition(lastStream, out::add);
        out.add(Component.translatable("block.fts_geology.gas_valve.help").withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("throttle", throttle);
        tag.putBoolean("powered", powered);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        throttle = Math.max(1, Math.min(4, tag.getInt("throttle")));
        powered = tag.getBoolean("powered");
    }
}
