package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the first-person hand-render window.
 *
 * <p>First-person hand items run through the same deferred item path as world items, so their outline jobs
 * would land in the shared world queues. But the main {@code LevelRenderer.executeOutline} HEAD drain runs
 * during the level framegraph, which finishes <em>before</em> {@code GameRenderer.renderItemInHand}, and the
 * hand pose is view-space anyway. So held items go to hand-only queues, drained right after the hand is drawn
 * ({@code GameRendererMixin}). This flag is how the special-item path knows it is in the hand: special items
 * have no display context at their submit hook.
 *
 * <p>In 26.2 {@code GameRenderer.renderItemInHand} calls {@code submitHandsWithItems} and then
 * {@code renderAllFeatures} for the hand, which is where quad items are prepared (and queue their jobs). The
 * window therefore stays open past this method; {@code GameRendererMixin} closes it after that
 * {@code renderAllFeatures} returns.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class ItemInHandRendererMixin {

    // Both the vanilla and Iris hand paths call submitHandsWithItems (Iris via HandRenderer →
    // iris$renderHandsWithCustomRenderer on earlier versions), so this fires in both.
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), require = 0)
    private void cg_markHandRenderStart(float partialTicks, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
            PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, CallbackInfo ci) {
        EntityGlintRender.beginFirstPersonHand();
    }
}
