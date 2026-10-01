package com.jeladastudios.ftsgeology.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The weather's own clouds, in place of vanilla's flat layer (see {@link ClientSky} for what the sky holds): boxes on a
 * twelve-block grid, as vanilla's are, but where the weather puts them. Under a high a few small white heaps; under a
 * low a grey deck; over a storm a heavy, dark and low mass with a lumpy underside, and over a thunderstorm towers heaped
 * up far higher. A cloud is thicker the further inside its patch, so it rises in a dome, and its sides darken towards
 * its foot. The field drifts on the wind aloft, and the lightning inside a thundercloud lights up the underside of the
 * cloud round it. Drawn with vanilla's cloud shader and fog, the geometry set out round the camera as vanilla's is (the
 * shader's fog reckons distance from there), so a shader pack's cloud program applies to it as to vanilla's.
 */
final class CloudRenderer {

    private static final int CELL = 12, R = 40, N = 2 * R + 1;
    private static final ResourceLocation WHITE = new ResourceLocation("textures/misc/white.png");

    private VertexBuffer clouds, glow;
    /** The cloud field last worked out: its corner cell, when, and each cell's underside and top over the cloud height (NaN where clear). */
    private int gridI = Integer.MIN_VALUE, gridJ;
    private long sampledAt;
    private final float[] base = new float[N * N], top = new float[N * N], dark = new float[N * N];
    private final float[] sample = new float[4];
    /** What the mesh was last built for: the camera's cell and height step, the colour and the cloud setting. */
    private int meshI = Integer.MIN_VALUE, meshJ, meshY;
    private Vec3 meshColor;
    private CloudStatus meshType;
    private boolean remesh;

    boolean render(ClientLevel level, float partial, PoseStack pose, double camX, double camY, double camZ, Matrix4f projection) {
        float height = level.effects().getCloudHeight();
        if (Float.isNaN(height)) return true;
        double dx = ClientSky.driftX(partial), dz = ClientSky.driftZ(partial);
        int ci = Mth.floor((camX - dx) / CELL), cj = Mth.floor((camZ - dz) / CELL), cy = Mth.floor(camY / 4) * 4;
        Vec3 color = level.getCloudColor(partial);
        CloudStatus type = Minecraft.getInstance().options.getCloudsType();
        long now = level.getGameTime();
        if (gridI == Integer.MIN_VALUE || Math.abs(ci - R - gridI) > 4 || Math.abs(cj - R - gridJ) > 4 || now - sampledAt > 40 || now < sampledAt) {
            sampleGrid(ci - R, cj - R, now, dx, dz);
        }
        if (remesh || ci != meshI || cj != meshJ || cy != meshY || meshColor == null || meshColor.distanceToSqr(color) > 2.0E-4 || type != meshType) {
            mesh(ci, cj, cy, height, color, type);
        }
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionTexColorNormalShader);
        RenderSystem.setShaderTexture(0, WHITE);
        FogRenderer.levelFogColor();
        pose.pushPose();
        // Only what the camera has moved within its cell since the mesh was built: the rest is in the vertices.
        pose.translate(ci * CELL + dx - camX, cy - camY, cj * CELL + dz - camZ);
        if (clouds != null) {
            clouds.bind();
            // As vanilla does: depth first, then colour, so a cloud's far faces do not show through its near ones.
            for (int pass = type == CloudStatus.FANCY ? 0 : 1; pass < 2; pass++) {
                RenderSystem.colorMask(pass == 1, pass == 1, pass == 1, pass == 1);
                ShaderInstance shader = RenderSystem.getShader();
                clouds.drawWithShader(pose.last().pose(), projection, shader);
            }
            VertexBuffer.unbind();
        }
        flashes(pose, projection, dx, dz, height);
        pose.popPose();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        return true;
    }

    /** Works out the cloud field round the camera: which cells hold cloud, how low its underside, how high its top. */
    private void sampleGrid(int i0, int j0, long now, double dx, double dz) {
        gridI = i0;
        gridJ = j0;
        sampledAt = now;
        remesh = true;
        float[] cell = new float[3];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int k = j * N + i;
                cell(i0 + i, j0 + j, now, dx, dz, sample, cell);
                base[k] = cell[0];
                top[k] = cell[1];
                dark[k] = cell[2];
            }
        }
    }

    /**
     * One cell of the field: {@code out[0]} its underside and {@code out[1]} its top over the cloud height (NaN where the
     * sky is clear), {@code out[2]} how dark.
     */
    private static void cell(int fi, int fj, long now, double dx, double dz, float[] sample, float[] out) {
        ClientSky.sample(fi * CELL + CELL / 2.0 + dx, fj * CELL + CELL / 2.0 + dz, now, sample);
        float cover = sample[0], d = sample[1], tower = sample[2];
        float n = noise(fi, fj);
        // The noise's spread is about 0.15 round a half: this share of the cells falls under the line.
        float line = 0.5f + 0.088f * (float) Math.log(cover / (1f - cover));
        // Over the rain there is no gap: a cell the noise would leave open is still a thin cloud there.
        boolean raining = sample[3] > 0.02f;
        if (n >= line && !raining) {
            out[0] = out[1] = Float.NaN;
            out[2] = 0;
            return;
        }
        float depth = Math.max((line - n) / 0.15f, raining ? 0.3f : 0f);
        // Fair-weather heaps rise in domes; a low's deck is thick and flat-bottomed; a thunderstorm towers.
        float thick = (d > 0.35f ? 10f + 12f * depth : 6f + 13f * depth) + tower * 60f * Math.min(depth, 2f);
        // A storm's underside hangs a little lower, in soft lumps; a fair-weather cloud's is flat.
        out[0] = -8f * d - smoothNoise(fi / 3f, fj / 3f, 57) * 3f * d;
        out[1] = out[0] + Math.min(thick, 140f);
        out[2] = d;
    }

    /**
     * The cloud over a place as drawn: {@code out[0]} its underside and {@code out[1]} its top, in world height; false where
     * the sky over it is clear, or the clouds are not this mod's.
     */
    static boolean deckAt(ClientLevel level, double x, double z, float partial, float[] out) {
        float height = level.effects().getCloudHeight();
        if (Float.isNaN(height) || !ClientSky.ready()) return false;
        double dx = ClientSky.driftX(partial), dz = ClientSky.driftZ(partial);
        float[] sample = new float[4], cell = new float[3];
        cell(Mth.floor((x - dx) / CELL), Mth.floor((z - dz) / CELL), level.getGameTime(), dx, dz, sample, cell);
        if (Float.isNaN(cell[0])) return false;
        out[0] = height + cell[0];
        out[1] = height + cell[1];
        return true;
    }

    /** Builds the clouds' mesh round the camera's cell and height step. */
    private void mesh(int ci, int cj, int cy, float height, Vec3 color, CloudStatus type) {
        meshI = ci;
        meshJ = cj;
        meshY = cy;
        meshColor = color;
        meshType = type;
        remesh = false;
        int count = 0;
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        float r = (float) color.x, g = (float) color.y, bl = (float) color.z;
        boolean fancy = type == CloudStatus.FANCY;
        float lift = height - cy;
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int k = j * N + i;
                if (Float.isNaN(base[k])) continue;
                float x0 = (gridI + i - ci) * CELL, x1 = x0 + CELL, z0 = (gridJ + j - cj) * CELL, z1 = z0 + CELL;
                float y0 = lift + base[k], y1 = lift + top[k], d = dark[k];
                count++;
                float a = Math.min(1f, 0.85f + 0.2f * d);
                // Its underside the darker the more cloud above it.
                float under = 0.72f - 0.28f * d - 0.1f * Math.min(1f, (y1 - y0) / 60f), over = 1f - 0.4f * d;
                // A storm's sides are nearly as dark as its underside: it is one dark mass, not a lit box.
                float sideX = Mth.lerp(d, 0.9f, under + 0.05f), sideZ = Mth.lerp(d, 0.82f, under + 0.03f);
                quad(b, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r * under, g * under, bl * under, a, 0, -1, 0);
                if (!fancy) continue;
                quad(b, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r * over, g * over, bl * over, a, 0, 1, 0);
                side(b, i - 1, j, k, lift, x0, z0, x0, z1, r * sideX, g * sideX, bl * sideX, a, -1, 0);
                side(b, i + 1, j, k, lift, x1, z1, x1, z0, r * sideX, g * sideX, bl * sideX, a, 1, 0);
                side(b, i, j - 1, k, lift, x1, z0, x0, z0, r * sideZ, g * sideZ, bl * sideZ, a, 0, -1);
                side(b, i, j + 1, k, lift, x0, z1, x1, z1, r * sideZ, g * sideZ, bl * sideZ, a, 0, 1);
            }
        }
        if (clouds != null) clouds.close();
        clouds = null;
        if (count == 0) {
            b.end().release();
            return;
        }
        clouds = new VertexBuffer(VertexBuffer.Usage.STATIC);
        clouds.bind();
        clouds.upload(b.end());
        VertexBuffer.unbind();
    }

    /**
     * A cell's side towards a neighbour, where the neighbour does not cover it: all of it, or above the neighbour's top,
     * and below the neighbour's underside where this cell hangs lower (else the lumpy underside of a storm shows the sky
     * through thin cracks between its cells).
     */
    private void side(BufferBuilder b, int ni, int nj, int k, float lift, float xa, float za, float xb, float zb,
                      float r, float g, float bl, float a, float nx, float nz) {
        float y0 = base[k], y1 = top[k];
        if (ni >= 0 && ni < N && nj >= 0 && nj < N) {
            int n = nj * N + ni;
            if (!Float.isNaN(base[n])) {
                if (base[n] > y0) span(b, lift, y0, Math.min(y1, base[n]), xa, za, xb, zb, r, g, bl, a, nx, nz);
                if (top[n] >= y1) return;
                y0 = Math.max(y0, top[n]);
            }
        }
        span(b, lift, y0, y1, xa, za, xb, zb, r, g, bl, a, nx, nz);
    }

    private static void span(BufferBuilder b, float lift, float y0, float y1, float xa, float za, float xb, float zb,
                             float r, float g, float bl, float a, float nx, float nz) {
        if (y1 <= y0) return;
        y0 += lift;
        y1 += lift;
        // Darker low down, where less light reaches: a side shades from its top to its foot.
        float foot = 0.78f;
        b.vertex(xa, y0, za).uv(0.5f, 0.5f).color(r * foot, g * foot, bl * foot, a).normal(nx, 0, nz).endVertex();
        b.vertex(xb, y0, zb).uv(0.5f, 0.5f).color(r * foot, g * foot, bl * foot, a).normal(nx, 0, nz).endVertex();
        b.vertex(xb, y1, zb).uv(0.5f, 0.5f).color(r, g, bl, a).normal(nx, 0, nz).endVertex();
        b.vertex(xa, y1, za).uv(0.5f, 0.5f).color(r, g, bl, a).normal(nx, 0, nz).endVertex();
    }

    private static void quad(BufferBuilder b, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float r, float g, float bl, float a, float nx, float ny, float nz) {
        b.vertex(x0, y0, z0).uv(0.5f, 0.5f).color(r, g, bl, a).normal(nx, ny, nz).endVertex();
        b.vertex(x1, y1, z1).uv(0.5f, 0.5f).color(r, g, bl, a).normal(nx, ny, nz).endVertex();
        b.vertex(x2, y2, z2).uv(0.5f, 0.5f).color(r, g, bl, a).normal(nx, ny, nz).endVertex();
        b.vertex(x3, y3, z3).uv(0.5f, 0.5f).color(r, g, bl, a).normal(nx, ny, nz).endVertex();
    }

    /** The lightning inside the clouds: the undersides and tops round each flash lit, added onto the cloud. */
    private void flashes(PoseStack pose, Matrix4f projection, double dx, double dz, float height) {
        var list = ClientSky.flashes();
        if (list.isEmpty() || gridI == Integer.MIN_VALUE) return;
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        int quads = 0;
        float lift = height - meshY;
        for (ClientSky.Flash f : list) {
            float bright = f.now();
            if (bright < 0.05f) continue;
            int fi = Mth.floor((f.x() - dx) / CELL) - gridI, fj = Mth.floor((f.z() - dz) / CELL) - gridJ;
            int reach = 13;
            for (int j = Math.max(0, fj - reach); j <= Math.min(N - 1, fj + reach); j++) {
                for (int i = Math.max(0, fi - reach); i <= Math.min(N - 1, fi + reach); i++) {
                    int k = j * N + i;
                    if (Float.isNaN(base[k])) continue;
                    float dist = Mth.sqrt((i - fi) * (i - fi) + (j - fj) * (j - fj)) / reach;
                    if (dist >= 1) continue;
                    float a = bright * (1 - dist) * (1 - dist);
                    float x0 = (gridI + i - meshI) * CELL, x1 = x0 + CELL, z0 = (gridJ + j - meshJ) * CELL, z1 = z0 + CELL;
                    float y0 = lift + base[k] - 0.05f, y1 = lift + top[k] + 0.05f;
                    quad(b, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0.85f, 0.88f, 1f, a, 0, -1, 0);
                    quad(b, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0.85f, 0.88f, 1f, a * 0.6f, 0, 1, 0);
                    quads++;
                }
            }
        }
        if (quads == 0) {
            b.end().release();
            return;
        }
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.colorMask(true, true, true, true);
        if (glow == null) glow = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        glow.bind();
        glow.upload(b.end());
        glow.drawWithShader(pose.last().pose(), projection, RenderSystem.getShader());
        VertexBuffer.unbind();
        RenderSystem.depthMask(true);
    }

    /** The cloud field's noise at a cell, about a half, spread some 0.15: broad patches and their ragged edges. */
    private static float noise(int i, int j) {
        return 0.6f * smoothNoise(i / 7f, j / 7f, 0) + 0.4f * smoothNoise(i / 2.5f, j / 2.5f, 101);
    }

    private static float smoothNoise(float x, float z, int salt) {
        int x0 = Mth.floor(x), z0 = Mth.floor(z);
        float fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        float a = lattice(x0 + salt, z0), b = lattice(x0 + 1 + salt, z0), c = lattice(x0 + salt, z0 + 1), d = lattice(x0 + 1 + salt, z0 + 1);
        return Mth.lerp(fz, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    private static float lattice(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }
}
