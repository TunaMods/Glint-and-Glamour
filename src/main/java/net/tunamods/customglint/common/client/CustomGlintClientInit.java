package net.tunamods.customglint.common.client;

import com.google.common.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.CustomGlintApiMod;

/**
 * Client-only initialization for {@code customglint_api}. Reached from
 * {@link CustomGlintApiMod} behind a {@code FMLEnvironment.getDist() == Dist.CLIENT} guard, so the JVM
 * never resolves {@link CustomGlintRenderer} or any client-only event class on a dedicated server.
 */
public final class CustomGlintClientInit {
    private CustomGlintClientInit() {}

    /** Invoked from the API mod constructor on client only, with the mod event bus + container. */
    public static void run(IEventBus modEventBus, ModContainer modContainer) {
        // Client-only settings (outline resolution and distance caps, per-type outline toggles, GUI skins,
        // menu button sound). CLIENT type: personal, never synced. Registering the generated config screen
        // makes it editable in-game from the mod list; both the screen and on-disk edits apply live (the
        // render code re-reads each frame).
        modContainer.registerConfig(ModConfig.Type.CLIENT, GlintClientConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (container, parent) -> new ConfigurationScreen(container, parent));

        modEventBus.addListener(CustomGlintClientInit::onRegisterClientReloadListeners);

        // The glow-outline composite pipelines are used via RenderPass.setPipeline directly (not through a
        // RenderType), so their shaders only get compiled if they're registered here. The silhouette pipeline
        // goes through RenderType.create and is compiled lazily like the other glint RTs.
        modEventBus.addListener((RegisterRenderPipelinesEvent event) -> {
            event.registerPipeline(GlintPipelines.GLOW_COMPOSITE_ID_PIPE);
            event.registerPipeline(GlintPipelines.GLOW_UPSCALE_PIPE);
            event.registerPipeline(GlintPipelines.CHROMATIC_COMPOSITE_PIPE);
        });

        // Entity glint attachment. The 26.1 entity render is decoupled from the entity by draw time
        // (LivingEntityRenderer.submit only sees a LivingEntityRenderState), so the glint must ride
        // the render state. This modifier runs after vanilla extraction for EVERY living renderer
        // (subclasses included) and stashes the resolved glint under EntityGlintRender.RENDER_DATA;
        // LivingEntityRendererMixin reads it back to submit the glint nodes.
        modEventBus.addListener((RegisterRenderStateModifiersEvent event) ->
                event.registerEntityModifier(
                        new TypeToken<LivingEntityRenderer<LivingEntity, LivingEntityRenderState, ?>>() {},
                        (entity, state) -> state.setRenderData(EntityGlintRender.RENDER_DATA,
                                EntityGlintRender.resolveResolution(entity))));

        // Per-frame setup. RenderFrameEvent.Pre fires once per rendered frame regardless of shader pack
        // or batched-render plumbing.
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> {
            // Opt-in GL debug probe (-Dcustomglint.gldebug=true). No-ops otherwise. Installs a synchronous
            // debug callback that logs a stack trace for the first occurrence of each high-severity GL error
            // (e.g. the "No active program" spam under Iris), naming the exact offending draw.
            GlDebugProbe.install();
            // Register our glint pipeline with Iris (soft/reflective, no-op without Iris) so an active
            // pack runs it through the right program instead of drawing white. Self-guards after it lands;
            // done here (not FMLClientSetupEvent) because IrisApi's singleton isn't ready that early.
            IrisCompat.register();
            // Drop any body outlines queued last frame but never drained (level not rendered, stage
            // cancelled, etc.) so they can't replay against the wrong frame.
            EntityGlintRender.clearBodyOutlineQueue();
            // Recycle our vertex batches' GPU buffers from finished frames, dropping anything left unflushed.
            CustomGlintRenderer.endFrameBatches();
            // Clear the item-render bridge thread-locals. A frame whose render threw skips the RETURN
            // injects that normally clear them, so reset here to keep stale glint/glow state from leaking
            // into the next frame.
            GlintCarrier.resetSubmitState();
            // Cache the GUI scale once for this frame. The GUI glint/glow overlay reads it per glinted
            // icon and it can't change mid-frame.
            CustomGlintRenderer.refreshFrameGuiScale();
            // Resolve "is a shader pack active" once for this frame (reflective Iris probe, hit many times
            // per frame downstream); a pack can't toggle mid-frame.
            CustomGlintRenderer.refreshFrameShaderActive();
        });

        // Snapshot the STABLE opaque depth between the solid and translucent feature passes. Every opaque
        // surface (terrain + solid entity bodies) has committed its depth, but no translucent shell has drawn
        // yet. The stashed translucent-layer glints (slime outer shell) occlude against this snapshot, not the
        // shell's per-frame re-sorted depth (the slime flicker). Gated so the depth copy only runs when there
        // is actually a slime-shell glint to draw. Off the shader path only (under a pack the overlay owns it).
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterOpaqueFeatures event) -> {
            if (!CustomGlintRenderer.isShaderPackActive() && EntityGlintRender.hasTranslucentLayerGlints()) {
                EntityGlintRender.captureSolidDepth();
            }
        });

        // Entity-body glow rings are queued during the entity submit(...) extraction (where the
        // entity-local pose is in hand) and drained HERE, at AfterWeather, the last level stage. The
        // composite writes straight to the main target's colour texture (raw GL, frame-graph pass aside),
        // and under Fancy graphics the clouds and weather passes ALSO write to main, executing AFTER the
        // "main" pass where the earlier stages fire. Draining at AfterTranslucentFeatures (inside the main
        // pass) let clouds and rain paint over the ring; AfterWeather runs after both, so the ring stays on
        // top of them. It's still later than the translucent glint (the reason the drain moved off
        // AfterOpaqueFeatures: there the glint drew after the ring → the ring showed through it as a faded
        // line). Occlusion is unaffected: the mask samples the OPAQUE scene depth, committed in the opaque
        // phase and never overwritten by translucent draws, so the ring still hides behind solid world
        // geometry. Under Fabulous graphics clouds/weather render to their own targets (combined later by
        // the transparency post-chain), so our direct-to-main composite lands before that chain at either
        // stage, no change there.
        // Off the shader path this is the glow drain: raw GL straight onto the main target, after the
        // cloud/weather passes (so the ring composites on top of them). Under an active Iris pack this
        // point is mid-framegraph, where Iris hijacks our framebuffer into its gbuffer (black screen),
        // there the drain is relocated to LevelRendererMixin (LevelRenderer.render TAIL, post-Iris). Skip here when
        // a pack is active so the glow isn't drained twice.
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterWeather event) -> {
            if (!CustomGlintRenderer.isShaderPackActive()) {
                // Translucent-shell glints (slime) draw here, after EVERY translucent pass, so they land ON
                // TOP of the shell instead of being washed out beneath a later-order shell. Their occlusion
                // still reads the stable opaque-depth snapshot taken at AfterOpaqueFeatures. Before the
                // glow ring so the ring composites on top of the glint, as for opaque entities.
                EntityGlintRender.drainTranslucentLayerGlints();
                EntityGlintRender.drainBodyOutlines();
            }
        });
    }

    private static void onRegisterClientReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(CustomGlint.res("glint_textures"),
                (ResourceManagerReloadListener) (ResourceManager manager) ->
                        CustomGlintRenderer.clearTextures());
    }
}
