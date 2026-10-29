package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Our own deferred vertex batch for the passes that draw outside vanilla's feature dispatch: the glow mask,
 * the post-shader glint/chromatic overlays and the translucent-shell glint. 26.2 removed the immediate
 * {@code MultiBufferSource.BufferSource} these used to accumulate into; this is the same idea on top of the
 * {@link StagedVertexBuffer} vanilla's feature renderers now use. One {@code Draw} per {@link RenderType},
 * vertices staged until {@link #flush}, which uploads once and draws each type through its
 * {@code PreparedRenderType} into one render pass on the target the caller names (an isolated mask or overlay
 * target, or the main target).
 *
 * <p>A staged buffer keeps only ONE live builder; asking for another draw's builder finishes the last. So
 * {@link #getBuffer} hands out a consumer that re-resolves its builder at each vertex start, and any number
 * of them can be held and written in any order.
 */
public final class GlintBatch implements AutoCloseable {
    private static final int INITIAL_CAPACITY = 262144;

    private final Supplier<String> label;
    private final StagedVertexBuffer staged;
    private final Map<RenderType, StagedVertexBuffer.Draw> draws = new LinkedHashMap<>();

    public GlintBatch(String label) {
        this.label = () -> label;
        this.staged = new StagedVertexBuffer(this.label, INITIAL_CAPACITY);
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

    /** Uploads everything staged and draws it into {@code color} (and {@code depth}, when non-null), {@code first}
     *  types before the rest (each in the order it was first requested). Clears the batch either way. */
    public void flush(GpuTextureView color, @Nullable GpuTextureView depth, Iterable<RenderType> first) {
        if (draws.isEmpty()) return;
        try {
            staged.upload();
            List<RenderType> order = new ArrayList<>();
            for (RenderType rt : first) if (draws.containsKey(rt)) order.add(rt);
            for (RenderType rt : draws.keySet()) if (!order.contains(rt)) order.add(rt);
            // Resolve and prepare every draw BEFORE the pass opens: no buffer or texture writes inside a pass.
            // That covers growing the shared sequential index buffer (vanilla only resizes it once per frame,
            // before our late drains), the transforms prepare() uploads, and textures it loads on first use.
            List<StagedVertexBuffer.ExecuteInfo> infos = new ArrayList<>(order.size());
            List<PreparedRenderType> prepared = new ArrayList<>(order.size());
            for (RenderType rt : order) {
                StagedVertexBuffer.ExecuteInfo info = staged.getExecuteInfo(draws.get(rt));
                if (info != null && info.customIndexBuffer() == null) {
                    RenderSystem.getSequentialBuffer(info.topology()).getBuffer(info.indexCount());
                }
                infos.add(info);
                prepared.add(info != null ? rt.prepare() : null);
            }
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    label, color, Optional.empty(), depth, OptionalDouble.empty())) {
                for (int i = 0; i < order.size(); i++) {
                    StagedVertexBuffer.ExecuteInfo info = infos.get(i);
                    if (info != null) prepared.get(i).drawFromBuffer(info, pass);
                }
            }
        } finally {
            staged.endDraw();
            draws.clear();
        }
    }

    public void flush(GpuTextureView color, @Nullable GpuTextureView depth) {
        flush(color, depth, List.of());
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
        @Override public VertexConsumer setUv3(float u, float v) { current.setUv3(u, v); return this; }
    }
}
