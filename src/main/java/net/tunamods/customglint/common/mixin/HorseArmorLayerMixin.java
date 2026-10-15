package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.HorseModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.HorseArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.AnimalArmorItem;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Intercepts HorseArmorLayer.render: a {@link Redirect} tees the vanilla base horse-armor draw to capture
 *  the glow silhouette in-phase (no re-render), and the RETURN {@link Inject} draws the custom glint. */
@Mixin(HorseArmorLayer.class)
public class HorseArmorLayerMixin {

    @Shadow private HorseModel<Horse> model;

    // Is Sodium's entity render path on the classpath this run? Gates the per-buffer glint draw below.
    private static final boolean SODIUM_PRESENT = cg_classPresent("net.caffeinemc.mods.sodium.client.render.immediate.model.EntityRenderer");

    private static boolean cg_classPresent(String fqn) {
        try { Class.forName(fqn, false, HorseArmorLayerMixin.class.getClassLoader()); return true; }
        catch (Throwable t) { return false; }
    }

    // ── in-phase glow tee on the vanilla base horse-armor draw ────────────────────

    @Redirect(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/animal/horse/Horse;FFFFFF)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/model/HorseModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
        require = 0, remap = false
    )
    private void cg_teeHorseArmorOutline_named(HorseModel drawModel, PoseStack pose, VertexConsumer vc,
            int light, int overlay, int color, PoseStack pose2, MultiBufferSource buffer, int packedLight,
            Horse entity, float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
            float netHeadYaw, float headPitch) {
        EntityGlintRender.OutlineSpec spec = null;
        ItemStack stack = entity.getBodyArmorItem();
        if (!entity.isInvisible() && !stack.isEmpty() && CustomGlint.hasGlowEffect(stack)
                && stack.getItem() instanceof AnimalArmorItem aa) {
            spec = new EntityGlintRender.OutlineSpec(entity, aa.getTexture(),
                    CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR, 0);
        }
        // Sodium coplanar armor-vs-body z-fight (shaders OFF, glowing horses only): draw the real base armor
        // straight through vc so it rides the SAME vertex pipeline as the horse body. Under Sodium the body goes
        // through EntityRenderer.renderCuboid; teeOutline5 would instead route this draw through a
        // CapturingModelConsumer (for the silhouette), which Sodium cannot fast-path (it logs "does not support
        // optimized vertex writing"), so the armor would fall to the vanilla per-vertex path. HorseModel bakes
        // the armor coplanar with the body on the torso and neck (both CubeDeformation 0.05F), so armor-on-vanilla
        // vs body-on-renderCuboid is a per-pixel depth coin-flip: the flicker. It reproduced only with Sodium
        // (body needs renderCuboid) and only when glowing (only then was the capture consumer in the path).
        // The glow silhouette is captured in a SEPARATE record-only walk that never writes the on-screen armor's
        // depth, so glow still works and the armor stays on Sodium's path. Worn humanoid armor sits proud
        // (VIEW_OFFSET) and is not coplanar, so it keeps teeOutline5's cheaper single-walk tee.
        drawModel.renderToBuffer(pose, vc, light, overlay, color);
        if (spec != null) {
            EntityGlintRender.captureModelSilhouette(entity, spec.identity, drawModel, spec.tex, pose, light,
                    spec.color, spec.category, spec.priority);
        }
    }

    @Inject(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/animal/horse/Horse;FFFFFF)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_horseArmorGlint_named(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Horse entity, float limbSwing, float limbSwingAmount,
            float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        applyHorseArmorGlint(poseStack, buffer, packedLight, entity, this.model);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static void applyHorseArmorGlint(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Horse entity, HorseModel<Horse> model) {
        ItemStack stack = entity.getBodyArmorItem();
        if (stack.isEmpty()) return;
        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        if (glint == null) return; // glow outline is captured by the in-phase tee redirect above
        if (!(stack.getItem() instanceof AnimalArmorItem aa)
                || aa.getBodyType() != AnimalArmorItem.BodyType.EQUESTRIAN) return;
        ResourceLocation tex = aa.getTexture();

        // Draw the glint into its fixed buffers and let it flush at the entity's global endBatch, the same
        // way worn humanoid armor (forArmorGlint) and entity-body glint do. Vanilla BufferSource.endBatch()
        // runs endLastBatch() FIRST (which draws vanilla's base horse armor, a non-fixed entityCutoutNoCull
        // builder) and only THEN walks the fixedBuffers to flush our glint, so the base armor's depth is
        // already written when the glint's LEQUAL pass runs. No explicit endBatch here, and no early
        // base-armor flush to compensate for one.
        //
        // Earlier ports flushed the glint explicitly mid-layer (bs2.endBatch) and so had to flush the base
        // armor early too, or the glint tested against absent depth. That whole early-flush dance was a relic
        // of the old stencil sequencing (mask-then-gate needed a strict order); the cutout is now per-fragment
        // in the glint_cutout shader, so it is gone. Early-flushing mid-pass also meant the glint depth-tested
        // against a half-built depth buffer, which is the one thing this path did that the working
        // humanoid/entity glint never did.
        //
        // Cutout: forMountArmorGlint binds the armor texture to Sampler1 and the glint_cutout shader
        // alpha-tests it per fragment. REQUIRED even for vanilla horse armor: the model is the whole horse
        // but the armor texture is alpha-cutout, so an uncut glint would paint bare hide (the lower neck).
        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();

        List<VertexConsumer> list = new ArrayList<>();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            if (CustomGlint.isChromatic(layers[layerIdx])) {
                // Procedural slick: no PNG, so it can't go through forMountArmorGlint. Under a shader pack the
                // in-phase chromatic program is hijacked, so capture the horse model and defer to the post-Iris
                // overlay drain (same as worn armor); otherwise draw the in-phase chromatic cutout.
                if (CustomGlintRenderer.isShaderPackActive()) {
                    EntityGlintRender.captureChromaticModel(entity, model, tex, poseStack, packedLight, glint, layerIdx);
                } else {
                    RenderType crt = CustomGlintRenderer.forMountChromaticGlint(glint, layerIdx, tex);
                    if (crt != null) list.add(buffer.getBuffer(crt));
                }
                continue;
            }
            // Non-chromatic layer. Under a shader pack the in-phase glint_cutout program is hijacked (its
            // Sampler1 armor-cutout binding is dropped, so every fragment discards and the glint vanishes), so
            // capture the horse model and defer to the post-Iris textured-glint overlay drain, exactly as the
            // chromatic branch above does. Off-pack it draws in-phase through forMountArmorGlint.
            if (CustomGlintRenderer.isShaderPackActive()) {
                EntityGlintRender.captureGlintModel(entity, model, tex, poseStack, packedLight, glint, layerIdx);
                continue;
            }
            int[] colors = layers[layerIdx].colors().length == 0 ? CustomGlintRenderer.WHITE_COLOR : layers[layerIdx].colors();
            if (layers[layerIdx].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = CustomGlintRenderer.forMountArmorGlint(glint, layerIdx, buf, i, tex);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                float a = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( color        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                RenderType rt = CustomGlintRenderer.forMountArmorGlint(glint, layerIdx, buf, 0, tex);
                if (rt != null) list.add(buffer.getBuffer(rt));
            }
        }
        if (!list.isEmpty()) {
            // TRIED (2026-07-18, Sodium coplanar z-fight, shaders OFF): under Sodium, draw each layer buffer with
            // its OWN model walk instead of one VertexMultiConsumer. Sodium only routes a cube through its
            // EntityRenderer.renderCuboid path when the target consumer is a fast-path VertexBufferWriter; a plain
            // fixed-buffer BufferBuilder qualifies, but VertexMultiConsumer$Multiple does NOT (Sodium logs "does
            // not support optimized vertex writing"). A combined draw therefore fell to the vanilla per-vertex
            // path while the base horse armor (plain BufferBuilder) went through renderCuboid, so the two coplanar
            // meshes came off DIFFERENT vertex pipelines and z-fought regardless of the NEW_ENTITY format match
            // (the wrapper hides the per-layer buffers, so the format never gets consulted). Per-buffer draws put
            // every glint layer back on the same renderCuboid path as the armor, exactly as when Sodium is absent
            // and both sides share the vanilla path. This is a per-buffer model walk (N walks for N layer buffers)
            // and is gated on Sodium so the vanilla path keeps the single combined-consumer draw.
            if (SODIUM_PRESENT) {
                for (int i = 0; i < list.size(); i++) {
                    model.renderToBuffer(poseStack, list.get(i), packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
                }
            } else {
                VertexConsumer combined = list.size() == 1 ? list.get(0)
                        : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
                model.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            }
        }
        // Glow outline is captured by the in-phase tee redirect above (no re-render here).
    }

}
