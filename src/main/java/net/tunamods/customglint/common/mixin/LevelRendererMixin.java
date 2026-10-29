package net.tunamods.customglint.common.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import java.util.Optional;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Level-frame hooks for the glow and slime-shell glint drains. Off the shader path they run between vanilla's
 * render passes (see the comments on the hooks). Under a pack the drain runs AFTER the level is fully rendered.
 *
 * <p><b>Why the pack drain is late.</b> Off the shader path, the glow capture + composite run mid-{@code LevelRenderer.render}
 * right after the main pass; that drain draws straight onto the main target and works fine. Under an active Iris pack it does NOT: while the level is
 * rendering, Iris rebinds its OWN gbuffer framebuffer for any unknown shader
 * ({@code IrisRenderingPipeline.bindDefault} via {@code MixinCompiledShaderProgram}). Our glow mask + the
 * fullscreen composite use custom GLSL Iris doesn't know, so their framebuffer gets hijacked into Iris's
 * deferred buffers and the pack's later composite passes re-shade the result, the whole screen goes black
 * with only the per-object scissor boxes showing.
 *
 * <p><b>The fix is timing, not program assignment</b> (assigning the composite to an Iris program would
 * REPLACE our post shader, see {@code IrisCompat}). {@code LevelRenderer.render} builds and executes the framegraph
 * internally; Iris's deferred + final composite run inside it, so by {@code @At("TAIL")} the framegraph is
 * done, Iris has written its final image to the main target, and {@code ImmediateState.isRenderingLevel} is
 * false. Drawing the glow here is exactly like a GUI overlay: our own shaders run, our own targets bind, and
 * the ring lands on the finished frame. Occlusion still reads the main target's depth (committed by the
 * scene render).
 *
 * <p><b>Camera-view matrix.</b> {@code LevelRenderer.render} pushes the camera-view matrix onto the modelview stack
 * for the duration of the level (so silhouettes project to the right screen spot) and pops it before this
 * TAIL. The glow mask draws entity geometry through that stack, so without re-applying the view here the
 * ring projects against an identity view and floats free of its object, sliding as the camera turns. We
 * push {@code cameraState.viewRotationMatrix} (the same matrix the level used) around the drain and pop it after.
 *
 * <p>Gated on {@link CustomGlintRenderer#isShaderPackActive()}: with no pack the after-main-pass drain owns the
 * frame (and must, it sequences correctly after vanilla's clouds and weather), so this no-ops to avoid a
 * double drain. The first-person hand-item drain ({@code ItemInHandRendererMixin}) is unaffected: it already
 * runs after {@code LevelRenderer.render}, when the hand items are queued.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    // Off the shader path the drains open their own render passes, and every RenderLevelStageEvent fires
    // inside vanilla's open "Main" pass (opening a second one throws). So the off-pack work hooks the
    // private steps between passes instead: executeOutline runs right after the main pass (and the OIT
    // composite) closes, after clouds and weather, in both transparency modes.

    // Classic transparency draws solid and translucent in ONE pass, so there is no gap for the opaque-depth
    // snapshot the slime-shell glint occludes against. When one is needed, end vanilla's pass after the solid
    // phase and hand executeClassicTransparency a fresh pass on the same attachments. RenderPass.close is
    // idempotent, so vanilla's try-with-resources closing its (already closed) pass is a no-op.
    @Unique private static @Nullable RenderPass cg_translucentPass;

    @ModifyVariable(method = "executeClassicTransparency", at = @At("HEAD"), argsOnly = true, require = 0)
    private RenderPass cg_splitForSolidDepth(RenderPass renderPass) {
        if (CustomGlintRenderer.isShaderPackActive() || !EntityGlintRender.hasTranslucentLayerGlints()) {
            return renderPass;
        }
        renderPass.close();
        EntityGlintRender.captureSolidDepth();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        cg_translucentPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Main (customglint: translucent)", main.getColorTextureView(), Optional.empty(),
                main.getDepthTextureView(), OptionalDouble.empty());
        RenderSystem.bindDefaultUniforms(cg_translucentPass);
        return cg_translucentPass;
    }

    @Inject(method = "executeClassicTransparency", at = @At("RETURN"), require = 0)
    private void cg_closeTranslucentPass(CallbackInfo ci) {
        if (cg_translucentPass != null) {
            cg_translucentPass.close();
            cg_translucentPass = null;
        }
    }

    // Improved transparency already ends the solid pass before executeOit.
    @Inject(method = "executeOit", at = @At("HEAD"), require = 0)
    private void cg_snapshotSolidDepthOit(CallbackInfo ci) {
        if (!CustomGlintRenderer.isShaderPackActive() && EntityGlintRender.hasTranslucentLayerGlints()) {
            EntityGlintRender.captureSolidDepth();
        }
    }

    // Translucent-shell glints (slime) first, after every translucent draw so they land on top of the shell,
    // then the glow ring so it composites over the glint as for opaque entities.
    @Inject(method = "executeOutline", at = @At("HEAD"), require = 0)
    private void cg_drainAfterMainPass(CallbackInfo ci) {
        if (!CustomGlintRenderer.isShaderPackActive()) {
            EntityGlintRender.drainTranslucentLayerGlints();
            EntityGlintRender.drainBodyOutlines();
        }
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void customglint$drainGlowAfterIris(GraphicsResourceAllocator resourceAllocator,
            boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog, Vector4f fogColor,
            boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
        if (CustomGlintRenderer.isShaderPackActive()) {
            // Re-apply the camera-view matrix the level rendered with, render already popped it,
            // so the glow mask would otherwise project against an identity view and float off-target.
            Matrix4fStack mvStack = RenderSystem.getModelViewStack();
            mvStack.pushMatrix();
            mvStack.mul(cameraState.viewRotationMatrix);
            try {
                EntityGlintRender.drainBodyOutlines();
                // Chromatic glint can't draw in-phase under a pack (Iris → flat white); re-render the queued
                // chromatic models + 3rd-person/dropped items here, post-Iris, with the camera view on the
                // stack. First-person held items drain at the hand point (GameRendererMixin). See drainChromaticOverlays.
                EntityGlintRender.drainChromaticOverlays(false, null);
            } finally {
                mvStack.popMatrix();
            }
        }
    }
}
