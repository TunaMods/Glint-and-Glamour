package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.commands.RenderPass;
import javax.annotation.Nullable;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

import net.tunamods.customglint.common.CustomGlint.Data;
import net.tunamods.customglint.common.CustomGlint.Layer;

/**
 * Client-only rendering backend. Split out of {@link CustomGlint} so that the data-API class
 * remains loadable on dedicated servers (where the render classes are absent). Mods bundling the
 * api jar should call render-pipeline methods through this class; NBT/data API stays on
 * {@link CustomGlint}.
 *
 * <p>On 26.1 the GPU state is built on {@link GlintPipelines} (immutable RenderPipeline /
 * RenderSetup) instead of the 1.20-era {@code RenderStateShard}/{@code CompositeState}
 * model. Per-RenderType color rides the vertex color (callers inject it via a color-overriding
 * VertexConsumer); animation rides a {@link net.minecraft.client.renderer.rendertype.TextureTransform}
 * supplier.
 *
 * <p>Naming convention: {@code forXxx} mints (and caches) a RenderType for a draw path, {@code getXxx}
 * resolves a cached texture, {@code computeXxx} derives a per-frame value, and {@code fanXxx} tees an
 * existing draw into a second buffer.
 */
public final class CustomGlintRenderer {

    private CustomGlintRenderer() {}

