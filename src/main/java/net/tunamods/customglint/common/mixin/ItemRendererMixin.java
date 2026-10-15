package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import net.tunamods.customglint.common.client.GnetumHudCompat;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Intercepts render() to capture item stack + trigger glowing outline; intercepts getFoilBuffer/getFoilBufferDirect to inject custom per-item glint. Dual SRG/named targets, require=0 on all. */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    /** True while render() is drawing a flat baked-quad item, false for a special / 3D BEWLR one. Set at
     *  render() HEAD beside CURRENT_ITEM_STACK, because applyGlint needs it and is handed no model. Only the
     *  flat items go through Embeddium's bulk quad encoder, which is what decides their glint path under a
     *  pack (see applyGlint). */
    private static final ThreadLocal<Boolean> CG_FLAT_ITEM = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // Stack capture (HEAD)

    /** SRG target: captures the stack before render begins (obfuscated environments). */
    @Inject(method = "m_115143_", at = @At("HEAD"), require = 0)
    private void cg_captureStack_srg(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        cg_onRenderHead(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pBuffer, pCombinedLight, pCombinedOverlay, pModel);
    }

    /** Named target: captures the stack before render begins (dev/deobf environments). */
    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_captureStack_named(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        cg_onRenderHead(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pBuffer, pCombinedLight, pCombinedOverlay, pModel);
    }

    /** HEAD logic shared between SRG and named injects: capture the stack so the body's
     *  getFoilBuffer (for glint) can resolve the glint data for this item. */
    private static void cg_onRenderHead(ItemStack stack, ItemDisplayContext ctx, boolean lh,
            PoseStack pose, MultiBufferSource buffer, int light, int overlay, BakedModel model) {
        CustomGlintRenderer.CURRENT_ITEM_STACK.set(stack);
        CG_FLAT_ITEM.set(model != null && !model.isCustomRenderer());
        // Gnetum caches the in-game HUD per element and re-renders each only every few frames; a glinted /
        // glowing item in a HUD slot needs its element live every frame or the glint freezes and the glow
        // ring flickers. Tell gnetum to stop caching whatever HUD element it is mid-render of (the hotbar).
        // No-op off gnetum, off the HUD, or once that element is already dropped.
        if (ctx == ItemDisplayContext.GUI && (CustomGlint.readCached(stack) != null || CustomGlint.isGlowing(stack))) {
            GnetumHudCompat.disableHudCachingForCurrentElement();
        }
    }

    // Stack clear (RETURN)

    /** SRG target: captures the glow-outline silhouette, then clears the captured stack. */
    @Inject(method = "m_115143_", at = @At("RETURN"), require = 0)
    private void cg_clearStack_srg(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        cg_captureGlowOutline(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pCombinedLight, pModel);
        cg_drawGlintUnderPack(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pBuffer, pCombinedLight, pModel);
        CustomGlintRenderer.CURRENT_ITEM_STACK.remove();
        CG_FLAT_ITEM.remove();
    }

    /** Named target: captures the glow-outline silhouette, then clears the captured stack. */
    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_clearStack_named(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        cg_captureGlowOutline(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pCombinedLight, pModel);
        cg_drawGlintUnderPack(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pBuffer, pCombinedLight, pModel);
        CustomGlintRenderer.CURRENT_ITEM_STACK.remove();
        CG_FLAT_ITEM.remove();
    }

    // Glow-outline capture
    // For a glowing item, snapshot its silhouette (baked quads + camera-relative pose + captured modelview
    // + light + animated glow colour) so GlowOutlineRenderer can replay it into the mask and ring it.
    // World items (third-person held, dropped, item frames, other players) drain at AFTER_WEATHER; the
    // first-person held item drains at the hand pass. GUI icons and special / 3D BEWLR items are deferred.
    private static void cg_captureGlowOutline(ItemStack stack, ItemDisplayContext ctx, boolean leftHand,
            PoseStack pose, int light, BakedModel model) {
        // Guard against re-entry from our own special-item re-render (renderStatic re-runs render(),
        // whose RETURN re-enters here).
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        if (model == null) return;
        if (!CustomGlint.isGlowing(stack)) return;
        boolean gui = ctx == ItemDisplayContext.GUI;
        // FP-replacing mods (Punchy, First-Person Model) draw the held item with a THIRD_PERSON display
        // context during the first-person hand pass; treat anything captured inside that pass as first-person
        // so it routes to the FP held queue (drained under the hand-FOV projection) instead of the world queue.
        boolean firstPerson = !gui && (ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || GlowOutlineRenderer.isFpHand());

        int color = CustomGlintRenderer.resolveGlowColor(stack);
        // GUI-only icon anchor (slot centre + on-screen size + texture resolution) for the drain's ring
        // sizing + slot clamp; null for world / first-person (they scissor by silhouette bounds).
        float[] guiAnchor = gui ? cg_guiAnchor(pose, model) : null;

        // Special / 3D BEWLR items (trident, shield, any isCustomRenderer item, incl. modded) have no
        // baked quads. Re-render the whole item through renderStatic into a record-only buffer (guarded by
        // IN_OUTLINE), capturing its animated, already-transformed geometry bucketed by texture: generic,
        // no per-item knowledge. Works in the GUI too (inventory/hotbar trident, shield, modded 3D items).
        if (model.isCustomRenderer()) {
            cg_captureSpecialOutline(stack, ctx, leftHand, pose, color, guiAnchor);
            return;
        }

        // render() pushed the pose, applied the item's display transform (handleCameraTransforms ->
        // applyTransform) + the (-0.5,-0.5,-0.5) centering, drew the quads, then popped, so pose.last()
        // at this RETURN is the OUTER pose, missing both. Reproduce that sequence on a copy so the
        // silhouette matches the item's real on-screen scale and position.
        PoseStack tp = new PoseStack();
        tp.last().pose().set(pose.last().pose());
        tp.last().normal().set(pose.last().normal());
        BakedModel rendered;
        try {
            rendered = model.applyTransform(ctx, tp, leftHand);
        } catch (Throwable ignored) {
            // Some modded models throw from applyTransform; skip the glow capture rather than crash render.
            return;
        }
        tp.translate(-0.5F, -0.5F, -0.5F);
        if (rendered == null || rendered.isCustomRenderer()) return;

        List<BakedQuad> quads = cg_collectQuads(rendered);
        if (quads.isEmpty()) return;
        // Snapshot the live modelview the item is drawn under (camera transform) so the deferred world/FP
        // replay reproduces the exact transform instead of the differing drain-time one. The GUI drain is
        // immediate (matrices still live) so it ignores this.
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        // tp is a fresh PoseStack created for this capture and never popped/reused, so its top Pose is a
        // stable object safe to hand off (1.20.1 has no PoseStack.Pose#copy()).
        if (gui) GlowOutlineRenderer.queueGuiItem(quads, tp.last(), light, color, guiAnchor);
        else if (firstPerson) GlowOutlineRenderer.queueHeldFpItem(quads, tp.last(), modelView, light, color);
        else GlowOutlineRenderer.queueWorldItem(quads, tp.last(), modelView, light, color);
    }

    // Shader-pack glint draw (item path)
    // Under an active shaderpack a flat item's glint is NEVER combined with the base in-phase (see applyGlint:
    // the base draws alone so Embeddium's bulk encoder can't drop it when a chromatic draw flips it on for the
    // frame; the "everything glinted fills solid" bug, hotbar included). The glint is drawn HERE instead, into
    // its own buffer on the same MultiBufferSource the base used. Being a LONE buffer rather than a multi with
    // the base is what keeps the base's writes safe.
    //
    // LOAD-BEARING: draw in-phase, do NOT defer to a post-composite replay. A replay runs after Iris finalizes,
    // where Iris's override gate is false, so it binds OUR program, which applies no TAA jitter. Packs that
    // jitter gbuffer vertices (BSL: TAAJitter(gl_Position.xy, gl_Position.w) in gbuffers_hand/gbuffers_entities;
    // Bliss: offsets[framemod8] * gl_Position.w * texelSize) leave JITTERED depth in the buffer, and these RTs
    // test EQUAL against it. The un-jittered replay then misses nearly every fragment and reads as static under
    // the item. Complementary's gbuffers_* are plain ftransform() (its TAA is reprojection-only, in composite),
    // so the replay only ever looked right there. In-phase the draw takes the pack's own GLINT program and the
    // same jitter as the base, so EQUAL matches. That is why chromatic was never affected.
    //
    // Covers the first-person hand: drawing in-phase into the live buffer means the hand pass's own projection
    // is already current, which is the whole reason the hand needed exempting while this was a replay.
    // Flat items only. Special / 3D BEWLR items are a follow-up (they keep the multi path in applyGlint).
    private static void cg_drawGlintUnderPack(ItemStack stack, ItemDisplayContext ctx, boolean leftHand,
            PoseStack pose, MultiBufferSource buffer, int light, BakedModel model) {
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        if (model == null || model.isCustomRenderer()) return;
        if (!CustomGlintRenderer.isShaderPackActive()) return;
        // The Iris shadow pass re-renders the world into the shadow map; a glint there is meaningless and the
        // base already drew alone, so just skip it.
        if (CustomGlintRenderer.isInShadowPass()) return;
        CustomGlint.Data glint = CustomGlint.readCached(stack);
        if (glint == null) return;

        // Reconstruct the item's display pose exactly as render() applied it (then popped), so the drawn quads
        // land in the same camera-pose space the foil vertex stream used. Mirrors cg_captureGlowOutline.
        PoseStack tp = new PoseStack();
        tp.last().pose().set(pose.last().pose());
        tp.last().normal().set(pose.last().normal());
        BakedModel rendered;
        try {
            rendered = model.applyTransform(ctx, tp, leftHand);
        } catch (Throwable ignored) {
            return;
        }
        tp.translate(-0.5F, -0.5F, -0.5F);
        if (rendered == null || rendered.isCustomRenderer()) return;
        List<BakedQuad> quads = cg_collectQuads(rendered);
        if (quads.isEmpty()) return;

        // isItem=true: these are flat sprite items (8x atlas-calibrated scale), the same value the in-phase
        // foil path uses (ItemRenderer.render calls getFoilBuffer* with isItem=true).
        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            if (CustomGlint.isChromatic(layers[layerIdx])) {
                RenderType crt = CustomGlintRenderer.forChromaticGlint(glint, layerIdx, true);
                // Chromatic's slick is a baked texture, so this RT binds the pack's own GLINT program and draws
                // in-gbuffer where the pack TAA-resolves it. The texture layers below now draw the same way.
                if (crt != null) cg_emitGlint(crt, quads, tp.last(), light, buffer);
                continue;
            }
            int[] colors = layers[layerIdx].colors();
            if (layers[layerIdx].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    CustomGlintRenderer.fillPremul(buf, colors[i]);
                    RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, true, i);
                    if (rt != null) cg_emitGlint(rt, quads, tp.last(), light, buffer);
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                CustomGlintRenderer.fillPremul(buf, color);
                RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, true, 0);
                if (rt != null) cg_emitGlint(rt, quads, tp.last(), light, buffer);
            }
        }
    }

    /** One glint layer: draw its quads into their own buffer on {@code buffer}. A LONE buffer, not a multi with
     *  the base, so Embeddium never drops the base. The glint colour rides the RenderType's ColorModulator, so
     *  the per-vertex colour passed to putBulkData (white) is ignored by the glint shader. */
    private static void cg_emitGlint(RenderType rt, List<BakedQuad> quads, PoseStack.Pose pose, int light,
            MultiBufferSource buffer) {
        VertexConsumer vc = buffer.getBuffer(rt);
        for (BakedQuad q : quads) vc.putBulkData(pose, q, 1.0f, 1.0f, 1.0f, light, OverlayTexture.NO_OVERLAY);
    }

    /** Capture a special / 3D BEWLR item's silhouette by re-rendering it through renderStatic into a
     *  record-only buffer. {@code pose} is the OUTER pose at render() RETURN; renderStatic re-applies the
     *  display transform, so the recorded positions are camera-relative and already include the item's
     *  animation (riptide spin, block tilt: it lives in the pose the model draws under). IN_OUTLINE
     *  prevents recursion and makes applyGlint route to the bare recording buffer. The capture buckets
     *  vertices by the texture each RenderType draws through, so the silhouette traces the real item shape
     *  via that texture's alpha (a trident traces the trident, not its square model hull). */
    private static void cg_captureSpecialOutline(ItemStack stack, ItemDisplayContext ctx, boolean leftHand,
            PoseStack pose, int color, float[] guiAnchor) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        PoseStack tp = new PoseStack();
        tp.last().pose().set(pose.last().pose());
        tp.last().normal().set(pose.last().normal());
        GlowOutlineRenderer.CapturingBufferSource cap = new GlowOutlineRenderer.CapturingBufferSource();
        CustomGlintRenderer.IN_OUTLINE.set(true);
        try {
            mc.getItemRenderer().renderStatic(mc.player, stack, ctx, leftHand, tp, cap, mc.level,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        } catch (Throwable ignored) {
            // A throwing BEWLR (modded special renderer) must skip the glow capture, not crash the item render.
            return;
        } finally {
            CustomGlintRenderer.IN_OUTLINE.set(false);
        }
        cap.queueGroups(color, modelView, ctx, guiAnchor);
    }

    /** Icon anchor for the GUI glow drain: {@code [x,y,z]} = the OUTER pose translation (the slot/preview
     *  centre, before the model's display transform), {@code [3]} = the icon's nominal half-size in GUI px
     *  (½ the pose x-axis scale; 8 for a 16-px slot, 40 for the 5x wand preview), {@code [4]} = the item
     *  texture's resolution in px (16 / 32 / 64…), read from the model's particle sprite. The drain sizes the
     *  ring per TEXTURE pixel, so a 32x32 / 64x64 item doesn't get a ring twice / four times too thick. */
    private static float[] cg_guiAnchor(PoseStack pose, BakedModel model) {
        Matrix4f m = pose.last().pose();
        float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
        float texRes = 16.0f;
        try {
            TextureAtlasSprite sp = model.getParticleIcon();
            if (sp != null && sp.contents() != null && sp.contents().width() > 0) texRes = sp.contents().width();
        } catch (Throwable ignored) {
            // Some modded models throw from the no-data getParticleIcon(); fall back to 16.
        }
        return new float[]{ m.m30(), m.m31(), m.m32(), sx * 0.5f, texRes };
    }

    /** Every baked quad of {@code model} (all directions + the general bucket), so the silhouette traces
     *  the item's full real shape including the 1/16 extrusion rim. */
    private static List<BakedQuad> cg_collectQuads(BakedModel model) {
        List<BakedQuad> out = new ArrayList<>();
        RandomSource random = RandomSource.create();
        // Fixed seed per bucket: vanilla getQuads seeds the same way, so quad selection stays deterministic.
        try {
            for (Direction dir : Direction.values()) {
                random.setSeed(42L);
                out.addAll(model.getQuads(null, dir, random));
            }
            random.setSeed(42L);
            out.addAll(model.getQuads(null, null, random));
        } catch (Throwable ignored) {
            // Some modded models throw from getQuads(null, ...); use whatever was collected.
        }
        return out;
    }

    // getFoilBuffer intercepts
    // getFoilBuffer = batched rendering (world items, item frames). @Inject stacks; isCancelled() yields.

    /** SRG target: intercepts getFoilBuffer in obfuscated environments. */
    @Inject(method = "m_115211_", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cg_onFoilBuffer_srg(MultiBufferSource buffer, RenderType renderType,
            boolean isItem, boolean hasFoil, CallbackInfoReturnable<VertexConsumer> cir) {
        if (cir.isCancelled()) return;
        VertexConsumer consumer = applyGlint(buffer, renderType, isItem);
        if (consumer != null) cir.setReturnValue(consumer);
    }

    /** Named target: intercepts getFoilBuffer in dev/deobf environments. */
    @Inject(
        method = "getFoilBuffer(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At("HEAD"), cancellable = true, require = 0, remap = false
    )
    private static void cg_onFoilBuffer_named(MultiBufferSource buffer, RenderType renderType,
            boolean isItem, boolean hasFoil, CallbackInfoReturnable<VertexConsumer> cir) {
        if (cir.isCancelled()) return;
        VertexConsumer consumer = applyGlint(buffer, renderType, isItem);
        if (consumer != null) cir.setReturnValue(consumer);
    }

    // getFoilBufferDirect intercepts
    // getFoilBufferDirect = direct/GUI (immediate-mode) rendering.

    /** SRG target: intercepts getFoilBufferDirect in obfuscated environments. */
    @Inject(method = "m_115222_", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cg_onFoilBufferDirect_srg(MultiBufferSource buffer, RenderType renderType,
            boolean noEntity, boolean withGlint, CallbackInfoReturnable<VertexConsumer> cir) {
        if (cir.isCancelled()) return;
        VertexConsumer consumer = applyGlint(buffer, renderType, noEntity);
        if (consumer != null) cir.setReturnValue(consumer);
    }

    /** Named target: intercepts getFoilBufferDirect in dev/deobf environments. */
    @Inject(
        method = "getFoilBufferDirect(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At("HEAD"), cancellable = true, require = 0, remap = false
    )
    private static void cg_onFoilBufferDirect_named(MultiBufferSource buffer, RenderType renderType,
            boolean noEntity, boolean withGlint, CallbackInfoReturnable<VertexConsumer> cir) {
        if (cir.isCancelled()) return;
        VertexConsumer consumer = applyGlint(buffer, renderType, noEntity);
        if (consumer != null) cir.setReturnValue(consumer);
    }

    /** Returns a VertexMultiConsumer combining all glint layers + base renderType, or null if no glint. */
    private static VertexConsumer applyGlint(MultiBufferSource buffer, RenderType renderType, boolean isItem) {
        // During the glow-outline special-item re-render, route foil requests to the bare recording
        // buffer so the silhouette captures the item's geometry, not fanned-out glint layers.
        if (CustomGlintRenderer.IN_OUTLINE.get()) return buffer.getBuffer(renderType);
        ItemStack stack = CustomGlintRenderer.CURRENT_ITEM_STACK.get();
        if (stack == null) return null;
        CustomGlint.Data glint = CustomGlint.readCached(stack);
        if (glint == null) return null;

        // Under an active shaderpack, NEVER combine the base with our glint in a VertexMultiConsumer here:
        // Embeddium's bulk vertex encoder (which a chromatic entity/item draw flips on for the whole frame,
        // world AND the GUI that renders after it) mangles the stream when our foreign glint delegate shares a
        // multi with the base. The base can lose its texture, and the glint samples one texel and fills with a
        // solid colour. Return the base ALONE so it always renders clean; the glint is drawn separately in
        // cg_drawGlintUnderPack at render() RETURN.
        //
        // This covers the first-person hand too. It used to be exempted here, back when the separate draw was a
        // post-composite replay that had to snapshot the hand's own projection to reproduce it. That replay is
        // gone. cg_drawGlintUnderPack draws in-phase into this same buffer, so the hand pass's projection is
        // already the live one and there is nothing to reproduce. Left on the multi path, the hand was the one
        // surface still hitting the encoder bug: the design sampled flat and read as a solid tint over the item.
        //
        // Special / 3D BEWLR items are the exception, and only in the hand: they draw through renderToBuffer,
        // never the bulk quad encoder, so the multi is safe for them. And cg_drawGlintUnderPack skips custom
        // renderers, so routing them there would drop their glint entirely rather than fix it.
        if (CustomGlintRenderer.isShaderPackActive()
                && (!GlowOutlineRenderer.isFpHand() || CG_FLAT_ITEM.get())) {
            return buffer.getBuffer(renderType);
        }

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();

        List<VertexConsumer> list = new ArrayList<>(layers.length + 1);
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            // Procedural chromatic: one shader-driven draw (the palette + seed ride the RenderType), no
            // per-colour fan-out and no texture sampling.
            //
            // Under a pack this line is now reached only for a first-person BEWLR item; every flat item took
            // the base-alone return above. chromaticWorldBuffer is a straight getBuffer either way. It stays
            // as the single seam every chromatic fan-out routes through.
            if (CustomGlint.isChromatic(layers[layerIdx])) {
                RenderType crt = CustomGlintRenderer.forChromaticGlint(glint, layerIdx, isItem);
                if (crt != null) list.add(CustomGlintRenderer.chromaticWorldBuffer(buffer, crt));
                continue;
            }
            int[] colors = layers[layerIdx].colors();
            if (layers[layerIdx].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    CustomGlintRenderer.fillPremul(buf, colors[i]);
                    RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, isItem, i);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                CustomGlintRenderer.fillPremul(buf, color);
                RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, isItem, 0);
                if (rt != null) list.add(buffer.getBuffer(rt));
            }
        }
        if (list.isEmpty()) return null;
        list.add(buffer.getBuffer(renderType));
        // Collapse duplicate glint delegates: Sodium/Embeddium's VertexMultiConsumer throws "Duplicate delegates"
        // if the same buffer appears twice. In-place, no allocation when there are none (the common case).
        for (int i = list.size() - 1; i > 0; i--)
            if (list.indexOf(list.get(i)) != i) list.remove(i);
        return list.size() == 1 ? list.get(0) : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
    }

}
