package net.tunamods.customglint.common.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CgGlintHolder;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlintCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The vanilla side of the per-item glint in the 26.3 deferred item pipeline. Our glint itself is submitted as
 * {@code GlintFeatureRenderer} nodes from {@code SubmitNodeCollectionMixin.submitItem}.
 *
 * <p>Item quads are queued as {@link ItemFeatureRenderer.Submit} nodes and built later, in
 * {@code ItemFeatureRenderer.buildGroup}, one {@code prepareSubmit(submit)} each. The original stack is gone by
 * then, so the glint rides the node (see {@link CgGlintHolder} / {@link GlintCarrier}). 26.3 bakes vanilla's
 * enchant glint into the quads' own RenderType ({@code itemGlintRenderType}), picked from the submit's foil
 * type in {@code prepareMainSubmit}; for a glinted submit that read becomes NONE, so vanilla builds the plain
 * item under our glint. {@code prepareSubmit} HEAD queues the glow outline and the shader-pack overlay.
 */
@Mixin(ItemFeatureRenderer.class)
public class ItemRendererMixin {

    /** Our glint EATS vanilla's foil, never stacks on it, on and off a shader pack. */
    @ModifyExpressionValue(method = "prepareMainSubmit",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;foilType()Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;"),
            require = 0)
    private ItemStackRenderState.FoilType cg_plainBaseUnderOurGlint(ItemStackRenderState.FoilType original,
            @Local(argsOnly = true) ItemFeatureRenderer.Submit submit) {
        return ((CgGlintHolder) (Object) submit).customglint$getGlint() != null
                ? ItemStackRenderState.FoilType.NONE : original;
    }

    @Inject(method = "prepareSubmit", at = @At("HEAD"), require = 0)
    private void cg_prepareSubmitHead(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
        CgGlintHolder holder = (CgGlintHolder) (Object) submit;
        // The vanilla outline is its own Submit (outline phase, outlineColor != 0); only the main one counts.
        if (submit.outlineColor() != 0) return;
        // Queue the item's glow outline for the after-main-pass drain (the same isolated silhouette-mask +
        // composite pass that draws entity rings, see EntityGlintRender). The item's base quads are committed
        // to the main target before that drain, so the scene depth the mask shader samples for occlusion is
        // there.
        boolean glowing = holder.customglint$isGlowing();
        int[] glowColors = holder.customglint$getGlowColors();
        // GUI icons get their glow from the flat halo blit (GuiRendererMixin), not this 3D silhouette
        // composite, which drains after the main pass (a world phase) and would otherwise just queue
        // per-glowing-icon jobs every atlas bake that never draw correctly. Skip them.
        boolean isGui = submit.displayContext() == ItemDisplayContext.GUI;
        if (!isGui && (glowing || (glowColors != null && glowColors.length > 0))) {
            // First-person hand items go to a separate queue drained only at the hand point (view-space
            // pose; the world drain would project it against the world matrix and the ring would float
            // off the item, most visible under Iris, which renders the hand inside the level framegraph).
            // OR in the hand-render window flag: a special-model quad item (a Sophisticated Backpack) submits
            // its base model with ItemDisplayContext.NONE, so the node's own context can't report first-person.
            boolean heldFp = (submit.displayContext() != null && submit.displayContext().firstPerson())
                    || EntityGlintRender.inFirstPersonHand();
            EntityGlintRender.queueItemOutline(submit.quads(), submit.pose(), submit.lightCoords(),
                    holder.customglint$getGlint(), glowing, glowColors,
                    holder.customglint$getGlowSpeed(), holder.customglint$getGlowInterp(), heldFp);
        }
        // Under an active shader pack NO glint layer can draw in-phase correctly: Iris replaces our program,
        // so chromatic goes flat white and normal glint goes SOLID (every gbuffer entity program it can pick
        // is opaque, it replaces the item surface instead of adding onto it). Queue EVERY layer for the
        // post-Iris overlay drain (world drain for 3rd-person/dropped, hand drain for first-person); the
        // in-phase glint above is skipped under a pack. Off the pack it draws normally.
        CustomGlint.Data glint = holder.customglint$getGlint();
        if (!isGui && glint != null && CustomGlintRenderer.isShaderPackActive()) {
            // Same NONE-context fallback as the glow queue above (SB backpack): route by the hand-window flag.
            boolean heldFp = (submit.displayContext() != null && submit.displayContext().firstPerson())
                    || EntityGlintRender.inFirstPersonHand();
            CustomGlint.Layer[] gl = glint.layers();
            for (int layerIdx = 0; layerIdx < gl.length; layerIdx++) {
                if (CustomGlint.isChromatic(gl[layerIdx])) {
                    EntityGlintRender.queueChromaticItem(submit.quads(), submit.pose(),
                            glint, layerIdx, submit.lightCoords(), heldFp);
                    continue;
                }
                int[] colors = gl[layerIdx].colors();
                if (colors.length == 0) colors = new int[]{0xFFFFFFFF}; // unchosen layer → white placeholder
                if (gl[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++)
                        EntityGlintRender.queueGlintOverlayItem(submit.quads(), submit.pose(),
                                glint, layerIdx, i, CustomGlintRenderer.packAdjustedColor(glint, layerIdx, colors[i]),
                                submit.lightCoords(), heldFp);
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    EntityGlintRender.queueGlintOverlayItem(submit.quads(), submit.pose(),
                            glint, layerIdx, 0, color, submit.lightCoords(), heldFp);
                }
            }
        }
    }
}
