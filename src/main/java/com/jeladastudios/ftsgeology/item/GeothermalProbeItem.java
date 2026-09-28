package com.jeladastudios.ftsgeology.item;

import com.jeladastudios.ftsgeology.advancement.GeologyTrigger;
import com.jeladastudios.ftsgeology.blockentity.GeothermalTurbineBlockEntity;
import com.jeladastudios.ftsgeology.command.InspectCommands;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.GeothermalSuitability;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * A temperature probe pushed into the ground, and a reading of the heat flow under it: how hot the ground gets with
 * depth here, how well the setting suits a volcano, a geyser field or hot springs, whether a well bored straight down
 * would reach one of the mod's hot water reservoirs and how much a turbine on it would make, and which way the
 * nearest hot ground shows at the surface. The {@code /geology suitability} reading, in survival.
 */
public class GeothermalProbeItem extends Item {

    private static final int COOLDOWN = 40;
    /** How far round the probe the surface is looked over for hot ground showing. */
    private static final int LOOK = 32;

    public GeothermalProbeItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer sp)) return InteractionResultHolder.pass(stack);
        BlockPos at = player.blockPosition();
        int ground = TerrainProbe.groundY(server, at.getX(), at.getZ());
        if (ground == Integer.MIN_VALUE) ground = at.getY() - 1;
        for (Component line : read(server, at.getX(), ground, at.getZ(), sp)) sp.sendSystemMessage(line);
        level.playSound(null, at, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 0.6f);
        player.getCooldowns().addCooldown(this, COOLDOWN);
        return InteractionResultHolder.consume(stack);
    }

    public static List<Component> read(ServerLevel level, int x, int ground, int z, @javax.annotation.Nullable ServerPlayer player) {
        List<Component> out = new java.util.ArrayList<>();
        GeothermalSuitability.Suitability fit = GeothermalSuitability.at(level, x, z);
        double hot = Math.max(fit.volcano(), Math.max(fit.geyser(), fit.hotSpring()));
        // Heat flow: a normal continent warms by about 25 degrees a kilometre down; over a plume or a magma body many
        // times that.
        double unrest = com.jeladastudios.ftsgeology.volcano.VolcanoUnrest.near(level, new BlockPos(x, ground, z));
        int gradient = (int) Math.round(25 + 125 * hot + 100 * unrest);
        out.add(Component.translatable("probe.fts_geology.header", gradient, 15 + gradient)
                .withStyle(ChatFormatting.GOLD));
        // Magma rising under a volcano nearby: the ground swells over it.
        com.jeladastudios.ftsgeology.volcano.VolcanoUnrest.Restless restless =
                com.jeladastudios.ftsgeology.volcano.VolcanoUnrest.nearest(level, x, z);
        if (restless != null) {
            int dx = restless.summit().getX() - x, dz = restless.summit().getZ() - z;
            out.add(Component.translatable("probe.fts_geology.unrest",
                    com.jeladastudios.ftsgeology.volcano.VolcanoUnrest.swellCm(restless, x, z),
                    (int) Math.round(Math.hypot(dx, dz)),
                    Component.translatable("prospect.fts_geology.dir." + com.jeladastudios.ftsgeology.instrument.Prospecting
                            .bearingOf(dx, dz))).withStyle(ChatFormatting.RED));
        }
        if (player != null && com.jeladastudios.ftsgeology.volcano.VolcanicGas.at(level, player.getX(), player.getY(),
                player.getZ()) != null) {
            out.add(Component.translatable("probe.fts_geology.gas").withStyle(ChatFormatting.DARK_RED));
        }
        // The water in the ground here: how wet the roots are, and how far down the groundwater stands.
        com.jeladastudios.ftsgeology.hydrology.SoilWater.Reading soil =
                com.jeladastudios.ftsgeology.hydrology.SoilWater.at(level, x, z);
        if (soil != null && soil.soil().ground() && soil.depth() >= 0) {
            out.add(Component.translatable("probe.fts_geology.soil", InspectCommands.pct(soil.rootAvailable()),
                    InspectCommands.bar(soil.rootAvailable()), InspectCommands.dec(Math.max(0, soil.depth() - soil.table()), 1),
                    soil.depth()).withStyle(ChatFormatting.AQUA));
        }
        out.add(Component.translatable("command.fts_geology.suitability.volcano", InspectCommands.dec(fit.volcano(), 2),
                InspectCommands.bar(fit.volcano())));
        out.add(Component.translatable("command.fts_geology.suitability.geyser", InspectCommands.dec(fit.geyser(), 2),
                InspectCommands.bar(fit.geyser())));
        out.add(Component.translatable("command.fts_geology.suitability.springs", InspectCommands.dec(fit.hotSpring(), 2),
                InspectCommands.bar(fit.hotSpring())));

        // A well straight down: the first depth at which a reservoir lies round its foot.
        int most = GeyserConfig.TURBINE_WELL_DEPTH.get();
        int foot = GeothermalTurbineBlockEntity.FOOT;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int well = -1;
        double heat = 0;
        for (int d = 1; d <= most && well < 0; d++) {
            int y = ground - d;
            if (y <= level.getMinBuildHeight()) break;
            boolean reservoir = false;
            double h = 0;
            for (int dx = -foot; dx <= foot; dx++) {
                for (int dy = -foot; dy <= foot; dy++) {
                    for (int dz = -foot; dz <= foot; dz++) {
                        m.set(x + dx, y + dy, z + dz);
                        if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m)) continue;
                        BlockState s = level.getBlockState(m);
                        if (GeothermalTurbineBlockEntity.isReservoir(s)) reservoir = true;
                        h += GeothermalTurbineBlockEntity.heatOf(s);
                    }
                }
            }
            if (reservoir && h > 0) {
                well = d;
                heat = h;
            }
        }
        if (well > 0) {
            double region = 0.35 + 0.65 * Math.max(fit.hotSpring(), fit.geyser());
            int max = GeyserConfig.TURBINE_MAX_FE.get(), min = Math.min(max, GeyserConfig.TURBINE_MIN_FE.get());
            double t = Math.min(1.0, heat / GeothermalTurbineBlockEntity.HEAT_FULL);
            int fe = (int) Math.round((min + (max - min) * t) * region);
            out.add(Component.translatable("probe.fts_geology.well", well, fe).withStyle(ChatFormatting.AQUA));
            if (player != null) {
                GeologyTrigger.award(player, "probe_heat");
                // Steam rising where the well would go, taller the hotter the reservoir.
                for (int i = 0; i < 12; i++) {
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE, x + 0.5,
                            ground + 1.0 + i * 0.35, z + 0.5, 1, 0.05, 0.1, 0.05, 0.01 + heat * 0.002);
                }
                level.playSound(null, new BlockPos(x, ground + 1, z), net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 0.8f);
            }
        } else {
            out.add(Component.translatable("probe.fts_geology.no_well", most).withStyle(ChatFormatting.GRAY));
        }

        // The nearest hot ground showing at the surface round here.
        int best = Integer.MAX_VALUE, bx = 0, bz = 0;
        BlockState seen = null;
        for (int dx = -LOOK; dx <= LOOK; dx++) {
            for (int dz = -LOOK; dz <= LOOK; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 >= best || d2 > LOOK * LOOK) continue;
                m.set(x + dx, ground, z + dz);
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m)) continue;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x + dx, z + dz);
                for (int y = top; y > top - 6; y--) {
                    BlockState s = level.getBlockState(m.set(x + dx, y, z + dz));
                    if (s.is(ModBlocks.HOT_SPRING.get()) || s.is(ModBlocks.STEAM_VENT.get()) || s.is(ModBlocks.MUD_POT.get())
                            || s.is(ModBlocks.GEYSER_CORE.get()) || s.is(ModBlocks.SINTER.get())
                            || s.is(ModBlocks.SINTER_CRUST.get()) || s.is(ModBlocks.NATIVE_SULFUR.get())) {
                        best = d2;
                        bx = dx;
                        bz = dz;
                        seen = s;
                        break;
                    }
                }
            }
        }
        if (seen != null) {
            out.add(Component.translatable("probe.fts_geology.nearest", seen.getBlock().getName(),
                    (int) Math.round(Math.sqrt(best)),
                    Component.translatable("prospect.fts_geology.dir." + com.jeladastudios.ftsgeology.instrument.Prospecting
                            .bearingOf(bx, bz))).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        out.add(Component.translatable(fit.reasonKey()).withStyle(ChatFormatting.GRAY));
        return out;
    }

    @Override
    public void appendHoverText(ItemStack stack, @javax.annotation.Nullable Level level, List<Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(Component.translatable("item.fts_geology.geothermal_probe.tip").withStyle(ChatFormatting.GRAY));
    }
}
