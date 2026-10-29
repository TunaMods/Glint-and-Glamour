package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.BatchableSubmit;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RegisterFeatureRenderersEvent;
import net.neoforged.neoforge.client.submit.RenderPhaseKeys;
import net.tunamods.customglint.common.CustomGlint;

import java.util.List;

/**
 * Every in-phase glint draw: entity bodies and layers, worn armor, special items, flat items and block-model
 * layers, as nodes of our own feature type.
 *
 * <p>Glint is an additive overlay that EQUAL/LEQUAL-tests against the surface under it, so it has to draw
 * after that surface. 26.3 broke both places vanilla offered for that: blended RenderTypes go to the
 * order-independent-transparency phase under "improved transparency", whose draw path throws for a type with
 * no OIT pipelines, and the solid phase orders batches by a HashMap, so a glint batch can land before its
 * surface. A solid phase does walk feature TYPES in id order, though, and {@link #TYPE} is created inside
 * {@link RegisterFeatureRenderersEvent}, after every vanilla type, so our groups always draw after vanilla's
 * solid groups. Nodes batch by RenderType: one draw per glint layer per group.
 *
 * <p>Translucent flat-item quads draw in the translucent item phase instead (off OIT only), sorted just in
 * front of their item; under OIT they fall back to the solid phase, where the glint can't reach a surface that
 * hasn't drawn yet.
 */
public final class GlintFeatureRenderer extends RenderTypeFeatureRenderer<GlintFeatureRenderer.Submit> {

    private static FeatureRendererType<Submit> TYPE;

    /** One renderer per feature dispatcher (the level/hand one, the GUI item atlas, picture-in-picture), all
     *  sharing one type. */
    public static void onRegister(RegisterFeatureRenderersEvent event) {
        if (TYPE == null) TYPE = FeatureRendererType.create("customglint:glint");
        event.register(TYPE, new GlintFeatureRenderer());
    }

    /** What a node writes into its RenderType's buffer, at the node's pose. */
    public sealed interface Geometry permits ModelGeometry, QuadGeometry {}

    /** A posed model re-walked through the glint RenderType, colour on the vertices. */
    @SuppressWarnings("rawtypes")
    public record ModelGeometry(Model model, Object state, int light, int overlay, int argb) implements Geometry {}

    /** Baked quads with {@code argb} forced onto every vertex (alpha honoured verbatim: every colour source ORs
     *  in 0xFF, so a 0 alpha only comes from the editor's A slider). */
    public record QuadGeometry(List<BakedQuad> quads, int light, int overlay, int argb) implements Geometry {}

    public record Submit(RenderType renderType, PoseStack.Pose pose, Geometry geometry, float distanceToCameraSq)
            implements BatchableSubmit, TranslucentSubmit {
        @Override public Object batchKey() { return renderType; }
        @Override public FeatureRendererType<Submit> featureType() { return TYPE; }
    }

    /** A model glint node, drawn after the solid surfaces. */
    @SuppressWarnings("rawtypes")
    public static void submitModel(OrderedSubmitNodeCollector collector, Model model, Object state, PoseStack pose,
                                   RenderType rt, int light, int overlay, int argb) {
        if (TYPE == null || rt == null) return;
        collector.submitSpecial(RenderPhaseKeys.SOLID,
                new Submit(rt, pose.last().copy(), new ModelGeometry(model, state, light, overlay, argb), 0.0f));
    }

    /** A quad glint node. {@code translucent} quads go to the translucent item phase off OIT, sorted just in
     *  front of the item they cover, so they draw after it. */
    public static void submitQuads(OrderedSubmitNodeCollector collector, PoseStack.Pose pose, List<BakedQuad> quads,
                                   RenderType rt, int light, int overlay, int argb, boolean translucent) {
        if (TYPE == null || rt == null || quads.isEmpty()) return;
        PoseStack.Pose copy = pose.copy();
        QuadGeometry geometry = new QuadGeometry(quads, light, overlay, argb);
        if (translucent && !Minecraft.getInstance().gameRenderer.useImprovedTransparency()) {
            float itemDist = TranslucentSubmit.computeDistanceToCameraSq(copy.pose());
            collector.submitSpecial(RenderPhaseKeys.TRANSLUCENT_BLOCKS_AND_ITEMS,
                    new Submit(rt, copy, geometry, Math.nextDown(itemDist)));
        } else {
            collector.submitSpecial(RenderPhaseKeys.SOLID, new Submit(rt, copy, geometry, 0.0f));
        }
    }

    /** Every glint layer of a flat item over its quads, one node per layer RenderType (and per colour of a
     *  simultaneous layer). The glint shader tints the grayscale design by the per-vertex Color. */
    public static void submitItemGlint(OrderedSubmitNodeCollector collector, CustomGlint.Data glint, PoseStack.Pose pose,
                                       List<BakedQuad> quads, int light, int overlay, boolean translucent) {
        CustomGlint.Layer[] layers = glint.layers();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            int[] colors = layers[layerIdx].colors();
            if (colors.length == 0) colors = new int[]{0xFFFFFFFF}; // unchosen layer → white placeholder
            // Chromatic composites every color in ONE draw (palette texture), so never loop per-color.
            if (layers[layerIdx].simultaneous() && !CustomGlint.isChromatic(layers[layerIdx])) {
                for (int i = 0; i < colors.length; i++) {
                    submitQuads(collector, pose, quads, CustomGlintRenderer.forGlint(glint, layerIdx, true, i), light,
                            overlay, CustomGlintRenderer.packAdjustedColor(glint, layerIdx, colors[i]), translucent);
                }
            } else {
                submitQuads(collector, pose, quads, CustomGlintRenderer.forGlint(glint, layerIdx, true, 0), light,
                        overlay, CustomGlintRenderer.computeAnimatedColor(glint, layerIdx), translucent);
            }
        }
    }

    private final PoseStack poseStack = new PoseStack();
    private final QuadInstance quadInstance = new QuadInstance();

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void buildGroup(FeatureFrameContext context, List<Submit> submits) {
        for (Submit submit : submits) {
            VertexConsumer buffer = getVertexBuilder(submit.renderType());
            if (submit.geometry() instanceof ModelGeometry m) {
                poseStack.last().set(submit.pose());
                Model model = m.model();
                if (m.state() != null) model.setupAnim(m.state());
                // Same visibility hook ModelFeatureRenderer applies, so our re-walk matches the drawn surface.
                var previous = ClientHooks.updateModelVisibility(model, m.state());
                model.renderToBuffer(poseStack, buffer, m.light(), m.overlay(), m.argb());
                ClientHooks.resetVisibility(previous);
            } else if (submit.geometry() instanceof QuadGeometry q) {
                int argb = q.argb();
                VertexConsumer colored = new CustomGlintRenderer.FullColorOverrideConsumer(buffer,
                        (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
                quadInstance.setLightCoords(q.light());
                quadInstance.setOverlayCoords(q.overlay());
                for (BakedQuad quad : q.quads()) colored.putBakedQuad(submit.pose(), quad, quadInstance);
            }
        }
    }
}
