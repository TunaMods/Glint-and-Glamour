package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.CustomGlint;

import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Client-only entity glint draw. Called from {@link
 * net.tunamods.customglint.common.mixin.LivingEntityRendererMixin} at the point just before
 * the renderer's outer popPose, so the pose stack is still in entity-local space (matches the
 * vantage of armor/layer renderers).
 *
 * Resolution order: per-instance via the registered {@link InstanceResolver} (the api client init
 * installs one that reads from EntityGlintCache), then {@link CustomGlint#ENTITY_GLINTS} type
 * registry. Mods that bundle only the api jar without a resolver still get type-registry-based glints.
 */
public final class EntityGlintRender {
    private EntityGlintRender() {}

    public interface InstanceResolver {
        @Nullable Resolution resolve(LivingEntity entity);
    }

    public static final class Resolution {
        @Nullable public final CustomGlint.Data data;
        public final boolean glowing;
        public final int[] glowColors;
        public Resolution(@Nullable CustomGlint.Data data, boolean glowing, int[] glowColors) {
            this.data = data;
            this.glowing = glowing;
            this.glowColors = glowColors;
        }
    }

    /** Default: no per-instance data. The api client init (EntityGlintClientInit) overrides this. */
    public static InstanceResolver instanceResolver = entity -> null;

    /**
     * Force the client glint cache to re-read this entity's current persistent NBT. Call after
     * a client-side mutation (e.g. {@link CustomGlint#writeEntity}, {@link CustomGlint#setEntityGlowing},
     * {@link CustomGlint#setEntityGlowColors}) when you need the change visible immediately
     * without waiting for a server broadcast, typically preview UIs, replay viewers, or mods
     * that reconstruct entities from stored NBT on the client.
     *
     * Server-side callers should use {@link net.tunamods.customglint.common.entity.EntityGlintEvents#broadcast}
     * instead; the broadcast packet handler refreshes the cache on every tracking client.
     *
     * No-op (clears the cache entry) if the entity has no glint NBT.
     */
    public static void refreshClientCache(LivingEntity entity) {
        EntityGlintCache.put(entity.getUUID(), CustomGlint.entityGlintTag(entity));
    }

    /**
     * Wraps the renderer's MultiBufferSource so EVERY entity-* RenderType requested during
     * the entity render (base model + every RenderLayer like StrayClothingLayer, EyesLayer,
     * VillagerProfessionLayer, …) gets a glint overlay fan-out. The wrapper is a no-op if the
     * entity has no glint data, so we just return the original buffer in that case.
     *
     * Called from {@link net.tunamods.customglint.common.mixin.LivingEntityRendererMixin} at HEAD.
     */
    public static MultiBufferSource wrapForEntity(LivingEntity entity, MultiBufferSource original) {
        // Idempotent: in dev workspaces where mixin.env.remapRefMap=true, BOTH the SRG and
        // named ModifyVariable hooks resolve and fire on the same arg, which would otherwise
        // wrap twice and break body-builder vertex routing.
        if (original instanceof GlintWrappingBufferSource) return original;
        CustomGlint.Data data = resolveData(entity);
        if (data == null) return original;
        return new GlintWrappingBufferSource(original, data);
    }

    /**
     * Unwraps the entity-glint buffer wrapper to the real underlying {@code BufferSource}. The
     * wrapper auto-fans every {@code entity_*} RenderType through the entity's body glint; a draw
     * that must NOT pick up that body glint (e.g. IaF mount armor, which reuses the mount's own
     * model so its {@code entityTranslucent}/{@code entityCutoutNoCull} base would otherwise be
     * stamped with the body glint across the whole model) routes its base + glint through this.
     * Returns the buffer unchanged when no wrapper is installed (non-glinted mount).
     */
    public static MultiBufferSource unwrap(MultiBufferSource buffer) {
        return buffer instanceof GlintWrappingBufferSource w ? w.delegate : buffer;
    }

    @Nullable
    private static CustomGlint.Data resolveData(LivingEntity entity) {
        Resolution r = instanceResolver.resolve(entity);
        if (r != null) return r.data;
        return CustomGlint.getEntityGlint(entity.getType());
    }

    /** Public view of {@link #resolveData}: the entity's glint data (per-instance NBT, else the
     *  {@link CustomGlint#ENTITY_GLINTS} type registry), or null. Used by render-path compat (Epic Fight)
     *  that installs its own glint fan-out and needs the same resolution the core buffer wrapper uses. */
    @Nullable
    public static CustomGlint.Data glintDataFor(LivingEntity entity) {
        return resolveData(entity);
    }

    // Glow outline capture
    // A glowing entity's body is re-recorded into the glow mask, tracing the real body shape against the
    // entity texture. The recording is the IN-PHASE TEE: the body is recorded DURING its single real draw
    // (no second model walk), routed through a CapturingModelConsumer that forwards every call to the real
    // body buffer and also records the camera-relative [x,y,z,u,v]. All silhouettes of ONE figure share that
    // figure's id (glowKeyFor) so they compose as ONE ring, distinct from other figures. Drained with the
    // world items at AFTER_WEATHER.

    /** Draw the entity body AND, when it glows, capture its silhouette in the SAME model walk: the in-phase
     *  tee. Called from {@code LivingEntityRendererMixin}'s {@code @Redirect} on the body {@code renderToBuffer}.
     *
     *  <p>The {@code consumer} is the renderer's real (glint-fanned, if the entity also has glint) body buffer.
     *  When glowing we route the walk through a {@link CapturingModelConsumer} whose {@code delegate} is that
     *  real buffer, so the body renders identically while we record its camera-relative {@code [x,y,z,u,v]}.
     *  Non-glowing entities just draw straight through (one extra method call). The captured modelview matches
     *  the world-item capture path so the deferred drain replays both under the same transform. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void renderBodyTee(LivingEntityRenderer renderer, LivingEntity entity, EntityModel model,
                                     PoseStack pose, VertexConsumer consumer, int light, int overlay,
                                     float red, float green, float blue, float alpha) {
        Resolution r = entity.isInvisible() ? null : instanceResolver.resolve(entity);
        boolean glow = r != null && (r.glowing || r.glowColors.length > 0);
        if (!glow) { model.renderToBuffer(pose, consumer, light, overlay, red, green, blue, alpha); return; }
        ResourceLocation tex = renderer.getTextureLocation(entity);
        if (tex == null) { model.renderToBuffer(pose, consumer, light, overlay, red, green, blue, alpha); return; }
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        cap.delegate = consumer;
        try {
            model.renderToBuffer(pose, cap, light, overlay, red, green, blue, alpha);
        } finally {
            cap.delegate = null;
        }
        // Snapshot the live modelview the body is drawn under (camera transform) so the deferred replay
        // reproduces the exact transform, same scheme as the world-item path in ItemRendererMixin.
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, tex, modelView, outlineColorFor(r),
                GlowOutlineRenderer.glowKeyFor(entity, GlowOutlineRenderer.CAT_ENTITY),
                GlowOutlineRenderer.CAT_ENTITY);
    }

    /** Outline colour for an entity-glow figure: its glow colours (animated) if set, else its glint
     *  layer-0 colour, else white. Shifted by {@link CustomGlintRenderer#GLOW_RING_PHASE_OFFSET} so the
     *  ring stays out of phase with the body glint's inner tint. */
    public static int outlineColorFor(@Nullable Resolution r) {
        float off = CustomGlintRenderer.GLOW_RING_PHASE_OFFSET;
        if (r != null && r.glowColors.length > 0)
            return CustomGlintRenderer.computeAnimatedGlowColor(r.glowColors, 1.0f, true, off);
        if (r != null && r.data != null) return CustomGlintRenderer.computeAnimatedColor(r.data, 0, off);
        return 0xFFFFFFFF;
    }

    /** Resolved silhouette-capture parameters for a teed surface-layer draw. */
    public static final class OutlineSpec {
        public final Object identity;
        public final ResourceLocation tex;
        public final int color, category;
        public OutlineSpec(Object identity, ResourceLocation tex, int color, int category) {
            this.identity = identity; this.tex = tex; this.color = color; this.category = category;
        }
    }

    /** Capture spec for an entity-surface layer (sheep wool, saddle, stray clothing, …), or null when the
     *  entity isn't glowing / is invisible. CAT_ENTITY + the entity id → folds into the body ring. */
    @Nullable
    public static OutlineSpec surfaceOutlineSpec(LivingEntity entity, ResourceLocation texture) {
        if (texture == null || entity.isInvisible()) return null;
        Resolution r = instanceResolver.resolve(entity);
        if (r == null || !(r.glowing || r.glowColors.length > 0)) return null;
        return new OutlineSpec(entity, texture, outlineColorFor(r), GlowOutlineRenderer.CAT_ENTITY);
    }

    /** In-phase tee for an 8-arg {@code Model.renderToBuffer(pose, vc, light, overlay, r,g,b,a)} draw (entity
     *  surface layers). Forwards the real draw unchanged; when {@code spec} is non-null it ALSO records the
     *  silhouette in the SAME walk and queues it (no second {@code renderToBuffer}). {@code spec == null}
     *  (entity not glowing) → plain forward. */
    public static void teeOutline(Model model, PoseStack pose, VertexConsumer realVc, int light, int overlay,
                                  float red, float green, float blue, float alpha, @Nullable OutlineSpec spec) {
        if (spec == null) { model.renderToBuffer(pose, realVc, light, overlay, red, green, blue, alpha); return; }
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        cap.delegate = realVc;
        try {
            model.renderToBuffer(pose, cap, light, overlay, red, green, blue, alpha);
        } finally {
            cap.delegate = null;
        }
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, spec.tex, modelView, spec.color,
                GlowOutlineRenderer.glowKeyFor(spec.identity, spec.category), spec.category);
    }

    /** Re-render a posed {@code model} into the glow mask, tracing it against {@code texture}. Used for worn
     *  items (humanoid armor, elytra, horse barding) that already draw at the layer's RETURN with the same
     *  pose; there's no single tee chokepoint, so this records a second (record-only) walk. Silhouettes
     *  sharing an {@code identity} (e.g. the wearer entity) compose as ONE ring with the body. {@code color}
     *  is the item's resolved glow colour; {@code category} picks the ring thickness. */
    public static void captureModelSilhouette(Object identity, Model model, ResourceLocation texture,
                                              PoseStack pose, int light, int color, int category) {
        if (model == null || texture == null) return;
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        model.renderToBuffer(pose, cap, light, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, texture, modelView, color,
                GlowOutlineRenderer.glowKeyFor(identity, category), category);
    }

    /** Parts-based variant of {@link #captureModelSilhouette} for renderers that expose raw
     *  {@link ModelPart}[] rather than a {@link Model} (Epic Knights armor decorations). Records the
     *  posed parts into the glow mask, tracing them against {@code texture}. Passing the wearer entity
     *  as {@code identity} (with {@code CAT_ARMOR}) folds the decoration silhouette into the same ring
     *  as the body + base armor, so there's no seam between them. */
    public static void capturePartsSilhouette(Object identity, ModelPart[] parts, ResourceLocation texture,
                                              PoseStack pose, int light, int color, int category) {
        if (parts == null || texture == null) return;
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        for (ModelPart part : parts) {
            part.render(pose, cap, light, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
        }
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, texture, modelView, color,
                GlowOutlineRenderer.glowKeyFor(identity, category), category);
    }

    /** One recorder per render thread, reused across captures so a model walk allocates nothing. */
    private static final ThreadLocal<CapturingModelConsumer> CAPTURE_POOL =
            ThreadLocal.withInitial(CapturingModelConsumer::new);

    /** {@link VertexConsumer} for tracing a posed model into a glow silhouette: captures each vertex's
     *  camera-relative position + UV as {@code [x,y,z,u,v]} and drops everything else. The model's big
     *  {@code vertex(Matrix4f, …)} convenience method decomposes into {@code vertex(x,y,z)} then {@code uv(u,v)}
     *  (among others), so we stash the position on {@code vertex} and flush the 5-tuple on {@code uv}.
     *
     *  <p>When {@link CapturingModelConsumer#delegate} is set it ALSO forwards every call to that real buffer,
     *  so a single model walk both DRAWS the entity and RECORDS its silhouette: the in-phase tee. With
     *  {@code delegate == null} it is record-only. The forwarded position is already pose-transformed (the
     *  convenience method transforms before calling {@code vertex(x,y,z)}), so the entity renders identically. */
    public static final class CapturingModelConsumer implements VertexConsumer {
        public float[] data = new float[4096];
        public int count = 0;
        public VertexConsumer delegate; // non-null = in-phase tee (forward + record); null = record-only
        private float px, py, pz;

        /** Reset for reuse: drop the recorded vertices + delegate but keep the (already-grown) backing array. */
        public void reset() { count = 0; delegate = null; }

        private void put(float u, float v) {
            if (count + 5 > data.length) data = Arrays.copyOf(data, data.length * 2);
            data[count++] = px; data[count++] = py; data[count++] = pz; data[count++] = u; data[count++] = v;
        }

        @Override public VertexConsumer vertex(double x, double y, double z) { px = (float) x; py = (float) y; pz = (float) z; if (delegate != null) delegate.vertex(x, y, z); return this; }
        @Override public VertexConsumer uv(float u, float v) { put(u, v); if (delegate != null) delegate.uv(u, v); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { if (delegate != null) delegate.color(r, g, b, a); return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { if (delegate != null) delegate.overlayCoords(u, v); return this; }
        @Override public VertexConsumer uv2(int u, int v) { if (delegate != null) delegate.uv2(u, v); return this; }
        @Override public VertexConsumer normal(float nx, float ny, float nz) { if (delegate != null) delegate.normal(nx, ny, nz); return this; }
        @Override public void endVertex() { if (delegate != null) delegate.endVertex(); }
        @Override public void defaultColor(int r, int g, int b, int a) { if (delegate != null) delegate.defaultColor(r, g, b, a); }
        @Override public void unsetDefaultColor() { if (delegate != null) delegate.unsetDefaultColor(); }
    }

    /**
     * MultiBufferSource wrapper that auto-fans every entity-* RenderType request through the
     * glint render-types of all configured layers. The strategy mirrors what ItemRendererMixin's
     * getFoilBuffer path does for items: a VertexMultiConsumer of {base, glint_layer0, glint_layer1, …}.
     *
     * RenderType filter: only RTs whose toString begins with "entity_" get wrapped. That covers
     * entityCutoutNoCull / entitySolid / entityTranslucent / itemEntityTranslucentCull / etc.
     * (used by all stock entity models and overlay layers like StrayClothingLayer), and
     * excludes text/nametag/particles/our own glint RTs so they pass through untouched.
     */
    public static final class GlintWrappingBufferSource implements MultiBufferSource {
        final MultiBufferSource delegate;
        final CustomGlint.Data glint;
        // TRIANGLES-mode glint RTs instead of QUADS, for renderers whose entity draw is a triangle list
        // (Epic Fight patched meshes). A quad glint RT fed a triangle stream shatters into facets.
        final boolean triangles;

        GlintWrappingBufferSource(MultiBufferSource delegate, CustomGlint.Data glint) {
            this(delegate, glint, false);
        }

        public GlintWrappingBufferSource(MultiBufferSource delegate, CustomGlint.Data glint, boolean triangles) {
            this.delegate = delegate;
            this.glint = glint;
            this.triangles = triangles;
        }

        @Override
        public VertexConsumer getBuffer(RenderType rt) {
            if (!shouldApplyGlint(rt)) return delegate.getBuffer(rt);
            // Don't bleed entity glint onto the item the entity is holding. ItemRenderer.render
            // (mixin'd to set CURRENT_ITEM_STACK at HEAD, clear at RETURN) is invoked through
            // HeldItemLayer / ItemInHandLayer during the entity render: those item draws route
            // through entity_solid / entity_translucent RTs which would otherwise match
            // shouldApplyGlint and fan-out the entity's glint onto the item's vertex stream.
            // The item has its own per-item glint via ItemRendererMixin's getFoilBuffer, which
            // still fires correctly; we just want to skip the entity-glint overlay on it.
            if (CustomGlintRenderer.CURRENT_ITEM_STACK.get() != null) return delegate.getBuffer(rt);

            // Acquire glint buffers BEFORE the base. The body's entity_* RT is non-fixed so it
            // shares the BufferSource's single non-fixed BufferBuilder, while our glint RTs are
            // registered in fixedBuffers (dedicated builders). If we got `base` first, the first
            // `delegate.getBuffer(grt)` call would see lastState=body_rt (non-fixed) and switch
            // away from it, which in vanilla BufferSource.getBuffer ends the previous non-fixed
            // builder, i.e. flushes the body builder while it's still empty and leaves it in a
            // non-building state. Subsequent vertex writes to `base` then drop on the floor and
            // the body renders invisible (the dilated outline ring still appears because its
            // stencil-write pass re-renders the model into its own dedicated fixed builder).
            // Acquiring `base` last leaves it as the current active builder when the model writes.
            // A translucent base surface (e.g. the slime's outer shell) needs its glint tagged for shader-mod
            // late render: under an ACTIVE shaderpack the translucent geometry is deferred past the point our
            // glint would otherwise flush, so the shell paints over the glint and it vanishes. The translucent
            // variants are distinct RT instances that flush in the LINES bucket, on top of the shell, writing
            // no depth and lifting toward the camera so LEQUAL clears the shell's own coplanar depth (see
            // forHorseArmorGlint for why those go together). That ordering only exists inside Iris's
            // FullyBuffered pipeline, so gate on an active pack: with shaders off (even with Oculus/Embeddium
            // installed) there is no deferred flush, so use the plain opaque EQUAL-depth glint there and the
            // shell renders normally with the glint on top.
            boolean translucent = isTranslucent(rt) && CustomGlintRenderer.isShaderPackActive();
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            List<VertexConsumer> list = new ArrayList<>(layers.length + 1);
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                if (CustomGlint.isChromatic(layers[layerIdx])) {
                    RenderType crt = translucent
                            ? CustomGlintRenderer.forChromaticEntityGlintTranslucent(glint, layerIdx, triangles)
                            : triangles
                                ? CustomGlintRenderer.forChromaticEntityGlintTriangles(glint, layerIdx)
                                : CustomGlintRenderer.forChromaticEntityGlint(glint, layerIdx);
                    // Chromatic draws in-pass like every other design: the slick is a texture baked once per
                    // frame (ChromaticTextureBaker) and sampled through GLINT_SHADER_SHARD, so under a pack it
                    // resolves to the pack's own GLINT program rather than the private one Iris colour-masks.
                    // The translucent variant takes the same on-top-of-the-shell path its texture twin does.
                    if (crt != null) list.add(CustomGlintRenderer.chromaticWorldBuffer(delegate, crt));
                    continue;
                }
                int[] colors = layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        CustomGlintRenderer.fillPremul(buf, colors[i]);
                        RenderType grt = translucent
                                ? CustomGlintRenderer.forEntityGlintTranslucent(glint, layerIdx, buf, i, triangles)
                                : triangles
                                    ? CustomGlintRenderer.forEntityGlintTriangles(glint, layerIdx, buf, i)
                                    : CustomGlintRenderer.forEntityGlint(glint, layerIdx, buf, i);
                        if (grt != null) list.add(delegate.getBuffer(grt));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    CustomGlintRenderer.fillPremul(buf, color);
                    RenderType grt = translucent
                            ? CustomGlintRenderer.forEntityGlintTranslucent(glint, layerIdx, buf, 0, triangles)
                            : triangles
                                ? CustomGlintRenderer.forEntityGlintTriangles(glint, layerIdx, buf, 0)
                                : CustomGlintRenderer.forEntityGlint(glint, layerIdx, buf, 0);
                    if (grt != null) list.add(delegate.getBuffer(grt));
                }
            }
            VertexConsumer base = delegate.getBuffer(rt);
            if (list.isEmpty()) return base;
            list.add(base);
            return VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        }

        private static boolean shouldApplyGlint(RenderType rt) {
            String name = rt.toString();
            // Cover entity_cutout_no_cull / entity_solid / entity_translucent / etc.
            return name.startsWith("entity_") || name.startsWith("RenderType[entity_");
        }

        /** entity_translucent / entity_translucent_cull / item_entity_translucent_cull: the shader mod defers
         *  these to a later pass than our fixed glint buffer, so their glint must be shader-late-tagged. */
        private static boolean isTranslucent(RenderType rt) {
            return rt.toString().contains("translucent");
        }
    }
}
