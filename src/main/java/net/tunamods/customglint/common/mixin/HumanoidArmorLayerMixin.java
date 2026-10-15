package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Intercepts renderArmorPiece at RETURN to draw custom glint and (if glowing) stencil outline on vanilla + modded armor. Dual SRG/named targets, require=0 on both. */
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {

    /** SRG target: injects at RETURN of renderArmorPiece in obfuscated environments. */
    @Inject(method = "m_117118_", at = @At("RETURN"), require = 0)
    private void cg_armorGlint_srg(PoseStack pPoseStack, MultiBufferSource pBuffer,
            LivingEntity pLivingEntity, EquipmentSlot pSlot, int pPackedLight,
            HumanoidModel pModel, CallbackInfo ci) {
        applyArmorGlint((HumanoidArmorLayer<?, ?, ?>) (Object) this,
                pPoseStack, pBuffer, pLivingEntity, pSlot, pPackedLight, pModel);
    }

    /** Named target: injects at RETURN of renderArmorPiece in dev/deobf environments. */
    @Inject(
        method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_armorGlint_named(PoseStack pPoseStack, MultiBufferSource pBuffer,
            LivingEntity pLivingEntity, EquipmentSlot pSlot, int pPackedLight,
            HumanoidModel pModel, CallbackInfo ci) {
        applyArmorGlint((HumanoidArmorLayer<?, ?, ?>) (Object) this,
                pPoseStack, pBuffer, pLivingEntity, pSlot, pPackedLight, pModel);
    }

    private static void applyArmorGlint(HumanoidArmorLayer<?, ?, ?> layer,
            PoseStack poseStack, MultiBufferSource buffer,
            LivingEntity entity, EquipmentSlot slot, int packedLight, HumanoidModel model) {
        ItemStack stack = entity.getItemBySlot(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem)) return;
        CustomGlint.Data glint = CustomGlint.readCached(stack);
        boolean glowing = CustomGlint.isGlowing(stack);
        if (glint == null && !glowing) return;

        Model rendererModel = ForgeHooksClient.getArmorModel(entity, stack, slot, model);
        // A modded IClientItemExtensions.getArmorModel override may return null; fall back to the vanilla
        // model rather than NPE on the render thread at renderToBuffer / captureModelSilhouette below.
        if (rendererModel == null) rendererModel = model;
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
                VertexConsumer combined = list.size() == 1 ? list.get(0) : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
                rendererModel.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
            }
        }

        // Glow outline: re-render the resolved armor model into the glow mask, traced against the real armor
        // texture (Forge's getArmorResource covers modded armor). Shares the wearer's outline id (identity =
        // entity), so body + every armor piece compose as ONE ring. CAT_ARMOR picks the armor ring thickness.
        if (glowing) {
            ResourceLocation tex = null;
            try {
                tex = ((HumanoidArmorLayer<?, ?, ?>) layer).getArmorResource(entity, stack, slot, null);
            } catch (Throwable ignored) {
                // Non-standard layer / renamed Forge hook → skip the glow outline rather than crash.
            }
            if (tex != null) {
                EntityGlintRender.captureModelSilhouette(entity, rendererModel, tex, poseStack, packedLight,
                        CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR);
            }
        }
    }

}
