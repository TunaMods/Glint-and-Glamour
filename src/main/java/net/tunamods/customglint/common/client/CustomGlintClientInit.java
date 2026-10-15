package net.tunamods.customglint.common.client;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.ContainerScreenEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.tunamods.customglint.common.CustomGlintApiMod;

/**
 * Client-only initialization for {@code customglint_api}. Reached from
 * {@link CustomGlintApiMod#CustomGlintApiMod()} via {@code DistExecutor.safeRunWhenOn(Dist.CLIENT, ...)}
 * so the JVM never resolves {@link CustomGlintRenderer} or any client-only Forge event class on a
 * dedicated server.
 */
public final class CustomGlintClientInit {
    private CustomGlintClientInit() {}

    /** Invoked via {@code () -> CustomGlintClientInit::run} from the API mod constructor on client only. */
    public static void run() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(CustomGlintClientInit::onRegisterClientReloadListeners);
        // Glow-outline core shaders (silhouette + composite): mod-bus event, client only.
        FMLJavaModLoadingContext.get().getModEventBus().addListener(GlowOutlineRenderer::registerShaders);
        // Procedural chromatic glint core shader: mod-bus event, client only.
        FMLJavaModLoadingContext.get().getModEventBus().addListener(CustomGlintRenderer::registerShaders);

        // Release the glow-outline offscreen target on resource reload so it isn't pinned for the session.
        CustomGlintRenderer.additionalReloadCleanup.add(GlowOutlineRenderer::release);

        // Once-per-frame reset. Arms the glint stencil masks (mount-armor glint via
        // forMountArmorStencilMask, Epic Knights decoration glint via the per-slot stencil pool), lowers
        // the renderingWorld flag, and clears the glow-outline per-frame capture queue.
        // RenderTickEvent.START fires once per rendered frame regardless of shader pack or
        // batched-render plumbing.
        MinecraftForge.EVENT_BUS.addListener((TickEvent.RenderTickEvent event) -> {
            if (event.phase == TickEvent.Phase.START) {
                CustomGlintRenderer.pendingFrameStencilClear = true;
                CustomGlintRenderer.resetStencilSlots();
                CustomGlintRenderer.setRenderingWorld(false);
                GlowOutlineRenderer.beginFrame();
                // Re-bake each chromatic slick drawn last frame at the current time. HAS to be here rather than
                // lazily at first use: the bake binds our own chromatic program, and Iris colour-masks a
                // non-ExtendedShader to nothing once the level pass owns the phase. This runs before that.
                ChromaticTextureBaker.bakeFrame();
            }
        });

        // Drain world-space item glow outlines after weather, where the live world projection / modelview
        // still match what the items were drawn with and the opaque scene depth is committed for the
        // occlusion test. Snapshot the world projection every frame here regardless: under a shader pack
        // the drain is DEFERRED to the RETURN of LevelRenderer.renderLevel (LevelRendererMixin), past which
        // the live projection has moved to GUI ortho; Iris composites its scene to the main target at that
        // RETURN, so a drain here would just be overwritten.
        MinecraftForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
                GlowOutlineRenderer.snapshotWorldProjection();
                if (!CustomGlintRenderer.isShaderPackActive()) {
                    GlowOutlineRenderer.drainWorld();
                }
            }
        });

        // GUI / inventory / HUD glow rings: drain ONCE per GUI context instead of once per item flush.
        // ItemRendererMixin captures each glowing icon's silhouette (in GUI screen space) at its render
        // RETURN; these hooks composite all of them together while the GUI ortho matrices are still live,
        // collapsing what used to be N mask<->main framebuffer ping-pongs (one per glowing icon) into one.
        // drainGui no-ops on an empty queue, so the four hooks never double-drain:
        //   - container foreground fires before the screen's tooltips/dragged item, keeping the ring under
        //     them; it drains the slot icons and clears the queue.
        //   - screen post catches non-container screens (e.g. the wand editor preview) and anything queued
        //     after the foreground (a dragged glowing item).
        //   - render-gui post covers the in-game HUD hotbar when no screen is open.
        //   - tooltip pre catches non-container screens (JEI recipe views) whose only other drain is the
        //     screen-post one, which runs AFTER the screen's own tooltip, so the ring would draw over it.
        //     Draining just before any tooltip keeps the ring under it; a no-op where the queue already drained.
        MinecraftForge.EVENT_BUS.addListener((ContainerScreenEvent.Render.Foreground event) ->
                GlowOutlineRenderer.drainGui());
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Render.Post event) ->
                GlowOutlineRenderer.drainGui());
        // LOWEST so this HUD drain runs AFTER EnhancedVisuals' own RenderGuiEvent.Post pass (NORMAL). EV
        // drives its shader visuals through a PostChain that reads + rewrites the main target; if our drain
        // binds an offscreen FBO earlier in this same event, EV's subsequent PostChain pass turns the whole
        // screen black. Running last means EV never sees an offscreen target bound during
        // its pass, and our ring still composites onto the finished HUD. (Container/screen drains fire on
        // ScreenEvent/ContainerScreenEvent; while a screen is open EV's RenderGuiEvent.Post doesn't run,
        // so they don't need this.)
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,
                (RenderGuiEvent.Post event) -> {
                    GlowOutlineRenderer.drainGui();
                    // Last thing before the frame blits: restore main-target alpha to opaque so EnhancedVisuals'
                    // alpha-reducing blood splatters don't present as black boxes on an alpha-composited window.
                    GlowOutlineRenderer.forceMainAlphaOpaque();
                });
        MinecraftForge.EVENT_BUS.addListener((RenderTooltipEvent.Pre event) ->
                GlowOutlineRenderer.drainGui());
    }

    private static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        // Anonymous class, not lambda: Forge's class transformer remaps method names (named→SRG)
        // at class-load time, but lambdas are generated by LambdaMetafactory at runtime and bypass
        // that transform, leaving the synthetic SAM named "onResourceManagerReload" while the
        // interface's abstract method at runtime is "m_6213_". That mismatch throws AbstractMethodError.
        event.registerReloadListener(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                CustomGlintRenderer.clearTextures();
            }
        });
    }
}
