package net.tunamods.customglint.common.client;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * One live procedural-chromatic overlay quad over a glinted item's cached GUI icon, the chromatic analog of
 * {@link GuiItemGlintRenderState}. {@code GuiRendererMixin} emits one per chromatic glint layer as a GLYPH so
 * it draws on top of the opaque cached icon. The shader ({@link GlintPipelines#GUI_ITEM_CHROMATIC}) samples
 * {@code Sampler0} (the cached slot) for the silhouette mask and {@code Sampler1} (the palette strip) for the
 * colours; the per-trim seed and colour count ride the {@code UV1} payload (see
 * {@code core/gui_chromatic.{vsh,fsh}}). Unlike the texture-design overlay there is no per-colour loop:
 * one glyph composites the whole palette.
 */
public final class GuiItemChromaticRenderState implements GuiElementRenderState {

    private final TextureSetup textureSetup;
    private final Matrix3x2f pose;
    private final int x0, y0, x1, y1;
    private final float u0, u1, v0, v1;
    private final int seedPacked;               // seed & 0xFFFF (shader reads /256 → the 0..256 range)
    private final int colorCount;               // 0 => rainbow fallback; 1..8 => palette texels
    private final int psPacked;                 // patternScale * 4096 (short)
    private final int guiScale;                 // guiScale (low 7 bits)
    private final @Nullable ScreenRectangle scissorArea;
    private final @Nullable ScreenRectangle bounds;

    public GuiItemChromaticRenderState(TextureSetup textureSetup, Matrix3x2f pose,
            int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1,
            int seedPacked, int colorCount, int psPacked, int guiScale,
            @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) {
        this.textureSetup = textureSetup;
        this.pose = pose;
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.u0 = u0; this.u1 = u1; this.v0 = v0; this.v1 = v1;
        this.seedPacked = seedPacked;
        this.colorCount = colorCount;
        this.psPacked = psPacked;
        this.guiScale = guiScale;
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
          .setUv1(this.seedPacked, this.colorCount)
          .setUv2(this.psPacked, this.guiScale)
          .setColor(0xFFFFFFFF);
    }

    @Override
    public RenderPipeline pipeline() {
        return GlintPipelines.GUI_ITEM_CHROMATIC;
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
