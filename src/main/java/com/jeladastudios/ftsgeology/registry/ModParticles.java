package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The mod's own particles.
 *
 * <h2>Why vanilla's were not enough, having been enough for thirty rounds</h2>
 * Almost everything here has been drawn with campfire smoke, and for smoke that is the right answer:
 * a fumarole really does breathe a thin grey wisp, and borrowing the vanilla particle kept the mod
 * consistent with the game around it. But three effects were being drawn with a particle that means
 * something else entirely, and each of them is a headline feature:
 *
 * <ul>
 *   <li>a geyser blowing a column of water forty blocks up was a few round smoke puffs rising
 *       gently, which is the opposite of pressure;</li>
 *   <li>a mud pot - thick, hot, slow - was vanilla bubble pop, which is the sound of a fish tank;</li>
 *   <li>sulfurous ground had white smoke, when the whole point of it is that it is yellow and
 *       stays low.</li>
 * </ul>
 *
 * <p>These three are the cases where the vanilla vocabulary has no word for what is happening, so
 * the mod supplies one. Everything else keeps borrowing.</p>
 */
public final class ModParticles {

    private ModParticles() {}

    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, GeysersMod.MODID);

    /** Water atomised by pressure: fires upward hard, slows, and spreads into fog at the top. */
    public static final RegistryObject<SimpleParticleType> GEYSER_MIST =
            PARTICLES.register("geyser_mist", () -> new SimpleParticleType(false));

    /** A gobbet of hot mud: heavy, arcing, and gone as soon as it lands. */
    public static final RegistryObject<SimpleParticleType> MUD_BLOB =
            PARTICLES.register("mud_blob", () -> new SimpleParticleType(false));

    /** Sulfurous vapour, denser than air: creeps along the ground rather than rising. */
    public static final RegistryObject<SimpleParticleType> SULFUR_HAZE =
            PARTICLES.register("sulfur_haze", () -> new SimpleParticleType(false));
}
