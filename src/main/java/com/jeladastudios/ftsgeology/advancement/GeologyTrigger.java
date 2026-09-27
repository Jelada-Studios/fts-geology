package com.jeladastudios.ftsgeology.advancement;

import com.google.gson.JsonObject;
import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;

/**
 * One advancement trigger for everything a player finds out about the ground: {@code fts_geology:geology} with an
 * {@code event} name, awarded from where it happens -- a hammer striking rock, a quake felt, a slope coming down.
 */
public final class GeologyTrigger extends SimpleCriterionTrigger<GeologyTrigger.Instance> {

    static final ResourceLocation ID = new ResourceLocation(GeysersMod.MODID, "geology");

    public static final GeologyTrigger INSTANCE = CriteriaTriggers.register(new GeologyTrigger());

    /** Makes sure the trigger is registered before advancements load. */
    public static void init() {}

    /** Tells the advancements that {@code player} has done {@code event}. */
    public static void award(ServerPlayer player, String event) {
        INSTANCE.trigger(player, i -> i.event.equals(event));
    }

    /** Tells the advancements that everyone within {@code radius} of a point has seen {@code event}. */
    public static void awardNear(net.minecraft.server.level.ServerLevel level, double x, double z, double radius, String event) {
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (dx * dx + dz * dz <= radius * radius) award(p, event);
        }
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected Instance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext context) {
        return new Instance(player, GsonHelper.getAsString(json, "event"));
    }

    public static final class Instance extends AbstractCriterionTriggerInstance {
        final String event;

        Instance(ContextAwarePredicate player, String event) {
            super(ID, player);
            this.event = event;
        }

        @Override
        public JsonObject serializeToJson(net.minecraft.advancements.critereon.SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            json.addProperty("event", event);
            return json;
        }
    }
}
