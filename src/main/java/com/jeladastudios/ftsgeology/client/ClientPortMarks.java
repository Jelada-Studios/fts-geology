package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.gas.block.entity.GasMachineBlockEntity;
import com.jeladastudios.ftsgeology.gas.registry.GasBlocks;
import com.jeladastudios.ftsgeology.gas.registry.GasItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.joml.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Where a gas machine takes its gas in and gives it out, marked on its faces while the player looks at it with a gas
 * pipe or the gas detector in hand: green dots on an inlet, orange on an outlet, blue on a tank, which is both. The
 * panel names them; this shows which face is which without opening it.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientPortMarks {

    private ClientPortMarks() {}

    private static final DustParticleOptions IN = new DustParticleOptions(new Vector3f(0.48f, 0.83f, 0.54f), 1.0f);
    private static final DustParticleOptions OUT = new DustParticleOptions(new Vector3f(0.96f, 0.64f, 0.38f), 1.0f);
    private static final DustParticleOptions BOTH = new DustParticleOptions(new Vector3f(0.56f, 0.79f, 0.90f), 1.0f);

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused() || mc.level.getGameTime() % 4 != 0) return;
        if (!holdsGasTool(mc.player.getMainHandItem()) && !holdsGasTool(mc.player.getOffhandItem())) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();
        if (!(mc.level.getBlockEntity(pos) instanceof GasMachineBlockEntity machine)) return;
        for (GasMachineBlockEntity.Port port : machine.ports()) {
            DustParticleOptions dot = port.key().equals("stored") ? BOTH : port.in() ? IN : OUT;
            for (Direction d : port.faces()) mark(mc, pos, d, dot);
        }
    }

    private static boolean holdsGasTool(ItemStack held) {
        return held.is(GasItems.GAS_DETECTOR.get()) || held.is(GasBlocks.GAS_PIPE.get().asItem());
    }

    /** A couple of dots just off the middle of a face, a little scattered over it. */
    private static void mark(Minecraft mc, BlockPos pos, Direction d, DustParticleOptions dot) {
        var r = mc.level.random;
        for (int i = 0; i < 3; i++) {
            double out = 0.56 + 0.06 * r.nextDouble();
            double a = (r.nextDouble() - 0.5) * 0.35, b = (r.nextDouble() - 0.5) * 0.35;
            double x = pos.getX() + 0.5 + d.getStepX() * out, y = pos.getY() + 0.5 + d.getStepY() * out,
                    z = pos.getZ() + 0.5 + d.getStepZ() * out;
            if (d.getAxis() == Direction.Axis.X) {
                y += a;
                z += b;
            } else if (d.getAxis() == Direction.Axis.Y) {
                x += a;
                z += b;
            } else {
                x += a;
                y += b;
            }
            mc.level.addParticle(dot, x, y, z, 0, 0, 0);
        }
    }
}
