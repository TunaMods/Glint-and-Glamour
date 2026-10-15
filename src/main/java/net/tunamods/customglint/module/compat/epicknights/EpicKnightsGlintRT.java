package net.tunamods.customglint.module.compat.epicknights;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compat-local render pipeline for Epic Knights armor decorations.
 *
 * The naive approach (just render parts with a glint render type) fails two ways.
 * <ul>
 *   <li>EQUAL depth test is invisible: EK's decoration depth and our glint depth differ by an FP
 *   epsilon despite using the same VIEW_OFFSET_Z_LAYERING. The same EQUAL works fine on vanilla
 *   armor ({@code CustomGlintRenderer.forArmorGlint}), so it's something about EK's pipeline.</li>
 *   <li>LEQUAL depth + no mask renders the full cuboid bounding box of each decoration ModelPart,
 *   bleeding through the transparent regions of the decoration texture. Looks "huge" because
 *   the plume's cuboid is much larger than its visible feather shape.</li>
 * </ul>
 *
 * Solution: a per-slot stencil pre-pass (see {@link #forDecorationStencilWriteSlot} /
 * {@link #forDecorationGlintSlot}).
 * 1. WRITE: render parts through {@code RENDERTYPE_OUTLINE_SHADER} (alpha-discard, so transparent
 *    texels are skipped) with stencil op REPLACE, stamping a unique per-decoration slot value where
 *    the discard passes. Color/depth masks off so nothing visible is drawn.
 * 2. GLINT: render glint passes with stencil test EQUAL slot; glint only appears on that
 *    decoration's opaque texels. LEQUAL depth handles occlusion.
 * Each {@code applyDecorationGlint} call takes a fresh slot from {@code CustomGlintRenderer.nextStencilSlot()}
 * so overlapping decorations don't cross-contaminate. There are three dispatch paths (shaders on / off /
 * absent); {@link #clearCaches()} releases the per-slot caches on reload and on logout.
 */
public final class EpicKnightsGlintRT extends RenderStateShard {
    private EpicKnightsGlintRT() { super("", () -> {}, () -> {}); }

    /** Releases every RenderType this class registered into the fixed-buffer maps, then drops the cache
     *  entries. The per-slot / per-shader glint caches key on continuous speed/scale floats (and, for the
     *  slot caches, the per-frame stencil slot), so without a reload clear they would accumulate dead
     *  RenderTypes + BufferBuilders for the whole session. Registered as a reload cleanup so it runs with
     *  {@link CustomGlintRenderer#clearTextures()}, mirroring how the core BY_* caches are cleared. */
    public static void clearCaches() {
        evictAll(SLOT_WRITE_CACHE.values());     SLOT_WRITE_CACHE.clear();
        evictAll(SLOT_GLINT_CACHE.values());     SLOT_GLINT_CACHE.clear();   SLOT_GLINT_COLORS.clear();
        evictAll(SLOT_CHROMATIC_CACHE.values()); SLOT_CHROMATIC_CACHE.clear();
        evictAll(DEPTH_PREWRITE_CACHE.values()); DEPTH_PREWRITE_CACHE.clear();
        evictAll(SHADER_GLINT_CACHE.values());   SHADER_GLINT_CACHE.clear(); SHADER_GLINT_COLORS.clear();
    }

    private static void evictAll(Collection<RenderType> rts) {
        for (RenderType rt : rts) CustomGlintRenderer.unregisterFixedBuffer(rt);
    }

    static { CustomGlintRenderer.additionalReloadCleanup.add(EpicKnightsGlintRT::clearCaches); }

    private static final RenderStateShard.WriteMaskStateShard EK_NO_WRITE =
            new RenderStateShard.WriteMaskStateShard(false, false);

    // Per-slot infrastructure for FullyBuffered (Oculus-no-pack) path
    //
    // Under Oculus's FullyBufferedMultiBufferSource, endBatch(rt) defers the actual draw to a
    // later graph-ordered flush. Any manual glStencilOp/glStencilFunc/glColorMask calls between
    // pass A's endBatch and pass B's endBatch execute immediately on the CPU but DON'T apply to
    // the deferred draws. By the time the graph flushes A and B, GL state is whatever was set
    // last. Result: stencil disabled, both glint and outline silently invisible.
    //
    // Fix: bake every stencil state change into the RT's layering shard (setupRenderState fires
    // at deferred-flush time, just before the draw). No manual GL state between passes.
    //
    // Slot isolation: each applyDecorationGlint call allocates a unique stencil slot V via
    // CustomGlintRenderer.nextStencilSlot(). WRITE stamps V at the decoration's silhouette and
    // GLINT tests EQUAL V, so it draws on that decoration only. Multiple decorations per frame
    // don't cross-contaminate.

    /** Per-slot WRITE RT cache, keyed by (slot, tex). Each entry's TextureStateShard closes
     *  over a stable texture, so multiple textures in one slot don't clobber each other's
     *  binding under FullyBuffered's deferred flush. */
    private static final Map<String, RenderType> SLOT_WRITE_CACHE = new HashMap<>();

    /** Depth-correct per-slot WRITE shard. dpfail=KEEP (no back-plane bleed). Honors the once-
     *  per-frame {@code pendingFrameStencilClear} gate from CustomGlintRenderer so the stencil
     *  buffer is cleared at the first WRITE of the frame (any glint pipeline can do it). */
    private static RenderStateShard.LayeringStateShard ekStencilWriteLayeringSlot(final int v) {
        return new RenderStateShard.LayeringStateShard(
                "custom_glint_ek_stencil_write_slot_v" + v,
                () -> {
                    Minecraft.getInstance().getMainRenderTarget().enableStencil();
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0xFF);
                    if (CustomGlintRenderer.pendingFrameStencilClear) {
                        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
                        CustomGlintRenderer.pendingFrameStencilClear = false;
                    }
                    GL11.glStencilFunc(GL11.GL_ALWAYS, v, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
                },
                () -> {
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                });
    }

    /** Per-slot WRITE RT factory. Cached per (slot, tex) so calling with different textures in
     *  the same slot doesn't clobber a shared holder. Crowns need a stencil write through BOTH
     *  base and overlay textures (shape lives in overlay); a mutable per-slot holder would
     *  collapse the second call's texture onto both deferred draws under FullyBuffered. */
    public static RenderType forDecorationStencilWriteSlot(int v, ResourceLocation texture) {
        String key = v + "|" + texture;
        RenderType rt = SLOT_WRITE_CACHE.computeIfAbsent(key, k ->
                RenderType.create(
                    "customglint:ek_deco_stencil_write_slot|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(texture, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(CustomGlintRenderer.FORCE_MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(EK_NO_WRITE)
                            .setLayeringState(ekStencilWriteLayeringSlot(v))
                            .createCompositeState(false)));
        CustomGlintRenderer.registerFixedBuffer(rt);
        return rt;
    }

    /** Per-slot GLINT layering: VIEW_OFFSET_Z polygon offset + stencil EQUAL v. */
    private static RenderStateShard.LayeringStateShard ekGlintStencilTestLayeringSlot(final int v) {
        return new RenderStateShard.LayeringStateShard(
                "custom_glint_ek_glint_stencil_test_slot_v" + v,
                () -> {
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0xFF);
                    GL11.glStencilFunc(GL11.GL_EQUAL, v, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                    GL11.glPolygonOffset(-1.0f, -10.0f);
                },
                () -> {
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                    GL11.glPolygonOffset(0.0f, 0.0f);
                    GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                });
    }

    /** The EK decoration-glint texture-matrix animation: a triple-rotated oil-slick scroll driven by time,
     *  layer speed, and the colorIdx phase. Shared by the stencil-slot and shader-pack decoration RTs.
     *  8.0 is the base UV scale (matches core forGlint), further scaled by the layer's patternScale. */
    private static void animateDecoMatrix(CustomGlint.Layer layer, int colorIdx) {
        float phase = (float) colorIdx / Math.max(1, layer.colors().length);
        long t = (long) (Util.getMillis() * 8.0 * layer.speed());
        float f  = (float) (t % 110000L) / 110000.0F + phase;
        float f1 = (float) (t % 30000L)  /  30000.0F;
        Matrix4f m = new Matrix4f().translation(-f, f1, 0.0F);
        m.rotateZ((float) (Math.PI / 3.0));
        m.translate(f, -f1, 0.0F);
        m.rotateZ((float) (Math.PI / 3.0));
        m.translate(-f, f1, 0.0F);
        m.rotateZ((float) (Math.PI / 3.0));
        m.translate(f, f1, 0.0F);
        m.scale(8.0f * layer.patternScale());
        RenderSystem.setTextureMatrix(m);
    }

    /** Per-slot glint RT cache, keyed by (slot, design, colors, speed, scale, colorIdx). */
    private static final Map<String, RenderType> SLOT_GLINT_CACHE = new HashMap<>();
    private static final Map<String, float[]> SLOT_GLINT_COLORS = new HashMap<>();

    public static RenderType forDecorationGlintSlot(int slot, CustomGlint.Data glint, int layerIdx,
            float[] frameColor, int colorIdx) {
        CustomGlint.Layer layer = glint.layers()[layerIdx];
        if (CustomGlintRenderer.getTexture(layer.design()) == null) return null;
        String key = "ek-deco-slot|" + slot + "|" + layer.design() + "|" + Arrays.toString(layer.colors())
                + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx;
        float[] holder = SLOT_GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = SLOT_GLINT_CACHE.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            return RenderType.create(
                    "customglint:ek_deco_glint_slot|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(CustomGlintRenderer.GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    CustomGlintRenderer.bindGlintTexture(k, tex, holder);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setLayeringState(ekGlintStencilTestLayeringSlot(slot))
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard("customglint:ek_deco_glint_slot_tx",
                                    () -> animateDecoMatrix(layer, colorIdx), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
        });
        CustomGlintRenderer.registerFixedBuffer(cached);
        return cached;
    }

    /** Per-slot CHROMATIC RT cache, keyed like {@link #SLOT_GLINT_CACHE} but on the layer's colours/speed/scale/seed
     *  (chromatic has no design texture). Lives here rather than in the core's BY_CHROMATIC because the key carries
     *  our per-frame stencil slot, which would thrash that LRU. See
     *  {@link CustomGlintRenderer#buildChromaticStencilGlint}. Released by {@link #clearCaches()}. */
    private static final Map<String, RenderType> SLOT_CHROMATIC_CACHE = new HashMap<>();

    /** Chromatic counterpart of {@link #forDecorationGlintSlot} for the stencil paths. Shares the decoration's
     *  stencil slot: the mask trims the glint to the decoration silhouette and is independent of which design the
     *  layer draws, so chromatic gates on exactly the same stamp the texture layers do. */
    public static RenderType forDecorationChromaticSlot(int slot, CustomGlint.Data glint, int layerIdx) {
        CustomGlint.Layer layer = glint.layers()[layerIdx];
        String key = "ek-deco-chroma-slot|" + slot + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed()
                + "|" + layer.patternScale() + "|" + layer.seed() + "|" + layerIdx;
        RenderType cached = SLOT_CHROMATIC_CACHE.get(key);
        if (cached == null) {
            cached = CustomGlintRenderer.buildChromaticStencilGlint(layer, key, ekGlintStencilTestLayeringSlot(slot));
            if (cached == null) return null; // chromatic shader not loaded yet
            SLOT_CHROMATIC_CACHE.put(key, cached);
        }
        CustomGlintRenderer.registerFixedBuffer(cached);
        return cached;
    }

    /**
     * Derive the sibling texture for an EK decoration: base ↔ overlay.
     *
     * EK splits decoration art across two files for dyeable items:
     *   base = textures/.../foo.png         (dye-tinted layer)
     *   overlay = textures/.../foo_overlay.png  (un-tinted detail)
     *
     * For most decorations the SHAPE lives in the base (plumes, surcoats); for crowns it's
     * inverted: base holds only the 16 gem pixels, overlay holds the 96 band pixels. Stencil
     * write must therefore union both texture's opaque pixels so the glint mask covers the
     * complete decoration silhouette regardless of layout. Same for the outline halo.
     *
     * Returns null if the path doesn't match the expected suffix shape, or for non-dyeable
     * decorations whose sibling file doesn't exist.
     */
    private static ResourceLocation siblingTexture(ResourceLocation tex) {
        String path = tex.getPath();
        ResourceLocation sibling;
        if (path.endsWith("_overlay.png")) {
            sibling = new ResourceLocation(tex.getNamespace(),
                    path.substring(0, path.length() - "_overlay.png".length()) + ".png");
        } else if (path.endsWith(".png")) {
            sibling = new ResourceLocation(tex.getNamespace(),
                    path.substring(0, path.length() - ".png".length()) + "_overlay.png");
        } else {
            return null;
        }
        // Existence check avoids triggering the missing-texture pink fallback (which has
        // full alpha → would write stencil over the entire decoration cuboid).
        return Minecraft.getInstance().getResourceManager().getResource(sibling).isPresent() ? sibling : null;
    }

    /**
     * Entry point. Dispatches to one of three implementations based on shader-mod state.
     * Each path has different constraints:
     * <ul>
     *   <li><b>NO SHADERS</b>: main render target is vanilla's, stencil buffer is reliable,
     *       call order matches GL submission order. Stencil-mask path works.</li>
     *   <li><b>SHADERS OFF</b> (Oculus loaded, no pack): FullyBuffered defers each endBatch to a
     *       graph-ordered flush, so every stencil state change has to be baked into an RT layering
     *       shard. Same stencil mask, driven from the shards instead of manual GL.</li>
     *   <li><b>SHADERS ON</b> (active pack): FullyBuffered reorders RT flushes by TransparencyType,
     *       so a stencil silhouette we stamp is gone before our glint tests it. Masks with a depth
     *       pre-pass instead of stencil.</li>
     * </ul>
     */
    public static void applyDecorationGlint(PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint) {
        if (CustomGlintRenderer.isShaderPackActive()) {
            applyDecorationGlint_shadersOn(pose, buffer, light, overlay, parts, decorationTexture, glint);
        } else if (CustomGlintRenderer.isShaderModInstalled()) {
            applyDecorationGlint_shadersOff(pose, buffer, light, overlay, parts, decorationTexture, glint);
        } else {
            applyDecorationGlint_noShaders(pose, buffer, light, overlay, parts, decorationTexture, glint);
        }
    }

    /**
     * Fold a glowing EK decoration into the wearer's glow ring. The decoration {@link ModelPart}s
     * trace their real shape against the decoration texture's alpha; for dyeable decorations whose
     * shape is split across base + overlay (crowns put the band in the overlay, the base holds only
     * the gems), both textures are unioned under the SAME wearer id so the whole silhouette joins the
     * ring. {@code CAT_ARMOR} + {@code identity = entity} matches what {@code HumanoidArmorLayerMixin}
     * uses for the base armor, so body + base armor + every decoration compose as ONE connected ring
     * with no internal seam.
     *
     * <p>Independent of the three glint paths: the glow ring is a post-process composite drained with
     * the world items, so it works the same with or without a shader mod/pack (matching the base
     * armor outline).
     */
    public static void captureDecorationOutline(LivingEntity entity, PoseStack pose, ModelPart[] parts,
            ResourceLocation decorationTexture, int light, ItemStack stack) {
        if (CustomGlintRenderer.isInShadowPass()) return;
        int color = CustomGlintRenderer.resolveGlowColor(stack);
        EntityGlintRender.capturePartsSilhouette(entity, parts, decorationTexture, pose, light, color,
                GlowOutlineRenderer.CAT_ARMOR);
        ResourceLocation sibling = siblingTexture(decorationTexture);
        if (sibling != null) {
            EntityGlintRender.capturePartsSilhouette(entity, parts, sibling, pose, light, color,
                    GlowOutlineRenderer.CAT_ARMOR);
        }
    }

    /**
     * SHADERS OFF path (Oculus loaded, no pack active). FullyBufferedMultiBufferSource defers
     * endBatch(rt) draws to a later graph-ordered flush; manual glStencilXxx calls between
     * passes don't apply. Solution: bake all stencil state into per-slot RT layering shards.
     *
     * Each call allocates a unique stencil slot V. WRITE stamps V; GLINT tests EQUAL V, so it draws
     * on that decoration's own silhouette. Multiple decorations per frame use different slots, so
     * there's no cross-contamination.
     *
     * The dependency graph adds edges based on bs.getBuffer() insertion order: calling WRITE then
     * GLINT registers WRITE-before-GLINT for the eventual flush.
     */
    private static void applyDecorationGlint_shadersOff(PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint) {
        // After an Oculus/Iris pack toggle (activate then deactivate), the entity render dispatcher
        // hands layers a synthetic lambda MultiBufferSource that does NOT extend BufferSource. The old
        // `instanceof BufferSource` check then bailed here and the decoration glint silently vanished
        // until a world reload rebuilt the dispatcher with a real BufferSource. Fall back to the global
        // bufferSource (the canonical singleton that wrapper delegates to under the hood) so getBuffer
        // and endBatch hit the same underlying builders.
        MultiBufferSource.BufferSource bs = buffer instanceof MultiBufferSource.BufferSource direct
                ? direct : Minecraft.getInstance().renderBuffers().bufferSource();
        if (bs == null) return;
        if (CustomGlintRenderer.isInShadowPass()) return;

        // Full pre-flush: drains EK's pending decoration verts and any other queued state so
        // our slot-stamped writes don't compete with unflushed earlier work in the graph order.
        // bs.endLastBatch() (used by _noShaders) only flushes the lastState RT; under FullyBuffered
        // we need a full drain to avoid mid-frame state mixing.
        bs.endBatch();

        int slot = CustomGlintRenderer.nextStencilSlot();

        // Pass 1: per-slot stencil write
        // forDecorationStencilWriteSlot's shard handles enableStencil, pendingFrameStencilClear,
        // and stamps slot V where alpha-pass AND depth-pass. No manual GL here.
        // Union over base + sibling overlay: crowns put their band shape in the overlay and only
        // the gems in the base. Without writing through both, stencil V only covers the gems and
        // the glint never reaches the crown band.
        ResourceLocation siblingWrite = siblingTexture(decorationTexture);
        ResourceLocation[] writeTextures = siblingWrite != null
                ? new ResourceLocation[]{decorationTexture, siblingWrite}
                : new ResourceLocation[]{decorationTexture};
        for (ResourceLocation tex : writeTextures) {
            RenderType writeRT = forDecorationStencilWriteSlot(slot, tex);
            VertexConsumer writeVC = bs.getBuffer(writeRT);
            for (ModelPart part : parts) {
                part.render(pose, writeVC, light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 0);
            }
            bs.endBatch(writeRT);
        }

        // Pass 2: glint where stencil == slot
        // forDecorationGlintSlot's shard does stencil EQUAL slot + polygon offset. No manual GL.
        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> glintVCs = new ArrayList<>();
        List<RenderType> glintRTs = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            // Chromatic has no design PNG, so forDecorationGlintSlot returns null for it and the layer would
            // silently vanish. Its own RT gates on the same slot the texture layers use. No replay wiring: this
            // path only runs with no pack active.
            if (CustomGlint.isChromatic(layers[li])) {
                RenderType rt = forDecorationChromaticSlot(slot, glint, li);
                if (rt != null) { glintRTs.add(rt); glintVCs.add(bs.getBuffer(rt)); }
                continue;
            }
            int[] colors = layers[li].colors();
            if (layers[li].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    CustomGlintRenderer.fillPremul(buf, colors[i]);
                    RenderType rt = forDecorationGlintSlot(slot, glint, li, buf, i);
                    if (rt != null) { glintRTs.add(rt); glintVCs.add(bs.getBuffer(rt)); }
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                CustomGlintRenderer.fillPremul(buf, color);
                RenderType rt = forDecorationGlintSlot(slot, glint, li, buf, 0);
                if (rt != null) { glintRTs.add(rt); glintVCs.add(bs.getBuffer(rt)); }
            }
        }
        if (!glintVCs.isEmpty()) {
            VertexConsumer combined = glintVCs.size() == 1 ? glintVCs.get(0)
                    : VertexMultiConsumer.create(glintVCs.toArray(new VertexConsumer[0]));
            for (ModelPart part : parts) {
                part.render(pose, combined, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            }
            for (RenderType rt : glintRTs) bs.endBatch(rt);
        }
    }

    /**
     * SHADERS ON path. An active shader pack runs FullyBufferedMultiBufferSource with
     * TransparencyType-based RT reordering, so any stencil silhouette we stamp is overwritten or
     * reordered by pack passes before our glint test fires. The slot-based stencil path used in
     * {@link #applyDecorationGlint_shadersOff} (which works because Oculus-no-pack still preserves
     * WRITE-then-TEST insertion edges) goes invisible here.
     *
     * So this path masks with depth instead of stencil. {@link #forDecorationDepthPrewrite} writes
     * depth at opaque texels only (its shader
     * alpha-discards at 0 regardless of pack settings, no polygon offset), then
     * {@link #forDecorationGlintShader} tests EQUAL against that raw depth and paints the glint at
     * the same texels. The prewrite runs over base + sibling texture so it covers the full
     * silhouette no matter which texture holds the shape (crowns put the band in overlay, base is
     * gems only; surcoats are the reverse).
     *
     * On flat decorations whose shape lives entirely in texture alpha (plumes, surcoats) the depth
     * prewrite covers the whole cuboid quad, so the glint paints across it. Known limitation; the
     * alternative of no glint under shaders is worse. 3D decorations (crowns, horns) come out clean
     * because their geometry is the visible silhouette.
     */
    private static void applyDecorationGlint_shadersOn(PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint) {
        if (CustomGlintRenderer.isInShadowPass()) return;

        // Sibling overlay union: dyeable decorations have shape split across base+overlay
        // (crowns: shape in overlay, base = gems only; surcoats: opposite). Compute once.
        ResourceLocation sibling = siblingTexture(decorationTexture);

        // Glint: depth-prewrite self-mask
        // forDecorationDepthPrewrite uses RENDERTYPE_OUTLINE_SHADER which alpha-discards at 0
        // in fragment code (independent of shader pack settings), writing depth ONLY at opaque
        // texels, no polygon offset, raw projected depth. forDecorationGlintShader then tests
        // EQUAL against that raw depth (also no offset), so it draws only at the same opaque
        // texels. Self-mask without stencil. Both RTs are late-tagged so they flush after EK's
        // own decoration draw under FullyBuffered. Union over base + sibling so the prewrite
        // covers the complete silhouette regardless of which texture holds the shape (crowns
        // put the band in overlay, base = gems only; surcoats are the opposite).
        ResourceLocation[] depthTextures = sibling != null
                ? new ResourceLocation[]{decorationTexture, sibling}
                : new ResourceLocation[]{decorationTexture};
        for (ResourceLocation tex : depthTextures) {
            RenderType prewriteRT = forDecorationDepthPrewrite(tex);
            VertexConsumer prewriteVC = buffer.getBuffer(prewriteRT);
            for (ModelPart part : parts) {
                part.render(pose, prewriteVC, light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            }
        }

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> glintVCs = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            // Chromatic has no design PNG, so forDecorationGlintShader returns null for it and the layer would
            // silently vanish. chromaticWorldBuffer is a plain getBuffer, kept as the single seam for chromatic
            // surfaces. The depth prewrite above masks it the same way it masks the texture layers.
            if (CustomGlint.isChromatic(layers[li])) {
                RenderType rt = CustomGlintRenderer.forChromaticDecorationGlint(glint, li);
                if (rt != null) glintVCs.add(CustomGlintRenderer.chromaticWorldBuffer(buffer, rt));
                continue;
            }
            int[] colors = layers[li].colors();
            if (layers[li].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    CustomGlintRenderer.fillPremul(buf, colors[i]);
                    RenderType rt = forDecorationGlintShader(glint, li, buf, i);
                    if (rt != null) glintVCs.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                CustomGlintRenderer.fillPremul(buf, color);
                RenderType rt = forDecorationGlintShader(glint, li, buf, 0);
                if (rt != null) glintVCs.add(buffer.getBuffer(rt));
            }
        }
        if (!glintVCs.isEmpty()) {
            VertexConsumer combined = glintVCs.size() == 1 ? glintVCs.get(0)
                    : VertexMultiConsumer.create(glintVCs.toArray(new VertexConsumer[0]));
            for (ModelPart part : parts) {
                part.render(pose, combined, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            }
        }
    }

    /**
     * Depth pre-pass RT for the shader-pack glint path. RENDERTYPE_OUTLINE_SHADER does its own
     * alpha-discard (alpha == 0) in fragment code, independent of shader pack settings,
     * so depth gets written ONLY at opaque decoration texels. No polygon offset (raw projected
     * depth), so the subsequent EQUAL glint pass (also no offset) compares raw depths and
     * matches exactly: no FP epsilon mismatch with EK's polygon-offset depth.
     *
     * Color write disabled: we only need this to land in the depth buffer. Tagged late so
     * it flushes after EK's color/depth land (we want OUR depth to be the most recent value
     * at each opaque texel, so the glint EQUAL sees a value we control).
     */
    private static final Map<ResourceLocation, RenderType> DEPTH_PREWRITE_CACHE = new HashMap<>();
    private static RenderType forDecorationDepthPrewrite(ResourceLocation decoTex) {
        return DEPTH_PREWRITE_CACHE.computeIfAbsent(decoTex, tex -> {
            RenderType rt = RenderType.create(
                    "customglint:ek_deco_depth_prewrite",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(DEPTH_WRITE)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setOutputState(CustomGlintRenderer.FORCE_MAIN_TARGET)
                            .createCompositeState(false));
            CustomGlintRenderer.registerFixedBuffer(rt);
            CustomGlintRenderer.tagAsLateRenderForShaders(rt);
            return rt;
        });
    }

    /**
     * Shader-pack-only glint RT: EQUAL depth + VIEW_OFFSET_Z_LAYERING (matches EK's
     * armorCutoutNoCull polygon offset exactly). Self-masks to opaque decoration texels:
     * EK's armorCutoutNoCull discards transparent fragments (alpha &lt; 0.1), so depth is
     * written ONLY where the decoration has visible pixels. EQUAL passes only at those
     * exact texels, transparent regions don't draw → glint follows the decoration shape
     * without needing a stencil mask.
     *
     * Why we can't just switch {@link #forDecorationGlintSlot} to EQUAL: that RT is used by the
     * no-shaders and shaders-off paths which rely on its LEQUAL behavior with stencil masking
     * for cases where the depth-match approach was historically observed to fail (FP epsilon,
     * documented in this file's javadoc). Under shader packs we don't have a stencil option,
     * so depth-self-mask is the only path, and empirically it works here.
     *
     * Tagged for shader-pack late render (LINES bucket) so this flushes AFTER EK's decoration
     * depth lands. Otherwise FullyBuffered ordering could schedule the glint first and EQUAL
     * compares against clear-depth → invisible.
     */
    private static final Map<String, RenderType> SHADER_GLINT_CACHE = new HashMap<>();
    private static final Map<String, float[]> SHADER_GLINT_COLORS = new HashMap<>();
    public static RenderType forDecorationGlintShader(CustomGlint.Data glint, int layerIdx,
            float[] frameColor, int colorIdx) {
        CustomGlint.Layer layer = glint.layers()[layerIdx];
        if (CustomGlintRenderer.getTexture(layer.design()) == null) return null;
        String key = "ek-deco-sh|" + layer.design() + "|" + Arrays.toString(layer.colors())
                + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx;
        float[] holder = SHADER_GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = SHADER_GLINT_CACHE.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    "customglint:ek_decoration_glint_sh|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(CustomGlintRenderer.GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    CustomGlintRenderer.bindGlintTexture(k, tex, holder);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            // No layering: match the depth pre-pass which uses no polygon
                            // offset, so EQUAL compares raw projected depths and matches.
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard("customglint:ek_decoration_glint_sh_texturing",
                                    () -> animateDecoMatrix(layer, colorIdx), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            CustomGlintRenderer.tagAsLateRenderForShaders(rt);
            return rt;
        });
        CustomGlintRenderer.registerFixedBuffer(cached);
        return cached;
    }

    /**
     * No-shaders path; delegates to {@link #applyDecorationGlint_shadersOff}. The per-(slot, tex) RT closure
     * is what makes base AND overlay textures both stamp the stencil silhouette, needed for crown-style
     * decorations whose shape lives in the overlay rather than the (gems-only) base.
     */
    private static void applyDecorationGlint_noShaders(PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint) {
        applyDecorationGlint_shadersOff(pose, buffer, light, overlay, parts, decorationTexture, glint);
    }
}
