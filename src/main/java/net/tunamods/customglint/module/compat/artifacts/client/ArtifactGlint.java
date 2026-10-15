package net.tunamods.customglint.module.compat.artifacts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;

/**
 * Glue for the Artifacts compat. Artifacts worn in Curios slots (belts, necklaces, gloves, boots, …) draw
 * through their own {@code ArtifactRenderer}, never the vanilla {@code EquipmentLayerRenderer}, so the core
 * {@code EquipmentLayerRendererMixin} never sees them. Every worn artifact funnels through the shared
 * primitive {@code ArtifactRenderer.renderModelWithFoil(model, state, pose, collector, texture, light,
 * hasFoil)}, which submits the base model at {@code order(0)} with {@code model.renderType(texture)}
 * (Artifacts' models render at {@code entityTranslucent}: EQUAL depth, no polygon offset) and vanilla's
 * foil at {@code order(1)}.
 *
 * <p>{@code ArtifactRenderMixin} arms this glue with the wearer's {@link ItemStack} for the span of one
 * {@code ArtifactRenderer.render}, then calls {@link #fan} at the first {@code renderModelWithFoil} of that
 * span. {@link #fan} submits our glint render types onto the SAME model/state/pose (so it inherits the
 * artifact's animation and transform-copied pose) and queues the glow silhouette. The glint uses
 * {@link CustomGlintRenderer#forEntityBodyGlint} (EQUAL + {@code NO_LAYERING}) to match the
 * {@code entityTranslucent} depth the base draws with; {@code forArmorGlint}'s {@code VIEW_OFFSET_Z} would
 * test against the wrong offset and vanish. Glow rides {@link EntityGlintRender#queueArmorOutline}, keyed on
 * the wearer render state so it folds into that entity's body ring. All references are vanilla types, so
 * there is no compile-time dependency on Artifacts.
 */
public final class ArtifactGlint {
    private ArtifactGlint() {}

    private static final ThreadLocal<ItemStack> CURRENT_STACK = new ThreadLocal<>();
    // renderModelWithFoil fires up to twice per artifact render (base texture + full-bright glow overlay
    // texture on the glowing variants). Fan once per armed span, on the first (base-texture) draw.
    private static final ThreadLocal<Boolean> FANNED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Arm for one {@code ArtifactRenderer.render}. Every {@code renderModelWithFoil} call until
     *  {@link #disarm} belongs to this artifact, so {@link #fan} may run without catching unrelated draws. */
    public static void arm(ItemStack stack) {
        CURRENT_STACK.set(stack);
        FANNED.set(Boolean.FALSE);
    }

    public static void disarm() {
        CURRENT_STACK.set(null);
        FANNED.set(Boolean.FALSE);
    }

    public static boolean isArmed() {
        return CURRENT_STACK.get() != null;
    }

    /** The armed artifact stack, or {@code null} outside an armed span. */
    public static ItemStack current() {
        return CURRENT_STACK.get();
    }

    /**
     * Fan the armed artifact's glint onto {@code model} and queue its glow silhouette. Mirrors
     * {@code EquipmentLayerRendererMixin} (worn-model idiom) but on the entity-body depth artifacts use.
     * Runs once per armed span (the base-texture draw). No-op when the stack carries neither glint nor glow.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void fan(Model model, Object state, PoseStack pose, SubmitNodeCollector collector,
            Identifier texture, int light) {
        if (Boolean.TRUE.equals(FANNED.get())) return;
        ItemStack stack = CURRENT_STACK.get();
        if (stack == null || stack.isEmpty() || model == null || state == null) return;
        FANNED.set(Boolean.TRUE);

        // One component fetch covers the glint data and both glow signals below.
        CustomGlint.GlintState glintState = CustomGlint.readStateForRender(stack);
        CustomGlint.Data glint = glintState.data();

        if (glint != null) {
            CustomGlint.Layer[] gl = glint.layers();
            for (int layerIdx = 0; layerIdx < gl.length; layerIdx++) {
                int[] colors = gl[layerIdx].colors();
                if (colors.length == 0) colors = new int[]{0xFFFFFFFF}; // unchosen layer → white placeholder
                boolean chroma = CustomGlint.isChromatic(gl[layerIdx]);
                if (CustomGlintRenderer.isShaderPackActive()) {
                    // Under a pack nothing draws in-phase correctly (Iris swaps our program): chromatic goes
                    // flat white, normal glint goes solid. Queue both for the post-Iris overlay drain, cut
                    // out against the artifact texture. Mirrors EquipmentLayerRendererMixin's shader branch.
                    if (chroma) {
                        RenderType rt = texture == null ? null
                                : CustomGlintRenderer.forEntityGlintOverlay(glint, layerIdx, texture);
                        if (rt != null) EntityGlintRender.queueChromaticModel(model, state, pose.last(), rt, light, false);
                    } else if (gl[layerIdx].simultaneous()) {
                        for (int i = 0; i < colors.length; i++) {
                            RenderType rt = texture == null ? null
                                    : CustomGlintRenderer.forEntityGlintOverlayNormal(glint, layerIdx, i, texture);
                            if (rt != null) EntityGlintRender.queueGlintOverlayModel(model, state, pose.last(), rt, light,
                                    CustomGlintRenderer.packAdjustedColor(glint, layerIdx, colors[i]), false);
                        }
                    } else {
                        int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                        RenderType rt = texture == null ? null
                                : CustomGlintRenderer.forEntityGlintOverlayNormal(glint, layerIdx, 0, texture);
                        if (rt != null) EntityGlintRender.queueGlintOverlayModel(model, state, pose.last(), rt, light, color, false);
                    }
                } else if (gl[layerIdx].simultaneous() && !chroma) {
                    for (int i = 0; i < colors.length; i++) {
                        RenderType rt = CustomGlintRenderer.forEntityBodyGlint(glint, layerIdx, i);
                        if (rt != null) submit(collector, model, state, pose, rt, light,
                                CustomGlintRenderer.packAdjustedColor(glint, layerIdx, colors[i]));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    RenderType rt = CustomGlintRenderer.forEntityBodyGlint(glint, layerIdx, 0);
                    if (rt != null) submit(collector, model, state, pose, rt, light, color);
                }
            }
        }

        // Glow outline, independent of the glint (a Glow-Trimmed artifact with no glint still outlines).
        // The artifact texture drives the silhouette alpha-discard; the model + state re-pose via setupAnim
        // at drain (matching the artifact's own state-driven draw), so multi-wearer scenes don't share a
        // stale pose. Keyed on the wearer render state (CAT_ARMOR) so it folds into that entity's body ring.
        boolean glowing = glintState.glowing();
        int[] glowColors = glintState.glowColors();
        if ((glowing || glowColors.length > 0) && texture != null) {
            EntityGlintRender.queueArmorOutline(model, state, pose.last(), texture, light,
                    glint, glowing, glowColors, glintState.glowSpeed(), glintState.glowInterp(), false);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void submit(SubmitNodeCollector collector, Model model, Object state, PoseStack pose,
            RenderType rt, int light, int argb) {
        int color = (argb >>> 24) == 0 ? (argb | 0xFF000000) : argb;
        collector.submitModel(model, state, pose, rt, light, OverlayTexture.NO_OVERLAY, color, null, 0, null);
    }
}
