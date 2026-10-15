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
import net.minecraft.world.item.HorseArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.model.Model;
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

/** Intercepts HorseArmorLayer.render at RETURN to draw custom glint and (if glowing) stencil outline on horse armor. Dual SRG/named targets, require=0 on both. */
@Mixin(HorseArmorLayer.class)
public class HorseArmorLayerMixin {

    @Shadow(aliases = {"f_117017_"}) private HorseModel<Horse> model;

    /** SRG target: injects at RETURN of render in obfuscated environments. */
    @Inject(method = "m_6494_", at = @At("RETURN"), require = 0)
    private void cg_horseArmorGlint_srg(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Horse entity, float limbSwing, float limbSwingAmount,
            float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        applyHorseArmorGlint(poseStack, buffer, packedLight, entity, this.model);
    }

    /** Named target: injects at RETURN of render in dev/deobf environments. */
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

    private static void applyHorseArmorGlint(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Horse entity, HorseModel<Horse> model) {
        ItemStack stack = entity.getArmor();
        if (stack.isEmpty()) return;
        CustomGlint.Data glint = CustomGlint.readCached(stack);
        boolean glowing = CustomGlint.isGlowing(stack);
        if (glint == null && !glowing) return;
        if (!(stack.getItem() instanceof HorseArmorItem ha)) return;
        ResourceLocation tex = ha.getTexture();
        if (tex == null) return; // a modded HorseArmorItem could return null; skip rather than NPE the stencil/glow path

        if (glint != null) {
            // Stencil mask pass
            // HorseArmorLayer draws the whole horse model with the barding texture, so the armor mesh
            // is also the body, legs and saddle mesh; only the barding texture's alpha says which faces
            // are armor. A glint RT samples its design, not that texture, so unmasked it would cover the
            // entire horse. Stencil-mask bit 0x80 through the barding texture's alpha cutoff, then
            // constrain the glint test to that bit. Same pattern as IaF mount armor.
            RenderType maskType = CustomGlintRenderer.forMountArmorStencilMask(tex);
            model.renderToBuffer(poseStack, buffer.getBuffer(maskType), packedLight, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
            if (buffer instanceof MultiBufferSource.BufferSource bs0) bs0.endBatch(maskType);

            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();

            List<VertexConsumer> list = new ArrayList<>();
            List<RenderType> glintTypes = new ArrayList<>();
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                if (CustomGlint.isChromatic(layers[layerIdx])) {
                    // chromaticWorldBuffer is a straight getBuffer; kept as the seam every chromatic fan-out
                    // routes through (see CustomGlintRenderer.chromaticWorldBuffer).
                    RenderType crt = CustomGlintRenderer.forChromaticMountArmorGlint(glint, layerIdx, tex);
                    if (crt != null) { list.add(CustomGlintRenderer.chromaticWorldBuffer(buffer, crt)); glintTypes.add(crt); }
                    continue;
                }
                int[] colors = layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        CustomGlintRenderer.fillPremul(buf, colors[i]);
                        RenderType rt = CustomGlintRenderer.forMountArmorGlint(glint, layerIdx, buf, i);
                        if (rt != null) { list.add(buffer.getBuffer(rt)); glintTypes.add(rt); }
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    CustomGlintRenderer.fillPremul(buf, color);
                    RenderType rt = CustomGlintRenderer.forMountArmorGlint(glint, layerIdx, buf, 0);
                    if (rt != null) { list.add(buffer.getBuffer(rt)); glintTypes.add(rt); }
                }
            }
            if (!list.isEmpty()) {
                VertexConsumer combined = list.size() == 1 ? list.get(0)
                        : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
                model.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
            }
            if (buffer instanceof MultiBufferSource.BufferSource bs2) {
                for (RenderType rt : glintTypes) bs2.endBatch(rt);
            }
        }

        // Glow outline: re-render the barding model into the glow mask, traced against the armor texture.
        // Shares the horse's outline id so barding + body compose as ONE ring. CAT_ARMOR ring thickness.
        if (glowing) {
            EntityGlintRender.captureModelSilhouette(entity, (Model) model, tex, poseStack, packedLight,
                    CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR);
        }
    }

}
