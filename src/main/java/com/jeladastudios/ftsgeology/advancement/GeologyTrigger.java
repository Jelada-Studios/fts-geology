package com.jeladastudios.ftsgeology.advancement;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Optional;

/**
 * One advancement trigger for everything a player finds out about the ground: {@code fts_geology:geology} with an
 * {@code event} name, awarded from where it happens -- a hammer striking rock, a quake felt, a slope coming down.
 */
public final class GeologyTrigger extends SimpleCriterionTrigger<GeologyTrigger.Instance> {

    private static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
            DeferredRegister.create(Registries.TRIGGER_TYPE, GeysersMod.MODID);

    public static final DeferredHolder<CriterionTrigger<?>, GeologyTrigger> INSTANCE =
            TRIGGERS.register("geology", GeologyTrigger::new);

    /** Registers the trigger on the mod bus, before advancements load. */
    public static void register(IEventBus modBus) {
        TRIGGERS.register(modBus);
    }

    /** Tells the advancements that {@code player} has done {@code event}. */
    public static void award(ServerPlayer player, String event) {
        INSTANCE.get().trigger(player, i -> i.event.equals(event));
    }

    /** Tells the advancements that everyone within {@code radius} of a point has seen {@code event}. */
    public static void awardNear(net.minecraft.server.level.ServerLevel level, double x, double z, double radius, String event) {
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (dx * dx + dz * dz <= radius * radius) award(p, event);
        }
    }

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public record Instance(Optional<ContextAwarePredicate> player, String event) implements SimpleCriterionTrigger.SimpleInstance {
        static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                Codec.STRING.fieldOf("event").forGetter(Instance::event)
        ).apply(i, Instance::new));
    }
}
