package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.network.LocalWeatherPacket;
import com.jeladastudios.ftsgeology.weather.LocalWeather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The player's own weather on the client: what the server says of the storm over them, eased in and out a tick at a
 * time (see {@link LocalWeather}), which the sky, the rain and the shaders then read; and how the rain sounds and looks
 * where they stand.
 *
 * <p>The rain is heard in three strengths, light, steady and heavy, from recordings of real rain in place of vanilla's,
 * one fading into the next as the rain grows; a rain after a dry while begins with its recording's own beginning.
 * Indoors it is quieter, under water nearly gone, and in snow or a dry biome not there at all; in the open a breath of
 * wind is heard, more of it high up and in a strong wind. Water drips from leaves and from the edges of roofs while it
 * rains and for a minute or two after, and a downpour closes the view in a little. The recordings are not part of the
 * public source (their licence is not known); a build without them keeps vanilla's rain sound.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientWeather {

    private ClientWeather() {}

    private static final ResourceLocation LIGHT = new ResourceLocation("fts_geology_rain", "rain_light");
    private static final ResourceLocation MEDIUM = new ResourceLocation("fts_geology_rain", "rain_medium");
    private static final ResourceLocation HEAVY = new ResourceLocation("fts_geology_rain", "rain_heavy");
    private static final ResourceLocation LIGHT_START = new ResourceLocation("fts_geology_rain", "rain_light_start");
    private static final ResourceLocation HEAVY_START = new ResourceLocation("fts_geology_rain", "rain_heavy_start");
    private static final ResourceLocation WIND = new ResourceLocation("fts_geology_rain", "wind");

    private static Boolean recordings;
    private static final java.util.Map<ResourceLocation, Boolean> HAS = new java.util.HashMap<>();
    private static Track light, medium, heavy, wind, intro;
    /**
     * The last game tick a recording was heard playing while it rained, and whether vanilla's own rain stands in for them
     * now: when the sound engine has no stream left for them (other mods' music and ambience use streams too) the rain
     * is not left silent.
     */
    private static long heardAt;
    private static boolean standIn;
    /** Ticks since the rain was last heard: a rain after a dry while begins with its recording's own beginning. */
    private static int dryTicks = Integer.MAX_VALUE / 2;
    /** How wet the world round the player still is: the rain, and after it a while of dripping. */
    private static float wet;
    /** How open to the sky the player stands, 0 indoors to 1 in the open, worked out every half second. */
    private static float open = 1f;

    public static void set(LocalWeatherPacket p) {
        LocalWeather.set(p);
    }

    /** Whether the jar carries the rain recordings. */
    static boolean recordings() {
        if (recordings == null) recordings = has(LIGHT) && has(HEAVY);
        return recordings;
    }

    /** Whether the recordings play the rain now: they are in the jar and the sound engine is playing them. */
    static boolean recordingsHeard() {
        return recordings() && !standIn;
    }

    /** Whether one recording is there: the middle strength, the beginnings and the wind may be missing. */
    private static boolean has(ResourceLocation id) {
        return HAS.computeIfAbsent(id, k -> Minecraft.getInstance().getResourceManager()
                .getResource(new ResourceLocation(k.getNamespace(), "sounds/" + k.getPath() + ".ogg")).isPresent());
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

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        // Under, in or over the storm's cloud: the rain reaching the player, and so the sky, the sound and the drops.
        if (level != null && player != null && LocalWeather.active()) {
            LocalWeather.overhead(ClientSky.overhead(level, player.getX(), player.getEyeY(), player.getZ()));
        }
        LocalWeather.tick();
        if (level == null || player == null) {
            stop();
            return;
        }
        if (mc.isPaused()) return;
        float rain = intensity(level);
        BlockPos at = player.blockPosition();
        Biome.Precipitation fall = level.getBiome(at).value().getPrecipitationAt(at);
        if (fall != Biome.Precipitation.RAIN || !level.dimensionType().hasSkyLight()) rain = 0;
        wet = Math.max(wet * 0.9995f, rain);
        ClientRiverMud.tick(player, rain);
        ClientGroundSteam.tick(player, rain);
        if (level.getGameTime() % 10 == 0) open = openness(level, player);
        sound(level, player, rain);
        drips(level, player);
    }

    // === Sound ================================================================

    /** Vanilla's rain is not played where the recordings play instead. */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        if (event.getSound() == null || !recordingsHeard()) return;
        ResourceLocation id = event.getSound().getLocation();
        if (id.getNamespace().equals("minecraft") && (id.getPath().equals("weather.rain") || id.getPath().equals("weather.rain.above"))) {
            event.setSound(null);
        }
    }

    /** How loud each recording plays at full strength, before the game's weather slider. */
    private static final float LIGHT_LEVEL = 0.5f, MEDIUM_LEVEL = 0.75f, HEAVY_LEVEL = 1.0f, WIND_LEVEL = 0.35f;
    /** A beginning's length, and from when the loop takes over from it: its last seconds fade out as the loop comes in. */
    private static final int INTRO_TICKS = 500, HANDOVER_FROM = 340;
    /** How long it must have been dry for a rain to begin with its recording's beginning again. */
    private static final int DRY_FOR_INTRO = 600;

    private static void sound(ClientLevel level, LocalPlayer player, float rain) {
        if (!recordings()) return;
        float cover = (0.25f + 0.75f * open) * com.jeladastudios.ftsgeology.config.ClientConfig.RAIN_VOLUME.get().floatValue();
        if (player.isUnderWater()) cover *= 0.2f;
        // The strengths hand over as the rain grows: a drizzle, a steady rain, a downpour.
        float l, m, h;
        // A drizzle is heard as soon as it is seen: the light recording comes in from the first drops.
        if (has(MEDIUM)) {
            l = smooth((rain - 0.01f) / 0.07f) * (1f - smooth((rain - 0.3f) / 0.2f));
            m = smooth((rain - 0.25f) / 0.15f) * (1f - smooth((rain - 0.6f) / 0.2f));
            h = smooth((rain - 0.55f) / 0.2f);
        } else {
            l = smooth((rain - 0.01f) / 0.08f) * (1f - smooth((rain - 0.45f) / 0.25f));
            m = 0;
            h = smooth((rain - 0.4f) / 0.3f);
        }
        // A rain after a dry while begins with its recording's own beginning, the drops coming in one by one; the
        // loops come in over its last seconds. A downpour arriving at once has the heavy recording's beginning.
        boolean heard = light != null || medium != null || heavy != null || intro != null;
        if (rain > 0.02f && !heard && dryTicks > DRY_FOR_INTRO) {
            boolean hard = LocalWeather.rainComing() >= 0.5f && has(HEAVY_START);
            if (hard || has(LIGHT_START)) {
                intro = new Track(SoundEvent.createVariableRangeEvent(hard ? HEAVY_START : LIGHT_START), false);
                intro.level = hard ? HEAVY_LEVEL : LIGHT_LEVEL;
                intro.startAt(cover * intro.level);
                Minecraft.getInstance().getSoundManager().play(intro);
            }
        }
        dryTicks = rain > 0.02f ? 0 : dryTicks + 1;
        float hold = 1f;
        if (intro != null) {
            intro.age++;
            if (intro.isStopped() || intro.age > INTRO_TICKS + 40
                    || !Minecraft.getInstance().getSoundManager().isActive(intro) && intro.age > 20) {
                intro = null;
            } else {
                hold = smooth((intro.age - HANDOVER_FROM) / (float) (INTRO_TICKS - HANDOVER_FROM));
                intro.target = rain < 0.015f ? 0f : cover * intro.level;
            }
        }
        light = track(light, LIGHT, LIGHT_LEVEL * l * cover * hold);
        medium = has(MEDIUM) ? track(medium, MEDIUM, MEDIUM_LEVEL * m * cover * hold) : null;
        heavy = track(heavy, HEAVY, HEAVY_LEVEL * h * cover * hold);
        // A breath of wind in the open, more of it high up and in a strong wind; none indoors or under water.
        if (has(WIND)) {
            float speed = Mth.sqrt(LocalWeather.windX() * LocalWeather.windX() + LocalWeather.windZ() * LocalWeather.windZ());
            float height = 0.4f + 0.6f * smooth((float) (player.getY() - 70) / 90f);
            float w = smooth((speed - 0.3f) / 3f) * height * open * open;
            if (player.isUnderWater()) w = 0;
            wind = track(wind, WIND, WIND_LEVEL * w * com.jeladastudios.ftsgeology.config.ClientConfig.WIND_VOLUME.get().floatValue());
        }
        // Heard, or not: with no stream to play on, vanilla's rain stands in until one is free again.
        var sounds = Minecraft.getInstance().getSoundManager();
        boolean playing = false;
        for (Track t : new Track[]{light, medium, heavy, intro}) if (t != null && sounds.isActive(t)) playing = true;
        long now = level.getGameTime();
        if (playing || rain * cover < 0.02f || now < heardAt) heardAt = now;
        boolean was = standIn;
        standIn = now - heardAt > 100;
        if (standIn != was) {
            GeysersMod.LOGGER.info(standIn ? "Rain recordings are not playing (no free sound stream): vanilla's rain stands in"
                    : "Rain recordings are playing again");
        }
    }

    /**
     * Keeps one recording playing at this volume: started when it is wanted, and started again when the sound engine has
     * dropped it or never began it (no free stream, or the engine was reloaded), a try every two seconds.
     */
    private static Track track(Track t, ResourceLocation id, float volume) {
        if (t != null && !t.isStopped()) {
            if (Minecraft.getInstance().getSoundManager().isActive(t)) t.unheard = 0;
            else if (++t.unheard > 40) t.end();
        }
        if (t != null && t.isStopped()) t = null;
        if (t == null) {
            if (volume < 0.01f) return null;
            t = new Track(SoundEvent.createVariableRangeEvent(id), true);
            Minecraft.getInstance().getSoundManager().play(t);
        }
        t.target = volume;
        return t;
    }

    private static void stop() {
        for (Track t : new Track[]{light, medium, heavy, wind, intro}) if (t != null) t.end();
        light = medium = heavy = wind = intro = null;
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

    /**
     * One recording, not placed anywhere: a loop, or a rain's beginning played once; its volume eased towards what the
     * rain calls for. Indoors it is only quieter: lowered in pitch as well, the rain sounded dull and boomy.
     */
    static final class Track extends AbstractTickableSoundInstance {
        float target, level = 1f;
        int age;
        /** Ticks the sound engine has not been playing it since it was asked to. */
        int unheard;
        private int silent;

        Track(SoundEvent event, boolean loop) {
            super(event, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
            this.looping = loop;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0.001f;
        }

        @Override
        public void tick() {
            volume += Mth.clamp(target - volume, -0.01f, 0.01f);
            silent = target < 0.01f && volume < 0.02f ? silent + 1 : 0;
            if (silent > 40) stop();
        }

        /** Starts at once at this volume: a beginning fades itself in. */
        void startAt(float v) {
            volume = v;
            target = v;
        }

        void end() {
            stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
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
        stop();
        wet = 0;
        recordings = null;
        HAS.clear();
        dryTicks = Integer.MAX_VALUE / 2;
    }
}
