package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the custom entity-body glint in the 26.1 deferred submit-node pipeline.
 *
 * <p>26.1 decoupled the entity render from the entity: {@code LivingEntityRenderer.submit(state, pose,
 * collector, camera)} submits the body as a single deferred {@code submitModel} node and only ever
 * sees a {@link LivingEntityRenderState} (no entity, no {@code MultiBufferSource}). So the glint rides
 * the render state, a {@code RegisterRenderStateModifiersEvent} modifier (installed in
 * {@code CustomGlintClientInit}) stashes the resolved glint under {@link EntityGlintRender#RENDER_DATA}
 * during extraction, and this mixin reads it back at draw time.
 *
 * <p>We inject just <b>before the outer {@code popPose()}</b>, after the body and all layers have been
 * submitted, while the pose is still in entity-local space (the same vantage the body model drew at).
 * There we submit one glint model node per layer/colour reusing the renderer's body {@code model}, so
 * the glint follows the entity silhouette exactly, the 26.1 replacement for the 1.21.1
 * {@code GlintWrappingBufferSource} buffer fan-out. Invisible entities are skipped so glint doesn't
 * reveal them.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {

    /**
     * Publish this renderer's body model for the span of {@code submit} so the per-layer glint hook
     * ({@code SubmitNodeCollectionMixin}) can tell the body's own {@code submitModel} apart from the
     * RenderLayer submits and skip it, the body is glinted below by {@link #cg_entityGlint}; the layers
     * are glinted in the storage hook. Cleared at RETURN so a non-entity / non-living submit can't leave a
     * stale model that suppresses a later layer's glint.
     */
    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"), require = 0
    )
    private void cg_markBodyModel(LivingEntityRenderState state, PoseStack poseStack,
            SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        EntityGlintRender.setCurrentBodyModel(((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel());
        // Publish the entity state for the layer span so block-model layers (mooshroom mushrooms, snow-golem
        // pumpkin), which submit via submitBlockModel with no state, can find their owning entity's glow.
        EntityGlintRender.setCurrentEntity(state);
    }

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("RETURN"), require = 0
    )
    private void cg_clearBodyModel(LivingEntityRenderState state, PoseStack poseStack,
            SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        EntityGlintRender.setCurrentBodyModel(null);
        EntityGlintRender.setCurrentEntity(null);
    }

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"),
        require = 0
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void cg_entityGlint(LivingEntityRenderState state, PoseStack poseStack,
            SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        // Clear any stale glow request first, every frame, every entity. A pooled/reused render state
        // could otherwise carry a previous (glowing) entity's outline into ModelFeatureRendererMixin.
        state.setRenderData(EntityGlintRender.GLOW_OUTLINE, null);
        if (state.isInvisible) return;
        EntityGlintRender.Resolution r = state.getRenderData(EntityGlintRender.RENDER_DATA);
        if (r == null) return;
        EntityModel<?> model = ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel();
        if (model == null) return;

        // Skip the inner-body glint on an entity whose TRANSLUCENT outer shell already carries the glint
        // (slime): the shell is the visible surface, so glinting the hidden inner body too doubles it and the
        // inner reads over the shell. The shell's layer submit (SubmitNodeCollectionMixin) runs before this
        // popPose hook, so the mark is already set. Non-shelled entities glint their body as normal.
        if (r.data != null && !EntityGlintRender.hasTranslucentShell(state)) {
            // Body texture: only needed for the chromatic post-Iris overlay's cutout alpha-test (the in-phase
            // path ignores it). getTextureLocation is in hand here; null is fine (overlay draws the full mesh).
            Identifier bodyTex = ((LivingEntityRenderer) (Object) this).getTextureLocation(state);
            EntityGlintRender.submitEntityGlint(collector, model, state, poseStack, state.lightCoords, r.data, bodyTex);
        }

        // Glow outline ring around the body silhouette. Instead of queuing a deferred re-pose + second
        // setupAnim at AfterWeather, we stash the colour + texture on the render state here (where
        // getTextureLocation is in hand). ModelFeatureRendererMixin reads it back at the body draw and
        // tees the silhouette IN-PHASE on the already-posed model, exactly how vanilla's own glowing-
        // entity outline works (SubmitNodeCollection.submitModel adds a second outline-phase node for it).
        // The mask + occluded composite still runs once in drainBodyOutlines.
        if (r.glowing || r.glowColors.length > 0) {
            Identifier texture = ((LivingEntityRenderer) (Object) this).getTextureLocation(state);
            if (texture != null) {
                state.setRenderData(EntityGlintRender.GLOW_OUTLINE,
                        new EntityGlintRender.GlowOutline(EntityGlintRender.outlineColorFor(r), texture, model));
            }
        }
    }
}
