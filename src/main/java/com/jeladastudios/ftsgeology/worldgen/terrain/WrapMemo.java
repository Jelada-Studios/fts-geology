package com.jeladastudios.ftsgeology.worldgen.terrain;

import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/**
 * A cheaper way for the generator to set up a noise chunk: every chunk it builds, and every single column the river
 * network builds to see where the sea stands.
 *
 * <p>A noise chunk starts by wrapping the whole noise router, from the leaves up, and keeps what it wrapped in a hash
 * map so that two equal branches share one cache. A branch the router names once and uses in several places -- the
 * depth, the factor, the sloped cheese, most of a worldgen mod's own functions -- was wrapped again at every place it is
 * used: all of it walked, every function in it rebuilt and hashed, and a function's hash is its whole branch's. In a
 * tall world with its worldgen mods that was nearly all the time a column took, two fifths of the chunk generator's
 * threads while new ground loaded.</p>
 *
 * <p>Here a named branch is wrapped once a chunk: its wrapping is kept by the branch itself and handed back the next
 * time. The map would have handed back the very same object after walking it all again, so the chunk is the same;
 * only the walk is saved. Only while a noise chunk is being set up, and only for its own wrapping.</p>
 */
public final class WrapMemo {

    private WrapMemo() {}

    /** The noise chunks being set up on this thread, the innermost last: what each has wrapped, by branch. */
    private static final ThreadLocal<ArrayDeque<IdentityHashMap<Object, DensityFunction>>> SCOPES =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** A noise chunk starts to set up. */
    public static void begin() {
        ArrayDeque<IdentityHashMap<Object, DensityFunction>> s = SCOPES.get();
        // Set-ups do not nest this deep: what is left over is from ones that threw and never ended.
        if (s.size() >= 16) s.clear();
        s.push(new IdentityHashMap<>());
    }

    /** It is set up. */
    public static void end() {
        ArrayDeque<IdentityHashMap<Object, DensityFunction>> s = SCOPES.get();
        if (!s.isEmpty()) s.pop();
    }

    /** What the chunk being set up already wrapped this branch into, if the visitor is its wrapping; else null. */
    public static DensityFunction known(DensityFunction.Visitor visitor, Object branch) {
        IdentityHashMap<Object, DensityFunction> done = scope(visitor);
        return done == null ? null : done.get(branch);
    }

    public static void remember(DensityFunction.Visitor visitor, Object branch, DensityFunction wrapped) {
        IdentityHashMap<Object, DensityFunction> done = scope(visitor);
        if (done != null && wrapped != null) done.put(branch, wrapped);
    }

    private static IdentityHashMap<Object, DensityFunction> scope(DensityFunction.Visitor visitor) {
        ArrayDeque<IdentityHashMap<Object, DensityFunction>> s = SCOPES.get();
        if (s.isEmpty()) return null;
        // The noise chunk's own wrapping is a method reference of its; any other visitor walks on as it always did.
        return visitor.getClass().getName().startsWith(NOISE_CHUNK) ? s.peek() : null;
    }

    private static final String NOISE_CHUNK = "net.minecraft.world.level.levelgen.NoiseChunk$$";
}
