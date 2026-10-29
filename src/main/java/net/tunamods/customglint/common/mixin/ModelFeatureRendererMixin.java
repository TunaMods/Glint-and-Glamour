package net.tunamods.customglint.common.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * In-phase entity-body glow-outline capture, the vanilla-parity path (option A).
 *
 * <p>Vanilla's own glowing-entity outline submits a SECOND model node into the outline phase
 * ({@code SubmitNodeCollection.submitModel}, the {@code outlineColor != 0} block), which walks the model
 * again. We go one better: {@code ModelFeatureRenderer.prepareModel} builds each model node's vertices while
 * the model is posed and {@code setupAnim}'d, and we wrap that single {@code renderToBuffer} so its vertices
 * fan into BOTH the node's own buffer and our glow-mask batch ({@link CustomGlintRenderer.TeeConsumer}). The
 * silhouette costs a few extra vertex writes, no second traversal, no second setupAnim. This is what makes
 * glow scale like vanilla. {@code drainBodyOutlines} flushes the mask batch and runs the single occluded
 * composite.
 *
 * <p>Gated on {@code submit.model() == go.model()} so only the BODY model fans with the body texture;
 * overlay layers share the render state but submit their own models/textures.
 */
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMixin {

    // @WrapOperation (not @Redirect): @Redirect claims exclusive ownership of this renderToBuffer call site, so
    // any other mod redirecting the same body draw (entity recolour, outline, shader-compat mods) would collide
    // and one of us would silently drop. @WrapOperation is stackable, each wrapper receives the next as its
    // Operation, so we compose with other mods instead of fighting them. The submit is prepareModel's own
    // argument, captured unambiguously by type via @Local(argsOnly).
    @WrapOperation(method = "prepareModel",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
            require = 0)
    private <S> void cg_fanBodyGlow(Model instance, PoseStack pose, VertexConsumer buffer,
            int light, int overlay, int color, Operation<Void> original,
            @Local(argsOnly = true) ModelFeatureRenderer.Submit<S> submit) {
        VertexConsumer target = buffer;
        if (submit.state() instanceof EntityRenderState state) {
            EntityGlintRender.GlowOutline go = state.getRenderData(EntityGlintRender.GLOW_OUTLINE);
            if (go != null) {
                if (instance == go.model()) {
                    // Body: fan this single walk into the glow mask too, no extra traversal, using the
                    // real entity texture so the silhouette alpha-discards to the body shape.
                    target = CustomGlintRenderer.fanBodyGlow(buffer, submit.pose(), go.color(), go.texture(),
                            state.boundingBoxWidth, state.boundingBoxHeight, state);
                } else if (EntityGlintRender.isEntitySurface(submit.renderType())) {
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
