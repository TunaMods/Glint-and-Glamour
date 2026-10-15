package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
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
import net.minecraft.world.item.Items;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Intercepts render() to capture item stack + trigger glowing outline; intercepts getFoilBuffer/getFoilBufferDirect to inject custom per-item glint. */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    // ── Stack capture (HEAD) ──────────────────────────────────────────────────

    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_captureStack_named(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        cg_onRenderHead(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pBuffer, pCombinedLight, pCombinedOverlay, pModel);
    }

    /** HEAD logic: capture the stack being rendered so the getFoilBuffer intercepts below can read its
     *  glint Data. The matching RETURN inject clears it. (The glow-outline capture happens at RETURN in
     *  cg_captureGlowOutline, not here.) */
    private static void cg_onRenderHead(ItemStack stack, ItemDisplayContext ctx, boolean lh,
            PoseStack pose, MultiBufferSource buffer, int light, int overlay, BakedModel model) {
        CustomGlintRenderer.CURRENT_ITEM_STACK.set(stack);
        CustomGlintRenderer.CURRENT_CTX.set(ctx);
        CustomGlintRenderer.CURRENT_IS_SPECIAL.set(model != null && model.isCustomRenderer());
    }

    // ── Stack clear + item outline (RETURN) ─────────────────────────────────

    /** Applies item outline for glowing items, then clears the captured stack. */
    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_clearStack_named(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        cg_captureGlowOutline(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pCombinedLight, pModel);
        CustomGlintRenderer.CURRENT_ITEM_STACK.remove();
        CustomGlintRenderer.CURRENT_CTX.remove();
        CustomGlintRenderer.CURRENT_IS_SPECIAL.remove();
    }

    // ── getFoilBuffer intercepts ─────────────────────────────────────────────
    // getFoilBuffer = batched rendering (world items, item frames). @Inject stacks; isCancelled() yields.

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

    // ── getFoilBufferDirect intercepts ───────────────────────────────────────
    // getFoilBufferDirect = direct/GUI (immediate-mode) rendering.

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

    // ─────────────────────────────────────────────────────────────────────────

    /** Buffer for a glint layer RenderType: the batched GUI source when the open screen defers its glint
     *  (creative menu, or a screen that armed the batch), else the normal inline buffer. See applyGlint for
     *  which screens defer and where each drains. */
    private static VertexConsumer cg_glintBuf(MultiBufferSource buffer, RenderType rt, boolean guiHud) {
        return guiHud ? CustomGlintRenderer.guiGlintBuffer(rt) : buffer.getBuffer(rt);
    }

    /** Returns a VertexMultiConsumer combining all glint layers + base renderType, or null if no glint. */
    private static VertexConsumer applyGlint(MultiBufferSource buffer, RenderType renderType, boolean isItem) {
        // During our record-only glow-outline capture re-render, route all foil requests to the
        // bare base buffer. Otherwise vanilla's getFoilBuffer returns a VertexMultiConsumer of
        // (glint, base), and because the capturing buffer source redirects every RenderType to the
        // same underlying builder, the two delegates would share one builder and tear its vertex
        // state (vertex,vertex,color,color,...,endVertex,endVertex). Items that hardcode
        // isFoil()=true (e.g. Ice & Fire's ItemAlchemySword: dragonbone_sword_fire/ice/lightning)
        // tripped this whenever a custom glint+outline was applied: glint worked, outline didn't.
        if (CustomGlintRenderer.IN_OUTLINE.get()) return buffer.getBuffer(renderType);
        ItemStack stack = CustomGlintRenderer.CURRENT_ITEM_STACK.get();
        if (stack == null) return null;
        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        if (glint == null) return null;

        // Batch the glint into the private deferred source so many same-config icons draw in ONE endBatch
        // instead of one GuiGraphics.flush() apiece, the dominant GUI cost with the creative tab's many
        // distinct-design icons. Only two cases defer, and BOTH drain immediately after their own icon pass
        // (so the deferred glint's EQUAL depth test still matches the icons' just-committed depth): the
        // CREATIVE menu (drained before the tooltip, see AbstractContainerScreenMixin) and any screen that
        // ARMS the batch around its icon pass and drains right after (guiGlintBatchArmed, the Glint Table's
        // scrollable design + printed palettes, which draw previews AFTER super.render and so have no later
        // drain to rely on).
        //
        // The HUD hotbar (no screen open) does NOT defer: it draws glint inline, in the item's own
        // GuiGraphics.flush(), the same path the survival inventory uses. It used to defer and drain at
        // Gui.render RETURN, a whole HUD after the icons and past Gui.render's closing disableDepthTest; a
        // shader-pack reload left the hotbar's committed depth in a state that late drain read wrong, so the
        // EQUAL glint tested against the wrong depth and vanished until a screen (chat) forced it inline. The
        // ~10 hotbar icons make inline's per-item flush cost irrelevant.
        Screen cgScreen = Minecraft.getInstance().screen;
        boolean guiHud = CustomGlintRenderer.CURRENT_CTX.get() == ItemDisplayContext.GUI
                && (cgScreen instanceof CreativeModeInventoryScreen
                    || CustomGlintRenderer.guiGlintBatchArmed);

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        // A shield draws its chromatic slick through a sprite-atlas-wrapped buffer that compresses the model
        // UVs, so it needs the shield-sprite scale compensation; the raw-UV trident and flat items do not.
        boolean isShield = stack.is(Items.SHIELD);

        // Fast path, the overwhelmingly common single-layer glint that resolves to ONE glint delegate: any
        // non-simultaneous layer (it animates down to a single colour) or a simultaneous layer with ≤1 colour.
        // Returns the (glint, base) pair straight from the 2-arg VertexMultiConsumer, skipping the ArrayList
        // + dedup array the general multi-delegate path below allocates for every item, every frame.
        if (layers.length == 1 && !CustomGlint.isChromatic(layers[0])) {
            CustomGlint.Layer only = layers[0];
            int[] cols = only.colors();
            if (!only.simultaneous() || cols.length <= 1) {
                int color = only.simultaneous()
                        ? (cols.length == 0 ? 0xFFFFFFFF : cols[0])
                        : CustomGlintRenderer.computeAnimatedColor(glint, 0);
                // Atlased GUI batch: on a many-icon screen with the batch armed (guiHud) and this a flat item
                // icon, route the single glint layer through the ONE shared design-atlas RenderType so every
                // distinct-design icon collapses into a single draw (design/colour/scroll/scale ride the vertex
                // payload). Designs the atlas doesn't hold (overflow / load failure) return null → forGlint below.
                if (guiHud && isItem) {
                    VertexConsumer atlasGlint = CustomGlintRenderer.guiAtlasGlintBuffer(glint, 0, 0, color);
                    if (atlasGlint != null) {
                        VertexConsumer base = buffer.getBuffer(renderType);
                        return VertexMultiConsumer.create(atlasGlint, base);
                    }
                }
                cg_packColor(buf, color);
                RenderType rt = CustomGlintRenderer.forGlint(glint, 0, buf, isItem, 0);
                if (rt == null) return null;
                VertexConsumer glintBuf = cg_glintBuf(buffer, rt, guiHud);
                VertexConsumer base = buffer.getBuffer(renderType);
                // glintBuf==base only when a Sodium immediate source hands back one builder for both; a
                // VertexMultiConsumer can't multiplex a builder against itself, so collapse to the base.
                return glintBuf == base ? base : VertexMultiConsumer.create(glintBuf, base);
            }
        }

        // General path: multi-layer, or a simultaneous layer fanning out one draw per colour.
        List<VertexConsumer> list = new ArrayList<>();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            // Procedural chromatic: one shader-driven draw (the palette + seed ride the RenderType), no
            // per-colour fan-out and no texture sampling.
            if (CustomGlint.isChromatic(layers[layerIdx])) {
                // Under a shader pack the chromatic program is hijacked; flat items in every context (world,
                // first-person, GUI) are captured for the post-Iris overlay in cg_captureGlowOutline instead.
                boolean divertedItem = isItem && CustomGlintRenderer.isShaderPackActive();
                if (!divertedItem) {
                    // Special 3D BEWLR items (shield/trident) take the special-item scale so the in-phase draw
                    // matches the shader-pack overlay; flat/held items keep the atlas/3D scale. The shield's UVs
                    // are sprite-atlas-compressed, so it takes the extra shield-sprite compensation.
                    RenderType crt;
                    if (isShield) {
                        crt = CustomGlintRenderer.forChromaticShieldGlint(glint, layerIdx);
                    } else if (CustomGlintRenderer.CURRENT_IS_SPECIAL.get()) {
                        crt = CustomGlintRenderer.forChromaticSpecialGlint(glint, layerIdx);
                    } else {
                        crt = CustomGlintRenderer.forChromaticGlint(glint, layerIdx, isItem);
                    }
                    if (crt != null) cg_addDistinct(list, cg_glintBuf(buffer, crt, guiHud));
                }
                continue;
            }
            // An undyed (empty-palette) non-chromatic layer renders white so the design stays visible without
            // any dye being stored. The animated branch already returns white for empty; default here for the
            // simultaneous fan-out so it draws one white pass instead of nothing.
            int[] colors = layers[layerIdx].colors().length == 0 ? CustomGlintRenderer.WHITE_COLOR : layers[layerIdx].colors();
            if (layers[layerIdx].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    cg_packColor(buf, colors[i]);
                    RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, isItem, i);
                    if (rt != null) cg_addDistinct(list, cg_glintBuf(buffer, rt, guiHud));
                }
            } else {
                cg_packColor(buf, CustomGlintRenderer.computeAnimatedColor(glint, layerIdx));
                RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, isItem, 0);
                if (rt != null) cg_addDistinct(list, cg_glintBuf(buffer, rt, guiHud));
            }
        }
        if (list.isEmpty()) return null;
        // De-dupe the base too: a Sodium immediate source can hand back a builder we already hold (the
        // "Duplicate delegates" crash the multi-layer Glint Table preview hit). The world path returns
        // distinct fixed buffers, so this is a no-op there.
        cg_addDistinct(list, buffer.getBuffer(renderType));
        return list.size() == 1 ? list.get(0)
                : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
    }

    /** Packs an ARGB int into the shader-colour buffer as premultiplied RGB (alpha folded in) + alpha 1:
     *  the form forGlint's colour holder expects. */
    private static void cg_packColor(float[] buf, int color) {
        float a = ((color >> 24) & 0xFF) / 255.0f;
        buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
        buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
        buf[2] = ( color        & 0xFF) / 255.0f * a;
        buf[3] = 1.0f;
    }

    /** Appends {@code vc} unless an identity-equal delegate is already present. VertexMultiConsumer rejects
     *  duplicate delegates, and a Sodium immediate source can return one builder for several RenderTypes. */
    private static void cg_addDistinct(List<VertexConsumer> list, VertexConsumer vc) {
        for (int i = 0; i < list.size(); i++) if (list.get(i) == vc) return;
        list.add(vc);
    }

    // ── Glow-outline capture ─────────────────────────────────────────────────
    // For glowing items, snapshot the item's silhouette (its baked quads + camera-relative pose + light +
    // animated glow colour) so GlowOutlineRenderer can replay it into the mask and ring it. World items
    // (third-person held, dropped, frames, other players) queue for the AFTER_WEATHER drain; the
    // first-person held item queues for the hand-pass drain (drainHeldFp). GUI / inventory / HUD icons are
    // captured in GUI screen space and drained immediately (drainGui) while the GUI ortho matrices are live.
    private static void cg_captureGlowOutline(ItemStack stack, ItemDisplayContext ctx, boolean leftHand,
            PoseStack pose, int light, BakedModel model) {
        // Guard against re-entry from our own special-item re-render (renderStatic below runs render()
        // again, whose RETURN re-enters this method).
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        boolean gui = ctx == ItemDisplayContext.GUI;
        // FP-replacing mods (Punchy, First-Person Model) draw the held item with a THIRD_PERSON display
        // context during the first-person hand pass; isFpHand() routes it to the FP held queue (drained
        // under the hand-FOV projection) instead of the world queue.
        boolean firstPerson = !gui && (ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || GlowOutlineRenderer.isFpHand());
        if (model == null) return;
        boolean glow = CustomGlint.hasGlowEffect(stack);
        // Under a shader pack a chromatic item can't draw in-phase (hijacked); capture its quads here for the
        // post-Iris overlay drain (world items only for now; GUI / first-person / special items deferred).
        CustomGlint.Data cgGlint = CustomGlintRenderer.isShaderPackActive() ? CustomGlint.readForRender(stack) : null;
        boolean chromaPack = cgGlint != null && cg_hasChromatic(cgGlint);
        if (!glow && !chromaPack) return;

        int color = glow ? CustomGlintRenderer.resolveGlowColor(stack) : 0;
        // GUI-only icon anchor (slot centre + on-screen size + texture resolution) for the drain's ring
        // sizing + slot clamp; null for world / first-person (they scissor by silhouette bounds).
        float[] guiAnchor = gui ? cg_guiAnchor(pose, model) : null;

        // Special / 3D BEWLR items (trident, shield, any isCustomRenderer item) have no baked quads.
        // Re-render the whole item through renderStatic into a record-only buffer (IN_OUTLINE guards
        // recursion + suppresses the glint fan-out), capturing its animated, already-transformed
        // geometry: the proven approach from the pre-purge doItemOutline/doBewlrOutline path.
        if (model.isCustomRenderer()) {
            // Special / 3D BEWLR items (trident, shield): one re-render capture feeds both the glow ring and,
            // under a pack, the chromatic overlay (per captured texture bucket). GUI special-item chromatic is
            // deferred inside queueChromaticGroups.
            cg_captureSpecialOutline(stack, ctx, leftHand, pose, light, color, guiAnchor, glow,
                    chromaPack ? cgGlint : null);
            return;
        }

        // render() pushed the pose, applied the item's display transform (handleCameraTransforms ->
        // applyTransform) + the (-0.5,-0.5,-0.5) centering, drew the quads, then popped, so pose.last()
        // at this RETURN is the OUTER pose, missing both. Reproduce that exact sequence on a copy so the
        // silhouette matches the item's real on-screen scale and position.
        PoseStack tp = new PoseStack();
        tp.last().pose().set(pose.last().pose());
        tp.last().normal().set(pose.last().normal());
        BakedModel rendered = model.applyTransform(ctx, tp, leftHand);
        tp.translate(-0.5F, -0.5F, -0.5F);
        if (rendered == null || rendered.isCustomRenderer()) return;

        // Trace the item's full real shape (every face, including the 1/16 extrusion rim) so the
        // outline wraps the visible 3D item instead of a single offset sprite plane. The isolated Sodium
        // flicker specks that used to leak past the edge are removed in the composite's morphological-
        // opening guard, not by dropping geometry here.
        List<BakedQuad> quads = cg_collectQuads(rendered, null);
        if (quads.isEmpty()) return;

        if (glow) {
            if (gui) {
                // Capture in GUI screen space; the ring is drained at GuiGraphics.flush() RETURN (GuiGraphicsMixin)
                // so it composites AFTER the icon + its slot background are flushed to the main target, while
                // RenderSystem still holds the GUI ortho projection / modelview the icon was drawn under. The
                // OUTER pose translation is the icon's slot centre (GuiGraphics translated to it before the
                // display transform), and its x-axis scale is the icon's on-screen size (16 GUI px in a slot,
                // 5x that in the wand preview). Pass both so the drain sizes + clamps the ring to the real icon.
                GlowOutlineRenderer.queueGuiItem(quads, tp.last().copy(), light, color, guiAnchor);
            } else if (firstPerson) {
                GlowOutlineRenderer.queueHeldFpItem(quads, tp.last().copy(), light, color);
            } else {
                GlowOutlineRenderer.queueWorldItem(quads, tp.last().copy(), light, color);
            }
        }

        // Chromatic overlay capture: expand the baked quads to camera-relative [x,y,z,u,v] under the item's
        // display transform and queue per chromatic layer. World items drain at renderLevel TAIL; first-person
        // hand items at the hand pass; GUI icons at GuiGraphics.flush, each under the matrices they drew with.
        if (chromaPack) {
            EntityGlintRender.CapturingModelConsumer cap = new EntityGlintRender.CapturingModelConsumer();
            for (BakedQuad q : quads) {
                cap.putBulkData(tp.last(), q, 1.0f, 1.0f, 1.0f, 1.0f, light, OverlayTexture.NO_OVERLAY);
            }
            CustomGlint.Layer[] ls = cgGlint.layers();
            for (int li = 0; li < ls.length; li++) {
                if (!CustomGlint.isChromatic(ls[li])) continue;
                if (gui) GlowOutlineRenderer.queueChromaticItemGui(cap.data, cap.count, cgGlint, li);
                else if (firstPerson) GlowOutlineRenderer.queueChromaticItemFp(cap.data, cap.count, cgGlint, li);
                else GlowOutlineRenderer.queueChromaticItem(cap.data, cap.count, cgGlint, li);
            }
        }
    }

    /** True when any layer of {@code glint} is the procedural chromatic design. */
    private static boolean cg_hasChromatic(CustomGlint.Data glint) {
        for (CustomGlint.Layer l : glint.layers()) if (CustomGlint.isChromatic(l)) return true;
        return false;
    }

    /** Capture a special / 3D BEWLR item's silhouette by re-rendering it through renderStatic into a
     *  record-only buffer. {@code pose} is the OUTER pose at render() RETURN; renderStatic re-applies the
     *  display transform, so the recorded positions are camera-relative and already include the item's
     *  animation (it lives in the pose the model draws under). IN_OUTLINE prevents recursion and makes
     *  applyGlint route to the bare (recording) buffer instead of fanning out glint layers.
     *
     *  <p>The capture buckets vertices by the texture each RenderType draws through, so the silhouette traces
     *  the item's REAL shape via that texture's alpha (a trident traces the trident, not its square model
     *  hull; an EK/IaF custom item traces its sprite, not the no-texture model parts). Textureless RTs fall
     *  back to a white fill. queueGroups routes each bucket to the world / FP / GUI drain by {@code ctx}. */
    private static void cg_captureSpecialOutline(ItemStack stack, ItemDisplayContext ctx, boolean leftHand,
            PoseStack pose, int light, int color, float[] guiAnchor, boolean glow, CustomGlint.Data chromaGlint) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        PoseStack tp = new PoseStack();
        tp.last().pose().set(pose.last().pose());
        tp.last().normal().set(pose.last().normal());
        GlowOutlineRenderer.CapturingBufferSource cap = new GlowOutlineRenderer.CapturingBufferSource(null);
        CustomGlintRenderer.IN_OUTLINE.set(true);
        try {
            mc.getItemRenderer().renderStatic(mc.player, stack, ctx, leftHand, tp, cap, mc.level,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        } finally {
            CustomGlintRenderer.IN_OUTLINE.set(false);
        }
        if (glow) cap.queueGroups(color, ctx, guiAnchor);
        if (chromaGlint != null) cap.queueChromaticGroups(chromaGlint, ctx);
    }

    /** Icon anchor for the GUI glow drain: {@code [x,y,z]} = the OUTER pose translation (the slot/preview
     *  centre, before the model's display transform), {@code [3]} = the icon's nominal half-size in GUI px
     *  (½ the pose x-axis scale; 8 for a 16-px slot, 40 for the 5x wand preview), {@code [4]} = the item
     *  texture's resolution in px (16 / 32 / 64…), read from the model's particle sprite. The drain sizes the
     *  ring per TEXTURE pixel, so a 32x32 or 64x64 item doesn't get a ring twice/four times too thick. */
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

    /** Collects a model's item quads. If {@code onlyDir} is non-null, keeps only quads whose face is that
     *  direction (used to take a single flat sprite face); null keeps every quad. */
    private static List<BakedQuad> cg_collectQuads(BakedModel model, Direction onlyDir) {
        List<BakedQuad> out = new ArrayList<>();
        RandomSource random = RandomSource.create();
        for (Direction dir : Direction.values()) {
            // 42L is vanilla ItemRenderer's fixed getQuads seed. Reusing it means a model that varies its
            // quads by random (connected textures, weighted variants) hands back the same set it just drew.
            random.setSeed(42L);
            for (BakedQuad q : model.getQuads(null, dir, random)) {
                if (onlyDir == null || q.getDirection() == onlyDir) out.add(q);
            }
        }
        random.setSeed(42L);
        for (BakedQuad q : model.getQuads(null, null, random)) {
            if (onlyDir == null || q.getDirection() == onlyDir) out.add(q);
        }
        return out;
    }

}
