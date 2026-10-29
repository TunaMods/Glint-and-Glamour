package net.tunamods.customglint.common.client;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Iris / shader-pack compatibility for the glint backend. Lives in {@code common.client} (the api jar)
 * so embedders that bundle the api jar get it too, not just the standalone mod.
 *
 * <p>Under an active pack Iris swaps the GPU program for each {@link RenderPipeline} via an internal
 * pipeline&rarr;program map. Our glint pipeline ({@link GlintPipelines#GLINT_COLOR}) derives from vanilla's
 * GLINT pipeline, so Iris's own matcher auto-assigns it to a program that drops our per-vertex colour and
 * the glint renders flat white. {@link #register()} reassigns it to {@code EMISSIVE_ENTITIES} instead,
 * which computes {@code vertexColour × texture} (grayscale design × glint colour) and is emissive.
 *
 * <p>The glow pipelines ({@code GLOW_MASK_*}, {@code GLOW_COMPOSITE_ID}, {@code GLOW_UPSCALE}) are
 * deliberately NOT assigned: they run our own post-process GLSL, which an Iris program would replace and
 * destroy. Glow survives a pack by TIMING instead: its capture + composite drain after Iris finishes the
 * frame (see {@code EntityGlintRender} / {@code LevelRendererMixin}), not by program assignment.
 *
 * <p>All Iris access is reflective (no {@code compileOnly} dependency, per the project's soft-compat rule);
 * a missing Iris just no-ops.
 */
public final class IrisCompat {
    private static final String API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String PROGRAM_CLASS = "net.irisshaders.iris.api.v0.IrisProgram";

    /** The Iris program the glint pipeline is mapped to. A pack's armor-glint program bakes its own tint
     *  and drops vertex colour (white glint); EMISSIVE_ENTITIES keeps {@code vertexColour × texture}. */
    private static final String GLINT_PROGRAM = "EMISSIVE_ENTITIES";

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Set once the assignment actually lands (api ready + assign succeeded), or once Iris is confirmed
     *  absent. Until then {@link #register()} retries; it runs every frame and costs one read after this
     *  trips. */
    private static volatile boolean applied = false;

    private IrisCompat() {}

    /**
     * Assign the glint pipeline to its Iris program. Idempotent and self-retrying: safe to call every
     * frame. Logs the outcome ONCE so a white-glint report can be told apart from a real assignment
     * (a silent reflective failure used to hide an unready {@code IrisApi}).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register() {
        if (applied) return;

        Class<?> apiCls, progCls;
        try {
            apiCls = Class.forName(API_CLASS);
            progCls = Class.forName(PROGRAM_CLASS);
        } catch (Throwable t) {
            applied = true;   // Iris not on the classpath at all, never retry, stay silent.
            return;
        }

        Object api;
        Method assign;
        try {
            api = apiCls.getMethod("getInstance").invoke(null);
            assign = apiCls.getMethod("assignPipeline", RenderPipeline.class, progCls);
        } catch (Throwable t) {
            LOGGER.warn("[customglint] Iris present but assignPipeline lookup failed, glint won't be "
                    + "pack-shaded", t);
            applied = true;
            return;
        }
        if (api == null) return;   // Iris loaded but its singleton isn't ready yet, try again next frame.

        // Iris's own matcher (ShaderKey.findBestMatch) auto-assigns our GLINT-derived pipeline to a
        // wrong program (it caches the match in IrisPipelines.coreShaderMap on compile), and the public
        // assignPipeline THROWS "Shader already assigned" rather than overwrite. Evict that auto-match
        // entry first so our explicit assignment lands. Best-effort + reflective (no compileOnly dep);
        // if the internal map moves in a future Iris, we fall back to letting assignPipeline throw (logged).
        forgetAutoMatch();

        try {
            Object prog = Enum.valueOf((Class<? extends Enum>) (Class<?>) progCls, GLINT_PROGRAM);
            assign.invoke(api, GlintPipelines.GLINT_COLOR, prog);
            applied = true;
            LOGGER.info("[customglint] Iris glint pipeline assigned -> {}", GLINT_PROGRAM);
        } catch (Throwable t) {
            LOGGER.warn("[customglint] Iris assignPipeline({}, {}) failed, glint will render white "
                    + "under a pack", "GLINT_COLOR", GLINT_PROGRAM, t);
            applied = true;
        }
    }

    /** Remove any auto-matched entry for our glint pipeline from {@code IrisPipelines.coreShaderMap} so the
     *  public {@code assignPipeline} (which throws on a duplicate key) can install our chosen program. */
    @SuppressWarnings("unchecked")
    private static void forgetAutoMatch() {
        try {
            Class<?> pipesCls = Class.forName("net.irisshaders.iris.pipeline.IrisPipelines");
            Field f = pipesCls.getDeclaredField("coreShaderMap");
            f.setAccessible(true);
            Object map = f.get(null);
            if (map instanceof Map<?, ?> m) {
                ((Map<Object, Object>) m).remove(GlintPipelines.GLINT_COLOR);
            }
        } catch (Throwable ignored) {
            // Map renamed/moved in this Iris build, assignPipeline below will throw and we log it.
        }
    }
}
