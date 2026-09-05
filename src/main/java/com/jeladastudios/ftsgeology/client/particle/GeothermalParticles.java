package com.jeladastudios.ftsgeology.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * The three geothermal particles, and the behaviour that is the whole reason they exist.
 *
 * <p>A particle is mostly its motion. The sprites here are soft blobs and could stand in for each
 * other; what makes one read as pressurised water and another as thick mud is entirely how it moves
 * and how it dies, which is what each class below is.</p>
 */
public final class GeothermalParticles {

    private GeothermalParticles() {}

    /**
     * Water thrown up under pressure.
     *
     * <p>Fires upward fast and is then dragged down hard, so it decelerates as it climbs and turns
     * over at the top - which is what makes it read as something <i>thrown</i> rather than something
     * rising. It also swells as it slows: a jet of water shears into fog as it loses speed, so the
     * particle grows while it fades.</p>
     */
    public static class Mist extends TextureSheetParticle {
        private final float baseSize;

        Mist(ClientLevel level, double x, double y, double z, double vx, double vy, double vz,
             SpriteSet sprites) {
            super(level, x, y, z, 0, 0, 0);
            this.xd = vx; this.yd = vy; this.zd = vz;
            this.friction = 0.92f;
            this.gravity = 0.06f;                 // enough to arch it over, not enough to rain
            this.lifetime = 26 + this.random.nextInt(18);
            this.baseSize = 0.7f + this.random.nextFloat() * 0.6f;
            this.quadSize = this.baseSize;
            this.setSpriteFromAge(sprites);
            this.rCol = this.gCol = this.bCol = 1.0f;
        }

        @Override
        public void tick() {
            super.tick();
            float t = this.age / (float) this.lifetime;
            // Spreads as it slows, and thins out at the same time, so the top of the column is a
            // cloud rather than a row of droplets.
            this.quadSize = this.baseSize * (1.0f + t * 2.2f);
            this.alpha = 1.0f - t * t;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }
    }

    /**
     * A gobbet of mud out of a mud pot.
     *
     * <p>Heavy and short-lived: it goes up slowly, comes down quickly, and is finished. Mud pots
     * are not fountains - they are a thick paste being pushed up by gas, so the motion has to look
     * reluctant.</p>
     */
    public static class MudBlob extends TextureSheetParticle {
        MudBlob(ClientLevel level, double x, double y, double z, double vx, double vy, double vz,
                SpriteSet sprites) {
            super(level, x, y, z, 0, 0, 0);
            this.xd = vx; this.yd = vy; this.zd = vz;
            this.friction = 0.86f;
            this.gravity = 1.1f;                  // heavier than water, and it shows
            this.lifetime = 12 + this.random.nextInt(10);
            this.quadSize = 0.5f + this.random.nextFloat() * 0.5f;
            this.hasPhysics = true;               // it lands and stops, rather than sinking away
            this.setSpriteFromAge(sprites);
            this.rCol = this.gCol = this.bCol = 1.0f;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
        }
    }

    /**
     * Sulfurous vapour.
     *
     * <p>The one that must NOT rise. Sulfur dioxide is heavier than air, which is why it pools in
     * hollows and why volcanic gas is dangerous in low ground - so this drifts sideways, sags very
     * slightly, and lasts a long time so a field of vents builds up a layer rather than producing
     * separate puffs.</p>
     */
    public static class SulfurHaze extends TextureSheetParticle {
        SulfurHaze(ClientLevel level, double x, double y, double z, double vx, double vy, double vz,
                   SpriteSet sprites) {
            super(level, x, y, z, 0, 0, 0);
            this.xd = vx; this.yd = vy; this.zd = vz;
            this.friction = 0.97f;
            this.gravity = -0.002f;               // very slightly downward: it sinks, it does not rise
            this.lifetime = 70 + this.random.nextInt(60);
            this.quadSize = 1.1f + this.random.nextFloat() * 0.9f;
            this.setSpriteFromAge(sprites);
            this.rCol = this.gCol = this.bCol = 1.0f;
            this.alpha = 0.0f;
        }

        @Override
        public void tick() {
            super.tick();
            // Fades in as well as out, so a vent does not pop haze into existence.
            float t = this.age / (float) this.lifetime;
            this.alpha = (t < 0.2f ? t / 0.2f : 1.0f - (t - 0.2f) / 0.8f) * 0.55f;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }
    }

    // === Providers ==========================================================

    public record MistProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new Mist(level, x, y, z, vx, vy, vz, sprites);
        }
    }

    public record MudProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new MudBlob(level, x, y, z, vx, vy, vz, sprites);
        }
    }

    public record HazeProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new SulfurHaze(level, x, y, z, vx, vy, vz, sprites);
        }
    }
}
