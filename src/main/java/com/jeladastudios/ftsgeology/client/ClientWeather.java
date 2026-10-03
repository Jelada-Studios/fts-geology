package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.network.LocalWeatherPacket;
import com.jeladastudios.ftsgeology.weather.LocalWeather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The player's own weather on the client: what the server says of the storm over them, eased in and out a tick at a
 * time (see {@link LocalWeather}), which the sky, the rain and the shaders then read; and how the rain looks where they
 * stand: water dripping from leaves and from the edges of roofs while it rains and for a minute or two after, and a
 * downpour closing the view in a little.
 *
 * <p>The rain and the wind are not heard from here. Where the weather is the server's, vanilla's own rain sound plays
 * from where the rain lands (see {@link SkyEffects#tickRain}); the sound of rain and wind on what they fall on and blow
 * through is the Feel the Nature addon's, which reads this weather.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientWeather {

    private ClientWeather() {}

    /** How wet the world round the player still is: the rain, and after it a while of dripping. */
    private static float wet;
    /** How open to the sky the player stands, 0 indoors to 1 in the open, worked out every half second. */
    private static float open = 1f;

    public static void set(LocalWeatherPacket p) {
        LocalWeather.set(p);
    }

    /**
     * How hard it rains where the player is, 0 to 1: the storm's rain where the server tells it, else vanilla's one
     * weather (a light rain, a heavy one in a thunderstorm).
     */
    public static float intensity(ClientLevel level) {
        if (LocalWeather.active()) return LocalWeather.rain(1f);
        float rain = level.getRainLevel(1f);
        return rain * (level.getThunderLevel(1f) > 0.5f ? 1f : 0.45f);
    }

    /**
     * The rain a shader pack is told, from the rain where the player stands: vanilla's one weather tells a pack 1 for any
     * rain at all, and a pack draws its overcast, its darkness and its wet ground from that. Told how hard the rain is, a
     * steady rain of a half was half a sky of cloud. Full from a fifth of the heaviest rain, less only at a shower's edge.
     */
    public static float shaderRain(float told) {
        if (!LocalWeather.active() || !com.jeladastudios.ftsgeology.config.ClientConfig.SHADER_OVERCAST.get()) return told;
        return smooth((told - 0.01f) / 0.19f);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        // Under, in or over the storm's cloud: the rain reaching the player, and so the sky and the drops.
        if (level != null && player != null && LocalWeather.active()) {
            LocalWeather.overhead(ClientSky.overhead(level, player.getX(), player.getEyeY(), player.getZ()));
        }
        LocalWeather.tick();
        if (level == null || player == null) return;
        if (mc.isPaused()) return;
        float rain = intensity(level);
        BlockPos at = player.blockPosition();
        Biome.Precipitation fall = level.getBiome(at).value().getPrecipitationAt(at);
        if (fall != Biome.Precipitation.RAIN || !level.dimensionType().hasSkyLight()) rain = 0;
        wet = Math.max(wet * 0.9995f, rain);
        ClientRiverMud.tick(player, rain);
        ClientGroundSteam.tick(player, rain);
        if (level.getGameTime() % 10 == 0) open = openness(level, player);
        drips(level, player);
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    /** How much of the sky the player is under: a three by three spread round the head, open where the sky is seen. */
    private static float openness(ClientLevel level, LocalPlayer player) {
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        int seen = 0;
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                if (level.canSeeSky(eye.offset(dx, 0, dz))) seen++;
            }
        }
        return seen / 9f;
    }

    // === Drips and the downpour's haze ============================================

    /**
     * Water dripping from leaves, and from the edges of roofs and ledges open to the rain, round the player: as the
     * rain falls, and fewer and fewer for a minute or two after it stops.
     */
    private static void drips(ClientLevel level, LocalPlayer player) {
        if (wet < 0.02f) return;
        RandomSource rnd = level.random;
        int tries = (int) (wet * 24);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < tries; i++) {
            int x = player.getBlockX() + rnd.nextInt(21) - 10, y = player.getBlockY() + rnd.nextInt(12) - 3,
                    z = player.getBlockZ() + rnd.nextInt(21) - 10;
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;
            if (!level.getBlockState(m.set(x, y - 1, z)).isAir()) continue;
            boolean leaves = s.is(BlockTags.LEAVES);
            // A roof's or a ledge's edge: rained on from above, open on a side for the water to run off.
            boolean edge = !leaves && s.isCollisionShapeFullBlock(level, m.set(x, y, z)) && level.canSeeSky(m.set(x, y + 1, z))
                    && (level.getBlockState(m.set(x + 1, y, z)).isAir() || level.getBlockState(m.set(x - 1, y, z)).isAir()
                    || level.getBlockState(m.set(x, y, z + 1)).isAir() || level.getBlockState(m.set(x, y, z - 1)).isAir());
            if (!leaves && !edge) continue;
            if (leaves && !level.canSeeSky(m.set(x, y + 1, z)) && rnd.nextInt(3) != 0) continue;
            level.addParticle(ParticleTypes.DRIPPING_WATER, x + rnd.nextDouble(), y - 0.05, z + rnd.nextDouble(), 0, 0, 0);
        }
    }

    /**
     * The ground fog round the camera, 0 to 1: the server's fog where the player stands, lying in the lowest dozen-odd
     * blocks over the ground under the camera, thinning above that; none indoors.
     */
    private static float groundFog(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.phys.Vec3 cam) {
        float f = LocalWeather.fog();
        if (f <= 0f || open < 0.3f) return 0f;
        int g = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, Mth.floor(cam.x), Mth.floor(cam.z));
        double above = cam.y - g;
        return f * Mth.clamp((float) (1.0 - (above - 12.0) / 10.0), 0f, 1f);
    }

    /**
     * A downpour closes the view in: at the heaviest rain, by about a third, outdoors. Inside a cloud, on a mountain or
     * flying, the mist closes it to some twenty blocks; a ground fog to under thirty.
     */
    @SubscribeEvent
    public static void onFog(ViewportEvent.RenderFog event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || event.getType() != FogType.NONE || mc.player == null) return;
        var cam = event.getCamera().getPosition();
        float mist = ClientSky.inCloud(mc.level, cam.x, cam.y, cam.z);
        float ground = groundFog(mc.level, cam);
        float h = open < 0.3f ? 0f : smooth((intensity(mc.level) - 0.5f) / 0.5f);
        if (h <= 0 && mist <= 0 && ground <= 0) return;
        float k = 1f - 0.35f * h;
        float far = event.getFarPlaneDistance() * k, near = event.getNearPlaneDistance() * k;
        if (ground > 0) {
            far = Mth.lerp(ground, far, Math.min(far, 28f));
            near = Mth.lerp(ground, near, 0f);
        }
        if (mist > 0) {
            far = Mth.lerp(mist, far, Math.min(far, 20f));
            near = Mth.lerp(mist, near, 0f);
        }
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(near);
        event.setCanceled(true);
    }

    /**
     * A downpour greys the far view: the fog's colour drawn towards a rain-grey, by up to a third, outdoors. A cloud's mist
     * is a pale grey, lit by the day.
     */
    @SubscribeEvent
    public static void onFogColour(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !LocalWeather.active()) return;
        var cam = event.getCamera().getPosition();
        float mist = Math.max(ClientSky.inCloud(mc.level, cam.x, cam.y, cam.z), 0.85f * groundFog(mc.level, cam));
        if (mist > 0) {
            float day = Mth.clamp(Mth.cos(mc.level.getTimeOfDay((float) event.getPartialTick()) * Mth.TWO_PI) * 2f + 0.5f, 0.1f, 1f);
            float pale = 0.78f * day;
            event.setRed(Mth.lerp(mist, event.getRed(), pale));
            event.setGreen(Mth.lerp(mist, event.getGreen(), pale));
            event.setBlue(Mth.lerp(mist, event.getBlue(), pale * 1.03f));
        }
        if (open < 0.3f) return;
        float h = smooth((intensity(mc.level) - 0.4f) / 0.6f) * 0.33f;
        if (h <= 0) return;
        float grey = (event.getRed() * 0.3f + event.getGreen() * 0.59f + event.getBlue() * 0.11f) * 0.85f;
        event.setRed(Mth.lerp(h, event.getRed(), grey));
        event.setGreen(Mth.lerp(h, event.getGreen(), grey));
        event.setBlue(Mth.lerp(h, event.getBlue(), grey * 1.05f));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LocalWeather.reset();
        ClientRiverMud.reset();
        ClientGroundSteam.reset();
        wet = 0;
    }
}
