package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The ground moving, as the person standing on it experiences it.
 *
 * <h2>What was missing</h2>
 * The earthquake model is the most carefully built thing in the mod - real rupture lengths, a fault
 * that knows what kind it is, deformation that falls off the way it should - and the moment it
 * arrives, the player felt a small push on their motion vector and nothing else. Testing's verdict
 * was that it read as walking on soap. The mismatch is not in the model, it is that an earthquake
 * is a thing you SEE happen to the world, and the camera was perfectly steady throughout.
 *
 * <h2>Angles, not position, and not the player's aim</h2>
 * This runs in {@link ViewportEvent.ComputeCameraAngles}, which adjusts the camera for the frame
 * being drawn and touches neither the entity's rotation nor its position. So the view shakes, the
 * crosshair keeps pointing where it was aimed, and nothing is sent back to the server - a shake
 * that dragged your aim around would be fighting the mouse rather than shaking the ground.
 *
 * <p>Being a per-frame hook, it also runs at the frame rate rather than the tick rate: the jitter is
 * smooth on a fast machine instead of stepping twenty times a second, which is the whole reason the
 * packet carries an intensity and a duration rather than a position per tick.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientShake {

    private ClientShake() {}

    /** Current peak displacement in degrees, decaying towards zero. */
    private static float intensity;
    /** Ticks left of the decay. */
    private static int remaining;
    private static int total;

    /** Game time the last instruction arrived, so two in one tick can be told from two in a row. */
    private static long lastAt = Long.MIN_VALUE;

    /**
     * The newest instruction wins; two arriving together take the stronger.
     *
     * <h2>Two failure modes this is shaped around</h2>
     * Summing is the obvious version and is wrong twice over. A rupture re-sends its strength
     * several times a second while it runs, so anything cumulative ratchets upward until the camera
     * is spinning - and a volcano shaking the same player at the same time would add to it.
     *
     * <p>But taking the maximum over time is wrong too, and that was this method's first draft: the
     * strength a player is sent falls as they walk away from the epicentre, so a rule that only ever
     * accepted a <i>higher</i> value would have pinned the shake at whatever the worst moment was
     * and never let it down again. Walking to safety has to actually feel like it.</p>
     *
     * <p>So a later instruction simply replaces an earlier one, and the maximum is taken only among
     * instructions that arrive in the same tick - which is the one case where they are genuinely two
     * sources rather than one source updating itself.</p>
     */
    public static void add(float newIntensity, int ticks) {
        long now = Minecraft.getInstance().level == null
                ? 0L : Minecraft.getInstance().level.getGameTime();

        float value = Math.min(newIntensity, 6.0f);      // a ceiling, so nothing can spin the view
        if (now == lastAt) value = Math.max(value, intensity);

        lastAt = now;
        intensity = value;
        remaining = ticks;
        total = Math.max(1, ticks);
    }

    /** Stops any shake at once. Used when leaving a world, so it cannot survive into the next one. */
    public static void clear() {
        intensity = 0.0f;
        remaining = 0;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // A paused single-player game should not go on shaking behind the menu, and a player who
        // has left the world must not carry it into the next one.
        if (Minecraft.getInstance().level == null) { clear(); return; }
        if (Minecraft.getInstance().isPaused()) return;
        if (remaining > 0) remaining--;
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (remaining <= 0 || intensity <= 0.0f) return;

        // Linear run-out. Anything cleverer is invisible next to the jitter itself, and a decay that
        // only ASYMPTOTES towards zero would leave the camera faintly trembling for ever.
        float fade = remaining / (float) total;
        float amp = intensity * fade;

        // Several sine waves at unrelated speeds rather than a random offset per frame. Random
        // offsets read as a flicker; this reads as something heavy moving, and each axis gets its
        // own set so the three do not march in step.
        double t = (Minecraft.getInstance().level.getGameTime() + event.getPartialTick()) * 0.6;
        float yaw = wobble(t, 1.00, 2.31, 3.77) * amp;
        float pitch = wobble(t, 1.43, 2.71, 4.19) * amp * 0.7f;
        // Roll is the one that sells it - the horizon tipping is what a camera does in an
        // earthquake and what a player never normally sees, so it is given the most.
        float roll = wobble(t, 0.87, 1.97, 3.11) * amp * 1.3f;

        event.setYaw(event.getYaw() + yaw);
        event.setPitch(event.getPitch() + pitch);
        event.setRoll(event.getRoll() + roll);
    }

    private static float wobble(double t, double a, double b, double c) {
        return (float) ((Math.sin(t * a) + Math.sin(t * b) * 0.6 + Math.sin(t * c) * 0.35) / 1.95);
    }
}
