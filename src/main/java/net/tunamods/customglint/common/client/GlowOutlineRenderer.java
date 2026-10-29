package net.tunamods.customglint.common.client;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.mixin.CompositeRenderTypeAccessor;
import net.tunamods.customglint.common.mixin.CompositeStateAccessor;
import net.tunamods.customglint.common.mixin.TextureStateShardAccessor;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-process glow-outline backend (1.20.1). Captures glowing items' silhouettes into an offscreen
 * mask target, then runs one fullscreen id-aware dilation composite that paints a ring over the scene.
 * Client-only; reached from {@link CustomGlintClientInit}. Built on 1.20.1 Blaze3D primitives
 * ({@link ShaderInstance} core shaders, a {@link TextureTarget} mask, a manual {@link Tesselator}
 * fullscreen blit).
 *
 * <p>Coverage is full across the render surfaces: world items (third-person held, dropped, item
 * frames, other players) drained at {@code RenderLevelStageEvent.AFTER_WEATHER} where the live world
 * projection / modelview match what the items were drawn with ({@link #drainWorld}); the shader-pack
 * path captures a projection snapshot and drains separately ({@link #drainWorldShaderPack}); the GUI /
 * inventory / HUD path renders under the live ortho matrices ({@link #drainGui}, {@link #queueGuiItem});
 * the first-person hand ({@link #drainHeldFp}, {@link #queueHeldFpItem}); special BEWLR models via the
 * recording {@code CapturingBufferSource}; and armor / entity model silhouettes ({@link #queueModelOutline},
 * driven from {@code EntityGlintRender}).
 *
 * <p><b>Occlusion</b>: the silhouette pass occludes against the scene: it binds the main depth texture
 * to sampler unit 1 (safe, because that pass writes the offscreen MASK, not the main target) and
 * discards silhouette fragments behind world geometry, so a world item behind a wall doesn't ring.
 */
public final class GlowOutlineRenderer extends RenderStateShard {

    private static final Logger LOGGER = LogUtils.getLogger();

    private GlowOutlineRenderer() { super("", () -> {}, () -> {}); }

    // Outline categories + per-object id keys
    // key = (category << 5) | id : top 2 bits = category, low 5 = a running 1..31 id. Stamped into the
    // silhouette vertex-colour alpha so the composite keeps each object's ring separate and picks a
    // per-category thickness (see glow_composite.fsh THICKNESS[]).
    public static final int CAT_ENTITY = 0, CAT_ARMOR = 1, CAT_ITEM = 2, CAT_HELD_FP = 3;

    // Per-category ring thickness in texels. MUST mirror glow_composite.fsh THICKNESS[].
    private static final int[] CAT_THICKNESS = { 4, 4, 3, 7 };

    private static int glowIdCounter = 0;

    private static int nextGlowId() { glowIdCounter = (glowIdCounter % 31) + 1; return glowIdCounter; }

    public static int nextGlowKey(int category) { return (category << 5) | nextGlowId(); }

    // Per-identity outline id
    // One id per logical figure (an entity instance), SHARED by its body and all its surface layers / worn
    // armor so they compose as ONE ring. The composite compares only the low-5-bit id (key & 31) for "same
    // object → no internal seam" and reads the per-category thickness from the high bits, so body
    // (CAT_ENTITY) and armor (CAT_ARMOR) of one figure merge into one ring while staying distinct from other
    // figures. Reset each frame in beginFrame().
    private static final Map<Object, Integer> glowIdByIdentity = new IdentityHashMap<>();

    public static int glowKeyFor(Object identity, int category) {
        int id = glowIdByIdentity.computeIfAbsent(identity, k -> nextGlowId());
        return (category << 5) | id;
    }

    // Shaders

    private static ShaderInstance silhouetteShader;
    private static ShaderInstance compositeShader;

    private static Uniform uSearchRadius, uThicknessScale, uProjA, uProjB,
            uTargetId, uGuiMode;
    private static Uniform uSilBiasScale;

    /** Mod-event-bus listener; registered from {@link CustomGlintClientInit#run}. The
     *  {@code "vertex"}/{@code "fragment"} program names inside each core-shader JSON must be namespaced
     *  ({@code "customglint:glow_silhouette"}); a bare name defaults to {@code minecraft}. */
    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("glow_silhouette"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> {
                        silhouetteShader = shader;
                        uSilBiasScale = shader.getUniform("OcclusionBiasScale");
                    });
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("glow_composite"),
                            DefaultVertexFormat.POSITION_TEX),
                    shader -> {
                        compositeShader = shader;
                        uSearchRadius   = shader.getUniform("SearchRadius");
                        uThicknessScale = shader.getUniform("ThicknessScale");
                        uProjA          = shader.getUniform("ProjA");
                        uProjB          = shader.getUniform("ProjB");
                        uTargetId       = shader.getUniform("TargetId");
                        uGuiMode        = shader.getUniform("GuiMode");
                    });
        } catch (Exception e) {
            LOGGER.error("[{}] failed to register glow-outline shaders", MOD_ID, e);
        }
    }

    // Offscreen mask target

    private static TextureTarget maskTarget;

    private static void ensureTarget(int width, int height) {
        if (maskTarget != null && (maskTarget.width != width || maskTarget.height != height)) {
            maskTarget.destroyBuffers();
            maskTarget = null;
        }
        if (maskTarget == null) {
            maskTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            maskTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            maskTarget.setFilterMode(GL11.GL_NEAREST);
        }
    }

    /** Force the main render target's ALPHA channel to fully opaque (1.0), leaving RGB untouched.
     *
     *  <p>EnhancedVisuals' blood splatters ({@code VisualTypeParticle} → {@code position_tex_col_smooth},
     *  a NON-separate {@code srcalpha/1-srcalpha} blend) apply that blend to the ALPHA channel as well, so
     *  each decal drives the framebuffer alpha under its quad BELOW 1. On a window whose framebuffer is
     *  alpha-composited that reduced alpha presents as an opaque BLACK box behind each splatter (the RGB is
     *  correct: an F2 screenshot, which samples main RGB, looks clean; only the composited window shows the
     *  boxes). A masked clear (glClear honours the colour write mask) writes alpha=1 everywhere without
     *  touching colour, so the window is opaque again. Cheap; run once at the end of the HUD frame. */
    public static void forceMainAlphaOpaque() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        main.bindWrite(true);
        RenderSystem.colorMask(false, false, false, true);
        RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 1.0f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.colorMask(true, true, true, true);
    }

    /** Released on resource reload (registered via {@code CustomGlintRenderer.additionalReloadCleanup}). */
    public static void release() {
        if (maskTarget != null) { maskTarget.destroyBuffers(); maskTarget = null; }
        texSilhouetteRTs.clear();
        texSilhouetteTriRTs.clear();
        rtTextureCache.clear();
    }

    // Silhouette RenderType
    // NEW_ENTITY so putBulkData writes naturally; the silhouette shader reads only Position/Color/UV0.
    // NO_CULL solid union, LEQUAL depth (front-most fragment wins), no blend; occlusion is done in-shader
    // against the scene depth (Sampler1, bound by the drain just before the flush). The shader
    // alpha-discards against the bound texture so the mask traces the real shape.
    private static RenderType buildSilhouetteRT(String name, ResourceLocation tex) {
        return buildSilhouetteRT(name, tex, VertexFormat.Mode.QUADS);
    }

    // Mode-parameterised variant. Epic Fight (and any renderer that draws through a TRIANGLES-mode
    // RenderType) pushes a triangle-list vertex stream; replaying that stream through a QUADS buffer
    // scrambles the topology, so its silhouette is traced through a TRIANGLES-mode RT instead. The
    // silhouette shader reads only Position/Color/UV0, so the primitive mode is the only thing that
    // has to match the captured stream.
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
                        .setCullState(NO_CULL)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setWriteMaskState(COLOR_DEPTH_WRITE)
                        .createCompositeState(false));
    }

    // Item sprites live in the block atlas, so flat/3D baked items trace their shape against it.
    private static RenderType silhouetteRT;

    private static RenderType silhouetteRT() {
        if (silhouetteRT == null) silhouetteRT = buildSilhouetteRT("customglint:glow_silhouette", TextureAtlas.LOCATION_BLOCKS);
        return silhouetteRT;
    }

    // Fallback for a special-item RenderType whose own texture couldn't be resolved: opaque vanilla white
    // (alpha test always passes) → the whole model hull fills.
    private static final ResourceLocation WHITE_TEXTURE = new ResourceLocation("minecraft", "textures/misc/white.png");

    // Per-texture silhouette RTs for special / 3D BEWLR items, whose ring must follow the REAL shape via
    // the item texture's alpha. Cached by texture; cleared on resource reload.
    private static final Map<ResourceLocation, RenderType> texSilhouetteRTs = new HashMap<>();

    private static RenderType silhouetteTexRT(ResourceLocation tex) {
        return texSilhouetteRTs.computeIfAbsent(tex,
                t -> buildSilhouetteRT("customglint:glow_silhouette_tex_" + t, t));
    }

    // TRIANGLES-mode counterpart of {@link #silhouetteTexRT}, for triangle-list silhouette captures
    // (Epic Fight patched entity meshes). Cached separately; cleared on resource reload.
    private static final Map<ResourceLocation, RenderType> texSilhouetteTriRTs = new HashMap<>();

    private static RenderType silhouetteTexTriangleRT(ResourceLocation tex) {
        return texSilhouetteTriRTs.computeIfAbsent(tex,
                t -> buildSilhouetteRT("customglint:glow_silhouette_tri_" + t, t, VertexFormat.Mode.TRIANGLES));
    }

    // RenderType → texture resolution (for special-item silhouettes)
    // A BEWLR (trident, shield, modded custom renderers) draws its model through RenderTypes bound to the
    // item's OWN texture, whose alpha is the real shape. Read that texture off the composite state via the
    // accessor mixins; WHITE_TEXTURE (full fill) for non-composite / textureless RTs. Cached per RenderType.
    private static final Map<RenderType, ResourceLocation> rtTextureCache = new IdentityHashMap<>();

    public static ResourceLocation resolveRenderTypeTexture(RenderType rt) {
        ResourceLocation r = rtTextureCache.get(rt);
        if (r != null) return r;
        r = reflectRenderTypeTexture(rt);
        rtTextureCache.put(rt, r);
        return r;
    }

    private static ResourceLocation reflectRenderTypeTexture(RenderType rt) {
        try {
            if (rt instanceof CompositeRenderTypeAccessor crt) {
                RenderType.CompositeState state = crt.customglint$state();
                RenderStateShard.EmptyTextureStateShard texState =
                        ((CompositeStateAccessor) (Object) state)
                                .customglint$textureState();
                if (texState instanceof TextureStateShardAccessor tsa) {
                    return tsa.customglint$cutoutTexture().orElse(WHITE_TEXTURE);
                }
            }
        } catch (Throwable ignored) {
            // Non-composite RT or renamed field → fall back to the white-fill hull.
        }
        return WHITE_TEXTURE;
    }

    private static final BufferBuilder MASK_BUILDER = new BufferBuilder(4096);
    private static final MultiBufferSource.BufferSource MASK_BUFFERS = MultiBufferSource.immediate(MASK_BUILDER);

    // Composite scissor / distance thinning
    // Slack px added beyond the ring reach when padding a projected box, so rounding the float bounds to
    // whole pixels can never clip the outermost ring texel.
    private static final int SCISSOR_MARGIN = 3;
    private static final float[] camBox = new float[6]; // minX,minY,minZ, maxX,maxY,maxZ (camera-relative)

    private static final Matrix4f ACC_MVP = new Matrix4f();
    private static final Vector4f SCRATCH_V = new Vector4f();
    private static int ACC_W, ACC_H;

    // Exact on-screen bounds (GL bottom-left px) of the silhouette currently being accumulated, tracked
    // per drawn vertex (stable across the near plane, unlike projecting synthetic AABB corners).
    private static final float[] screenBox = new float[4]; // minX,minY,maxX,maxY

    // {@code prio} layers worn pieces over the body within the far→near order (unused in world/GUI here,
    // kept for parity); {@code snap} is the per-object ring reach in framebuffer texels. snap=1.0 for world
    // (per-pixel dilation); for GUI icons it is the icon's ring reach so the composite dilates in icon-pixel
    // steps and the ring matches the pixel-art icon instead of a screen-pixel-smooth edge.
    private record Box(int x, int y, int w, int h, float scale, int id, float dist, int prio, float snap) {}
    private static final List<Box> itemBoxes = new ArrayList<>();

    // The world ring thins with distance as REF_DIST/dist, clamped to [MIN_SCALE, 1]. 4 blocks is about
    // arm's length, so a ring never grows past its full thickness on a nearby item; the 0.40 floor keeps a
    // distant ring from thinning below what a pixel can carry and dropping out entirely.
    private static final float REF_DIST = 4.0f;   // blocks
    private static final float MIN_SCALE = 0.40f;

    // GUI ring thickness as a FRACTION of an icon (1/16) pixel, matching the reference GUI outline THICKNESS.
    // The composite reach is this * the icon's on-screen pixel size, in framebuffer texels, so the ring reads
    // as a thin ~1px line rather than a full blocky icon-pixel.
    private static final float GUI_RING_ITEM_PIXELS = 0.6f;

    private static void resetCamBox() {
        camBox[0] = camBox[1] = camBox[2] = Float.POSITIVE_INFINITY;
        camBox[3] = camBox[4] = camBox[5] = Float.NEGATIVE_INFINITY;
        screenBox[0] = screenBox[1] = Float.POSITIVE_INFINITY;
        screenBox[2] = screenBox[3] = Float.NEGATIVE_INFINITY;
    }

    private static void beginAccumulation(int width, int height, Matrix4f modelView, Matrix4f proj) {
        ACC_MVP.set(proj).mul(modelView);
        ACC_W = width;
        ACC_H = height;
    }

    private static float computeDist() {
        if (camBox[0] > camBox[3]) return Float.POSITIVE_INFINITY;
        float cx = (camBox[0] + camBox[3]) * 0.5f;
        float cy = (camBox[1] + camBox[4]) * 0.5f;
        float cz = (camBox[2] + camBox[5]) * 0.5f;
        return (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    private static float computeScale(float dist) {
        return Math.max(MIN_SCALE, Math.min(1.0f, REF_DIST / Math.max(dist, 0.001f)));
    }

    // Capture queue

    // {@code anchor} = the GUI-space slot centre + size (the {@code cg_guiAnchor} tuple); null for
    // world / first-person jobs (they scissor by silhouette bounds, not a slot).
    private record ItemJob(List<BakedQuad> quads, PoseStack.Pose pose, Matrix4f modelView,
                           int light, int color, int category, float[] anchor, int[] scissor) {}

    // Special / 3D BEWLR items: camera-relative {@code [x,y,z,u,v]} per vertex (QUADS order) captured by
    // re-rendering the item into a record-only buffer, traced against {@code tex}. All buckets of one item
    // share a {@code key} so the multi-texture item composes as ONE ring.
    // {@code triangles}: replay the captured stream through a TRIANGLES-mode silhouette RT rather than
    // the default QUADS (set for Epic Fight patched-entity meshes, whose draw is a triangle list).
    private record ModelJob(float[] data, int len, ResourceLocation tex, Matrix4f modelView,
                            int color, int key, int category, float[] anchor, int[] scissor, boolean triangles) {}

    private static final List<ItemJob> worldJobs = new ArrayList<>();
    private static final List<ItemJob> heldFpJobs = new ArrayList<>();
    private static final List<ItemJob> guiJobs = new ArrayList<>();
    private static final List<ModelJob> modelWorldJobs = new ArrayList<>();
    private static final List<ModelJob> modelFpJobs = new ArrayList<>();
    private static final List<ModelJob> modelGuiJobs = new ArrayList<>();

    /** Snapshot the live GL scissor (lower-left, framebuffer pixels) at queue time so a GUI icon's ring can be
     *  clipped to whatever clip was active when the icon drew (wand-preview recess, scroll viewport). The GUI
     *  drain now runs once per context (after the screen's scissor has been popped), so it can no longer read
     *  the icon's clip live. Returns null when no scissor is active (normal slots). */
    private static int[] captureScissor() {
        if (!GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) return null;
        int[] box = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
        return box;
    }

    /** Queue a world-space glowing item (third-person held / dropped / frame / other player). {@code pose}
     *  is the item's camera-relative pose at its {@code ItemRenderer.render} RETURN; {@code modelView} is a
     *  COPY of the live {@code RenderSystem} modelview at that draw point. The silhouette is replayed under
     *  that same modelview at drain. The AFTER_WEATHER live modelview differs (it would otherwise double
     *  the camera transform and offset the ring). */
    public static void queueWorldItem(List<BakedQuad> quads, PoseStack.Pose pose, Matrix4f modelView,
                                      int light, int color) {
        if (!pose.pose().isFinite()) return;
        if (CustomGlintRenderer.isInShadowPass()) return; // don't capture the Iris shadow-map pass
        worldJobs.add(new ItemJob(quads, pose, modelView, light, color, CAT_ITEM, null, null));
    }

    /** Queue a glowing item rendered into a GUI / inventory / HUD slot ({@code ItemDisplayContext.GUI}).
     *  {@code pose} is the reproduced GUI transform, so the silhouette vertices are in GUI screen space; it
     *  is drained once per GUI context (see {@link #drainGui()}) while the GUI ortho ProjMat / ModelView are
     *  still live, projecting the silhouette exactly onto the drawn icon. {@code anchor} is the icon's
     *  GUI-space slot centre + size so the drain can size + clamp the ring to the real icon; the live GL
     *  scissor is captured here so the ring clips to whatever clip was active when the icon drew. */
    public static void queueGuiItem(List<BakedQuad> quads, PoseStack.Pose pose, int light, int color,
                                    float[] anchor) {
        if (!pose.pose().isFinite()) return;
        guiJobs.add(new ItemJob(quads, pose, null, light, color, CAT_ITEM, anchor, captureScissor()));
    }

    // First-person hand pass flag
    // Set across GameRenderer.renderItemInHand (the vanilla first-person hand pass, which nothing cancels).
    // Any item captured while this is true belongs to the FP held queue REGARDLESS of its display context:
    // FP-replacing mods (Punchy, First-Person Model) render the held item with a THIRD_PERSON_*_HAND display
    // context (their 3D arm shows the item third-person-style), so the ctx alone misroutes it to the world
    // queue and the world drain then replays it under the wrong (world) projection → the ring lands off-screen.
    private static final ThreadLocal<Boolean> IN_FP_HAND = ThreadLocal.withInitial(() -> Boolean.FALSE);
    public static void setFpHandPass(boolean v) { if (v) IN_FP_HAND.set(Boolean.TRUE); else IN_FP_HAND.remove(); }
    public static boolean inFpHandPass() { return IN_FP_HAND.get(); }

    /** True when the item currently being drawn belongs to the first-person hand: either our own hand-pass flag
     *  is armed (vanilla / Punchy / FPM off-pack), or Iris is in its HAND phase (the hand is drawn there under a
     *  shader pack, outside renderItemInHand/renderHandsWithItems, so the flag never arms). */
    public static boolean isFpHand() { return inFpHandPass() || CustomGlintRenderer.isShaderHandPass(); }

    /** Queue a first-person held item. {@code pose} is the item's pose at its render RETURN inside
     *  {@code renderHandsWithItems}; {@code modelView} is a copy of the live modelview there. Drained at
     *  the RETURN of {@code renderHandsWithItems} (see {@link #drainHeldFp()}) under the live hand-FOV
     *  projection, so it composites over the already-flushed hand items. CAT_HELD_FP → a thicker ring. */
    public static void queueHeldFpItem(List<BakedQuad> quads, PoseStack.Pose pose, Matrix4f modelView,
                                       int light, int color) {
        if (!pose.pose().isFinite()) return;
        if (CustomGlintRenderer.isInShadowPass()) return;
        snapshotHeldFpProjection();
        heldFpJobs.add(new ItemJob(quads, pose, modelView, light, color, CAT_HELD_FP, null, null));
    }

    /** CAT_HELD_FP for a first-person hand item, else CAT_ITEM. */
    static int itemCategory(ItemDisplayContext ctx) {
        boolean fp = ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        return fp ? CAT_HELD_FP : CAT_ITEM;
    }

    /** Queue one textured special-item silhouette bucket under an explicit outline {@code key}. A
     *  multi-texture item (shield base + banner patterns) passes the SAME key for every bucket so they
     *  compose as one ring with no internal seam. Routed to the world / FP / GUI drain by {@code ctx}.
     *  {@code modelView} is the captured draw-time modelview (replayed by the deferred world/FP drain;
     *  ignored by the immediate GUI drain, which uses the live matrices). */
    static void addItemModelJob(float[] data, int len, ResourceLocation tex, Matrix4f modelView,
                                int color, ItemDisplayContext ctx, int key, float[] anchor) {
        if (CustomGlintRenderer.isInShadowPass()) return;
        if (len < 20 || tex == null) return; // need at least one quad (4 verts * 5 floats)
        if (!allFinite(data, len)) return;
        float[] copy = new float[len];
        System.arraycopy(data, 0, copy, 0, len);
        boolean gui = ctx == ItemDisplayContext.GUI;
        boolean fp = !gui && (isFpHand()
                || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        ModelJob job = new ModelJob(copy, len, tex, modelView, color, key, fp ? CAT_HELD_FP : CAT_ITEM,
                gui ? anchor : null, gui ? captureScissor() : null, false);
        if (gui) modelGuiJobs.add(job);
        else if (fp) { snapshotHeldFpProjection(); modelFpJobs.add(job); }
        else modelWorldJobs.add(job);
    }

    /** Queue a posed model silhouette (entity body, entity-surface layer, worn armor piece) traced against
     *  {@code tex}. {@code data} is camera-relative {@code [x,y,z,u,v]} per vertex (QUADS order) captured by
     *  re-rendering / teeing the posed model into a record-only buffer; {@code modelView} is a COPY of the
     *  live RenderSystem modelview at that draw (replayed at drain, matching the world-item path). {@code key}
     *  comes from {@link #glowKeyFor} so a figure's body + surface layers + armor share one id → ONE unified
     *  ring. Drained with the world items at {@code AFTER_WEATHER}. */
    public static void queueModelOutline(float[] data, int len, ResourceLocation tex, Matrix4f modelView,
                                         int color, int key, int category) {
        if (CustomGlintRenderer.isInShadowPass()) return; // don't capture the Iris shadow-map pass
        if (len < 20 || tex == null) return;              // need at least one quad (4 verts * 5 floats)
        if (!allFinite(data, len)) return;
        float[] copy = new float[len];
        System.arraycopy(data, 0, copy, 0, len);
        modelWorldJobs.add(new ModelJob(copy, len, tex, modelView, color, key, category, null, null, false));
    }

    /** TRIANGLES-mode counterpart of {@link #queueModelOutline}, for a silhouette captured from a
     *  triangle-list draw (Epic Fight patched-entity meshes route through a TRIANGLES-mode RenderType).
     *  {@code data} is camera-relative {@code [x,y,z,u,v]} per vertex in triangle-list order; the vertex
     *  count is trimmed to a multiple of 3 so the deferred TRIANGLES draw never leaves a partial primitive.
     *  Same {@code key} scheme as {@link #queueModelOutline}: the body mesh + every worn/patched layer of
     *  one figure share a key and compose as ONE ring. Drained with the world items at {@code AFTER_WEATHER}. */
    public static void queueModelOutlineTriangles(float[] data, int len, ResourceLocation tex, Matrix4f modelView,
                                                  int color, int key, int category) {
        if (CustomGlintRenderer.isInShadowPass()) return; // don't capture the Iris shadow-map pass
        if (tex == null || !allFinite(data, len)) return;
        int verts = len / 5;
        verts -= verts % 3;               // TRIANGLES: whole primitives only
        int usable = verts * 5;
        if (usable < 15) return;          // need at least one triangle (3 verts * 5 floats)
        float[] copy = new float[usable];
        System.arraycopy(data, 0, copy, 0, usable);
        modelWorldJobs.add(new ModelJob(copy, usable, tex, modelView, color, key, category, null, null, true));
    }

    /** Per-frame reset; called from the RenderTickEvent.START listener. */
    public static void beginFrame() {
        worldJobs.clear();
        heldFpJobs.clear();
        guiJobs.clear();
        modelWorldJobs.clear();
        modelFpJobs.clear();
        modelGuiJobs.clear();
        glowIdByIdentity.clear();
        glowIdCounter = 0;
        heldFpProjValid = false;
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
                LOGGER.error("[customglint] {} failed; skipping glow rings this frame", pass, e);
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

    // Drain

    /** Drain world-space item outlines at {@code RenderLevelStageEvent.AFTER_WEATHER}, where the live
     *  projection is the world one the items were drawn with. Used off-pack only; under a shader pack the
     *  drain is deferred to {@link #drainWorldShaderPack()} (see that method). */
    public static void drainWorld() {
        guarded("drainWorld", () -> drain(worldJobs, modelWorldJobs, RenderSystem.getProjectionMatrix()));
    }

    // Snapshot of the hand-FOV projection taken when a first-person held item is captured (inside the hand
    // pass, where it is drawn). Replayed at the drain rather than read live there, because the live matrix can
    // move in between: under a shader pack the drain is deferred to renderItemInHand RETURN, and sprint's
    // dynamic FOV shifts it further. Off-pack the snapshot equals the live projection, so this is a no-op there.
    private static final Matrix4f HELD_FP_PROJ = new Matrix4f();
    private static boolean heldFpProjValid = false;

    /** Capture the projection a first-person held item is drawn under: the live one, read inside the hand pass.
     *
     *  <p>TRIED: preferring the shader mod's captured gbuffer projection here when a pack is active, on the
     *  theory that the pack rasterizes the hand under its own matrix rather than the vanilla hand-FOV one the
     *  RenderSystem still holds. It is wrong. Once the mask is drawn through the same matrix the boxes are
     *  measured with, that choice puts the entire ring visibly down-left of the item under Bliss, while the live
     *  matrix lands it on the item. The hand rasterizes under the live projection; keep reading it. */
    public static void snapshotHeldFpProjection() {
        HELD_FP_PROJ.set(RenderSystem.getProjectionMatrix());
        heldFpProjValid = true;
    }

    /** Drain first-person held-item outlines at the RETURN of {@code renderItemInHand}, replaying the
     *  hand-FOV projection snapshotted when the item was drawn (see {@link #snapshotHeldFpProjection()}). */
    public static void drainHeldFp() { guarded("drainHeldFp", GlowOutlineRenderer::drainHeldFpUnguarded); }

    private static void drainHeldFpUnguarded() {
        drain(heldFpJobs, modelFpJobs, heldFpProjValid ? HELD_FP_PROJ : RenderSystem.getProjectionMatrix(), false);
        heldFpProjValid = false;
    }

    // Shader-pack (Oculus/Iris) deferred world drain
    // Snapshot of the live world projection taken at AFTER_WEATHER (which fires every frame BEFORE Iris's
    // end-of-renderLevel composite). Under a pack the world drain runs at renderLevel RETURN, past which the
    // live projection has moved to GUI ortho, so the deferred drain replays this snapshot instead.
    private static final Matrix4f WORLD_PROJ = new Matrix4f();
    private static boolean worldProjValid = false;

    /** Capture the live world projection. Called every frame at {@code AFTER_WEATHER}. */
    public static void snapshotWorldProjection() {
        WORLD_PROJ.set(RenderSystem.getProjectionMatrix());
        worldProjValid = true;
    }

    /** Deferred world drain for the shader-pack path. Iris composites its scene to the main render target
     *  at the RETURN of {@code LevelRenderer.renderLevel} ({@code finalizeLevelRendering}), overwriting
     *  anything drawn during the earlier RenderLevelStageEvent phases, so under a pack the ring must be
     *  composited AFTER that, replaying the {@link #snapshotWorldProjection() AFTER_WEATHER} projection. */
    public static void drainWorldShaderPack() { guarded("drainWorldShaderPack", GlowOutlineRenderer::drainWorldShaderPackUnguarded); }

    private static void drainWorldShaderPackUnguarded() {
        if (!worldProjValid) { worldJobs.clear(); modelWorldJobs.clear(); return; }
        drain(worldJobs, modelWorldJobs, WORLD_PROJ);
    }

    /** Drain GUI / inventory / HUD item outlines. Called ONCE per GUI context (container foreground, screen
     *  post, HUD render post; see {@code CustomGlintClientInit}) rather than once per item flush, so all of a
     *  frame's glowing icons composite in a single mask pass instead of N framebuffer ping-pongs. By the time
     *  any of those hooks fire the icons (and their slot backgrounds) have already flushed to the main target,
     *  and the GUI ortho projection / modelview they were drawn under are still the live RenderSystem matrices
     *  (the per-icon slot position lives in the captured pose, not RS state, so the live base matrices are the
     *  same at any point in the GUI render). So each captured GUI-space silhouette projects exactly onto its
     *  drawn icon and the ring composites into the margin around it. Differs from {@link #drain}: (1) no captured-modelview
     *  replay: the live matrices ARE the draw matrices, so accumulate directly; (2) the scene-depth occlusion
     *  sampler is left UNBOUND (stale world depth would wrongly occlude every flat icon); (3) ring thickness is
     *  driven by the icon's on-screen size (GUI_RING_ITEM_PIXELS) not camera distance; (4) each ring box is
     *  clipped to the GL scissor captured when that icon drew (wand-preview box, scroll viewport), so the
     *  per-icon clip survives this drain running after the screen's scissor was popped. */
    public static void drainGui() { guarded("drainGui", GlowOutlineRenderer::drainGuiUnguarded); }

    private static void drainGuiUnguarded() {
        if (guiJobs.isEmpty() && modelGuiJobs.isEmpty()) return;
        if (silhouetteShader == null || compositeShader == null) { guiJobs.clear(); modelGuiJobs.clear(); return; }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();

        // Save/restore the live GL scissor for hygiene only. The composite toggles it per box and would
        // otherwise leave it disabled. Per-icon clipping uses each job's OWN scissor (captured when the icon
        // drew); the live scissor here is no longer the icon's clip, since this drain runs once per GUI
        // context after the screen popped its scissor. Read BEFORE the try so the finally can restore it.
        boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] prevBox = new int[4];
        if (prevScissor) GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevBox);

        snapshotAmbientState();
        try {
            ensureTarget(main.width, main.height);
            // GUI is drained immediately while the GUI ortho matrices are live, so accumulate under the LIVE
            // modelview (no captured-modelview push like the world drain).
            beginAccumulation(main.width, main.height, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix());

            // Clear the mask for this drain. The GUI now drains a few times per frame (once per GUI context:
            // HUD post, container foreground, screen post) instead of once per item flush, so a per-drain clear is
            // cheap and keeps each context's mask clean (a later dragged-item / tooltip drain can't pick up a
            // stale silhouette from the slot pass that already composited).
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
            maskTarget.clear(Minecraft.ON_OSX);
            maskTarget.bindWrite(true);
            RenderSystem.setShaderTexture(1, 0); // GUI: no scene-depth occlusion (stale world depth would erase icons)
            itemBoxes.clear();

            VertexConsumer base = MASK_BUFFERS.getBuffer(silhouetteRT());
            for (ItemJob job : guiJobs) {
                resetCamBox();
                int key = nextGlowKey(job.category);
                int r = (job.color >> 16) & 0xFF, g = (job.color >> 8) & 0xFF, b = job.color & 0xFF;
                SilhouetteConsumer sc = new SilhouetteConsumer(base, r, g, b, key);
                for (BakedQuad quad : job.quads) {
                    sc.putBulkData(job.pose, quad, 1.0f, 1.0f, 1.0f, job.light, OverlayTexture.NO_OVERLAY);
                }
                Box box = computeGuiBox(main.width, main.height, key & 31, false, job.anchor);
                if (box != null && job.scissor() != null) box = intersectBox(box, job.scissor());
                if (box != null) itemBoxes.add(box);
            }
            for (ModelJob job : modelGuiJobs) {
                resetCamBox();
                VertexConsumer tc = MASK_BUFFERS.getBuffer(silhouetteTexRT(job.tex));
                int r = (job.color >> 16) & 0xFF, g = (job.color >> 8) & 0xFF, b = job.color & 0xFF;
                SilhouetteConsumer sc = new SilhouetteConsumer(tc, r, g, b, job.key);
                emitModel(sc, job);
                Box box = computeGuiBox(main.width, main.height, job.key & 31, true, job.anchor);
                if (box != null && job.scissor() != null) box = intersectBox(box, job.scissor());
                if (box != null) itemBoxes.add(box);
            }
            MASK_BUFFERS.endBatch();
            RenderSystem.setShaderTexture(1, 0);

            if (!itemBoxes.isEmpty()) {
                // Each icon carries its OWN ring reach in framebuffer texels (box.snap). SearchRadius is the kernel
                // bound = the widest reach present; each pass then limits to its own reach via ThicknessScale =
                // snap / SearchRadius, so a thinned 3D icon rings thinner than a flat icon in the same drain.
                int searchRadius = 1;
                for (Box bx : itemBoxes) searchRadius = Math.max(searchRadius, (int) Math.ceil(bx.snap));
                compositeBegin(main, maskTarget, searchRadius, false, true, 0.0f, 0.0f);
                for (int i = 0; i < itemBoxes.size(); i++) {
                    Box box = itemBoxes.get(i);
                    compositePass(box, box.snap / searchRadius, box.id);
                }
                compositeEnd();
            }
        } catch (Throwable t) {
            // Log, but let the finally restore state. See bindMainAndResetState for why that's load-bearing.
            LOGGER.error("[{}] glow GUI drain failed", MOD_ID, t);
        } finally {
            bindMainAndResetState(main, true);
            if (prevScissor) RenderSystem.enableScissor(prevBox[0], prevBox[1], prevBox[2], prevBox[3]);
            else RenderSystem.disableScissor();
            guiJobs.clear();
            modelGuiJobs.clear();
        }
    }

    // Blend + depth-test ENABLE flags captured at drain entry, restored in bindMainAndResetState. The glow
    // drain runs both in the world phase (blend off, depth on) AND the GUI phase (blend ON, depth-test OFF);
    // it must hand back exactly what it found. Forcing world defaults inside the GUI phase left
    // EnhancedVisuals' fullscreen overlay pass drawing UNBLENDED, which painted the whole screen black.
    private static boolean savedBlend, savedDepthTest;
    // BlendMode.lastApplied is not GL state, but later code depends on it. Both glow shaders declare no blend,
    // so they parse as OPAQUE and leave lastApplied opaque, which makes the next blended shader re-set its own
    // blend function over whatever its caller set. Citadel's post-effect blit (Alex's Caves) relies on that not
    // happening; see GLINT_SHADER_SHARD in CustomGlintRenderer for the black-screen case.
    private static BlendMode savedBlendMode;

    /** Snapshot the ambient blend / depth-test enable flags before the drain mutates GL state. */
    private static void snapshotAmbientState() {
        savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        savedBlendMode = BlendMode.lastApplied;
    }

    /** Guarantee the MAIN render target is the bound draw target and hand back the GL state the surrounding
     *  render phase expects. Called in the finally of every drain so a mid-drain throw (or any leftover mask
     *  texture / scissor / blend the composite set) can never send the rest of the frame into the offscreen
     *  mask (a black screen). {@code guiPhase}: the GUI/HUD render phase has a FIXED invariant: blend ON,
     *  depth-test OFF. So restore that explicitly (not a captured snapshot, which can be a transient
     *  mid-HUD state). Handing EnhancedVisuals' post-HUD overlay pass blend=OFF made it draw opaque and paint
     *  the whole screen black. The world/held drain runs in the 3D pass, so it restores what it found there. */
    private static void bindMainAndResetState(RenderTarget main, boolean guiPhase) {
        main.bindWrite(true);
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.setShaderTexture(1, 0);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        if (guiPhase) {
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
        } else {
            if (savedDepthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (savedBlend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableScissor();
        BlendMode.lastApplied = savedBlendMode;
    }

    /** Shared drain: accumulate the queued silhouettes into the mask under their captured modelview + the
     *  live projection, then run the id-aware composite. Flat baked items trace the block atlas; special
     *  items trace their own texture. The live projection differs per call site (world FOV at AFTER_WEATHER
     *  vs hand FOV at renderHandsWithItems RETURN), which is exactly what each set of jobs was drawn under. */
    private static void drain(List<ItemJob> jobs, List<ModelJob> models, Matrix4f proj) {
        drain(jobs, models, proj, true);
    }

    /** {@code sceneOcclusion=false} leaves the scene-depth sampler unbound so the silhouette shader does not
     *  occlude the ring against the main depth buffer. Used for the first-person held item, which is always
     *  foreground: under a shader pack the hand item's own depth sits in that buffer and would occlude its own
     *  ring ("outline hides behind the item"). World / third-person items keep occlusion (a glowing item behind
     *  a wall must not ring through it). */
    private static void drain(List<ItemJob> jobs, List<ModelJob> models, Matrix4f proj, boolean sceneOcclusion) {
        if (jobs.isEmpty() && models.isEmpty()) return;
        if (silhouetteShader == null || compositeShader == null) { jobs.clear(); models.clear(); return; }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        ensureTarget(main.width, main.height);
        snapshotAmbientState();
        try {
            int searchRadius = accumulate(jobs, models, main, proj, sceneOcclusion);
            composite(main, itemBoxes, searchRadius, proj.m22(), proj.m32());
        } catch (Throwable t) {
            // Log, but let the finally restore state. See bindMainAndResetState for why that's load-bearing.
            LOGGER.error("[{}] glow world drain failed", MOD_ID, t);
        } finally {
            bindMainAndResetState(main, false);
            jobs.clear();
            models.clear();
        }
    }

    /** Accumulate the silhouettes of {@code jobs}+{@code models} into the mask and compute each object's
     *  screen box into {@link #itemBoxes}. Returns the composite kernel radius (the widest category
     *  present). Leaves the mask bound for writing.
     *
     *  <p>Every job replays under the modelview it was DRAWN with (captured at its render RETURN), not the
     *  live drain-time modelview. Those differ on 1.20.1, and the live one offsets the ring. Jobs are grouped
     *  by that captured matrix ({@link #distinctModelViews}) and each group emits under its own. */
    private static int accumulate(List<ItemJob> jobs, List<ModelJob> models, RenderTarget main, Matrix4f proj,
                                  boolean sceneOcclusion) {
        int searchRadius = 1;
        for (ItemJob j : jobs) searchRadius = Math.max(searchRadius, CAT_THICKNESS[j.category]);
        for (ModelJob j : models) searchRadius = Math.max(searchRadius, CAT_THICKNESS[j.category]);

        // Force write masks on before clearing: glClear honours the live GL write masks, and the world
        // phase (AFTER_WEATHER) leaves depthMask=false; without this the mask's depth clear is a silent
        // no-op, the silhouette's LEQUAL test then drops every fragment and nothing rings.
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);
        maskTarget.clear(Minecraft.ON_OSX);
        maskTarget.bindWrite(true);
        // Bind the main depth texture to unit 1 so the silhouette shader can occlude fragments behind the
        // scene. Safe: we're writing the MASK, not the main target. Skipped for the always-foreground FP held
        // item (sceneOcclusion=false): under a shader pack its own depth would otherwise occlude its ring.
        RenderSystem.setShaderTexture(1, sceneOcclusion ? main.getDepthTextureId() : 0);
        if (uSilBiasScale != null) uSilBiasScale.set(0.0f);
        itemBoxes.clear();

        // One emit pass per distinct captured modelview, all into the same already-cleared mask, so the
        // composite below still sees every object in one image and runs once.
        for (Matrix4f modelView : distinctModelViews(jobs, models)) {
            accumulateUnder(modelView, jobs, models, main, proj);
        }

        RenderSystem.setShaderTexture(1, 0); // don't leave the resize-volatile main depth bound
        return searchRadius;
    }

    /** The distinct captured modelviews across {@code jobs}+{@code models}, in queue order. Normally one: a
     *  render pass draws everything under a single RS modelview, and each job stored a copy of it.
     *
     *  <p>Picking an item up is the exception, and it is why this can't just read {@code jobs.get(0)}. Vanilla
     *  spawns an {@code ItemPickupParticle} (3 ticks) that re-renders a COPY of the item entity from inside the
     *  particle pass. {@code ParticleEngine.render} pushes the camera rotation onto the RS modelview stack for
     *  that pass, so that one item's silhouette is captured under a camera-rotated modelview, while every
     *  entity / armor silhouette from the earlier entity pass was captured under the plain one. Replaying the
     *  whole drain under either matrix double-transforms the other group and throws its rings across the screen
     *  as ghosts for the particle's lifetime. */
    private static List<Matrix4f> distinctModelViews(List<ItemJob> jobs, List<ModelJob> models) {
        List<Matrix4f> views = new ArrayList<>(1);
        for (ItemJob j : jobs) addDistinctView(views, j.modelView);
        for (ModelJob j : models) addDistinctView(views, j.modelView);
        return views;
    }

    /** Exact equality, not a delta compare: within one pass every job copied the same live {@code RenderSystem}
     *  modelview instance, so matrices that belong to the same group are bit-identical, not merely close. */
    private static void addDistinctView(List<Matrix4f> views, Matrix4f m) {
        if (m == null) return;
        for (Matrix4f v : views) if (v.equals(m)) return;
        views.add(m);
    }

    /** Emit every job captured under {@code modelView} into the already-bound mask, under that modelview. */
    private static void accumulateUnder(Matrix4f modelView, List<ItemJob> jobs, List<ModelJob> models,
                                        RenderTarget main, Matrix4f proj) {
        beginAccumulation(main.width, main.height, modelView, proj);

        // Force BOTH captured matrices onto the RS state for the mask draw. The silhouette RT's shader reads
        // ModelViewMat AND ProjMat off RenderSystem at the endBatch draw, and the screen boxes below are
        // measured through that same pair via ACC_MVP, so the two have to be the same pair or the box does not
        // bound the shape it scissors.
        //
        // Leaving the projection live is what silently mis-sized the box under a shader pack, where the drain is
        // deferred past the point the live matrix still matches the snapshot the boxes were measured with. The
        // difference is a scale, so the box error is negligible at the screen centre and tens of pixels at a
        // corner, which is why only an item posed right out at the edge showed a cut ring.
        PoseStack rsStack = RenderSystem.getModelViewStack();
        rsStack.pushPose();
        rsStack.setIdentity();
        rsStack.mulPoseMatrix(modelView);
        RenderSystem.applyModelViewMatrix();
        Matrix4f prevProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting prevSorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(proj, VertexSorting.DISTANCE_TO_ORIGIN);

        VertexConsumer base = MASK_BUFFERS.getBuffer(silhouetteRT());
        for (ItemJob job : jobs) {
            if (!modelView.equals(job.modelView)) continue;
            resetCamBox();
            int key = nextGlowKey(job.category);
            int r = (job.color >> 16) & 0xFF, g = (job.color >> 8) & 0xFF, b = job.color & 0xFF;
            SilhouetteConsumer sc = new SilhouetteConsumer(base, r, g, b, key);
            for (BakedQuad quad : job.quads) {
                sc.putBulkData(job.pose, quad, 1.0f, 1.0f, 1.0f, job.light, OverlayTexture.NO_OVERLAY);
            }
            int[] box = computeScissor(main.width, main.height, CAT_THICKNESS[job.category]);
            if (box != null) {
                float d = computeDist();
                itemBoxes.add(new Box(box[0], box[1], box[2], box[3], computeScale(d), key & 31, d, 0, 1.0f));
            }
        }
        // Special items: each bucket traces its own texture, so switch the bound silhouette RT per texture
        // (the immediate buffer flushes the prior batch on each switch). The per-job key makes a
        // multi-texture item's buckets share one id → one unified ring.
        for (ModelJob job : models) {
            if (!modelView.equals(job.modelView)) continue;
            resetCamBox();
            VertexConsumer tc = MASK_BUFFERS.getBuffer(
                    job.triangles ? silhouetteTexTriangleRT(job.tex) : silhouetteTexRT(job.tex));
            int r = (job.color >> 16) & 0xFF, g = (job.color >> 8) & 0xFF, b = job.color & 0xFF;
            SilhouetteConsumer sc = new SilhouetteConsumer(tc, r, g, b, job.key);
            emitModel(sc, job);
            int[] box = computeScissor(main.width, main.height, CAT_THICKNESS[job.category]);
            if (box != null) {
                float d = computeDist();
                itemBoxes.add(new Box(box[0], box[1], box[2], box[3], computeScale(d), job.key & 31, d, 0, 1.0f));
            }
        }
        // Flush before restoring the matrices: the buffered vertices only draw at endBatch, and that draw reads
        // the LIVE matrices. Anything still buffered would draw under the next group's.
        MASK_BUFFERS.endBatch();

        RenderSystem.setProjectionMatrix(prevProj, prevSorting);
        rsStack.popPose();
        RenderSystem.applyModelViewMatrix();
    }

    /** Replay a captured special item's {@code [x,y,z,u,v]} vertices (camera-relative, QUADS order) into
     *  the texture-bound silhouette buffer. The real UVs drive the shader's alpha-discard against the item
     *  texture, so the silhouette follows the real shape (not a square model hull). */
    private static void emitModel(SilhouetteConsumer sc, ModelJob job) {
        float[] d = job.data;
        for (int i = 0; i + 4 < job.len; i += 5) {
            sc.vertex(d[i], d[i + 1], d[i + 2]);
            sc.color(255, 255, 255, 255);   // overridden to glow colour + key by SilhouetteConsumer
            sc.uv(d[i + 3], d[i + 4]);
            sc.overlayCoords(OverlayTexture.NO_OVERLAY);
            sc.uv2(0);                       // light (unused by the silhouette shader)
            sc.normal(0.0f, 1.0f, 0.0f);
            sc.endVertex();
        }
    }

    /** Composite once per OUTLINE ID, FAR→NEAR. Each pass rings only its own id's silhouette and is
     *  occluded where a nearer silhouette covers it. Matrix-independent (fullscreen blit). Rebinds the
     *  main target even when there are no on-screen boxes so the offscreen mask doesn't stay bound. */
    private static void composite(RenderTarget main, List<Box> boxes, int searchRadius, float projA, float projB) {
        if (boxes.isEmpty()) { main.bindWrite(true); return; }
        groupById(boxes);
        boxes.sort((a, b) -> Float.compare(b.dist, a.dist)); // far → near
        compositeBegin(main, maskTarget, searchRadius, true, false, projA, projB);
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            compositePass(box, box.scale, box.id);
        }
        compositeEnd();
    }

    /** {@code ringOcclusion}: bind the mask depth to unit 1 so the ring is occluded where a nearer silhouette
     *  covers it (world); off for the GUI (orthographic, depth meaningless). {@code guiMode}: square reach =
     *  ThicknessScale * SearchRadius texels, no morphological-opening guard (matches the pixel-art icon). */
    private static void compositeBegin(RenderTarget main, TextureTarget maskSrc, int searchRadius,
                                       boolean ringOcclusion, boolean guiMode, float projA, float projB) {
        main.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,       GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(() -> compositeShader);
        RenderSystem.setShaderTexture(0, maskSrc.getColorTextureId());
        // Mask depth -> unit 1 for ring occlusion (mask is a separate target from the main colour we write,
        // so no feedback). ProjA/ProjB linearise it the same way the silhouette pass projected depth. Under the
        // GUI's orthographic projection that linearisation is meaningless and would reject every ring pixel, so
        // the GUI drain passes ringOcclusion=false: unit 1 reads texture 0 (depth 0) -> nothing is occluded.
        RenderSystem.setShaderTexture(1, ringOcclusion ? maskSrc.getDepthTextureId() : 0);
        if (uSearchRadius != null) uSearchRadius.set(searchRadius);
        if (uProjA != null) uProjA.set(projA);
        if (uProjB != null) uProjB.set(projB);
        if (uGuiMode != null) uGuiMode.set(guiMode ? 1 : 0);
    }

    private static void compositePass(Box box, float thicknessScale, int targetId) {
        RenderSystem.enableScissor(box.x, box.y, box.w, box.h);
        if (uThicknessScale != null) uThicknessScale.set(thicknessScale);
        if (uTargetId != null) uTargetId.set(targetId);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.vertex(0.0, 0.0, 0.0).uv(0.0f, 0.0f).endVertex();
        bb.vertex(1.0, 0.0, 0.0).uv(1.0f, 0.0f).endVertex();
        bb.vertex(1.0, 1.0, 0.0).uv(1.0f, 1.0f).endVertex();
        bb.vertex(0.0, 1.0, 0.0).uv(0.0f, 1.0f).endVertex();
        BufferUploader.drawWithShader(bb.end());

        RenderSystem.disableScissor();
    }

    private static void compositeEnd() {
        RenderSystem.setShaderTexture(1, 0); // don't leak the mask depth binding to later rendering
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /** Padded screen-space scissor box (GL bottom-left origin, px) from the per-vertex {@link #screenBox}.
     *  Returns null when nothing projected in front of the camera. */
    private static int[] computeScissor(int width, int height, int searchRadius) {
        if (screenBox[0] > screenBox[2]) return null;
        int pad = searchRadius + SCISSOR_MARGIN;
        int x0 = Math.max(0,     (int) Math.floor(screenBox[0]) - pad);
        int y0 = Math.max(0,     (int) Math.floor(screenBox[1]) - pad);
        int x1 = Math.min(width, (int) Math.ceil(screenBox[2])  + pad);
        int y1 = Math.min(height,(int) Math.ceil(screenBox[3])  + pad);
        if (x1 <= x0 || y1 <= y0) return null;
        return new int[]{ x0, y0, x1 - x0, y1 - y0 };
    }

    /** GUI variant of {@link #computeScissor}. {@code anchor} (from {@code ItemRendererMixin#cg_guiAnchor})
     *  carries the icon's GUI-space slot centre {@code [x,y,z]}, its nominal half-size in GUI px {@code [3]}
     *  (8 for a 16-px slot, 40 for the 5x wand preview), and the item texture's resolution {@code [4]}. Both
     *  the ring thickness and the slot clamp are sized off that REAL on-screen icon, not the silhouette
     *  footprint (which a 3D BEWLR overflows). Returns null when nothing accumulated / off-screen. */
    private static Box computeGuiBox(int width, int height, int id, boolean is3d, float[] anchor) {
        if (screenBox[0] > screenBox[2]) return null; // nothing accumulated
        float minX = screenBox[0], minY = screenBox[1], maxX = screenBox[2], maxY = screenBox[3];
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();

        // Icon centre (framebuffer px) + nominal half-size (framebuffer px), from the projected anchor.
        // Fall back to the silhouette box if the anchor is missing or projects behind the near plane.
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f;
        float halfFb = Math.max((maxX - minX), (maxY - minY)) * 0.5f;
        if (anchor != null && anchor.length > 3) {
            halfFb = anchor[3] * guiScale; // GUI px → framebuffer px (GUI ortho scales by guiScale)
            Vector4f p = SCRATCH_V.set(anchor[0], anchor[1], anchor[2], 1.0f);
            ACC_MVP.transform(p);
            if (p.w() > 1.0e-4f) {
                cx = (p.x() / p.w() * 0.5f + 0.5f) * ACC_W;
                cy = (p.y() / p.w() * 0.5f + 0.5f) * ACC_H;
            }
        }

        // Ring thickness scales with one TEXTURE pixel of the item, not a fixed 1/16: the icon is (2*halfFb)
        // framebuffer texels wide spanning texRes texture pixels, so a texture pixel is (2*halfFb / texRes)
        // texels. Keeps the ring the same relative width on a 16x16 icon AND a 32/64 high-res item (which
        // otherwise rings 2x/4x too thick). Floored at 1 texel so a high-res ring at a small gui scale stays
        // visible rather than rounding away.
        float texRes = anchor != null && anchor.length > 4 ? anchor[4] : 16.0f;
        float pxPerTexPx = Math.max(2.0f * halfFb / texRes, 1.0f);
        float reach = Math.max(1.0f, Math.round(GUI_RING_ITEM_PIXELS * pxPerTexPx));

        // 3D BEWLR icons get a small OUTWARD pad so the ring wraps the item; flat sprites trace their own edge
        // (pad 0). The slot clamp below keeps the padded ring inside the icon.
        int pad = is3d ? (int) Math.ceil(reach) : 0;
        int x0 = Math.max(0,      (int) Math.floor(minX) - pad);
        int y0 = Math.max(0,      (int) Math.floor(minY) - pad);
        int x1 = Math.min(width,  (int) Math.ceil(maxX)  + pad);
        int y1 = Math.min(height, (int) Math.ceil(maxY)  + pad);

        // Clamp the ring to the icon's nominal square (centred on the true anchor). A 3D BEWLR whose model
        // projects past its slot can't ring outside the icon; in the big wand preview the nominal square is the
        // 5x recess, so the ring still wraps the whole item. No-op for a flat sprite that already fills its slot.
        x0 = Math.max(x0, (int) Math.floor(cx - halfFb));
        y0 = Math.max(y0, (int) Math.floor(cy - halfFb));
        x1 = Math.min(x1, (int) Math.ceil(cx + halfFb));
        y1 = Math.min(y1, (int) Math.ceil(cy + halfFb));
        if (x1 <= x0 || y1 <= y0) return null;
        // snap carries the per-icon ring reach (texels); drainGui sets ThicknessScale = reach / SearchRadius
        // so each icon rings to its OWN reach (the shader limits the GUI kernel per pass).
        return new Box(x0, y0, x1 - x0, y1 - y0, 1.0f, id, 0.0f, 0, reach);
    }

    /** Intersects a ring box with a saved GL scissor box ({@code [x,y,w,h]}, bottom-left); null if empty.
     *  Honours the GUI's own active scissor (wand-editor preview box, scroll panel) so the ring can't draw
     *  past it onto the surrounding GUI. */
    private static Box intersectBox(Box b, int[] s) {
        int x0 = Math.max(b.x, s[0]);
        int y0 = Math.max(b.y, s[1]);
        int x1 = Math.min(b.x + b.w, s[0] + s[2]);
        int y1 = Math.min(b.y + b.h, s[1] + s[3]);
        if (x1 <= x0 || y1 <= y0) return null;
        return new Box(x0, y0, x1 - x0, y1 - y0, b.scale, b.id, b.dist, b.prio, b.snap);
    }

    /** Groups boxes by outline id in-place: same-id boxes union into one (MAX scale, MIN dist); distinct
     *  ids stay separate for the per-id far→near passes. O(n^2), n small. */
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
        return new Box(x0, y0, x1 - x0, y1 - y0, Math.max(a.scale, b.scale), a.id, Math.min(a.dist, b.dist),
                Math.max(a.prio, b.prio), Math.max(a.snap, b.snap));
    }

    // Silhouette vertex consumer
    // Wraps the mask buffer and forces every vertex colour to (glow rgb, key) so the silhouette shader
    // receives the glow colour in rgb and the per-object key (1..127) in alpha. Position / uv / overlay /
    // light / normal pass through unchanged (the shader uses only Position, UV0 and the forced Color).
    private static final class SilhouetteConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final int r, g, b, key;

        SilhouetteConsumer(VertexConsumer delegate, int r, int g, int b, int key) {
            this.delegate = delegate;
            this.r = r; this.g = g; this.b = b; this.key = key;
        }

        @Override public VertexConsumer vertex(double x, double y, double z) {
            float fx = (float) x, fy = (float) y, fz = (float) z;
            if (fx < camBox[0]) camBox[0] = fx;
            if (fy < camBox[1]) camBox[1] = fy;
            if (fz < camBox[2]) camBox[2] = fz;
            if (fx > camBox[3]) camBox[3] = fx;
            if (fy > camBox[4]) camBox[4] = fy;
            if (fz > camBox[5]) camBox[5] = fz;
            Vector4f p = SCRATCH_V.set(fx, fy, fz, 1.0f);
            ACC_MVP.transform(p);
            if (p.w() > 1.0e-4f) {
                float sx = (p.x() / p.w() * 0.5f + 0.5f) * ACC_W;
                float sy = (p.y() / p.w() * 0.5f + 0.5f) * ACC_H;
                if (sx < screenBox[0]) screenBox[0] = sx;
                if (sy < screenBox[1]) screenBox[1] = sy;
                if (sx > screenBox[2]) screenBox[2] = sx;
                if (sy > screenBox[3]) screenBox[3] = sy;
            }
            delegate.vertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer color(int red, int green, int blue, int alpha) { delegate.color(r, g, b, key); return this; }
        @Override public VertexConsumer uv(float u, float v) { delegate.uv(u, v); return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { delegate.overlayCoords(u, v); return this; }
        @Override public VertexConsumer uv2(int u, int v) { delegate.uv2(u, v); return this; }
        @Override public VertexConsumer normal(float nx, float ny, float nz) { delegate.normal(nx, ny, nz); return this; }
        @Override public void endVertex() { delegate.endVertex(); }
        @Override public void defaultColor(int red, int green, int blue, int alpha) { delegate.defaultColor(red, green, blue, alpha); }
        @Override public void unsetDefaultColor() { delegate.unsetDefaultColor(); }
    }

    // Special-item capture
    // A record-only MultiBufferSource handed to the re-rendered BEWLR (trident/shield/modded). Each
    // emitted vertex's already-transformed (camera-relative) [x,y,z,u,v] is recorded into a bucket keyed by
    // the texture of the RenderType it drew through, so the silhouette alpha-discards against that texture
    // and traces the REAL item shape. Textureless RTs fall back to WHITE_TEXTURE (full fill). Drawing the
    // same shape through several layers records it more than once, harmlessly; the mask write is keyed.
    public static final class CapturingBufferSource implements MultiBufferSource {
        private final Map<ResourceLocation, float[]> data = new LinkedHashMap<>();
        private final Map<ResourceLocation, Integer> counts = new HashMap<>();

        /** Queue every captured bucket as a textured special-item silhouette (one shared id for the whole
         *  item → no seam between textures), routed to the world / FP / GUI drain by {@code ctx}.
         *  {@code anchor} is the GUI-space slot centre (used only for the GUI drain's slot clamp; null
         *  otherwise). */
        public void queueGroups(int color, Matrix4f modelView, ItemDisplayContext ctx, float[] anchor) {
            int key = nextGlowKey(itemCategory(ctx)); // one id for the whole item → no seam between textures
            for (Map.Entry<ResourceLocation, float[]> e : data.entrySet()) {
                addItemModelJob(e.getValue(), counts.get(e.getKey()), e.getKey(), modelView, color, ctx, key, anchor);
            }
        }

        @Override public VertexConsumer getBuffer(RenderType renderType) {
            ResourceLocation tex = resolveRenderTypeTexture(renderType);
            data.computeIfAbsent(tex, k -> new float[1024]);
            counts.putIfAbsent(tex, 0);
            return new RecordingConsumer(tex);
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
            private float px, py, pz; // stash position until uv flushes the 5-tuple
            RecordingConsumer(ResourceLocation tex) { this.tex = tex; }
            @Override public VertexConsumer vertex(double x, double y, double z) { px = (float) x; py = (float) y; pz = (float) z; return this; }
            @Override public VertexConsumer uv(float u, float v) { record(tex, px, py, pz, u, v); return this; }
            @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
            @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
            @Override public VertexConsumer uv2(int u, int v) { return this; }
            @Override public VertexConsumer normal(float nx, float ny, float nz) { return this; }
            @Override public void endVertex() {}
            @Override public void defaultColor(int r, int g, int b, int a) {}
            @Override public void unsetDefaultColor() {}
        }
    }
}