    // ── Texture cache ─────────────────────────────────────────────────────────

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Identifier, Identifier> textureCache = new HashMap<>();
    /** Procedural-chromatic palette strips (1px-tall RGBA), keyed by the color-array CONTENTS (see
     *  {@link #colorsKey}). Keying on {@code Arrays.hashCode} alone collides: distinct equal-length color
     *  arrays can hash equal (the 31*r+e formula), which would silently hand a trim the wrong cached strip.
     *  Access-order LRU capped like the RenderType caches: one tiny DynamicTexture is registered per distinct
     *  color-array ever seen, and without a bound they accumulate for the whole session (freed only on resource
     *  reload). The eldest's texture is released on eviction; the cap sits far above any frame's distinct count. */
    private static final int PALETTE_CACHE_CAP = 256;
    private static final Map<String, Identifier> paletteCache =
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
                if (size() <= PALETTE_CACHE_CAP) return false;
                Identifier loc = eldest.getValue();
                if (loc != null) {
                    try { Minecraft.getInstance().getTextureManager().release(loc); } catch (Throwable ignored) {}
                }
                return true;
            }
        };
    /** 1×1 opaque-white dummy (lazily built), bound to the chromatic pipeline's unused Sampler0 and, on the
     *  overlay paths, as the no-cutout fallback: alpha 1 discards nothing, so the whole mesh draws. */
    private static Identifier whiteTexture;
    /** Cached block-atlas dimensions feeding the item-glint scale. The atlas size only changes on resource
     *  reload, so resolving it once (vs every {@link #forGlint} call) avoids a per-item-draw TextureManager
     *  lookup + instanceof. 0 = unresolved; reset in {@link #clearTextures}. */
    private static int cachedAtlasW = 0, cachedAtlasH = 0;
    /** GUI scale (clamped 1..127) cached once per frame by {@link #refreshFrameGuiScale()}. The GUI glint/glow
     *  overlay packs this into a colour's alpha byte for every glinted icon; it can't change mid-frame, so the
     *  per-icon Window lookup it replaces was redundant on the full-creative-tab path. */
    private static int frameGuiScale = 1;

    /** Refreshes {@link #frameGuiScale}; called once per rendered frame from the RenderFrameEvent.Pre hook. */
    public static void refreshFrameGuiScale() {
        frameGuiScale = Math.max(1, Math.min(127, (int) Minecraft.getInstance().getWindow().getGuiScale()));
    }

    /** The GUI scale (1..127) cached at the start of the current frame. */
    public static int frameGuiScale() { return frameGuiScale; }

    /** Returns the cached grayscale design texture, building it on first request. A missing/unreadable design
     *  caches {@code null} on purpose: {@link #generateTexture} does file I/O + an image pass, so retrying it
     *  every frame for an absent design would stall the GUI. The null entry is cleared on the next resource
     *  reload ({@link #clearTextures}), so a design that appears later recovers. Callers MUST null-check both
     *  this result AND the subsequent {@code TextureManager.getTexture} before dereferencing. */
    @Nullable
    public static Identifier getTexture(Identifier design) {
        if (textureCache.containsKey(design)) return textureCache.get(design);
        Identifier result = generateTexture(design);
        textureCache.put(design, result);
        return result;
    }

    /** Hard cap on each per-config glint RenderType cache. The keys embed continuous params (speed, pattern
     *  scale, scroll offset) and the per-trim chromatic seed, so without a bound these grow once per distinct
     *  trim configuration ever seen this session. An access-order LRU keeps the live working set and drops the
     *  rest. The cap sits far above any single frame's distinct-config count, so in-use entries are never
     *  evicted mid-frame. */
    private static final int RT_CACHE_CAP = 256;

    /** Builds an access-order LRU RenderType cache. */
    private static <K> Map<K, RenderType> newRtCache() {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, RenderType> eldest) {
                return size() > RT_CACHE_CAP;
            }
        };
    }

    public static void clearTextures() {
        Minecraft mc = Minecraft.getInstance();
        cachedAtlasW = cachedAtlasH = 0; // atlas may resize on reload, re-resolve lazily in forGlint
        for (Identifier loc : textureCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        textureCache.clear();
        designDim.clear(); // re-measured by loadGrayscale when a design is next read
        // The stitched GUI design atlas is a DynamicTexture too; release it and force a lazy rebuild so a
        // resource reload (or a data pack that adds designs) re-stitches the current design set.
        invalidateGuiDesignAtlas();
        // Chromatic palette strips + the white dummy are DynamicTextures too, release and rebuild on reload.
        for (Identifier loc : paletteCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        paletteCache.clear();
        if (whiteTexture != null) { mc.getTextureManager().release(whiteTexture); whiteTexture = null; }
        // Drop the borrowed scene-depth holder's TextureManager registration so it doesn't dangle across a
        // reload; bindSceneDepth re-registers a fresh holder on the next frame. (The view itself is owned by
        // the main render target, releasing here only removes our registration, never frees the view.)
        if (sceneDepthTex != null) { mc.getTextureManager().release(SCENE_DEPTH_ID); sceneDepthTex = null; }
        // The entity-surface RenderType identity cache keys on interned singletons; clear it so a pack/dimension
        // swap that re-mints those RenderTypes doesn't keep stale identities alive.
        EntityGlintRender.clearSurfaceCache();
        // Free the glow/chromatic composite RenderTargets' GPU textures, they're otherwise never closed and
        // dangle across reloads; rebuilt lazily on the next drain.
        EntityGlintRender.releaseTargets();
        for (Runnable r : additionalReloadCleanup) {
            try { r.run(); } catch (Throwable t) {
                LOGGER.warn("[{}/CustomGlint] additional reload cleanup threw", MOD_ID, t);
            }
        }
        BY_GLINT.clear();
        BY_ARMOR_GLINT.clear();
        BY_ENTITY_BODY_GLINT.clear();
        BY_BLOCK_GLINT.clear();
        BY_CHROMATIC.clear();
        BY_CHROMATIC_OVERLAY.clear();
        BY_GLINT_OVERLAY.clear();
        BY_ENTITY_LAYER_GLINT.clear();
        BY_GLOW_MASK.clear();
        BY_WING_DEPTH.clear();
        // Anything staged against the RenderTypes the caches just dropped would draw with stale types.
        GLOW_BODY.discard();
        GLOW_MASK.discard();
        OVERLAY.discard();
        // TRIED (did not fix): after Iris/Oculus pack ON→OFF toggle, items in item frames develop
        // a "lens through walls" effect, looking through one outlined item shows other outlined
        // items behind walls. Hypothesis was that Iris's pipeline-destroy path leaves vanilla's
        // RenderTarget.useStencil = false even though the depth-stencil attachment is still there
        // (forced by iris's MixinRenderTarget_StencilBufferTest), making glStencilFunc undefined.
        // Tried: `Minecraft.getInstance().getMainRenderTarget().enableStencil()` here in
        // clearTextures (fires on resource reload = shader toggle) AND every frame in
        // CustomGlintClientInit's RenderTickEvent.START. Both no-ops in practice, symptom
        // persisted. Stencil-flag is NOT the cause. Real cause unknown; do not re-attempt this
        // angle without new evidence.
    }

    // ── Isolated glow-outline (silhouette target + composite) ────────────────────────────────────
    // The 26.1 replacement for the entity-body stencil ring the 1.20.1/1.21.1 builds used (confirmed
    // dead end, the dilated band's depth flickered at silhouette edges). Orchestrated by
    // EntityGlintRender.drainBodyOutlines at LevelRenderer.executeOutline HEAD (off-shader) /
    // LevelRendererMixin LevelRenderer.render TAIL (under an active Iris pack).

    private static final Map<Identifier, RenderType> BY_GLOW_MASK = new HashMap<>();

    /** Worn-armor / item / special-model silhouettes accumulated by the drain itself, flushed per group into
     *  the mask target by {@link #flushGlowMask}. */
    private static final GlintBatch GLOW_MASK = new GlintBatch("customglint glow mask");

    /** Post-shader glint/chromatic overlays and the translucent-shell glint, each filled and flushed within
     *  one drain (see {@link #overlayBatch}). */
    private static final GlintBatch OVERLAY = new GlintBatch("customglint overlay");

    /** The batch the isolated overlay drains (EntityGlintRender) stage into and flush themselves. */
    public static GlintBatch overlayBatch() { return OVERLAY; }

    /** Once per frame: recycles each batch's GPU buffers and drops anything a thrown frame never flushed. */
    public static void endFrameBatches() {
        GLOW_BODY.endFrame();
        GLOW_MASK.endFrame();
        OVERLAY.endFrame();
    }

    /** Identifier the combined-mask RenderType binds for the scene-depth sampler. Resolved through
     *  TextureManager to {@link #sceneDepthTex}, a holder whose view is re-pointed at the live main-target
     *  depth each frame by {@link #bindSceneDepth}. The mask shader (core/glow_silhouette) samples this to
     *  decide per-fragment occlusion, which is why no separate depth-downsample pass is needed any more. */
    public static final Identifier SCENE_DEPTH_ID = CustomGlint.res("scene_depth");

    /** Per-texture combined-mask RenderType (GLOW_MASK_PIPE: ALWAYS_PASS depth, shape + per-fragment
     *  visibility encoded in alpha by core/glow_silhouette). One render per mob writes everything the
     *  composite needs, no separate shape pass. The draw is redirected to the half-res mask target by
     *  the caller via RenderSystem.outputColor/DepthTextureOverride. */
    public static RenderType glowMaskRT(Identifier texture) {
        RenderType rt = BY_GLOW_MASK.computeIfAbsent(texture, t ->
                GlintPipelines.glowMaskType(MOD_ID + ":glow_mask_" + texTag(t), t, SCENE_DEPTH_ID));
        return rt;
    }

    private static final Map<Identifier, RenderType> BY_WING_DEPTH = new HashMap<>();

    /** Depth-only pre-pass RenderType for folded elytra/cape wings, keyed by the equipment texture (its alpha
     *  is the wing cutout). Drawn first in the post-Iris overlay drain to prime the isolated target's depth
     *  with the NEAREST overlapping surface, so the following wing glint/chromatic pass (LEQUAL,
     *  {@link GlintPipelines#GLINT_OVERLAY_WING} / {@link GlintPipelines#CHROMATIC_OVERLAY_WING}) drops the
     *  farther one instead of additively doubling the seam down the spine. Shared by the normal and chromatic
     *  wing passes; the depth depends only on geometry + cutout, not the design. Quad items reuse it, keyed by atlas, to
     *  hide their own far faces in the same drain. */
    public static RenderType forWingDepthPrepass(Identifier texture) {
        if (texture == null) return null;
        RenderType rt = BY_WING_DEPTH.computeIfAbsent(texture, t ->
                GlintPipelines.wingDepthType(MOD_ID + ":wing_depth_" + texTag(t), t));
        return rt;
    }

    /** Accumulates one {@link Model} into the combined-mask buffer with ONE model render and ONE vertex emit
     *  (the old path rendered the model into TWO fanned buffers, a full-shape pass and a visible pass,
     *  doubling the per-mob CPU emit and the GPU silhouette fill). Occlusion is now decided per-fragment in the
     *  shader against the bound scene depth, so a single pass carries both. Nothing draws yet: every model
     *  sharing a texture piles into the same draw of the mask batch, and one {@link #flushGlowMask} then draws
     *  them all (1×textures draws, not 2×models). Entity BODIES are captured in-phase by {@link #fanBodyGlow};
     *  this method's caller is the worn-armor loop ({@code EntityGlintRender.drainBodyOutlines}), which has no
     *  in-phase hook. */
    public static void accumulateGlowMask(PoseStack pose, Model<?> model,
            RenderType maskRT, int packedLight, int color, int glowKey) {
        if (model == null || maskRT == null) return;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        VertexConsumer c = new AABBTrackingConsumer(
                new FullColorOverrideConsumer(GLOW_MASK.getBuffer(maskRT), r, g, b, glowKey), glowMaskBox);
        model.renderToBuffer(pose, c, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
    }

    // ── In-phase entity-body glow capture (option A, vanilla-parity) ──────────────────────────────
    // Vanilla's glowing-entity outline submits a SECOND model node into the outline phase, which walks
    // the model again. We do better: ModelFeatureRendererMixin wraps the body's single renderToBuffer in
    // ModelFeatureRenderer.prepareModel (the model is still posed + setupAnim'd there) and tees its
    // vertices into GLOW_BODY too. No second setupAnim, no per-entity pose alloc, no deferred re-walk.
    // GLOW_BODY is our own batch, so vanilla's feature execute never draws it into the main target;
    // drainBodyOutlines flushes it into the mask and composites.
    private static final GlintBatch GLOW_BODY = new GlintBatch("customglint glow body");
    private static boolean bodyGlowPresent;
    private static int bodyGlowCount; // this frame's fanned body count, for the outlineMaxEntities cap
    private static float bgMinX, bgMinY, bgMinZ, bgMaxX, bgMaxY, bgMaxZ;
    // Per-body camera-relative AABBs {minX,minY,minZ,maxX,maxY,maxZ} this frame, for the per-cluster
    // composite scissor (EntityGlintRender splits these into disjoint screen rects so the composite pays
    // for each cluster's screen area, not the whole union bbox spanning the gaps between them). The union
    // (bgMin/Max above) is still tracked for the mask clear + the single-rect fallback.
    private static final List<float[]> bgBoxes = new ArrayList<>();
    // Parallel to bgBoxes but the TIGHT per-body silhouette AABB (filled by an AABBTrackingConsumer in
    // fanBodyGlow as the body draws), so the drain can scissor a single glowing entity's composite to its
    // real silhouette instead of the loose pose-origin box. Cleared per frame in resetBodyGlow.
    private static final List<float[]> bgTightBoxes = new ArrayList<>();
    // Per-frame per-object mask key stamped into the vertex-colour alpha so the id-aware composite
    // (post/glow_outline_id) keeps each silhouette's outline separate AND picks a per-category thickness.
    // key = (category << 5) | id : top 2 bits = category, low 5 = a running 1..31 id. Reset in resetBodyGlow.
    private static int glowIdCounter = 0;
    /** Outline category, top 2 bits of the mask key, indexes THICKNESS[] in post/glow_outline_id.
     *  CAT_ITEM = 3rd-person held + dropped; CAT_HELD_FP = first-person held. */
    public static final int CAT_ENTITY = 0, CAT_ARMOR = 1, CAT_ITEM = 2, CAT_HELD_FP = 3;
    /** Running per-object id, 1..31 (wraps). 5 bits, leaving 2 for the category in the 7-bit mask key. */
    public static int nextGlowId() { glowIdCounter = (glowIdCounter % 31) + 1; return glowIdCounter; }
    /** Mask key = (category << 5) | id (1..127). The composite separates objects by key and reads the
     *  per-category outline thickness from the category bits. */
    public static int nextGlowKey(int category) { return (category << 5) | nextGlowId(); }

    /** One id per outline IDENTITY, shared by every silhouette that belongs to it, an entity's body and
     *  ALL its worn armor (identity = the entity render-state instance), or a special item's sub-models
     *  (identity = the submit token). Same id ⇒ the id-aware composite skips ringing between them, so two
     *  overlapping armor pieces (or body↔armor) compose as ONE ring with no doubled thickness along the
     *  seam, while DISTINCT identities still get distinct ids and stay separated. Identity-keyed (render
     *  states can collide on equals/hashCode); cleared each drain by {@link #resetBodyGlow}. */
    private static final Map<Object,Integer> GLOW_ID_BY_IDENTITY = new IdentityHashMap<>();
    /** Mask key for a silhouette belonging to {@code identity}: that identity's shared id (allocated on
     *  first use this frame) combined with {@code category} for per-category thickness. */
    public static int glowKeyFor(Object identity, int category) {
        int id = GLOW_ID_BY_IDENTITY.computeIfAbsent(identity, k -> nextGlowId());
        return (category << 5) | id;
    }

    /**
     * Wraps a glowing entity body's draw buffer so its SINGLE model walk fans into both the normal body
     * buffer AND our glow-mask buffer. The silhouette is captured for the cost of a few extra vertex
     * writes, with no second model traversal and no second setupAnim (strictly cheaper than vanilla's
     * outline, which re-renders the model). Called from {@code ModelFeatureRendererMixin}'s redirect of
     * the body {@code renderToBuffer}. Also records the camera-relative bbox for the composite scissor.
     */
    public static VertexConsumer fanBodyGlow(VertexConsumer bodyBuffer, PoseStack.Pose pose,
            int color, Identifier texture, float bbWidth, float bbHeight,
            Object entityState) {
        return fanEntityGlow(bodyBuffer, pose, color, texture, bbWidth, bbHeight, entityState, true);
    }

    /** Full-opaque silhouette for layer/special models: alpha never discards, so the whole model shape
     *  outlines (the 1.21.1 BEWLR "white.png full fill" approach). RenderLayer models (sheep wool, slime
     *  outer cube, saddle, mushrooms) are 3D geometry whose hull IS the visible shape, so this is correct.
     *  It also sidesteps recovering each layer's bound texture from the immutable RenderType. */
    private static final Identifier WHITE_SILHOUETTE = Identifier.fromNamespaceAndPath("neoforge", "textures/white.png");

    /**
     * Fans a glowing entity's secondary-model draw (a {@code RenderLayer} surface, wool, slime outer
     * cube, saddle, stray clothing, …) into the SAME glow mask the body uses, sharing the entity's outline
     * id ({@link #glowKeyFor}, CAT_ENTITY) so the whole mob composites as ONE ring with no doubled seam.
     * Like {@link #fanBodyGlow} this is in-phase (no second model walk, no second setupAnim); the layer is
     * already posed when {@code ModelFeatureRenderer.prepareModel} draws it. Uses white.png (full geometry)
     * so the silhouette follows the layer's real shape, and does NOT touch the entity cap / loose bbox
     * bookkeeping (the body already accounts the entity); only the tight silhouette box is extended so the
     * composite scissor covers a layer that sticks out past the body (mushrooms, wool).
     */
    public static VertexConsumer fanLayerGlow(VertexConsumer layerBuffer, PoseStack.Pose pose,
            int color, Object entityState) {
        return fanEntityGlow(layerBuffer, pose, color, WHITE_SILHOUETTE, 0.0f, 0.0f, entityState, false);
    }

    private static VertexConsumer fanEntityGlow(VertexConsumer baseBuffer, PoseStack.Pose pose,
            int color, Identifier texture, float bbWidth, float bbHeight,
            Object entityState, boolean isBody) {
        if (texture == null || pose == null || !GlintClientConfig.entityOutlines()) return baseBuffer;
        // Iris re-renders the body into its shadow map through this same tee; capturing there adds a
        // second, light-posed silhouette that the world drain flushes as a detached floating ring.
        if (isInShadowPass()) return baseBuffer;
        Matrix4f m = pose.pose();
        double distSq = (double) m.m30() * m.m30() + (double) m.m31() * m.m31() + (double) m.m32() * m.m32();
        if (distSq > GlintClientConfig.outlineMaxDistanceSq()) return baseBuffer; // too far: skip the outline
        if (isBody) {
            int maxEnt = GlintClientConfig.outlineMaxEntities();
            if (maxEnt > 0 && bodyGlowCount >= maxEnt) return baseBuffer; // entity cap reached this frame
            bodyGlowCount++;
        }
        RenderType rt = glowMaskRT(texture);
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        // Tight per-object silhouette AABB, filled as the model draws through the returned consumer (added to
        // bgTightBoxes by reference now; populated by drain time). Lets the drain scissor the composite to
        // the real silhouette, body AND any layer that extends past it, not the loose pose-origin box.
        float[] tight = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        VertexConsumer mask = new AABBTrackingConsumer(new FullColorOverrideConsumer(
                GLOW_BODY.getBuffer(rt), r, g, b, glowKeyFor(entityState, CAT_ENTITY)), tight);
        bgTightBoxes.add(tight);
        if (isBody) {
            // Generous camera-relative box (see EntityGlintRender.computeGroupScissor for the radius
            // rationale). Layers reuse the body's loose box (same entity), only the tight box above grows.
            float rad = bbWidth + bbHeight + 2.0f;
            float x = m.m30(), y = m.m31(), z = m.m32();
            if (!bodyGlowPresent) {
                bgMinX = x - rad; bgMaxX = x + rad; bgMinY = y - rad; bgMaxY = y + rad; bgMinZ = z - rad; bgMaxZ = z + rad;
                bodyGlowPresent = true;
            } else {
                bgMinX = Math.min(bgMinX, x - rad); bgMaxX = Math.max(bgMaxX, x + rad);
                bgMinY = Math.min(bgMinY, y - rad); bgMaxY = Math.max(bgMaxY, y + rad);
                bgMinZ = Math.min(bgMinZ, z - rad); bgMaxZ = Math.max(bgMaxZ, z + rad);
            }
            bgBoxes.add(new float[]{x - rad, y - rad, z - rad, x + rad, y + rad, z + rad});
        } else {
            // A layer fanning before its body (rare) still needs the entity group to be processed.
            bodyGlowPresent = true;
        }
        return new TeeConsumer(baseBuffer, mask);
    }

    public static boolean hasBodyGlow() { return bodyGlowPresent; }

    /** Camera-relative AABB {minX,minY,minZ,maxX,maxY,maxZ} of this frame's body glows (union), or null. */
    @Nullable
    public static float[] bodyGlowBox() {
        return bodyGlowPresent ? new float[]{bgMinX, bgMinY, bgMinZ, bgMaxX, bgMaxY, bgMaxZ} : null;
    }

    /** Per-body camera-relative AABBs captured this frame, for the per-cluster composite scissor. Live
     *  list, valid only during the drain; cleared by {@link #resetBodyGlow}. */
    public static List<float[]> bodyGlowBoxes() { return bgBoxes; }

    /** Union of this frame's TIGHT per-body silhouette AABBs (real geometry, not the loose pose box), or
     *  null if none were captured, used to scissor a glowing entity's composite to its actual size. */
    @Nullable
    public static float[] bodyGlowTightUnion() {
        float[] u = null;
        for (float[] bx : bgTightBoxes) {
            if (bx[0] > bx[3]) continue; // never drawn → still empty sentinel
            if (u == null) u = new float[]{bx[0], bx[1], bx[2], bx[3], bx[4], bx[5]};
            else {
                u[0] = Math.min(u[0], bx[0]); u[1] = Math.min(u[1], bx[1]); u[2] = Math.min(u[2], bx[2]);
                u[3] = Math.max(u[3], bx[3]); u[4] = Math.max(u[4], bx[4]); u[5] = Math.max(u[5], bx[5]);
            }
        }
        return u;
    }

    /** Draws all captured body silhouettes into the mask target. The flush consumes the staged vertices, so the
     *  next frame starts clean. */
    public static void flushBodyGlow(GpuTextureView maskColor, GpuTextureView maskDepth) {
        GLOW_BODY.flush(maskColor, maskDepth);
    }

    public static void resetBodyGlow() { bodyGlowPresent = false; bodyGlowCount = 0; bgBoxes.clear(); bgTightBoxes.clear(); glowIdCounter = 0; GLOW_ID_BY_IDENTITY.clear(); }

    // Tight per-group silhouette AABB (camera-relative world space, the ACTUAL posed geometry captured by
    // an AABBTrackingConsumer wrapped around accumulateGlowMask/Item/Part, NOT the loose pose-origin±radius
    // box). The drain projects this to scissor the COMPOSITE (the expensive 149-tap pass) to the real
    // silhouette instead of the near-fullscreen pose box a close/screen-filling object projects to (e.g. a
    // 3rd-person player in glowing armor). The loose box still drives the cheap mask clear.
    private static final float[] glowMaskBox = new float[6];
    /** Resets the tight-AABB accumulator; the drain calls this once per group before its accumulate* calls. */
    public static void resetGlowMaskBox() {
        glowMaskBox[0] = glowMaskBox[1] = glowMaskBox[2] = Float.POSITIVE_INFINITY;
        glowMaskBox[3] = glowMaskBox[4] = glowMaskBox[5] = Float.NEGATIVE_INFINITY;
    }
    /** The tight silhouette AABB {minX,minY,minZ,maxX,maxY,maxZ} accumulated since the last
     *  {@link #resetGlowMaskBox}, or null when nothing was emitted (degenerate → caller keeps the loose
     *  pose-origin scissor = prior behaviour). */
    @Nullable
    public static float[] glowMaskBox() {
        return glowMaskBox[0] <= glowMaskBox[3] ? glowMaskBox.clone() : null;
    }

    /** Accumulates one held/dropped item's silhouette into the combined glow mask, the item analog of
     *  {@link #accumulateGlowMask}. Items are a list of {@link BakedQuad}s rather than an {@link EntityModel},
     *  so each quad is re-emitted into the {@link #glowMaskRT} for its sprite's atlas (so the mask shader's
     *  Sampler0 alpha-discard follows the real item shape: flat sprites trace the sprite, 3D models trace
     *  the model). The quad's vertex colour is forced to the resolved glow colour via
     *  {@link FullColorOverrideConsumer}; the pose is the camera-relative {@code ItemFeatureRenderer.Submit} pose, the same
     *  space the entity bodies drew in, so the silhouette lands at the right screen position. Runs inside the
     *  after-main-pass drain, sharing the mask + composite with entity outlines (one unioned ring). */
    public static void accumulateItemGlowMask(PoseStack.Pose pose, List<BakedQuad> quads, int packedLight,
            int color, int category) {
        if (quads == null || quads.isEmpty()) return;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        QuadInstance qi = new QuadInstance();
        qi.setLightCoords(packedLight);
        qi.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        qi.setColor(0xFFFFFFFF); // white base; FullColorOverrideConsumer forces the glow colour per-vertex
        int gid = nextGlowKey(category); // one key (category + id) for the item so its quads share an outline
        // The wrappers are quad-invariant (same r/g/b/gid + shared glowMaskBox); only the underlying buffer
        // changes per atlas. Build one wrapper per distinct atlas instead of one per quad, a modeled item
        // (crossbow, banner) has dozens of quads but usually a single atlas, so this drops the per-frame
        // allocation from O(quads) to O(distinct atlases).
        Map<Identifier, VertexConsumer> byAtlas = new HashMap<>();
        for (BakedQuad quad : quads) {
            Identifier tex = quad.materialInfo().sprite().atlasLocation();
            VertexConsumer c = byAtlas.get(tex);
            if (c == null) {
                c = new AABBTrackingConsumer(
                        new FullColorOverrideConsumer(GLOW_MASK.getBuffer(glowMaskRT(tex)), r, g, b, gid), glowMaskBox);
                byAtlas.put(tex, c);
            }
            c.putBakedQuad(pose, quad, qi);
        }
    }

    /** Draws every silhouette accumulated for the current group (one draw per texture) into the mask target. */
    public static void flushGlowMask(GpuTextureView maskColor, GpuTextureView maskDepth) {
        GLOW_MASK.flush(maskColor, maskDepth);
    }

    /** Re-points the scene-depth holder at the live main-target depth view (registering it with
     *  TextureManager on first use). Must be called each frame before the mask RTs flush, so the combined
     *  mask shader samples the current frame's committed scene depth. The holder borrows the view; it never
     *  owns or closes it (see {@link SceneDepthTexture#close}). */
    public static void bindSceneDepth(GpuTextureView view) {
        if (sceneDepthTex == null) {
            sceneDepthTex = new SceneDepthTexture();
            Minecraft.getInstance().getTextureManager().register(SCENE_DEPTH_ID, sceneDepthTex);
        }
        sceneDepthTex.view = view;
    }

    private static SceneDepthTexture sceneDepthTex;

    /** Identifier for the STABLE opaque-depth snapshot (main depth copied at the end of the solid phase, before
     *  any translucent shell draws). The translucent-layer glint samples this so it occludes against terrain +
     *  solid bodies instead of the shell's per-frame re-sorted depth. See {@code EntityGlintRender.captureSolidDepth}. */
    public static final Identifier SOLID_DEPTH_ID = CustomGlint.res("solid_depth");
    private static SceneDepthTexture solidDepthTex;

    /** Re-points the solid-depth holder at the opaque-depth snapshot view (see {@link #SOLID_DEPTH_ID}).
     *  Borrowed view; the snapshot target is owned by {@code EntityGlintRender}. */
    public static void bindSolidDepth(GpuTextureView view) {
        if (solidDepthTex == null) {
            solidDepthTex = new SceneDepthTexture();
            Minecraft.getInstance().getTextureManager().register(SOLID_DEPTH_ID, solidDepthTex);
        }
        solidDepthTex.view = view;
    }

    /** Borrowed-view holder so a RenderType (which resolves textures by Identifier through TextureManager)
     *  can sample the main-target depth, which is a raw GpuTextureView with no Identifier of its own. The
     *  view is owned by the main render target, close() must NOT free it. */
    private static final class SceneDepthTexture extends AbstractTexture {
        GpuTextureView view;
        @Override public GpuTextureView getTextureView() { return view; }
        @Override public void close() { /* borrowed view, owned by the main render target, never freed here */ }
    }

    /**
     * Composite the per-object mask into the ring via the single-pass id-aware shader
     * (post/glow_outline_id): a pixel rings where a DIFFERENT object's visible silhouette is within reach,
     * so each glowing entity / armor / item keeps its own outline instead of merging into one union ring.
     * The mask packs object id + visibility into alpha (core/glow_silhouette). {@code scissor} ({x,y,w,h},
     * bottom-left origin, full-res pixels) confines the pass to a group's screen bbox; null draws full
     * screen. MaskSampler is NEAREST (the alpha is a packed classifier, not a continuous value).
     */
    public static void compositeGlowOutline(GpuTextureView maskColorView, GpuTextureView maskDepthView,
                                            GpuTextureView outColorView, int[] scissor) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "customglint glow outline composite", outColorView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(GlintPipelines.GLOW_COMPOSITE_ID_PIPE));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("MaskSampler", maskColorView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            // Scene depth, for the ring-occlusion test at the RING pixel (what world geometry stands there).
            // bindSceneDepth(main depth) runs each frame before any composite (EntityGlintRender), so the
            // holder's view is live here. NEAREST + clamp: a depth read needs the exact texel, not a blend.
            pass.setUniform("DepthSampler", sceneDepthTex.view,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            // The mask target's OWN depth, written by GLOW_MASK_PIPE at exactly the silhouette texels, which
            // is where the SOURCE's distance has to come from. See the srcDist note in post/glow_outline_id.
            pass.setUniform("MaskDepthSampler", maskDepthView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            if (scissor != null) pass.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            pass.draw(3, 1, 0, 0);
        }
    }

    /** Bilinear-upscales the half-res ring ({@code ringColorView}) onto the main target, blended. */
    public static void upscaleGlowRing(GpuTextureView ringColorView, GpuTextureView mainColorView) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "customglint glow upscale", mainColorView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(GlintPipelines.GLOW_UPSCALE_PIPE));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("InSampler", ringColorView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
    }


    private static Identifier generateTexture(Identifier design) {
        Minecraft mc = Minecraft.getInstance();
        NativeImage gray = loadGrayscale(design);
        if (gray == null) return null;
        String safePath = design.getNamespace() + "/" + design.getPath().replace('/', '_').replace('.', '_');
        Identifier loc = CustomGlint.res("glint/" + safePath);
        // 26.1: DynamicTexture needs a label supplier; wrap/filter (REPEAT + NEAREST) is no longer set
        // on the texture, GlintPipelines.glintSampler() supplies it per binding.
        DynamicTexture dt = new DynamicTexture(() -> MOD_ID + ":glint/" + safePath, gray);
        mc.getTextureManager().register(loc, dt);
        return loc;
    }

    /** Reads a design PNG and converts it to a fresh grayscale {@link NativeImage} (the caller owns and must
     *  close it). Returns null when the design is absent/unreadable. Shared by {@link #generateTexture} (one
     *  texture per design, the world path) and {@link #ensureGuiDesignAtlas} (all designs stitched into one
     *  GUI atlas so the inventory glint overlays batch). */
    private static NativeImage loadGrayscale(Identifier design) {
        Minecraft mc = Minecraft.getInstance();
        NativeImage source;
        try {
            var resource = mc.getResourceManager().getResource(design);
            if (resource.isEmpty()) return null;
            try (InputStream stream = resource.get().open()) {
                source = NativeImage.read(stream);
            }
        } catch (IOException e) {
            return null;
        }
        // Allocate `gray` and run the copy INSIDE the try whose finally closes `source`: the
        // `new NativeImage(...)` can itself throw (native allocation failure), and if it sat outside
        // the try the already-read `source` would leak its native buffer on every retry. If the copy
        // throws after gray is allocated, close gray too before propagating.
        NativeImage gray = null;
        long lumSum = 0; // Σ lum×alpha over the design, 0..255*255 per texel; feeds envelopeDim
        try {
            gray = new NativeImage(source.getWidth(), source.getHeight(), false);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    // 26.1: getPixel/setPixel are ARGB (0xAARRGGBB). The gray result is channel-
                    // symmetric so the packing is unchanged; only the channel reads differ.
                    int pixel = source.getPixel(x, y);
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >>  8) & 0xFF;
                    int b =  pixel        & 0xFF;
                    int a = (pixel >> 24) & 0xFF;
                    int lum = (r + g + b) / 3;
                    gray.setPixel(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
                    lumSum += (long) lum * a;
                }
            }
            // long divisor: a 256×256 data-pack design overflows this product in int arithmetic.
            float mean = lumSum / (float) ((long) source.getWidth() * source.getHeight() * 255 * 255);
            designDim.put(design, envelopeDim(mean));
        } catch (Throwable t) {
            if (gray != null) gray.close();
            throw t;
        } finally {
            source.close();
        }
        return gray;
    }

    // ── Vanilla's glint envelope (shader-pack brightness compat) ─────────────────────────────────
    //
    // Mean luminance of vanilla's enchanted_glint_item.png. Its peak is 0.66, so it never goes near white.
    // Packs that treat the glint as emissive and amplify it are calibrated against that texture, which makes
    // it the only contract they all share.
    //
    // Our designs don't respect it: solid is mean 1.00, grid 0.63, crosshatch 0.54, and a dozen more sit
    // above vanilla with peaks pinned at 1.0. A pack tuned for vanilla is then handed up to 3x the energy it
    // expects, amplifies it as designed, and every channel saturates. The colour is gone and the glint reads
    // as a white-on-black print of the design. The pack is not wrong. So scale each design under such a pack
    // until its mean energy lands inside vanilla's envelope.
    private static final float VANILLA_GLINT_MEAN = 0.36f;

    /** Per-design brightness scalar for the pack path, measured in {@link #loadGrayscale}. */
    private static final Map<Identifier, Float> designDim = new HashMap<>();

    /** Under-pack brightness for a design of this mean energy: enough to bring it into vanilla's envelope,
     *  never more than 1.0. The clamp matters as much as the scale: sparse designs (sparkle at 0.05, ember
     *  at 0.01) already sit far below vanilla, and scaling those up would blow a few scattered specks into
     *  something the design never meant to be. */
    private static float envelopeDim(float mean) {
        if (mean <= 0.0f) return 1.0f; // fully transparent or fully black design; nothing to scale
        return Math.min(1.0f, VANILLA_GLINT_MEAN / mean);
    }

    // ── Photon compat ────────────────────────────────────────────────────────────────────────────
    //
    // The one pack that drives our glint into the saturation described above. Its gbuffers_armor_glint runs
    // the texture through a raw power law and scales by ENCHANTMENT_GLINT_BRIGHTNESS:
    //     frag_color.rgb = (srgb_eotf_inv(armor_glint) * rec709_to_working_color) * ENCHANTMENT_GLINT_BRIGHTNESS;
    // and its tonemapper bleaches what comes out of that. Sparse designs never reach the ceiling and keep
    // their colour, which is the whole "some designs still have colour" split, ordered by design density.
    // BSL, Bliss and Complementary stay inside their range at the same input, so they take no envelope.
    //
    // This is a pack-NAME gate feeding one scalar derived from a vanilla asset, not a table of tuned
    // per-pack numbers. Do not grow it into one: every new pack would be a new entry and it decays the
    // moment one ships. If another pack looks like it needs an entry, first check that it really does
    // amplify past saturation rather than just looking bright.
    private static volatile String PACK_DIM_LAST_NAME = null;
    private static volatile boolean PACK_DIM_LAST_RESULT = false;

    /** True while the active pack pushes our glint past its own ceiling and needs the envelope. */
    private static boolean packNeedsGlintDim() {
        if (!frameShaderActive) return false;
        String name = currentPackName();
        if (name == null) return false;
        if (!name.equals(PACK_DIM_LAST_NAME)) { // recomputed only when the pack actually changes
            PACK_DIM_LAST_NAME = name;
            PACK_DIM_LAST_RESULT = name.toLowerCase(Locale.ROOT).contains("photon");
        }
        return PACK_DIM_LAST_RESULT;
    }

    private static volatile boolean PACK_NAME_LOOKUP_DONE = false;
    private static volatile Field IRIS_PACK_NAME = null;

    /** The active shaderpack's name, or null with no shader mod / no pack. Reads Iris's private
     *  {@code currentPackName}: neither IrisApi nor Iris exposes a public accessor, and an access transformer
     *  only reaches Minecraft classes, so there is no cleaner route to it. */
    @Nullable
    public static String currentPackName() {
        if (!PACK_NAME_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!PACK_NAME_LOOKUP_DONE) {
                    try {
                        Field f = Class.forName("net.irisshaders.iris.Iris").getDeclaredField("currentPackName");
                        f.setAccessible(true);
                        IRIS_PACK_NAME = f;
                    } catch (Throwable ignored) {
                        IRIS_PACK_NAME = null;
                    }
                    PACK_NAME_LOOKUP_DONE = true;
                }
            }
        }
        if (IRIS_PACK_NAME == null) return null;
        try {
            return (String) IRIS_PACK_NAME.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * One glint layer's draw colour, with the active pack's brightness envelope applied. Every world glint
     * colour goes through here (the animated single colour via {@link #computeAnimatedColor}, each colour of
     * a simultaneous layer at the call site), so a pack that needs the envelope gets it on every surface.
     * Returns {@code argb} untouched off a pack and on every pack that doesn't need one. The GUI path
     * ({@link #computeAnimatedColorGui}) is excluded on purpose: no pack program ever touches it.
     */
    public static int packAdjustedColor(Data glint, int layerIdx, int argb) {
        if (!packNeedsGlintDim()) return argb;
        Layer[] layers = glint.layers();
        if (layerIdx < 0 || layerIdx >= layers.length) return argb;
        Float d = designDim.get(layers[layerIdx].design());
        if (d == null || d >= 1.0f) return argb;   // chromatic, unloaded, or already inside the envelope
        return (argb & 0xFF000000)
                | (Math.round(((argb >> 16) & 0xFF) * d) << 16)
                | (Math.round(((argb >>  8) & 0xFF) * d) <<  8)
                |  Math.round(( argb        & 0xFF) * d);
    }

    // ── GUI design atlas (inventory glint-overlay batching) ──────────────────────────────────────
    // Each glinted inventory icon draws its scrolling glint as a live overlay GLYPH (GuiRendererMixin),
    // one per layer/colour. Those glyphs are NOT sorted by the GUI renderer (only blits are), and the GUI
    // mesher flushes a new draw on every texture change, so with a per-design Sampler1 each distinct design
    // forced its own draw call (an inventory full of trims = dozens of draws/frame). Stitching every design
    // into ONE shared atlas makes every glint glyph carry the SAME TextureSetup, so they batch into a single
    // draw regardless of how many distinct designs are on screen. The per-glyph design is selected in-shader
    // from a cell index (packed into the spare high bits of UV2.y); each cell is built with a wrapped gutter
    // so the in-shader fract() tiling stays seamless under LINEAR filtering.
    private static final int GUI_ATLAS_CONTENT = 64;  // design content size per cell (designs are <=64px)
    private static final int GUI_ATLAS_GUTTER  = 4;   // wrapped border per side, so LINEAR can't seam-bleed
    private static final int GUI_ATLAS_STRIDE  = GUI_ATLAS_CONTENT + 2 * GUI_ATLAS_GUTTER; // 72
    // The grid is sized to the design count at build time (ceil(sqrt(n)) cells per side) so data-pack designs
    // batch too, not just the built-ins. Only the grid dimension varies; the shader recovers it from
    // textureSize(Sampler1)/STRIDE, so no per-draw uniform is needed. The cell index rides 8 bits of the GUI
    // glint vertex payload (UV2.y bits 8-15), so at most 256 designs share the atlas; any beyond that fall
    // back to the per-design draw path (still correct, just one draw each).
    private static final int GUI_ATLAS_MAX_CELLS = 256;
    public static final Identifier GUI_DESIGN_ATLAS_ID = CustomGlint.res("gui_design_atlas");
    private static boolean guiDesignAtlasBuilt = false;
    /** design Identifier → its 0-based cell index in the atlas. Absent designs (CHROMATIC, load failures, or
     *  beyond {@link #GUI_ATLAS_MAX_CELLS}) fall back to the per-design draw path. */
    private static final Map<Identifier, Integer> guiDesignCell = new HashMap<>();
    /** Full design list to atlas (built-ins + data-pack designs), installed by the full mod at client init
     *  since the data-pack design list lives in the module, not the api jar. Null → built-ins only (an
     *  api-only embedder without the full mod's data-pack list). */
    private static volatile Supplier<List<Identifier>> guiAtlasDesignSource = null;

    /** Installs the design source for the shared GUI atlas: the full mod passes its data-pack-inclusive
     *  design list here so those designs batch like the built-ins. Forces a rebuild on the next draw. */
    public static void setGuiAtlasDesignSource(Supplier<List<Identifier>> source) {
        guiAtlasDesignSource = source;
        invalidateGuiDesignAtlas();
    }

    /** Drops the built GUI atlas so the next inventory glint draw re-stitches it; call after the design list
     *  changes (data-pack reload / server sync) or a resource reload. Idempotent; no-op if not yet built. */
    public static void invalidateGuiDesignAtlas() {
        if (!guiDesignAtlasBuilt) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.getTextureManager().release(GUI_DESIGN_ATLAS_ID);
        guiDesignCell.clear();
        guiDesignAtlasBuilt = false;
    }

    /** Builds the shared GUI design atlas once (lazily, on first inventory glint draw). Idempotent; the
     *  {@code built} flag is set first so a failed build degrades to the per-design fallback for the whole
     *  session instead of retrying (and stalling) every frame. Rebuilt after a resource reload via
     *  {@link #clearTextures}. */
    private static void ensureGuiDesignAtlas() {
        if (guiDesignAtlasBuilt) return;
        guiDesignAtlasBuilt = true;
        // Source list: the full mod's data-pack-inclusive designs when installed, else the built-ins. Guarded
        // because the source reads a list mutated on other threads (the data-pack reload), a torn read falls
        // back to the built-ins rather than aborting the build.
        List<Identifier> designs;
        try {
            Supplier<List<Identifier>> src = guiAtlasDesignSource;
            designs = src != null ? src.get() : Arrays.asList(CustomGlint.PATTERNS);
        } catch (Throwable t) {
            designs = Arrays.asList(CustomGlint.PATTERNS);
        }
        if (designs == null || designs.isEmpty()) designs = Arrays.asList(CustomGlint.PATTERNS);
        int count = Math.min(designs.size(), GUI_ATLAS_MAX_CELLS);
        int grid = (int) Math.ceil(Math.sqrt(count));       // cells per side, so grid*grid >= count
        int dim = grid * GUI_ATLAS_STRIDE;
        NativeImage atlas = new NativeImage(dim, dim, false);
        try {
            for (int i = 0; i < count; i++) {
                Identifier design = designs.get(i);
                if (design == null || design.equals(CustomGlint.CHROMATIC)) continue; // procedural: no texture
                NativeImage gray = loadGrayscale(design);
                if (gray == null) continue;                          // missing → per-design fallback
                try {
                    int col = i % grid, row = i / grid;
                    int ox = col * GUI_ATLAS_STRIDE, oy = row * GUI_ATLAS_STRIDE;
                    int sw = gray.getWidth(), sh = gray.getHeight();
                    for (int gy = 0; gy < GUI_ATLAS_STRIDE; gy++) {
                        for (int gx = 0; gx < GUI_ATLAS_STRIDE; gx++) {
                            // Local cell coord (content origin at GUTTER,GUTTER), wrapped into the design so
                            // the gutter holds the opposite-edge texels: seamless LINEAR tiling in the shader.
                            int lx = gx - GUI_ATLAS_GUTTER, ly = gy - GUI_ATLAS_GUTTER;
                            int cx = ((lx % GUI_ATLAS_CONTENT) + GUI_ATLAS_CONTENT) % GUI_ATLAS_CONTENT;
                            int cy = ((ly % GUI_ATLAS_CONTENT) + GUI_ATLAS_CONTENT) % GUI_ATLAS_CONTENT;
                            atlas.setPixel(ox + gx, oy + gy,
                                    gray.getPixel(cx * sw / GUI_ATLAS_CONTENT, cy * sh / GUI_ATLAS_CONTENT));
                        }
                    }
                    guiDesignCell.put(design, i);
                } finally {
                    gray.close();
                }
            }
            // DynamicTexture takes ownership of `atlas` on register (don't close it on the success path).
            Minecraft.getInstance().getTextureManager().register(GUI_DESIGN_ATLAS_ID,
                    new DynamicTexture(() -> MOD_ID + ":gui_design_atlas", atlas));
        } catch (Throwable t) {
            atlas.close();
            guiDesignCell.clear();
            LOGGER.warn("[{}/CustomGlint] GUI design atlas build failed; falling back to per-design glint draws", MOD_ID, t);
        }
    }

    /** The shared GUI design-atlas texture view (one for every glinted icon → the glint glyphs batch), or
     *  null if the atlas isn't built/available (caller falls back to the per-design path). */
    public static GpuTextureView guiDesignAtlasView() {
        ensureGuiDesignAtlas();
        AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(GUI_DESIGN_ATLAS_ID);
        return t != null ? t.getTextureView() : null;
    }

    /** The atlas cell index for {@code design}, or null when it isn't atlased (CHROMATIC, a load failure, or
     *  past {@link #GUI_ATLAS_MAX_CELLS}) and the per-design draw path must be used. */
    public static Integer guiDesignCellIndex(Identifier design) {
        ensureGuiDesignAtlas();
        return guiDesignCell.get(design);
    }

    /** Sampler for the GUI design atlas: CLAMP (tiling is done in-shader via fract + the wrapped gutters,
     *  so the bound sampler must NOT wrap across cell boundaries) + LINEAR (matches the per-design design
     *  sampler's filtering). Cached instance, so every atlas glyph shares one TextureSetup and batches. */
    public static GpuSampler guiDesignAtlasSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    // ── Procedural chromatic ────────────────────────────────────────────────────

    /** Lazily builds the 1×1 opaque-white texture bound to the chromatic pipeline's unused Sampler0
     *  (the chromatic shader never samples it, but the inherited binding must point somewhere). */
    private static Identifier getWhiteTexture() {
        if (whiteTexture != null) return whiteTexture;
        NativeImage img = new NativeImage(1, 1, false);
        img.setPixel(0, 0, 0xFFFFFFFF);
        Identifier loc = CustomGlint.res("chromatic_white");
        Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(() -> MOD_ID + ":chromatic_white", img));
        whiteTexture = loc;
        return loc;
    }

    /** Builds (and caches) the palette strip for {@code colors}, one opaque RGBA texel per color. An empty
     *  list yields a 1×1 dummy; the shader reads the real count from the animation matrix and takes the
     *  rainbow-fallback path, so the dummy texel is never read. */
    private static Identifier getPaletteTexture(int[] colors) {
        String key = colorsKey(colors);
        Identifier cached = paletteCache.get(key);
        if (cached != null) return cached;
        int w = Math.max(1, colors.length);
        NativeImage img = new NativeImage(w, 1, false);
        for (int i = 0; i < w; i++) {
            int c = i < colors.length ? colors[i] : 0xFFFFFFFF;
            img.setPixel(i, 0, 0xFF000000 | (c & 0xFFFFFF)); // force opaque so texelFetch returns the pure color
        }
        Identifier loc = CustomGlint.res("chromatic_palette/" + key);
        Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(() -> MOD_ID + ":chromatic_palette", img));
        paletteCache.put(key, loc);
        return loc;
    }

    /** Content key for a color array, distinct colors map to distinct keys (unlike {@code Arrays.hashCode},
     *  which collides). Hex digits + {@code '_'} only, so it doubles as a valid resource-path segment. */
    private static String colorsKey(int[] colors) {
        StringBuilder sb = new StringBuilder(colors.length * 9);
        for (int c : colors) sb.append(Integer.toHexString(c)).append('_');
        return sb.toString();
    }

    /** The palette an UNCOLORED chromatic layer renders with, white / grey / dark grey, so an "empty"
     *  chromatic trim reads as a neutral greyscale oil-slick instead of the full-spectrum rainbow fallback. */
    public static final int[] CHROMATIC_EMPTY_PALETTE = { 0xFFFFFFFF, 0xFF8A8A8A, 0xFF3A3A3A };

    /** A chromatic layer's effective palette: its own colors, or {@link #CHROMATIC_EMPTY_PALETTE} when it has
     *  none. Both the world RenderType and the GUI overlay resolve colors through this so an empty chromatic
     *  trim looks the same everywhere. */
    public static int[] chromaticColors(int[] colors) {
        return colors.length == 0 ? CHROMATIC_EMPTY_PALETTE : colors;
    }

    /** Public accessor for the GUI overlay path: the cached palette-strip texture for {@code colors}
     *  (same texture the world chromatic RenderType binds to Sampler1). */
    public static Identifier paletteTexture(int[] colors) {
        return getPaletteTexture(colors);
    }

    /** Reduces a 32-bit seed to a small, well-distributed float so {@code seed*offset} in the shader stays
     *  within float precision (the raw seed would blow up the noise coordinates). */
    private static float packSeed(int seed) {
        return (seed & 0xFFFF) / 256.0f; // 0..256
    }

    /** Noise UV scale for chromatic on MODEL surfaces (armor, entity bodies, horse armor). These sample their
     *  own 0..1 model UV. Tunable: higher = smaller/more colour cells.
     *
     *  <p>4.0 against the shaders' {@code DENSITY} of 14 puts 56 colour cells across a model's 0..1 UV at
     *  scale 1, which is the 1.20.1 look ({@code CHROMATIC_MODEL_UV_SCALE} 8.0 against a baked field of 7
     *  cells per repeat). TRIED 0.5, carried over from the 1.21.1 port on the theory that 8.0 was tiling the
     *  field too densely: that is 8× coarser than 1.20.1 and reads as a few big smears on a chestplate
     *  instead of an oil-slick. 1.20.1 is the reference; do not lower this again without comparing side by
     *  side against it. */
    private static final float CHROMATIC_MODEL_UV_SCALE = 4.0f;

    /** Standardized noise UV scale for chromatic on SPECIAL 3D BEWLR items (shield, trident). 1.21.1 parity:
     *  a special item is small on screen, so the plain 3D scale (1.0) reads as one coarse blob rather than a
     *  slick (the reported "hard to see in 3rd person"). 3.0 gives enough colour cells to read as an oil-slick
     *  at small on-screen sizes, and is applied BOTH in-phase ({@link #forChromaticSpecialGlint}) and in the
     *  post-Iris overlay ({@link #forSpecialItemGlintOverlay}) so the slick is identical on and off a pack. */
    private static final float CHROMATIC_SPECIAL_ITEM_UV_SCALE = 3.0f;

    /** Brings the IN-WORLD flat held/dropped item's chromatic slick to the same cell scale as worn ARMOR.
     *  The flat-item noise scale ({@code atlasW/16}) is calibrated to the block atlas so the world item matches
     *  the INVENTORY ICON's density. But the item sprite IS the whole visible surface, while armor's 0..1 UV
     *  wraps a large body (a visible face shows only a slice of it), so for the SAME scale slider the held item
     *  measured ~16× finer than the armor. This divisor cancels that: the same trim at the same scale now reads
     *  at the same density on a held item and on worn armor. Tunable: raise the denominator for a coarser item,
     *  lower it for finer; {@code 1.0} = the old GUI-icon-matched density (~16× finer than armor). NOTE: this
     *  decouples the in-world item from the inventory icon.
     *
     *  <p>1/2 against {@code DENSITY} 14 puts 7 colour cells across a 16px sprite at scale 1, the 1.20.1
     *  density. The earlier 1/16 was paired with the 0.5 model scale and left the icon 8× coarser. */
    private static final float CHROMATIC_FLAT_ITEM_MATCH = 1.0f / 2.0f;

    /** The flat-item chromatic-density match factor (see {@link #CHROMATIC_FLAT_ITEM_MATCH}), for the GUI
     *  overlay path to apply to its packed patternScale so the inventory icon reads at the same cell scale as
     *  the in-world held item (and thus worn armor). Shared so the two flat-item paths never drift. */
    public static float chromaticFlatItemMatch() {
        return CHROMATIC_FLAT_ITEM_MATCH;
    }

    /** Single-draw chromatic RenderType for {@code layer}. The palette carries every color, so unlike the
     *  normal glint factories this is never looped per-color. {@code tag} keeps the item / armor / entity
     *  layering variants in separate cache buckets. {@code uvScale} multiplies the noise UV so the on-item
     *  density matches the GUI overlay: flat items sample the BLOCK ATLAS, where a 16px sprite spans only
     *  {@code spritePx/atlasPx} of UV space, so without this the whole oil-slick collapses to one zoomed-in
     *  blob. {@code atlasW/16} makes a 16px sprite span ~1 UV unit (= the GUI's item-local 0..1), so both show
     *  the same ~DENSITY cells. Armor/entity sample their own 0..1 model UV, so they pass {@code 1.0}. */
    private static RenderType chromaticRT(Layer layer, LayeringTransform layering, String tag, float uvScale) {
        return chromaticRT(layer, layering, tag, uvScale, false);
    }

    /** {@code overlay=true} builds on {@link GlintPipelines#CHROMATIC_OVERLAY} (LEQUAL depth) for chromatic
     *  drawn on top of a separately-rendered surface, block-model entity layers. See {@link #forBlockGlint}. */
    private static RenderType chromaticRT(Layer layer, LayeringTransform layering, String tag, float uvScale, boolean overlay) {
        int[] colors = chromaticColors(layer.colors());
        final double speed = layer.speed();
        final float effScale = layer.patternScale() * uvScale;
        final int cc = colors.length;
        final float seedPacked = packSeed(layer.seed());
        Identifier white = getWhiteTexture();
        Identifier palette = getPaletteTexture(colors);
        String key = tag + "|" + colorsKey(colors) + "|" + speed + "|" + effScale + "|" + layer.seed();
        RenderType cached = BY_CHROMATIC.computeIfAbsent(key, k -> {
            String name = MOD_ID + ":custom_chromatic|" + k.hashCode();
            Supplier<Matrix4f> anim = () -> GlintPipelines.chromaticMatrix(speed, effScale, cc, seedPacked);
            RenderType rt = overlay
                    ? GlintPipelines.chromaticBlockType(name, white, palette, TextureAtlas.LOCATION_BLOCKS, layering, anim)
                    : GlintPipelines.chromaticType(name, white, palette, layering, anim);
            return rt;
        });
        return cached;
    }

    /**
     * Post-Iris chromatic OVERLAY RenderType for {@code layer}, the {@link #chromaticRT} counterpart on
     * {@link GlintPipelines#CHROMATIC_OVERLAY} (ALWAYS depth + an in-shader scene-depth occlusion test).
     * Same palette / seed / speed / count / pattern-scale payload, so the slick matches the in-phase look;
     * it just survives an active shader pack by being re-rendered after the framegraph (see
     * {@code EntityGlintRender.drainChromaticOverlays}). Only meaningful for chromatic layers; callers gate
     * on {@code CustomGlint.isChromatic} and {@link #isShaderPackActive()}.
     */
    private static RenderType chromaticOverlayRT(Layer layer, String tag, float uvScale, @Nullable Identifier texture) {
        return chromaticOverlayRT(layer, tag, uvScale, texture, SCENE_DEPTH_ID);
    }

    /** As {@link #chromaticOverlayRT(Layer, String, float, Identifier)} but samples {@code depthId} for the
     *  occlusion test. The post-Iris path uses {@link #SCENE_DEPTH_ID}; the in-phase translucent-shell path
     *  uses {@link #SOLID_DEPTH_ID} (the stable opaque-depth snapshot) so a slime shell's chromatic doesn't
     *  fight the shell's re-sorted depth. */
    private static RenderType chromaticOverlayRT(Layer layer, String tag, float uvScale, @Nullable Identifier texture,
                                                 Identifier depthId) {
        return chromaticOverlayRT(layer, tag, uvScale, texture, depthId, false);
    }

    /** As above but {@code wing=true} routes onto {@link GlintPipelines#CHROMATIC_OVERLAY_WING} for thin
     *  double-sided equipment (elytra wings), so the additive slick doesn't double where the wings overlap. */
    private static RenderType chromaticOverlayRT(Layer layer, String tag, float uvScale, @Nullable Identifier texture,
                                                 Identifier depthId, boolean wing) {
        return chromaticOverlayRT(layer, tag, uvScale, texture, depthId, false, wing);
    }

    /** @param loose true routes onto {@link GlintPipelines#CHROMATIC_OVERLAY_LOOSE} (flat generous occlusion)
     *      for a translucent shell; false is the standard slope-scaled {@link GlintPipelines#CHROMATIC_OVERLAY}.
     *      Mutually exclusive with {@code wing} (a thin wing is never a translucent shell). */
    private static RenderType chromaticOverlayRT(Layer layer, String tag, float uvScale, @Nullable Identifier texture,
                                                 Identifier depthId, boolean loose, boolean wing) {
        int[] colors = chromaticColors(layer.colors());
        final double speed = layer.speed();
        final float effScale = layer.patternScale() * uvScale;
        final int cc = colors.length;
        final float seedPacked = packSeed(layer.seed());
        // Sampler0 is the MODEL texture (its alpha cuts the slick to the real silhouette, a rectangular
        // elytra/armor mesh would otherwise fill its whole bounding quad). Fall back to the opaque white
        // dummy (alpha 1 → no discard, full mesh) when the caller has no texture (e.g. an opaque body).
        final Identifier sampler0 = texture != null ? texture : getWhiteTexture();
        Identifier palette = getPaletteTexture(colors);
        String key = tag + "|" + (wing ? "wing|" : "") + (loose ? "loose|" : "") + depthId + "|" + sampler0 + "|" + colorsKey(colors) + "|" + speed + "|" + effScale + "|" + layer.seed();
        RenderType cached = BY_CHROMATIC_OVERLAY.computeIfAbsent(key, k -> {
            String name = MOD_ID + ":custom_chromatic_overlay|" + k.hashCode();
            Supplier<Matrix4f> anim = () -> GlintPipelines.chromaticMatrix(speed, effScale, cc, seedPacked);
            RenderType rt = loose
                    ? GlintPipelines.chromaticOverlayLooseType(name, sampler0, palette, depthId, anim)
                    : GlintPipelines.chromaticOverlayType(name, sampler0, palette, depthId, anim, wing);
            return rt;
        });
        return cached;
    }

    /** In-phase (off shader-pack) chromatic RT for an entity LAYER (sheep wool, slime outer shell), drawn on
     *  top after the main pass and occluded against the stable opaque-depth snapshot ({@link #SOLID_DEPTH_ID}),
     *  the chromatic counterpart of {@link #forEntityTranslucentLayerGlint}. Replaces routing layer chromatic
     *  through the EQUAL-depth body RT (which flickered against the layer's re-sorted / different-pipeline depth). */
    public static RenderType forEntityLayerChromaticSolid(Data glint, int layerIdx) {
        return chromaticOverlayRT(glint.layers()[layerIdx], "layer_chroma_solid", CHROMATIC_MODEL_UV_SCALE,
                getWhiteTexture(), SOLID_DEPTH_ID);
    }

    /** Post-Iris chromatic overlay RT for worn equipment (humanoid armor, elytra/cape, barding), the
     *  {@link #forArmorGlint} chromatic branch re-routed onto {@link GlintPipelines#CHROMATIC_OVERLAY}.
     *  {@code texture} is the equipment-layer texture whose alpha cuts the slick to the real armor shape. */
    public static RenderType forArmorGlintOverlay(Data glint, int layerIdx, @Nullable Identifier texture) {
        return forArmorGlintOverlay(glint, layerIdx, texture, false);
    }

    /** {@code wing=true} routes the elytra/cape (thin double-sided wings) onto the LEQUAL wing overlay pipeline
     *  so the additive slick doesn't double where the wings overlap; it pairs with the depth pre-pass the
     *  caller queues alongside it. */
    public static RenderType forArmorGlintOverlay(Data glint, int layerIdx, @Nullable Identifier texture, boolean wing) {
        return chromaticOverlayRT(glint.layers()[layerIdx], "armor_ov", CHROMATIC_MODEL_UV_SCALE, texture, SCENE_DEPTH_ID, wing);
    }

    /** Post-Iris chromatic overlay RT for entity bodies / horse armor, the {@link #forEntityGlint} chromatic
     *  branch re-routed onto {@link GlintPipelines#CHROMATIC_OVERLAY}. {@code texture} is the body texture
     *  (its alpha cuts the slick to the entity shape); null for an opaque body draws the whole mesh. */
    public static RenderType forEntityGlintOverlay(Data glint, int layerIdx, @Nullable Identifier texture) {
        return chromaticOverlayRT(glint.layers()[layerIdx], "entity_ov", CHROMATIC_MODEL_UV_SCALE, texture);
    }

    /** LOOSE-occlusion chromatic overlay RT for a TRANSLUCENT shell (slime outer cube), the chromatic twin of
     *  {@link #forEntityGlintOverlayNormalLoose}. The shell's committed depth is re-sorted every frame under
     *  Iris, so the standard slope-scaled occlusion self-culls the slick on some faces at some angles. */
    public static RenderType forEntityGlintOverlayLoose(Data glint, int layerIdx, @Nullable Identifier texture) {
        return chromaticOverlayRT(glint.layers()[layerIdx], "entity_ov_loose", CHROMATIC_MODEL_UV_SCALE, texture,
                SCENE_DEPTH_ID, true, false);
    }

    /** Post-Iris chromatic overlay RT for SPECIAL 3D items (shield/trident): the standardized special-item
     *  noise scale ({@link #CHROMATIC_SPECIAL_ITEM_UV_SCALE}, matching the in-phase {@link
     *  #forChromaticSpecialGlint} so the slick is identical on and off a pack) + the white dummy cutout (full
     *  model shape, like the glow part path). Flat/quad items use {@link #forItemGlintOverlay} instead. */
    public static RenderType forSpecialItemGlintOverlay(Data glint, int layerIdx) {
        return chromaticOverlayRT(glint.layers()[layerIdx], "special_ov", CHROMATIC_SPECIAL_ITEM_UV_SCALE, getWhiteTexture());
    }

    /** In-phase (off shader-pack) chromatic RT for a SPECIAL 3D item (shield/trident): the standardized
     *  special-item noise scale ({@link #CHROMATIC_SPECIAL_ITEM_UV_SCALE}), so the slick matches the post-Iris
     *  overlay ({@link #forSpecialItemGlintOverlay}) exactly. NO_LAYERING like the 3D item. 1.21.1 parity
     *  ({@code forChromaticSpecialGlint}). Replaces routing special chromatic through {@code forGlint}
     *  (isItem=false → the plain 1.0 3D scale that read as a coarse blob on a small item). */
    public static RenderType forChromaticSpecialGlint(Data glint, int layerIdx) {
        return chromaticRT(glint.layers()[layerIdx], LayeringTransform.NO_LAYERING, "special",
                CHROMATIC_SPECIAL_ITEM_UV_SCALE);
    }

    /** Post-Iris chromatic overlay RT for a flat/quad item, cut out against the quad's OWN sprite atlas.
     *  {@code atlas} must be the quad's {@code materialInfo().sprite().atlasLocation()} (item sprites can live
     *  on different atlases; binding a fixed atlas samples the wrong texels, so the slick either fills the
     *  whole quad or vanishes). Sampler0 = that atlas drives the cutout; the noise scale stays
     *  {@code atlasW/16}, matching {@link #forGlint}'s flat-item calibration. */
    public static RenderType forItemGlintOverlay(Data glint, int layerIdx, Identifier atlas) {
        ensureAtlasDims();
        // wing=true: LEQUAL against the item depth pre-pass, see drainChromaticOverlays.
        return chromaticOverlayRT(glint.layers()[layerIdx], "item_ov|" + atlas,
                cachedAtlasW / 16.0f * CHROMATIC_FLAT_ITEM_MATCH, atlas, SCENE_DEPTH_ID, true);
    }

    // ── Post-Iris NORMAL-glint overlay RenderTypes ───────────────────────────────────────────────
    //
    // The {@code forXxxGlintOverlayNormal} family is the {@code forXxxGlint} counterpart on {@link
    // GlintPipelines#GLINT_OVERLAY} (ALWAYS depth + an in-shader scene-depth occlusion test + a cutout
    // sampler). Same design + animation-matrix payload as the in-phase RT, so the look matches; it just
    // survives an active shader pack by being re-rendered after the framegraph (see
    // {@code EntityGlintRender.drainChromaticOverlays}). Colour rides the vertices (drain passes it as
    // tintedColor), so {@code colorIdx} here only phases the design's per-colour scroll, matching in-phase.

    /** Shared builder for a model-UV overlay RT (armor / entity body): grayscale design on Sampler0,
     *  {@code cutout} (the model texture, or the white dummy when null) on Sampler1, scene depth on
     *  DepthSampler, and the armor-style scroll matrix. */
    private static RenderType modelGlintOverlayRT(Layer layer, String tag, int colorIdx, @Nullable Identifier cutout) {
        return modelGlintOverlayRT(layer, tag, colorIdx, cutout, false, false);
    }

    /** @param loose true routes onto {@link GlintPipelines#GLINT_OVERLAY_LOOSE} (flat generous occlusion) for a
     *      translucent shell; false is the tight per-part-occluding {@link GlintPipelines#GLINT_OVERLAY}.
     *  @param wing true routes onto {@link GlintPipelines#GLINT_OVERLAY_WING} for thin double-sided equipment
     *      (elytra wings), so the additive glint doesn't double where the wings overlap. Mutually exclusive
     *      with {@code loose} (the translucent-shell path is never a thin wing). */
    private static RenderType modelGlintOverlayRT(Layer layer, String tag, int colorIdx, @Nullable Identifier cutout,
                                                  boolean loose, boolean wing) {
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final Identifier cut = cutout != null ? cutout : getWhiteTexture();
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = tag + "|" + (wing ? "wing|" : "") + layer.design() + "|" + cut + "|" + speed + "|" + ps + "|" + colorIdx
                + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_GLINT_OVERLAY.computeIfAbsent(key, k -> {
            String name = MOD_ID + ":custom_glint_overlay|" + k.hashCode();
            Supplier<Matrix4f> anim =
                    () -> GlintPipelines.armorAnimationMatrix(speed, ps, colorIdx, cc, scrollDir, scrollOffset);
            RenderType rt = loose
                    ? GlintPipelines.glintOverlayLooseType(name, gray, cut, SCENE_DEPTH_ID, anim)
                    : GlintPipelines.glintOverlayType(name, gray, cut, SCENE_DEPTH_ID, anim, wing);
            return rt;
        });
        return cached;
    }

    /** Post-Iris NORMAL-glint overlay RT for worn equipment (humanoid armor, elytra/cape, barding), the
     *  {@link #forArmorGlint} normal branch re-routed onto {@link GlintPipelines#GLINT_OVERLAY}.
     *  {@code texture} is the equipment-layer texture whose alpha cuts the glint to the real armor shape. */
    public static RenderType forArmorGlintOverlayNormal(Data glint, int layerIdx, int colorIdx, @Nullable Identifier texture) {
        return forArmorGlintOverlayNormal(glint, layerIdx, colorIdx, texture, false);
    }

    /** {@code wing=true} routes the elytra/cape (thin double-sided wings) onto the LEQUAL wing overlay pipeline
     *  so the additive glint doesn't double where the wings overlap; it pairs with the depth pre-pass the
     *  caller queues alongside it. */
    public static RenderType forArmorGlintOverlayNormal(Data glint, int layerIdx, int colorIdx, @Nullable Identifier texture, boolean wing) {
        return modelGlintOverlayRT(glint.layers()[layerIdx], "armor_ovn", colorIdx, texture, false, wing);
    }

    /** Post-Iris NORMAL-glint overlay RT for entity bodies, the {@link #forEntityGlint} normal branch
     *  re-routed onto {@link GlintPipelines#GLINT_OVERLAY}. {@code texture} is the body texture (its alpha
     *  cuts the glint to the entity shape); null draws the whole mesh. */
    public static RenderType forEntityGlintOverlayNormal(Data glint, int layerIdx, int colorIdx, @Nullable Identifier texture) {
        return modelGlintOverlayRT(glint.layers()[layerIdx], "entity_ovn", colorIdx, texture);
    }

    /** LOOSE-occlusion entity-layer overlay RT for a TRANSLUCENT shell (slime outer cube). Same design +
     *  animation as {@link #forEntityGlintOverlayNormal}, routed onto {@link GlintPipelines#GLINT_OVERLAY_LOOSE}
     *  so the shell's re-sorted committed depth doesn't self-occlude the glint (per-face dropout under Iris). */
    public static RenderType forEntityGlintOverlayNormalLoose(Data glint, int layerIdx, int colorIdx, @Nullable Identifier texture) {
        return modelGlintOverlayRT(glint.layers()[layerIdx], "entity_ovn_loose", colorIdx, texture, true, false);
    }

    /** Post-Iris NORMAL-glint overlay RT for SPECIAL 3D items (shield/trident): the 3D scale-1 item scroll
     *  (matching the in-phase special-item path, {@link #forGlint} isItem=false) + the white dummy cutout
     *  (full model shape, like the glow part path). Flat/quad items use {@link #forItemGlintOverlayNormal}
     *  (per-quad atlas cutout). */
    public static RenderType forSpecialItemGlintOverlayNormal(Data glint, int layerIdx, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = "special_ovn|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx + "|" + cc
                + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_GLINT_OVERLAY.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintOverlayType(MOD_ID + ":custom_glint_overlay|" + k.hashCode(),
                    gray, getWhiteTexture(), SCENE_DEPTH_ID,
                    () -> GlintPipelines.itemAnimationMatrix(speed, 1.0f, 1.0f, ps, colorIdx, cc, scrollDir, scrollOffset));
            return rt;
        });
        return cached;
    }

    /** Post-Iris NORMAL-glint overlay RT for a flat/quad item, cut out against the quad's OWN sprite atlas.
     *  {@code atlas} must be the quad's {@code materialInfo().sprite().atlasLocation()}. The design scroll
     *  uses the same atlas-calibrated {@code scaleU/scaleV} as {@link #forGlint}'s flat-item path, so the
     *  overlay density matches the in-phase look. */
    public static RenderType forItemGlintOverlayNormal(Data glint, int layerIdx, int colorIdx, Identifier atlas) {
        Layer layer = glint.layers()[layerIdx];
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        ensureAtlasDims();
        final int atlasW = cachedAtlasW, atlasH = cachedAtlasH;
        final float scaleU = 8.0f * atlasW / 1024.0f;
        final float scaleV = 8.0f * atlasH / 512.0f;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = "item_ovn|" + atlas + "|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx
                + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_GLINT_OVERLAY.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintOverlayType(MOD_ID + ":custom_glint_overlay|" + k.hashCode(),
                    gray, atlas, SCENE_DEPTH_ID,
                    () -> GlintPipelines.itemAnimationMatrix(speed, scaleU, scaleV, ps, colorIdx, cc, scrollDir, scrollOffset),
                    true);   // wing pipeline: LEQUAL against the item depth pre-pass, see drainChromaticOverlays
            return rt;
        });
        return cached;
    }

    /** Composites the isolated chromatic-overlay target ({@code inView}) onto the main target
     *  ({@code outView}) with the GLINT blend, the chromatic analog of {@link #upscaleGlowRing}. Called by
     *  {@code EntityGlintRender.drainChromaticOverlays} after the slick is rendered into its own target. */
    public static void compositeChromatic(GpuTextureView inView, GpuTextureView outView) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "customglint chromatic composite", outView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(GlintPipelines.CHROMATIC_COMPOSITE_PIPE));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("InSampler", inView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
    }

    // ── Render types ──────────────────────────────────────────────────────────

    private static final Map<String, RenderType> BY_GLINT             = newRtCache();
    private static final Map<String, RenderType> BY_ARMOR_GLINT        = newRtCache();
    private static final Map<String, RenderType> BY_ENTITY_BODY_GLINT  = newRtCache();
    private static final Map<String, RenderType> BY_BLOCK_GLINT        = newRtCache();
    private static final Map<String, RenderType> BY_CHROMATIC          = newRtCache();
    private static final Map<String, RenderType> BY_CHROMATIC_OVERLAY  = newRtCache();
    private static final Map<String, RenderType> BY_GLINT_OVERLAY      = newRtCache();
    private static final Map<String, RenderType> BY_ENTITY_LAYER_GLINT = newRtCache();

    /** Lazily resolves the block-atlas dimensions feeding the item/block glint scale. Atlas size only
     *  changes on resource reload, so this runs once until {@link #clearTextures} resets the cache. */
    private static void ensureAtlasDims() {
        if (cachedAtlasW != 0) return;
        cachedAtlasW = 2048; cachedAtlasH = 2048; // 26.1 default block-atlas size, used if the atlas isn't stitched yet
        if (Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)
                instanceof TextureAtlas atlas) {
            cachedAtlasW = atlas.width;
            cachedAtlasH = atlas.height;
        }
    }

    /**
     * Glint for a block-model entity layer (mooshroom mushrooms, snow-golem pumpkin). Mirrors
     * {@link #forGlint}'s flat-item path (block parts carry block-atlas UVs, so the same atlas-calibrated
     * scale applies), but builds on {@link GlintPipelines#GLINT_BLOCK} (LEQUAL depth) + VIEW_OFFSET_Z. The
     * block is drawn by a DIFFERENT pipeline, so EQUAL would flicker against its depth; LEQUAL + a toward-camera
     * bias sits the glint just in front instead. Emitted via {@code submitCustomGeometry} by
     * {@link EntityGlintRender#submitBlockLayerGlintGlow}.
     */
    public static RenderType forBlockGlint(Data glint, int layerIdx, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        ensureAtlasDims();
        final int atlasW = cachedAtlasW, atlasH = cachedAtlasH;
        if (CustomGlint.isChromatic(layer))
            return chromaticRT(layer, LayeringTransform.VIEW_OFFSET_Z_LAYERING, "block", atlasW / 16.0f, true);
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final float scaleU = 8.0f * atlasW / 1024.0f;
        final float scaleV = 8.0f * atlasH / 512.0f;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = "block|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx + "|" + layerIdx
                + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_BLOCK_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.blockGlintType(MOD_ID + ":custom_block_glint|" + k.hashCode(),
                    gray, TextureAtlas.LOCATION_BLOCKS, LayeringTransform.VIEW_OFFSET_Z_LAYERING,
                    () -> GlintPipelines.itemAnimationMatrix(speed, scaleU, scaleV, ps, colorIdx, cc, scrollDir, scrollOffset));
            return rt;
        });
        return cached;
    }

    // Armor glint matches armor_cutout_no_cull's VIEW_OFFSET_Z layering (EQUAL depth, D-ε) so the
    // glint depth-test lands exactly on the armor depth. See the project_armor_glint_bleed_fix memory.
    // Elytra/capes need no special casing here: the EQUAL depth test already lands only on the nearest
    // surface the equipment draw wrote, so the wing's far face never passes and the additive glint can't
    // double. (Culling them was tried and is wrong, see the mirrored-wing note on
    // GlintPipelines.CHROMATIC_OVERLAY_WING.) Only the post-shader overlay path, which has no such depth
    // test, needs the wing pre-pass.
    public static RenderType forArmorGlint(Data glint, int layerIdx, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        // Armor samples its own 0..1 texture UV spanning a large area, so use a SMALL noise scale
        // (CHROMATIC_MODEL_UV_SCALE) to read as an oil-slick rather than a tiny tiled grid.
        if (CustomGlint.isChromatic(layer)) return chromaticRT(layer, LayeringTransform.VIEW_OFFSET_Z_LAYERING, "armor", CHROMATIC_MODEL_UV_SCALE);
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = "armor|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_ARMOR_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintType(MOD_ID + ":custom_armor_glint|" + k.hashCode(),
                    GlintPipelines.GLINT_COLOR, gray,
                    LayeringTransform.VIEW_OFFSET_Z_LAYERING,
                    () -> GlintPipelines.armorAnimationMatrix(speed, ps, colorIdx, cc, scrollDir, scrollOffset));
            return rt;
        });
        return cached;
    }

    /**
     * Entity body glint (pigs, cows, zombies, … anything rendered through entityCutoutNoCull).
     * Aliases {@link #forEntityBodyGlint}; separate method only for caller clarity.
     */
    public static RenderType forEntityGlint(Data glint, int layerIdx, int colorIdx) {
        return forEntityBodyGlint(glint, layerIdx, colorIdx);
    }

    // Entity bodies render through entityCutoutNoCull (no polygon offset / no VIEW_OFFSET_Z_LAYERING).
    // forArmorGlint uses EQUAL + VIEW_OFFSET_Z_LAYERING, wrong offset → invisible on a body draw.
    // This variant keeps EQUAL + NO_LAYERING so depth matches, and scale 1.0 matches forArmorGlint visually.
    // (Named for horse armor in 1.20.1/1.21.1, where it backed barding; in 26.1 barding routes through the
    // unified EquipmentLayerRenderer → forArmorGlint; this method now backs the entity-body path and the layer chromatic fallbacks.)
    public static RenderType forEntityBodyGlint(Data glint, int layerIdx, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        // Entity bodies sample their own 0..1 model UV, same small chromatic scale as armor.
        if (CustomGlint.isChromatic(layer)) return chromaticRT(layer, LayeringTransform.NO_LAYERING, "entitybody", CHROMATIC_MODEL_UV_SCALE);
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = "entitybody|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx + "|" + layerIdx + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_ENTITY_BODY_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintType(MOD_ID + ":custom_entity_body_glint|" + k.hashCode(), gray,
                    LayeringTransform.NO_LAYERING,
                    () -> GlintPipelines.armorAnimationMatrix(speed, ps, colorIdx, cc, scrollDir, scrollOffset));
            return rt;
        });
        return cached;
    }

    /**
     * Entity LAYER glint (sheep wool, slime outer cube, saddle, stray clothing, any {@code RenderLayer}
     * surface caught by {@code SubmitNodeCollectionMixin}). Same design + scroll as {@link #forEntityBodyGlint}
     * but on {@link GlintPipelines#GLINT_LEQUAL} + a toward-camera {@code VIEW_OFFSET_Z} nudge instead of the
     * body's EQUAL + NO_LAYERING. Layer surfaces are drawn by a different pipeline than our glint and sit
     * flush on / translucent over the base body, so EQUAL flickers on the ~1 ULP raster mismatch between the
     * two draws (the reported sheep-wool / slime shimmer); LEQUAL is deterministic and the nudge sits the
     * glint just in front of the layer. The base body stays on {@link #forEntityBodyGlint} (EQUAL), where it
     * rasterises identically to its own draw and so is stable already.
     */
    public static RenderType forEntityLayerGlint(Data glint, int layerIdx, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        // Chromatic layers keep the body's chromatic RT (NO_LAYERING); the flicker fix is for the design path.
        if (CustomGlint.isChromatic(layer)) return forEntityBodyGlint(glint, layerIdx, colorIdx);
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = "entitylayer|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx + "|" + layerIdx
                + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_ENTITY_LAYER_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintType(MOD_ID + ":custom_entity_layer_glint|" + k.hashCode(),
                    GlintPipelines.GLINT_LEQUAL, gray, LayeringTransform.VIEW_OFFSET_Z_LAYERING,
                    () -> GlintPipelines.armorAnimationMatrix(speed, ps, colorIdx, cc, scrollDir, scrollOffset));
            return rt;
        });
        return cached;
    }

    /**
     * TRANSLUCENT entity-layer glint (slime outer shell). Reuses the {@link GlintPipelines#GLINT_OVERLAY}
     * pipeline (ALWAYS depth so it draws ON TOP of the translucent shell, additive GLINT blend) with an
     * in-shader occlusion test against the STABLE opaque-depth snapshot ({@link #SOLID_DEPTH_ID}) instead of
     * the shell's re-sorted depth. Drawn on top after the main pass by {@code
     * EntityGlintRender.drainTranslucentLayerGlints}; white dummy cutout (the shell geometry IS the shape).
     * Chromatic layers fall back to the body chromatic RT (rare combo).
     */
    public static RenderType forEntityTranslucentLayerGlint(Data glint, int layerIdx, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        if (CustomGlint.isChromatic(layer)) return forEntityBodyGlint(glint, layerIdx, colorIdx);
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = "translayer|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx + "|" + layerIdx
                + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_GLINT_OVERLAY.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintOverlayType(MOD_ID + ":custom_translayer_glint|" + k.hashCode(),
                    gray, getWhiteTexture(), SOLID_DEPTH_ID,
                    () -> GlintPipelines.armorAnimationMatrix(speed, ps, colorIdx, cc, scrollDir, scrollOffset));
            return rt;
        });
        return cached;
    }

    public static RenderType forGlint(Data glint, int layerIdx, boolean isItem, int colorIdx) {
        // isItem=true → flat item model → atlas-calibrated 8× scale (matches vanilla glint()).
        // isItem=false → 3D entity model (trident, etc.) → 1.0 for visible pattern detail.
        // 26.1: ModelManager.getAtlas is gone (atlas lives on the private AtlasManager). The block
        // atlas is registered in TextureManager under LOCATION_BLOCKS; read its dims there.
        // Pattern density across a sprite = scale * (spritePx / atlasDim), so scale must track the
        // atlas dimension to keep density (and thus the on-screen look) constant as the atlas grows.
        // The asymmetric 1024/512 denominators are the empirically-square 1.20.1/1.21.1 calibration:
        // they yield density_U = spriteW/128, density_V = spriteH/64, the proven values. On the larger
        // 2048² atlas this resolves to scaleU=16, scaleV=32, the bigger numbers just offset the bigger
        // atlas; the density (16*16/2048 = 16/64) is identical to the 1.21.1 atlas (8*16/512 = 16/64).
        // A previous port collapsed both to /2048 thinking the larger V scale was "too dense"; that
        // actually quartered V density and squared the designs. Do NOT re-symmetrize these.
        ensureAtlasDims();
        final int atlasW = cachedAtlasW, atlasH = cachedAtlasH;
        final float scaleU = isItem ? (8.0f * atlasW / 1024.0f) : 1.0f;
        final float scaleV = isItem ? (8.0f * atlasH / 512.0f) : 1.0f;
        Layer layer = glint.layers()[layerIdx];
        if (CustomGlint.isChromatic(layer)) {
            // Flat items sample the block atlas (UV0 spans only spritePx/atlasPx) → scale up toward the GUI
            // density, then × CHROMATIC_FLAT_ITEM_MATCH to land on worn-armor's cell scale; 3D items (trident)
            // sample their own 0..1 model UV → scale 1.
            float uvScale = isItem ? (atlasW / 16.0f) * CHROMATIC_FLAT_ITEM_MATCH : 1.0f;
            return chromaticRT(layer, LayeringTransform.NO_LAYERING, "item|" + isItem, uvScale);
        }
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = layer.design() + "|" + speed + "|" + isItem + "|" + ps + "|" + colorIdx + "|" + layerIdx
                + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintType(MOD_ID + ":custom_glint|" + k.hashCode(), gray,
                    LayeringTransform.NO_LAYERING,
                    () -> GlintPipelines.itemAnimationMatrix(speed, scaleU, scaleV, ps, colorIdx, cc, scrollDir, scrollOffset));
            return rt;
        });
        return cached;
    }

    public static int computeAnimatedColor(Data glint, int layerIdx) {
        return computeAnimatedColor(glint, layerIdx, 0.0f);
    }

    /** As {@link #computeAnimatedColor(Data,int)} but shifts the colour loop by {@code phaseFraction} of a
     *  full cycle (0.5 = half a loop), so the glow outline ring can run out of phase with the surface tint
     *  (see {@link #GLOW_RING_PHASE_OFFSET}). */
    public static int computeAnimatedColor(Data glint, int layerIdx, float phaseFraction) {
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        return packAdjustedColor(glint, layerIdx, computeAnimatedColorAt(glint, layerIdx, gameTime, phaseFraction));
    }

    /** GUI variant of {@link #computeAnimatedColor}: animates off wall-clock ticks ({@code Util.getMillis()/50})
     *  so a multi-color cycle keeps moving on GUI icons shown with no level loaded (main menu, off-world
     *  inventory screens), where {@code getGameTime()} is pinned to 0 and the world variant would freeze on
     *  color 0. The world path stays on game time so it still pauses with the game. */
    public static int computeAnimatedColorGui(Data glint, int layerIdx) {
        return computeAnimatedColorAt(glint, layerIdx, Util.getMillis() / 50L, 0.0f);
    }

    private static int computeAnimatedColorAt(Data glint, int layerIdx, long gameTime, float phaseFraction) {
        Layer[] layers = glint.layers();
        // A decoded Data may legally hold zero layers ({"layers":[]} via give-NBT / datapack / crafted
        // packet, Data.CODEC sets no minimum). Call sites that pass a fixed index 0 only null-check, so
        // guard here against an AIOOBE on the render thread.
        if (layerIdx < 0 || layerIdx >= layers.length) return 0xFFFFFFFF;
        Layer layer = layers[layerIdx];
        int[] colors = layer.colors();
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
        float totalTicks = (20.0f * colors.length) / layer.speed();
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        // Shift by phaseFraction of the loop and wrap back into [0, length).
        t = (((t + phaseFraction * colors.length) % colors.length) + colors.length) % colors.length;
        int idx = (int) t % colors.length;
        if (!layer.interpolate()) return colors[idx];
        float frac = t - (int) t;
        return lerpArgb(colors[idx], colors[(idx + 1) % colors.length], frac);
    }

    /** Per-channel linear blend between two ARGB colours. {@code frac} in [0,1] weights {@code c2}. */
    private static int lerpArgb(int c1, int c2, float frac) {
        int a = (int)(((c1 >> 24) & 0xFF) * (1 - frac) + ((c2 >> 24) & 0xFF) * frac);
        int r = (int)(((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
        int g = (int)(((c1 >>  8) & 0xFF) * (1 - frac) + ((c2 >>  8) & 0xFF) * frac);
        int b = (int)((c1         & 0xFF) * (1 - frac) + (c2         & 0xFF) * frac);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Animates through an int[] color array using game time. Default speed=1, interpolate=true. */
    public static int computeAnimatedGlowColor(int[] colors) {
        return computeAnimatedGlowColor(colors, 1.0f, true);
    }

    /** As above at a chosen {@code speed} (a higher speed cycles faster, mirroring the glint layer speed) and
     *  either blending between colors ({@code interpolate}) or stepping hard between them. */
    public static int computeAnimatedGlowColor(int[] colors, float speed, boolean interpolate) {
        return computeAnimatedGlowColor(colors, speed, interpolate, 0.0f);
    }

    /** Phase offset (fraction of a full colour loop) applied to the glow OUTLINE ring so it never shows the
     *  same colour as the item's surface/edge tint at the same instant. Half a cycle = maximum contrast:
     *  when the edge is on one colour, the ring sits half a step behind it. The surface tint
     *  ({@link net.tunamods.customglint.module.client.GlowTintSource}) animates at offset 0; the ring
     *  ({@code GuiRendererMixin} halo, {@code EntityGlintRender} outline) at this offset. */
    public static final float GLOW_RING_PHASE_OFFSET = 0.5f;

    /** As {@link #computeAnimatedGlowColor(int[],float,boolean)} but shifts the loop by {@code phaseFraction}
     *  of a full cycle (0.5 = half a loop), for the out-of-phase glow ring. */
    public static int computeAnimatedGlowColor(int[] colors, float speed, boolean interpolate, float phaseFraction) {
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        return computeAnimatedGlowColorAt(colors, gameTime, speed, interpolate, phaseFraction);
    }

    /** GUI variant of {@link #computeAnimatedGlowColor}: wall-clock ticks so a multi-color glow halo keeps
     *  animating on off-world GUI icons (see {@link #computeAnimatedColorGui}). */
    public static int computeAnimatedGlowColorGui(int[] colors) {
        return computeAnimatedGlowColorGui(colors, 1.0f, true);
    }

    public static int computeAnimatedGlowColorGui(int[] colors, float speed, boolean interpolate) {
        return computeAnimatedGlowColorAt(colors, Util.getMillis() / 50L, speed, interpolate, 0.0f);
    }

    private static int computeAnimatedGlowColorAt(int[] colors, long gameTime, float speed, boolean interpolate, float phaseFraction) {
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
        if (!Float.isFinite(speed) || speed <= 0) speed = 1.0f;
        float totalTicks = (20.0f * colors.length) / speed;
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        // Shift by phaseFraction of the loop and wrap back into [0, length).
        t = (((t + phaseFraction * colors.length) % colors.length) + colors.length) % colors.length;
        int idx = (int) t % colors.length;
        if (!interpolate) return colors[idx];
        float frac = t - (int) t;
        return lerpArgb(colors[idx], colors[(idx + 1) % colors.length], frac);
    }

    /** Reload hooks appended by compat modules; invoked by {@link #clearTextures()} so each
     *  compat can release its own {@code DynamicTexture}s without {@code CustomGlintRenderer}
     *  needing to know about them. */
    public static final List<Runnable> additionalReloadCleanup = new CopyOnWriteArrayList<>();

    private static String texTag(Identifier t) {
        return t.getNamespace() + "_" + t.getPath().replace('/', '_');
    }

    /**
     * Shared base for the mod's wrapping {@link VertexConsumer}s. 26.1.2 added two abstract methods to
     * the interface that every wrapper would otherwise have to implement: the single-arg
     * {@code setColor(int packedARGB)} and {@code setLineWidth(float)}. This base implements both once.
     * {@code setColor(int)} unpacks to the subclass's own {@code setColor(r,g,b,a)}, so each wrapper's
     * color policy (no-op, force-override, pass-through) is honored, and {@code setLineWidth} is a
     * no-op (these wrappers never drive line geometry).
     */
    private abstract static class WrappingConsumer implements VertexConsumer {
        @Override public VertexConsumer setColor(int argb) {
            return setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
        }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    /** Wraps a VertexConsumer and overrides vertex colors with a fixed RGBA value, forwarding
     *  uv/overlay/uv2/normal to the wrapped buffer. Forwarding those is required when the underlying
     *  buffer uses a full entity vertex format (POSITION_COLOR_TEX_OVERLAY_LIGHTMAP_NORMAL); dropping
     *  them leaves the BufferBuilder with unfilled elements and crashes on endVertex. This is the color
     *  injector for the glint layers (ItemRendererMixin) and the glow-mask silhouettes. */
    public static final class FullColorOverrideConsumer extends WrappingConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        public FullColorOverrideConsumer(VertexConsumer wrapped, int r, int g, int b, int a) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) { wrapped.addVertex(x, y, z); return this; }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) { wrapped.addVertex(m, x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return wrapped.setColor(this.r, this.g, this.b, this.a); }
        @Override public VertexConsumer setColor(float r, float g, float b, float a) { return wrapped.setColor(this.r, this.g, this.b, this.a); }
        @Override public VertexConsumer setUv(float u, float v) { return wrapped.setUv(u, v); }
        @Override public VertexConsumer setUv1(int u, int v) { return wrapped.setUv1(u, v); }
        @Override public VertexConsumer setUv2(int u, int v) { return wrapped.setUv2(u, v); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return wrapped.setNormal(x, y, z); }
        @Override public VertexConsumer setUv3(float u, float v) { return wrapped.setUv3(u, v); }
    }

    /** Writes every vertex to two consumers. The body-glow tee: one model walk feeds both the body's own
     *  buffer and our glow mask (26.2 dropped {@code VertexMultiConsumer}). */
    public static final class TeeConsumer extends WrappingConsumer {
        private final VertexConsumer a, b;
        public TeeConsumer(VertexConsumer a, VertexConsumer b) { this.a = a; this.b = b; }
        @Override public VertexConsumer addVertex(float x, float y, float z) { a.addVertex(x, y, z); b.addVertex(x, y, z); return this; }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) { a.addVertex(m, x, y, z); b.addVertex(m, x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int bl, int al) { a.setColor(r, g, bl, al); b.setColor(r, g, bl, al); return this; }
        @Override public VertexConsumer setColor(float r, float g, float bl, float al) { a.setColor(r, g, bl, al); b.setColor(r, g, bl, al); return this; }
        @Override public VertexConsumer setUv(float u, float v) { a.setUv(u, v); b.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { a.setUv1(u, v); b.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { a.setUv2(u, v); b.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { a.setNormal(x, y, z); b.setNormal(x, y, z); return this; }
        @Override public VertexConsumer setUv3(float u, float v) { a.setUv3(u, v); b.setUv3(u, v); return this; }
    }

    /** Wraps a VertexConsumer and records each vertex's eye-space position into a shared
     *  {minX,minY,minZ,maxX,maxY,maxZ} accumulator. Feeds {@code glowMaskBox}, the per-object screen-space
     *  bounds the composite pass scissors to so each glow ring only touches its own region. */
    public static final class AABBTrackingConsumer extends WrappingConsumer {
        private final VertexConsumer wrapped;
        private final float[] minMax;
        public AABBTrackingConsumer(VertexConsumer wrapped, float[] minMax) {
            this.wrapped = wrapped; this.minMax = minMax;
        }
        private void track(float x, float y, float z) {
            if (x < minMax[0]) minMax[0] = x;
            if (y < minMax[1]) minMax[1] = y;
            if (z < minMax[2]) minMax[2] = z;
            if (x > minMax[3]) minMax[3] = x;
            if (y > minMax[4]) minMax[4] = y;
            if (z > minMax[5]) minMax[5] = z;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            track(x, y, z);
            wrapped.addVertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) {
            float tx = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
            float ty = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
            float tz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
            track(tx, ty, tz);
            wrapped.addVertex(m, x, y, z);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return wrapped.setColor(r, g, b, a); }
        @Override public VertexConsumer setColor(float r, float g, float b, float a) { return wrapped.setColor(r, g, b, a); }
        @Override public VertexConsumer setUv(float u, float v) { return wrapped.setUv(u, v); }
        @Override public VertexConsumer setUv1(int u, int v) { return wrapped.setUv1(u, v); }
        @Override public VertexConsumer setUv2(int u, int v) { return wrapped.setUv2(u, v); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return wrapped.setNormal(x, y, z); }
        @Override public VertexConsumer setUv3(float u, float v) { return wrapped.setUv3(u, v); }
    }

    // ── Shader-mod detection ──────────────────────────────────────────────────
    // Reflective (via IrisApi) so we don't need a compileOnly dep on Iris. The three Method handles are
    // resolved once and cached. Used as a timing switch: under an active pack the glow drain happens mid-framegraph
    // (where Iris hijacks our framebuffer into its gbuffer → black screen), so the drain is relocated to
    // LevelRenderer.render TAIL (post-Iris) and LevelRendererMixin's after-main-pass drain
    // skips itself here. The actual pipeline→program wiring under a pack is handled by IrisCompat, not here.
    private static volatile boolean SHADER_LOOKUP_DONE = false;
    private static volatile Method SHADER_GET_INSTANCE = null;
    private static volatile Method SHADER_IS_IN_USE = null;
    private static volatile Method SHADER_IS_SHADOW_PASS = null;

    /** Per-frame cache of {@link #computeShaderPackActive()}. A shader pack can't toggle mid-frame, and the
     *  reflective {@code isShaderPackInUse} probe is hit many times per frame (per equipment layer per wearer,
     *  every chromatic gate, every drain), so resolve it once in {@code RenderFrameEvent.Pre}. */
    private static volatile boolean frameShaderActive;

    /** Refreshes {@link #frameShaderActive}; called once per frame from {@code CustomGlintClientInit}. */
    public static void refreshFrameShaderActive() { frameShaderActive = computeShaderPackActive(); }

    public static boolean isShaderPackActive() { return frameShaderActive; }

    private static boolean computeShaderPackActive() {
        if (!SHADER_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_LOOKUP_DONE) {
                    try {
                        // Iris ships the same API package on both loaders, do not change this path.
                        Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                        SHADER_GET_INSTANCE = api.getMethod("getInstance");
                        SHADER_IS_IN_USE = api.getMethod("isShaderPackInUse");
                        SHADER_IS_SHADOW_PASS = api.getMethod("isRenderingShadowPass");
                    } catch (Throwable ignored) {
                        SHADER_GET_INSTANCE = null;
                        SHADER_IS_IN_USE = null;
                        SHADER_IS_SHADOW_PASS = null;
                    }
                    SHADER_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADER_IS_IN_USE == null) return false;
        try {
            Object inst = SHADER_GET_INSTANCE.invoke(null);
            return (Boolean) SHADER_IS_IN_USE.invoke(inst);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether Iris is CURRENTLY rendering its shadow map. Unlike {@link #isShaderPackActive()} this is NOT
     * frame-cached: the shadow pass is a sub-phase of a frame that toggles on and off mid-frame, so it must
     * be probed live. Under a pack Iris re-renders every entity + its worn equipment into the shadow map
     * through the same {@code ModelFeatureRenderer.prepareModel} path the camera pass uses, so our in-phase
     * silhouette tee ({@link #fanBodyGlow}/{@link #fanLayerGlow}) and the armor/item outline queues would
     * capture a SECOND copy posed for the light's viewpoint. The world drain then flushes it against the
     * camera view, producing a detached duplicate outline that slides as the camera turns. Every capture /
     * queue entry point bails when this is true. Reflective (no compileOnly Iris dep); false without Iris.
     */
    public static boolean isInShadowPass() {
        // Reuse the shader-detection lookup (resolves SHADER_IS_SHADOW_PASS in the same block).
        if (!frameShaderActive) return false;   // no pack ⇒ no shadow pass; also skips the reflection cost.
        if (SHADER_IS_SHADOW_PASS == null || SHADER_GET_INSTANCE == null) return false;
        try {
            Object inst = SHADER_GET_INSTANCE.invoke(null);
            return (Boolean) SHADER_IS_SHADOW_PASS.invoke(inst);
        } catch (Throwable t) {
            return false;
        }
    }

}
