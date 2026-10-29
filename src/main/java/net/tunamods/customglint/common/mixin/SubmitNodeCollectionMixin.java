package net.tunamods.customglint.common.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlintCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The universal model sink. Every model submit lands on {@link SubmitNodeCollection#submitModel}: the entity
 * body and every {@code RenderLayer} surface (sheep wool, slime outer cube, saddle, stray clothing, …), and
 * every special-renderer item part. {@code SubmitNodeStorage.submitModel} only delegates to {@code order(0)},
 * renderers that submit at a later order ({@code collector.order(n).submitModel}: the trident, the shield
 * foil, banner patterns) come here directly, and 26.2's {@code submitModelPart} is a default that wraps the
 * part in a {@code Model.Simple} and calls {@code submitModel} too. So this one hook sees each model once.
 *
 * <p><b>Special-renderer items</b> (shield, trident, anything drawn through a {@code SpecialModelRenderer}
 * rather than as baked quads), recognised by the item-glint context {@code ItemStackRenderStateMixin}
 * publishes for the span of {@code ItemStackRenderState.submit} ({@link GlintCarrier#SUBMIT_GLINT} /
 * {@link GlintCarrier#SUBMIT_GLOWING} / {@link GlintCarrier#SUBMIT_GLOW_COLORS} are non-null only inside an
 * item submit, and a special renderer's model submits are the only model nodes produced there). They get:
 * <ul>
 * <li>their vanilla foil node consumed (a separate model node in a vanilla glint RenderType) when our glint
 *     is present, so the two never stack;</li>
 * <li>the glow outline queued (white.png silhouette, the 1.21.1 BEWLR full-shape approach) for the shared
 *     AfterWeather / hand-render drain;</li>
 * <li>our animated GLINT. The vanilla foil is gated on enchantment ({@code SpecialModelWrapper.update} reads
 *     {@code ItemStack.hasFoil()}), so a glinted but unenchanted item never reaches it; we submit our own glint
 *     model nodes instead, once per item submit ({@link #cg_firstGlintForToken()}) so a shield's base +
 *     patterns don't stack several glint passes.</li>
 * </ul>
 *
 * <p><b>Entity layers</b>: matching glint nodes for each entity-surface layer model, the 26.x replacement for
 * the 1.21.1 {@code GlintWrappingBufferSource} fan-out. The BASE body is glinted directly by
 * {@code LivingEntityRendererMixin}, so it's skipped via the current-body-model guard. Per-layer glow OUTLINE
 * rides the in-phase tee in {@code ModelFeatureRendererMixin}, not this hook.
 *
 * <p>Re-entrancy guarded so the glint nodes we submit (which call {@code submitModel} again) don't loop.
 */
@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {

    /** Guards against re-entering this hook while submitting our own glint nodes (which call
     *  {@code submitModel} again). Render thread only. */
    @Unique
    private static boolean cg_addingGlint = false;

    /** Per-item-submit token of the last submit we added glint for. A special renderer can submit several
     *  model nodes per item (shield: base + patterns + foil); glinting only the first keeps one glint pass
     *  over the item shape instead of stacking one per node. */
    @Unique
    private static final ThreadLocal<Object> cg_glintedToken = new ThreadLocal<>();

    @Inject(method = "submitModel", at = @At("HEAD"), cancellable = true, require = 0)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void cg_submitModel(Model model, Object state, PoseStack poseStack, RenderType renderType,
            int lightCoords, int overlayCoords, int tintedColor, TextureAtlasSprite sprite, int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, CallbackInfo ci) {
        if (cg_addingGlint) return; // our own glint nodes, don't recurse
        CustomGlint.Data itemGlint = GlintCarrier.SUBMIT_GLINT.get();
        if (itemGlint != null || cg_glowingItem()) {
            cg_specialItem(model, state, poseStack, renderType, lightCoords, itemGlint, ci);
            return;
        }
        if (!(state instanceof EntityRenderState ers) || !(model instanceof EntityModel)) return;
        if (model == EntityGlintRender.currentBodyModel()) return; // body is glinted by LivingEntityRendererMixin
        if (!EntityGlintRender.isEntitySurface(renderType)) return;
        EntityGlintRender.Resolution res = ers.getRenderData(EntityGlintRender.RENDER_DATA);
        if (res == null || res.data == null) return;
        // Off a shader pack, stash the layer glint for the stable-depth draw at AfterWeather (drawn on top,
        // occluded against the opaque-depth snapshot) when it would otherwise fight a re-sorted / mismatched
        // depth in-phase:
        //   1. a TRANSLUCENT layer (slime outer shell) lands in the distance-sorted translucent phase with
        //      our glint (also mark the entity so its inner body glint is skipped, the shell is the surface),
        //   2. a CHROMATIC layer draws through the EQUAL-depth chromatic RT, which flickers on the ~1 ULP depth
        //      mismatch between our program and the layer surface (both sheep wool and slime shell).
        boolean translucent = cg_isTranslucent(renderType);
        if (translucent) EntityGlintRender.markTranslucentShell(state);
        if ((translucent || cg_hasChromatic(res.data)) && !CustomGlintRenderer.isShaderPackActive()) {
            EntityGlintRender.queueTranslucentLayerGlint((EntityModel<?>) model, state, poseStack.last(),
                    res.data, lightCoords);
            return;
        }
        cg_addingGlint = true;
        try {
            // null cutout texture → the chromatic overlay draws the full layer mesh, matching how the glow
            // path silhouettes these RenderLayer surfaces (white.png full shape, their hull IS the shape).
            EntityGlintRender.submitEntityGlint((OrderedSubmitNodeCollector) (Object) this,
                    (EntityModel<?>) model, state, poseStack, lightCoords, res.data, null, true, translucent);
        } finally {
            cg_addingGlint = false;
        }
    }

    /** One model node of a special-renderer item, inside its {@code ItemStackRenderState.submit} span. */
    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void cg_specialItem(Model model, Object state, PoseStack poseStack, RenderType renderType,
            int lightCoords, CustomGlint.Data glint, CallbackInfo ci) {
        // Consume vanilla foil: a special renderer submits its enchantment glint as a SEPARATE model node in
        // a vanilla glint RenderType (shield and trident both, in 26.2). When our glint is present we draw our
        // own, so drop vanilla's node here to avoid a doubled shimmer. (The base/pattern nodes use cutout
        // RenderTypes, not glint, so only the foil node is cancelled.)
        if (cg_isVanillaGlint(renderType)) {
            if (glint != null) ci.cancel();
            return; // the foil re-draws the base shape; it is never outlined either way
        }
        if (cg_glowingItem()) {
            EntityGlintRender.queueSpecialModelOutline(model, state, poseStack.last(), lightCoords,
                    glint, cg_glowingFlag(), GlintCarrier.SUBMIT_GLOW_COLORS.get(),
                    cg_glowSpeed(), cg_glowInterp());
        }
        if (glint != null && cg_firstGlintForToken()) {
            cg_addingGlint = true;
            try {
                EntityGlintRender.submitSpecialModelGlint((OrderedSubmitNodeCollector) (Object) this, model, state,
                        poseStack, lightCoords, glint);
            } finally {
                cg_addingGlint = false;
            }
        }
    }

    /**
     * Mute vanilla's glowing outline on a model that carries OUR glow. 26.2 builds that outline as a second
     * model node in the outline phase whenever {@code outlineColor != 0} (the team colour stamped on every
     * submit of an {@code isCurrentlyGlowing} entity, or the outline of a glowing item entity). We draw our own
     * ring, so zeroing the colour here drops vanilla's node and leaves one outline instead of two stacked ones.
     * Entities: gated on the render state carrying our {@code GLOW_OUTLINE} (set in
     * {@link LivingEntityRendererMixin}), so non-glinted glowing entities keep their vanilla outline; body,
     * layers and armor all submit with that state. Special items: gated on the item-glow context.
     */
    @ModifyVariable(method = "submitModel", at = @At("HEAD"), argsOnly = true, ordinal = 3, require = 0)
    private int cg_muteVanillaModelGlow(int outlineColor, @Local(argsOnly = true) Object state) {
        if (outlineColor == 0) return 0;
        if (state instanceof EntityRenderState es && es.getRenderData(EntityGlintRender.GLOW_OUTLINE) != null) return 0;
        if (cg_glowingItem()) return 0;
        return outlineColor;
    }

    /** The quad-item twin of {@link #cg_muteVanillaModelGlow}: dropped and 3rd-person held items whose
     *  {@code submitItem} would add a vanilla outline node, when the item carries our glow. */
    @ModifyVariable(method = "submitItem", at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 0)
    private int cg_muteVanillaItemGlow(int outlineColor) {
        return outlineColor != 0 && cg_glowingItem() ? 0 : outlineColor;
    }

    /** True for a translucent entity-surface RenderType (slime outer shell etc.); its depth is re-sorted
     *  every frame, so an in-phase glint tied to it flickers. Identity-independent name check. */
    @Unique
    private static boolean cg_isTranslucent(RenderType renderType) {
        return renderType.toString().contains("translucent");
    }

    /** True if any layer of {@code data} is chromatic (procedural). Chromatic layers draw through the
     *  EQUAL-depth chromatic RT, which flickers on a layer surface, so they take the stable-depth stash path. */
    @Unique
    private static boolean cg_hasChromatic(CustomGlint.Data data) {
        for (CustomGlint.Layer l : data.layers()) if (CustomGlint.isChromatic(l)) return true;
        return false;
    }

    /** True for vanilla's enchantment-glint RenderTypes (the foil sheets). Identity compare against the
     *  three memoized singletons, our own glint RTs are never these. */
    @Unique
    private static boolean cg_isVanillaGlint(RenderType rt) {
        return rt == RenderTypes.entityGlint() || rt == RenderTypes.glint() || rt == RenderTypes.glintTranslucent();
    }

    @Unique
    private static boolean cg_firstGlintForToken() {
        Object tok = GlintCarrier.SUBMIT_TOKEN.get();
        if (tok == null) return true;            // no token (shouldn't happen inside an item submit)
        if (tok == cg_glintedToken.get()) return false;
        cg_glintedToken.set(tok);
        return true;
    }

    @Unique
    private static boolean cg_glowingFlag() {
        Boolean g = GlintCarrier.SUBMIT_GLOWING.get();
        return g != null && g;
    }

    /** True only inside an {@code ItemStackRenderState.submit} for a glowing item (glow flag or colours). */
    @Unique
    private static boolean cg_glowingItem() {
        int[] gc = GlintCarrier.SUBMIT_GLOW_COLORS.get();
        return cg_glowingFlag() || (gc != null && gc.length > 0);
    }

    /** The submitting item's stored glow-cycle speed / interpolation (default 1.0 / true when unset). */
    @Unique
    private static float cg_glowSpeed() {
        Float s = GlintCarrier.SUBMIT_GLOW_SPEED.get();
        return s != null ? s : 1.0f;
    }

    @Unique
    private static boolean cg_glowInterp() {
        Boolean i = GlintCarrier.SUBMIT_GLOW_INTERP.get();
        return i == null || i;
    }

    /**
     * GLINT + GLOW for block-model entity layers, mooshroom mushrooms, snow-golem pumpkin, and any modded
     * layer that submits a {@code BlockModelRenderState}. These funnel through {@code submitBlockModel}, the
     * universal block sink, which (unlike {@code submitModel}) carries no entity state, so
     * {@link EntityGlintRender#submitBlockLayerGlintGlow} reads the owning entity from the thread-local
     * published by {@code LivingEntityRendererMixin} for the submit span. Skipped inside an item submit (held
     * block items resolve on the item path). The glint is emitted via {@code submitCustomGeometry} (the block
     * sink can't carry our per-vertex glint colour); the glow rides the shared item-quad outline path.
     */
    @Inject(method = "submitBlockModel", at = @At("HEAD"), require = 0)
    private void cg_blockLayerGlintGlow(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> modelParts,
            int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor, CallbackInfo ci) {
        if (GlintCarrier.SUBMIT_GLINT.get() != null) return; // item submit context, handled on the item path
        if (EntityGlintRender.currentEntity() == null) return; // non-entity block submit (the common case), nothing to do
        EntityGlintRender.submitBlockLayerGlintGlow((OrderedSubmitNodeCollector) (Object) this,
                poseStack, modelParts, lightCoords, overlayCoords);
    }
}
