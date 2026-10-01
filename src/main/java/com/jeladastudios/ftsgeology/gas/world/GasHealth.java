package com.jeladastudios.ftsgeology.gas.world;

import com.jeladastudios.ftsgeology.gas.GasConfig;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.item.BreathingApparatusItem;
import com.jeladastudios.ftsgeology.gas.item.GasMaskItem;
import com.jeladastudios.ftsgeology.gas.registry.GasDamageTypes;
import com.jeladastudios.ftsgeology.gas.registry.GasTags;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Physiological effects of the air an entity breathes, evaluated once per second at eye level.
 * Thresholds follow occupational-health data:
 * <ul>
 *   <li>O\u2082 &lt; 16 %: impaired; &lt; 12 %: nausea; &lt; 10 %: collapse; &lt; 6 %: death within minutes.</li>
 *   <li>CO\u2082 &gt; 3 %: breathlessness; &gt; 5 %: headache/nausea; &gt; 8 %: unconsciousness.</li>
 *   <li>CO binds haemoglobin: carboxyhaemoglobin (COHb) builds up over time towards an
 *       equilibrium set by the concentration, and slowly clears in fresh air.</li>
 *   <li>H\u2082S: rotten-egg smell at low ppm, olfactory paralysis above 100 ppm (you stop smelling
 *       it!), knockdown above ~700 ppm.</li>
 *   <li>SO\u2082: sharp irritant; dangerous above ~100 ppm.</li>
 * </ul>
 */
public final class GasHealth {
    private static final String COHB = "fts_geology.gas.cohb";
    private static final String HUNGER = "fts_geology.gas.air_hunger";
    private static final String LAST_HINT = "fts_geology.gas.hint";

    private GasHealth() {
    }

    public static void tick(LivingEntity e) {
        if (!(e.level() instanceof ServerLevel level)) return;
        if ((e.tickCount + e.getId()) % 20 != 0) return;
        GasManager mgr = GasManager.get(level);
        BlockPos eye = BlockPos.containing(e.getEyePosition());

        // Burning mobs and players are ignition sources.
        if (e.isOnFire()) {
            if (!mgr.ignite(eye, e)) mgr.ignite(e.blockPosition(), e);
        }

        if (!GasConfig.HEALTH_EFFECTS.get()) return;
        if (e instanceof ArmorStand || e.getMobType() == MobType.UNDEAD || e.getType().is(GasTags.GAS_IMMUNE)) return;
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) return;

        GasMix g = mgr.sample(eye);
        double o2 = g.fraction(Gas.O2);
        double co2 = g.fraction(Gas.CO2);
        double coPpm = g.fraction(Gas.CO) * 1e6;
        double h2sPpm = g.fraction(Gas.H2S) * 1e6;
        double so2Ppm = g.fraction(Gas.SO2) * 1e6;

        ItemStack head = e.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack chest = e.getItemBySlot(EquipmentSlot.CHEST);
        boolean hazardous = o2 < 0.195 || co2 > 0.02 || coPpm > 25 || h2sPpm > 10 || so2Ppm > 2;
        boolean underwater = e.isEyeInFluid(FluidTags.WATER);

        if (chest.getItem() instanceof BreathingApparatusItem && (hazardous || underwater)
                && BreathingApparatusItem.breathe(e, chest)) {
            if (underwater) e.setAirSupply(e.getMaxAirSupply());
            o2 = Gas.AIR_O2;
            co2 = 0;
            coPpm = h2sPpm = so2Ppm = 0;
        } else if (head.getItem() instanceof GasMaskItem && GasMaskItem.hasFilter(head)) {
            if (coPpm > 25 || h2sPpm > 10 || so2Ppm > 2) {
                int wear = 1 + (int) ((coPpm / 2000) + (h2sPpm / 200) + (so2Ppm / 200));
                GasMaskItem.useFilter(e, head, Math.min(wear, 10));
                if (!GasMaskItem.hasFilter(head) && e instanceof Player p) {
                    p.displayClientMessage(Component.translatable("item.fts_geology.gas_mask.spent").withStyle(ChatFormatting.RED), true);
                }
            }
            coPpm = h2sPpm = so2Ppm = 0;
        } else if (e instanceof Player p && GasConfig.SMELL_MESSAGES.get()) {
            smellHints(p, h2sPpm, so2Ppm);
        }

        CompoundTag data = e.getPersistentData();
        double cohb = data.getDouble(COHB);
        double eq = 100.0 * coPpm / (coPpm + 600.0);
        cohb += (eq - cohb) / (eq > cohb ? 45.0 : 30.0);
        if (cohb < 0.05) cohb = 0;
        if (cohb > 0 || data.contains(COHB)) data.putDouble(COHB, cohb);

