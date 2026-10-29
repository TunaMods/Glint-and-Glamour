package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4fc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Our own deferred vertex batch for the passes that draw outside vanilla's feature dispatch: the glow mask,
 * the post-shader glint/chromatic overlays and the translucent-shell glint. 26.2 removed the immediate
 * {@code MultiBufferSource.BufferSource} these used to accumulate into; this is the same idea on top of the
 * {@link StagedVertexBuffer} vanilla's feature renderers now use. One {@code Draw} per {@link RenderType},
 * vertices staged until {@link #flush}, which uploads once and draws each type through its
 * {@code PreparedRenderType}, so {@code RenderSystem.outputColor/DepthTextureOverride} still redirects
 * the draw into an isolated target.
 *
 * <p>A staged buffer keeps only ONE live builder; asking for another draw's builder finishes the last. So
 * {@link #getBuffer} hands out a consumer that re-resolves its builder at each vertex start, and any number
 * of them can be held and written in any order.
 */
public final class GlintBatch implements AutoCloseable {
    private static final int INITIAL_CAPACITY = 262144;

    private final StagedVertexBuffer staged;
    private final Map<RenderType, StagedVertexBuffer.Draw> draws = new LinkedHashMap<>();

    public GlintBatch(String label) {
        this.staged = new StagedVertexBuffer(() -> label, INITIAL_CAPACITY);
    }

    /** A consumer that appends to {@code rt}'s draw in this batch. Safe to hold alongside others. */
    public VertexConsumer getBuffer(RenderType rt) {
        StagedVertexBuffer.Draw draw = draws.computeIfAbsent(rt, k -> staged.appendDraw(k.format(), k.primitiveTopology(),
                k.sortOnUpload() ? RenderSystem.getProjectionType().vertexSorting() : null));
        return new DrawConsumer(staged, draw);
    }

    public boolean isEmpty() {
        return draws.isEmpty();
    }

    /** Uploads everything staged and draws it, {@code first} types before the rest (each in the order it was
     *  first requested). Clears the batch either way. */
    public void flush(Iterable<RenderType> first) {
        if (draws.isEmpty()) return;
        try {
            staged.upload();
            for (RenderType rt : first) drawOne(rt, draws.remove(rt));
            for (Map.Entry<RenderType, StagedVertexBuffer.Draw> e : draws.entrySet()) drawOne(e.getKey(), e.getValue());
        } finally {
            staged.endDraw();
            draws.clear();
        }
    }

    public void flush() {
        flush(List.of());
    }

    private void drawOne(RenderType rt, StagedVertexBuffer.Draw draw) {
        if (draw == null) return;
        StagedVertexBuffer.ExecuteInfo info = staged.getExecuteInfo(draw);
        if (info != null) rt.prepare().drawFromBuffer(info);
    }

    /** Drops anything staged but never flushed (a frame that threw before its drain). */
    public void discard() {
        if (draws.isEmpty()) return;
        staged.endDraw();
        draws.clear();
    }

    /** Once per frame: recycles the GPU buffers this batch used once their frame has finished. */
    public void endFrame() {
        discard();
        staged.endFrame();
    }

    @Override
    public void close() {
        draws.clear();
        staged.close();
    }

    /** Appends to one draw, re-acquiring the staged builder at each vertex start (the builder is shared by
     *  every draw in the batch and is swapped whenever another draw writes). Attribute setters go to the
     *  builder the current vertex started in. */
    private static final class DrawConsumer implements VertexConsumer {
        private final StagedVertexBuffer staged;
        private final StagedVertexBuffer.Draw draw;
        private VertexConsumer current;

        DrawConsumer(StagedVertexBuffer staged, StagedVertexBuffer.Draw draw) {
            this.staged = staged;
            this.draw = draw;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            current = staged.getVertexBuilder(draw);
            current.addVertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) {
            current = staged.getVertexBuilder(draw);
            current.addVertex(m, x, y, z);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { current.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setColor(int color) { current.setColor(color); return this; }
        @Override public VertexConsumer setUv(float u, float v) { current.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { current.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { current.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { current.setNormal(x, y, z); return this; }
        @Override public VertexConsumer setLineWidth(float width) { current.setLineWidth(width); return this; }
    }
}
