package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.ClientConfig;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.jeladastudios.ftsgeology.weather.LocalWeather;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Foam lying on the rivers, drawn on the water rather than thrown up as particles: thick at the foot of every fall
 * down a step and for a few blocks after it, streaks on the fast water above a step and along the banks, a little more
 * in rain, none on still water.
 *
 * <p>Drawn the way the game draws everything, in pixels: sixteen to a block's side, each one foam or not, with a hard
 * edge, in three shades -- white, a light grey, and at its fringe a white gone the colour of the water -- and carried
 * down the current a whole pixel at a time. A smooth sheet with soft edges, the first way it was drawn, looked like
 * nothing else in the world round it. In cold country the foam is less and takes more of the water's colour, so that
 * it is not taken for snow. It lies flat on the water and ends at a step's lip.</p>
 *
 * <p>The water round the player is read every half second, the pixels laid out every tick from that, and drawn each
 * frame lit as the water there is and fogged as particles are. The shades are set colours on vanilla's own blank
 * texture: no texture of the mod's. Nothing here is the server's.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class RiverFoamMesh {

    private RiverFoamMesh() {}

    /** How far round the player the rivers are read, and how often, in ticks. */
    private static final int RADIUS = 24, RESCAN = 10, HEIGHT_REACH = 40;
    /** Pixels to a block's side, as the game's textures. */
    private static final int PX = 16;
    /** How thick the foam is down a reach after a fall, block by block from its foot. */
    private static final float[] AFTER_FALL = {1.0f, 0.85f, 0.65f, 0.45f, 0.3f, 0.18f};
    /** How much of the foam there is, all told: less than the sheet had, which covered too much of the water. */
    private static final float SHARE = 0.8f;
    /** Foam in cold country, against the rest. */
    private static final float COLD = 0.7f;
    private static final ResourceLocation WHITE = new ResourceLocation("textures/misc/white.png");

    /** One block of water with foam on it: where its surface is, how much foam, which way it runs, how it is lit. */
    private record Spot(int x, int z, float top, float amount, float dx, float dz, int light, boolean fall, int water,
                        boolean cold) {}

    private static List<Spot> spots = List.of();
    /** The foam of each block read, and its surface, by column, for the soft edges between blocks. */
    private static Long2FloatOpenHashMap amounts = new Long2FloatOpenHashMap(), tops = new Long2FloatOpenHashMap();
    private static long scannedAt = -RESCAN;
    /** The pixels to draw, laid out every tick: per run of foam pixels along a row, x0 z0 x1 z1 y r g b a light. */
    private static FloatArrayList runs = new FloatArrayList(), spare = new FloatArrayList();
    private static final int STRIDE = 10;
    /** Foam thinner than this nowhere shows: the pattern seldom stands so high. */
    private static final float THINNEST = 0.15f;

    /** Whether the foam is drawn this way: on, and not as particles. */
    public static boolean on() {
        return ClientConfig.RIVER_FOAM.get() && ClientConfig.RIVER_FOAM_SHEET.get();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || !on()) {
            spots = List.of();
            runs.clear();
            return;
        }
        long now = level.getGameTime();
        if (now - scannedAt >= RESCAN || now < scannedAt) {
            scannedAt = now;
            scan(level, mc.player.blockPosition());
        }
        long t0 = System.nanoTime();
        lay(now, mc.player.getX(), mc.player.getZ());
        layMs = 0.95 * layMs + 0.05 * (System.nanoTime() - t0) / 1e6;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Reads the river surface round the player and how much foam lies on each block of it. */
    private static void scan(ClientLevel level, BlockPos at) {
        List<Spot> found = new ArrayList<>();
        Long2FloatOpenHashMap amount = new Long2FloatOpenHashMap(), top = new Long2FloatOpenHashMap();
        float rain = LocalWeather.active() ? LocalWeather.rain(1f) : level.getRainLevel(1f);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = at.getX() - RADIUS; x <= at.getX() + RADIUS; x++) {
            for (int z = at.getZ() - RADIUS; z <= at.getZ() + RADIUS; z++) {
                int y = surface(level, x, z, m);
                if (y == Integer.MIN_VALUE || Math.abs(y - at.getY()) > HEIGHT_REACH) continue;
                FluidState f = level.getFluidState(m.set(x, y, z));
                if (!f.hasProperty(RiverWaterFluid.FLOW)) continue;
                // A mod that runs the water says which way it runs (see the API's hydraulics).
                int flow = RiverWaterFluid.runningWay(level, m.set(x, y, z), f);
                Vec3 way = RiverWaterFluid.way(flow);
                float a = foam(level, x, y, z, way, flow, rain, m);
                boolean cold = level.getBiome(m.set(x, y, z)).value().coldEnoughToSnow(m);
                a *= SHARE * (cold ? COLD : 1f);
                if (a < 0.02f) continue;
                // At the water's drawn height: a mod's hook may draw it under the block's own (see the API).
                float drawn = com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.surface(level, m.set(x, y, z), f);
                float surface = y + (Float.isNaN(drawn) ? f.getHeight(level, m.set(x, y, z)) : drawn);
                amount.put(key(x, z), a);
                top.put(key(x, z), surface);
                found.add(new Spot(x, z, surface, a, (float) way.x, (float) way.z, LevelRenderer.getLightColor(level, m.set(x, y + 1, z)),
                        fallInto(level, x, y, z, m), BiomeColors.getAverageWaterColor(level, m.set(x, y, z)), cold));
            }
        }
        spots = found;
        amounts = amount;
        tops = top;
    }

    /**
     * The water's surface in a column: the top of the water that is not falling, under any curtain falling into it.
     * MIN_VALUE where the column's top is not water.
     */
    private static int surface(ClientLevel level, int x, int z, BlockPos.MutableBlockPos m) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        FluidState f = level.getFluidState(m.set(x, y, z));
        if (!f.is(FluidTags.WATER)) return Integer.MIN_VALUE;
        for (int i = 0; i < 6 && falling(f); i++) {
            y--;
            f = level.getFluidState(m.set(x, y, z));
        }
        return f.is(FluidTags.WATER) && !falling(f) ? y : Integer.MIN_VALUE;
    }

    /** How much foam lies on the water at a block, 0 to 1. */
    private static float foam(ClientLevel level, int x, int y, int z, Vec3 way, int flow, float rain, BlockPos.MutableBlockPos m) {
        float a = 0f;
        // The foot of a fall, and the reach after it.
        if (fallInto(level, x, y, z, m)) a = 1f;
        if (flow > 0) {
            for (int k = 1; k < AFTER_FALL.length && a < AFTER_FALL[k]; k++) {
                int ux = x - (int) Math.round(way.x * k), uz = z - (int) Math.round(way.z * k);
                if (fallInto(level, ux, y, uz, m)) a = Math.max(a, AFTER_FALL[k]);
            }
        }
        // Beside a fall the churned water spreads a little.
        if (a < 0.45f) {
            for (int dx = -1; dx <= 1 && a < 0.45f; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && fallInto(level, x + dx, y, z + dz, m)) {
                        a = Math.max(a, 0.45f);
                        break;
                    }
                }
            }
        }
        if (flow == 0) return a;
        // The fast water above a step: the next block down the current is lower.
        int nx = x + (int) Math.round(way.x), nz = z + (int) Math.round(way.z);
        FluidState next = level.getFluidState(m.set(nx, y, nz));
        if (!next.is(FluidTags.WATER) || falling(next)) a = Math.max(a, 0.25f);
        // Along a bank or round a rock in the current: a thin line.
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockState s = level.getBlockState(m.set(x + d[0], y, z + d[1]));
            if (!s.isAir() && s.getFluidState().isEmpty() && s.isCollisionShapeFullBlock(level, m)) {
                a = Math.max(a, 0.25f);
                break;
            }
        }
        return Math.max(a, 0.04f + 0.1f * rain);
    }

    /** Whether water falls into this block from over it: the foot of a curtain down a step. */
    private static boolean fallInto(ClientLevel level, int x, int y, int z, BlockPos.MutableBlockPos m) {
        return falling(level.getFluidState(m.set(x, y + 1, z)));
    }

    private static boolean falling(FluidState f) {
        return f.hasProperty(FlowingFluid.FALLING) && f.getValue(FlowingFluid.FALLING);
    }

    // === The pixels ========================================================

    /**
     * Lays out this tick's foam pixels. For every block read, each of its sixteen by sixteen pixels is foam where the
     * pattern there, carried down the current to now, stands over what the foam's amount at that pixel lets through;
     * its shade is from how far over. Neighbouring foam pixels along a row are one run, drawn as one quad.
     */
    private static void lay(long now, double px, double pz) {
        FloatArrayList out = spare;
        out.clear();
        Long2FloatOpenHashMap amount = amounts, top = tops;
        for (Spot s : spots) {
            if ((s.x() + 0.5 - px) * (s.x() + 0.5 - px) + (s.z() + 0.5 - pz) * (s.z() + 0.5 - pz) > RADIUS * RADIUS) continue;
            float a00 = corner(amount, top, s.x(), s.z(), s.top()), a10 = corner(amount, top, s.x() + 1, s.z(), s.top());
            float a01 = corner(amount, top, s.x(), s.z() + 1, s.top()), a11 = corner(amount, top, s.x() + 1, s.z() + 1, s.top());
            // Too thin anywhere on the block for the pattern ever to stand over it: nothing to lay out.
            if (Math.max(Math.max(a00, a10), Math.max(a01, a11)) < THINNEST) continue;
            float dx = s.dx(), dz = s.dz();
            boolean still = dx == 0 && dz == 0;
            if (still) dx = 1f;
            // How far the pattern has come down the current, in whole pixels: a block and a bit a second on a river.
            int shift = (int) (now * (still ? 0.06 : 0.9));
            int wr = s.water() >> 16 & 255, wg = s.water() >> 8 & 255, wb = s.water() & 255;
            float tint = s.cold() ? 0.45f : 0.2f;
            float y = s.top() + 0.01f;
            for (int j = 0; j < PX; j++) {
                int run = -1, runShade = -1;
                for (int i = 0; i <= PX; i++) {
                    int shade = -1;
                    if (i < PX) {
                        float fx = (i + 0.5f) / PX, fz = (j + 0.5f) / PX;
                        float a = Mth.lerp(fz, Mth.lerp(fx, a00, a10), Mth.lerp(fx, a01, a11));
                        if (a > 0.02f) {
                            int wx = s.x() * PX + i, wz = s.z() * PX + j;
                            int along = (int) Math.round((double) wx * dx + (double) wz * dz) - shift;
                            int across = (int) Math.round(-(double) wx * dz + (double) wz * dx);
                            float n = s.fall() ? BLOBS[(along & 63) * 64 + (across & 63)] : CLUMPS[(along & 63) * 64 + (across & 63)];
                            float over = n - (1f - a);
                            if (over > 0f) shade = over > 0.16f ? 0 : over > 0.06f ? 1 : 2;
                            // A speck here and there, as the game's own textures have.
                            if (shade == 0 && speck(wx, wz) < 0.12f) shade = 1;
                        }
                    }
                    if (shade == runShade) continue;
                    if (run >= 0) emit(out, s, run, i, j, runShade, y, wr, wg, wb, tint);
                    run = shade >= 0 ? i : -1;
                    runShade = shade;
                }
            }
        }
        spare = runs;
        runs = out;
    }

    /** One run of pixels of a shade: from pixel {@code i0} to {@code i1} along row {@code j} of a block. */
    private static void emit(FloatArrayList out, Spot s, int i0, int i1, int j, int shade, float y, int wr, int wg, int wb, float tint) {
        float r, g, b, alpha;
        switch (shade) {
            case 0 -> { r = 0.94f; g = 0.96f; b = 0.97f; alpha = 0.9f; }
            case 1 -> { r = 0.80f; g = 0.85f; b = 0.88f; alpha = 0.8f; }
            default -> {
                // The fringe takes half the water's colour.
                r = 0.5f + 0.5f * wr / 255f; g = 0.5f + 0.5f * wg / 255f; b = 0.5f + 0.5f * wb / 255f; alpha = 0.55f;
            }
        }
        r = Mth.lerp(tint, r, wr / 255f);
        g = Mth.lerp(tint, g, wg / 255f);
        b = Mth.lerp(tint, b, wb / 255f);
        out.add(s.x() + i0 / (float) PX);
        out.add(s.z() + j / (float) PX);
        out.add(s.x() + i1 / (float) PX);
        out.add(s.z() + (j + 1) / (float) PX);
        out.add(y);
        out.add(r);
        out.add(g);
        out.add(b);
        out.add(alpha);
        out.add(Float.intBitsToFloat(s.light()));
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        FloatArrayList draw = runs;
        if (draw.isEmpty() || !on()) {
            drawn = 0;
            return;
        }
        long t0 = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = pose.last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderTexture(0, WHITE);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        float[] v = draw.elements();
        int n = draw.size() / STRIDE;
        for (int k = 0; k < n; k++) {
            int o = k * STRIDE;
            float x0 = v[o], z0 = v[o + 1], x1 = v[o + 2], z1 = v[o + 3], y = v[o + 4];
            float r = v[o + 5], g = v[o + 6], bl = v[o + 7], a = v[o + 8];
            int light = Float.floatToRawIntBits(v[o + 9]);
            vertex(b, matrix, x0, y, z0, r, g, bl, a, light);
            vertex(b, matrix, x0, y, z1, r, g, bl, a, light);
            vertex(b, matrix, x1, y, z1, r, g, bl, a, light);
            vertex(b, matrix, x1, y, z0, r, g, bl, a, light);
        }
        drawn = n;
        BufferBuilder.RenderedBuffer done = b.end();
        if (n > 0) com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(done);
        else done.release();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        pose.popPose();
        drawMs = 0.95 * drawMs + 0.05 * (System.nanoTime() - t0) / 1e6;
    }

    /**
     * How many blocks of the river carry foam round the player now, on the debug screen (F3). Forge asks for the debug
     * screen's lines every frame, open or not, and draws what it is given: only while it is open.
     */
    @SubscribeEvent
    public static void onDebugText(net.minecraftforge.client.event.CustomizeGuiOverlayEvent.DebugText event) {
        if (Minecraft.getInstance().level == null || !Minecraft.getInstance().options.renderDebug || !on()) return;
        int cold = 0;
        for (Spot s : spots) if (s.cold()) cold++;
        event.getLeft().add("River foam: " + spots.size() + " blocks (" + cold + " cold), " + drawn
                + " runs of pixels drawn, " + String.format(java.util.Locale.ROOT, "%.2f ms a tick, %.2f ms a frame", layMs, drawMs));
    }

    /** Runs drawn in the last frame, for the debug screen. */
    private static int drawn;
    /** What laying the pixels out takes a tick and drawing them a frame, smoothed, for the debug screen. */
    private static double layMs, drawMs;

    private static void vertex(BufferBuilder b, Matrix4f m, float x, float y, float z, float r, float g, float bl, float alpha, int light) {
        b.vertex(m, x, y, z).uv(0.5f, 0.5f).color(r, g, bl, alpha).uv2(light).endVertex();
    }

    /**
     * The foam at a block's corner: the mean of the four blocks that meet there, a block of water a step up or down
     * counted as none -- the foot of a fall does not spread its foam onto the lip above it.
     */
    private static float corner(Long2FloatOpenHashMap amount, Long2FloatOpenHashMap top, int x, int z, float at) {
        return 0.25f * (level(amount, top, x, z, at) + level(amount, top, x - 1, z, at) + level(amount, top, x, z - 1, at)
                + level(amount, top, x - 1, z - 1, at));
    }

    private static float level(Long2FloatOpenHashMap amount, Long2FloatOpenHashMap top, int x, int z, float at) {
        long k = key(x, z);
        return top.containsKey(k) && Math.abs(top.get(k) - at) < 0.5f ? amount.get(k) : 0f;
    }

    // === The patterns ======================================================

    /**
     * The foam's two patterns, made once, 64 pixels square and repeating without a seam: clumps a little drawn out
     * along the current, which break up and shrink as the foam thins down the reach, and the smaller, closer clots
     * churned up at the foot of a fall. Long streaks, the first way it was drawn, stood up off the water like flames.
     * Values 0 to 1.
     */
    private static final float[] CLUMPS = pattern(64, 64, 7, 5, 3, 3), BLOBS = pattern(64, 64, 5, 5, 2, 2);

    /** A repeating pattern {@code w} by {@code h} pixels, of two octaves of value noise with these cell sizes. */
    private static float[] pattern(int w, int h, int c1u, int c1v, int c2u, int c2v) {
        float[] out = new float[w * h];
        for (int u = 0; u < w; u++) {
            for (int v = 0; v < h; v++) {
                double n = 0.65 * tiled(u, v, c1u, c1v, w, h, 0) + 0.35 * tiled(u, v, c2u, c2v, w, h, 1);
                out[u * h + v] = (float) n;
            }
        }
        return out;
    }

    /** Smooth value noise with cells {@code cu} by {@code cv} pixels, repeating every {@code w} by {@code h}. */
    private static double tiled(int u, int v, int cu, int cv, int w, int h, int salt) {
        int pu = Math.max(1, w / cu), pv = Math.max(1, h / cv);
        double fu = u / (double) cu, fv = v / (double) cv;
        int iu = Mth.floor(fu), iv = Mth.floor(fv);
        double tu = fu - iu, tv = fv - iv;
        tu = tu * tu * (3 - 2 * tu);
        tv = tv * tv * (3 - 2 * tv);
        double a = hash(Math.floorMod(iu, pu), Math.floorMod(iv, pv), salt), b = hash(Math.floorMod(iu + 1, pu), Math.floorMod(iv, pv), salt);
        double c = hash(Math.floorMod(iu, pu), Math.floorMod(iv + 1, pv), salt), d = hash(Math.floorMod(iu + 1, pu), Math.floorMod(iv + 1, pv), salt);
        return Mth.lerp(tv, Mth.lerp(tu, a, b), Mth.lerp(tu, c, d));
    }

    /** 0 to 1, fixed for a pixel of the world. */
    private static float speck(int x, int z) {
        return (float) hash(x, z, 7);
    }

    private static double hash(int x, int y, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ salt * 0x632BE59BD9B4E019L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }
}
