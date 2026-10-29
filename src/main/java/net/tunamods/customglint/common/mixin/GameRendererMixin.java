package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the first-person hand projection matrix so the glow drain can scissor the held-item composite.
 *
 * <p>The first-person hand renders with its own projection ({@code GameRenderer.hudProjection}, set up
 * from {@code cameraState.hudFov} immediately before {@code renderItemInHand}). That projection is uploaded
 * to a GPU buffer, {@code RenderSystem.setProjectionMatrix} takes a {@code GpuBufferSlice}, not a
 * {@code Matrix4f}, so it can't be read back at the drain. We grab the {@code Matrix4f} here at
 * {@code renderItemInHand} HEAD (after {@code hudProjection} is configured) and hand it to
 * {@link EntityGlintRender} for the held-item composite scissor.
 *
 * <p><b>Held-item glow drain off the shader path</b> runs inside {@code renderItemInHand}, after the hand's
 * features are prepared and drawn (that prepare is what queues quad items' outline jobs) and their render pass
 * has closed, just before the camera modelview is popped, so the ring projects exactly like the item did. That
 * point also closes the hand window {@code ItemInHandRendererMixin} opened. The hand may draw into its own
 * depth target ({@code hud3DTarget}, when consistent depth is required), so this drain samples the depth view
 * {@code renderItemInHand} was given, not the main target's. The pack drain below uses the main depth.
 *
 * <p><b>Held-item glow drain under an active Iris pack.</b> Under a pack Iris cancels vanilla's
 * {@code renderHandsWithItems} ({@code MixinGameRenderer.iris$disableVanillaHandRendering}) and renders the
 * hand itself, in-pipeline, via {@code HandRenderer.renderSolid}, baking the hand perspective + bob into
 * the uploaded projection and drawing with an identity modelview. So the held-item drain moves HERE, to
 * {@code renderItemInHand} RETURN (past Iris's pass, no gbuffer hijack), and reconstructs that state from
 * the still-bound {@code hudProjection} + a rebuilt bob modelview. See the TRIED history on the RETURN
 * method for the two earlier dead ends. Gated on {@link CustomGlintRenderer#isShaderPackActive()} so it
 * doesn't double-drain off the pack path.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow @Final private Projection hudProjection;
    @Shadow private void bobHurt(CameraRenderState cameraState, PoseStack poseStack) { throw new AssertionError(); }
    @Shadow private void bobView(CameraRenderState cameraState, PoseStack poseStack) { throw new AssertionError(); }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), require = 0)
    private void cg_captureHandProjection(CameraRenderState cameraState, PlayerRenderState playerState,
            GpuTextureView depthTextureView, CallbackInfo ci) {
        EntityGlintRender.setFirstPersonProjection(hudProjection.getMatrix(new Matrix4f()));
    }

    // Before the camera-modelview pop, the only Matrix4fStack.popMatrix in the method: the hand's render pass
    // (a try-with-resources around renderAllFeatures) has closed by then, so our drain can open its own passes.
    @Inject(method = "renderItemInHand",
            at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"),
            require = 0)
    private void cg_drainHeldItemOutline(CameraRenderState cameraState, PlayerRenderState playerState,
            GpuTextureView depthTextureView, CallbackInfo ci) {
        EntityGlintRender.endFirstPersonHand();
        // Under an active Iris pack this point is still inside the hand's pack pass, so compositing here
        // hijacks the framebuffer into Iris's gbuffer (the 1st-person black box). Leave whatever's queued
        // in place; the RETURN drain below takes it, just past the hand pass (no hijack).
        if (CustomGlintRenderer.isShaderPackActive()) return;
        // Full-screen (no scissor): the hand item's pose is in view space, not camera-relative world
        // space, so the world projection used to size per-group scissors wouldn't line up. Only the held
        // item is queued here (one group), so a full-screen composite costs next to nothing.
        EntityGlintRender.drainBodyOutlines(false, depthTextureView);
    }

    // TRIED (attempt 1, insufficient ALONE): adding only this RETURN drain did nothing, the held item
    // outline still floated. Root cause: under Iris the hand item is captured into the shared item-outline
    // queue DURING the level framegraph (Iris renders the hand in-pipeline), so LevelRendererMixin's
    // renderLevel-TAIL world drain already consumed AND cleared it (with the world projection, that's the
    // float) before this RETURN ran, leaving an empty queue here. The fix that made this drain effective:
    // route first-person items to a dedicated HELD_FP_OUTLINES queue the world drain never touches (see
    // EntityGlintRender.HELD_FP_OUTLINES + ItemRendererMixin's displayContext().firstPerson() gate), so the
    // hand item survives the world drain and only this hand-projection drain composites it.
    //
    // TRIED (attempt 2, STILL floated): with the dedicated queue in place, this drain ran with content but
    // re-applied the world `modelViewMatrix` (mvStack.mul(modelViewMatrix)). Wrong space. Verified against
    // Iris's HandRenderer.setupGlState/renderSolid: under a pack Iris bakes the hand
    // perspective + bobHurt/bobView INTO the uploaded projection matrix and renders the hand with an
    // IDENTITY modelview, and the item submit poses are hand-LOCAL (relative to a fresh PoseStack). So
    // multiplying by the world view put the hand-local pose out into the world ≈1 block away, the float.
    //
    // FIX (attempt 3): we don't have Iris's baked projection (it's uploaded as an unreadable GpuBufferSlice),
    // but the plain hand perspective (`hudProjection`, set by vanilla at GameRenderer line 746) is STILL the
    // bound ProjMat at this RETURN, and the main depth was cleared right before the hand (line 747) so the
    // held item always reads visible. The only thing missing vs Iris's bake is bob, which we put into the
    // MODELVIEW instead: rebuild it with the same bobHurt/bobView vanilla/Iris use, set it as the modelview,
    // and drain. ProjMat(hud) × bob × hand-local-pose == what Iris drew. Matches the off-pack path too
    // (there vanilla puts bob in the pose and pushes modelViewMatrix, netting the same bob × pose).
    @Inject(method = "renderItemInHand", at = @At("RETURN"), require = 0)
    private void cg_drainHeldItemOutlineUnderShaders(CameraRenderState cameraState, PlayerRenderState playerState,
            GpuTextureView depthTextureView, CallbackInfo ci) {
        if (!CustomGlintRenderer.isShaderPackActive()) {
            return;   // off the pack path cg_drainHeldItemOutline already drained the held item.
        }
        // Rebuild the hand bob (bobHurt + optional bobView) into a fresh pose, Iris baked this into its
        // projection; we instead carry it in the modelview because our ProjMat is the plain hudProjection.
        PoseStack bob = new PoseStack();
        bobHurt(cameraState, bob);
        if (Minecraft.getInstance().options.bobView().get()) {
            bobView(cameraState, bob);
        }
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.set(bob.last().pose());   // absolute: hand items are hand-local; bob is the whole modelview.
        try {
            // Main depth, not depthTextureView: Iris draws the hand into the level depth itself and never touches
            // the hand depth vanilla would have used, so testing against that hides nothing.
            EntityGlintRender.drainBodyOutlines(false, null);
            // First-person held chromatic items: re-render post-Iris with the hand projection + bob modelview
            // in place (same state the glow FP drain uses). See EntityGlintRender.drainChromaticOverlays.
            EntityGlintRender.drainChromaticOverlays(true, null);
        } finally {
            mvStack.popMatrix();
        }
    }
}