        float asphyxia = 0, poison = 0;

        // Air hunger, 0 to 100: built up while the air is short of oxygen (whatever displaced it) or thick with CO2, and
        // gone again in a few breaths of good air. A few breaths in it only make one short of breath and slow; staying
        // in it brings dizziness, then blackness, and only then harm. How fast is how bad the air is: in air with almost
        // no oxygen, or a third CO2, it comes within seconds; in a tenth CO2, within the better part of a minute; in air
        // only somewhat short of oxygen, never past being out of breath.
        double rate = o2 < 0.06 || co2 > 0.30 ? 6.0 : o2 < 0.10 || co2 > 0.10 ? 2.0 : o2 < 0.16 || co2 > 0.05 ? 0.7 : 0.0;
        double hunger = data.getDouble(HUNGER);
        hunger = rate > 0 ? Math.min(rate >= 2.0 ? 100.0 : 45.0, hunger + rate) : Math.max(0.0, hunger - 5.0);
        if (hunger > 0 || data.contains(HUNGER)) data.putDouble(HUNGER, hunger);
        if (hunger > 15) {
            effect(e, MobEffects.MOVEMENT_SLOWDOWN, hunger > 50 ? 1 : 0);
            effect(e, MobEffects.DIG_SLOWDOWN, 0);
        }
        if (hunger > 35) effect(e, MobEffects.WEAKNESS, 0);
        if (hunger > 50) effect(e, MobEffects.CONFUSION, 0);
        if (hunger > 70) effect(e, MobEffects.BLINDNESS, 0);
        if (hunger > 85) asphyxia = rate >= 6.0 ? 3 : 1;

        // Carbon monoxide via carboxyhaemoglobin.
        if (cohb > 10 && e instanceof Player p && e.tickCount % 200 < 20) hint(p, "headache", ChatFormatting.GRAY);
        if (cohb > 20) effect(e, MobEffects.MOVEMENT_SLOWDOWN, 0);
        if (cohb > 30) {
            effect(e, MobEffects.CONFUSION, 0);
            effect(e, MobEffects.WEAKNESS, 1);
        }
        if (cohb > 40) {
            effect(e, MobEffects.BLINDNESS, 0);
            poison = Math.max(poison, cohb > 60 ? 4 : cohb > 50 ? 2 : 1);
        }

        // Hydrogen sulfide.
        if (h2sPpm > 100) effect(e, MobEffects.MOVEMENT_SLOWDOWN, 0);
        if (h2sPpm > 300) {
            effect(e, MobEffects.CONFUSION, 0);
            effect(e, MobEffects.WEAKNESS, 1);
            poison = Math.max(poison, 1);
        }
        if (h2sPpm > 500) {
            effect(e, MobEffects.BLINDNESS, 0);
            poison = Math.max(poison, 3);
        }
        if (h2sPpm > 700) poison = Math.max(poison, 8);

        // Sulfur dioxide.
        if (so2Ppm > 50) effect(e, MobEffects.MOVEMENT_SLOWDOWN, 0);
        if (so2Ppm > 100) poison = Math.max(poison, so2Ppm > 500 ? 4 : so2Ppm > 200 ? 2 : 1);

        if (asphyxia > 0) e.hurt(GasDamageTypes.source(level, GasDamageTypes.ASPHYXIATION), asphyxia);
        if (poison > 0) {
            e.invulnerableTime = 0;
            e.hurt(GasDamageTypes.source(level, GasDamageTypes.GAS_POISONING), poison);
        }
    }

    private static void effect(LivingEntity e, MobEffect effect, int amp) {
        MobEffectInstance cur = e.getEffect(effect);
        if (cur != null && cur.getAmplifier() > amp && cur.getDuration() > 30) return;
        e.addEffect(new MobEffectInstance(effect, effect == MobEffects.CONFUSION ? 120 : 60, amp, false, false, true));
    }

    private static void smellHints(Player p, double h2sPpm, double so2Ppm) {
        if (so2Ppm > 1) hint(p, "so2", ChatFormatting.YELLOW);
        else if (h2sPpm > 0.5 && h2sPpm < 100) hint(p, "h2s", ChatFormatting.YELLOW);
    }

    private static void hint(Player p, String key, ChatFormatting color) {
        CompoundTag data = p.getPersistentData();
        long now = p.level().getGameTime();
        if (now - data.getLong(LAST_HINT) < 160) return;
        data.putLong(LAST_HINT, now);
        p.displayClientMessage(Component.translatable("fts_geology.gas.hint." + key).withStyle(color, ChatFormatting.ITALIC), true);
    }
}
