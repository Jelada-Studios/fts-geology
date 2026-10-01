package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.weather.LocalWeather;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * The overworld's sky effects where the rain is the player's own (see {@link LocalWeather}): the rain drawn as it
 * falls there. A drizzle is a few streaks, clearly seen, not a faint veil; a downpour is many, falling faster and in
 * longer streaks; the wind drives them aslant; and the drops splash on the ground and on water, more of them the harder
 * it rains, and big splashes in a downpour. Snow is drawn as vanilla draws it. Where the server does not tell the
 * weather, all of it is left to vanilla. Drawn as vanilla draws its rain, so shader packs that restyle the rain still do.
 */
public class SkyEffects extends DimensionSpecialEffects.OverworldEffects {

    private static final ResourceLocation RAIN = new ResourceLocation("textures/environment/rain.png");
    private static final ResourceLocation SNOW = new ResourceLocation("textures/environment/snow.png");
    /** How fast the drops fall, blocks a second, against which the wind slants them. */
    private static final float FALL = 7f;

    private final float[] sizeX = new float[1024], sizeZ = new float[1024];
    private final CloudRenderer clouds = new CloudRenderer();

    /**
     * Each column's streak of rain, where it starts and where it stops, worked out along its slant when the camera moves
     * a block, the wind turns or half a second has passed; and whether it stops on something (a splash there).
     */
    private final int[] streakTop = new int[1024], streakBottom = new int[1024];
    private final boolean[] landed = new boolean[1024];
    private int laidX = Integer.MIN_VALUE, laidY, laidZ, laidR, laidTop;
    private float laidSx, laidSz;
    private long laidAt;
    private double laidCamY;

