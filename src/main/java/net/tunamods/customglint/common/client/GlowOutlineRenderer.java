package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Post-process glow outline backend (1.21.1). Captures glowing items' silhouettes into an offscreen
 * mask target, then runs one fullscreen id-aware dilation composite that paints a ring over the scene.
 * Client-only; reached from {@link CustomGlintClientInit}. The approach is ported from the 26.1 branch
 * (silhouette mask + {@code glow_outline_id} composite) onto 1.21.1 primitives: {@link ShaderInstance}
 * core shaders, a {@link TextureTarget} mask, and a manual {@link Tesselator} fullscreen blit.
 *
 * <p>Coverage: items in the world (third-person held, dropped, item frames, other players) and glowing
 * entities, drained at {@code RenderLevelStageEvent.AFTER_WEATHER} where the live world projection /
 * modelview match what they were drawn with; the first-person held item drained at the RETURN of
 * {@code ItemInHandRenderer.renderHandsWithItems} (see {@link #drainHeldFp()}) under the hand-FOV
 * projection; GUI / inventory / HUD icons drained per item (see {@link #drainGui()}); plus worn armor,
 * the elytra, and special BEWLR items (trident/shield) traced against their own textures. Under an active
 * shader pack the world drain splits into accumulate-now (see {@link #accumulateWorld()}) and
 * composite-at-{@code renderLevel}-TAIL (see {@link #compositeWorld()}).
 *
 * <p><b>Occlusion</b>: the silhouette pass occludes against the scene by binding the main depth texture to
 * sampler unit 1 (safe, because that pass writes the offscreen MASK, not the main target) and discards
 * silhouette fragments behind world geometry, so a world item behind a wall doesn't ring. The remaining
 * deferred case is composite-stage <i>through-wall</i> occlusion. The composite cannot sample the main
 * target's depth while also writing its colour (reading an attachment of the bound framebuffer is undefined
 * and returns garbage on some drivers / under Sodium, which crackled the ring), so it would need a
 * format-matched depth COPY (the main target is DEPTH+STENCIL, so a plain-DEPTH blit fails). The ring is a
 * fixed per-category thickness.
 */
public final class GlowOutlineRenderer extends RenderStateShard {

    private static final Logger LOGGER = LogUtils.getLogger();

    private GlowOutlineRenderer() { super("", () -> {}, () -> {}); }

    // ── Outline categories + per-object id keys ────────────────────────────────
    // key = (category << 5) | id : the low 5 bits are a running 1..31 id, the bits above them the category
    // (so a key never exceeds 127 and fits the vertex-colour alpha byte). Stamped into the silhouette's
    // vertex-colour alpha so the composite can keep each object's ring separate and pick a per-category
    // thickness (see glow_composite.fsh THICKNESS[]).
    public static final int CAT_ENTITY = 0, CAT_ARMOR = 1, CAT_ITEM = 2, CAT_HELD_FP = 3;

    // Per-category ring thickness in texels. MUST mirror glow_composite.fsh THICKNESS[]. Drives the
    // composite kernel radius and the per-job scissor pad for whichever categories a single drain contains.
    private static final int[] CAT_THICKNESS = { 4, 4, 3, 7 };

    private static int glowIdCounter = 0;

    private static int nextGlowId() { glowIdCounter = (glowIdCounter % 31) + 1; return glowIdCounter; }

    public static int nextGlowKey(int category) { return (category << 5) | nextGlowId(); }

    // ── Shaders ────────────────────────────────────────────────────────────────

    private static ShaderInstance silhouetteShader;
    private static ShaderInstance compositeShader;

    // Composite uniforms, resolved ONCE per shader load instead of looked up by string ~7x per composite
    // pass. The drain runs one pass per outline id, so with many glowing entities/icons on screen this is a
    // per-frame hot path. Re-resolved whenever compositeShader is reassigned (registerShaders on reload).
    private static Uniform uSearchRadius, uThicknessScale, uProjA, uProjB,
            uTargetId, uGuiMode, uEdgeBleed;
    // Silhouette shader: per-block distance scaling of the scene-occlusion bias (0 off-pack).
    private static Uniform uSilBiasScale;

    /** Mod-event-bus listener; registered from {@link CustomGlintClientInit#run}.
     *  NOTE: the {@code "vertex"}/{@code "fragment"} program names INSIDE each core-shader JSON must be
     *  namespaced (e.g. {@code "customglint:glow_silhouette"}). They are parsed with
     *  {@code ResourceLocation.parse}, which defaults a bare name to the {@code minecraft} namespace and
     *  then fails to find {@code minecraft:shaders/core/glow_silhouette.vsh}. */
    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), CustomGlint.res("glow_silhouette"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> {
                        silhouetteShader = shader;
                        uSilBiasScale = shader.getUniform("OcclusionBiasScale");
                    });
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), CustomGlint.res("glow_composite"),
                            DefaultVertexFormat.POSITION_TEX),
                    shader -> {
                        compositeShader = shader;
                        uSearchRadius   = shader.getUniform("SearchRadius");
                        uThicknessScale = shader.getUniform("ThicknessScale");
                        uProjA          = shader.getUniform("ProjA");
                        uProjB          = shader.getUniform("ProjB");
                        uTargetId       = shader.getUniform("TargetId");
                        uGuiMode        = shader.getUniform("GuiMode");
                        uEdgeBleed      = shader.getUniform("EdgeBleed");
                    });
        } catch (Exception e) {
            LOGGER.error("[customglint] failed to register glow-outline shaders", e);
        }
    }

    // ── Offscreen mask target ──────────────────────────────────────────────────

    private static TextureTarget maskTarget;
    // Dedicated mask for the deferred under-shader-pack world drain. The world silhouettes are accumulated at
    // AFTER_WEATHER but composited at renderLevel TAIL; in between, the first-person hand drain clears + rewrites
    // the SHARED maskTarget, so the deferred world composite needs its own untouched mask (else it would
    // composite the hand's silhouette at the world's box positions, a doubled, floating ring).
    private static TextureTarget worldDeferredMask;

    private static void ensureTarget(int width, int height) {
        maskTarget = ensureTarget(maskTarget, width, height);
    }

    /** Create or resize a NEAREST RGBA+depth mask target. useDepth=true: the silhouette pass depth-tests
     *  (LEQUAL) against the mask's OWN depth so only the front-most fragment per pixel writes; without it the
     *  model's NO_CULL back faces (always "behind" the front face that's in the scene depth) would overwrite
     *  the front face's occlusion verdict and punch holes. The OCCLUSION test itself samples the MAIN depth
     *  texture (Sampler1, bound at drain), safe because the silhouette writes the MASK, not the main target.
     *  Fully recreate on size change rather than resize(): a window resize otherwise left the depth
     *  attachment in a state where the silhouette's LEQUAL test silently dropped every fragment. */
    private static TextureTarget ensureTarget(TextureTarget t, int width, int height) {
        if (t != null && (t.width != width || t.height != height)) {
            t.destroyBuffers();
            t = null;
        }
        if (t == null) {
            t = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            t.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            t.setFilterMode(GL11.GL_NEAREST);
        }
        return t;
    }

    /** Released on resource reload (registered via CustomGlintRenderer.additionalReloadCleanup). */
    public static void release() {
        if (maskTarget != null) { maskTarget.destroyBuffers(); maskTarget = null; }
        if (worldDeferredMask != null) { worldDeferredMask.destroyBuffers(); worldDeferredMask = null; }
        if (chromaticTarget != null) { chromaticTarget.destroyBuffers(); chromaticTarget = null; }
        texSilhouetteRTs.clear();
        texSilhouetteTriRTs.clear();
        rtTextureCache.clear();
    }

    // ── Silhouette RenderType ──────────────────────────────────────────────────
    // NEW_ENTITY so VertexConsumer.putBulkData writes naturally; the silhouette shader reads only
    // Position/Color/UV0. Sampler0 = the block atlas (item shape via alpha). No depth test/write and no
    // blend: the alpha channel is a packed classifier, occlusion is done in-shader against the scene
    // depth (Sampler1, bound by the drain just before the flush).

    /** Builds a silhouette RenderType bound to {@code tex}: NO_CULL solid union, LEQUAL depth (front-most
     *  fragment wins), no blend; the shader alpha-discards against {@code tex} so the mask traces the real
     *  shape. The name must be unique per texture (RenderType identity includes it). */
    private static RenderType buildSilhouetteRT(String name, ResourceLocation tex) {
        return buildSilhouetteRT(name, tex, VertexFormat.Mode.QUADS);
    }

    // {@code mode} lets a caller build a TRIANGLES-mode silhouette RT for a silhouette captured from a
    // triangle-list draw (Epic Fight patched-entity meshes). A QUADS RT fed triangle-list vertices
    // reassembles them wrong and the mask shatters.
    private static RenderType buildSilhouetteRT(String name, ResourceLocation tex, VertexFormat.Mode mode) {
        return RenderType.create(
                name,
                DefaultVertexFormat.NEW_ENTITY,
                mode,
                1024,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(() -> silhouetteShader))
                        .setTextureState(new RenderStateShard.TextureStateShard(tex, false, false))
                        // NO_CULL keeps the silhouette a solid union of faces (no winding-dependent
                        // holes/seam crackle).
                        .setCullState(NO_CULL)
                        // LEQUAL + depth write against the mask's own depth: front-most fragment wins,
                        // so back faces can't overwrite the front face's occlusion verdict.
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setWriteMaskState(COLOR_DEPTH_WRITE)
                        .createCompositeState(false));
    }

    // Item sprites live in the block atlas, so flat/3D baked items trace their shape against it.
    private static RenderType silhouetteRT;

    private static RenderType silhouetteRT() {
        if (silhouetteRT == null) {
            silhouetteRT = buildSilhouetteRT("customglint:glow_silhouette", TextureAtlas.LOCATION_BLOCKS);
        }
        return silhouetteRT;
    }

    // Fallback texture for a special-item RenderType whose own texture couldn't be resolved: fully-opaque
    // white (alpha test always passes) → the whole model hull fills, the pre-texture-aware behaviour. Traced
    // via silhouetteTexRT(WHITE_TEXTURE) like any other texture bucket.
    private static final ResourceLocation WHITE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("neoforge", "textures/white.png");

    // Per-texture silhouette RTs for armor / entity bodies, whose ring must follow the REAL shape via the
    // texture's alpha (an armor box full-filled would just re-trace the body). Cached by texture; cleared
    // on resource reload (the textures themselves may change).
    private static final Map<ResourceLocation, RenderType> texSilhouetteRTs = new HashMap<>();

    private static RenderType silhouetteTexRT(ResourceLocation tex) {
        return texSilhouetteRTs.computeIfAbsent(tex,
                t -> buildSilhouetteRT("customglint:glow_silhouette_tex_" + t, t));
    }

    // TRIANGLES-mode counterpart of silhouetteTexRT, for triangle-list silhouette captures (Epic Fight
    // patched-entity meshes). Cached separately; cleared on resource reload.
    private static final Map<ResourceLocation, RenderType> texSilhouetteTriRTs = new HashMap<>();

    private static RenderType silhouetteTexTriangleRT(ResourceLocation tex) {
        return texSilhouetteTriRTs.computeIfAbsent(tex,
                t -> buildSilhouetteRT("customglint:glow_silhouette_tri_" + t, t, VertexFormat.Mode.TRIANGLES));
    }

    // ── RenderType → texture resolution (for special-item silhouettes) ──────────
    // A BEWLR (trident, shield, IaF/EK custom renderers) draws its model through RenderTypes bound to the
    // item's OWN texture, whose alpha is the real shape. To trace that shape (instead of white-filling the
    // whole model hull → a square/over-cover), the special capture groups vertices by the texture of each
    // RenderType it draws through. RenderType doesn't expose its texture, so it's read reflectively from the
    // composite state (state().textureState.texture): best-effort, cached per RenderType, with WHITE_TEXTURE
    // (full fill) as the fallback for non-composite / textureless RTs so behaviour degrades to the old hull.
    private static final Map<RenderType, ResourceLocation> rtTextureCache = new IdentityHashMap<>();
    private static Method stateMethod;
    private static Field textureStateField;
    private static Field textureField;

    public static ResourceLocation resolveRenderTypeTexture(RenderType rt) {
        ResourceLocation r = rtTextureCache.get(rt);
        if (r != null) return r;
        r = reflectRenderTypeTexture(rt);
        rtTextureCache.put(rt, r);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static ResourceLocation reflectRenderTypeTexture(RenderType rt) {
        try {
            Method sm = stateMethod;
            if (sm == null || !sm.getDeclaringClass().isInstance(rt)) {
                sm = rt.getClass().getDeclaredMethod("state");
                sm.setAccessible(true);
                stateMethod = sm;
            }
            Object state = sm.invoke(rt); // RenderType.CompositeState
            Field tsf = textureStateField;
            if (tsf == null || !tsf.getDeclaringClass().isInstance(state)) {
                tsf = state.getClass().getDeclaredField("textureState");
                tsf.setAccessible(true);
                textureStateField = tsf;
            }
            Object texState = tsf.get(state); // RenderStateShard.EmptyTextureStateShard / TextureStateShard
            Field tf = textureField;
            if (tf == null || !tf.getDeclaringClass().isInstance(texState)) {
                Field found = null;
                for (Class<?> c = texState.getClass(); c != null && found == null; c = c.getSuperclass()) {
                    try { found = c.getDeclaredField("texture"); } catch (NoSuchFieldException ignored) {}
                }
                if (found == null) return WHITE_TEXTURE; // EmptyTextureStateShard has no texture
                found.setAccessible(true);
                tf = found;
                textureField = tf;
            }
            Object opt = tf.get(texState);
            if (opt instanceof Optional<?> o && o.isPresent() && o.get() instanceof ResourceLocation loc)
                return loc;
        } catch (Throwable ignored) {
            // Non-composite RT, sealed access, or renamed field → fall back to the white-fill hull.
        }
        return WHITE_TEXTURE;
    }

    // ── Per-identity outline id ──────────────────────────────────────────────────
    // One id per logical figure (an entity instance), SHARED by its body and all its worn armor. The
    // composite compares only the low-5-bit id (key & 31) for "same object → no internal seam", and reads
    // the per-category thickness from the high bits, so body (CAT_ENTITY) and armor (CAT_ARMOR) of one
    // figure compose as ONE unified ring while staying distinct from other figures. Reset each frame.
    private static final Map<Object, Integer> glowIdByIdentity = new IdentityHashMap<>();

    public static int glowKeyFor(Object identity, int category) {
        int id = glowIdByIdentity.computeIfAbsent(identity, k -> nextGlowId());
        return (category << 5) | id;
    }

    // 4096 bytes is only the starting allocation; ByteBufferBuilder grows itself as a drain fills it.
    private static final ByteBufferBuilder MASK_BUFFER = new ByteBufferBuilder(4096);
    private static final MultiBufferSource.BufferSource MASK_BUFFERS =
            MultiBufferSource.immediate(MASK_BUFFER);

    // ── Composite scissor ──────────────────────────────────────────────────────
    // The composite is a fullscreen kernel pass: unbounded, it runs the ~15x15 source search over EVERY
    // screen pixel every frame and pegs the GPU even for one small item (~700->400 fps on a single
    // third-person sword). Fix: track the silhouette's camera-relative AABB while accumulating it,
    // project that to a screen box, and scissor the composite to it so the kernel only runs near the
    // item(s). Pad must cover this pass's ring reach (searchRadius) + the opening guard's 1px read + a
    // small margin; SCISSOR_MARGIN supplies that fixed slack on top of searchRadius.
    private static final int SCISSOR_MARGIN = 3;
    private static final float[] camBox = new float[6]; // minX,minY,minZ, maxX,maxY,maxZ (camera-relative)

    // Per-drain projection (ProjMat*ModelViewMat) + viewport, set by beginAccumulation, used by
    // SilhouetteConsumer to project each vertex to screen. Render-thread only.
    private static final Matrix4f ACC_MVP = new Matrix4f();
    private static final Vector4f SCRATCH_V = new Vector4f();
    private static int ACC_W, ACC_H;

    // Exact on-screen bounds (GL bottom-left px) of the silhouette currently being accumulated, tracked
    // per-vertex in SilhouetteConsumer. Replaces projecting the camera-space AABB CORNERS: a close /
    // first-person item's synthetic AABB corners can fall behind the near plane and force the scissor to
    // fullscreen (the radius-7 held-FP kernel over the whole screen, the GPU spike on a held glowing item).
    // The item's DRAWN vertices are all in front of the camera, so projecting them gives a stable tight box.
    private static final float[] screenBox = new float[4]; // minX,minY,maxX,maxY

    // Per-object screen box + its distance-thinning scale, outline id, and camera distance. The composite
    // runs once per id (grouped), far→near, so a nearer object's whole outline layers cleanly OVER a
    // farther one's (elytra over helmet) instead of a per-pixel nearest-source pick that swaps colours
    // where they meet. {@code scale} is the distance thinning; {@code dist} drives the far→near order.
    // {@code snap} = framebuffer texels per ring step. 1.0 = world (per-pixel, smooth dilation); for GUI
    // icons it is the item-pixel size (guiScale * preview zoom) so the composite dilates in item-pixel steps
    // and the ring is BLOCKY, matching the pixel-art icon instead of a screen-pixel-smooth edge.
    private record Box(int x, int y, int w, int h, float scale, int id, float dist, int prio, float snap) {}
    private static final List<Box> itemBoxes = new ArrayList<>();

    // Distance-proportional thinning (ported from 26.1.2's glow_outline_id.fsh, computed CPU-side here from
    // each object's camera-relative AABB instead of sampling scene depth; 1.21.1 can't sample the main
    // depth in the composite without the feedback-loop crackle). At/under REF_DIST the ring keeps full
    // thickness; beyond it narrows ∝ 1/distance so the ring tracks the object's apparent size, floored at
    // MIN_SCALE so far rings stay a hairline.
    private static final float REF_DIST = 4.0f;   // blocks
    private static final float MIN_SCALE = 0.40f;

    // GUI ring thickness as a FRACTION of an icon (1/16) pixel. Matches the 26.1 GUI outline's THICKNESS.
    // The composite reach is this * the icon's on-screen pixel size, in framebuffer texels, so the ring
    // reads as a thin ~1px line rather than a full blocky icon-pixel (which is ~2 screen px at common GUI
    // scales). Sub-icon-pixel but still crisp (square Chebyshev reach, no anti-aliasing).
    private static final float GUI_RING_ITEM_PIXELS = 0.6f;

    private static void resetCamBox() {
        camBox[0] = camBox[1] = camBox[2] = Float.POSITIVE_INFINITY;
        camBox[3] = camBox[4] = camBox[5] = Float.NEGATIVE_INFINITY;
        screenBox[0] = screenBox[1] = Float.POSITIVE_INFINITY;
        screenBox[2] = screenBox[3] = Float.NEGATIVE_INFINITY;
    }

    /** Snapshot the live projection + viewport for per-vertex screen tracking; call once per drain before
     *  accumulating, while the matrices the objects were drawn under are the live RenderSystem matrices. */
    private static void beginAccumulation(int width, int height) {
        ACC_MVP.set(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix());
        ACC_W = width;
        ACC_H = height;
    }

    /** Camera distance of whatever was just accumulated into camBox (AABB centre), or +inf if empty. */
    private static float computeDist() {
        if (camBox[0] > camBox[3]) return Float.POSITIVE_INFINITY;
        float cx = (camBox[0] + camBox[3]) * 0.5f;
        float cy = (camBox[1] + camBox[4]) * 0.5f;
        float cz = (camBox[2] + camBox[5]) * 0.5f;
        return (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** Distance-thinning scale: full (1.0) at/under REF_DIST, narrowing with distance to MIN_SCALE. */
    private static float computeScale(float dist) {
        return Math.max(MIN_SCALE, Math.min(1.0f, REF_DIST / Math.max(dist, 0.001f)));
    }

    // ── Capture queue ──────────────────────────────────────────────────────────

    // {@code anchor} = the item's GUI-space slot centre ({@code [x,y,z]}, the pose translation at
    // ItemRenderer.render), used by the GUI drain to clamp the ring to the nominal 16-GUI-px slot. null for
    // world / first-person jobs (they scissor by silhouette bounds, not a slot).
    private record ItemJob(List<BakedQuad> quads, PoseStack.Pose pose, int light, int color, int category,
                           float[] anchor) {}

    // Armor pieces and entity bodies: a posed model re-rendered into a record-only buffer at draw time,
    // capturing camera-relative [x,y,z,u,v] per vertex (QUADS order) so the silhouette traces the real
    // shape against {@code tex}. Each job carries an explicit {@code key} from {@link #glowKeyFor} so a
    // figure's body + all its armor share one id and compose as ONE ring.
    private record ModelJob(float[] data, int len, ResourceLocation tex, int color, int key, int category,
                            int priority, float[] anchor, boolean triangles) {}

    private static final List<ItemJob> worldJobs = new ArrayList<>();
    private static final List<ItemJob> heldFpJobs = new ArrayList<>();
    private static final List<ItemJob> guiJobs = new ArrayList<>();
    private static final List<ModelJob> modelWorldJobs = new ArrayList<>();
    private static final List<ModelJob> modelFpJobs = new ArrayList<>();
    private static final List<ModelJob> modelGuiJobs = new ArrayList<>();

    // The GUI mask is cleared once per frame (see drainGui); this flag, reset in beginFrame, tracks whether
    // the first GUI drain of the frame has done that clear yet.
    private static boolean guiMaskCleared = false;

    /** Queue a world-space glowing item (third-person held / dropped / frame / other player). The pose
     *  must be a copy ({@code pose.last().copy()}); it is camera-relative and is replayed at drain. */
    public static void queueWorldItem(List<BakedQuad> quads, PoseStack.Pose pose, int light, int color) {
        if (!pose.pose().isFinite()) return;
        if (CustomGlintRenderer.isInShadowPass()) return; // don't capture the Iris shadow-map pass
        worldJobs.add(new ItemJob(quads, pose, light, color, CAT_ITEM, null));
    }

    /** Queue a first-person held item. The pose must be a copy ({@code pose.last().copy()}) taken at the
     *  item's {@code ItemRenderer.render} RETURN during the hand pass; it bakes in the inverse camera
     *  rotation, so at {@link #drainHeldFp()} (where the live ProjMat is the hand-FOV proj and the live
     *  ModelView is the camera rotation) the rotation cancels and the silhouette lands exactly on the
     *  drawn hand item. */
    public static void queueHeldFpItem(List<BakedQuad> quads, PoseStack.Pose pose, int light, int color) {
        if (!pose.pose().isFinite()) return;
        if (CustomGlintRenderer.isInShadowPass()) return; // don't capture the Iris shadow-map pass
        snapshotHeldFpMatrices();
        heldFpJobs.add(new ItemJob(quads, pose, light, color, CAT_HELD_FP, null));
    }

    /** Queue a glowing item rendered into a GUI / inventory / HUD slot ({@code ItemDisplayContext.GUI}).
     *  {@code pose} is the reproduced GUI transform (GuiGraphics pose * display transform * centering), so
     *  the silhouette vertices are in GUI screen space; it is drained immediately via {@link #drainGui()}
     *  while the GUI ortho ProjMat / ModelView are still the live RenderSystem matrices, projecting the
     *  silhouette exactly onto the drawn icon. {@code anchor} is the item's GUI-space slot centre (the
     *  render pose translation) so the drain can clamp the ring to the nominal slot. */
    public static void queueGuiItem(List<BakedQuad> quads, PoseStack.Pose pose, int light, int color,
                                    float[] anchor) {
        if (!pose.pose().isFinite()) return;
        guiJobs.add(new ItemJob(quads, pose, light, color, CAT_ITEM, anchor));
    }

    /** Queue a posed model silhouette (worn armor piece, or an entity body) traced against {@code tex}.
     *  {@code data} is camera-relative {@code [x,y,z,u,v]} per vertex (QUADS order) captured by
     *  re-rendering the freshly-posed model into a record-only buffer; {@code key} comes from
     *  {@link #glowKeyFor} so a figure's body + all its armor share one id → ONE unified ring. Drained
     *  with the world items at {@code AFTER_WEATHER}. */
    public static void queueModelOutline(float[] data, int len, ResourceLocation tex, int color, int key,
                                         int category, int priority) {
        if (CustomGlintRenderer.isInShadowPass()) return; // don't capture the Iris shadow-map pass
        if (len < 20 || tex == null) return; // need at least one quad (4 verts * 5 floats)
        if (!allFinite(data, len)) return;
        modelWorldJobs.add(new ModelJob(Arrays.copyOf(data, len), len, tex, color, key, category, priority, null, false));
    }

    /** TRIANGLES-mode counterpart of {@link #queueModelOutline}, for a silhouette captured from a
     *  triangle-list draw (Epic Fight patched-entity meshes route through a TRIANGLES-mode RenderType).
     *  {@code data} is camera-relative {@code [x,y,z,u,v]} per vertex in triangle-list order; the vertex
     *  count is trimmed to a multiple of 3 so the deferred TRIANGLES draw never leaves a partial primitive.
     *  Same {@code key} scheme as {@link #queueModelOutline}: the body mesh + every worn/patched layer of
     *  one figure share a key and compose as ONE ring. Drained with the world items at {@code AFTER_WEATHER}. */
    public static void queueModelOutlineTriangles(float[] data, int len, ResourceLocation tex, int color, int key,
                                                  int category, int priority) {
        if (CustomGlintRenderer.isInShadowPass()) return; // don't capture the Iris shadow-map pass
        if (tex == null || !allFinite(data, len)) return;
        float[] copy = trimToPrimitives(data, len, true);
        if (copy == null) return;
        modelWorldJobs.add(new ModelJob(copy, copy.length, tex, color, key, category, priority, null, true));
    }

    /** Copy the first {@code len} floats of {@code data} trimmed to WHOLE primitives (3 verts per triangle,
     *  4 per quad, 5 floats per vert) so a deferred draw never gets a partial primitive. Null when fewer than
     *  one whole primitive was captured. */
    @Nullable
    private static float[] trimToPrimitives(float[] data, int len, boolean triangles) {
        int vertsPerPrim = triangles ? 3 : 4;
        int verts = len / 5;
        verts -= verts % vertsPerPrim;
        int usable = verts * 5;
        if (usable < vertsPerPrim * 5) return null;
        return Arrays.copyOf(data, usable);
    }

    // Set for the duration of the first-person hand pass (armed at the HEAD of
    // ItemInHandRenderer.renderHandsWithItems / GameRenderer.renderItemInHand). FP-replacing mods (Punchy,
    // First-Person Model) draw the held item with a THIRD_PERSON display context during that pass; this flag
    // lets the capture route it to the FP held queue (drained under the hand-FOV matrices) instead of the
    // world queue, and the drain point clears it.
    private static boolean fpHandPass = false;

    /** Arm/disarm the first-person hand pass (see {@link #isFpHand()}). */
    public static void setFpHandPass(boolean active) { fpHandPass = active; }

    /** True when the item currently being drawn belongs to the first-person hand: either our own hand-pass
     *  flag is armed (vanilla / Punchy / FPM off-pack), or Iris is in its HAND phase (under a shader pack the
     *  hand is drawn there (inside the gbuffer pass, outside renderItemInHand/renderHandsWithItems), so the
     *  flag never arms). Used to treat a THIRD_PERSON-context held item (Punchy / FPM / Iris draw it that way)
     *  as first-person so it routes to the FP queue. */
    public static boolean isFpHand() { return fpHandPass || CustomGlintRenderer.isShaderHandPass(); }

    /** True for a native first-person hand context, or any item captured inside the hand pass. */
    static boolean isFpContext(ItemDisplayContext ctx) {
        return ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || isFpHand();
    }

    /** CAT_HELD_FP for a first-person hand item, else CAT_ITEM. */
    static int itemCategory(ItemDisplayContext ctx) {
        return isFpContext(ctx) ? CAT_HELD_FP : CAT_ITEM;
    }

    // Modelview a first-person held item is DRAWN under, snapshotted when it is queued (inside the hand pass).
    // Under a shader pack the drain runs at renderItemInHand RETURN, where vanilla has already popped the
    // hand modelview back to the world one; replaying under that detaches the ring and free-floats it near
    // the player. The PROJECTION is deliberately NOT snapshotted: at renderItemInHand RETURN the live
    // RenderSystem projection is still the fixed hand-FOV projection (vanilla set it at the method HEAD and
    // never restores it), which is exactly what the hand was drawn under. Overriding it with Iris's gbuffer
    // (world) projection instead made the ring drift on sprint, when the world FOV diverges from the hand's.
    private static final Matrix4f HELD_FP_MV = new Matrix4f();
    private static boolean heldFpMatricesValid = false;

    /** Capture the live RenderSystem modelview the first-person hand item is drawn under (the hand pass sets
     *  it once and every hand item shares it). */
    private static void snapshotHeldFpMatrices() {
        HELD_FP_MV.set(RenderSystem.getModelViewMatrix());
        heldFpMatricesValid = true;
    }

    /** Queue one textured item silhouette bucket under an explicit outline {@code key}. A multi-texture item
     *  (e.g. a shield base + banner-pattern textures) passes the SAME key for every bucket so they compose as
     *  one ring with no internal seam where the textures overlap. */
    static void addItemModelJob(float[] data, int len, ResourceLocation tex, int color,
                                ItemDisplayContext ctx, int key, float[] anchor) {
        if (CustomGlintRenderer.isInShadowPass()) return; // don't capture the Iris shadow-map pass
        if (len < 20 || tex == null) return; // need at least one quad (4 verts * 5 floats)
        ModelJob job = new ModelJob(Arrays.copyOf(data, len), len, tex, color, key, itemCategory(ctx), 0,
                ctx == ItemDisplayContext.GUI ? anchor : null, false);
        if (ctx == ItemDisplayContext.GUI) modelGuiJobs.add(job);
        else if (isFpContext(ctx)) { snapshotHeldFpMatrices(); modelFpJobs.add(job); }
        else modelWorldJobs.add(job);
    }

    /** Per-frame reset; called from {@code RenderFrameEvent.Pre}. */
    public static void beginFrame() {
        worldJobs.clear();
        heldFpJobs.clear();
        guiJobs.clear();
        modelWorldJobs.clear();
        modelFpJobs.clear();
        modelGuiJobs.clear();
        glowIdByIdentity.clear();
        glowIdCounter = 0;
        guiMaskCleared = false;
        worldDeferredPending = false;
        heldFpMatricesValid = false;
        chromaticWorldJobs.clear();
        chromaticFpJobs.clear();
        chromaticGuiJobs.clear();
        chromaticPending = false;
    }

    // A broken pose from another mod (a NaN arm swing from extreme haste, a zero-scale render) or any other
    // failure inside one of our passes must cost that frame's rings, never the game. Every public pass runs
    // through here: the first failure per pass is logged with its stack, the frame's queued jobs are dropped,
    // and the GL state our passes change is put back for whatever renders next.
    private static final Set<String> loggedFailures = new HashSet<>();

    private static void guarded(String pass, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            if (loggedFailures.add(pass)) {
                LOGGER.error("[customglint] {} failed; skipping glow and chromatic rings this frame", pass, e);
            }
            beginFrame();
            restoreRenderState();
        }
    }

    private static void restoreRenderState() {
        try {
            MASK_BUFFERS.endBatch();
        } catch (RuntimeException ignored) {
            // A half-built batch can't be flushed; the next frame starts a fresh one.
        }
        RenderSystem.setShaderTexture(1, 0);
        RenderSystem.disableScissor();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    // Captured vertices are replayed and projected later; a non-finite one would poison the screen box.
    private static boolean allFinite(float[] data, int len) {
        for (int i = 0; i < len; i++) {
            if (!Float.isFinite(data[i])) return false;
        }
        return true;
    }

    // ── Drain ──────────────────────────────────────────────────────────────────

    /** Drain world-space outlines (items + glowing entities). Called at
     *  {@code RenderLevelStageEvent.AFTER_WEATHER}, where the live projection / modelview are the world
     *  ones the items and entities were drawn with. */
    public static void drainWorld() { guarded("drainWorld", GlowOutlineRenderer::drainWorldUnguarded); }

    private static void drainWorldUnguarded() {
        drain(worldJobs, modelWorldJobs, true);
    }

    /** Drain first-person held item outlines. Called at the RETURN of
     *  {@code ItemInHandRenderer.renderHandsWithItems} (via {@code ItemInHandRendererMixin}), where the
     *  live ProjMat is the hand-FOV projection and the live ModelView is the camera rotation, exactly the
     *  matrices the hand items were drawn under, so the captured camera-relative pose replays in place. The
     *  hand items have already been flushed ({@code endBatch}) by that point, so the ring composites over
     *  them. */
    public static void drainHeldFp() { guarded("drainHeldFp", GlowOutlineRenderer::drainHeldFpUnguarded); }

    private static void drainHeldFpUnguarded() {
        if (heldFpJobs.isEmpty() && modelFpJobs.isEmpty()) { heldFpMatricesValid = false; return; }
        // Under a shader pack the drain runs at renderItemInHand RETURN, where the live modelview has been
        // popped back to the world one; replaying the captured hand modelview puts the silhouette back on the
        // hand item. The live projection there is still the fixed hand-FOV one the hand drew under, so it is
        // left untouched (overriding it drifted the ring on sprint). Off-pack the drain point already has the
        // correct live matrices, so keep that proven path untouched.
        if (heldFpMatricesValid && CustomGlintRenderer.isShaderPackActive()) {
            Matrix4fStack mv = RenderSystem.getModelViewStack();
            mv.pushMatrix();
            mv.set(HELD_FP_MV);
            RenderSystem.applyModelViewMatrix();
            try {
                drain(heldFpJobs, modelFpJobs, false);
            } finally {
                mv.popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
        } else {
            drain(heldFpJobs, modelFpJobs, false);
        }
        heldFpMatricesValid = false;
    }

    /** Drain GUI / inventory / HUD item outlines. Called per item from {@code ItemRendererMixin} at the
     *  RETURN of {@code ItemRenderer.render} when {@code ItemDisplayContext.GUI}, while the GUI ortho
     *  projection and modelview ({@code identity·translate(0,0,-11000)}) are still the live RenderSystem
     *  matrices, so the captured GUI-space silhouette projects exactly onto the just-drawn icon. The icon's
     *  own quads are still buffered (GuiGraphics flushes after this returns), so the ring composites into the
     *  margin and the icon overdraws any overlap, leaving the ring strictly around it.
     *
     *  <p>Differs from {@link #drain}: (1) the scene-depth occlusion sampler is left UNBOUND, since the main
     *  depth here holds stale world depth and would wrongly occlude every flat icon; (2) ring thickness is
     *  driven by the icon's on-screen size (GUI_RING_ITEM_PIXELS) rather than camera distance; (3) the live
     *  GUI scissor (e.g. the wand-preview box) is saved, intersected with each ring box, and restored, so
     *  the ring stays inside whatever the GUI was clipping to and never clobbers it. */
    public static void drainGui() { guarded("drainGui", GlowOutlineRenderer::drainGuiUnguarded); }

    private static void drainGuiUnguarded() {
        if (guiJobs.isEmpty() && modelGuiJobs.isEmpty()) return;
        if (silhouetteShader == null || compositeShader == null) {
            guiJobs.clear(); modelGuiJobs.clear(); return;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        ensureTarget(main.width, main.height);
        beginAccumulation(main.width, main.height);

        // Save the GUI's active GL scissor (raw bottom-left coords): we INTERSECT each ring box with it (so
        // a ring can't draw past the GUI's own clip, e.g. the wand-editor preview box or a scroll panel)
        // and RESTORE it afterwards (our composite toggles the GL scissor per ring box and would otherwise
        // leave it disabled, unclipping the rest of the screen).
        boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] prevBox = new int[4];
        if (prevScissor) GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevBox);

        // Clear the mask ONCE per frame, not once per icon. GuiGraphics flushes (and so this drains) after
        // EVERY item it renders, so an inventory full of glowing items would otherwise do one FULL-SCREEN
        // mask clear per icon, the dominant cost in that scene. GUI icon boxes are disjoint and each
        // composite is scissored + TargetId-filtered to its own icon, so one clear at the first GUI drain of
        // the frame is enough: later icons write into their own (already-clean) regions, and the first clear
        // also wipes the world/FP drains' leftover silhouettes. Reset in beginFrame().
        if (!guiMaskCleared) {
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
            maskTarget.clear(Minecraft.ON_OSX);
            guiMaskCleared = true;
        }
        maskTarget.bindWrite(true);
        // GUI: leave the scene-depth sampler UNBOUND (unit 1 = 0). The silhouette shader then treats every
        // fragment as visible; binding the main depth (which still holds the world's depth in the GUI phase)
        // would occlude the whole icon and erase the ring.
        RenderSystem.setShaderTexture(1, 0);
        itemBoxes.clear();

        VertexConsumer base = MASK_BUFFERS.getBuffer(silhouetteRT());
        for (ItemJob job : guiJobs) {
            resetCamBox();
            int key = nextGlowKey(job.category);
            SilhouetteConsumer sc = new SilhouetteConsumer(base, job.color, key);
            for (BakedQuad quad : job.quads) {
                sc.putBulkData(job.pose, quad, 1.0f, 1.0f, 1.0f, 1.0f, job.light, OverlayTexture.NO_OVERLAY);
            }
            Box box = computeGuiBox(main.width, main.height, key & 31, false, job.anchor);
            if (box != null && prevScissor) box = intersectBox(box, prevBox);
            if (box != null) itemBoxes.add(box);
        }
        // Textured item models: flat compat BEWLRs whose shape is texture-carved (IaF troll weapons) AND
        // special / 3D BEWLR items (trident, shield) captured per-texture by the special path. Each traces
        // its real shape against its own texture, not a square model hull.
        for (ModelJob job : modelGuiJobs) {
            resetCamBox();
            VertexConsumer tc = MASK_BUFFERS.getBuffer(
                    job.triangles ? silhouetteTexTriangleRT(job.tex) : silhouetteTexRT(job.tex));
            SilhouetteConsumer sc = new SilhouetteConsumer(tc, job.color, job.key);
            emitModel(sc, job);
            Box box = computeGuiBox(main.width, main.height, job.key & 31, true, job.anchor);
            if (box != null && prevScissor) box = intersectBox(box, prevBox);
            if (box != null) itemBoxes.add(box);
        }
        MASK_BUFFERS.endBatch();
        RenderSystem.setShaderTexture(1, 0);

        if (!itemBoxes.isEmpty()) {
            // Each icon carries its OWN ring reach in framebuffer texels (box.snap = GUI_RING_ITEM_PIXELS *
            // icon-pixel size, thinned for 3D items). SearchRadius is the kernel bound = the widest reach
            // present; each pass then limits to its own reach via ThicknessScale = reach / SearchRadius, so a
            // thinned 3D icon rings thinner than a flat icon in the same drain (the shared kernel can't do
            // that without per-pass scaling).
            int searchRadius = 1;
            for (Box bx : itemBoxes) {
                searchRadius = Math.max(searchRadius, (int) Math.ceil(bx.snap));
            }
            // One scissored pass per item id; the TargetId filter keeps each icon's ring inside its own box.
            // ringOcclusion=false: the GUI is orthographic, the depth-based occlusion test is invalid there
            // and icons never overlap, so it would only (wrongly) erase the ring. guiMode=true: square reach
            // = ThicknessScale * searchRadius texels, no opening guard.
            compositeBegin(main, searchRadius, false, true, 0.0f, 0.0f, maskTarget, 0);
            for (int i = 0; i < itemBoxes.size(); i++) {
                Box box = itemBoxes.get(i);
                compositePass(box, box.snap / searchRadius, box.id);
            }
            compositeEnd();
        } else {
            // No on-screen box → composite skipped → maskTarget still bound from accumulation. Rebind the
            // main target so the rest of the GUI renders to the screen, not into the offscreen mask.
            main.bindWrite(true);
        }

        // Restore the GUI scissor (composite leaves GL scissor disabled).
        if (prevScissor) RenderSystem.enableScissor(prevBox[0], prevBox[1], prevBox[2], prevBox[3]);
        else RenderSystem.disableScissor();

        guiJobs.clear();
        modelGuiJobs.clear();
    }

    /** Shared drain: accumulate each queued silhouette into the mask under the live matrices, then run the
     *  id-aware dilation composite once per cluster of overlapping objects. Quad items trace the block
     *  atlas through {@link #silhouetteRT()}; model items (special / 3D BEWLR items, armor, entities) trace
     *  their own texture through {@link #silhouetteTexRT}. Each job carries its own {@code category}; the
     *  per-job key drives the shader's per-category {@code THICKNESS[]}, and the composite kernel radius for
     *  the whole pass is the widest category present (a source can never ring past its own thickness, so a
     *  larger kernel only costs unused taps, never a wrong ring). */
    private static void drain(List<ItemJob> jobs, List<ModelJob> models, boolean sceneOcclusion) {
        if (jobs.isEmpty() && models.isEmpty()) return;
        if (silhouetteShader == null || compositeShader == null) {
            jobs.clear(); models.clear(); return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        Matrix4f proj = RenderSystem.getProjectionMatrix();
        ensureTarget(main.width, main.height);
        int searchRadius = accumulate(jobs, models, main, itemBoxes, maskTarget, sceneOcclusion);
        composite(main, itemBoxes, searchRadius, proj.m22(), proj.m32(), maskTarget, 0);
        jobs.clear();
        models.clear();
    }

    // ── Deferred world drain (shader pack active) ───────────────────────────────
    // Under an Iris/Oculus shader pack the AFTER_WEATHER composite was being overwritten by the pack's own
    // scene composite (which runs later), so no world outline ever showed. Split the drain: ACCUMULATE the
    // mask at AFTER_WEATHER (the silhouette draw + screen-box projection need the live world matrices) but
    // hold the COMPOSITE until renderLevel TAIL (LevelRendererMixin), after the pack has finished compositing
    // to the main target, so the ring lands on the final image. The composite is a matrix-independent
    // fullscreen blit; only the ring-occlusion depth linearisation needs the world projection, snapshotted
    // here as projA/projB. Off-pack, AFTER_WEATHER still does the whole drain immediately (drainWorld).
    private static final List<Box> worldDeferredBoxes = new ArrayList<>();
    private static int worldDeferredSearchRadius;
    private static float worldDeferredProjA, worldDeferredProjB;
    private static boolean worldDeferredPending = false;

    /** Accumulate the world silhouettes into the mask now (matrices live) but defer the composite. */
    public static void accumulateWorld() { guarded("accumulateWorld", GlowOutlineRenderer::accumulateWorldUnguarded); }

    private static void accumulateWorldUnguarded() {
        worldDeferredPending = false;
        if (worldJobs.isEmpty() && modelWorldJobs.isEmpty()) return;
        if (silhouetteShader == null || compositeShader == null) {
            worldJobs.clear(); modelWorldJobs.clear(); return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        Matrix4f proj = RenderSystem.getProjectionMatrix();
        worldDeferredMask = ensureTarget(worldDeferredMask, main.width, main.height);
        worldDeferredSearchRadius = accumulate(worldJobs, modelWorldJobs, main, worldDeferredBoxes, worldDeferredMask, true);
        worldDeferredProjA = proj.m22();
        worldDeferredProjB = proj.m32();
        worldDeferredPending = true;
        worldJobs.clear();
        modelWorldJobs.clear();
        // accumulate() left the mask bound for writing; rebind the main target so the rest of the world pass
        // (translucent, hand, the pack's composite) renders to the scene, not into our offscreen mask.
        main.bindWrite(true);
    }

    /** Composite the deferred world outlines onto the (now pack-composited) main target. Called at
     *  {@code LevelRenderer.renderLevel} TAIL. No-op when nothing was deferred this frame. */
    public static void compositeWorld() { guarded("compositeWorld", GlowOutlineRenderer::compositeWorldUnguarded); }

    private static void compositeWorldUnguarded() {
        if (!worldDeferredPending) return;
        worldDeferredPending = false;
        if (worldDeferredMask == null) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        // EdgeBleed=1: the pack's final image insets the item ~1px inside our silhouette, so bleed the ring
        // one texel inward over the border to close the hairline gap.
        composite(main, worldDeferredBoxes, worldDeferredSearchRadius, worldDeferredProjA, worldDeferredProjB,
                worldDeferredMask, 1);
    }

    // ── Deferred chromatic overlay (shader pack active) ─────────────────────────
    // Under an Iris/Oculus pack the in-phase chromatic program is hijacked (the procedural slick goes flat
    // white / never appears, no pack program can recreate the oil-slick). Mirror the glow deferred split:
    // ACCUMULATE the slick into an isolated target at AFTER_WEATHER (the world matrices are live and the scene
    // depth is committed for the overlay's in-shader occlusion), then COMPOSITE-blit it over the pack's final
    // image at renderLevel TAIL. Off-pack nothing is queued (chromatic draws in-phase), so this is inert.
    private static TextureTarget chromaticTarget;
    private static final List<ChromaJob> chromaticWorldJobs = new ArrayList<>();
    private static boolean chromaticPending = false;

    private static final class ChromaJob {
        final float[] data; final int len; final ResourceLocation tex;
        final CustomGlint.Data glint; final int layerIdx; final boolean triangles; final boolean isItem;
        final boolean special; // special 3D item (trident/shield): own texture + the denser special-item scale
        final float[] color; final int colorIdx; // non-null color => TEXTURED (non-chromatic) glint overlay
        final float brightness; // sub-1 dims the slick (slime shell); opaque surfaces stay at 1.0
        ChromaJob(float[] data, int len, ResourceLocation tex, CustomGlint.Data glint, int layerIdx,
                  boolean triangles, boolean isItem, boolean special, float brightness) {
            this(data, len, tex, glint, layerIdx, triangles, isItem, special, null, 0, brightness);
        }
        ChromaJob(float[] data, int len, ResourceLocation tex, CustomGlint.Data glint, int layerIdx,
                  boolean triangles, boolean isItem, boolean special, float[] color, int colorIdx, float brightness) {
            this.data = data; this.len = len; this.tex = tex;
            this.glint = glint; this.layerIdx = layerIdx; this.triangles = triangles; this.isItem = isItem;
            this.special = special; this.color = color; this.colorIdx = colorIdx; this.brightness = brightness;
        }
    }

    // Chromatic drain destination: WORLD (renderLevel TAIL), FP hand (renderItemInHand RETURN), GUI (flush).
    private static final int DEST_WORLD = 0, DEST_FP = 1, DEST_GUI = 2;

    /** Queue a captured chromatic model (camera-relative {@code [x,y,z,u,v]} per vertex, QUADS or TRIANGLES
     *  order) for the post-Iris overlay drain. Called from the armor chromatic chokepoints ONLY when a shader
     *  pack is active; off-pack the layer draws in-phase and never reaches here. Trims to whole primitives so
     *  the deferred draw never leaves a partial quad/triangle. */
    public static void queueChromaticModel(float[] data, int len, ResourceLocation tex,
                                           CustomGlint.Data glint, int layerIdx, boolean triangles) {
        queueChromaticModel(data, len, tex, glint, layerIdx, triangles, 1.0f);
    }

    /** As {@link #queueChromaticModel} with a {@code brightness} the overlay applies to the slick (sub-1 dims
     *  it). The slime shell passes < 1 so its chromatic doesn't read as a solid glowing block over the
     *  translucent jelly; opaque surfaces stay at 1.0. */
    public static void queueChromaticModel(float[] data, int len, ResourceLocation tex,
                                           CustomGlint.Data glint, int layerIdx, boolean triangles, float brightness) {
        queueChroma(data, len, tex, glint, layerIdx, triangles, false, false, DEST_WORLD, brightness);
    }

    /** Queue a captured horse-armor model for the post-Iris TEXTURED (non-chromatic) glint overlay: same world
     *  drain as {@link #queueChromaticModel}, but the overlay samples the scrolled design × {@code color} and
     *  cuts itself against {@code tex} (armor alpha). Called ONLY under a shader pack (off-pack the glint draws
     *  in-phase and never reaches here). Trims to whole primitives so no partial quad/triangle is left. */
    public static void queueGlintModel(float[] data, int len, ResourceLocation tex, CustomGlint.Data glint,
                                       int layerIdx, float[] color, int colorIdx, boolean triangles) {
        if (CustomGlintRenderer.isInShadowPass()) return;
        if (tex == null || glint == null || color == null || !allFinite(data, len)) return;
        float[] copy = trimToPrimitives(data, len, triangles);
        if (copy == null) return;
        float[] col = { color[0], color[1], color[2], color[3] };
        chromaticWorldJobs.add(new ChromaJob(copy, copy.length, tex, glint, layerIdx, triangles, false, false, col, colorIdx, 1.0f));
    }

    /** Special 3D item (trident/shield) tracing its own {@code tex} at the denser special-item noise scale.
     *  {@code dest}: world (dropped/3rd-person), FP hand, or GUI icon, matching the world/hand item. */
    public static void queueChromaticSpecial(float[] data, int len, ResourceLocation tex,
                                             CustomGlint.Data glint, int layerIdx, int dest) {
        queueChroma(data, len, tex, glint, layerIdx, false, false, true, dest, 1.0f);
    }

    /** As {@link #queueChromaticModel} but for a flat/quad ITEM (held third-person, dropped, item frame): the
     *  captured verts are baked item quads and the overlay traces the block atlas (the item overlay RT binds
     *  the atlas itself). QUADS. */
    public static void queueChromaticItem(float[] data, int len, CustomGlint.Data glint, int layerIdx) {
        queueChroma(data, len, TextureAtlas.LOCATION_BLOCKS, glint, layerIdx, false, true, false, DEST_WORLD, 1.0f);
    }

    /** First-person-hand variant of {@link #queueChromaticModel} (worn-armor-style model in the hand). */
    public static void queueChromaticModelFp(float[] data, int len, ResourceLocation tex,
                                             CustomGlint.Data glint, int layerIdx, boolean triangles) {
        queueChroma(data, len, tex, glint, layerIdx, triangles, false, false, DEST_FP, 1.0f);
    }

    /** GUI variant for a flat/quad item icon (inventory, hotbar, wand-menu preview). Item scale (atlas). */
    public static void queueChromaticItemGui(float[] data, int len, CustomGlint.Data glint, int layerIdx) {
        queueChroma(data, len, TextureAtlas.LOCATION_BLOCKS, glint, layerIdx, false, true, false, DEST_GUI, 1.0f);
    }

    private static void queueChroma(float[] data, int len, ResourceLocation tex,
                                    CustomGlint.Data glint, int layerIdx, boolean triangles, boolean isItem,
                                    boolean special, int dest, float brightness) {
        if (CustomGlintRenderer.isInShadowPass()) return;
        if (tex == null || glint == null || !allFinite(data, len)) return;
        float[] copy = trimToPrimitives(data, len, triangles);
        if (copy == null) return;
        ChromaJob job = new ChromaJob(copy, copy.length, tex, glint, layerIdx, triangles, isItem, special, brightness);
        switch (dest) {
            case DEST_FP -> { chromaticFpJobs.add(job); snapshotHeldFpMatrices(); }
            case DEST_GUI -> chromaticGuiJobs.add(job);
            default -> chromaticWorldJobs.add(job);
        }
    }

    /** Render {@code jobs} into the isolated chromatic target. {@code sceneOcclusion} binds the main depth on
     *  unit 2 for the overlay's in-shader wall/self occlusion (world surfaces); the first-person hand item is
     *  always foreground so it passes false (its own imprecise depth would else flicker its slick). Leaves the
     *  main target bound. */
    private static void accumulateChromaJobs(List<ChromaJob> jobs, boolean sceneOcclusion) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        chromaticTarget = ensureTarget(chromaticTarget, main.width, main.height);
        // glClear honours the live write masks; the world phase leaves depthMask=false, so force them on.
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);
        chromaticTarget.clear(Minecraft.ON_OSX);
        chromaticTarget.bindWrite(true);
        // Scene depth -> unit 2 for the overlay's in-shader occlusion. Safe: we write the isolated target, not
        // the main colour, so there is no read/write feedback. The overlay RT setup only touches units 0/1.
        RenderSystem.setShaderTexture(2, sceneOcclusion ? main.getDepthTextureId() : 0);
        // Two passes so the straight-write chromatic slicks (NO_TRANSPARENCY) land BEFORE the additive textured
        // glints: a chromatic job overwrites the target, so a textured job drawn first would be clobbered.
        // Drawing chromatic first, then adding the textured glint over it, keeps both in a mixed glint. Textured
        // jobs (color != null) additively sum, so their own order between each other doesn't matter.
        for (int pass = 0; pass < 2; pass++) {
            boolean texturedPass = pass == 1;
            for (ChromaJob job : jobs) {
                if ((job.color != null) != texturedPass) continue;
                RenderType rt;
                if (job.color != null) rt = CustomGlintRenderer.forGlintEntityOverlay(job.glint, job.layerIdx,
                        job.color, job.colorIdx, job.tex, job.triangles ? VertexFormat.Mode.TRIANGLES : VertexFormat.Mode.QUADS);
                else if (job.isItem) rt = CustomGlintRenderer.forChromaticItemGlintOverlay(job.glint, job.layerIdx);
                else if (job.special) rt = CustomGlintRenderer.forChromaticSpecialGlintOverlay(job.glint, job.layerIdx, job.tex);
                else rt = CustomGlintRenderer.forChromaticArmorGlintOverlay(job.glint, job.layerIdx, job.tex,
                        job.triangles ? VertexFormat.Mode.TRIANGLES : VertexFormat.Mode.QUADS, job.brightness);
                if (rt == null) continue;
                VertexConsumer vc = MASK_BUFFERS.getBuffer(rt);
                float[] d = job.data;
                for (int i = 0; i + 4 < job.len; i += 5) {
                    vc.addVertex(d[i], d[i + 1], d[i + 2]);
                    vc.setColor(255, 255, 255, 255);
                    vc.setUv(d[i + 3], d[i + 4]);
                    vc.setUv1(0, 0);
                    vc.setUv2(0, 0);
                    vc.setNormal(0.0f, 1.0f, 0.0f);
                }
                MASK_BUFFERS.endBatch(); // flush per job so each overlay RT's texture + texture-matrix apply to its own verts
            }
        }
        RenderSystem.setShaderTexture(2, 0);
        main.bindWrite(true);
    }

    /** Screen-blit the chromatic target over the main target: result.rgb = src + dst - src*dst (brightens like
     *  a foil, can't exceed 1.0 so a bright slick over lit geometry never clips to white). Shared by the world
     *  composite + the first-person drain. */
    private static void blitChromaticTarget() {
        ShaderInstance shader = CustomGlintRenderer.chromaticCompositeShader();
        if (shader == null || chromaticTarget == null) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        main.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, chromaticTarget.getColorTextureId());
        drawFullscreenQuad();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /** Render the queued world chromatic slicks into the isolated target NOW (world matrices live, scene depth
     *  bound); the composite blit is deferred to renderLevel TAIL. Called at {@code AFTER_WEATHER} under a pack. */
    public static void accumulateChromaticWorld() { guarded("accumulateChromaticWorld", GlowOutlineRenderer::accumulateChromaticWorldUnguarded); }

    private static void accumulateChromaticWorldUnguarded() {
        chromaticPending = false;
        if (chromaticWorldJobs.isEmpty()) return;
        accumulateChromaJobs(chromaticWorldJobs, true);
        chromaticWorldJobs.clear();
        chromaticPending = true;
    }

    /** Composite the deferred world chromatic slick onto the (pack-composited) main target. renderLevel TAIL. */
    public static void compositeChromaticWorld() { guarded("compositeChromaticWorld", GlowOutlineRenderer::compositeChromaticWorldUnguarded); }

    private static void compositeChromaticWorldUnguarded() {
        if (!chromaticPending) return;
        chromaticPending = false;
        blitChromaticTarget();
    }

    // First-person hand chromatic items: captured during the hand pass, drained at renderItemInHand RETURN
    // (post-Iris-safe) under the same captured hand modelview (HELD_FP_MV) as the glow FP ring.
    private static final List<ChromaJob> chromaticFpJobs = new ArrayList<>();
    // GUI / inventory / HUD icon chromatic items: captured at ItemRenderer.render RETURN, drained at
    // GuiGraphics.flush() RETURN (GuiGraphicsMixin) while the GUI ortho matrices are still live.
    private static final List<ChromaJob> chromaticGuiJobs = new ArrayList<>();

    /** Drain the GUI chromatic icons: render into the target under the live GUI ortho matrices (no scene
     *  occlusion, the GUI is 2D and icons don't overlap) and blit. Called at {@code GuiGraphics.flush} RETURN,
     *  the same point the glow GUI ring drains, so the ortho projection / modelview match what the icons drew
     *  under. */
    public static void drainChromaticGui() { guarded("drainChromaticGui", GlowOutlineRenderer::drainChromaticGuiUnguarded); }

    private static void drainChromaticGuiUnguarded() {
        if (chromaticGuiJobs.isEmpty()) return;
        accumulateChromaJobs(chromaticGuiJobs, false);
        blitChromaticTarget();
        chromaticGuiJobs.clear();
    }

    /** Queue a first-person hand chromatic ITEM (captured baked quads). Snapshots the hand modelview like the
     *  glow FP path so the drain can re-establish it under a shader pack. */
    public static void queueChromaticItemFp(float[] data, int len, CustomGlint.Data glint, int layerIdx) {
        queueChroma(data, len, TextureAtlas.LOCATION_BLOCKS, glint, layerIdx, false, true, false, DEST_FP, 1.0f);
    }

    /** Drain the first-person hand chromatic items. Called at {@code GameRenderer.renderItemInHand} RETURN
     *  (post-Iris-safe), BEFORE {@link #drainHeldFp} clears the shared FP matrix snapshot. The FP item is
     *  foreground so no scene occlusion; re-establish the captured hand modelview under a pack exactly like
     *  drainHeldFp. Reuses the (already world-composited) chromatic target, re-cleared for the hand items. */
    public static void drainChromaticFp() { guarded("drainChromaticFp", GlowOutlineRenderer::drainChromaticFpUnguarded); }

    private static void drainChromaticFpUnguarded() {
        if (chromaticFpJobs.isEmpty()) return;
        if (heldFpMatricesValid && CustomGlintRenderer.isShaderPackActive()) {
            Matrix4fStack mv = RenderSystem.getModelViewStack();
            mv.pushMatrix();
            mv.set(HELD_FP_MV);
            RenderSystem.applyModelViewMatrix();
            try {
                accumulateChromaJobs(chromaticFpJobs, false);
                blitChromaticTarget();
            } finally {
                mv.popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
        } else {
            accumulateChromaJobs(chromaticFpJobs, false);
            blitChromaticTarget();
        }
        chromaticFpJobs.clear();
    }

    /** Accumulate the silhouettes of {@code jobs}+{@code models} into the mask and compute each object's
     *  screen box into {@code outBoxes}. Must run while the world matrices are live (the silhouette RT draws
     *  through {@code ProjMat*ModelViewMat} and {@link SilhouetteConsumer} projects each vertex). Returns the
     *  composite kernel radius (the widest category present). Leaves the mask bound for writing. */
    private static int accumulate(List<ItemJob> jobs, List<ModelJob> models, RenderTarget main, List<Box> outBoxes,
                                  TextureTarget mask, boolean sceneOcclusion) {
        beginAccumulation(main.width, main.height);

        int searchRadius = 1;
        for (ItemJob j : jobs) searchRadius = Math.max(searchRadius, CAT_THICKNESS[j.category]);
        for (ModelJob j : models) searchRadius = Math.max(searchRadius, CAT_THICKNESS[j.category]);

        // Force the depth/colour write masks on before clearing: glClear honours the live GL write masks,
        // and the world render phase (AFTER_WEATHER, after weather/translucent) leaves depthMask=false.
        // With it false the mask's depth clear is a silent no-op, so a freshly recreated target (e.g. right
        // after a window resize) keeps its undefined ~0 depth, the silhouette's LEQUAL test then drops every
        // fragment, and the outline never appears in third person until another phase (the first-person hand
        // pass, where depthMask is true) clears it for us.
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);
        mask.clear(Minecraft.ON_OSX);
        mask.bindWrite(true);
        // Bind the main depth texture to sampler unit 1 so the silhouette shader can occlude fragments
        // that are behind the scene. Safe to read here: we're writing the MASK, not the main target.
        // (shaderTextures[1] persists across both silhouette RTs' draws; the RT setup only touches unit 0.)
        // sceneOcclusion=false leaves it unbound (unit 1 = 0) for the always-foreground first-person held
        // item: under a shader pack the hand item's own imprecise depth would otherwise occlude its own ring,
        // flipping in/out per frame (flicker) and shifting as the scene depth changes while you run.
        RenderSystem.setShaderTexture(1, sceneOcclusion ? main.getDepthTextureId() : 0);
        // Under a shader pack the reconstructed scene distance is imprecise at grazing edges and the error
        // grows with distance, so the per-fragment occlusion flips in/out each frame (the dragon "inner-scale"
        // flicker). Give the occlusion a distance-scaled tolerance under a pack so grazing edges stay visible
        // (no flickering holes) while a real wall (a large depth gap) still occludes. 0 off-pack (unchanged).
        if (uSilBiasScale != null) uSilBiasScale.set(CustomGlintRenderer.isShaderPackActive() ? 0.03f : 0.0f);
        outBoxes.clear();

        VertexConsumer base = MASK_BUFFERS.getBuffer(silhouetteRT());
        for (ItemJob job : jobs) {
            resetCamBox();
            int key = nextGlowKey(job.category);
            SilhouetteConsumer sc = new SilhouetteConsumer(base, job.color, key);
            for (BakedQuad quad : job.quads) {
                sc.putBulkData(job.pose, quad, 1.0f, 1.0f, 1.0f, 1.0f, job.light, OverlayTexture.NO_OVERLAY);
            }
            int[] box = computeScissor(main.width, main.height, CAT_THICKNESS[job.category]);
            if (box != null) { float d = computeDist(); outBoxes.add(new Box(box[0], box[1], box[2], box[3], computeScale(d), key & 31, d, 0, 1.0f)); }
        }

        // Model silhouettes (special / 3D BEWLR items, armor pieces, entity bodies): each traces its real
        // shape against its own texture, so switch the bound silhouette RT per texture (the immediate buffer
        // flushes the prior batch on each switch). The per-job key (from glowKeyFor / the item capture) makes
        // a figure's body + armor (or a multi-texture item's buckets) share one id → one unified ring.
        for (ModelJob job : models) {
            resetCamBox();
            VertexConsumer texBuf = MASK_BUFFERS.getBuffer(
                    job.triangles ? silhouetteTexTriangleRT(job.tex) : silhouetteTexRT(job.tex));
            SilhouetteConsumer sc = new SilhouetteConsumer(texBuf, job.color, job.key);
            emitModel(sc, job);
            int[] box = computeScissor(main.width, main.height, CAT_THICKNESS[job.category]);
            if (box != null) { float d = computeDist(); outBoxes.add(new Box(box[0], box[1], box[2], box[3], computeScale(d), job.key & 31, d, job.priority, 1.0f)); }
        }
        MASK_BUFFERS.endBatch();
        // Don't leave the (per-frame, resize-volatile) main depth texture bound to unit 1.
        RenderSystem.setShaderTexture(1, 0);
        return searchRadius;
    }

    /** Composite once per OUTLINE ID, FAR→NEAR. Each pass rings only its own id's silhouette and is occluded
     *  where a nearer silhouette covers it; drawing near LAST makes a nearer object's whole outline layer
     *  cleanly over a farther one's (elytra over helmet). Matrix-independent (fullscreen blit), safe to run
     *  at a later phase than {@link #accumulate} (the deferred under-pack path). Rebinds the main target even
     *  when there are no on-screen boxes so the offscreen mask doesn't stay bound for whatever renders next. */
    private static void composite(RenderTarget main, List<Box> boxes, int searchRadius, float projA, float projB,
                                  TextureTarget mask, int edgeBleed) {
        if (!boxes.isEmpty()) {
            groupById(boxes);                       // merge same-id boxes; distinct ids stay separate
            // Lower priority first, then far→near, so the nearer object is drawn on top within a priority
            // and a high-priority worn layer (the elytra) always draws LAST over the body/armor it covers,
            // regardless of which is technically nearer at the seam.
            boxes.sort((a, b) -> a.prio != b.prio ? Integer.compare(a.prio, b.prio)
                                                  : Float.compare(b.dist, a.dist));
            compositeBegin(main, searchRadius, true, false, projA, projB, mask, edgeBleed);
            for (int i = 0; i < boxes.size(); i++) {
                Box box = boxes.get(i);
                compositePass(box, box.scale, box.id);
            }
            compositeEnd();
        } else {
            main.bindWrite(true);
        }
    }

    /** Replay a captured model's [x,y,z,u,v] vertices (camera-relative, QUADS order) into the texture-bound
     *  silhouette buffer. The real UVs drive the shader's alpha-discard against the model texture, so the
     *  silhouette follows the real armor / body shape (not a box). */
    private static void emitModel(SilhouetteConsumer sc, ModelJob job) {
        float[] d = job.data;
        for (int i = 0; i + 4 < job.len; i += 5) {
            sc.addVertex(d[i], d[i + 1], d[i + 2]);
            sc.setColor(255, 255, 255, 255); // overridden to glow colour + key by SilhouetteConsumer
            sc.setUv(d[i + 3], d[i + 4]);
            sc.setUv1(0, 0);                 // overlay
            sc.setUv2(0, 0);                 // light (unused by the silhouette shader)
            sc.setNormal(0.0f, 1.0f, 0.0f);
        }
    }

    // The composite is split into begin / per-pass / end so the GL state + per-drain-constant uniforms
    // (blend, shader, mask textures, SearchRadius, ProjA/ProjB, GuiMode) are issued ONCE per drain rather
    // than re-issued for every id pass. Nothing between passes resets that state, so this is pixel-identical;
    // it just removes ~15 GL/uniform calls per pass, which adds up with many glowing objects on screen.

    /** Set up render state + per-drain-constant uniforms for a run of {@link #compositePass} calls.
     *  {@code projA}/{@code projB} are the perspective depth-linearisation terms ({@code ProjMat.m22/m32}) of
     *  the projection the silhouettes were accumulated under, passed in (not read live) so the deferred
     *  under-Iris composite, which runs at {@code renderLevel} TAIL with a different live projection, still
     *  linearises ring-occlusion depth with the world projection. Ignored when {@code ringOcclusion} is off. */
    private static void compositeBegin(RenderTarget main, int searchRadius, boolean ringOcclusion, boolean guiMode,
                                       float projA, float projB, TextureTarget mask, int edgeBleed) {
        main.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,       GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(() -> compositeShader);
        RenderSystem.setShaderTexture(0, mask.getColorTextureId());
        // Mask depth -> unit 1 for ring occlusion (mask is a separate target from the main colour we write,
        // so no feedback). ProjA/ProjB linearise it the same way the silhouette pass projected depth. Under
        // the GUI's ORTHOGRAPHIC projection that perspective-style linearisation is meaningless and would
        // make the occlusion test reject every ring pixel, so the GUI drain passes ringOcclusion=false:
        // unit 1 reads texture 0 (depth 0 everywhere) -> ringDist==srcDist -> nothing is occluded.
        RenderSystem.setShaderTexture(1, ringOcclusion ? mask.getDepthTextureId() : 0);
        if (uSearchRadius != null) uSearchRadius.set(searchRadius);
        if (uProjA != null) uProjA.set(projA);
        if (uProjB != null) uProjB.set(projB);
        // GUI mode: square (Chebyshev) reach = SearchRadius texels (a sub-icon-pixel thickness), no
        // morphological-opening guard. World mode (0): round per-category ring + opening guard.
        if (uGuiMode != null) uGuiMode.set(guiMode ? 1 : 0);
        compositeEdgeBleed = edgeBleed; // applied per-pass, scaled by the pass's distance factor
    }

    private static int compositeEdgeBleed;

    /** One scissored composite pass ringing a single outline id ({@code targetId}). */
    private static void compositePass(Box box, float thicknessScale, int targetId) {
        if (box != null) RenderSystem.enableScissor(box.x, box.y, box.w, box.h);
        if (uThicknessScale != null) uThicknessScale.set(thicknessScale);
        if (uTargetId != null) uTargetId.set(targetId);
        // Scale the inward edge-bleed by the SAME per-object distance factor as the rest of the ring, so the
        // gap-closing bleed thins out with distance instead of staying a fixed texel and reading as a fatter
        // ring on a small far object. (The hairline gap it closes is invisible at distance anyway.)
        if (uEdgeBleed != null) uEdgeBleed.set(Math.round(compositeEdgeBleed * thicknessScale));

        drawFullscreenQuad();

        if (box != null) RenderSystem.disableScissor();
    }

    /** Unit-square quad in clip space, the geometry every fullscreen blit here draws through (the composite
     *  pass and the chromatic screen blit). The bound shader supplies the transform. */
    private static void drawFullscreenQuad() {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.addVertex(0.0f, 0.0f, 0.0f).setUv(0.0f, 0.0f);
        bb.addVertex(1.0f, 0.0f, 0.0f).setUv(1.0f, 0.0f);
        bb.addVertex(1.0f, 1.0f, 0.0f).setUv(1.0f, 1.0f);
        bb.addVertex(0.0f, 1.0f, 0.0f).setUv(0.0f, 1.0f);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    /** Restore render state after a run of {@link #compositePass} calls. */
    private static void compositeEnd() {
        RenderSystem.setShaderTexture(1, 0); // don't leak the mask depth binding to later rendering
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /** Padded screen-space scissor box (GL bottom-left origin, pixels) from the per-vertex {@code screenBox}
     *  tracked during accumulation. Returns null when nothing projected in front of the camera. No near-plane
     *  fullscreen fallback: the drawn vertices are all in front, so the box is always tight; a close /
     *  first-person item no longer blows the radius-7 kernel up to the whole screen. */
    private static int[] computeScissor(int width, int height, int searchRadius) {
        if (screenBox[0] > screenBox[2]) return null; // nothing projected in front of the camera
        int pad = searchRadius + SCISSOR_MARGIN;
        int x0 = Math.max(0,      (int) Math.floor(screenBox[0]) - pad);
        int y0 = Math.max(0,      (int) Math.floor(screenBox[1]) - pad);
        int x1 = Math.min(width,  (int) Math.ceil(screenBox[2])  + pad);
        int y1 = Math.min(height, (int) Math.ceil(screenBox[3])  + pad);
        if (x1 <= x0 || y1 <= y0) return null;
        return new int[]{ x0, y0, x1 - x0, y1 - y0 };
    }

    /** GUI variant of {@link #computeScissor}. {@code anchor} (from {@code ItemRendererMixin#cg_guiAnchor})
     *  carries the icon's GUI-space slot centre {@code [x,y,z]} and its nominal half-size in GUI px
     *  {@code [3]} = ½ the render-pose x-axis scale (8 for a 16-px inventory slot, 40 for the 5x wand
     *  preview). Both the ring thickness and the slot clamp are sized off that REAL on-screen icon, not the
     *  silhouette footprint (which a 3D BEWLR overflows) and not a render-order flag (the preview's
     *  GuiGraphics.renderItem self-flushes before any opt-in could be set). Returns null when nothing
     *  accumulated / off-screen. dist/prio are unused in the GUI. */
    private static Box computeGuiBox(int width, int height, int id, boolean is3d, float[] anchor) {
        if (screenBox[0] > screenBox[2]) return null; // nothing accumulated
        float minX = screenBox[0], minY = screenBox[1], maxX = screenBox[2], maxY = screenBox[3];
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();

        // Icon centre (framebuffer px) + nominal half-size (framebuffer px), from the projected anchor.
        // Fall back to the silhouette box if the anchor is missing or projects behind the near plane.
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f;
        float halfFb = Math.max((maxX - minX), (maxY - minY)) * 0.5f;
        if (anchor != null) {
            halfFb = anchor[3] * guiScale; // GUI px → framebuffer px (GUI ortho scales by guiScale)
            Vector4f p = SCRATCH_V.set(anchor[0], anchor[1], anchor[2], 1.0f);
            ACC_MVP.transform(p);
            if (p.w > 1.0e-4f) {
                cx = (p.x / p.w * 0.5f + 0.5f) * ACC_W;
                cy = (p.y / p.w * 0.5f + 0.5f) * ACC_H;
            }
        }

        // Ring thickness scales with one TEXTURE pixel of the item, not a fixed 1/16: the icon is
        // (2*halfFb) framebuffer texels wide spanning texRes texture pixels, so a texture pixel is
        // (2*halfFb / texRes) texels. This keeps the ring the same relative width on a 16x16 icon AND a
        // 32x32 / 64x64 high-res item (which otherwise rings 2x / 4x too thick). texRes defaults to 16, so
        // a normal item is unchanged (2*halfFb/16 == halfFb/8). Floored at 1 texel so a high-res ring at a
        // small gui scale stays visible rather than rounding away.
        float texRes = anchor != null && anchor.length > 4 ? anchor[4] : 16.0f;
        float pxPerTexPx = Math.max(2.0f * halfFb / texRes, 1.0f);
        float reach = Math.max(1.0f, Math.round(GUI_RING_ITEM_PIXELS * pxPerTexPx));

        // 3D BEWLR icons get a small OUTWARD pad so the ring wraps the item; flat sprites trace their own
        // edge (pad 0, unchanged). The slot clamp below keeps the padded ring inside the icon.
        int pad = is3d ? (int) Math.ceil(reach) : 0;
        int x0 = Math.max(0,      (int) Math.floor(minX) - pad);
        int y0 = Math.max(0,      (int) Math.floor(minY) - pad);
        int x1 = Math.min(width,  (int) Math.ceil(maxX)  + pad);
        int y1 = Math.min(height, (int) Math.ceil(maxY)  + pad);

        // Clamp the ring to the icon's nominal square (centred on the true anchor). A 3D BEWLR whose model
        // projects past its slot can't ring outside the icon; in the big wand preview the nominal square is
        // the 5x recess, so the ring still wraps the whole item. A no-op for a flat sprite that already
        // fills its slot.
        x0 = Math.max(x0, (int) Math.floor(cx - halfFb));
        y0 = Math.max(y0, (int) Math.floor(cy - halfFb));
        x1 = Math.min(x1, (int) Math.ceil(cx + halfFb));
        y1 = Math.min(y1, (int) Math.ceil(cy + halfFb));
        if (x1 <= x0 || y1 <= y0) return null;
        // snap carries the per-icon ring reach (texels); drainGui sets the pass's ThicknessScale = reach /
        // SearchRadius so each icon rings to its OWN reach (the shader limits the GUI kernel per pass).
        return new Box(x0, y0, x1 - x0, y1 - y0, 1.0f, id, 0.0f, 0, reach);
    }

    /** Intersects a ring box with a saved GL scissor box ({@code [x,y,w,h]}, bottom-left); null if empty.
     *  Used to also honour the GUI's own active scissor (e.g. the wand-editor preview's box, a scroll panel)
     *  so the ring can't draw past it onto the surrounding GUI. */
    private static Box intersectBox(Box b, int[] s) {
        int x0 = Math.max(b.x, s[0]);
        int y0 = Math.max(b.y, s[1]);
        int x1 = Math.min(b.x + b.w, s[0] + s[2]);
        int y1 = Math.min(b.y + b.h, s[1] + s[3]);
        if (x1 <= x0 || y1 <= y0) return null;
        return new Box(x0, y0, x1 - x0, y1 - y0, b.scale, b.id, b.dist, b.prio, b.snap);
    }

    /** Groups boxes by outline id in-place: same-id boxes (a figure's body + all its armor pieces) union
     *  into one box (union geometry, MAX scale = nearest thickness, MIN dist = nearest so it draws on top),
     *  but DISTINCT ids stay separate so the per-id far→near passes can layer them. O(n^2), n small. */
    private static void groupById(List<Box> boxes) {
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    if (boxes.get(i).id == boxes.get(j).id) {
                        boxes.set(i, unionBox(boxes.get(i), boxes.get(j)));
                        boxes.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
        }
    }

    private static Box unionBox(Box a, Box b) {
        int x0 = Math.min(a.x, b.x), y0 = Math.min(a.y, b.y);
        int x1 = Math.max(a.x + a.w, b.x + b.w), y1 = Math.max(a.y + a.h, b.y + b.h);
        return new Box(x0, y0, x1 - x0, y1 - y0, Math.max(a.scale, b.scale), a.id, Math.min(a.dist, b.dist), Math.max(a.prio, b.prio), a.snap);
    }

    // ── Silhouette vertex consumer ─────────────────────────────────────────────
    // Wraps the mask buffer and forces every vertex colour to (glow rgb, key) so the silhouette shader
    // receives the glow colour in rgb and the per-object key (1..127) in alpha. Position / uv / overlay /
    // light / normal pass through unchanged (the shader uses only Position, UV0 and the forced Color).

    private static final class SilhouetteConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final int r, g, b, key;

        /** {@code color} is the packed 0xRRGGBB glow colour; alpha carries the outline {@code key} instead. */
        SilhouetteConsumer(VertexConsumer delegate, int color, int key) {
            this.delegate = delegate;
            this.r = (color >> 16) & 0xFF;
            this.g = (color >> 8) & 0xFF;
            this.b = color & 0xFF;
            this.key = key;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            // Camera-relative AABB (drives distance thinning via computeDist).
            if (x < camBox[0]) camBox[0] = x;
            if (y < camBox[1]) camBox[1] = y;
            if (z < camBox[2]) camBox[2] = z;
            if (x > camBox[3]) camBox[3] = x;
            if (y > camBox[4]) camBox[4] = y;
            if (z > camBox[5]) camBox[5] = z;
            // Exact screen bounds (drives the composite scissor). Projecting the ACTUAL drawn vertex is stable
            // across the near plane, unlike projecting the synthetic AABB corners.
            Vector4f p = SCRATCH_V.set(x, y, z, 1.0f);
            ACC_MVP.transform(p);
            if (p.w > 1.0e-4f) {
                float sx = (p.x / p.w * 0.5f + 0.5f) * ACC_W;
                float sy = (p.y / p.w * 0.5f + 0.5f) * ACC_H;
                if (sx < screenBox[0]) screenBox[0] = sx;
                if (sy < screenBox[1]) screenBox[1] = sy;
                if (sx > screenBox[2]) screenBox[2] = sx;
                if (sy > screenBox[3]) screenBox[3] = sy;
            }
            delegate.addVertex(x, y, z);
            return this;
        }
        // Arguments deliberately discarded: the mask carries the glow colour + outline key, never the
        // model's own vertex colour.
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { delegate.setColor(r, g, b, key); return this; }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float nx, float ny, float nz) { delegate.setNormal(nx, ny, nz); return this; }
    }

    // ── Special-item capture (tee) ─────────────────────────────────────────────
    // Wraps the MultiBufferSource handed to a BEWLR's renderByItem (trident, shield, IaF/EK custom renderers)
    // so a glowing item's animated geometry is recorded as it draws. Each vertex's already-transformed
    // (camera-relative) [x,y,z,u,v] is recorded into a bucket keyed by the texture of the RenderType it drew
    // through (resolved reflectively), so the silhouette can alpha-discard against that texture and trace the
    // REAL item shape instead of white-filling the whole model hull (which read as a square / outlined
    // no-texture model parts). Textureless RTs fall back to WHITE_TEXTURE = full fill. Drawing the same shape
    // through several glint-layer buffers records it more than once, which is harmless: the mask write is
    // keyed, not additive. Buckets are queued (via queueGroups → addItemModelJob) and replayed at drain: no
    // re-pose, the animation already lives in the positions.

    public static final class CapturingBufferSource implements MultiBufferSource {
        private final MultiBufferSource delegate; // null = record-only (the item already drew elsewhere)
        // Per-texture vertex buckets ([x,y,z,u,v] each), so each is traced against its own texture's alpha.
        private final Map<ResourceLocation, float[]> data = new LinkedHashMap<>();
        private final Map<ResourceLocation, Integer> counts = new HashMap<>();

        public CapturingBufferSource(MultiBufferSource delegate) { this.delegate = delegate; }

        /** Queue every captured bucket as a textured item silhouette (alpha-discard against its texture),
         *  routed to the world / FP / GUI drain by {@code ctx}. {@code anchor} is the GUI-space slot centre
         *  (used only for the GUI drain's slot clamp; null/ignored otherwise). */
        public void queueGroups(int color, ItemDisplayContext ctx, float[] anchor) {
            int key = nextGlowKey(itemCategory(ctx)); // one id for the whole item → no seam between textures
            for (Map.Entry<ResourceLocation, float[]> e : data.entrySet()) {
                addItemModelJob(e.getValue(), counts.get(e.getKey()), e.getKey(), color, ctx, key, anchor);
            }
        }

        /** Queue every captured bucket as a CHROMATIC overlay job (special / 3D BEWLR item, shader-pack path):
         *  each texture bucket traces its own texture's alpha as the cutout, per chromatic layer. World items
         *  drain at renderLevel TAIL; a first-person-hand item drains at the hand pass. GUI special items are
         *  deferred (not routed here). */
        public void queueChromaticGroups(CustomGlint.Data glint, ItemDisplayContext ctx) {
            boolean gui = ctx == ItemDisplayContext.GUI;
            boolean fp = isFpContext(ctx);
            CustomGlint.Layer[] layers = glint.layers();
            for (int li = 0; li < layers.length; li++) {
                if (!CustomGlint.isChromatic(layers[li])) continue;
                for (Map.Entry<ResourceLocation, float[]> e : data.entrySet()) {
                    // Special 3D items trace their OWN texture at the denser special-item scale, in whichever
                    // context (world / hand / GUI) so the icon matches the world/hand item.
                    int dest = gui ? DEST_GUI : (fp ? DEST_FP : DEST_WORLD);
                    queueChromaticSpecial(e.getValue(), counts.get(e.getKey()), e.getKey(), glint, li, dest);
                }
            }
        }

        /** Queue every captured bucket as a world entity-surface silhouette under {@code identity}'s shared
         *  CAT_ENTITY id, so a block-rendered surface (a mooshroom's mushrooms) merges into the entity's body
         *  ring. Drained with the world outlines at AFTER_WEATHER. */
        public void queueEntitySurface(int color, Object identity) {
            int key = glowKeyFor(identity, CAT_ENTITY);
            for (Map.Entry<ResourceLocation, float[]> e : data.entrySet()) {
                queueModelOutline(e.getValue(), counts.get(e.getKey()), e.getKey(), color, key, CAT_ENTITY, 0);
            }
        }

        @Override public VertexConsumer getBuffer(RenderType renderType) {
            ResourceLocation tex = resolveRenderTypeTexture(renderType);
            // 1024 floats = ~204 vertices to start; record() doubles the bucket when a model overruns it.
            data.computeIfAbsent(tex, k -> new float[1024]);
            counts.putIfAbsent(tex, 0);
            return new RecordingConsumer(tex, delegate == null ? null : delegate.getBuffer(renderType));
        }

        private void record(ResourceLocation tex, float x, float y, float z, float u, float v) {
            float[] buf = data.get(tex);
            int c = counts.get(tex);
            if (c + 5 > buf.length) { buf = Arrays.copyOf(buf, buf.length * 2); data.put(tex, buf); }
            buf[c++] = x; buf[c++] = y; buf[c++] = z; buf[c++] = u; buf[c++] = v;
            counts.put(tex, c);
        }

        private final class RecordingConsumer implements VertexConsumer {
            private final ResourceLocation tex;
            private final VertexConsumer d; // null = no real draw, record only
            private float px, py, pz;       // stash position until setUv flushes the 5-tuple
            RecordingConsumer(ResourceLocation tex, VertexConsumer d) { this.tex = tex; this.d = d; }
            @Override public VertexConsumer addVertex(float x, float y, float z) { px = x; py = y; pz = z; if (d != null) d.addVertex(x, y, z); return this; }
            @Override public VertexConsumer setUv(float u, float v) { record(tex, px, py, pz, u, v); if (d != null) d.setUv(u, v); return this; }
            @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { if (d != null) d.setColor(red, green, blue, alpha); return this; }
            @Override public VertexConsumer setUv1(int u, int v) { if (d != null) d.setUv1(u, v); return this; }
            @Override public VertexConsumer setUv2(int u, int v) { if (d != null) d.setUv2(u, v); return this; }
            @Override public VertexConsumer setNormal(float nx, float ny, float nz) { if (d != null) d.setNormal(nx, ny, nz); return this; }
        }
    }
}
