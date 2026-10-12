package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The blocks of the large ore bodies (0.11.3), and which kind of body each belongs to. They are natural ground: the
 * rock a body is made of, the rust on its top and the pieces of it carried down the streams.
 */
public final class OreBodyBlocks {

    private OreBodyBlocks() {}

    /** The kind of body a block tells of. */
    public enum Kind { PORPHYRY, REEF, NONE }

    /** Whether this is one of the ore-body blocks. */
    public static boolean is(BlockState s) {
        return kind(s) != Kind.NONE;
    }

    /** Which kind of body this block is part of, or was carried from. */
    public static Kind kind(BlockState s) {
        if (s.is(ModBlocks.COPPER_PORPHYRY.get()) || s.is(ModBlocks.PYRITE_ROCK.get())
                || s.is(ModBlocks.MALACHITE_ROCK.get()) || s.is(ModBlocks.AZURITE_ROCK.get())
                || s.is(ModBlocks.GOSSAN_BROWN.get()) || s.is(ModBlocks.GOSSAN_ORANGE.get())
                || s.is(ModBlocks.GOSSAN_YELLOW.get()) || s.is(ModBlocks.COPPER_FLOAT.get())) {
            return Kind.PORPHYRY;
        }
        if (s.is(ModBlocks.GOLD_QUARTZ.get()) || s.is(ModBlocks.GOLD_GNEISS.get()) || s.is(ModBlocks.GOLD_SCHIST.get())
                || s.is(ModBlocks.GOLD_GRAVEL.get()) || s.is(ModBlocks.GOLD_FLOAT.get())) {
            return Kind.REEF;
        }
        return Kind.NONE;
    }

    /** Whether this block lies away from its body: float on a bank, or gold in a stream's gravel. */
    public static boolean carried(BlockState s) {
        return s.is(ModBlocks.COPPER_FLOAT.get()) || s.is(ModBlocks.GOLD_FLOAT.get()) || s.is(ModBlocks.GOLD_GRAVEL.get());
    }
}
