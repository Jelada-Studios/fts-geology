package com.jeladastudios.ftsgeology.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * What a river carries on its surface: foam where it comes down a step, and leaves and bits of twig from the trees on its
 * banks. They lie flat on the water and go with it: along the way it runs, down the falling water of a step, slowing to
 * a stop in still water and caught at a bank. Drawn with vanilla's own pictures -- the puff of its smoke for the foam,
 * the leaves' and the stick's textures for the rest -- so a resource pack's look carries over.
 */
public final class RiverParticles {

    private RiverParticles() {}

    /**
     * How fast the water carries a thing, blocks a tick, for each unit of the push it gives a swimmer: a river's 0.5
     * carries a leaf about a block a second.
     */
    private static final double CARRY = 0.06;


    /** A thing on the water: flat, carried, turning slowly. */
    abstract static class Floating extends TextureSheetParticle {
        protected final float spin;
        protected final double pace;
        protected float turn, oTurn;
        private int stranded;

        Floating(ClientLevel level, double x, double y, double z) {
            super(level, x, y, z, 0, 0, 0);
            this.hasPhysics = false;
            this.gravity = 0;
            this.xd = this.yd = this.zd = 0;
            this.spin = (this.random.nextFloat() - 0.5f) * 0.06f;
            this.turn = this.oTurn = this.random.nextFloat() * Mth.TWO_PI;
            this.pace = 0.75 + this.random.nextDouble() * 0.5;
            com.jeladastudios.ftsgeology.fluid.RiverLife.alive++;
        }

        @Override
        public void remove() {
            if (!this.removed) com.jeladastudios.ftsgeology.fluid.RiverLife.alive--;
            super.remove();
        }

        @Override
        public void tick() {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;
            this.oTurn = this.turn;
            if (this.age++ >= this.lifetime) {
                remove();
                return;
            }
            BlockPos at = BlockPos.containing(this.x, this.y - 0.05, this.z);
            FluidState f = this.level.getFluidState(at);
            if (!f.is(FluidTags.WATER)) {
                // Off the water: fallen over a step's edge, or left on a bank. Down a little to find it, else gone.
                FluidState under = this.level.getFluidState(at.below());
                if (under.is(FluidTags.WATER)) {
                    this.y -= 0.2;
                } else if (++this.stranded > 10) {
                    remove();
                }
                return;
            }
            this.stranded = 0;
            // Blocks a tick the surface carries a thing: a registered hydraulics' own speed, else from the push.
            Vec3 real = com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.flow(this.level, at, f);
            Vec3 carry = real != null ? real.scale(1.0 / 20.0) : f.getFlow(this.level, at).scale(CARRY * 2.0);
            boolean falling = f.hasProperty(net.minecraft.world.level.material.FlowingFluid.FALLING)
                    && f.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING);
            if (falling) {
                // Down the face of the step with the water, and a little forward.
                this.y -= 0.18;
                this.xd = carry.x * 0.5;
                this.zd = carry.z * 0.5;
            } else {
                // On the surface, carried at the water's pace; a still pool lets it drift to a stop.
                float drawn = com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.surface(this.level, at, f);
                double ty = at.getY() + (Float.isNaN(drawn) ? f.getHeight(this.level, at) : drawn) + 0.01;
                this.y += (ty - this.y) * 0.5;
                double tx = carry.x * this.pace, tz = carry.z * this.pace;
                this.xd += (tx - this.xd) * 0.08 + (this.random.nextDouble() - 0.5) * 0.002;
                this.zd += (tz - this.zd) * 0.08 + (this.random.nextDouble() - 0.5) * 0.002;
                if (carry.lengthSqr() < 1e-8) {
                    this.xd *= 0.96;
                    this.zd *= 0.96;
                }
            }
            // A bank stops it the way it was going; it slides along.
            if (blocked(this.x + this.xd, this.y - 0.05, this.z)) this.xd *= -0.2;
            if (blocked(this.x, this.y - 0.05, this.z + this.zd)) this.zd *= -0.2;
            this.x += this.xd;
            this.z += this.zd;
            this.turn += this.spin * (falling ? 4f : 1f);
            float t = this.age / (float) this.lifetime;
            this.alpha = Math.min(1f, this.age / 8f) * (t > 0.8f ? (1f - t) / 0.2f : 1f) * peak();
            this.setPos(this.x, this.y, this.z);
        }

        private boolean blocked(double x, double y, double z) {
            BlockPos p = BlockPos.containing(x, y, z);
            BlockState s = this.level.getBlockState(p);
            return s.getFluidState().isEmpty() && !s.getCollisionShape(this.level, p).isEmpty();
        }

        /** How opaque at its fullest. */
        protected float peak() {
            return 1f;
        }

