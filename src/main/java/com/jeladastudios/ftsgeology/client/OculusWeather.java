package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * The rain under a shader pack (Oculus, the Forge port of Iris; LGPL).
 *
 * <p>Oculus sets its weather phase, in which a pack's weather program draws the rain, only after the dimension's own
 * rain hook has run: rain drawn inside that hook, as this mod's is, went through the pack's particle program and kept
 * vanilla's plain look. So round the mod's own rain the phase is set here, by name, and put back after; the pack then
 * draws it as it draws vanilla's. Where Oculus is not there or no pack is in use nothing is done here; where its pieces
 * are not found as expected, the rain is left to vanilla while a pack is in use.</p>
 */
final class OculusWeather {

    private OculusWeather() {}

    /** Oculus' public API: whether a pack is in use. Null without Oculus. */
    private static final Object IRIS_API;
    private static final Method IN_USE;
    /** Its pipeline's phase, found by name; null where not as expected. */
    private static final Api API;

    private record Api(Method manager, Method pipeline, Method getPhase, Method setPhase, Method depth, Object rainSnow) {}

    static {
        Object irisApi = null;
        Method inUse = null;
        Api api = null;
        if (ModList.get().isLoaded("oculus")) {
            try {
                Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = apiClass.getMethod("getInstance").invoke(null);
                inUse = apiClass.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | RuntimeException e) {
                irisApi = null;
            }
            for (String root : new String[]{"net.irisshaders.iris", "net.coderbot.iris"}) {
                try {
                    Method manager = Class.forName(root + ".Iris").getMethod("getPipelineManager");
                    Method pipeline = manager.getReturnType().getMethod("getPipelineNullable");
                    Class<?> type = pipeline.getReturnType();
                    Class<?> phase = Class.forName(root + ".pipeline.WorldRenderingPhase");
                    api = new Api(manager, pipeline, type.getMethod("getPhase"), type.getMethod("setPhase", phase),
                            type.getMethod("shouldWriteRainAndSnowToDepthBuffer"), phase.getField("RAIN_SNOW").get(null));
                    break;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // the other package name, or not as expected
                }
            }
            if (api == null) GeysersMod.LOGGER.info("Oculus' weather phase was not found: under a shader pack the rain is left to vanilla");
        }
        IRIS_API = irisApi;
        IN_USE = inUse;
        API = api;
    }

    /** Whether a shader pack draws the world now. */
    static boolean shadersOn() {
        if (IRIS_API == null) return false;
        try {
            return (Boolean) IN_USE.invoke(IRIS_API);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The pack's pipeline set to its weather phase, for the rain drawn now: what to give {@link #end}, or null where it
     * cannot be (then the rain is better left to vanilla).
     */
    static Begun begin() {
        if (API == null) return null;
        try {
            Object pipeline = API.pipeline.invoke(API.manager.invoke(null));
            if (pipeline == null) return null;
            Object before = API.getPhase.invoke(pipeline);
            API.setPhase.invoke(pipeline, API.rainSnow);
            return new Begun(pipeline, before, (Boolean) API.depth.invoke(pipeline));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The phase put back as it was. */
    static void end(Begun begun) {
        try {
            API.setPhase.invoke(begun.pipeline, begun.before);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // nothing more to do
        }
    }

    /** A weather phase set: the pipeline, the phase before, and whether the pack wants the rain in its depth. */
    record Begun(Object pipeline, Object before, boolean writeDepth) {}
}