    /** The weather's clouds where the server tells the sky and the player has them on; vanilla's otherwise. */
    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partial, com.mojang.blaze3d.vertex.PoseStack pose,
                                double camX, double camY, double camZ, org.joml.Matrix4f projection) {
        if (!com.jeladastudios.ftsgeology.config.ClientConfig.STORM_CLOUDS.get() || !ClientSky.ready()) return false;
        return clouds.render(level, partial, pose, camX, camY, camZ, projection);
    }

    public SkyEffects() {
        // Each column's streak faces the middle, as vanilla's does.
        for (int i = 0; i < 32; i++) {
            for (int j = 0; j < 32; j++) {
                float dz = j - 16, dx = i - 16, d = Mth.sqrt(dz * dz + dx * dx);
                sizeX[i << 5 | j] = -dx / d;
                sizeZ[i << 5 | j] = dz / d;
            }
        }
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partial, LightTexture light, double camX, double camY, double camZ) {
        if (!LocalWeather.active()) return false;
        float f = level.getRainLevel(partial);
        if (f <= 0f) return true;
        // Under a shader pack, drawn in its weather phase so that the pack styles it as it styles vanilla's rain; left to
        // vanilla where that phase cannot be set.
        OculusWeather.Begun phase = null;
        if (OculusWeather.shadersOn()) {
            phase = OculusWeather.begin();
            if (phase == null) return false;
        }
        try {
            draw(level, ticks, partial, light, camX, camY, camZ, f, phase != null ? phase.writeDepth() : Minecraft.useShaderTransparency());
        } finally {
            if (phase != null) OculusWeather.end(phase);
        }
        return true;
    }

    private void draw(ClientLevel level, int ticks, float partial, LightTexture light, double camX, double camY, double camZ,
                      float f, boolean depth) {
        light.turnOnLightLayer();
        int x0 = Mth.floor(camX), y0 = Mth.floor(camY), z0 = Mth.floor(camZ);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        boolean fancy = Minecraft.useFancyGraphics();
        int r = fancy ? 10 + Math.round(4 * f) : 5;
        RenderSystem.depthMask(depth);
        RenderSystem.setShader(GameRenderer::getParticleShader);
        int drawing = -1;
        float time = ticks + partial;
        // A drizzle is a few streaks clearly seen; the harder it rains, the more of them, faster and longer.
        float keep = 0.3f + 0.7f * smooth(f / 0.6f);
        float alpha = 0.45f + 0.55f * f;
        float speed = 0.85f + 0.6f * f;
        float vScale = 0.25f * (1.15f - 0.35f * f);
        float sx = slant(LocalWeather.windX()), sz = slant(LocalWeather.windZ());
        // The drops come from the cloud's underside: under it, from no higher up than that.
        float under = ClientSky.deckBase(level, camX, camZ);
        int ceil = !Float.isNaN(under) && camY < under ? Math.max(y0 - r, Math.min(y0 + r, Mth.floor(under))) : y0 + r;
        layStreaks(level, x0, y0, z0, r, ceil, camY, sx, sz);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int z = z0 - r; z <= z0 + r; z++) {
            for (int x = x0 - r; x <= x0 + r; x++) {
                int idx = (z - z0 + 16) * 32 + x - x0 + 16;
                double hx = sizeX[idx] * 0.5, hz = sizeZ[idx] * 0.5;
                m.set(x, camY, z);
                Biome biome = level.getBiome(m).value();
                if (!biome.hasPrecipitation()) continue;
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int bottom = Math.max(y0 - r, ground), top = Math.max(ceil, ground);
                int lit = Math.max(ground, y0);
                RandomSource rnd = RandomSource.create((long) (x * x * 3121 + x * 45238971 ^ z * z * 418711 + z * 13761));
                m.set(x, bottom, z);
                Biome.Precipitation fall = biome.getPrecipitationAt(m);
                if (fall == Biome.Precipitation.RAIN) {
                    // Along its slant: stopped by the first thing in its way, so it blows in under an eave or a doorway.
                    bottom = streakBottom[idx];
                    top = streakTop[idx];
                    lit = Math.max(bottom, y0);
                }
                if (bottom >= top) continue;
                m.set(x, lit, z);
                int lightColor = LevelRenderer.getLightColor(level, m);
                if (fall == Biome.Precipitation.RAIN) {
                    if (rnd.nextFloat() > keep) continue;
                    if (drawing != 0) {
                        if (drawing >= 0) tesselator.end();
                        drawing = 0;
                        RenderSystem.setShaderTexture(0, RAIN);
                        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                    }
                    int phase = ticks + x * x * 3121 + x * 45238971 + z * z * 418711 + z * 13761 & 31;
                    float v = -(phase + partial) / 32f * (3f + rnd.nextFloat()) * speed;
                    double dx = x + 0.5 - camX, dz = z + 0.5 - camZ;
                    float d = (float) Math.sqrt(dx * dx + dz * dz) / r;
                    float a = ((1f - d * d) * 0.5f + 0.5f) * alpha;
                    // Aslant in the wind: higher up, a drop is further upwind of where it lands.
                    double tx = -sx * (top - camY), tz = -sz * (top - camY), bx = -sx * (bottom - camY), bz = -sz * (bottom - camY);
                    buffer.vertex(x - camX - hx + 0.5 + tx, top - camY, z - camZ - hz + 0.5 + tz).uv(0f, bottom * vScale + v)
                            .color(1f, 1f, 1f, a).uv2(lightColor).endVertex();
                    buffer.vertex(x - camX + hx + 0.5 + tx, top - camY, z - camZ + hz + 0.5 + tz).uv(1f, bottom * vScale + v)
                            .color(1f, 1f, 1f, a).uv2(lightColor).endVertex();
                    buffer.vertex(x - camX + hx + 0.5 + bx, bottom - camY, z - camZ + hz + 0.5 + bz).uv(1f, top * vScale + v)
                            .color(1f, 1f, 1f, a).uv2(lightColor).endVertex();
                    buffer.vertex(x - camX - hx + 0.5 + bx, bottom - camY, z - camZ - hz + 0.5 + bz).uv(0f, top * vScale + v)
                            .color(1f, 1f, 1f, a).uv2(lightColor).endVertex();
                } else if (fall == Biome.Precipitation.SNOW) {
                    if (drawing != 1) {
                        if (drawing >= 0) tesselator.end();
                        drawing = 1;
                        RenderSystem.setShaderTexture(0, SNOW);
                        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                    }
                    float v = -((ticks & 511) + partial) / 512f;
                    float du = (float) (rnd.nextDouble() + time * 0.01 * (float) rnd.nextGaussian());
                    float dv = (float) (rnd.nextDouble() + time * (float) rnd.nextGaussian() * 0.001);
                    double dx = x + 0.5 - camX, dz = z + 0.5 - camZ;
                    float d = (float) Math.sqrt(dx * dx + dz * dz) / r;
                    float a = ((1f - d * d) * 0.3f + 0.5f) * f;
                    int sky = lightColor >> 16 & 65535, block = lightColor & 65535;
                    int sl = (sky * 3 + 240) / 4, bl = (block * 3 + 240) / 4;
                    buffer.vertex(x - camX - hx + 0.5, top - camY, z - camZ - hz + 0.5).uv(0f + du, bottom * 0.25f + v + dv)
                            .color(1f, 1f, 1f, a).uv2(bl, sl).endVertex();
                    buffer.vertex(x - camX + hx + 0.5, top - camY, z - camZ + hz + 0.5).uv(1f + du, bottom * 0.25f + v + dv)
                            .color(1f, 1f, 1f, a).uv2(bl, sl).endVertex();
                    buffer.vertex(x - camX + hx + 0.5, bottom - camY, z - camZ + hz + 0.5).uv(1f + du, top * 0.25f + v + dv)
                            .color(1f, 1f, 1f, a).uv2(bl, sl).endVertex();
                    buffer.vertex(x - camX - hx + 0.5, bottom - camY, z - camZ - hz + 0.5).uv(0f + du, top * 0.25f + v + dv)
                            .color(1f, 1f, 1f, a).uv2(bl, sl).endVertex();
                }
            }
        }
        if (drawing >= 0) tesselator.end();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        light.turnOffLightLayer();
    }

    /** How far a drop moves sideways for each block it falls in this wind, held to the player's setting. */
    private static float slant(float wind) {
        float most = com.jeladastudios.ftsgeology.config.ClientConfig.RAIN_SLANT.get().floatValue();
        return Mth.clamp(wind / FALL, -most, most);
    }

    /**
     * Each column's streak, a straight line through the column's middle at eye height, downwind as it falls: from the top
     * of the drawn space, if the sky is open above it there, down to the first block in its way or the bottom of the
     * drawn space. Worked out again when the camera moves a block, the slant turns, or half a second has passed.
     */
    private void layStreaks(ClientLevel level, int x0, int y0, int z0, int r, int top, double camY, float sx, float sz) {
        long now = level.getGameTime();
        if (x0 == laidX && y0 == laidY && z0 == laidZ && r == laidR && top == laidTop && Math.abs(sx - laidSx) < 0.02f
                && Math.abs(sz - laidSz) < 0.02f && now - laidAt < 10 && now >= laidAt) return;
        laidX = x0;
        laidY = y0;
        laidZ = z0;
        laidR = r;
        laidTop = top;
        laidSx = sx;
        laidSz = sz;
        laidAt = now;
        laidCamY = camY;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int low = y0 - r;
        for (int z = z0 - r; z <= z0 + r; z++) {
            for (int x = x0 - r; x <= x0 + r; x++) {
                int idx = (z - z0 + 16) * 32 + x - x0 + 16;
                streakTop[idx] = top;
                streakBottom[idx] = top;
                landed[idx] = false;
                // Above the drawn space the drop comes straight from the sky only where nothing stands over that point.
                int ax = Mth.floor(x + 0.5 + sx * (camY - top)), az = Mth.floor(z + 0.5 + sz * (camY - top));
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, ax, az) > top) continue;
                int bottom = low;
                for (int y = top - 1; y >= low; y--) {
                    double fallen = camY - (y + 0.5);
                    int bx = Mth.floor(x + 0.5 + sx * fallen), bz = Mth.floor(z + 0.5 + sz * fallen);
                    if (y >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz)) continue;
                    BlockState s = level.getBlockState(m.set(bx, y, bz));
                    if (s.blocksMotion() || !s.getFluidState().isEmpty()) {
                        bottom = y + 1;
                        landed[idx] = true;
                        break;
                    }
                }
                streakBottom[idx] = bottom;
            }
        }
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        // Without the recordings, or while the sound engine has no stream for them, vanilla's own rain sound is kept, and
        // vanilla plays it here.
        if (!LocalWeather.active() || !ClientWeather.recordingsHeard()) return false;
        Minecraft mc = Minecraft.getInstance();
        float f = level.getRainLevel(1f) / (Minecraft.useFancyGraphics() ? 1f : 2f);
        if (f <= 0f || mc.options.particles().get() == ParticleStatus.MINIMAL) return true;
        RandomSource rnd = RandomSource.create((long) ticks * 312987231L);
        BlockPos at = BlockPos.containing(camera.getPosition());
        // More splashes the harder it rains, and some even in a drizzle; a downpour throws up big ones as well.
        int n = (int) (12 + 160 * f) / (mc.options.particles().get() == ParticleStatus.DECREASED ? 2 : 1);
        // Where the streaks land, aslant: under an eave or inside a doorway too when the wind blows the rain in.
        boolean laid = laidX != Integer.MIN_VALUE && Math.abs(laidX - at.getX()) <= 1 && Math.abs(laidZ - at.getZ()) <= 1;
        int reach = laid ? Math.min(laidR, 10) : 10;
        for (int i = 0; i < n; i++) {
            int ox = rnd.nextInt(2 * reach + 1) - reach, oz = rnd.nextInt(2 * reach + 1) - reach;
            BlockPos top;
            double px, pz;
            if (laid) {
                int idx = (oz + 16) * 32 + ox + 16;
                if (!landed[idx]) continue;
                int y = streakBottom[idx];
                double fallen = laidCamY - y;
                double lx = laidX + ox + 0.5 + laidSx * fallen + (rnd.nextDouble() - 0.5) * 0.8;
                double lz = laidZ + oz + 0.5 + laidSz * fallen + (rnd.nextDouble() - 0.5) * 0.8;
                top = BlockPos.containing(lx, y, lz);
                px = lx - top.getX();
                pz = lz - top.getZ();
                // The jitter can step off the block the streak met, into a wall or over an edge: no splash there.
                BlockState under = level.getBlockState(top.below());
                if (level.getBlockState(top).blocksMotion() || !under.blocksMotion() && under.getFluidState().isEmpty()) continue;
            } else {
                top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, at.offset(ox, 0, oz));
                px = rnd.nextDouble();
                pz = rnd.nextDouble();
            }
            if (top.getY() <= level.getMinBuildHeight() || top.getY() > at.getY() + 10 || top.getY() < at.getY() - 10) continue;
            if (level.getBiome(top).value().getPrecipitationAt(top) != Biome.Precipitation.RAIN) continue;
            BlockPos on = top.below();
            BlockState state = level.getBlockState(on);
            FluidState fluid = level.getFluidState(on);
            double h = Math.max(state.getCollisionShape(level, on).max(Direction.Axis.Y, px, pz), fluid.getHeight(level, on));
            ParticleOptions p = fluid.is(FluidTags.LAVA) || state.is(Blocks.MAGMA_BLOCK) || CampfireBlock.isLitCampfire(state)
                    ? ParticleTypes.SMOKE
                    : f > 0.6f && rnd.nextInt(6) == 0 ? ParticleTypes.SPLASH : ParticleTypes.RAIN;
            level.addParticle(p, on.getX() + px, on.getY() + h, on.getZ() + pz, 0, 0, 0);
        }
        return true;
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3 - 2 * t);
    }
}
