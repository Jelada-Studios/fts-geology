package com.jeladastudios.ftsgeology.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * The volcano's own smoke and ash.
 *
 * <p>Vanilla's campfire smoke is a pale grey column that rises steadily and politely, which is what
 * a campfire does. An eruption column is black at the throat, lit from underneath by the lava it is
 * coming out of, swelling as it climbs, and pale only far up where the ash is fine. None of that is
 * a colour or a speed vanilla's particle has, so these carry it.</p>
 */
public final class VolcanicParticles {

    private VolcanicParticles() {}

    /** Black billowing smoke from the throat and the lower column, glowing for its first moments. */
    public static class Smoke extends TextureSheetParticle {
        private final float baseSize;

        Smoke(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
            super(level, x, y, z, 0, 0, 0);
            this.xd = vx; this.yd = vy; this.zd = vz;
            this.friction = 0.96f;
            this.gravity = 0.0f;
            this.lifetime = 80 + this.random.nextInt(50);
            this.baseSize = 1.6f + this.random.nextFloat();
            this.quadSize = this.baseSize;
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            float t = this.age / (float) this.lifetime;
            this.quadSize = this.baseSize * (1.0f + t * 1.4f);
            // Lava-red at the start, cooling to near black, then thinning out.
            float warm = Math.max(0.0f, 1.0f - t * 6.0f);
            this.rCol = 0.10f + 0.30f * warm;
            this.gCol = 0.09f + 0.10f * warm;
            this.bCol = 0.09f + 0.02f * warm;
            this.alpha = t < 0.6f ? 0.95f : 0.95f * (1.0f - (t - 0.6f) / 0.4f);
        }

        /** Lit by the lava underneath for its first moments, whatever the light where it is. */
        @Override
        protected int getLightColor(float partialTick) {
            return this.age < this.lifetime * 0.15f ? LightTexture.FULL_BRIGHT : super.getLightColor(partialTick);
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }
    }

    /** The pale cloud high in the column, where ash is fine enough to catch the light. Slow and huge. */
    public static class AshCloud extends TextureSheetParticle {
        private final float baseSize;

        AshCloud(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
            super(level, x, y, z, 0, 0, 0);
            this.xd = vx; this.yd = vy; this.zd = vz;
            this.friction = 0.98f;
            this.gravity = 0.0f;
            this.lifetime = 160 + this.random.nextInt(80);
            this.baseSize = 3.5f + this.random.nextFloat() * 2.5f;
            this.quadSize = this.baseSize;
            float grey = 0.52f + this.random.nextFloat() * 0.12f;
            this.rCol = grey; this.gCol = grey * 0.97f; this.bCol = grey * 0.93f;
            this.alpha = 0.0f;
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            float t = this.age / (float) this.lifetime;
            this.quadSize = this.baseSize * (1.0f + t * 0.6f);
            this.alpha = (t < 0.15f ? t / 0.15f : 1.0f - (t - 0.15f) / 0.85f) * 0.75f;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }
    }

    /** A flake of ash coming down, rocking a little as it falls. */
    public static class AshFlake extends TextureSheetParticle {
        private final float phase;

        AshFlake(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
            super(level, x, y, z, 0, 0, 0);
            this.xd = vx; this.yd = vy; this.zd = vz;
            this.friction = 0.98f;
            this.gravity = 0.015f;
            this.hasPhysics = true;
            this.lifetime = 100 + this.random.nextInt(40);
            this.quadSize = 0.10f + this.random.nextFloat() * 0.06f;
            float grey = 0.28f + this.random.nextFloat() * 0.12f;
            this.rCol = grey; this.gCol = grey; this.bCol = grey;
            this.phase = this.random.nextFloat() * 6.28f;
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            this.xd += Math.sin(this.age * 0.25f + this.phase) * 0.004;
            this.zd += Math.cos(this.age * 0.21f + this.phase) * 0.004;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
        }
    }

    /** Black smoke forced out of a flank chimney while the mountain erupts: quick, narrow, dense. */
    public static class VentSmoke extends TextureSheetParticle {
        private final float baseSize;

        VentSmoke(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
            super(level, x, y, z, 0, 0, 0);
            this.xd = vx; this.yd = vy; this.zd = vz;
            this.friction = 0.93f;
            this.gravity = 0.0f;
            this.lifetime = 50 + this.random.nextInt(30);
            this.baseSize = 0.8f + this.random.nextFloat() * 0.5f;
            this.quadSize = this.baseSize;
            this.rCol = 0.12f; this.gCol = 0.11f; this.bCol = 0.11f;
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            float t = this.age / (float) this.lifetime;
            this.quadSize = this.baseSize * (1.0f + t * 2.5f);
            this.alpha = 0.9f * (1.0f - t * t);
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }
    }

    // === Providers ==========================================================

    public record SmokeProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType t, ClientLevel l, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new Smoke(l, x, y, z, vx, vy, vz, sprites);
        }
    }

    public record CloudProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType t, ClientLevel l, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new AshCloud(l, x, y, z, vx, vy, vz, sprites);
        }
    }

    public record FlakeProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType t, ClientLevel l, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new AshFlake(l, x, y, z, vx, vy, vz, sprites);
        }
    }

    public record VentProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType t, ClientLevel l, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new VentSmoke(l, x, y, z, vx, vy, vz, sprites);
        }
    }
}