        /** Flat on the water, both faces, turned about the upright. */
        @Override
        public void render(VertexConsumer buffer, Camera camera, float partial) {
            Vec3 cam = camera.getPosition();
            float px = (float) (Mth.lerp(partial, this.xo, this.x) - cam.x());
            float py = (float) (Mth.lerp(partial, this.yo, this.y) - cam.y());
            float pz = (float) (Mth.lerp(partial, this.zo, this.z) - cam.z());
            float a = Mth.lerp(partial, this.oTurn, this.turn), s = getQuadSize(partial);
            float c = Mth.cos(a) * s, d = Mth.sin(a) * s;
            float u0 = getU0(), u1 = getU1(), v0 = getV0(), v1 = getV1();
            int light = getLightColor(partial);
            float[][] corner = {{-c + d, -d - c}, {c + d, d - c}, {c - d, d + c}, {-c - d, -d + c}};
            float[][] uv = {{u1, v1}, {u1, v0}, {u0, v0}, {u0, v1}};
            for (int i = 0; i < 4; i++) {
                buffer.vertex(px + corner[i][0], py, pz + corner[i][1]).uv(uv[i][0], uv[i][1])
                        .color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
            }
            for (int i = 3; i >= 0; i--) {
                buffer.vertex(px + corner[i][0], py, pz + corner[i][1]).uv(uv[i][0], uv[i][1])
                        .color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
            }
        }
    }

    /** Foam churned up where the water falls: a pale patch that spreads and thins as it goes. */
    static class Foam extends Floating {
        private final float size;

        Foam(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
            super(level, x, y, z);
            this.lifetime = 50 + this.random.nextInt(60);
            this.size = 0.14f + this.random.nextFloat() * 0.12f;
            this.quadSize = this.size;
            float w = 0.92f + this.random.nextFloat() * 0.08f;
            this.rCol = w;
            this.gCol = w;
            this.bCol = Math.min(1f, w + 0.03f);
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            this.quadSize = this.size * (1f + this.age / (float) this.lifetime);
        }

        @Override
        protected float peak() {
            return 0.75f;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }
    }

    /** A leaf or a bit of twig from the bank, drifting down the river. */
    static class Drift extends Floating {
        Drift(ClientLevel level, double x, double y, double z, boolean twig) {
            super(level, x, y, z);
            this.lifetime = 500 + this.random.nextInt(400);
            Minecraft mc = Minecraft.getInstance();
            BlockPos at = BlockPos.containing(x, y, z);
            if (twig) {
                this.setSprite(mc.getItemRenderer().getModel(new ItemStack(Items.STICK), level, null, 0).getParticleIcon());
                this.quadSize = 0.10f + this.random.nextFloat() * 0.06f;
                this.rCol = this.gCol = this.bCol = 0.85f;
            } else {
                BlockState leaves = leavesFor(level, at);
                this.setSprite(mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(leaves));
                this.quadSize = 0.09f + this.random.nextFloat() * 0.06f;
                int color = mc.getBlockColors().getColor(leaves, level, at, 0);
                float shade = 0.8f + this.random.nextFloat() * 0.2f;
                this.rCol = (color >> 16 & 255) / 255f * shade;
                this.gCol = (color >> 8 & 255) / 255f * shade;
                this.bCol = (color & 255) / 255f * shade;
            }
        }

        /** The leaves of the kind of tree that grows round here, as near as the biome tells it. */
        private static BlockState leavesFor(ClientLevel level, BlockPos at) {
            var biome = level.getBiome(at);
            if (biome.is(net.minecraft.tags.BiomeTags.IS_TAIGA) || biome.value().coldEnoughToSnow(at)) return Blocks.SPRUCE_LEAVES.defaultBlockState();
            if (biome.is(net.minecraft.tags.BiomeTags.IS_JUNGLE)) return Blocks.JUNGLE_LEAVES.defaultBlockState();
            if (biome.is(net.minecraft.tags.BiomeTags.IS_SAVANNA)) return Blocks.ACACIA_LEAVES.defaultBlockState();
            if (biome.is(net.minecraft.world.level.biome.Biomes.CHERRY_GROVE)) return Blocks.CHERRY_LEAVES.defaultBlockState();
            if (biome.is(net.minecraft.world.level.biome.Biomes.BIRCH_FOREST)) return Blocks.BIRCH_LEAVES.defaultBlockState();
            return Blocks.OAK_LEAVES.defaultBlockState();
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.TERRAIN_SHEET;
        }
    }

    // === Providers ==========================================================

    public record FoamProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType t, ClientLevel l, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new Foam(l, x, y, z, sprites);
        }
    }

    public record LeafProvider() implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType t, ClientLevel l, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new Drift(l, x, y, z, false);
        }
    }

    public record TwigProvider() implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType t, ClientLevel l, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new Drift(l, x, y, z, true);
        }
    }
}
