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
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
 * Foam lying on the rivers, drawn as a sheet on the water rather than thrown up as particles: thick and white at the
 * foot of every fall down a step and for a few blocks after it, streaks on the fast water above a step and along the
 * banks, a little more in rain, none on still water. The sheet is a few pixels proud of the surface where it is thick,
 * soft at its edges, and its pattern is drawn out along the current and carried down it.
 *
 * <p>The water round the player is read every half second; each frame the foam is drawn from that, four by four cells
 * to a block, lit as the water there is and fogged as particles are. White from vanilla's own blank texture: no texture
 * of the mod's. Nothing here is the server's.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class RiverFoamMesh {

    private RiverFoamMesh() {}

    /** How far round the player the rivers are read, and how often, in ticks. */
    private static final int RADIUS = 24, RESCAN = 10, HEIGHT_REACH = 40;
    /** Cells to a block's side. */
    private static final int CELLS = 4;
    /** How thick the foam is down a reach after a fall, block by block from its foot. */
    private static final float[] AFTER_FALL = {1.0f, 0.9f, 0.75f, 0.6f, 0.45f, 0.3f};
    private static final ResourceLocation WHITE = new ResourceLocation("textures/misc/white.png");

    /** One block of water with foam on it: where its surface is, how much foam, which way it runs, how it is lit. */
    private record Spot(int x, int z, float top, float amount, float dx, float dz, int light) {}

    private static List<Spot> spots = List.of();
    /** The foam of each block read, and its surface, by column, for the soft edges between blocks. */
    private static Long2FloatOpenHashMap amounts = new Long2FloatOpenHashMap(), tops = new Long2FloatOpenHashMap();
    private static long scannedAt = -RESCAN;

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
            return;
        }
        long now = level.getGameTime();
        if (now - scannedAt < RESCAN && now >= scannedAt) return;
        scannedAt = now;
        scan(level, mc.player.blockPosition());
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
                int flow = f.getValue(RiverWaterFluid.FLOW);
                Vec3 way = RiverWaterFluid.way(flow);
                float a = foam(level, x, y, z, way, flow, rain, m);
                if (a < 0.02f) continue;
                float surface = y + f.getHeight(level, m.set(x, y, z));
                amount.put(key(x, z), a);
                top.put(key(x, z), surface);
                found.add(new Spot(x, z, surface, a, (float) way.x, (float) way.z, LevelRenderer.getLightColor(level, m.set(x, y + 1, z))));
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
        if (a < 0.5f) {
            for (int dx = -1; dx <= 1 && a < 0.5f; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && fallInto(level, x + dx, y, z + dz, m)) {
                        a = Math.max(a, 0.5f);
                        break;
                    }
                }
            }
        }
        if (flow == 0) return a;
        // The fast water above a step: the next block down the current is lower.
        int nx = x + (int) Math.round(way.x), nz = z + (int) Math.round(way.z);
        FluidState next = level.getFluidState(m.set(nx, y, nz));
        if (!next.is(FluidTags.WATER) || falling(next)) a = Math.max(a, 0.3f);
        // Along a bank or round a rock in the current: a thin line.
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockState s = level.getBlockState(m.set(x + d[0], y, z + d[1]));
            if (!s.isAir() && s.getFluidState().isEmpty() && s.isCollisionShapeFullBlock(level, m)) {
                a = Math.max(a, 0.3f);
                break;
            }
        }
        return Math.max(a, 0.05f + 0.12f * rain);
    }

    /** Whether water falls into this block from over it: the foot of a curtain down a step. */
    private static boolean fallInto(ClientLevel level, int x, int y, int z, BlockPos.MutableBlockPos m) {
        return falling(level.getFluidState(m.set(x, y + 1, z)));
    }

    private static boolean falling(FluidState f) {
        return f.hasProperty(FlowingFluid.FALLING) && f.getValue(FlowingFluid.FALLING);
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        List<Spot> draw = spots;
        if (draw.isEmpty() || !on()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        double t = (mc.level.getGameTime() + event.getPartialTick()) / 20.0;
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
        Long2FloatOpenHashMap amount = amounts, top = tops;
        float[][] alpha = new float[CELLS + 1][CELLS + 1], lift = new float[CELLS + 1][CELLS + 1];
        int count = 0;
        for (Spot s : draw) {
            if ((s.x() + 0.5 - cam.x) * (s.x() + 0.5 - cam.x) + (s.z() + 0.5 - cam.z) * (s.z() + 0.5 - cam.z) > RADIUS * RADIUS) continue;
            // The amount at the block's corners, the mean of the four blocks round each: soft edges from block to block.
            float a00 = corner(amount, top, s.x(), s.z(), s.top()), a10 = corner(amount, top, s.x() + 1, s.z(), s.top());
            float a01 = corner(amount, top, s.x(), s.z() + 1, s.top()), a11 = corner(amount, top, s.x() + 1, s.z() + 1, s.top());
            float dx = s.dx(), dz = s.dz();
            double speed = dx == 0 && dz == 0 ? 0.08 : 1.1;
            if (dx == 0 && dz == 0) dx = 1f;
            for (int i = 0; i <= CELLS; i++) {
                for (int j = 0; j <= CELLS; j++) {
                    float fx = i / (float) CELLS, fz = j / (float) CELLS;
                    float a = Mth.lerp(fz, Mth.lerp(fx, a00, a10), Mth.lerp(fx, a01, a11));
                    double wx = s.x() + fx, wz = s.z() + fz;
                    double along = wx * dx + wz * dz, across = -wx * dz + wz * dx;
                    double n = 0.65 * noise(along / 1.6 - t * speed, across / 0.45)
                            + 0.35 * noise(along / 0.6 - t * speed * 1.3, across / 0.25 + 17.0);
                    float v = Mth.clamp((float) ((n - (1.0 - a)) / 0.22), 0f, 1f);
                    alpha[i][j] = v * 0.85f;
                    lift[i][j] = 0.012f + 0.035f * v;
                }
            }
            int light = s.light();
            for (int i = 0; i < CELLS; i++) {
                for (int j = 0; j < CELLS; j++) {
                    if (alpha[i][j] + alpha[i + 1][j] + alpha[i][j + 1] + alpha[i + 1][j + 1] < 0.02f) continue;
                    float x0 = s.x() + i / (float) CELLS, x1 = s.x() + (i + 1) / (float) CELLS;
                    float z0 = s.z() + j / (float) CELLS, z1 = s.z() + (j + 1) / (float) CELLS;
                    vertex(b, matrix, x0, s.top() + lift[i][j], z0, alpha[i][j], light);
                    vertex(b, matrix, x0, s.top() + lift[i][j + 1], z1, alpha[i][j + 1], light);
                    vertex(b, matrix, x1, s.top() + lift[i + 1][j + 1], z1, alpha[i + 1][j + 1], light);
                    vertex(b, matrix, x1, s.top() + lift[i + 1][j], z0, alpha[i + 1][j], light);
                    count++;
                }
            }
        }
        drawn = count;
        BufferBuilder.RenderedBuffer done = b.end();
        if (count > 0) com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(done);
        else done.release();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /** How many blocks of the river carry foam round the player now, on the debug screen (F3). */
    @SubscribeEvent
    public static void onDebugText(net.minecraftforge.client.event.CustomizeGuiOverlayEvent.DebugText event) {
        if (Minecraft.getInstance().level == null || !on()) return;
        event.getLeft().add("River foam: " + spots.size() + " blocks, " + drawn + " cells drawn");
    }

    /** Cells drawn in the last frame, for the debug screen. */
    private static int drawn;

    private static void vertex(BufferBuilder b, Matrix4f m, float x, float y, float z, float alpha, int light) {
        b.vertex(m, x, y, z).uv(0.5f, 0.5f).color(0.95f, 0.97f, 1.0f, alpha).uv2(light).endVertex();
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

    /** Smooth value noise, 0 to 1, on a unit grid. */
    private static double noise(double u, double v) {
        int iu = Mth.floor(u), iv = Mth.floor(v);
        double fu = u - iu, fv = v - iv;
        fu = fu * fu * (3 - 2 * fu);
        fv = fv * fv * (3 - 2 * fv);
        double a = hash(iu, iv), b = hash(iu + 1, iv), c = hash(iu, iv + 1), d = hash(iu + 1, iv + 1);
        return Mth.lerp(fv, Mth.lerp(fu, a, b), Mth.lerp(fu, c, d));
    }

    private static double hash(int x, int y) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }
}
