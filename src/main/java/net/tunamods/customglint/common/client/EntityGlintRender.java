package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.CustomGlint.GlintState;
import net.tunamods.customglint.common.CustomGlintComponents;
import net.tunamods.customglint.common.mixin.LivingEntityRendererMixin;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-only entity glint draw. Wraps the renderer's {@link MultiBufferSource} so every
 * {@code entity_*} RenderType requested during an entity render (base model + every RenderLayer)
 * gets a glint overlay fan-out.
 *
 * Resolution order: per-instance via the {@link InstanceResolver} (default reads the synced
 * {@link CustomGlintComponents#ENTITY_GLINT} attachment), then {@link CustomGlint#ENTITY_GLINTS} type
 * registry. Both live in the api jar, so api-only embedders get instance + type glints with no wiring.
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

    /** Default: read the per-instance glint state from the synced {@link CustomGlintComponents#ENTITY_GLINT}
     *  attachment. The server writes it and NeoForge syncs it to this client, so a client-side mutation is
     *  visible immediately with no cache or sync packet. Embedders may replace this to source glint elsewhere. */
    public static InstanceResolver instanceResolver = entity -> {
        GlintState s = entity.getExistingDataOrNull(CustomGlintComponents.ENTITY_GLINT);
        if (s == null || s.isEmpty()) return null;
        return new Resolution(s.data(), s.glowing(), s.glowColors());
    };

    /**
     * Wraps the renderer's MultiBufferSource so EVERY entity-* RenderType requested during
     * the entity render (base model + every RenderLayer like StrayClothingLayer, EyesLayer,
     * VillagerProfessionLayer, …) gets a glint overlay fan-out. The wrapper is a no-op if the
     * entity has no glint data, so we just return the original buffer in that case.
     *
     * Called from {@link LivingEntityRendererMixin} at HEAD.
     */
    public static MultiBufferSource wrapForEntity(LivingEntity entity, MultiBufferSource original) {
        // Idempotent: never wrap an already-wrapped source (would double-wrap and break
        // body-builder vertex routing).
        if (original instanceof GlintWrappingBufferSource) return original;
        CustomGlint.Data data = resolveData(entity);
        if (data == null) return original;
        return new GlintWrappingBufferSource(original, data);
    }

    /**
     * Re-wrap the buffer for an Epic Fight patched entity. Epic Fight draws its body mesh through a
     * TRIANGLES-mode {@code entity_*} RenderType, but its render receives the buffer already wrapped by the
     * core {@link #wrapForEntity} (installed on vanilla {@code LivingEntityRenderer.render}, whose HEAD runs
     * before Epic Fight cancels that render from {@code RenderLivingEvent.Pre}). That core wrapper fans the
     * glint through QUADS RTs, so the triangle stream shatters into facets. Strip it and re-install a
     * TRIANGLES-mode glint wrapper. No-op (returns the unwrapped buffer) when the entity has no glint.
     */
    public static MultiBufferSource rewrapTriangles(LivingEntity entity, MultiBufferSource buffer) {
        MultiBufferSource raw = unwrap(buffer);
        if (entity.isInvisible()) return raw;
        CustomGlint.Data data = resolveData(entity);
        if (data == null) return raw;
        return new GlintWrappingBufferSource(raw, data, true);
    }

    // ── Glow outline capture ────────────────────────────────────────────────────────
    // Each glowing model (entity body, an entity-surface layer, or a worn-armor piece) is re-rendered into
    // a record-only buffer (capturing camera-relative [x,y,z,u,v]) and queued, tracing the real shape
    // against its own texture. The re-render is in-phase (the model is still posed from the live draw), so
    // there is no stale-pose problem and no second setupAnim. All silhouettes of ONE figure share that
    // figure's id (glowKeyFor): body + surface layers (CAT_ENTITY) plus every worn-armor piece (CAT_ARMOR),
    // so they compose as ONE ring, distinct from other figures.

    /** Re-render a posed {@code model} into the glow mask under {@code identity}'s shared id. Silhouettes
     *  sharing an {@code identity} compose as ONE ring (no internal seam); a distinct identity gets its own
     *  ring. Pass the {@code entity} for the figure-wide merge (body + armor), or a per-piece object (e.g.
     *  its ItemStack) to keep that piece separate. {@code entity} is still used for the invisibility skip.
     *  The caller has already decided this should glow and resolved the {@code color}; {@code category}
     *  picks the ring thickness (CAT_ENTITY for body/surface, CAT_ARMOR for worn armor). */
    public static void captureModelSilhouette(LivingEntity entity, Object identity, Model model,
                                              ResourceLocation texture, PoseStack pose, int light,
                                              int color, int category, int priority) {
        if (model == null || texture == null || entity.isInvisible()) return;
        // Reuse one consumer (and its grown backing array) instead of allocating per glowing model per
        // frame (body + every armor piece + elytra + surface layer all hit this each frame). queueModelOutline
        // copies the data into a right-sized array, so the buffer is free to reuse on the next capture. The
        // capture runs on the render thread and never re-enters (renderToBuffer only records vertices).
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        model.renderToBuffer(pose, cap, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, texture, color,
                GlowOutlineRenderer.glowKeyFor(identity, category), category, priority);
    }

    /** Capture a posed model for the post-Iris CHROMATIC overlay drain (shader-pack path). Under a pack the
     *  in-phase chromatic program is hijacked, so any model-based chromatic surface (worn armor, elytra, an
     *  entity body, a compat armor) re-renders the freshly-posed model into the pooled record-only buffer and
     *  queues the {@code [x,y,z,u,v]} verts against {@code texture} (its alpha carves the cutout). QUADS order
     *  (a vanilla {@link Model}); the overlay traces the real shape + occludes in-shader. No-op off-pack (the
     *  caller gates on {@link CustomGlintRenderer#isShaderPackActive()}) or when the entity is invisible. */
    public static void captureChromaticModel(LivingEntity entity, Model model, ResourceLocation texture,
                                             PoseStack pose, int light, CustomGlint.Data glint, int layerIdx) {
        if (model == null || texture == null || entity.isInvisible()) return;
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        model.renderToBuffer(pose, cap, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        GlowOutlineRenderer.queueChromaticModel(cap.data, cap.count, texture, glint, layerIdx, false);
    }

    /** Capture the slime's translucent OUTER shell for the post-Iris CHROMATIC overlay drain. The in-phase
     *  fan skips chromatic under a pack, and {@link #renderBodyTee} only captures the INNER body, so without
     *  this the chromatic slick traces the smaller inner cube (reads as dim/buried) instead of the visible
     *  shell. The textured path already lands on the shell through the OPAQUE_DECAL fan; this brings chromatic
     *  to parity. Mirrors the glow tee the same mixin runs on the outer draw. No-op off-pack, when invisible,
     *  or when the entity carries no chromatic layer. */
    public static void captureSurfaceChromatic(LivingEntity entity, Model model, ResourceLocation texture,
                                               PoseStack pose, int light) {
        if (model == null || texture == null || entity.isInvisible()) return;
        if (!CustomGlintRenderer.isShaderPackActive()) return;
        CustomGlint.Data glint = resolveData(entity);
        if (glint == null || !hasChromaticLayer(glint)) return;
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        model.renderToBuffer(pose, cap, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        queueChromaticLayers(cap, texture, glint, SHELL_CHROMATIC_BRIGHTNESS);
    }

    /** The slick composites over the translucent shell, so at full strength it reads as a solid glowing block
     *  instead of an oil-slick sheen on jelly. Knock it down so the shell still shows through. Tunable. */
    private static final float SHELL_CHROMATIC_BRIGHTNESS = 0.55f;

    /** Capture a posed horse-armor model for the post-Iris TEXTURED-glint overlay drain (shader-pack path).
     *  Mirrors {@link #captureChromaticModel}: a pack hijacks the in-phase glint_cutout program, so re-render
     *  the model into the record-only buffer once and queue its verts per COLOUR of {@code layerIdx} against
     *  {@code texture} (its alpha carves the cutout). QUADS (a vanilla {@link Model}); the overlay traces the
     *  real shape + occludes in-shader. No-op off-pack (caller gates on the shader-pack check) / when invisible. */
    public static void captureGlintModel(LivingEntity entity, Model model, ResourceLocation texture,
                                         PoseStack pose, int light, CustomGlint.Data glint, int layerIdx) {
        if (model == null || texture == null || entity.isInvisible()) return;
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        model.renderToBuffer(pose, cap, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        queueGlintLayer(cap, texture, glint, layerIdx);
    }

    /** Queue {@code cap}'s captured verts as the post-Iris TEXTURED glint overlay for one layer: one job per
     *  colour when the layer shows every colour at once, else one job for the current animated colour. */
    private static void queueGlintLayer(CapturingModelConsumer cap, ResourceLocation texture,
                                        CustomGlint.Data glint, int layerIdx) {
        CustomGlint.Layer layer = glint.layers()[layerIdx];
        int[] colors = layer.colors().length == 0 ? CustomGlintRenderer.WHITE_COLOR : layer.colors();
        if (layer.simultaneous()) {
            for (int i = 0; i < colors.length; i++) {
                GlowOutlineRenderer.queueGlintModel(cap.data, cap.count, texture, glint, layerIdx, premul(colors[i]), i, false);
            }
        } else {
            GlowOutlineRenderer.queueGlintModel(cap.data, cap.count, texture, glint, layerIdx,
                    premul(CustomGlintRenderer.computeAnimatedColor(glint, layerIdx)), 0, false);
        }
    }

    /** Queue every chromatic layer of {@code glint} as a post-Iris overlay job over {@code cap}'s verts. */
    private static void queueChromaticLayers(CapturingModelConsumer cap, ResourceLocation texture,
                                             CustomGlint.Data glint, float brightness) {
        CustomGlint.Layer[] layers = glint.layers();
        for (int li = 0; li < layers.length; li++) {
            if (CustomGlint.isChromatic(layers[li])) {
                GlowOutlineRenderer.queueChromaticModel(cap.data, cap.count, texture, glint, li, false, brightness);
            }
        }
    }

    /** ARGB int to a fresh premultiplied-RGB + alpha-1 float[4], the form the glint colour holder expects. */
    private static float[] premul(int argb) {
        float[] c = new float[4];
        fillPremul(c, argb);
        return c;
    }

    /** As {@link #captureModelSilhouette} but from raw {@code ModelPart[]}. Epic Knights armor decorations
     *  (plumes, surcoats, crowns) draw via {@code ModelPart.render} + {@code getArmorFoilBuffer}, not a
     *  {@link Model} / {@code renderColoredCutoutModel}, so no generic tee reaches them. Re-renders the parts
     *  into the pooled recording consumer and traces the silhouette against {@code texture}. Call once per
     *  texture (base + overlay) with the SAME {@code identity} to union a split-shape decoration into one ring. */
    public static void captureModelPartsSilhouette(LivingEntity entity, Object identity, ModelPart[] parts,
                                                   ResourceLocation texture, PoseStack pose, int light,
                                                   int color, int category, int priority) {
        if (parts == null || texture == null || entity.isInvisible()) return;
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        for (ModelPart part : parts) {
            if (part != null) part.render(pose, cap, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, texture, color,
                GlowOutlineRenderer.glowKeyFor(identity, category), category, priority);
    }

    /** As {@link #captureGlintModel} but from raw {@code ModelPart[]}. Epic Knights decorations draw via
     *  {@code ModelPart.render}, not a {@link Model}, so under a shader pack (where the in-phase cutout program is
     *  hijacked) re-render the parts into the pooled recording consumer and queue the verts per COLOUR of
     *  {@code layerIdx} against {@code texture} (its alpha carves the cutout) for the post-Iris textured-glint
     *  overlay drain. Call once per texture (base + overlay) to union a split-shape decoration. No-op off-pack
     *  (caller gates on the shader-pack check) or when invisible. */
    public static void captureGlintModelParts(LivingEntity entity, ModelPart[] parts, ResourceLocation texture,
                                              PoseStack pose, int light, CustomGlint.Data glint, int layerIdx) {
        if (parts == null || texture == null || entity == null || entity.isInvisible()) return;
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        for (ModelPart part : parts) {
            if (part != null) part.render(pose, cap, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
        queueGlintLayer(cap, texture, glint, layerIdx);
    }

    /** As {@link #captureChromaticModel} but from raw {@code ModelPart[]}. Epic Knights decorations draw via
     *  {@code ModelPart.render}, not a {@link Model}, so under a shader pack (where the in-phase chromatic program
     *  is hijacked) re-render the parts into the pooled recording consumer and queue the verts for the post-Iris
     *  CHROMATIC overlay drain against {@code texture} (its alpha carves the cutout). Call once per texture
     *  (base + overlay) to union a split-shape decoration. No-op off-pack (caller gates on the shader-pack check)
     *  or when invisible. */
    public static void captureChromaticModelParts(LivingEntity entity, ModelPart[] parts, ResourceLocation texture,
                                                  PoseStack pose, int light, CustomGlint.Data glint, int layerIdx) {
        if (parts == null || texture == null || entity == null || entity.isInvisible()) return;
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        for (ModelPart part : parts) {
            if (part != null) part.render(pose, cap, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
        GlowOutlineRenderer.queueChromaticModel(cap.data, cap.count, texture, glint, layerIdx, false);
    }

    /** Draw the entity body AND, when it glows, capture its silhouette in the SAME model walk: the in-phase
     *  tee. Replaces the old "re-render the body model into a record-only buffer after the fact" path (a full
     *  second {@code renderToBuffer} walk per glowing entity every frame), which was the dominant cost with
     *  many glowing entities on screen (matches 26.1.2's measured bottleneck; 26.1.2 fixed it the same way).
     *  Called from {@code LivingEntityRendererMixin}'s {@code @Redirect} on the body {@code renderToBuffer}.
     *
     *  <p>The {@code consumer} is the renderer's real (glint-fanned if the entity also has glint) body buffer.
     *  When glowing we route the walk through a {@link CapturingModelConsumer} whose {@code delegate} is that
     *  real buffer, so the body renders identically while we record its camera-relative {@code [x,y,z,u,v]}
     *  for the drain. Non-glowing entities just draw straight through (one extra method call). Teeing an
     *  entity model is safe under Sodium: the body uses the per-vertex {@code ModelPart} path, NOT the
     *  {@code putBulkData} intrinsic that made the ITEM tee a net loss. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void renderBodyTee(LivingEntityRenderer renderer,
                                     LivingEntity entity, Model model, PoseStack pose, VertexConsumer consumer,
                                     int light, int overlay, int color) {
        Resolution r = entity.isInvisible() ? null : instanceResolver.resolve(entity);
        boolean bodyGlow = r != null && (r.glowing || r.glowColors.length > 0);
        // Capture the body silhouette only when the figure glows through its own BODY. A worn armor piece
        // outlines itself (CAT_ARMOR, captured in HumanoidArmorLayerMixin); it no longer pulls the whole body
        // into the ring, so a single glowing piece outlines just that piece rather than the entire figure.
        int figColor = bodyGlow ? outlineColorFor(r) : 0;
        boolean glow = figColor != 0;
        // Under a shader pack the entity body's chromatic layers can't draw in-phase (hijacked), so capture the
        // body here for the post-Iris overlay drain in the SAME walk as the glow tee. Off-pack the wrapping
        // buffer draws chromatic in-phase, so this is glow-only there.
        CustomGlint.Data glint = entity.isInvisible() ? null : resolveData(entity);
        boolean chromaPack = glint != null && CustomGlintRenderer.isShaderPackActive() && hasChromaticLayer(glint);
        if (!glow && !chromaPack) { model.renderToBuffer(pose, consumer, light, overlay, color); return; }
        ResourceLocation tex = renderer.getTextureLocation(entity);
        if (tex == null) { model.renderToBuffer(pose, consumer, light, overlay, color); return; }
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        cap.delegate = consumer;
        try {
            model.renderToBuffer(pose, cap, light, overlay, color);
        } finally {
            cap.delegate = null;
        }
        if (glow) {
            GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, tex, figColor,
                    GlowOutlineRenderer.glowKeyFor(entity, GlowOutlineRenderer.CAT_ENTITY),
                    GlowOutlineRenderer.CAT_ENTITY, 0);
        }
        if (chromaPack) {
            queueChromaticLayers(cap, tex, glint, 1.0f);
        }
    }

    /** True when any layer of {@code glint} is the procedural chromatic design. */
    public static boolean hasChromaticLayer(CustomGlint.Data glint) {
        for (CustomGlint.Layer l : glint.layers()) if (CustomGlint.isChromatic(l)) return true;
        return false;
    }

    /** Should a worn-armor compat capture the armor silhouette this piece? True when it glows OR (under a
     *  shader pack) it has a chromatic layer that needs the post-Iris overlay. Compat armors call this to
     *  decide whether to add the record-only capture consumer to their glint tee. */
    public static boolean needsArmorCapture(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (CustomGlint.hasGlowEffect(stack)) return true;
        if (!CustomGlintRenderer.isShaderPackActive()) return false;
        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        return glint != null && hasChromaticLayer(glint);
    }

    /** From a captured worn-armor silhouette ({@code cap}, QUADS {@code [x,y,z,u,v]}), queue the glow ring
     *  (if glowing) AND the chromatic overlay per chromatic layer (if under a pack). Shared by the compat
     *  armor modules (GeckoLib / Immersive Armors / Mekanism / Artifacts) so their flush is one call. */
    public static void flushArmorCapture(CapturingModelConsumer cap, LivingEntity entity, ItemStack stack,
                                         ResourceLocation tex, int category) {
        if (cap == null || cap.count <= 0 || tex == null || entity == null || stack == null) return;
        if (CustomGlint.hasGlowEffect(stack)) {
            GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, tex,
                    CustomGlintRenderer.resolveGlowColor(stack),
                    GlowOutlineRenderer.glowKeyFor(entity, category), category, 0);
        }
        if (CustomGlintRenderer.isShaderPackActive()) {
            CustomGlint.Data glint = CustomGlint.readForRender(stack);
            if (glint != null) queueChromaticLayers(cap, tex, glint, 1.0f);
        }
    }

    /** Resolved silhouette-capture parameters for a teed model draw. */
    public static final class OutlineSpec {
        public final Object identity;
        public final ResourceLocation tex;
        public final int color, category, priority;
        public OutlineSpec(Object identity, ResourceLocation tex, int color, int category, int priority) {
            this.identity = identity; this.tex = tex; this.color = color;
            this.category = category; this.priority = priority;
        }
    }

    /** In-phase tee for a 5-arg {@code Model.renderToBuffer(pose, vc, light, overlay, color)} draw (worn
     *  humanoid armor base layer, horse armor, entity-surface layers). Forwards the real draw unchanged;
     *  when {@code spec} is non-null it ALSO records the silhouette in the SAME walk and queues it (no second
     *  {@code renderToBuffer}). {@code spec == null} (entity not glowing) → plain forward. Safe under Sodium
     *  (these are ModelPart per-vertex draws, not the {@code putBulkData} item intrinsic). */
    public static void teeOutline5(Model model, PoseStack pose, VertexConsumer realVc,
                                   int light, int overlay, int color, @Nullable OutlineSpec spec) {
        if (spec == null) { model.renderToBuffer(pose, realVc, light, overlay, color); return; }
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        cap.delegate = realVc;
        try {
            model.renderToBuffer(pose, cap, light, overlay, color);
        } finally {
            cap.delegate = null;
        }
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, spec.tex, spec.color,
                GlowOutlineRenderer.glowKeyFor(spec.identity, spec.category), spec.category, spec.priority);
    }

    /** As {@link #teeOutline5} for a 4-arg {@code Model.renderToBuffer(pose, vc, light, overlay)} draw
     *  (the elytra; vanilla's overload defaults colour to white). */
    public static void teeOutline4(Model model, PoseStack pose, VertexConsumer realVc,
                                   int light, int overlay, @Nullable OutlineSpec spec) {
        if (spec == null) { model.renderToBuffer(pose, realVc, light, overlay); return; }
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        cap.delegate = realVc;
        try {
            model.renderToBuffer(pose, cap, light, overlay);
        } finally {
            cap.delegate = null;
        }
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, spec.tex, spec.color,
                GlowOutlineRenderer.glowKeyFor(spec.identity, spec.category), spec.category, spec.priority);
    }

    /** Capture spec for an entity-surface layer (sheep wool, saddle, clothing, …), or null when the entity
     *  isn't glowing / is invisible. CAT_ENTITY + the entity id → folds into the body ring. */
    @Nullable
    public static OutlineSpec surfaceOutlineSpec(LivingEntity entity, ResourceLocation texture) {
        if (texture == null || entity.isInvisible()) return null;
        Resolution r = instanceResolver.resolve(entity);
        if (r == null || !(r.glowing || r.glowColors.length > 0)) return null;
        return new OutlineSpec(entity, texture, outlineColorFor(r), GlowOutlineRenderer.CAT_ENTITY, 0);
    }

    /** Body / entity-surface (sheep wool, saddle, stray clothing, …) silhouette: captured ONLY when the
     *  entity itself glows (entity NBT), traced against {@code texture} (the body or layer texture). Shares
     *  the figure's CAT_ENTITY id, so body + all its surface layers + its armor compose as one ring. Worn
     *  armor outlines itself separately (CAT_ARMOR). Re-renders the freshly-posed model (entity-local pose)
     *  into a record-only buffer to trace the silhouette. */
    public static void captureEntityOutline(LivingEntity entity, Model model, ResourceLocation texture,
                                            PoseStack pose, int light) {
        if (entity.isInvisible()) return;
        Resolution r = instanceResolver.resolve(entity);
        if (r == null || !(r.glowing || r.glowColors.length > 0)) return;
        captureModelSilhouette(entity, entity, model, texture, pose, light, outlineColorFor(r),
                GlowOutlineRenderer.CAT_ENTITY, 0);
    }

    /** Outline colour for an entity-glow figure: its glow colours (animated) if set, else its glint
     *  layer-0 colour, else white. Also used by the armor-outline path so body + armor of one figure
     *  ring in the same colour. */
    public static int outlineColorFor(@Nullable Resolution r) {
        float off = CustomGlintRenderer.GLOW_RING_PHASE_OFFSET;
        if (r != null && r.glowColors.length > 0) return CustomGlintRenderer.computeAnimatedGlowColor(r.glowColors, 1.0f, true, off);
        if (r != null && r.data != null) return CustomGlintRenderer.computeAnimatedColor(r.data, 0, off);
        return 0xFFFFFFFF;
    }

    @Nullable
    private static CustomGlint.Data resolveData(LivingEntity entity) {
        Resolution r = instanceResolver.resolve(entity);
        if (r != null) return r.data;
        return CustomGlint.getEntityGlint(entity.getType());
    }

    /**
     * Unwraps the entity-glint buffer wrapper to the real underlying {@code BufferSource}. Mixins
     * that must {@code endBatch} a specific RenderType to order passes (the stencil-mask-before-glint
     * sequence on mount/dragon armor) need the real BufferSource: during entity rendering the layer
     * receives {@link GlintWrappingBufferSource}, which is NOT a BufferSource, so a raw
     * {@code instanceof BufferSource} check silently skips the flush. That only bites when the entity
     * ALSO carries its own glint (so the wrapper is installed), e.g. a glinted dragon wearing glinted
     * armor; the body part's glint then bled across the whole dragon.
     */
    public static MultiBufferSource unwrap(MultiBufferSource buffer) {
        return buffer instanceof GlintWrappingBufferSource w ? w.delegate : buffer;
    }

    private static void fillPremul(float[] buf, int argb) {
        float a = ((argb >> 24) & 0xFF) / 255.0f;
        buf[0] = ((argb >> 16) & 0xFF) / 255.0f * a;
        buf[1] = ((argb >>  8) & 0xFF) / 255.0f * a;
        buf[2] = ( argb        & 0xFF) / 255.0f * a;
        buf[3] = 1.0f;
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
        // Fan the glint through TRIANGLES-mode RTs instead of QUADS, for renderers whose entity draw is a
        // triangle list (Epic Fight patched meshes). A QUADS glint RT fed a triangle stream shatters.
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
            // HeldItemLayer / ItemInHandLayer during the entity render; those item draws route
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
            // the body renders invisible. Acquiring `base` last leaves it as the current active
            // builder when the model writes.
            // A translucent base surface (the slime's outer shell = entity_translucent) needs its glint tagged
            // for shader-mod late render ONLY under a shader pack: there the translucent geometry is deferred and
            // its depth is re-sorted, so an opaque EQUAL-depth glint tests against the wrong reference and
            // vanishes; the LEQUAL / OPAQUE_DECAL variant fixes that. Off-pack there is no reorder, so LEQUAL
            // alone would just drop the shell's self-occlusion (back faces show through); off-pack the shell
            // writes its own depth and the normal EQUAL path self-occludes correctly, so keep it.
            boolean translucent = isTranslucent(rt) && CustomGlintRenderer.isShaderPackActive();
            // Opaque base surface under a shader pack: route to the OPAQUE_DECAL-tagged cutout variant so the glint
            // flushes against stable opaque depth. In-phase forEntityGlint flushes too early under a pack and its
            // EQUAL depth-cutout leaks onto the body's transparent texels. Off-pack, translucent, and TRIANGLES
            // paths are unchanged.
            boolean shaderCutout = !translucent && !triangles && CustomGlintRenderer.isShaderPackActive();
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            List<VertexConsumer> list = new ArrayList<>(layers.length + 1);
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                if (CustomGlint.isChromatic(layers[layerIdx])) {
                    // Under a shader pack the in-phase chromatic program is hijacked; the entity body is
                    // captured + queued for the post-Iris overlay drain in renderBodyTee instead. Off-pack,
                    // draw it in-phase as normal.
                    if (!CustomGlintRenderer.isShaderPackActive()) {
                        RenderType crt = translucent
                                ? CustomGlintRenderer.forChromaticEntityGlintTranslucent(glint, layerIdx, triangles)
                                : triangles
                                    ? CustomGlintRenderer.forChromaticEntityGlintTriangles(glint, layerIdx)
                                    : CustomGlintRenderer.forChromaticEntityGlint(glint, layerIdx);
                        if (crt != null) list.add(delegate.getBuffer(crt));
                    }
                    continue;
                }
                int[] colors = layers[layerIdx].colors().length == 0 ? CustomGlintRenderer.WHITE_COLOR : layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        fillPremul(buf, colors[i]);
                        RenderType grt = translucent
                                ? CustomGlintRenderer.forEntityGlintTranslucent(glint, layerIdx, buf, i, triangles)
                                : triangles
                                    ? CustomGlintRenderer.forEntityGlintTriangles(glint, layerIdx, buf, i)
                                    : shaderCutout
                                        ? CustomGlintRenderer.forEntityGlintShaderCutout(glint, layerIdx, buf, i)
                                        : CustomGlintRenderer.forEntityGlint(glint, layerIdx, buf, i);
                        if (grt != null) list.add(delegate.getBuffer(grt));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    fillPremul(buf, color);
                    RenderType grt = translucent
                            ? CustomGlintRenderer.forEntityGlintTranslucent(glint, layerIdx, buf, 0, triangles)
                            : triangles
                                ? CustomGlintRenderer.forEntityGlintTriangles(glint, layerIdx, buf, 0)
                                : shaderCutout
                                    ? CustomGlintRenderer.forEntityGlintShaderCutout(glint, layerIdx, buf, 0)
                                    : CustomGlintRenderer.forEntityGlint(glint, layerIdx, buf, 0);
                    if (grt != null) list.add(delegate.getBuffer(grt));
                }
            }
            VertexConsumer base = delegate.getBuffer(rt);
            if (list.isEmpty()) return base;
            list.add(base);
            return VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        }

        // RenderType instances are stable singletons, so cache the (expensive) toString-based verdict
        // by identity: getBuffer is called for every RT requested during a glinted entity's render,
        // every frame, and rt.toString() allocated a fresh String each time.
        private static final Map<RenderType, Boolean> GLINT_RT_VERDICT = new IdentityHashMap<>();

        /** Dropped on resource reload (via {@link CustomGlintRenderer#clearTextures()}) so the verdict map
         *  doesn't pin RenderType singletons across reloads, matching the other reload-cleared caches. */
        public static void clearRtVerdictCache() { GLINT_RT_VERDICT.clear(); }

        private static boolean shouldApplyGlint(RenderType rt) {
            Boolean cached = GLINT_RT_VERDICT.get(rt);
            if (cached != null) return cached;
            String name = rt.toString();
            // Cover entity_cutout_no_cull / entity_solid / entity_translucent / etc.
            boolean verdict = name.startsWith("entity_") || name.startsWith("RenderType[entity_");
            GLINT_RT_VERDICT.put(rt, verdict);
            return verdict;
        }

        /** A translucent base surface (e.g. the slime's outer shell renders through entity_translucent_cull).
         *  Its glint routes to the LEQUAL / OPAQUE_DECAL variant so it survives under a shader pack. */
        private static boolean isTranslucent(RenderType rt) {
            return rt.toString().contains("translucent");
        }
    }

    /** One reusable capture consumer per render thread; see {@link CapturingModelConsumer}. */
    private static final ThreadLocal<CapturingModelConsumer> CAPTURE_POOL =
            ThreadLocal.withInitial(CapturingModelConsumer::new);

    /** {@link VertexConsumer} for tracing a posed model into a glow silhouette: captures each vertex's
     *  camera-relative position + UV as {@code [x,y,z,u,v]} and drops everything else. The model's big
     *  {@code addVertex(...)} convenience method decomposes into {@code addVertex(x,y,z)} then
     *  {@code setUv(u,v)} (among others), so we stash the position on {@code addVertex} and flush the
     *  5-tuple on {@code setUv}.
     *
     *  <p>When {@link #delegate} is set it ALSO forwards every call to that real buffer, so a single model
     *  walk both DRAWS the entity and RECORDS its silhouette: the in-phase tee (no second
     *  {@code renderToBuffer} walk). With {@code delegate == null} it is record-only (the re-render path used
     *  by armor / surface layers). The forwarded position is already pose-transformed (the convenience method
     *  transforms before calling {@code addVertex(x,y,z)}), so the entity renders identically. */
    public static final class CapturingModelConsumer implements VertexConsumer {
        // 4096 floats = ~819 captured vertices; doubled in put() when a bigger model overruns it.
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

        @Override public VertexConsumer addVertex(float x, float y, float z) { px = x; py = y; pz = z; if (delegate != null) delegate.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setUv(float u, float v) { put(u, v); if (delegate != null) delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { if (delegate != null) delegate.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { if (delegate != null) delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { if (delegate != null) delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float nx, float ny, float nz) { if (delegate != null) delegate.setNormal(nx, ny, nz); return this; }
    }
}
