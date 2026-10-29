package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CgGlintHolder;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.GlintPipelines;
import net.tunamods.customglint.common.client.GuiItemChromaticRenderState;
import net.tunamods.customglint.common.client.GuiItemGlintRenderState;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory-icon glow halo: a ring around a glowing item's GUI icon, in the item's glow colour(s). The
 * 26.1 analog of 1.21.1's {@code doGuiItemOutline} (which drew 4 one-pixel-offset copies of the item via
 * {@code ItemRenderer.render}). That immediate path is gone, GUI items render into a cached
 * {@link GuiItemAtlas} slot and are blitted, AND the 26.1 GUI sorts blits by (scissor, pipeline), not
 * submission order, so a "behind the item" copy can't be guaranteed.
 *
 * <p>Instead we add ONE blit of the slot texture through {@link GlintPipelines#GUI_ITEM_OUTLINE} on a quad
 * GROWN by {@link #OUTLINE_MARGIN} item-pixels (scissor grown to match), whose shader does a uniform outward
 * dilation of the item: it emits the flat glow colour in the {@code OUTLINE_MARGIN}-px border around the
 * silhouette and discards over the icon itself, so drawing on top is fine. Every sample maps back into the
 * item's own slot, so the halo can't read a neighbouring icon. Order-independent, works despite the 26.1
 * GUI sorting blits by pipeline rather than submission order.
 *
 * <p>Glow comes off the item's render state (set by {@code ItemModelResolverMixin}); the colour resolves
 * like everywhere else: glow colours first (animated), then glint layer 0, else white.
 */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    /** Halo thickness in ITEM pixels, the blit quad is grown by this much on every side to give the outward
     *  halo room outside the 16x16 icon. MUST equal {@code MARGIN} in {@code core/gui_item_outline.fsh}
     *  (the shader derives its SCALE and dilation radius from the same value). Tune both together. */
    private static final int OUTLINE_MARGIN = 1;

    @Shadow @Final private GuiRenderState renderState;

    @Inject(method = "submitBlitFromItemAtlas", at = @At("HEAD"), require = 0)
    private void cg_itemGlowHalo(GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView, CallbackInfo ci) {
        ItemStackRenderState rs = itemState.itemStackRenderState();
        if (!(rs instanceof CgGlintHolder holder)) return;
        boolean glowing = holder.customglint$isGlowing();
        int[] gc = holder.customglint$getGlowColors();
        boolean hasColors = gc != null && gc.length > 0;
        if (!glowing && !hasColors) return;

        // Use game time (computeAnimatedGlowColor), NOT the wall-clock GUI variant, so the halo runs on the
        // same clock as the trim's tinted edge texture (GlowTintSource, also game time) and the in-world ring.
        // The halo carries GLOW_RING_PHASE_OFFSET (half a cycle) so it sits out of phase with the edge: when
        // the edge is on one colour the ring is half a step behind it, matching the in-world outline ring.
        int color;
        if (hasColors) {
            color = CustomGlintRenderer.computeAnimatedGlowColor(gc, holder.customglint$getGlowSpeed(),
                    holder.customglint$getGlowInterp(), CustomGlintRenderer.GLOW_RING_PHASE_OFFSET);
        } else {
            // Auto glow follows glint layer 0's colours on the SAME glow clock as the tint (GlowTintSource
            // animates those same colours), offset by the ring phase, not the glint's own animation.
            CustomGlint.Data glint = holder.customglint$getGlint();
            color = (glint != null && glint.layers().length > 0)
                    ? CustomGlintRenderer.computeAnimatedGlowColor(glint.layers()[0].colors(),
                            holder.customglint$getGlowSpeed(), holder.customglint$getGlowInterp(),
                            CustomGlintRenderer.GLOW_RING_PHASE_OFFSET)
                    : 0xFFFFFFFF;
        }

        // Pack guiScale into the colour's alpha byte. The shader needs the slot UV size s; the atlas slot is
        // ALWAYS 16*guiScale texels, so s = 16*guiScale/atlasSize can be computed EXACTLY shader-side from just
        // guiScale, no fwidth, no rounding, no clamp. (The earlier scheme packed the quad's on-screen px size
        // and recovered s via fwidth; on the big zoomed wand preview that value overflowed 7 bits, clamped, and
        // corrupted s, which tore the outline into cross lines. guiScale is small and exact.)
        int guiScale = CustomGlintRenderer.frameGuiScale();
        color = (color & 0x00FFFFFF) | (guiScale << 24);

        TextureSetup tex = TextureSetup.singleTexture(slotView.textureView(),
                RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
        // Grow the blit quad by OUTLINE_MARGIN item-pixels on every side so the outward halo has room to draw
        // OUTSIDE the 16x16 icon. UVs stay pinned to the atlas slot, so the icon appears at SCALE inside the
        // bigger quad and the shader fills the surrounding border. (x/y are pose-local; the pose scales them.)
        int x0 = itemState.x() - OUTLINE_MARGIN, y0 = itemState.y() - OUTLINE_MARGIN;
        int x1 = itemState.x() + 16 + OUTLINE_MARGIN, y1 = itemState.y() + 16 + OUTLINE_MARGIN;
        // Clip the halo to the item's on-screen SLOT BOUNDS so it can't bleed past the slot into neighbouring
        // slots or other UI. bounds() is the 16x16 item rect transformed by the pose and already intersected
        // with the active menu scissor, i.e. exactly the slot the icon occupies on screen. The outward halo
        // still shows because item sprites carry a transparent border inside their 16x16, leaving room within
        // the slot; a sprite that bleeds to the very edge simply loses the halo on that edge rather than
        // spilling over. Falls back to scissorArea() if bounds() is somehow absent.
        ScreenRectangle scissor = itemState.bounds() != null ? itemState.bounds() : itemState.scissorArea();
        renderState.addBlitToCurrentLayer(new BlitRenderState(
                GlintPipelines.GUI_ITEM_OUTLINE, tex, itemState.pose(),
                x0, y0, x1, y1,
                slotView.u0(), slotView.u1(), slotView.v0(), slotView.v1(), color, scissor));
    }

    /**
     * Draws the animated glint LIVE over the cached icon, for items flagged by {@code CuboidItemModelWrapperMixin}
     * as overlay items (flat GUI icons whose base is cached static instead of re-baking every frame). One
     * {@link GuiItemGlintRenderState} is emitted per glint layer/colour, the GUI analog of {@code applyGlint}'s
     * per-layer multi-consumer, added as a GLYPH so it draws on top of the opaque icon (glyphs render after
     * the node's sorted elements). The scroll scalars are wall-clock, matching the in-hand glint exactly.
     */
    @Inject(method = "submitBlitFromItemAtlas", at = @At("HEAD"), require = 0)
    private void cg_itemGlintOverlay(GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView, CallbackInfo ci) {
        ItemStackRenderState rs = itemState.itemStackRenderState();
        if (!(rs instanceof CgGlintHolder holder) || !holder.customglint$isGuiGlintOverlay()) return;
        CustomGlint.Data glint = holder.customglint$getGlint();
        if (glint == null) return;

        // guiScale only, the shader derives the slot UV size from it. (The block-atlas aspect that used to
        // ride the high bits is gone: forGlint's U:V density ratio is intrinsic, reproduced by a constant
        // 0.5 in the shader, not atlas-derived.)
        int guiScale = CustomGlintRenderer.frameGuiScale();
        GpuSampler slotSampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
        GpuSampler designSampler = GlintPipelines.glintSampler();
        long millis = Util.getMillis();
        int x0 = itemState.x(), y0 = itemState.y();
        int x1 = x0 + 16, y1 = y0 + 16;
        ScreenRectangle scissor = itemState.scissorArea();
        ScreenRectangle bounds = itemState.bounds() != null ? itemState.bounds() : scissor;

        CustomGlint.Layer[] layers = glint.layers();
        for (int li = 0; li < layers.length; li++) {
            CustomGlint.Layer layer = layers[li];
            // Procedural chromatic: no design texture, emit ONE glyph through the gui_chromatic pipeline,
            // palette on Sampler1, seed + colour count packed into the vertex payload (the shader composites
            // every colour itself, so no per-colour loop).
            if (CustomGlint.isChromatic(layer)) {
                int[] chromaCols = CustomGlintRenderer.chromaticColors(layer.colors());
                Identifier palId = CustomGlintRenderer.paletteTexture(chromaCols);
                var palTex = Minecraft.getInstance().getTextureManager().getTexture(palId);
                GpuTextureView palView = palTex != null ? palTex.getTextureView() : null;
                if (palView == null) continue;
                TextureSetup ctex = TextureSetup.doubleTexture(slotView.textureView(), slotSampler, palView, designSampler);
                // × the flat-item match factor so the inventory icon's oil-slick density equals the in-world
                // held item's (and thus worn armor's). The world flat-item RT applies the same factor.
                int psPackedC = Math.min(32767, Math.round(
                        layer.patternScale() * CustomGlintRenderer.chromaticFlatItemMatch() * 4096.0f));
                // Pack morph speed into UV2.y's high bits (guiScale stays in the low 7), so the GUI morph
                // tracks the trim's speed exactly like the world chromatic's TextureMat[2][0].
                int speedQ = Math.max(1, Math.min(255, Math.round((float) layer.speed() * 16.0f)));
                int guiSpeedPacked = (guiScale & 127) | (speedQ << 7);
                renderState.addGlyphToCurrentLayer(new GuiItemChromaticRenderState(ctex, itemState.pose(),
                        x0, y0, x1, y1, slotView.u0(), slotView.u1(), slotView.v0(), slotView.v1(),
                        layer.seed() & 0xFFFF, chromaCols.length, psPackedC, guiSpeedPacked, scissor, bounds));
                continue;
            }
            // Prefer the shared design atlas: every glinted icon's glyph then carries the SAME TextureSetup
            // (shared slot atlas + shared design atlas), so the GUI mesher batches them into ONE draw instead
            // of flushing a draw per distinct design. The design is selected in-shader from the cell index
            // packed into the payload (bit 7 = atlas mode, bits 8-15 = cell). Designs the atlas doesn't hold
            // (CHROMATIC aside, beyond capacity, or a load failure) fall back to the per-design texture.
            TextureSetup tex;
            int modeAspect;
            Integer cell = CustomGlintRenderer.guiDesignCellIndex(layer.design());
            GpuTextureView atlasView = cell != null ? CustomGlintRenderer.guiDesignAtlasView() : null;
            if (cell != null && atlasView != null) {
                tex = TextureSetup.doubleTexture(slotView.textureView(), slotSampler,
                        atlasView, CustomGlintRenderer.guiDesignAtlasSampler());
                modeAspect = (guiScale & 127) | (1 << 7) | ((cell & 255) << 8);
            } else {
                Identifier texId = CustomGlintRenderer.getTexture(layer.design());
                if (texId == null) continue;
                // Mirror the chromatic branch's guard: getTexture(texId) can return null/placeholder if the
                // design was released between a resource reload and this frame; getTextureView() would NPE.
                var designTex = Minecraft.getInstance().getTextureManager().getTexture(texId);
                GpuTextureView designView = designTex != null ? designTex.getTextureView() : null;
                if (designView == null) continue;
                tex = TextureSetup.doubleTexture(slotView.textureView(), slotSampler, designView, designSampler);
                modeAspect = guiScale & 127;
            }
            int[] colors = layer.colors();
            // An unchosen layer (no dye picked) renders as a white placeholder, the colors stay empty
            // everywhere else (so it isn't "chosen" and can't be printed), only the draw substitutes white.
            if (colors.length == 0) colors = new int[]{0xFFFFFFFF};
            int cc = Math.max(1, colors.length);
            // patternScale as 12-bit fixed point, clamped to the int16 the payload field can hold.
            int psPacked = Math.min(32767, Math.round(layer.patternScale() * 4096.0f));
            int scrollDir = layer.scrollDir();
            float scrollOffset = layer.scrollOffset();
            if (layer.simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    cg_emitGlint(tex, itemState.pose(), x0, y0, x1, y1, slotView, colors[i],
                            layer.speed(), i, cc, psPacked, modeAspect, scrollDir, scrollOffset, millis, scissor, bounds);
                }
            } else {
                cg_emitGlint(tex, itemState.pose(), x0, y0, x1, y1, slotView,
                        CustomGlintRenderer.computeAnimatedColorGui(glint, li),
                        layer.speed(), 0, cc, psPacked, modeAspect, scrollDir, scrollOffset, millis, scissor, bounds);
            }
        }
    }

    private void cg_emitGlint(TextureSetup tex, Matrix3x2f pose, int x0, int y0, int x1, int y1,
            GuiItemAtlas.SlotView slotView, int color, double speed, int colorIdx, int cc, int psPacked,
            int modeAspect, int scrollDir, float scrollOffset, long millis, ScreenRectangle scissor, ScreenRectangle bounds) {
        // Compute the 2D scroll vector exactly like GlintPipelines.itemAnimationMatrix so the GUI overlay
        // drifts the same direction/speed as the in-hand glint (wall-clock millis keeps them in lockstep).
        float scrollX, scrollY;
        float phase = (float) colorIdx / cc;
        if (scrollDir == CustomGlint.SCROLL_STATIC) {
            // Spread each color by its phase so a simultaneous+static layer fans the colors out instead of
            // stacking them on the same UV (matches itemAnimationMatrix's static branch).
            scrollX = scrollOffset + phase;
            scrollY = 0.0f;
        } else {
            long t = (long) (millis * 8.0 * speed);
            float f  = (float) (t % 110000L) / 110000.0f + phase;
            float f1 = (float) (t % 30000L)  /  30000.0f;
            float[] dir = GlintPipelines.scrollUnit(scrollDir);
            scrollX = (f + f1) * dir[0];
            scrollY = (f + f1) * dir[1];
        }
        // Wrap each axis to [0,1) (the design REPEATs) so it fits the int16 *16000 packing without overflow.
        scrollX -= (float) Math.floor(scrollX);
        scrollY -= (float) Math.floor(scrollY);
        int scrollXPacked = Math.round(scrollX * 16000.0f);
        int scrollYPacked = Math.round(scrollY * 16000.0f);
        renderState.addGlyphToCurrentLayer(new GuiItemGlintRenderState(tex, pose,
                x0, y0, x1, y1, slotView.u0(), slotView.u1(), slotView.v0(), slotView.v1(),
                color, scrollXPacked, scrollYPacked, psPacked, modeAspect, scissor, bounds));
    }
}
