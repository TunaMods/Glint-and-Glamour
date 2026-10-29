package net.tunamods.customglint.common.client;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * One live glint-overlay quad over a glinted item's cached GUI icon, the GUI analog of one glint layer
 * (one colour) in the world {@code applyGlint}. {@code GuiRendererMixin} emits one of these per layer/colour
 * as a GLYPH on the item's node, so it draws on top of the opaque cached icon (glyphs draw after the node's
 * sorted elements). The base icon therefore no longer needs to re-bake every frame to animate the glint:
 * {@code CuboidItemModelWrapperMixin} stops forcing the foil/animated flag for flat GUI items, and this
 * overlay scrolls the glint live instead.
 *
 * <p>The shader ({@link GlintPipelines#GUI_ITEM_GLINT}) samples {@code Sampler0} (the cached slot) for the
 * silhouette mask and {@code Sampler1} (the grayscale design) for the pattern, animating via the per-vertex
 * payload packed below. See {@code core/gui_item_glint.{vsh,fsh}}.
 */
public final class GuiItemGlintRenderState implements GuiElementRenderState {

    private final TextureSetup textureSetup;
    private final Matrix3x2f pose;
    private final int x0, y0, x1, y1;
    private final float u0, u1, v0, v1;
    private final int color;                    // animated layer colour, ARGB
    private final int scrollXPacked, scrollYPacked; // scroll vector (design-UV, wrapped to 0..1) * 16000 (short)
    private final int psPacked;                 // patternScale * 4096 (short)
    private final int modeAspect;               // guiScale (low 7 bits) | atlas-mode bit 7 | atlas cell (bits 8-15)
    private final @Nullable ScreenRectangle scissorArea;
    private final @Nullable ScreenRectangle bounds;

    public GuiItemGlintRenderState(TextureSetup textureSetup, Matrix3x2f pose,
            int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1,
            int color, int scrollXPacked, int scrollYPacked, int psPacked, int modeAspect,
            @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) {
        this.textureSetup = textureSetup;
        this.pose = pose;
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.u0 = u0; this.u1 = u1; this.v0 = v0; this.v1 = v1;
        this.color = color;
        this.scrollXPacked = scrollXPacked; this.scrollYPacked = scrollYPacked;
        this.psPacked = psPacked; this.modeAspect = modeAspect;
        this.scissorArea = scissorArea;
        this.bounds = bounds;
    }

    @Override
    public void buildVertices(VertexConsumer vc) {
        emit(vc, x0, y0, u0, v0);
        emit(vc, x0, y1, u0, v1);
        emit(vc, x1, y1, u1, v1);
        emit(vc, x1, y0, u1, v0);
    }

    private void emit(VertexConsumer vc, int x, int y, float u, float v) {
        vc.addVertexWith2DPose(this.pose, (float) x, (float) y)
          .setUv(u, v)
          .setUv1(this.scrollXPacked, this.scrollYPacked)
          .setUv2(this.psPacked, this.modeAspect)
          .setColor(this.color);
    }

    @Override
    public RenderPipeline pipeline() {
        return GlintPipelines.GUI_ITEM_GLINT;
    }

    @Override
    public TextureSetup textureSetup() {
        return this.textureSetup;
    }

    @Override
    public @Nullable ScreenRectangle scissorArea() {
        return this.scissorArea;
    }

    @Override
    public @Nullable ScreenRectangle bounds() {
        return this.bounds;
    }
}
