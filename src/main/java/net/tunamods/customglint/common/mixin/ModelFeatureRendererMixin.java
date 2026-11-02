package net.tunamods.customglint.common.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In-phase entity-body glow-outline capture, the vanilla-parity path (option A).
 *
 * <p>Vanilla's own glowing-entity outline tees a second {@code renderToBuffer} into a shared
 * {@code OutlineBufferSource} right after the body draws, while the model is still posed and
 * {@code setupAnim}'d (see {@code ModelFeatureRenderer.renderModel}, the {@code submit.outlineColor()}
 * block). That is why vanilla glow is nearly free for hundreds of entities: no second {@code setupAnim},
 * no per-entity {@code PoseStack}, no deferred re-walk, just one extra batched vertex emit.
 *
 * <p>We go one better than vanilla: rather than a SECOND {@code renderToBuffer} per glowing entity
 * (vanilla's cost, and what an earlier version of this mixin did), we redirect the body's single
 * {@code renderToBuffer} to fan its vertices into BOTH the normal buffer and our glow-mask buffer via a
 * {@link VertexMultiConsumer}. The silhouette is captured during the one model
 * walk the entity already does, a few extra vertex writes, no second traversal, no second setupAnim.
 * This is what makes glow scale like vanilla (the bottleneck was the per-entity second model walk).
 * {@code drainBodyOutlines} flushes the mask buffer and runs the single occluded composite.
 *
 * <p>Gated on {@code submit.model() == go.model()} so only the BODY model fans, overlay layers share
 * the render state but submit their own models/textures and must not be traced with the body texture.
 */
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMixin {

    /**
     * Snapshot the STABLE opaque depth at the very START of the translucent entity pass. Every opaque
     * surface (terrain + solid entity bodies) has committed its depth, but no translucent shell has drawn
     * yet. The stashed translucent-layer glints (slime outer shell) occlude against this snapshot, not the
     * shell's per-frame re-sorted depth (the slime flicker). Gated so the depth copy only runs when there is
     * actually a slime-shell glint to draw. Off the shader path only (under a pack the overlay owns it).
     */
    @Inject(method = "renderTranslucent", at = @At("HEAD"), require = 0)
    private void cg_captureSolidDepth(SubmitNodeCollection nodeCollection,
            MultiBufferSource.BufferSource bufferSource, OutlineBufferSource outlineBufferSource,
            MultiBufferSource.BufferSource crumblingBufferSource, CallbackInfo ci) {
        if (EntityGlintRender.hasTranslucentLayerGlints()) {
            EntityGlintRender.captureSolidDepth();
        }
    }
    // The stashed translucent-layer glints (slime shell) are NOT drawn here: draining at renderTranslucent
    // RETURN drew them before LATER-order translucent shells (the slime shell submits at order(1)), which then
    // painted over them and washed them out. They're drained at RenderLevelStageEvent.AfterWeather instead
    // (CustomGlintClientInit), after EVERY translucent pass, so they land ON TOP of the shell, while their
    // in-shader occlusion still reads the stable opaque-depth snapshot captured above.

    /**
     * Mute vanilla's glowing-entity outline when OUR glow is on the same entity. Vanilla tees a second
     * {@code renderToBuffer} into the {@code OutlineBufferSource} whenever {@code submit.outlineColor() != 0}
     * (the team colour the renderer stamps on every submit of a {@code isCurrentlyGlowing} entity). Our glow
     * draws its own ring for that entity, so forcing the outline colour to 0 on its submits drops vanilla's
     * tee, leaving only our outline instead of two stacked rings. Gated on the entity render state carrying
     * our {@code GLOW_OUTLINE} (set in {@link LivingEntityRendererMixin}), so non-glinted glowing entities
     * keep their normal vanilla outline. Body, entity-surface layers, and armor all submit with this state.
     */
    @ModifyExpressionValue(method = "renderModel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeStorage$ModelSubmit;outlineColor()I"),
            require = 0)
    private int cg_muteVanillaGlow(int original, @Local(argsOnly = true) SubmitNodeStorage.ModelSubmit<?> submit) {
        if (submit.state() instanceof EntityRenderState es
                && es.getRenderData(EntityGlintRender.GLOW_OUTLINE) != null) {
            return 0;
        }
        return original;
    }

    // @WrapOperation (not @Redirect): @Redirect claims exclusive ownership of this renderToBuffer call site, so
    // any other mod redirecting the same body draw (entity recolour, outline, shader-compat mods) would collide
    // and one of us would silently drop. @WrapOperation is stackable, each wrapper receives the next as its
    // Operation, so we compose with other mods instead of fighting them. submit/renderType are renderModel's
    // own arguments, captured unambiguously by type via @Local(argsOnly).
    @WrapOperation(method = "renderModel",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
            require = 0)
    private <S> void cg_fanBodyGlow(Model instance, PoseStack pose, VertexConsumer buffer,
            int light, int overlay, int color, Operation<Void> original,
            @Local(argsOnly = true) SubmitNodeStorage.ModelSubmit<S> submit,
            @Local(argsOnly = true) RenderType renderType) {
        VertexConsumer target = buffer;
        if (submit.state() instanceof EntityRenderState state) {
            EntityGlintRender.GlowOutline go = state.getRenderData(EntityGlintRender.GLOW_OUTLINE);
            if (go != null) {
                if (instance == go.model()) {
                    // Body: fan this single walk into the glow mask too, no extra traversal, using the
                    // real entity texture so the silhouette alpha-discards to the body shape.
                    target = CustomGlintRenderer.fanBodyGlow(buffer, submit.pose(), go.color(), go.texture(), state);
                } else if (EntityGlintRender.isEntitySurface(renderType)) {
                    // RenderLayer surface of the SAME glowing entity (sheep wool, slime outer cube, saddle,
                    // stray clothing, …): fan its silhouette into the same mask + share the entity outline id
                    // so the mob composites as ONE ring. White.png = full layer geometry (set inside).
                    target = CustomGlintRenderer.fanLayerGlow(buffer, submit.pose(), go.color(), state);
                }
            }
        }
        // Re-emit through the operation chain (next wrapper / vanilla) with our swapped target buffer.
        original.call(instance, pose, target, light, overlay, color);
    }
}
