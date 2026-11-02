package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drains the first-person held-item glow outlines.
 *
 * <p>First-person hand items run through the same deferred {@code ItemFeatureRenderer.renderItem} path
 * as world items, so {@code ItemRendererMixin} queues their outline jobs into the shared
 * {@code ITEM_OUTLINES} queue. But the main {@code RenderLevelStageEvent.AfterWeather} drain runs
 * during the level framegraph, which finishes <em>before</em> {@code GameRenderer.renderItemInHand},
 * so by the time the hand items queue, the level drain has already run and cleared its queue. Those jobs
 * would otherwise be dropped on the next frame's defensive reset, which is why the glow shows in 3rd
 * person / on dropped items but not on the held item in 1st person.
 *
 * <p>{@code renderHandsWithItems} submits the hand item nodes, then draws them
 * ({@code renderAllFeatures()}) and flushes ({@code endBatch()}) before returning, so at RETURN the hand
 * items are both queued and depth-committed to the main target. A second drain here composites their ring
 * (no entity bodies are queued in 1st person, so this only paints the hand items).
 * Vanilla clears the depth buffer before the hand render (GameRenderer), so the silhouette occlusion test
 * naturally reads "visible", correct, the held item always draws on top.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    // Mark the first-person hand-render window so the outline queues can route held items (quad, special
    // 3D model, and part submits) to their hand-only queues. Both the vanilla and Iris hand paths call
    // renderHandsWithItems (Iris via HandRenderer → iris$renderHandsWithCustomRenderer), so this fires in
    // both, the quad path keys off the ItemSubmit's display context instead, but special items have no
    // display context at their submit hook, so they need this flag.
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), require = 0)
    private void cg_markHandRenderStart(float frameInterp, PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector, LocalPlayer player, int lightCoords, CallbackInfo ci) {
        EntityGlintRender.beginFirstPersonHand();
    }

    @Inject(method = "renderHandsWithItems", at = @At("RETURN"), require = 0)
    private void cg_drainHandItemOutlines(float frameInterp, PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector, LocalPlayer player, int lightCoords, CallbackInfo ci) {
        EntityGlintRender.endFirstPersonHand();
        // Under an active Iris pack this point is still inside the hand's pack pass, so compositing here
        // hijacks the framebuffer into Iris's gbuffer (the 1st-person black box). Leave whatever's queued
        // in place; GameRendererMixin drains it at renderItemInHand RETURN, just past the hand pass (no
        // hijack). Do NOT clear here, under a pack the first-person frame's entity/armor/item outlines are
        // drained at that RETURN, so clearing would wipe them.
        if (CustomGlintRenderer.isShaderPackActive()) {
            return;
        }
        // The hand drain: the hand item's pose is in view space, not camera-relative world space, so the
        // drain scissors it with the captured hand projection instead of the world one.
        EntityGlintRender.drainBodyOutlines(false);
    }
}
