package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Two hooks on {@code LivingEntityRenderer.render}:
 *
 *  1. {@link ModifyVariable} at HEAD on the MultiBufferSource arg, wraps it with a
 *     GlintWrappingBufferSource so every {@code entity_*} RenderType the renderer or any RenderLayer
 *     (StrayClothingLayer, EyesLayer, VillagerProfessionLayer, …) requests gets a glint pass. No-op when the
 *     entity has no glint data.
 *  2. {@link Redirect} on the body {@code model.renderToBuffer} call: the IN-PHASE TEE. Instead of
 *     re-rendering the body model a second time to capture its glow silhouette (a full extra model walk per
 *     glowing entity every frame), it records the silhouette DURING the single real body draw. No-op
 *     capture-wise when the entity doesn't glow (it just forwards the draw).
 *
 * Dual SRG/named, require=0 on every hook: same pattern as the armor-layer mixins.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {

    // HEAD: wrap buffer source so every entity_* RT picks up the glint fan-out

    @ModifyVariable(method = "m_7392_", at = @At("HEAD"), argsOnly = true, require = 0)
    private MultiBufferSource cg_wrapBuf_srg(MultiBufferSource original,
                                             LivingEntity entity, float yaw, float partialTicks,
                                             PoseStack pose, MultiBufferSource buffer, int light) {
        return EntityGlintRender.wrapForEntity(entity, original);
    }

    @ModifyVariable(
        method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"), argsOnly = true, require = 0, remap = false
    )
    private MultiBufferSource cg_wrapBuf_named(MultiBufferSource original,
                                               LivingEntity entity, float yaw, float partialTicks,
                                               PoseStack pose, MultiBufferSource buffer, int light) {
        return EntityGlintRender.wrapForEntity(entity, original);
    }

    // Body draw: tee the single walk into the glow silhouette (no second model render)

    // remap=false on BOTH variants so the SRG-named one stays literal (matches the SRG runtime in
    // production) and the named one stays literal (matches the deobf dev runtime). Exactly one resolves per
    // environment: two @Redirects on the SAME instruction would conflict if both resolved (unlike @Inject,
    // @Redirect is not stackable). Mirrors the dual-@Redirect pattern in LayerDragonArmorMixin.
    @Redirect(
        method = "m_7392_",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/model/EntityModel;m_7695_(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"),
        require = 0, remap = false
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void cg_teeBodyOutline_srg(EntityModel model, PoseStack pose, VertexConsumer consumer,
                                       int light, int overlay, float red, float green, float blue, float alpha,
                                       LivingEntity entity, float yaw, float partialTicks,
                                       PoseStack pose2, MultiBufferSource buffer, int light2) {
        EntityGlintRender.renderBodyTee((LivingEntityRenderer) (Object) this, entity, model, pose, consumer,
                light, overlay, red, green, blue, alpha);
    }

    @Redirect(
        method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"),
        require = 0, remap = false
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void cg_teeBodyOutline_named(EntityModel model, PoseStack pose, VertexConsumer consumer,
                                         int light, int overlay, float red, float green, float blue, float alpha,
                                         LivingEntity entity, float yaw, float partialTicks,
                                         PoseStack pose2, MultiBufferSource buffer, int light2) {
        EntityGlintRender.renderBodyTee((LivingEntityRenderer) (Object) this, entity, model, pose, consumer,
                light, overlay, red, green, blue, alpha);
    }
}
