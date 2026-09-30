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
 * <p>The rain is heard in two strengths, light and heavy, from recordings of real rain in place of vanilla's, one
 * fading into the other as the rain grows; indoors it is quieter and duller, under water nearly gone, and in snow or a
 * dry biome not there at all. Water drips from leaves and from the edges of roofs while it rains and for a minute or
 * two after, and a downpour closes the view in a little. The recordings are not part of the public source (their
 * licence is not known); a build without them keeps vanilla's rain sound.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientWeather {

    private ClientWeather() {}

    private static final ResourceLocation LIGHT = new ResourceLocation("fts_geology_rain", "rain_light");
    private static final ResourceLocation HEAVY = new ResourceLocation("fts_geology_rain", "rain_heavy");

    private static Boolean recordings;
    private static RainLoop light, heavy;
    /** How wet the world round the player still is: the rain, and after it a while of dripping. */
    private static float wet;
    /** How open to the sky the player stands, 0 indoors to 1 in the open, worked out every half second. */
    private static float open = 1f;

    public static void set(LocalWeatherPacket p) {
        LocalWeather.set(p);
    }

    /** Whether the jar carries the rain recordings. */
    private static boolean recordings() {
        if (recordings == null) {
            recordings = Minecraft.getInstance().getResourceManager()
                    .getResource(new ResourceLocation("fts_geology_rain", "sounds/rain_light.ogg")).isPresent();
        }
        return recordings;
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
        LocalWeather.tick();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
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
        if (level.getGameTime() % 10 == 0) open = openness(level, player);
        sound(level, player, rain);
        drips(level, player);
    }

    // === Sound ================================================================

    /** Vanilla's rain is not played where the recordings play instead. */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        if (event.getSound() == null || !recordings()) return;
        ResourceLocation id = event.getSound().getLocation();
        if (id.getNamespace().equals("minecraft") && (id.getPath().equals("weather.rain") || id.getPath().equals("weather.rain.above"))) {
            event.setSound(null);
        }
    }

    private static void sound(ClientLevel level, LocalPlayer player, float rain) {
        if (!recordings()) return;
        // Light rain fades in from a drizzle and gives way to the heavy recording as the rain grows.
        float l = smooth((rain - 0.03f) / 0.2f) * (1f - smooth((rain - 0.45f) / 0.25f));
        float h = smooth((rain - 0.4f) / 0.3f);
        float cover = 0.25f + 0.75f * open;
        if (player.isUnderWater()) cover *= 0.2f;
        float pitch = open < 0.3f ? 0.88f : 1f;
        light = loop(light, LIGHT, 0.7f * l * cover, pitch);
        heavy = loop(heavy, HEAVY, 1.0f * h * cover, pitch);
    }

    private static RainLoop loop(RainLoop loop, ResourceLocation id, float volume, float pitch) {
        if (loop != null && loop.isStopped()) loop = null;
        if (loop == null) {
            if (volume < 0.01f) return null;
            loop = new RainLoop(SoundEvent.createVariableRangeEvent(id));
            Minecraft.getInstance().getSoundManager().play(loop);
        }
        loop.target = volume;
        loop.targetPitch = pitch;
        return loop;
    }

    private static void stop() {
        if (light != null) light.end();
        if (heavy != null) heavy.end();
        light = heavy = null;
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

    /** One rain recording, looping, not placed anywhere: its volume eased towards what the rain calls for. */
    static final class RainLoop extends AbstractTickableSoundInstance {
        float target, targetPitch = 1f;
        private int silent;

        RainLoop(SoundEvent event) {
            super(event, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0.001f;
        }

        @Override
        public void tick() {
            volume += Mth.clamp(target - volume, -0.02f, 0.02f);
            pitch += Mth.clamp(targetPitch - pitch, -0.01f, 0.01f);
            silent = target < 0.01f && volume < 0.02f ? silent + 1 : 0;
            if (silent > 40) stop();
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

    /** A downpour closes the view in: at the heaviest rain, by about a third, outdoors. */
    @SubscribeEvent
    public static void onFog(ViewportEvent.RenderFog event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || event.getType() != FogType.NONE || mc.player == null || open < 0.3f) return;
        float h = smooth((intensity(mc.level) - 0.5f) / 0.5f);
        if (h <= 0) return;
        float k = 1f - 0.35f * h;
        event.setFarPlaneDistance(event.getFarPlaneDistance() * k);
        event.setNearPlaneDistance(event.getNearPlaneDistance() * k);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LocalWeather.reset();
        stop();
        wet = 0;
        recordings = null;
    }
}
