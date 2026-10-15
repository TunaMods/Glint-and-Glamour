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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Intercepts HumanoidArmorLayer: a HEAD context + a {@code renderModel} {@link Redirect} tee the vanilla
 *  base-armor draw to capture the glow silhouette in-phase (no re-render, eliminating up to 4 extra model
 *  walks per armored entity per frame), and the RETURN {@link Inject} draws the custom glint, on vanilla +
 *  modded armor. */
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {

    // Per-piece glow capture context, set at renderArmorPiece HEAD and consumed by the renderModel tee
    // (which fires once per material layer, we tee only the first = the base shape layer 0). Render-thread
    // only; renderArmorPiece calls renderModel synchronously so the threadlocal handoff is safe.
    private static final ThreadLocal<EntityGlintRender.OutlineSpec> CG_ARMOR_SPEC = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CG_ARMOR_RECORDED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** HEAD of renderArmorPiece (12-arg overload, the one render() invokes per slot): resolve the glow
     *  capture spec for this piece (or null when it doesn't glow) so the renderModel tee below knows whether
     *  to record. */
    @Inject(
        method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_armorOutlineCtx_named(PoseStack pPoseStack, MultiBufferSource pBuffer,
            LivingEntity entity, EquipmentSlot slot, int pPackedLight, HumanoidModel pModel,
            float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
            float netHeadYaw, float headPitch, CallbackInfo ci) {
        CG_ARMOR_RECORDED.set(Boolean.FALSE);
        CG_ARMOR_SPEC.set(null);
        if (entity.isInvisible()) return;
        ItemStack stack = entity.getItemBySlot(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem) || !CustomGlint.hasGlowEffect(stack)) return;
        ResourceLocation tex = cg_armorTexture(entity, stack, slot);
        if (tex != null) {
            CG_ARMOR_SPEC.set(new EntityGlintRender.OutlineSpec(entity, tex,
                    CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR, 0));
        }
    }

    /** Tee the base-armor draw inside renderModel (the Model-typed overload that actually draws). Forwards
     *  every layer; records only the FIRST drawn layer (= material layer 0, the base shape whose texture the
     *  spec uses). Captures the glow silhouette in the same walk vanilla draws the armor with. */
    @Redirect(
        method = "renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/Model;ILnet/minecraft/resources/ResourceLocation;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
        require = 0, remap = false
    )
    private void cg_teeArmorBaseOutline_named(Model model, PoseStack pose, VertexConsumer vc,
            int light, int overlay, int color) {
        EntityGlintRender.OutlineSpec spec = CG_ARMOR_SPEC.get();
        if (spec == null || CG_ARMOR_RECORDED.get()) {
            model.renderToBuffer(pose, vc, light, overlay, color);
            return;
        }
        CG_ARMOR_RECORDED.set(Boolean.TRUE);
        EntityGlintRender.teeOutline5(model, pose, vc, light, overlay, color, spec);
    }

    /**
     * RETURN of renderArmorPiece. Targets the 12-arg overload, the one
     * HumanoidArmorLayer.render() invokes per slot. The 6-arg overload is a @Deprecated
     * Neo back-compat shim that vanilla never calls; injecting there silently no-ops, which is
     * why armor showed no glint or glow while items rendered fine.
     */
    @Inject(
        method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_armorGlint_named(PoseStack pPoseStack, MultiBufferSource pBuffer,
            LivingEntity pLivingEntity, EquipmentSlot pSlot, int pPackedLight,
            HumanoidModel pModel, float limbSwing, float limbSwingAmount, float partialTick,
            float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        applyArmorGlint((HumanoidArmorLayer<?, ?, ?>) (Object) this,
                pPoseStack, pBuffer, pLivingEntity, pSlot, pPackedLight, pModel);
        // Drop the per-piece capture context so it doesn't pin the entity reference between renders.
        CG_ARMOR_SPEC.remove();
        CG_ARMOR_RECORDED.remove();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static void applyArmorGlint(HumanoidArmorLayer<?, ?, ?> layer,
            PoseStack poseStack, MultiBufferSource buffer,
            LivingEntity entity, EquipmentSlot slot, int packedLight, HumanoidModel model) {
        ItemStack stack = entity.getItemBySlot(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem)) return;
        CustomGlint.Data glint = CustomGlint.readForRender(stack);
        if (glint == null) return; // glow outline is captured by the renderModel tee above

        Model rendererModel = IClientItemExtensions.of(stack).getGenericArmorModel(entity, stack, slot, model);
        // A buggy/non-standard IClientItemExtensions can return null here; fall back to the vanilla
        // model passed in rather than NPE out of the inject into HumanoidArmorLayer.render (hard crash).
        if (rendererModel == null) rendererModel = model;

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();

        List<VertexConsumer> list = new ArrayList<>();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            if (CustomGlint.isChromatic(layers[layerIdx])) {
                // Under a shader pack the in-phase chromatic program is hijacked (flat white / nothing), so
                // capture the armor model and defer the slick to the post-Iris overlay drain instead.
                if (CustomGlintRenderer.isShaderPackActive()) {
                    ResourceLocation ctex = cg_armorTexture(entity, stack, slot);
                    if (ctex != null) {
                        EntityGlintRender.captureChromaticModel(entity, rendererModel, ctex, poseStack, packedLight, glint, layerIdx);
                    }
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
        if (!list.isEmpty()) {
            VertexConsumer combined = list.size() == 1 ? list.get(0) : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            rendererModel.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }

        // Glow outline is captured by the renderModel tee above (no re-render here). All armor pieces on one
        // wearer share the wearer's outline id (glowKeyFor identity = entity), and the body outline shares it
        // too, so the figure composes as ONE ring.
    }

    /** The base armor-layer texture for this piece (layer 0 defines the full shape), resolved through the
     *  Forge hook so modded armor textures work. Its alpha drives the silhouette's alpha-discard so the
     *  ring follows the real armor, not the model's bounding boxes. Null when the piece isn't armor or its
     *  material declares no layers; every caller treats null as "no capture". */
    @Nullable
    private static ResourceLocation cg_armorTexture(LivingEntity entity, ItemStack stack, EquipmentSlot slot) {
        if (!(stack.getItem() instanceof ArmorItem armorItem)) return null;
        var layers = armorItem.getMaterial().value().layers();
        if (layers.isEmpty()) return null;
        boolean inner = slot == EquipmentSlot.LEGS; // matches HumanoidArmorLayer.usesInnerModel
        return ClientHooks.getArmorTexture(entity, stack, layers.get(0), inner, slot);
    }

}
