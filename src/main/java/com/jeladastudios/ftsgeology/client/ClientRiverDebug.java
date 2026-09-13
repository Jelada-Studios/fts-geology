package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.hydrology.RiverDebug;
import com.jeladastudios.ftsgeology.network.RiverDebugPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws the river debug view: the channel centre lines (one colour a river), flow arrows, the bends the survey has
 * planned with the side they cut and the side they build, and a summary of the nearest river in the corner.
 * The server sends the view once a second while {@code /geology debug river on}; it fades when it stops.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientRiverDebug {

    private ClientRiverDebug() {}

    private static volatile RiverDebugPacket latest;
    private static volatile long receivedAt;

    /** How long a view is drawn after the last packet, so it goes away when the command is turned off. */
    private static final long STALE_MS = 3000;

    public static void accept(RiverDebugPacket packet) {
        latest = packet;
        receivedAt = System.currentTimeMillis();
    }

    private static RiverDebugPacket current() {
        RiverDebugPacket p = latest;
        if (p == null || System.currentTimeMillis() - receivedAt > STALE_MS) return null;
        return p;
    }

    /** One colour a river: a hue from the network's key, bright and saturated. */
    private static int colour(long river) {
        if (river == 0) return 0x8899BB;   // a river the network has not read: grey-blue
        float hue = (float) (((river * 0x9E3779B97F4A7C15L) >>> 40) % 360) / 360.0f;
        return Mth.hsvToRgb(hue, 0.85f, 1.0f);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        RiverDebugPacket p = current();
        if (p == null || Minecraft.getInstance().level == null) return;

        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        Matrix4f mat = pose.last().pose();
        Matrix3f normal = pose.last().normal();

        // Centre line cells, so neighbouring cells can be joined across chunk edges.
        Map<Long, float[]> cells = new HashMap<>();   // (x, z) -> y, colour
        for (RiverDebugPacket.ChunkInfo c : p.chunks()) {
            int rgb = colour(c.river());
            byte[] pts = c.centre();
            for (int i = 0; i + 1 < pts.length; i += 2) {
                int x = c.cx() * 16 + pts[i], z = c.cz() * 16 + pts[i + 1];
                cells.put(((long) x << 32) ^ (z & 0xFFFFFFFFL), new float[] {c.yW() + 1.2f, rgb});
            }
        }
        int[][] steps = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (Map.Entry<Long, float[]> e : cells.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
            float y = e.getValue()[0];
            int rgb = (int) e.getValue()[1];
            boolean joined = false;
            for (int[] s : steps) {
                float[] n = cells.get(((long) (x + s[0]) << 32) ^ ((z + s[1]) & 0xFFFFFFFFL));
                if (n == null) continue;
                joined = true;
                line(vc, mat, normal, x + 0.5f, y, z + 0.5f, x + s[0] + 0.5f, n[0], z + s[1] + 0.5f, rgb);
            }
            // A lone cell still shows as a short tick.
            if (!joined) line(vc, mat, normal, x + 0.5f, y, z + 0.5f, x + 0.5f, y + 1.0f, z + 0.5f, rgb);
        }

        for (RiverDebugPacket.ChunkInfo c : p.chunks()) {
            int rgb = colour(c.river());
            // The flow arrow, from the chunk's first centre cell.
            if ((c.fx() != 0 || c.fz() != 0) && c.centre().length >= 2) {
                float x = c.cx() * 16 + c.centre()[0] + 0.5f, z = c.cz() * 16 + c.centre()[1] + 0.5f, y = c.yW() + 2.0f;
                arrow(vc, mat, normal, x, y, z, c.fx() * 6, c.fz() * 6, rgb);
            }
            for (RiverDebugPacket.BendInfo b : c.bends()) {
                int box = b.dead() ? 0x808080 : b.done() >= b.steps() ? 0x40FF40 : 0xFFFFFF;
                float half = Math.max(1.0f, b.width() / 2.0f);
                LevelRenderer.renderLineBox(pose, vc, b.x() + 0.5 - half, c.yW() + 0.5, b.z() + 0.5 - half,
                        b.x() + 0.5 + half, c.yW() + 1.5, b.z() + 0.5 + half,
                        ((box >> 16) & 255) / 255f, ((box >> 8) & 255) / 255f, (box & 255) / 255f, 1.0f);
                // The cut bank in red, the bar in yellow, each as far as the bend still has to go.
                float reach = Math.max(2.0f, b.steps());
                float ax = b.x() + 0.5f, az = b.z() + 0.5f, ay = c.yW() + 1.5f;
                arrow(vc, mat, normal, ax, ay, az, b.nx() * reach, b.nz() * reach, 0xFF4040);
                arrow(vc, mat, normal, ax, ay, az, -b.nx() * reach, -b.nz() * reach, 0xFFE040);
            }
        }
        RenderSystem.lineWidth(3.0f);
        buffers.endBatch(RenderType.lines());
        RenderSystem.lineWidth(1.0f);
        pose.popPose();
    }

    private static void arrow(VertexConsumer vc, Matrix4f mat, Matrix3f normal, float x, float y, float z,
                              float dx, float dz, int rgb) {
        float ex = x + dx, ez = z + dz;
        line(vc, mat, normal, x, y, z, ex, y, ez, rgb);
        double len = Math.hypot(dx, dz);
        if (len < 0.5) return;
        double ux = dx / len, uz = dz / len;
        double h = Math.min(2.0, len * 0.35);
        // Two barbs at thirty degrees.
        for (int s = -1; s <= 1; s += 2) {
            double bx = -ux * 0.866 + s * uz * 0.5, bz = -uz * 0.866 - s * ux * 0.5;
            line(vc, mat, normal, ex, y, ez, (float) (ex + bx * h), y, (float) (ez + bz * h), rgb);
        }
    }

    private static void line(VertexConsumer vc, Matrix4f mat, Matrix3f normal, float x1, float y1, float z1,
                             float x2, float y2, float z2, int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-4f) return;
        dx /= len; dy /= len; dz /= len;
        vc.vertex(mat, x1, y1, z1).color(r, g, b, 1.0f).normal(normal, dx, dy, dz).endVertex();
        vc.vertex(mat, x2, y2, z2).color(r, g, b, 1.0f).normal(normal, dx, dy, dz).endVertex();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        RiverDebugPacket p = current();
        Minecraft mc = Minecraft.getInstance();
        if (p == null || mc.player == null || mc.options.hideGui) return;
        List<String> lines = new ArrayList<>();
        int bends = 0;
        for (RiverDebugPacket.ChunkInfo c : p.chunks()) bends += c.bends().size();
        lines.add(String.format(Locale.ROOT, "River debug: %d river chunks, %d bends", p.chunks().size(), bends));

        // The nearest chunk and the nearest bend to the player.
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        RiverDebugPacket.ChunkInfo nearChunk = null;
        double best = Double.MAX_VALUE;
        RiverDebugPacket.BendInfo nearBend = null;
        int nearBendY = 0;
        double bestBend = Double.MAX_VALUE;
        for (RiverDebugPacket.ChunkInfo c : p.chunks()) {
            double d = Math.hypot(c.cx() * 16 + 8 - px, c.cz() * 16 + 8 - pz);
            if (d < best) { best = d; nearChunk = c; }
            for (RiverDebugPacket.BendInfo b : c.bends()) {
                double db = Math.hypot(b.x() - px, b.z() - pz);
                if (db < bestBend) { bestBend = db; nearBend = b; nearBendY = c.yW(); }
            }
        }
        if (nearChunk != null) {
            lines.add(String.format(Locale.ROOT, "chunk %d,%d (%d m): %s", nearChunk.cx(), nearChunk.cz(), (int) best,
                    RiverDebug.stateName(nearChunk.state())));
            lines.add(String.format(Locale.ROOT, "  river #%s, water Y %d, flow (%.2f, %.2f), speed %.2f",
                    RiverDebug.riverName(nearChunk.river()), nearChunk.yW(), nearChunk.fx(), nearChunk.fz(), nearChunk.speed()));
        }
        if (nearBend != null) {
            lines.add(String.format(Locale.ROOT, "bend %d,%d,%d (%d m): width %d, step %d of %d, next in %d min%s",
                    nearBend.x(), nearBendY, nearBend.z(), (int) bestBend, nearBend.width(), nearBend.done(), nearBend.steps(),
                    nearBend.nextTicks() / 1200, nearBend.dead() ? ", dead" : ""));
            if (!nearBend.why().isEmpty()) lines.add("  last: " + nearBend.why());
        }
        GuiGraphics g = event.getGuiGraphics();
        int y = 4;
        for (String s : lines) {
            g.drawString(mc.font, s, 4, y, 0xFFFFFF, true);
            y += 10;
        }
    }
}
