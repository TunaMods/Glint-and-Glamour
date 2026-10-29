package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drains the glow-outline pass AFTER the level is fully rendered, the shader-pack path only.
 *
 * <p><b>Why this exists.</b> Off the shader path, the glow capture + composite run mid-{@code LevelRenderer.render}
 * at {@code RenderLevelStageEvent.AfterWeather} (see {@code CustomGlintClientInit}); that drain draws raw GL
 * straight onto the main target and works fine. Under an active Iris pack it does NOT: while the level is
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
 * push {@code modelViewMatrix} (the same matrix the level used) around the drain and pop it after.
 *
 * <p>Gated on {@link CustomGlintRenderer#isShaderPackActive()}: with no pack the AfterWeather drain owns the
 * frame (and must, it sequences correctly against vanilla cloud/weather passes), so this no-ops to avoid a
 * double drain. The first-person hand-item drain ({@code ItemInHandRendererMixin}) is unaffected: it already
 * runs after {@code LevelRenderer.render}, when the hand items are queued.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void customglint$drainGlowAfterIris(GraphicsResourceAllocator resourceAllocator,
            DeltaTracker deltaTracker, boolean renderOutline, CameraRenderState cameraState,
            Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog, Vector4f fogColor,
            boolean shouldRenderSky, CallbackInfo ci) {
        if (CustomGlintRenderer.isShaderPackActive()) {
            // Re-apply the camera-view matrix the level rendered with, render already popped it,
            // so the glow mask would otherwise project against an identity view and float off-target.
            Matrix4fStack mvStack = RenderSystem.getModelViewStack();
            mvStack.pushMatrix();
            mvStack.mul(modelViewMatrix);
            try {
                EntityGlintRender.drainBodyOutlines();
                // Chromatic glint can't draw in-phase under a pack (Iris → flat white); re-render the queued
                // chromatic models + 3rd-person/dropped items here, post-Iris, with the camera view on the
                // stack. First-person held items drain at the hand point (GameRendererMixin). See drainChromaticOverlays.
                EntityGlintRender.drainChromaticOverlays(false);
            } finally {
                mvStack.popMatrix();
            }
        }
    }
}
