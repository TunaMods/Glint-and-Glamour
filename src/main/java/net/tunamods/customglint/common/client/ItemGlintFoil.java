package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.tunamods.customglint.common.CustomGlint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The flat-item glint, built layer-major per item feature group ({@code ItemRendererMixin}).
 *
 * <p>A feature group hands out one staged builder at a time, and switching to another RenderType can start a
 * new draw: it re-prepares the type (texture lookups plus a fresh transform uniform) and, since that uniform is
 * new, never matches an existing draw. Writing each quad into every layer in turn therefore cost one draw per
 * quad per layer, and a generated 2D sprite is hundreds of edge quads. So the foil pass only records each
 * glinted submit here, and {@link #flush} writes every recorded quad grouped by layer RenderType: one builder,
 * one draw per layer type for the whole group. Render thread only.
 */
public final class ItemGlintFoil {
    private ItemGlintFoil() {}

    private record Entry(PoseStack.Pose pose, List<BakedQuad> quads, int light, int overlay, int argb) {}

    private static final Map<RenderType, List<Entry>> PENDING = new LinkedHashMap<>();

    /** Records one foiled submit's quads under each of its glint layers. */
    public static void add(CustomGlint.Data glint, PoseStack.Pose pose, List<BakedQuad> quads, int light, int overlay) {
        CustomGlint.Layer[] layers = glint.layers();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            int[] colors = layers[layerIdx].colors();
            if (colors.length == 0) colors = new int[]{0xFFFFFFFF}; // unchosen layer → white placeholder
            // Chromatic composites every color in ONE draw (palette texture), so never loop per-color.
            if (layers[layerIdx].simultaneous() && !CustomGlint.isChromatic(layers[layerIdx])) {
                for (int i = 0; i < colors.length; i++) {
                    put(CustomGlintRenderer.forGlint(glint, layerIdx, true, i), pose, quads, light, overlay,
                            CustomGlintRenderer.packAdjustedColor(glint, layerIdx, colors[i]));
                }
            } else {
                put(CustomGlintRenderer.forGlint(glint, layerIdx, true, 0), pose, quads, light, overlay,
                        CustomGlintRenderer.computeAnimatedColor(glint, layerIdx));
            }
        }
    }

    private static void put(RenderType rt, PoseStack.Pose pose, List<BakedQuad> quads, int light, int overlay, int argb) {
        if (rt == null) return;
        PENDING.computeIfAbsent(rt, k -> new ArrayList<>()).add(new Entry(pose, quads, light, overlay, argb));
    }

    /**
     * Writes everything recorded since the last flush through {@code builders} (the group's
     * {@code getVertexBuilder}), one RenderType at a time. The layer colour is forced onto the vertices because
     * the glint shader tints the grayscale design by the per-vertex Color. Alpha is honoured verbatim: every
     * colour source ORs in 0xFF, so a 0 alpha only ever comes from the editor's A slider.
     */
    public static void flush(Function<RenderType, VertexConsumer> builders) {
        if (PENDING.isEmpty()) return;
        try {
            QuadInstance qi = new QuadInstance();
            for (Map.Entry<RenderType, List<Entry>> e : PENDING.entrySet()) {
                VertexConsumer builder = builders.apply(e.getKey());
                for (Entry entry : e.getValue()) {
                    int argb = entry.argb();
                    VertexConsumer colored = new CustomGlintRenderer.FullColorOverrideConsumer(builder,
                            (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
                    qi.setLightCoords(entry.light());
                    qi.setOverlayCoords(entry.overlay());
                    for (BakedQuad quad : entry.quads()) colored.putBakedQuad(entry.pose(), quad, qi);
                }
            }
        } finally {
            PENDING.clear();
        }
    }

    /** Drops anything recorded but never flushed (a group that threw mid-build). */
    public static void clear() {
        PENDING.clear();
    }
}
