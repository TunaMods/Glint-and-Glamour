package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.resources.ResourceLocation;
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

/**
 * Two hooks on {@code ElytraLayer.render}:
 *  1. {@link Redirect} on the vanilla elytra {@code renderToBuffer}: the IN-PHASE glow TEE. The elytra
 *     wings are drawn once by vanilla (inside its {@code translate(0,0,0.125)} push/pop); we route that
 *     single walk through a recording consumer to capture the glow silhouette in place, instead of a second
 *     re-render. Keyed by the elytra STACK so it keeps its own ring. No-op capture when not glowing.
 *  2. {@link Inject} at RETURN, draws the custom GLINT (vanilla doesn't), re-applying the 0.125 offset.
 * Both honor vanilla's {@code shouldRender} so a Mekanism-style subclass layer doesn't double-draw.
 */
@Mixin(ElytraLayer.class)
public class ElytraLayerMixin {

    @Shadow private ElytraModel<?> elytraModel;

    // ── in-phase glow tee on the vanilla elytra draw ──────────────────────────

    @Redirect(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/model/ElytraModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"),
        require = 0, remap = false
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void cg_teeElytraOutline_named(ElytraModel model, PoseStack pose, VertexConsumer vc, int light,
            int overlay, PoseStack pose2, MultiBufferSource buffer, int packedLight, LivingEntity entity,
            float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
            float headPitch) {
        EntityGlintRender.OutlineSpec spec = null;
        ItemStack stack = entity.getItemBySlot(EquipmentSlot.CHEST);
        if (!entity.isInvisible() && !stack.isEmpty() && CustomGlint.hasGlowEffect(stack)) {
            ResourceLocation tex = ((ElytraLayer) (Object) this).getElytraTexture(stack, entity);
            if (tex != null) {
                // Priority 1 (worn armor and the body use 0): the composite sorts low priority first, so the
                // elytra's ring draws last and stays on top of the body/armor it covers at the seam.
                spec = new EntityGlintRender.OutlineSpec(stack, tex, CustomGlintRenderer.resolveGlowColor(stack),
                        GlowOutlineRenderer.CAT_ARMOR, 1);
            }
        }
        EntityGlintRender.teeOutline4(model, pose, vc, light, overlay, spec);
    }

    // ── custom glint draw at RETURN ───────────────────────────────────────────

    @Inject(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_elytraGlint_named(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, float limbSwing, float limbSwingAmount,
            float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        applyElytraGlint((ElytraLayer<LivingEntity, ?>)(Object)this, poseStack, buffer, packedLight, entity, this.elytraModel);
    }

    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyElytraGlint(ElytraLayer<LivingEntity, ?> self, PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, ElytraModel<?> elytraModel) {
        ItemStack stack = entity.getItemBySlot(EquipmentSlot.CHEST);
        if (stack.isEmpty()) return;
        // Honor the layer's own shouldRender so subclass layers (e.g. Mekanism's MekanismElytraLayer,
        // which only renders HDPE elytra) don't double-draw glint on a vanilla elytra.
        if (!((ElytraLayer)self).shouldRender(stack, entity)) return;
        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        if (glint == null) return; // glow outline is captured by the in-phase tee redirect above

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();

        List<VertexConsumer> list = new ArrayList<>();
        List<Integer> chromaPackLayers = new ArrayList<>();
        boolean pack = CustomGlintRenderer.isShaderPackActive();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            if (CustomGlint.isChromatic(layers[layerIdx])) {
                // Under a shader pack chromatic can't draw in-phase; capture the elytra for the post-Iris
                // overlay drain instead (see EntityGlintRender.captureChromaticModel).
                if (pack) {
                    chromaPackLayers.add(layerIdx);
                } else {
                    RenderType crt = CustomGlintRenderer.forChromaticArmorGlint(glint, layerIdx);
                    if (crt != null) list.add(buffer.getBuffer(crt));
                }
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
                    RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, i);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                float a = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( color        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, 0);
                if (rt != null) list.add(buffer.getBuffer(rt));
            }
        }
        if (list.isEmpty() && chromaPackLayers.isEmpty()) return;
        // Vanilla's render pops the pose before returning, so re-apply the elytra's (0, 0, 0.125) offset for
        // both the in-phase glint draw and the chromatic capture (so the captured verts match the drawn wings).
        poseStack.pushPose();
        poseStack.translate(0.0f, 0.0f, 0.125f);
        if (!list.isEmpty()) {
            VertexConsumer combined = list.size() == 1 ? list.get(0)
                    : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            elytraModel.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
        if (!chromaPackLayers.isEmpty()) {
            ResourceLocation ctex = ((ElytraLayer) (Object) self).getElytraTexture(stack, entity);
            if (ctex != null) {
                for (int li : chromaPackLayers) {
                    EntityGlintRender.captureChromaticModel(entity, elytraModel, ctex, poseStack, packedLight, glint, li);
                }
            }
        }
        poseStack.popPose();
    }

}
