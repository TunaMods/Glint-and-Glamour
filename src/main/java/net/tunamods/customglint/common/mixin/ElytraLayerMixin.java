package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.Model;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Intercepts ElytraLayer.render at RETURN to draw custom glint and (if glowing) stencil outline on the elytra model.
 * Elytra uses armorCutoutNoCull (VIEW_OFFSET_Z_LAYERING), so forArmorGlint works correctly here.
 * Vanilla pops the pose before returning, so the inject re-applies the (0, 0, 0.125) elytra offset.
 */
@Mixin(ElytraLayer.class)
public class ElytraLayerMixin {

    @Shadow(aliases = {"f_116935_"}) private ElytraModel<?> elytraModel;

    /** Vanilla elytra texture for the glow-outline trace, hoisted so it isn't reallocated per elytra per frame. */
    private static final ResourceLocation CG_ELYTRA_TEX = new ResourceLocation("minecraft", "textures/entity/elytra.png");

    /** SRG target: injects at RETURN of render in obfuscated environments. */
    @Inject(method = "m_6494_", at = @At("RETURN"), require = 0)
    private void cg_elytraGlint_srg(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, float limbSwing, float limbSwingAmount,
            float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        applyElytraGlint((ElytraLayer<LivingEntity, ?>)(Object)this, poseStack, buffer, packedLight, entity, this.elytraModel);
    }

    /** Named target: injects at RETURN of render in dev/deobf environments. */
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyElytraGlint(ElytraLayer<LivingEntity, ?> self, PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, ElytraModel<?> elytraModel) {
        ItemStack stack = entity.getItemBySlot(EquipmentSlot.CHEST);
        if (stack.isEmpty()) return;
        // Honor the layer's own shouldRender so subclass layers (e.g. Mekanism's MekanismElytraLayer,
        // which only renders HDPE elytra) don't double-draw glint/outline on a vanilla elytra.
        if (!((ElytraLayer)self).shouldRender(stack, entity)) return;
        CustomGlint.Data glint = CustomGlint.readCached(stack);
        boolean glowing = CustomGlint.isGlowing(stack);
        if (glint == null && !glowing) return;

        VertexConsumer combined = null;
        if (glint != null) {
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();

            List<VertexConsumer> list = new ArrayList<>();
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                if (CustomGlint.isChromatic(layers[layerIdx])) {
                    RenderType crt = CustomGlintRenderer.forChromaticArmorGlint(glint, layerIdx);
                    if (crt != null) list.add(CustomGlintRenderer.chromaticWorldBuffer(buffer, crt));
                    continue;
                }
                int[] colors = layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        CustomGlintRenderer.fillPremul(buf, colors[i]);
                        RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, i);
                        if (rt != null) list.add(buffer.getBuffer(rt));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    CustomGlintRenderer.fillPremul(buf, color);
                    RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, 0);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            }
            if (!list.isEmpty()) {
                combined = list.size() == 1 ? list.get(0)
                        : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            }
        }
        if (combined == null && !glowing) return;
        // Vanilla's render pops the pose before returning, so re-apply the elytra's (0, 0, 0.125) offset.
        poseStack.pushPose();
        poseStack.translate(0.0f, 0.0f, 0.125f);
        try {
            if (combined != null) {
                elytraModel.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
            }
            // Glow outline: re-render the elytra model into the glow mask under this offset pose, traced against
            // the vanilla elytra texture. Keyed on the elytra ItemStack (NOT the wearer) so the elytra gets its
            // OWN ring distinct from the body/armor ring, rather than merging into the figure's one ring.
            if (glowing) {
                EntityGlintRender.captureModelSilhouette(stack, (Model) elytraModel, CG_ELYTRA_TEX, poseStack, packedLight,
                        CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR);
            }
        } finally {
            poseStack.popPose();
        }
    }

}
