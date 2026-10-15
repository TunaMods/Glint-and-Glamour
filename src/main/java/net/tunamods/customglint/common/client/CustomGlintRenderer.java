package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

import net.tunamods.customglint.common.CustomGlint.Data;
import net.tunamods.customglint.common.CustomGlint.Layer;

/**
 * Client-only rendering backend. Split out of {@link CustomGlint} so that the data-API class
 * remains loadable on dedicated servers (where {@link RenderStateShard} and other client classes
 * are absent). Mods bundling the api jar should call render-pipeline methods through this class;
 * NBT/data API stays on {@link CustomGlint}.
 */
public final class CustomGlintRenderer extends RenderStateShard {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Texture cache ─────────────────────────────────────────────────────────

    private static final Map<ResourceLocation, ResourceLocation> TEXTURE_CACHE = new HashMap<>();

    /** The uploaded greyscale texture for {@code design}, built on first ask. Null when the design has no
     *  PNG (the procedural chromatic one) or its resource is missing; every caller treats null as "skip". */
    @Nullable
    public static ResourceLocation getTexture(ResourceLocation design) {
        // Single map lookup on the common (resolved, non-null) hit path; only null-cached negatives
        // fall through to the containsKey check to distinguish "cached miss" from "never seen".
        ResourceLocation cached = TEXTURE_CACHE.get(design);
        if (cached != null || TEXTURE_CACHE.containsKey(design)) return cached;
        ResourceLocation result = generateTexture(design);
        TEXTURE_CACHE.put(design, result);
        return result;
    }

    /**
     * Bind Sampler0 + the colour modulator for a textured glint draw. Normally the grayscale design goes on
     * Sampler0 and the colour rides {@code ColorModulator}; under a pack whose glint program ignores that
     * uniform (Photon) the colour is baked into the texture instead and the modulator stays white. See
     * {@link PhotonCompat}.
     */
    private static void bindGlintTexture(ResourceLocation design, float[] holder, float brightness) {
        if (PhotonCompat.dropsGlintColor()) {
            ResourceLocation tinted = PhotonCompat.tintedDesign(design, holder, brightness);
            if (tinted != null) {
                RenderSystem.setShaderTexture(0, tinted);
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, holder[3]);
                return;
            }
        }
        RenderSystem.setShaderTexture(0, getTexture(design));
        RenderSystem.setShaderColor(holder[0] * brightness, holder[1] * brightness,
                holder[2] * brightness, holder[3]);
    }

    public static void clearTextures() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation loc : TEXTURE_CACHE.values())
            if (loc != null) mc.getTextureManager().release(loc);
        TEXTURE_CACHE.clear();
        PhotonCompat.clear();
        for (Runnable r : additionalReloadCleanup) {
            try { r.run(); } catch (Throwable t) {
                LOGGER.warn("[{}/CustomGlint] additional reload cleanup threw", MOD_ID, t);
            }
        }
        for (ItemGlintEntry e : BY_GLINT_ITEM.values())     evictRt(e.rt);
        for (RenderType rt : BY_ARMOR_GLINT.values())       evictRt(rt);
        for (RenderType rt : BY_HORSE_ARMOR_GLINT.values()) evictRt(rt);
        for (RenderType rt : BY_MOUNT_ARMOR_GLINT.values()) evictRt(rt);
        for (RenderType rt : BY_CHROMATIC.values())         evictRt(rt);
        for (RenderType rt : BY_CHROMATIC_OVERLAY.values()) evictRt(rt);
        for (RenderType rt : BY_GLINT_OVERLAY.values())     evictRt(rt);
        BY_GLINT_ITEM.clear();
        BY_ARMOR_GLINT.clear();
        BY_HORSE_ARMOR_GLINT.clear();
        BY_MOUNT_ARMOR_GLINT.clear();
        BY_CHROMATIC.clear();
        BY_CHROMATIC_OVERLAY.clear();
        BY_GLINT_OVERLAY.clear();
        GLINT_COLORS.clear();
        LAYER_KEY_CACHE.clear();
        // Release the chromatic palette strips + white dummy (DynamicTextures registered with the manager).
        for (ResourceLocation loc : PALETTE_CACHE.values())
            if (loc != null) mc.getTextureManager().release(loc);
        PALETTE_CACHE.clear();
        if (whiteTex != null) { mc.getTextureManager().release(whiteTex); whiteTex = null; }
        // The shader-TT tag set retains every RenderType it ever saw; drop it on reload (re-applied lazily)
        // so RenderTypes evicted above aren't pinned across reloads.
        SHADER_TT_TAGGED.clear();
        // Drop the entity RenderType verdict cache too, so it doesn't pin RT singletons across reloads.
        EntityGlintRender.GlintWrappingBufferSource.clearRtVerdictCache();
        // Free the private deferred GUI-glint source's per-RenderType buffers; the forGlint RTs they key on
        // were just evicted, so these native builders would dangle. The source + builders re-create lazily
        // next deferred GUI glint.
        for (ByteBufferBuilder b : GUI_GLINT_FIXED.values()) { try { b.close(); } catch (Throwable ignored) {} }
        GUI_GLINT_FIXED.clear();
        if (guiGlintSpare != null) { try { guiGlintSpare.close(); } catch (Throwable ignored) {} guiGlintSpare = null; }
        guiGlintSource = null;
        // Rebuild the shared GUI design atlas on the next inventory glint draw: its cells point at design
        // textures released above, and the block-atlas scale baked into its RenderType may have changed.
        invalidateGuiDesignAtlas();
        // Invalidate the cached block-atlas dimensions (the atlas restitches on resource reload).
        cachedAtlasW = 0;
        cachedAtlasH = 0;
        // The shield sheet repacks on reload, so the base sprite's UV extent (and its compensation) can move.
        shieldInvU = 0f;
    }

    // Block-atlas dimensions, read once and reused (the atlas only restitches on resource reload, which
    // resets these to 0 in clearTextures()). Avoids a getModelManager().getAtlas() lookup per glint draw.
    private static int cachedAtlasW = 0;
    private static int cachedAtlasH = 0;

    private static void ensureAtlasDims() {
        if (cachedAtlasW == 0) {
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            cachedAtlasW = atlas.width;
            cachedAtlasH = atlas.height;
        }
    }

    // The shield draws its glint through material.sprite().wrap() (BEWLR.renderByItem), which squeezes the
    // model's [0,1] UVs into the base shield sprite's small sub-rect of the SHIELD atlas. A glint sampled off
    // those UVs (the procedural chromatic slick) is compressed by that sub-rect's extent, so at the shared
    // special-item scale the shield's slick reads far more zoomed-in than the trident's (which draws unwrapped,
    // raw UVs). shieldInvU/V = 1/(sprite extent): multiply a special-item UV scale by it and the slick tiles
    // across the shield model's real UVs, matching the trident. Cached; recomputed after a reload.
    private static float shieldInvU = 0f, shieldInvV = 0f;

    private static void ensureShieldScale() {
        if (shieldInvU != 0f) return;
        float du = 0f, dv = 0f;
        try {
            TextureAtlasSprite s = ModelBakery.NO_PATTERN_SHIELD.sprite();
            du = s.getU1() - s.getU0();
            dv = s.getV1() - s.getV0();
        } catch (Throwable ignored) {
            // Sprite not resolvable (atlas not ready / renamed): fall back to a typical shield-sheet extent.
        }
        shieldInvU = du > 0f ? 1.0f / du : 8.0f;
        shieldInvV = dv > 0f ? 1.0f / dv : 8.0f;
    }


    private static ResourceLocation generateTexture(ResourceLocation design) {
        // The chromatic design is procedural (no PNG). Callers branch to forChromatic* before reaching the
        // texture path; this guard keeps any stragglers (compat stencil paths) from probing the resource
        // manager; they get null and skip, so chromatic silently no-ops there rather than crashing.
        if (CustomGlint.isChromatic(design)) return null;
        Minecraft mc = Minecraft.getInstance();
        NativeImage source;
        try {
            var resource = mc.getResourceManager().getResource(design);
            if (resource.isEmpty()) return null;
            try (InputStream stream = resource.get().open()) {
                source = NativeImage.read(stream);
            }
        } catch (IOException e) {
            return null;
        }

        NativeImage gray = new NativeImage(source.getWidth(), source.getHeight(), false);
        try {
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    // NativeImage pixel format is ABGR stored as int: (A<<24)|(B<<16)|(G<<8)|R
                    int pixel = source.getPixelRGBA(x, y);
                    int r =  pixel        & 0xFF;
                    int g = (pixel >>  8) & 0xFF;
                    int b = (pixel >> 16) & 0xFF;
                    int a = (pixel >> 24) & 0xFF;
                    int lum = (r + g + b) / 3;
                    gray.setPixelRGBA(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
                }
            }
        } finally {
            source.close();
        }

        String safePath = design.getNamespace() + "/" + design.getPath().replace('/', '_').replace('.', '_');
        ResourceLocation loc = CustomGlint.res("glint/" + safePath);
        DynamicTexture dt = new DynamicTexture(gray);
        mc.getTextureManager().register(loc, dt);
        dt.bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return loc;
    }

    // ── Shared GUI design atlas (inventory glint batching) ───────────────────────────────────────
    // A many-icon screen (creative tab, the Glint Table palettes) draws each glinted icon's glint through the
    // deferred GUI source (guiGlintBuffer); with a per-design texture, each distinct design is its own
    // RenderType and so its own draw (a palette of trims = dozens of draws/frame). Stitching every design into
    // ONE shared atlas lets every icon draw through the single guiAtlasGlintRenderType, batching them into one
    // draw no matter how many distinct designs are on screen. The per-icon design is picked in-shader from a
    // cell index carried on the vertex payload; each cell has a wrapped gutter so tiling stays clean. Ported
    // from the 26.1.2 branch (which atlases the GUI path the same way); only the image build lifts verbatim.
    private static final int GUI_ATLAS_CONTENT = 64;  // design content per cell (designs are <=64px)
    private static final int GUI_ATLAS_GUTTER  = 4;   // wrapped border per side (harmless under NEAREST; kept
                                                      // so the layout matches the shader + the 26.1.2 build)
    private static final int GUI_ATLAS_STRIDE  = GUI_ATLAS_CONTENT + 2 * GUI_ATLAS_GUTTER; // 72
    // Cell index rides one vertex short, and the atlas stays a sane texture; past this a design falls back to
    // the per-design draw (still correct, just its own draw).
    private static final int GUI_ATLAS_MAX_CELLS = 256;
    public static final ResourceLocation GUI_DESIGN_ATLAS_ID = CustomGlint.res("gui_design_atlas");
    private static boolean guiDesignAtlasBuilt = false;
    /** design → its 0-based atlas cell. Absent (CHROMATIC, a load failure, or past the cap) → per-design draw. */
    private static final Map<ResourceLocation, Integer> GUI_DESIGN_CELLS = new HashMap<>();
    /** Full design list to atlas (built-ins + data-pack), installed by the full mod at client init since the
     *  data-pack list lives in the module. Null → built-ins only (an api-only embedder without the full mod). */
    private static volatile Supplier<List<ResourceLocation>> guiAtlasDesignSource = null;

    /** Installs the design source for the shared GUI atlas (the full mod passes its data-pack-inclusive list)
     *  and forces a rebuild on the next draw. */
    public static void setGuiAtlasDesignSource(Supplier<List<ResourceLocation>> source) {
        guiAtlasDesignSource = source;
        invalidateGuiDesignAtlas();
    }

    /** Drops the built atlas (+ its RenderType, whose baked block-atlas scale may go stale) so the next glint
     *  draw restitches it. Call after the design list changes or a resource reload. No-op if not built. */
    public static void invalidateGuiDesignAtlas() {
        guiAtlasGlintRT = null;
        if (!guiDesignAtlasBuilt) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.getTextureManager().release(GUI_DESIGN_ATLAS_ID);
        GUI_DESIGN_CELLS.clear();
        guiDesignAtlasBuilt = false;
    }

    /** Builds the shared atlas once (lazily, on first inventory glint draw). The {@code built} flag is set
     *  first so a failed build degrades to the per-design fallback for the session instead of retrying every
     *  frame; a resource reload restitches it via {@link #clearTextures}. */
    private static void ensureGuiDesignAtlas() {
        if (guiDesignAtlasBuilt) return;
        guiDesignAtlasBuilt = true;
        List<ResourceLocation> designs;
        try {
            Supplier<List<ResourceLocation>> src = guiAtlasDesignSource;
            designs = src != null ? src.get() : Arrays.asList(CustomGlint.PATTERNS);
        } catch (Throwable t) {
            designs = Arrays.asList(CustomGlint.PATTERNS);
        }
        if (designs == null || designs.isEmpty()) designs = Arrays.asList(CustomGlint.PATTERNS);
        int count = Math.min(designs.size(), GUI_ATLAS_MAX_CELLS);
        int grid = Math.max(1, (int) Math.ceil(Math.sqrt(count)));  // cells per side, so grid*grid >= count
        int dim = grid * GUI_ATLAS_STRIDE;
        NativeImage atlas = new NativeImage(dim, dim, false);
        try {
            for (int i = 0; i < count; i++) {
                ResourceLocation design = designs.get(i);
                if (design == null || CustomGlint.isChromatic(design)) continue; // procedural: no texture
                NativeImage gray = loadGrayscaleImage(design);
                if (gray == null) continue;                                       // missing → per-design fallback
                try {
                    int col = i % grid, row = i / grid;
                    int ox = col * GUI_ATLAS_STRIDE, oy = row * GUI_ATLAS_STRIDE;
                    int sw = gray.getWidth(), sh = gray.getHeight();
                    for (int gy = 0; gy < GUI_ATLAS_STRIDE; gy++) {
                        for (int gx = 0; gx < GUI_ATLAS_STRIDE; gx++) {
                            // Cell-local coord (content origin at GUTTER,GUTTER) wrapped into the design, so the
                            // gutter holds the opposite-edge texels for seam-free tiling.
                            int lx = gx - GUI_ATLAS_GUTTER, ly = gy - GUI_ATLAS_GUTTER;
                            int cx = ((lx % GUI_ATLAS_CONTENT) + GUI_ATLAS_CONTENT) % GUI_ATLAS_CONTENT;
                            int cy = ((ly % GUI_ATLAS_CONTENT) + GUI_ATLAS_CONTENT) % GUI_ATLAS_CONTENT;
                            atlas.setPixelRGBA(ox + gx, oy + gy,
                                    gray.getPixelRGBA(cx * sw / GUI_ATLAS_CONTENT, cy * sh / GUI_ATLAS_CONTENT));
                        }
                    }
                    GUI_DESIGN_CELLS.put(design, i);
                } finally {
                    gray.close();
                }
            }
            // DynamicTexture takes ownership of `atlas` on register (don't close it on the success path).
            DynamicTexture dt = new DynamicTexture(atlas);
            Minecraft.getInstance().getTextureManager().register(GUI_DESIGN_ATLAS_ID, dt);
            dt.bind();
            // CLAMP (fract + the gutter do the tiling in-shader; the sampler must not wrap across cells) +
            // NEAREST (the per-design path's filter, so the atlased glint reads identically).
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        } catch (Throwable t) {
            atlas.close();
            GUI_DESIGN_CELLS.clear();
            LOGGER.warn("[{}/CustomGlint] GUI design atlas build failed; falling back to per-design glint draws", MOD_ID, t);
        }
    }

    /** The atlas cell index for {@code design}, or null when it isn't atlased (CHROMATIC, a load failure, or
     *  past {@link #GUI_ATLAS_MAX_CELLS}) and the per-design {@link #forGlint} path must be used. */
    @Nullable
    public static Integer guiDesignCellIndex(ResourceLocation design) {
        ensureGuiDesignAtlas();
        return GUI_DESIGN_CELLS.get(design);
    }

    /** Reads a design PNG into a fresh greyscale {@link NativeImage} (caller owns + closes it), or null if
     *  absent/unreadable. Same conversion as {@link #generateTexture}, minus the GL upload; stitches the atlas. */
    private static NativeImage loadGrayscaleImage(ResourceLocation design) {
        if (CustomGlint.isChromatic(design)) return null;
        Minecraft mc = Minecraft.getInstance();
        NativeImage source;
        try {
            var resource = mc.getResourceManager().getResource(design);
            if (resource.isEmpty()) return null;
            try (InputStream stream = resource.get().open()) {
                source = NativeImage.read(stream);
            }
        } catch (IOException e) {
            return null;
        }
        NativeImage gray = null;
        try {
            gray = new NativeImage(source.getWidth(), source.getHeight(), false);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    int px = source.getPixelRGBA(x, y);   // ABGR: (A<<24)|(B<<16)|(G<<8)|R
                    int r =  px        & 0xFF;
                    int g = (px >>  8) & 0xFF;
                    int b = (px >> 16) & 0xFF;
                    int a = (px >> 24) & 0xFF;
                    int lum = (r + g + b) / 3;
                    gray.setPixelRGBA(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
                }
            }
        } catch (Throwable t) {
            if (gray != null) gray.close();
            throw t;
        } finally {
            source.close();
        }
        return gray;
    }

    // ── Atlased GUI glint RenderType + per-vertex injector ───────────────────────────────────────
    /** Atlased GUI item-glint core shader: samples the shared design atlas cell selected per-vertex, so every
     *  glinted inventory icon batches into one draw. Declared before its shard to avoid a forward reference,
     *  mirroring {@code glintCutoutShader}. Assigned in {@link #registerShaders}. */
    private static ShaderInstance guiItemGlintShader;
    private static final ShaderStateShard GUI_ATLAS_GLINT_SHADER_SHARD =
            new ShaderStateShard(() -> guiItemGlintShader);
    private static RenderType guiAtlasGlintRT;

    /** The single shared RenderType for atlased GUI glint: every glinted inventory icon whose design is in the
     *  atlas draws through this ONE type, so the deferred GUI drain collapses them into a single draw. The atlas
     *  is Sampler0; colour/scroll/scale/cell ride the vertex payload; TextureMat carries the constant per-axis
     *  block-atlas scale (matching forGlint). EQUAL depth against the icons' committed depth, like forGlint.
     *  Null until the gui_item_glint shader loads. Nulled on reload so its baked scale can't go stale. */
    public static RenderType guiAtlasGlintRenderType() {
        if (guiItemGlintShader == null) return null;
        if (guiAtlasGlintRT == null) {
            ensureAtlasDims();
            final float scaleU = 8.0f * cachedAtlasW / 1024.0f;   // same calibration as forGlint's isItem scale
            final float scaleV = 8.0f * cachedAtlasH / 512.0f;
            guiAtlasGlintRT = RenderType.create(
                    MOD_ID + ":gui_atlas_glint",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(GUI_ATLAS_GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(GUI_DESIGN_ATLAS_ID, false, false))
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            // TextureMat diagonal = the constant per-axis block-atlas scale; the scroll is
                            // per-vertex, so this stays constant across the whole batch. scaling() (not raw m00/
                            // m11 setters) so joml's identity-property flag is cleared and the scale isn't ignored.
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":gui_atlas_glint_tx",
                                    () -> RenderSystem.setTextureMatrix(TEX_MATRIX.get().scaling(scaleU, scaleV, 1.0f)),
                                    RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
        }
        return guiAtlasGlintRT;
    }

    /** Per-vertex injector for the atlased GUI glint. The icon's own quads render once into this (wrapping the
     *  shared atlas RT's deferred buffer); it forces every vertex to carry this icon-layer's premultiplied
     *  colour, pre-fract'd scroll, patternScale and atlas cell, which a batched draw can't pass per item.
     *  Position + UV0 pass through; overlay/light (setUv1/setUv2) are REPLACED. Mirrors GlowOutlineRenderer's
     *  SilhouetteConsumer. Reused per icon (a GUI icon renders synchronously), like COLOR_BUF / TEX_MATRIX. */
    public static final class GuiAtlasGlintConsumer implements VertexConsumer {
        private VertexConsumer delegate;
        private int cr, cg, cb;      // premultiplied colour, 0..255
        private int sx16, sy16;      // scroll x16000, pre-fract'd to [0,16000)
        private int ps4096, cell;    // patternScale x4096 (clamped) ; atlas cell index

        GuiAtlasGlintConsumer set(VertexConsumer delegate, int cr, int cg, int cb,
                                  int sx16, int sy16, int ps4096, int cell) {
            this.delegate = delegate; this.cr = cr; this.cg = cg; this.cb = cb;
            this.sx16 = sx16; this.sy16 = sy16; this.ps4096 = ps4096; this.cell = cell;
            return this;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) { delegate.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { delegate.setColor(cr, cg, cb, 255); return this; }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(sx16, sy16); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(ps4096, cell); return this; }
        @Override public VertexConsumer setNormal(float nx, float ny, float nz) { delegate.setNormal(nx, ny, nz); return this; }
    }
    private static final ThreadLocal<GuiAtlasGlintConsumer> GUI_ATLAS_CONSUMER =
            ThreadLocal.withInitial(GuiAtlasGlintConsumer::new);

    /** The atlased GUI-glint consumer for one icon-layer (wrapping the shared atlas RT's deferred buffer), or
     *  null if this design isn't atlased so the caller falls back to {@link #forGlint}. {@code color} is the
     *  animated ARGB (alpha = the layer's opacity); it is premultiplied here to match forGlint's ColorModulator. */
    public static VertexConsumer guiAtlasGlintBuffer(Data glint, int layerIdx, int colorIdx, int color) {
        Layer layer = glint.layers()[layerIdx];
        Integer cell = guiDesignCellIndex(layer.design());
        if (cell == null) return null;
        RenderType rt = guiAtlasGlintRenderType();
        if (rt == null) return null;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        int cr = Math.round(((color >> 16) & 0xFF) * a);
        int cg = Math.round(((color >>  8) & 0xFF) * a);
        int cb = Math.round(( color        & 0xFF) * a);
        // Pre-fract the scroll to [0,1): the fsh fract-folds it into the cell, so dropping the integer part is
        // exact, and it keeps x16000 positive and inside the signed short the vertex payload uses.
        float[] sc = scrollAmount(layer, colorIdx);
        int sx16 = Math.round((sc[0] - (float) Math.floor(sc[0])) * 16000.0f);
        int sy16 = Math.round((sc[1] - (float) Math.floor(sc[1])) * 16000.0f);
        // patternScale in 1/4096ths, clamped to the signed-short range the setUv2 payload slot holds
        // (32767/4096 ≈ scale 8, well past the editor's ceiling).
        int ps4096 = Math.max(0, Math.min(32767, Math.round(layer.patternScale() * 4096.0f)));
        return GUI_ATLAS_CONSUMER.get().set(guiGlintBuffer(rt), cr, cg, cb, sx16, sy16, ps4096, cell);
    }

    // ── Render types ──────────────────────────────────────────────────────────

    /** Assigned by RenderBuffersMixin on RenderBuffers construction; null until then. */
    public static SequencedMap<RenderType, ByteBufferBuilder> fixedBufferRegistry;
    public static final ThreadLocal<ItemStack> CURRENT_ITEM_STACK = new ThreadLocal<>();
    /** The display context of the item currently being rendered (set at ItemRenderer.render HEAD). Lets
     *  {@code applyGlint} tell a HUD/GUI icon apart from a world/held item so the HUD glint can be routed
     *  to the batched {@link #guiGlintBuffer} source instead of drawing inline per item. */
    public static final ThreadLocal<ItemDisplayContext> CURRENT_CTX = new ThreadLocal<>();
    // True while a special / 3D BEWLR item (shield, trident) is rendering. Those call getFoilBuffer with
    // noEntity=true (so applyGlint sees isItem=true = the flat-item atlas scale), but they're 3D models whose
    // UV spans a large region, so the atlas scale reads far too dense, and mismatches the shader-pack special
    // overlay. Set at render HEAD from the model's isCustomRenderer() so chromatic can pick the special scale.
    public static final ThreadLocal<Boolean> CURRENT_IS_SPECIAL = ThreadLocal.withInitial(() -> Boolean.FALSE);
    public static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    /** ARGB int into {@code buf} as premultiplied RGB plus alpha 1, the form every glint RenderType factory
     *  reads its colour holder in. */
    public static void premultiply(float[] buf, int argb) {
        float a = ((argb >> 24) & 0xFF) / 255.0f;
        buf[0] = ((argb >> 16) & 0xFF) / 255.0f * a;
        buf[1] = ((argb >>  8) & 0xFF) / 255.0f * a;
        buf[2] = ( argb        & 0xFF) / 255.0f * a;
        buf[3] = 1.0f;
    }

    /** Picks the glint {@link RenderType} for one (layer, colour) pair. The colour arrives premultiplied in
     *  the shared {@link #COLOR_BUF} scratch array, so an implementation must hand it straight to a factory
     *  rather than keep the reference. */
    @FunctionalInterface
    public interface LayerRenderType {
        @Nullable RenderType get(int layerIdx, float[] color, int colorIdx);
    }

    /** Appends one TEXTURED (non-chromatic) layer's draw buffers to {@code out}: one buffer per colour when
     *  the layer shows all of them at once, else one for the current animated colour. Every worn/held render
     *  path fans a layer the same way and differs only in which factory it calls, so they share this and keep
     *  their own chromatic branch, which does differ per path. */
    public static void fanLayerBuffers(List<VertexConsumer> out, MultiBufferSource source, Data glint,
                                       int layerIdx, LayerRenderType factory) {
        Layer layer = glint.layers()[layerIdx];
        int[] colors = layer.colors().length == 0 ? WHITE_COLOR : layer.colors();
        float[] buf = COLOR_BUF.get();
        if (layer.simultaneous()) {
            for (int i = 0; i < colors.length; i++) {
                premultiply(buf, colors[i]);
                RenderType rt = factory.get(layerIdx, buf, i);
                if (rt != null) out.add(source.getBuffer(rt));
            }
        } else {
            premultiply(buf, computeAnimatedColor(glint, layerIdx));
            RenderType rt = factory.get(layerIdx, buf, 0);
            if (rt != null) out.add(source.getBuffer(rt));
        }
    }

    // ── Deferred GUI glint batching ──────────────────────────────────────────────
    // A many-icon screen (the creative tab, the Glint Table palettes) draws each icon through
    // GuiGraphics.renderItem, which calls GuiGraphics.flush() after EVERY icon, so inline glint pays one flush
    // per item and same-design icons never batch. Route those icons' glint into this private source instead:
    // vanilla's per-item flush never touches it, so every same-config glint accumulates into ONE RenderType
    // buffer and drains in a single endBatch ({@link #drainGuiGlint}) right after the screen's icon pass
    // (AbstractContainerScreenMixin for the creative menu; guiGlintBatchArmed for the Glint Table), while the
    // GUI ortho + the icons' depth are still committed. forGlint's color rides a per-(design,colors,…) holder,
    // so same-key icons always resolve the same colour in a frame; deferring the draw is colour-safe. The HUD
    // hotbar and every other screen keep the inline path. See ItemRendererMixin#applyGlint for why the hotbar
    // must NOT defer: a late drain at Gui.render RETURN tested EQUAL against depth a shader-pack reload had
    // disturbed, so the hotbar glint vanished.
    private static final SequencedMap<RenderType, ByteBufferBuilder> GUI_GLINT_FIXED = new LinkedHashMap<>();
    private static MultiBufferSource.BufferSource guiGlintSource;
    private static ByteBufferBuilder guiGlintSpare;

    /** Set by a screen that draws its own item icons (outside the vanilla slot pass) and wants their glint
     *  batched, the Glint Table's design/printed palettes. While armed, {@code applyGlint} routes GUI-context
     *  glint into {@link #guiGlintBuffer} regardless of the open screen; the screen must drain + disarm right
     *  after its icon pass (before it draws anything on top of those icons). */
    public static boolean guiGlintBatchArmed = false;

    /** A glint-layer buffer in the private deferred GUI source (each RenderType gets its own builder so
     *  layers/colours accumulate without flushing one another). The base item RT still goes through the
     *  normal GUI source. */
    public static VertexConsumer guiGlintBuffer(RenderType rt) {
        GUI_GLINT_FIXED.computeIfAbsent(rt, k -> new ByteBufferBuilder(k.bufferSize()));
        if (guiGlintSource == null) {
            guiGlintSpare = new ByteBufferBuilder(256);
            guiGlintSource = MultiBufferSource.immediateWithBuffers(GUI_GLINT_FIXED, guiGlintSpare);
        }
        return guiGlintSource.getBuffer(rt);
    }

    /** Draws all deferred GUI glint accumulated this frame in one batch per RenderType. Called right after a
     *  many-icon screen's icon pass (AbstractContainerScreenMixin before the tooltip; the Glint Table after
     *  its armed palette pass), where the GUI ortho projection and the icons' committed depth (forGlint
     *  EQUAL-depth-tests against it) are both still live. No-op until the first deferred glint creates the source. */
    public static void drainGuiGlint() {
        if (guiGlintSource != null) guiGlintSource.endBatch();
    }

    /** Reused scroll matrix for the glint texturing shards. Each shard's setup→draw→clear is atomic
     *  per RenderType flush, so a single per-thread instance (reset via {@code translation(...)}) avoids
     *  allocating a fresh {@link Matrix4f} on every batch flush of every glinted surface. */
    private static final ThreadLocal<Matrix4f> TEX_MATRIX = ThreadLocal.withInitial(Matrix4f::new);

    // ── Scroll direction → UV drift ─────────────────────────────────────────────
    // The glint motif drifts along one of eight compass directions (or freezes when STATIC). The pattern
    // *appears* to move opposite to the UV drift, so these are negated from screen direction; texture-V runs
    // DOWN so North (pattern up) drifts +V. Shared immutable vectors avoid a per-flush float[] allocation.
    private static final float SQ = 0.70710677f; // 1/√2
    private static final float[] SU_E  = {-1f, 0f},  SU_NE = {-SQ, SQ}, SU_N  = {0f, 1f},  SU_NW = {SQ, SQ},
                                 SU_W  = { 1f, 0f},  SU_SW = { SQ,-SQ}, SU_S  = {0f,-1f},  SU_SE = {-SQ,-SQ},
                                 SU_STATIC = {0f, 0f};
    private static final ThreadLocal<float[]> SCROLL_BUF = ThreadLocal.withInitial(() -> new float[2]);

    private static float[] scrollUnit(int dir) {
        return switch (dir) {
            case CustomGlint.SCROLL_E  -> SU_E;
            case CustomGlint.SCROLL_NE -> SU_NE;
            case CustomGlint.SCROLL_N  -> SU_N;
            case CustomGlint.SCROLL_NW -> SU_NW;
            case CustomGlint.SCROLL_W  -> SU_W;
            case CustomGlint.SCROLL_SW -> SU_SW;
            case CustomGlint.SCROLL_S  -> SU_S;
            case CustomGlint.SCROLL_SE -> SU_SE;
            default -> SU_STATIC; // SCROLL_STATIC
        };
    }

    /** Per-color UV drift for {@code layer} at the current wall-clock time. Animated dirs scroll along
     *  {@link #scrollUnit}; STATIC freezes at {@code scrollOffset} and fans simultaneous colors out by phase
     *  (else each color samples the same frozen UV and stacks exactly atop the others). */
    private static float[] scrollAmount(Layer layer, int colorIdx) {
        float[] out = SCROLL_BUF.get();
        float phase = (float) colorIdx / Math.max(1, layer.colors().length);
        if (layer.scrollDir() == CustomGlint.SCROLL_STATIC) {
            out[0] = layer.scrollOffset() + phase;
            out[1] = 0.0f;
        } else {
            long t = (long) (Util.getMillis() * 8.0 * layer.speed());
            // Two scroll octaves at vanilla's glint periods (slow 110000ms + fast 30000ms) summed for drift.
            float f  = (float) (t % 110000L) / 110000.0F + phase;
            float f1 = (float) (t % 30000L)  /  30000.0F;
            float[] dir = scrollUnit(layer.scrollDir());
            out[0] = (f + f1) * dir[0];
            out[1] = (f + f1) * dir[1];
        }
        return out;
    }

    /** Item-glint directional-drift texture matrix (atlas-calibrated scale). Drift first, then scale about the
     *  texture centre so growing patternScale doesn't slide the motif off (centre-pivot). */
    private static void setItemScrollMatrix(Layer layer, int colorIdx, float scaleU, float scaleV) {
        float[] sc = scrollAmount(layer, colorIdx);
        Matrix4f m = TEX_MATRIX.get().translation(sc[0], sc[1], 0.0F);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scaleU * layer.patternScale(), scaleV * layer.patternScale(), 1.0f);
        m.translate(-0.5f, -0.5f, 0.0f);
        RenderSystem.setTextureMatrix(m);
    }

    /** Armor / horse / mount / entity / EK-decoration directional-drift texture matrix (uniform model-UV scale).
     *  Shared so every model-space glint tiles the design at one scale; the off-pack EK decoration cutout calls
     *  it too so it matches worn armor and the on-pack overlay. */
    public static void setModelScrollMatrix(Layer layer, int colorIdx) {
        float[] sc = scrollAmount(layer, colorIdx);
        Matrix4f m = TEX_MATRIX.get().translation(sc[0], sc[1], 0.0F);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(layer.patternScale());
        m.translate(-0.5f, -0.5f, 0.0f);
        RenderSystem.setTextureMatrix(m);
    }

    /** Hard cap on each glint RenderType cache. A creative player cycling colours/designs on the wand
     *  generates a distinct key per config; without a bound these maps (and the native
     *  {@link ByteBufferBuilder} each cached RT pins in {@code fixedBuffers}) grow for the whole session.
     *  256 covers any realistic on-screen variety; the eldest entry is evicted (buffer closed) past it. */
    private static final int RT_CACHE_CAP = 256;

    /** Access-order LRU of glint RenderTypes. On eviction it closes the RT's native fixed buffer
     *  ({@link #evictRt}) and drops the paired colour holder so the two caches stay in lockstep. */
    private static final class RtCache extends LinkedHashMap<String, RenderType> {
        RtCache() { super(64, 0.75f, true); }
        @Override protected boolean removeEldestEntry(Map.Entry<String, RenderType> eldest) {
            if (size() > RT_CACHE_CAP) {
                evictRt(eldest.getValue());
                GLINT_COLORS.remove(eldest.getKey());
                return true;
            }
            return false;
        }
    }

    /** Item-glint cache entry: the cached RenderType plus its per-frame colour holder in one object, so a
     *  single map probe per draw yields both (the RT's setup closure reads {@code color[0..3]} at flush). */
    private static final class ItemGlintEntry { RenderType rt; final float[] color = new float[4]; }

    /** LRU of item-glint entries. Mirrors {@link RtCache}'s bound + eviction, but evicts the RenderType held
     *  inside the entry and needs no paired colour-map cleanup (the colour rides the entry). */
    private static final class ItemGlintCache extends LinkedHashMap<String, ItemGlintEntry> {
        ItemGlintCache() { super(64, 0.75f, true); }
        @Override protected boolean removeEldestEntry(Map.Entry<String, ItemGlintEntry> eldest) {
            if (size() > RT_CACHE_CAP) { evictRt(eldest.getValue().rt); return true; }
            return false;
        }
    }

    /** Per-key mutable float[4] holders; RenderType lambdas close over these references and read them each frame. */
    private static final Map<String, float[]>    GLINT_COLORS          = new HashMap<>();
    /** Item glint (forGlint) RenderType + colour holder, keyed together so the hot path probes one map. */
    private static final Map<String, ItemGlintEntry> BY_GLINT_ITEM     = new ItemGlintCache();
    private static final Map<String, RenderType> BY_ARMOR_GLINT        = new RtCache();
    private static final Map<String, RenderType> BY_HORSE_ARMOR_GLINT  = new RtCache();
    private static final Map<String, RenderType> BY_MOUNT_ARMOR_GLINT  = new RtCache();

    // ── Procedural chromatic glint ──────────────────────────────────────────────
    // The chromatic design has no PNG: a custom core shader (registered via RegisterShadersEvent)
    // synthesises an oil-slick from value-noise. Per-layer payload (seed / morph-speed / colour count)
    // rides spare TextureMat slots [2][0]/[2][1]/[2][3]; up to 8 colours ride a 1px palette strip on
    // Sampler1. One RenderType per (colours, speed, scale, seed, surface): a single draw, no per-colour
    // fan-out. Caches mirror the texture-glint LRU caps + reload eviction.
    private static ShaderInstance chromaticShader;
    private static final ShaderStateShard CHROMATIC_SHADER_SHARD =
            new ShaderStateShard(() -> chromaticShader);
    private static final Map<String, RenderType> BY_CHROMATIC = new RtCache();

    /** Chromatic slick plus the armor-shape alpha-test on Sampler2; the horse-armor counterpart of
     *  {@link #glintCutoutShader}. See {@link #forMountChromaticGlint}. */
    private static ShaderInstance chromaticCutoutShader;
    private static final ShaderStateShard CHROMATIC_CUTOUT_SHADER_SHARD =
            new ShaderStateShard(() -> chromaticCutoutShader);

    /** Chromatic slick declared for NEW_ENTITY, no camera-ward bias and no mask, so it can test EQUAL against a
     *  cutout base's own depth; the chromatic counterpart of {@link #glintModelShader}. See
     *  {@link #forElytraChromaticGlint}. */
    private static ShaderInstance chromaticModelShader;
    private static final ShaderStateShard CHROMATIC_MODEL_SHADER_SHARD =
            new ShaderStateShard(() -> chromaticModelShader);

    /** Vanilla rendertype_glint plus an alpha-test against a second sampler; see {@link #forMountArmorGlint}. */
    private static ShaderInstance glintCutoutShader;
    private static final ShaderStateShard GLINT_CUTOUT_SHADER_SHARD =
            new ShaderStateShard(() -> glintCutoutShader);

    /** Vanilla rendertype_glint declared for NEW_ENTITY, no camera-ward bias and no mask, so it can test EQUAL
     *  against a cutout base's own depth; see {@link #forElytraGlint}. */
    private static ShaderInstance glintModelShader;
    private static final ShaderStateShard GLINT_MODEL_SHADER_SHARD =
            new ShaderStateShard(() -> glintModelShader);

    /** Like {@link #glintCutoutShader} but cuts against the UNION of two mask textures (Sampler1 + Sampler2):
     *  the Epic Knights decoration counterpart, since dyeable EK decorations split their shape across a base +
     *  overlay file. The RT factory lives in the compat module and builds its shard off {@link #glintCutoutDecoShader()};
     *  see {@code EpicKnightsGlintRT#forDecorationGlintCutout}. */
    private static ShaderInstance glintCutoutDecoShader;
    public static ShaderInstance glintCutoutDecoShader() { return glintCutoutDecoShader; }

    // Post-Iris chromatic overlay (shader-pack path only): the in-phase chromatic program is replaced/hijacked
    // by an active pack, so under a pack the chromatic surfaces are re-rendered AFTER the pack finishes, through
    // this NEW_ENTITY overlay shader, onto an isolated target that is then composited back (chromaticComposite).
    // See GlowOutlineRenderer.queueChromaticModel / accumulateChromaticWorld / compositeChromaticWorld.
    private static ShaderInstance chromaticOverlayShader;
    private static ShaderInstance chromaticCompositeShader;
    private static final ShaderStateShard CHROMATIC_OVERLAY_SHADER_SHARD =
            new ShaderStateShard(() -> chromaticOverlayShader);
    private static final Map<String, RenderType> BY_CHROMATIC_OVERLAY = new RtCache();

    // Post-Iris TEXTURED-glint overlay (shader-pack path): the non-chromatic counterpart of the chromatic
    // overlay. A pack hijacks the in-phase glint_cutout program, so horse-armor textured glint re-renders
    // through this NEW_ENTITY overlay after the pack finishes and composites back (see forGlintEntityOverlay).
    private static ShaderInstance glintOverlayShader;
    private static final ShaderStateShard GLINT_OVERLAY_SHADER_SHARD =
            new ShaderStateShard(() -> glintOverlayShader);
    private static final Map<String, RenderType> BY_GLINT_OVERLAY = new RtCache();

    public static ShaderInstance chromaticCompositeShader() { return chromaticCompositeShader; }

    /** 1px-tall palette strips (one RGBA texel per colour) bound to Sampler1, keyed by colour set. Bounded
     *  LRU: a creative player cycling colours would otherwise pin one DynamicTexture per distinct set. */
    private static final int PALETTE_CACHE_CAP = 256;
    private static final Map<String, ResourceLocation> PALETTE_CACHE =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, ResourceLocation> eldest) {
                    if (size() <= PALETTE_CACHE_CAP) return false;
                    if (eldest.getValue() != null) {
                        try { Minecraft.getInstance().getTextureManager().release(eldest.getValue()); }
                        catch (Throwable ignored) {}
                    }
                    return true;
                }
            };
    private static ResourceLocation whiteTex; // 1×1 opaque-white dummy for Sampler0 (the shader never reads it)

    /** Mod-bus listener (hooked from {@link CustomGlintClientInit}): registers every core shader the glint
     *  pipeline owns. The procedural chromatic one is POSITION_TEX so it slots into the same fixed-buffer
     *  paths as the texture glints; each of the others declares the format its own draw path needs, noted
     *  at its registration below. A failure here leaves the matching shader field null and the {@code forX}
     *  factory returns null, so that path silently skips instead of crashing. */
    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("chromatic"),
                            DefaultVertexFormat.POSITION_TEX),
                    shader -> chromaticShader = shader);
            // Post-Iris overlay (NEW_ENTITY: replays captured armor/entity vertices) + its fullscreen composite.
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("chromatic_overlay"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> chromaticOverlayShader = shader);
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("chromatic_composite"),
                            DefaultVertexFormat.POSITION_TEX),
                    shader -> chromaticCompositeShader = shader);
            // Post-Iris textured-glint overlay (NEW_ENTITY: replays captured horse-armor vertices), reuses the
            // chromatic composite to blend over the pack image. See forGlintEntityOverlay.
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("glint_overlay"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> glintOverlayShader = shader);
            // Cutout glint for armor whose model is not its shape (horse armor); see forMountArmorGlint.
            // NEW_ENTITY, NOT POSITION_TEX: the glint must share the base armor's vertex pipeline so Sodium
            // builds both coplanar meshes through the same EntityRenderer.renderCuboid path (see the shader
            // header + forMountArmorGlint for the Sodium z-fight rationale).
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("glint_cutout"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> glintCutoutShader = shader);
            // Model glint (elytra): design only, NEW_ENTITY, no camera-ward bias, so it tests EQUAL against the
            // base's cutout depth and self-occludes overlapping wings instead of doubling. See forElytraGlint.
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("glint_model"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> glintModelShader = shader);
            // Decoration cutout (Epic Knights): design + UNION alpha-test against a base + overlay decoration
            // texture. Reuses glint_cutout's vertex program (same NEW_ENTITY format + camera-ward bias for
            // Sodium's renderCuboid path); only the fragment stage differs (two masks). See
            // EpicKnightsGlintRT#forDecorationGlintCutout.
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("glint_cutout_deco"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> glintCutoutDecoShader = shader);
            // Chromatic slick + armor-shape cutout for horse armor (procedural design, no PNG). NEW_ENTITY so it
            // rides Sodium's renderCuboid alongside the armor; see forMountChromaticGlint.
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("chromatic_cutout"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> chromaticCutoutShader = shader);
            // Model chromatic (elytra): procedural slick, NEW_ENTITY, no camera-ward bias, so it tests EQUAL
            // against the base's cutout depth and self-occludes overlapping wings. See forElytraChromaticGlint.
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("chromatic_model"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> chromaticModelShader = shader);
            // Atlased GUI item glint: one shared design atlas so every inventory glint icon batches into a
            // single draw. NEW_ENTITY so the item's own quads emit through putBulkData; colour/scroll/scale/
            // cell ride the vertex payload (a batched draw has no per-item uniform). See guiAtlasGlintRenderType.
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("gui_item_glint"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> guiItemGlintShader = shader);
        } catch (Exception e) {
            LOGGER.error("[{}/CustomGlint] failed to register glint core shaders", MOD_ID, e);
        }
    }

    /** 1×1 opaque-white texture for Sampler0 (declared by the shader pipeline but unused by the fsh). */
    private static ResourceLocation getWhiteTexture() {
        if (whiteTex != null) return whiteTex;
        NativeImage img = new NativeImage(1, 1, false);
        img.setPixelRGBA(0, 0, 0xFFFFFFFF);
        DynamicTexture dt = new DynamicTexture(img);
        ResourceLocation loc = CustomGlint.res("chromatic_white");
        Minecraft.getInstance().getTextureManager().register(loc, dt);
        whiteTex = loc;
        return loc;
    }

    private static String colorsKey(int[] colors) {
        StringBuilder sb = new StringBuilder(colors.length * 7);
        for (int c : colors) sb.append(Integer.toHexString(c)).append('_');
        return sb.isEmpty() ? "rainbow" : sb.toString();
    }

    /** Palette strip for Sampler1: width = max(1, colours), one opaque RGBA texel per colour (RGB only;
     *  the shader applies its own brightness). With no colours a 1px white keeps Sampler1 bound; the shader
     *  reads colour-count 0 and falls back to a full-spectrum rainbow, so the strip's contents go unused. */
    private static ResourceLocation getPaletteTexture(int[] colors) {
        String key = colorsKey(colors);
        ResourceLocation existing = PALETTE_CACHE.get(key);
        if (existing != null) return existing;
        int w = Math.max(1, colors.length);
        NativeImage img = new NativeImage(w, 1, false);
        for (int i = 0; i < w; i++) {
            int c = i < colors.length ? colors[i] : 0xFFFFFFFF;
            // NativeImage is ABGR little-endian; force opaque and keep RGB.
            int rgb = c & 0xFFFFFF;
            int abgr = 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
            img.setPixelRGBA(i, 0, abgr);
        }
        DynamicTexture dt = new DynamicTexture(img);
        ResourceLocation loc = CustomGlint.res("chromatic_palette/" + key);
        Minecraft.getInstance().getTextureManager().register(loc, dt);
        PALETTE_CACHE.put(key, loc);
        return loc;
    }

    private static float packSeed(int seed) {
        return (seed & 0xFFFF) / 256.0f; // 0..256 range; vsh forwards it, fsh derives a 2D offset from it
    }

    /** Render colour for an undyed (empty-palette) non-chromatic layer: white, so a blank trim's design
     *  stays visible without any dye being stored. Shared by the item/armor/elytra/entity glint loops. */
    public static final int[] WHITE_COLOR = { 0xFFFFFFFF };

    /** A colourless chromatic trim (the creative-tab template) renders this neutral white→grey→dark slick
     *  instead of the full-spectrum rainbow fallback, the recognisable "greyscale" look from 26.1.2. */
    private static final int[] CHROMATIC_EMPTY_PALETTE = { 0xFFFFFFFF, 0xFF8A8A8A, 0xFF3A3A3A };

    /** Resolve a chromatic layer's palette: its own colours, or the greyscale template when it has none. */
    public static int[] chromaticColors(int[] colors) {
        return colors.length == 0 ? CHROMATIC_EMPTY_PALETTE : colors;
    }

    /** Chromatic texture matrix: the 2D part scales the noise UV exactly like the texture glint (no scroll;
     *  the slick flows in-shader via GameTime); the spare column-2 slots carry the per-layer payload the
     *  immutable RenderType can't pass as a uniform. UV0.z is 0 so column 2 never affects {@code noiseCoord}. */
    private static void setChromaticMatrix(Layer layer, float scaleU, float scaleV, int colorCount) {
        Matrix4f m = TEX_MATRIX.get().translation(0.0f, 0.0f, 0.0f);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scaleU * layer.patternScale(), scaleV * layer.patternScale(), 1.0f);
        m.translate(-0.5f, -0.5f, 0.0f);
        m.m20((float) layer.speed());      // TextureMat[2][0] = morph speed
        m.m21((float) colorCount);         // TextureMat[2][1] = colour count (0 → rainbow)
        m.m23(packSeed(layer.seed()));     // TextureMat[2][3] = per-trim seed
        RenderSystem.setTextureMatrix(m);
    }

    /** Builds (or returns cached) a chromatic RenderType for one layer. {@code layering} matches the surface
     *  the glint draws on (NO_LAYERING for items / horse / entity, VIEW_OFFSET_Z_LAYERING for worn armor),
     *  exactly mirroring the texture-glint depth setup so the EQUAL_DEPTH_TEST pass lines up. */
    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          LayeringStateShard layering) {
        return chromaticRT(layer, tag, scaleU, scaleV, layering, VertexFormat.Mode.QUADS);
    }

    // {@code mode} lets a caller request a TRIANGLES-mode RT for renderers that draw a triangle list (Epic
    // Fight's skinned meshes) instead of vanilla QUADS. A QUADS RT fed a triangle stream shatters into facets.
    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          LayeringStateShard layering, VertexFormat.Mode mode) {
        return chromaticRT(layer, tag, scaleU, scaleV, layering, mode, false);
    }

    // {@code lateForShaders} routes a translucent-surface chromatic glint (slime outer shell) into the
    // OPAQUE_DECAL shader bucket with LEQUAL depth; see forHorseArmorGlint for the full slime rationale.
    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          LayeringStateShard layering, VertexFormat.Mode mode,
                                          boolean lateForShaders) {
        int[] colors = chromaticColors(layer.colors());
        final int colorCount = Math.min(colors.length, 8);   // 8 = the palette texels the chromatic fsh reads
        final ResourceLocation white = getWhiteTexture();
        final ResourceLocation palette = getPaletteTexture(colors);
        String key = tag + "|" + colorsKey(colors) + "|" + layer.speed() + "|" + layer.patternScale()
                + "|" + layer.seed() + "|" + scaleU + "|" + scaleV + "|" + mode + (lateForShaders ? "|late" : "");
        RenderType cached = BY_CHROMATIC.computeIfAbsent(key, k -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_chromatic|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    mode,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(CHROMATIC_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(white, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, white);
                                    RenderSystem.setShaderTexture(1, palette);
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderTexture(1, 0);
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            // Shell glint never writes depth; back-face CULL self-occludes the convex shell
                            // instead (see forHorseArmorGlint for the full rationale).
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(lateForShaders ? CULL : NO_CULL)
                            .setDepthTestState(lateForShaders ? LEQUAL_DEPTH_TEST : EQUAL_DEPTH_TEST)
                            // NO_LAYERING on the shell: the old VIEW_OFFSET_Z_LAYERING punch is pack-dependent,
                            // so some packs let the shell overdraw its glint. Opaque chromatic keeps its layering.
                            .setLayeringState(lateForShaders ? NO_LAYERING : layering)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_chromatic_texturing|" + k.hashCode(),
                                    () -> setChromaticMatrix(layer, scaleU, scaleV, colorCount), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            putCapturedFixedBuffer(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        if (lateForShaders) tagAsOpaqueDecalForShaders(cached);
        return cached;
    }

    /** Noise UV scale for armor / elytra / horse-armor / entity-body chromatic. Lower = bigger slick cells.
     *  The 26.1.2 value (8.0) made the pattern far too dense on models (a body part spans a large UV region,
     *  unlike a tiny atlas-sprite item), tiny/repeating even at 0.1x patternScale; dropped so the default
     *  patternScale reads as a large oil-slick and the slider tunes up from there. Items keep their own
     *  atlas-calibrated scale (atlasW/16), so they're unaffected. */
    private static final float CHROMATIC_MODEL_UV_SCALE = 0.5f;

    /** Flat item / 3D held-item chromatic glint (EQUAL depth, no polygon offset). isItem mirrors
     *  {@link #forGlint}'s atlas-calibrated scale so the slick density matches the texture glints. */
    public static RenderType forChromaticGlint(Data glint, int layerIdx, boolean isItem) {
        if (chromaticShader == null) return null;
        // Flat item: uvScale = atlasW/16 (per-axis atlasH/16) cancels the block-atlas sprite compression so a
        // 16px sprite spans patternScale UV units → DENSITY(7)·patternScale cells per icon, GUI + world alike
        // (26.1.2's value). 3D items keep uvScale 1.0. (Special BEWLR items, shield/trident, take the denser
        // special-item scale via forChromaticSpecialGlint instead, so applyGlint routes them there.)
        float scaleU = 1.0f, scaleV = 1.0f;
        if (isItem) {
            ensureAtlasDims();
            scaleU = cachedAtlasW / 16.0f;
            scaleV = cachedAtlasH / 16.0f;
        }
        return chromaticRT(glint.layers()[layerIdx], "item|" + isItem + "|L" + layerIdx, scaleU, scaleV, NO_LAYERING);
    }

    /** Noise scale for special 3D BEWLR items (trident/shield). These are much smaller than an armor surface,
     *  so the big-surface model scale ({@link #CHROMATIC_MODEL_UV_SCALE}) reads far too coarse on them; a
     *  higher scale gives a denser slick, and the patternScale slider tunes up from here. */
    private static final float CHROMATIC_SPECIAL_ITEM_UV_SCALE = 3.0f;

    /** In-phase chromatic glint for a special 3D BEWLR item (shield/trident): the special-item noise scale
     *  ({@link #CHROMATIC_SPECIAL_ITEM_UV_SCALE}), so it matches the shader-pack overlay
     *  ({@link #forChromaticSpecialGlintOverlay}): same slick on and off a pack. NO_LAYERING like the 3D item. */
    public static RenderType forChromaticSpecialGlint(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "special|L" + layerIdx,
                CHROMATIC_SPECIAL_ITEM_UV_SCALE, CHROMATIC_SPECIAL_ITEM_UV_SCALE, NO_LAYERING);
    }

    /** In-phase chromatic glint for a shield. The shield's foil buffer is sprite-atlas-wrapped (BEWLR), which
     *  compresses its UVs, so at the shared special-item scale the slick reads massively zoomed-in. Dividing by
     *  the sprite extent ({@link #ensureShieldScale}) tiles the slick across the shield model's real UVs, so it
     *  matches the (unwrapped) trident. */
    public static RenderType forChromaticShieldGlint(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        ensureShieldScale();
        return chromaticRT(glint.layers()[layerIdx], "shield|L" + layerIdx,
                CHROMATIC_SPECIAL_ITEM_UV_SCALE * shieldInvU, CHROMATIC_SPECIAL_ITEM_UV_SCALE * shieldInvV, NO_LAYERING);
    }

    /** Worn-armor chromatic glint (EQUAL depth + VIEW_OFFSET_Z_LAYERING, matching armorCutoutNoCull). */
    public static RenderType forChromaticArmorGlint(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "armor|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                VIEW_OFFSET_Z_LAYERING);
    }

    /** TRIANGLES-mode worn-armor chromatic glint, for renderers that draw armor through a triangle-list
     *  RenderType (Epic Fight runs {@code armorCutoutNoCull} through {@code getTriangulated}). Same
     *  {@code VIEW_OFFSET_Z_LAYERING} as {@link #forChromaticArmorGlint}; only the primitive mode differs. */
    public static RenderType forChromaticArmorGlintTriangles(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "armor|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                VIEW_OFFSET_Z_LAYERING, VertexFormat.Mode.TRIANGLES);
    }

    /**
     * Post-Iris chromatic overlay RenderType for worn armor / entity bodies (shader-pack path). Drawn during
     * the deferred drain into the offscreen chromatic target, NOT in-phase: NEW_ENTITY format (replays captured
     * {@code [x,y,z,u,v]} vertices), Sampler0 = the model texture (cutout alpha-test), Sampler1 = the palette,
     * Sampler2 = scene depth (bound by the drain). Occlusion + cutout are decided in-shader, so no GPU depth
     * test and no polygon offset; {@code NO_CULL} + the in-shader front-surface test keep only visible faces.
     * {@code mode} follows the captured topology (TRIANGLES for Epic Fight skinned armor, QUADS otherwise).
     */
    public static RenderType forChromaticArmorGlintOverlay(Data glint, int layerIdx, ResourceLocation modelTex,
            VertexFormat.Mode mode, float brightness) {
        return chromaticOverlayRT(glint, layerIdx, "armor_ov", CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                modelTex, mode, brightness);
    }

    /**
     * Post-Iris chromatic overlay for flat / quad items (held third-person, dropped, item frames). Sampler0 =
     * the block atlas (the item sprite's alpha carves the cutout, like the glow item silhouette), and the noise
     * scale cancels the atlas-sprite compression ({@code atlasW/16}, per-axis) so the slick density matches the
     * in-phase item chromatic. QUADS (baked item quads replayed as {@code [x,y,z,u,v]}).
     */
    public static RenderType forChromaticItemGlintOverlay(Data glint, int layerIdx) {
        ensureAtlasDims();
        return chromaticOverlayRT(glint, layerIdx, "item_ov", cachedAtlasW / 16.0f, cachedAtlasH / 16.0f,
                TextureAtlas.LOCATION_BLOCKS, VertexFormat.Mode.QUADS, 1.0f);
    }

    /** Post-Iris chromatic overlay for a special 3D item (trident/shield), tracing its own {@code tex}. Uses
     *  the denser special-item noise scale so a small item isn't a coarse blob (see the constant). */
    public static RenderType forChromaticSpecialGlintOverlay(Data glint, int layerIdx, ResourceLocation tex) {
        return chromaticOverlayRT(glint, layerIdx, "special_ov", CHROMATIC_SPECIAL_ITEM_UV_SCALE,
                CHROMATIC_SPECIAL_ITEM_UV_SCALE, tex, VertexFormat.Mode.QUADS, 1.0f);
    }

    private static RenderType chromaticOverlayRT(Data glint, int layerIdx, String tag, float scaleU, float scaleV,
            ResourceLocation surfaceTex, VertexFormat.Mode mode, float brightness) {
        if (chromaticOverlayShader == null) return null;
        Layer layer = glint.layers()[layerIdx];
        int[] colors = chromaticColors(layer.colors());
        final int colorCount = Math.min(colors.length, 8);   // 8 = the palette texels the chromatic fsh reads
        final ResourceLocation palette = getPaletteTexture(colors);
        final ResourceLocation tex = surfaceTex != null ? surfaceTex : getWhiteTexture();
        // The overlay shader multiplies the slick by ColorModulator.a, so a sub-1 brightness dims it. Used to
        // knock the slime shell's slick down (it composites over a translucent surface and reads too hot at 1.0);
        // opaque armor / items pass 1.0.
        String key = tag + "|" + layerKey(layer) + "|" + tex + "|" + mode + "|" + brightness;
        return BY_CHROMATIC_OVERLAY.computeIfAbsent(key, k -> RenderType.create(
                MOD_ID + ":custom_chromatic_overlay|" + k.hashCode(),
                DefaultVertexFormat.NEW_ENTITY,
                mode,
                1024,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(CHROMATIC_OVERLAY_SHADER_SHARD)
                        .setTextureState(new TextureStateShard(tex, false, false) {
                            @Override public void setupRenderState() {
                                RenderSystem.setShaderTexture(0, tex);
                                RenderSystem.setShaderTexture(1, palette);
                                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, brightness);
                            }
                            @Override public void clearRenderState() {
                                super.clearRenderState();
                                RenderSystem.setShaderTexture(1, 0);
                                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                            }
                        })
                        // Depth WRITE + LEQUAL against the target's own depth: where two chromatic pieces cover
                        // the same pixel (chestplate skirt over leggings waistband), the NEARER surface wins and
                        // draws once; without this both wrote and the additive composite doubled them into a
                        // bright seam whose winner flipped with the view angle. (Back faces also fail LEQUAL, a
                        // free second guard alongside the in-shader scene occlusion.) The target depth is cleared
                        // each frame in accumulateChromaticWorld. Scene-vs-world occlusion still rides Sampler2.
                        .setWriteMaskState(COLOR_DEPTH_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setLayeringState(NO_LAYERING)
                        // Straight write into the cleared isolated target (NOT additive) so it holds exactly the
                        // slick rgb + alpha=1 where drawn; the composite then adds that to the scene. Additive
                        // here left the target alpha near 0, so the SRC_ALPHA composite multiplied it to nothing.
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_chromatic_overlay_texturing|" + k.hashCode(),
                                () -> setChromaticMatrix(layer, scaleU, scaleV, colorCount),
                                RenderSystem::resetTextureMatrix))
                        .createCompositeState(false)));
    }

    /**
     * Post-Iris TEXTURED-glint overlay for horse armor (shader-pack path): the non-chromatic counterpart of
     * {@link #forChromaticArmorGlintOverlay}. A pack hijacks the in-phase {@code glint_cutout} program (its
     * Sampler1 armor-cutout binding is dropped, so every fragment discards and the glint vanishes), so the horse
     * model is captured and re-drawn through this overlay AFTER the pack composites, onto the shared chromatic
     * target. Sampler0 = the armor texture (cutout alpha), Sampler1 = the scrolled design, Sampler2 = scene depth
     * (bound by the drain). Occlusion + cutout are decided in-shader, so there is no GPU-depth-test dependence
     * and no polygon offset; the animated {@code frameColor} rides a per-key holder like {@link #forMountArmorGlint}.
     */
    public static RenderType forGlintEntityOverlay(Data glint, int layerIdx, float[] frameColor, int colorIdx,
                                                   ResourceLocation armorTex, VertexFormat.Mode mode) {
        if (glintOverlayShader == null) return null;
        Layer layer = glint.layers()[layerIdx];
        ResourceLocation design = layer.design();
        if (getTexture(design) == null) return null;
        String key = "glint_ov|" + layerKey(layer) + "|" + colorIdx + "|" + armorTex + "|" + mode;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        return BY_GLINT_OVERLAY.computeIfAbsent(key, k -> RenderType.create(
                MOD_ID + ":custom_glint_overlay|" + k.hashCode(),
                DefaultVertexFormat.NEW_ENTITY,
                mode,
                1024,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(GLINT_OVERLAY_SHADER_SHARD)
                        .setTextureState(new TextureStateShard(armorTex, false, false) {
                            @Override public void setupRenderState() {
                                RenderSystem.setShaderTexture(0, armorTex);           // cutout alpha
                                RenderSystem.setShaderTexture(1, getTexture(design)); // scrolled glint pattern
                                RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                            }
                            @Override public void clearRenderState() {
                                super.clearRenderState();
                                RenderSystem.setShaderTexture(1, 0);
                                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                            }
                        })
                        // Additive, no depth write, no GPU depth test: matches the in-phase forMountArmorGlint so
                        // multiple layers/colours SUM into the target instead of overwriting each other (the
                        // chromatic overlay's straight write + depth test left only the last layer, and clobbered
                        // a chromatic slick in a mixed glint). Cutout + occlusion are decided in-shader (Sampler0
                        // alpha + Sampler2 scene depth), so the GPU depth test isn't needed.
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(NO_DEPTH_TEST)
                        .setLayeringState(NO_LAYERING)
                        .setTransparencyState(GLINT_TRANSPARENCY)
                        .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_glint_overlay_texturing|" + k.hashCode(),
                                () -> setModelScrollMatrix(layer, colorIdx), RenderSystem::resetTextureMatrix))
                        .createCompositeState(false)));
    }

    /** Horse-armor / entity-body chromatic glint (EQUAL depth + NO_LAYERING, matching entityCutoutNoCull). */
    public static RenderType forChromaticEntityGlint(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "entity|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                NO_LAYERING);
    }

    /** TRIANGLES-mode entity-body chromatic glint, for renderers that draw through a triangle-list
     *  RenderType (Epic Fight patched meshes). A QUADS chromatic RT fed a triangle stream shatters. */
    public static RenderType forChromaticEntityGlintTriangles(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "entity|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                NO_LAYERING, VertexFormat.Mode.TRIANGLES);
    }

    /** Translucent-surface entity chromatic glint (slime outer shell): LEQUAL depth + OPAQUE_DECAL shader
     *  bucket so it survives the deferred translucent geometry under a pack. See {@link #forEntityGlintTranslucent}. */
    public static RenderType forChromaticEntityGlintTranslucent(Data glint, int layerIdx, boolean triangles) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "entity|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                NO_LAYERING, triangles ? VertexFormat.Mode.TRIANGLES : VertexFormat.Mode.QUADS, true);
    }

    /**
     * Removes {@code rt} from both the captured {@link #fixedBufferRegistry} and the live BufferSource's
     * {@code fixedBuffers}, closing each {@link ByteBufferBuilder} so its off-heap memory is freed.
     * Called on LRU eviction and resource reload. The old code discarded the {@code remove()} return
     * value, leaking one native buffer per cached RenderType on every reload. Public so compat modules
     * (e.g. {@code EpicKnightsGlintRT}) can release their own cached RTs the same way.
     */
    public static void evictRt(RenderType rt) {
        if (rt == null) return;
        if (fixedBufferRegistry != null) {
            ByteBufferBuilder b = fixedBufferRegistry.remove(rt);
            if (b != null) b.close();
        }
        try {
            SequencedMap<RenderType, ByteBufferBuilder> live =
                    Minecraft.getInstance().renderBuffers().bufferSource().fixedBuffers;
            if (live != null) {
                ByteBufferBuilder b2 = live.remove(rt);
                if (b2 != null) b2.close();
            }
        } catch (Throwable ignored) {
            // immutable fixedBuffers (Iris/Sodium); never received our buffer to begin with
        }
    }

    /** Inserts {@code rt}'s persistent buffer into the captured vanilla {@code fixedBufferRegistry}.
     *  That map is the vanilla (mutable) one captured at {@code RenderBuffers.<init>}, so this normally
     *  succeeds; the try/catch mirrors {@link #registerLiveFixedBuffer} purely as belt-and-suspenders, so
     *  that if it is ever an immutable Iris/Sodium map the put degrades to a no-op (the shader path doesn't
     *  pull from these buffers) instead of crashing the render thread. Closes the buffer on the immutable
     *  path so it isn't leaked. */
    public static void putCapturedFixedBuffer(RenderType rt) {
        if (rt == null || fixedBufferRegistry == null) return;
        ByteBufferBuilder buf = new ByteBufferBuilder(rt.bufferSize());
        try {
            fixedBufferRegistry.put(rt, buf);
        } catch (UnsupportedOperationException uoe) {
            buf.close(); // immutable fixedBuffers (Iris/Sodium); forward/shader path handles these RTs
        }
    }

    /**
     * Registers {@code rt}'s persistent buffer into the live BufferSource's {@code fixedBuffers}
     * when absent. Iris and Sodium swap the vanilla BufferSource for one whose {@code fixedBuffers}
     * is an IMMUTABLE fastutil map: its {@code put} throws {@link UnsupportedOperationException}
     * (the inline {@code live.put(...)} this replaced crashed armor/entity/item/outline glint the
     * instant Iris was installed: {@code Object2ObjectFunction.put} → UOE). Swallow that: under an
     * active shader pack our RTs render through the forward/shader path, which never pulls from this
     * BufferSource's fixed buffers, so the registration isn't needed there anyway. The vanilla
     * {@code fixedBufferRegistry} (captured in RenderBuffersMixin) still holds the RT for the
     * no-shader path.
     */
    public static void registerLiveFixedBuffer(RenderType rt) {
        if (rt == null) return;
        MultiBufferSource.BufferSource bs = Minecraft.getInstance().renderBuffers().bufferSource();
        SequencedMap<RenderType, ByteBufferBuilder> live = bs.fixedBuffers;
        if (live == null || live.containsKey(rt)) return;
        try {
            live.put(rt, new ByteBufferBuilder(rt.bufferSize()));
        } catch (UnsupportedOperationException uoe) {
            // immutable fixedBuffers (Iris/Sodium); forward/shader path handles these RTs
        }
    }

    // Per-Layer memoized cache-key fragment. The costly part of every forX key is the
    // design.toString() + Arrays.toString(colors) + float/int concatenation, rebuilt every frame
    // even on a cache hit. Layer is an immutable data component that stays identity-stable across
    // frames for an unchanged stack, so memoize the fragment by identity. Render-thread only;
    // bounded (clears past 4096 distinct layers) and dropped on resource reload.
    private static final Map<Layer, String> LAYER_KEY_CACHE = new IdentityHashMap<>();

    private static String layerKey(Layer l) {
        String k = LAYER_KEY_CACHE.get(l);
        if (k == null) {
            if (LAYER_KEY_CACHE.size() > 4096) LAYER_KEY_CACHE.clear();
            // interpolate + simultaneous belong here even though they touch no render state: the model RTs
            // read their tint from a per-key float[] holder at FLUSH time (bindGlintTexture), and both fields
            // change which colour a caller writes into it. Two layers (or two entities' layers) alike in
            // everything else but one of these would otherwise share a key, share the holder, and both draw
            // with whichever wrote last.
            k = l.design() + "|" + Arrays.toString(l.colors()) + "|" + l.speed()
                    + "|" + l.patternScale() + "|" + l.scrollDir() + "|" + l.scrollOffset()
                    + "|" + l.interpolate() + "|" + l.simultaneous();
            LAYER_KEY_CACHE.put(l, k);
        }
        return k;
    }

    /**
     * Shared builder for the two POSITION_TEX model-space textured glints (worn armor and horse/entity body).
     * Both draw the design on Sampler0 through {@code rendertype_glint} with the model-UV scroll matrix and cache
     * a persistent fixed buffer; they differ only in the depth / layering / write-mask their coplanar neighbour
     * demands (each caller's TRIED comment explains its own choice), the primitive {@code mode}, the RT name, and
     * the late-for-shaders tag. Mirrors {@link #chromaticRT} on the chromatic side. The caller owns the cache map
     * probe, the colour {@code holder}, {@link #registerLiveFixedBuffer}, and any {@link #tagAsOpaqueDecalForShaders};
     * this method only builds + captures the RenderType.
     */
    private static RenderType modelGlintRT(Layer layer, int colorIdx, String namePrefix, String key,
            VertexFormat.Mode mode, float[] holder, DepthTestStateShard depthTest,
            LayeringStateShard layering, WriteMaskStateShard writeMask, CullStateShard cull, float brightness,
            String texturingName) {
        ResourceLocation tex = layer.design();
        RenderType rt = RenderType.create(
                namePrefix + "|" + key.hashCode(),
                DefaultVertexFormat.POSITION_TEX,
                mode,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_GLINT_SHADER)
                        .setTextureState(new TextureStateShard(tex, false, false) {
                            @Override public void setupRenderState() {
                                bindGlintTexture(tex, holder, brightness);
                            }
                            @Override public void clearRenderState() {
                                super.clearRenderState();
                                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                            }
                        })
                        .setWriteMaskState(writeMask)
                        .setCullState(cull)
                        .setDepthTestState(depthTest)
                        .setLayeringState(layering)
                        .setTransparencyState(GLINT_TRANSPARENCY)
                        .setTexturingState(new TexturingStateShard(texturingName,
                                () -> setModelScrollMatrix(layer, colorIdx), RenderSystem::resetTextureMatrix))
                        .createCompositeState(false));
        putCapturedFixedBuffer(rt);
        return rt;
    }

    public static RenderType forArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        return forArmorGlint(glint, layerIdx, frameColor, colorIdx, VertexFormat.Mode.QUADS);
    }

    /** TRIANGLES-mode worn-armor glint, for renderers that draw armor through a triangle-list RenderType
     *  (Epic Fight runs {@code armorCutoutNoCull} through {@code EpicFightRenderTypes.getTriangulated}). Keeps
     *  {@code forArmorGlint}'s EQUAL + {@code VIEW_OFFSET_Z_LAYERING} depth so the glint aligns with the
     *  offset-written armor depth; only the primitive mode differs (a QUADS RT fed a triangle stream shatters
     *  into facets). */
    public static RenderType forArmorGlintTriangles(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        return forArmorGlint(glint, layerIdx, frameColor, colorIdx, VertexFormat.Mode.TRIANGLES);
    }

    public static RenderType forArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx,
            VertexFormat.Mode mode) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        // Key MUST include the primitive mode: a QUADS and a TRIANGLES RT for the same layer/color are
        // distinct fixed buffers; collapsing them would trip Mixin's "Duplicate delegates" guard.
        String key = "armor|" + layerKey(layer) + "|" + colorIdx + "|" + mode;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_ARMOR_GLINT.computeIfAbsent(key, k ->
                // TRIED: EQUAL_DEPTH_TEST (attempt 1: shared buffer only, no fixedBufferRegistry;
                // attempt 2: immediate BufferBuilder + bs.endBatch() pre-flush before glint render;
                // attempt 3: rename type to "~customglint:..." so it sorts after
                //   "minecraft:armor_cutout_no_cull" in fixedBuffers flush order, theory being
                //   armor depth wasn't written yet, all three → glint completely invisible)
                // LEQUAL required for visibility but bleeds through transparent cutout holes.
                // Root cause of attempt 1-3 failure: armorCutoutNoCull itself uses
                // VIEW_OFFSET_Z_LAYERING (polygonOffset -1,-10), writing depth as D-ε. All prior
                // EQUAL attempts also removed VIEW_OFFSET_Z_LAYERING, so the glint tested at raw
                // D while the buffer held D-ε, they never matched. Fix: EQUAL + keep
                // VIEW_OFFSET_Z_LAYERING so the glint also tests at D-ε, matching exactly.
                modelGlintRT(layer, colorIdx, MOD_ID + ":custom_armor_glint", k, mode, holder,
                        EQUAL_DEPTH_TEST, VIEW_OFFSET_Z_LAYERING, COLOR_WRITE, NO_CULL, 1.0f,
                        MOD_ID + ":custom_armor_glint_texturing"));
        registerLiveFixedBuffer(cached);
        return cached;
    }

    /**
     * Entity body glint (pigs, cows, zombies, … anything rendered through entityCutoutNoCull).
     * Aliases {@link #forHorseArmorGlint}: same render-state requirements (EQUAL depth + NO_LAYERING),
     * since LivingEntityRenderer body draws have no polygon offset. Separate method only for caller clarity.
     */
    public static RenderType forEntityGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx);
    }

    /** TRIANGLES-mode entity-body glint, for renderers that draw through a triangle-list RenderType (Epic
     *  Fight patched meshes). A QUADS glint RT fed a triangle vertex stream reassembles the primitives wrong
     *  and shatters into facets; matching the primitive mode assembles it correctly. */
    public static RenderType forEntityGlintTriangles(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx, VertexFormat.Mode.TRIANGLES);
    }

    /**
     * Entity glint for a TRANSLUCENT base surface (the slime's outer shell). Under a shader pack (Iris)
     * translucent entity geometry is deferred to a later pass than our fixed glint buffer would otherwise
     * flush, and the shell's depth is re-sorted per frame, so an EQUAL-depth glint tests against the wrong
     * reference (flicker / dropout) or the shell paints over it entirely (the glint vanishes). This variant
     * is a DISTINCT RT with LEQUAL depth tagged into the OPAQUE_DECAL bucket, so it flushes right after the
     * opaque pass but BEFORE the translucent shell, testing against stable opaque depth. A no-op without a
     * shader pack; opaque entity glint keeps its own EQUAL instance so its working ordering is unchanged.
     */
    public static RenderType forEntityGlintTranslucent(Data glint, int layerIdx, float[] frameColor, int colorIdx,
                                                       boolean triangles) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx,
                triangles ? VertexFormat.Mode.TRIANGLES : VertexFormat.Mode.QUADS, true);
    }

    /**
     * Opaque entity-body glint tagged for late shader render. Off-pack the plain {@link #forEntityGlint} EQUAL
     * path carves the cutout against the body's own written depth (COLOR_WRITE, no depth write); under a shader
     * pack our fixed glint buffer flushes before Iris resolves opaque depth, so the EQUAL test reads incomplete
     * depth and the glint leaks onto the body's transparent-texel regions. Tagging it into the OPAQUE_DECAL
     * bucket defers the flush until opaque depth is stable, restoring the cutout. Same EQUAL + NO_LAYERING +
     * COLOR_WRITE + NO_CULL state as the opaque path; only the shader tag differs, so a distinct RT instance.
     */
    public static RenderType forEntityGlintShaderCutout(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "entShaderCut|" + layerKey(layer) + "|" + colorIdx + "|" + layerIdx;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_HORSE_ARMOR_GLINT.computeIfAbsent(key, k ->
                modelGlintRT(layer, colorIdx, MOD_ID + ":custom_entity_glint_shader_cutout", k,
                        VertexFormat.Mode.QUADS, holder, EQUAL_DEPTH_TEST, NO_LAYERING, COLOR_WRITE, NO_CULL, 1.0f,
                        MOD_ID + ":custom_entity_glint_shader_cutout_texturing"));
        registerLiveFixedBuffer(cached);
        tagAsOpaqueDecalForShaders(cached);
        return cached;
    }

    // Colour boost for the translucent slime-shell glint under a shader pack: the OPAQUE_DECAL glint draws
    // before the shell, which then blends over it and roughly halves it, so lift it back toward full.
    private static final float SHELL_GLINT_BRIGHTNESS = 1.9f;

    // Horse armor uses entityCutoutNoCull (no polygon offset / no VIEW_OFFSET_Z_LAYERING).
    // forArmorGlint uses EQUAL + VIEW_OFFSET_Z_LAYERING; wrong offset → invisible on horses.
    // This variant keeps EQUAL + NO_LAYERING so depth matches, and scale 1.0 matches forArmorGlint visually.
    public static RenderType forHorseArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx, VertexFormat.Mode.QUADS, false);
    }

    public static RenderType forHorseArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx,
                                                VertexFormat.Mode mode) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx, mode, false);
    }

    public static RenderType forHorseArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx,
                                                VertexFormat.Mode mode, boolean lateForShaders) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "horse|" + layerKey(layer) + "|" + colorIdx + "|" + layerIdx + "|" + mode
                + (lateForShaders ? "|late" : "");
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_HORSE_ARMOR_GLINT.computeIfAbsent(key, k ->
                modelGlintRT(layer, colorIdx, MOD_ID + ":custom_horse_armor_glint", k, mode, holder,
                        // Opaque entity glint tests EQUAL (matches the body's own depth). The translucent
                        // slime-shell variant tests LEQUAL against stable opaque depth (OPAQUE_DECAL flushes
                        // before the shell), so shell front faces pass and anything behind a nearer opaque
                        // body is occluded.
                        lateForShaders ? LEQUAL_DEPTH_TEST : EQUAL_DEPTH_TEST,
                        // NO_LAYERING on both. The old shell path punched depth toward the camera with
                        // VIEW_OFFSET_Z_LAYERING to make the shell fail LEQUAL at the glint texels, but that
                        // projection nudge is honoured by some shader packs and ignored by others; on the packs
                        // that ignore it the shell paints over its own glint and only the inner-body glint shows
                        // through. Drop it so the state is pack-independent.
                        NO_LAYERING,
                        // COLOR_WRITE on both: the shell glint never writes depth, so no pack can act on a
                        // depth value and the base surface stays byte-identical to the no-glint case. Back-face
                        // CULL below replaces the depth write's self-occlusion.
                        COLOR_WRITE,
                        // The slime shell is one convex cube, so dropping its back faces IS its self-occlusion:
                        // exact, view-independent, and needs no depth. Opaque glint keeps NO_CULL (its EQUAL
                        // test already self-occludes against the body's depth).
                        lateForShaders ? CULL : NO_CULL,
                        // OPAQUE_DECAL flushes the shell glint BEFORE the translucent shell, so the shell then
                        // alpha-blends over it and attenuates it (~half of the additive glint survives). Boost
                        // the shell path's colour to compensate; the opaque path draws over solid geometry and
                        // needs no lift. Tunable eyeball constant.
                        lateForShaders ? SHELL_GLINT_BRIGHTNESS : 1.0f,
                        MOD_ID + ":custom_horse_armor_glint_texturing"));
        registerLiveFixedBuffer(cached);
        if (lateForShaders) tagAsOpaqueDecalForShaders(cached);
        return cached;
    }

    /**
     * Cutout glint for armor whose MODEL is not its SHAPE: HorseArmorLayer renders the whole horse mesh with
     * the armor texture, so only that texture's alpha says which texels are armor. {@code armorTex} rides
     * Sampler1 and the glint_cutout shader alpha-tests it at the raw model UV, discarding on the same 0.1
     * cutoff rendertype_entity_cutout_no_cull used to draw the base armor. The cutout is exact and
     * per-fragment, in the same draw as the glint.
     *
     * <p>Two independent defects live on this path. They have separate causes and separate fixes; keep both.
     *
     * <p>CUTOUT, and the temporal flicker it used to cause. The cutout used to be a stencil-mask pass
     * (since removed). It was the sole reason anything here
     * called RenderTarget.enableStencil(), and on NeoForge that reformats the MAIN target's depth from
     * GL_DEPTH_COMPONENT to a packed GL_DEPTH32F_STENCIL8 (NeoForgeConfig useCombinedDepthStencilAttachment
     * defaults false, so it binds to GL_DEPTH_ATTACHMENT and GL_STENCIL_ATTACHMENT as two attachment points
     * on one texture). The mask then did a masked full-screen glClear(GL_STENCIL_BUFFER_BIT) on that packed
     * surface once per horse, mid-pass: a read-modify-write of every depth+stencil texel in the frame. That
     * was a whole-glint blink, only on horses (nothing else enabled the stencil) and only without a pack
     * (Iris binds a stencil-less gbuffer, so the ops no-op). The per-fragment shader cutout removes it: no
     * enableStencil, no glClear, no stencil at all.
     *
     * <p>OCCLUSION, and the coplanar z-fight it must not cause. HorseArmorLayer draws the base armor through
     * entityCutoutNoCull with no polygon offset, and HorseModel bakes the armor coplanar with the horse body
     * on the torso and neck, so EQUAL there is a per-pixel coin flip: the speckle. The glint is biased proud to
     * win the tie, but the bias is in the glint_cutout VERTEX SHADER (gl_Position), NOT a layering shard.
     * glPolygonOffset and VIEW_OFFSET both fixed this in vanilla and both vanished under Sodium, which
     * reimplements entity rendering (its own ModelCuboid vertex path) and drops flush-time GL / modelview
     * layering state. A gl_Position bias is what the rasterizer depth-tests, so nothing can drop it. This RT
     * therefore uses NO_LAYERING and LEQUAL: the biased glint sits proud of the un-biased base armor and wins,
     * and its far faces still fail LEQUAL behind the body.
     *
     * <p>The bias is necessary but not sufficient under Sodium: a bias only arbitrates a tie cleanly when both
     * meshes are the SAME geometry. The base armor draws NEW_ENTITY, which Sodium claims for renderCuboid; while
     * this RT was POSITION_TEX the glint fell to Sodium's vanilla-fallback path (format mismatch), so the two
     * coplanar meshes came off DIFFERENT vertex pipelines and the tie stayed non-deterministic despite the bias
     * (it resolves cleanly without Sodium, where both sides share one pipeline).
     * So this RT now also declares NEW_ENTITY, routing the glint through renderCuboid alongside the armor: same
     * pipeline, bit-identical coplanar corners, and the +0.01 bias is the only remaining delta. See the format
     * arg + the glint_cutout shader header for the TRIED detail.
     */
    public static RenderType forMountArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx,
                                                ResourceLocation armorTex) {
        if (glintCutoutShader == null) return null;
        Layer layer = glint.layers()[layerIdx];
        ResourceLocation design = layer.design();
        if (getTexture(design) == null) return null;
        String key = "mountcut|" + layerKey(layer) + "|" + colorIdx + "|" + layerIdx + "|" + armorTex;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_MOUNT_ARMOR_GLINT.computeIfAbsent(key, k -> {
            // TRIED (2026-07-18, Sodium coplanar z-fight, shaders OFF): NEW_ENTITY, not POSITION_TEX. Sodium's
            // EntityRenderer.renderCuboid only claims a draw when the buffer format == EntityVertex.FORMAT
            // (== NEW_ENTITY); on a mismatch VertexConsumerUtils.convertOrLog returns null and the cube falls to
            // the vanilla per-vertex path. With POSITION_TEX the base horse armor (entity_cutout_no_cull,
            // NEW_ENTITY) went through renderCuboid while this glint went through vanilla compile, so the two
            // coplanar meshes were built by DIFFERENT pipelines under Sodium and the shader's camera-ward bias
            // could not deterministically win the tie (clean without Sodium, same pipeline both sides). Matching
            // NEW_ENTITY puts the glint back on the identical renderCuboid path as the armor. The glint_cutout
            // shader reads only Position+UV0; the other NEW_ENTITY attributes are written by compile and ignored.
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_mount_armor_glint_cutout|" + k.hashCode(),
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(GLINT_CUTOUT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(design, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, getTexture(design));
                                    RenderSystem.setShaderTexture(1, armorTex);
                                    RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderTexture(1, 0);
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            // LEQUAL, and the camera-ward bias that beats the coplanar tie lives in the
                            // glint_cutout VERTEX SHADER (gl_Position), NOT in a layering shard. The base armor
                            // is coplanar with the horse body on the torso and neck, so the glint must sit proud
                            // or it hatches per pixel. glPolygonOffset AND the VIEW_OFFSET modelview scale both
                            // work in vanilla, but Sodium reimplements entity rendering (its own ModelCuboid
                            // vertex path) and drops flush-time GL / modelview layering state, so both biases
                            // vanished under Sodium and the hatch returned (the "flickers only with Sodium"
                            // report). A bias baked into gl_Position cannot be dropped: it is what the rasterizer
                            // depth-tests. So this RT carries no layering; the shader owns the offset. LEQUAL
                            // because the biased glint sits proud of the un-biased base armor and must win.
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setLayeringState(NO_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_mount_armor_glint_cutout_texturing",
                                    () -> setModelScrollMatrix(layer, colorIdx), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            putCapturedFixedBuffer(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    /**
     * Elytra glint. Unlike horse armor (whose glint is coplanar with a SEPARATE horse-body mesh and so must sit
     * proud with a bias + LEQUAL), the elytra's glint relates only to its own base mesh, which is drawn cutout.
     * So it tests EQUAL against that base's own depth: only the nearest surface's glint matches, which
     * self-occludes the two folded wings where they overlap at the center instead of additively doubling the
     * seam. A biased LEQUAL glint (forMountArmorGlint) can't self-occlude there because the wings sit well within
     * the bias distance, so both pass and stack.
     *
     * <p>NEW_ENTITY so the glint rides Sodium's renderCuboid alongside the base and quantizes identically (what
     * lets EQUAL match under Sodium, the reason forArmorGlint's POSITION_TEX flickered there), and
     * VIEW_OFFSET_Z_LAYERING to match the base armorCutoutNoCull offset (dropped under Sodium for both, so they
     * stay equal). The glint_model shader carries no camera-ward bias, precisely so EQUAL can match; no Sampler1
     * mask, since the EQUAL test already clips the glint to the base's opaque texels.
     */
    public static RenderType forElytraGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        if (glintModelShader == null) return null;
        Layer layer = glint.layers()[layerIdx];
        ResourceLocation design = layer.design();
        if (getTexture(design) == null) return null;
        String key = "elytra|" + layerKey(layer) + "|" + colorIdx + "|" + layerIdx;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_MOUNT_ARMOR_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_elytra_glint|" + k.hashCode(),
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(GLINT_MODEL_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(design, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, getTexture(design));
                                    RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            // EQUAL against the base elytra's own cutout depth (same VIEW_OFFSET), so only the
                            // nearest wing's glint matches and the overlapping far wing fails, order-independently.
                            // No shader bias, unlike forMountArmorGlint, precisely so this EQUAL test can match.
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_elytra_glint_texturing",
                                    () -> setModelScrollMatrix(layer, colorIdx), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            putCapturedFixedBuffer(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    /** Horse-armor chromatic slick: the {@link #forMountChromaticGlint chromatic} counterpart of the cutout
     *  {@link #forMountArmorGlint}. Procedural design (no PNG), so it uses the chromatic_cutout shader with the
     *  palette on Sampler1 and the armor texture on Sampler2 for the shape cutout. Same coplanar depth setup as
     *  the cutout glint (LEQUAL + NO_LAYERING + the +0.01 gl_Position bias in the shader) and NEW_ENTITY so it
     *  rides Sodium's renderCuboid alongside the base armor. */
    public static RenderType forMountChromaticGlint(Data glint, int layerIdx, ResourceLocation armorTex) {
        if (chromaticCutoutShader == null) return null;
        Layer layer = glint.layers()[layerIdx];
        int[] colors = chromaticColors(layer.colors());
        final int colorCount = Math.min(colors.length, 8);   // 8 = the palette texels the chromatic fsh reads
        final ResourceLocation white = getWhiteTexture();
        final ResourceLocation palette = getPaletteTexture(colors);
        String key = "mountchroma|" + colorsKey(colors) + "|" + layer.speed() + "|" + layer.patternScale()
                + "|" + layer.seed() + "|" + layerIdx + "|" + armorTex;
        RenderType cached = BY_MOUNT_ARMOR_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_mount_chromatic_cutout|" + k.hashCode(),
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(CHROMATIC_CUTOUT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(white, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, white);
                                    RenderSystem.setShaderTexture(1, palette);
                                    RenderSystem.setShaderTexture(2, armorTex);
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderTexture(1, 0);
                                    RenderSystem.setShaderTexture(2, 0);
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            // Same coplanar depth setup as forMountArmorGlint: LEQUAL, NO_LAYERING, the bias is in
                            // the chromatic_cutout VERTEX SHADER (gl_Position), so it survives Sodium.
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setLayeringState(NO_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_mount_chromatic_cutout_texturing|" + k.hashCode(),
                                    () -> setChromaticMatrix(layer, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE, colorCount),
                                    RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            putCapturedFixedBuffer(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    /** Elytra chromatic slick: the {@link #forElytraGlint chromatic} counterpart. Like forElytraGlint it tests
     *  EQUAL against the base elytra's own cutout depth (NEW_ENTITY for Sodium, VIEW_OFFSET to match the base,
     *  the chromatic_model shader with no camera-ward bias), so the two folded wings self-occlude at the center
     *  instead of the LEQUAL bias of {@link #forMountChromaticGlint} letting both overlapping faces double. */
    public static RenderType forElytraChromaticGlint(Data glint, int layerIdx) {
        if (chromaticModelShader == null) return null;
        Layer layer = glint.layers()[layerIdx];
        int[] colors = chromaticColors(layer.colors());
        final int colorCount = Math.min(colors.length, 8);   // 8 = the palette texels the chromatic fsh reads
        final ResourceLocation white = getWhiteTexture();
        final ResourceLocation palette = getPaletteTexture(colors);
        String key = "elytrachroma|" + colorsKey(colors) + "|" + layer.speed() + "|" + layer.patternScale()
                + "|" + layer.seed() + "|" + layerIdx;
        RenderType cached = BY_MOUNT_ARMOR_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_elytra_chromatic|" + k.hashCode(),
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(CHROMATIC_MODEL_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(white, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, white);
                                    RenderSystem.setShaderTexture(1, palette);
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderTexture(1, 0);
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            // EQUAL against the base elytra's cutout depth (same VIEW_OFFSET), no shader bias, so
                            // only the nearest wing's slick matches and the overlapping far wing fails.
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_elytra_chromatic_texturing|" + k.hashCode(),
                                    () -> setChromaticMatrix(layer, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE, colorCount),
                                    RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            putCapturedFixedBuffer(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    public static RenderType forGlint(Data glint, int layerIdx, float[] frameColor, boolean isItem, int colorIdx) {
        // isItem=true → flat item model (sword, tool, etc.) → scale 8.0 matches vanilla glint().
        // isItem=false → 3D entity model (trident, etc.) → 1.0 gives visible pattern detail;
        // vanilla entityGlint() uses 0.16 but that tiles too infrequently for custom designs.
        // 1024x512 is the atlas the 8.0 was tuned against, and it is NOT square, so the two axes are
        // calibrated separately: one shared factor stretches a design into diamonds on a taller atlas.
        ensureAtlasDims();
        float scaleU = isItem ? (8.0f * cachedAtlasW / 1024.0f) : 1.0f;
        float scaleV = isItem ? (8.0f * cachedAtlasH / 512.0f) : 1.0f;
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = layerKey(layer) + "|" + isItem + "|" + layer.interpolate() + "|" + colorIdx + "|" + layerIdx;
        // One map probe carries both the RenderType and the colour holder its setup closure reads each flush.
        ItemGlintEntry entry = BY_GLINT_ITEM.computeIfAbsent(key, k -> new ItemGlintEntry());
        System.arraycopy(frameColor, 0, entry.color, 0, 4);
        if (entry.rt == null) {
            ResourceLocation tex = layer.design();
            final float[] holder = entry.color;
            entry.rt = RenderType.create(
                    MOD_ID + ":custom_glint|" + key.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_GLINT_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    bindGlintTexture(tex, holder, 1.0f);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_glint_texturing",
                                    () -> setItemScrollMatrix(layer, colorIdx, scaleU, scaleV), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            putCapturedFixedBuffer(entry.rt);
        }
        registerLiveFixedBuffer(entry.rt);
        return entry.rt;
    }

    public static int computeAnimatedColor(Data glint, int layerIdx) {
        return computeAnimatedColor(glint, layerIdx, 0.0f);
    }

    /** As {@link #computeAnimatedColor(Data, int)} but shifted by {@code phaseFraction} of a full colour loop.
     *  The glow OUTLINE ring passes {@link #GLOW_RING_PHASE_OFFSET} so it lands on a different point of the
     *  cycle than the item's own animated tint, so you see two colours at once instead of one. */
    public static int computeAnimatedColor(Data glint, int layerIdx, float phaseFraction) {
        if (glint == null || layerIdx < 0 || layerIdx >= glint.layers().length) return 0xFFFFFFFF;
        Layer layer = glint.layers()[layerIdx];
        int[] colors = layer.colors();
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
        // Wall-clock ticks, NOT game time: a screen pausing the game (singleplayer) freezes game time, which
        // sticks the colour on one point of the cycle. A two-colour glint with a near-invisible colour (the
        // layer tear's yellow/black) then freezes blank in a menu; wall-clock keeps it cycling like it does
        // in the running world.
        long gameTime = Util.getMillis() / 50L;
        return sampleColorCycle(colors, gameTime, (TICKS_PER_COLOR * colors.length) / layer.speed(),
                layer.interpolate(), phaseFraction);
    }

    /** Phase offset (fraction of a full colour loop) applied to the glow OUTLINE ring so it never shows the
     *  same colour as the item's own animated tint at the same instant. Half a cycle = maximum contrast: when
     *  the inner tint is on one colour, the ring is on the opposite side of the loop, so a multi-colour glow
     *  shows two different colours at once instead of one. Mirrors the 1.20.1 GLOW_RING_PHASE_OFFSET. */
    public static final float GLOW_RING_PHASE_OFFSET = 0.5f;

    /** Animates through an int[] color array using game time. Default speed=1, interpolate=true. */
    public static int computeAnimatedGlowColor(int[] colors) {
        return computeAnimatedGlowColor(colors, 1.0f, true);
    }

    /** As above at a chosen {@code speed} (a higher speed cycles faster, mirroring the glint layer speed) and
     *  either blending between colors ({@code interpolate}) or stepping hard between them. Uses GAME time: the
     *  glow OUTLINE (ring/halo) animates on this so it matches the in-world clock. */
    public static int computeAnimatedGlowColor(int[] colors, float speed, boolean interpolate) {
        return computeAnimatedGlowColor(colors, speed, interpolate, 0.0f);
    }

    /** As above but shifted by {@code phaseFraction} of a full colour loop (see {@link #GLOW_RING_PHASE_OFFSET}). */
    public static int computeAnimatedGlowColor(int[] colors, float speed, boolean interpolate, float phaseFraction) {
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        return computeAnimatedGlowColorAt(colors, gameTime, speed, interpolate, phaseFraction);
    }

    /** WALL-CLOCK variant of {@link #computeAnimatedGlowColor}, ticked off {@code Util.getMillis} instead of
     *  game time. The trim texture's tinted edge ("white × rgb") uses this so it runs on a DIFFERENT clock than
     *  the game-time glow outline: the edge and the ring/halo show different colours at the same moment. */
    public static int computeAnimatedGlowColorGui(int[] colors, float speed, boolean interpolate) {
        return computeAnimatedGlowColorAt(colors, Util.getMillis() / 50L, speed, interpolate, 0.0f);
    }

    private static int computeAnimatedGlowColorAt(int[] colors, long timeTicks, float speed, boolean interpolate, float phaseFraction) {
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
        if (!Float.isFinite(speed) || speed <= 0) speed = 1.0f;
        return sampleColorCycle(colors, timeTicks, (TICKS_PER_COLOR * colors.length) / speed,
                interpolate, phaseFraction);
    }

    /** One colour holds for a second at speed 1 (20 ticks); speed divides that, so speed 2 is half a second. */
    private static final float TICKS_PER_COLOR = 20.0f;

    /** Samples a colour cycle: {@code timeTicks} wrapped into {@code totalTicks}, shifted by
     *  {@code phaseFraction} of a full loop, then either the hard step or a blend into the next colour.
     *  Callers pass {@code totalTicks} already computed so each keeps its own tick source and its own speed
     *  handling (the glint path has no speed guard, the glow path clamps a bad speed to 1). */
    private static int sampleColorCycle(int[] colors, long timeTicks, float totalTicks,
                                        boolean interpolate, float phaseFraction) {
        float t = (timeTicks % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        t = ((t + phaseFraction * colors.length) % colors.length + colors.length) % colors.length;
        int idx = (int) t % colors.length;
        if (!interpolate) return colors[idx];
        float frac = t - (int) t;
        int c1 = colors[idx], c2 = colors[(idx + 1) % colors.length];
        int a = (int)(((c1 >> 24) & 0xFF) * (1 - frac) + ((c2 >> 24) & 0xFF) * frac);
        int r = (int)(((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
        int g = (int)(((c1 >>  8) & 0xFF) * (1 - frac) + ((c2 >>  8) & 0xFF) * frac);
        int b = (int)((c1         & 0xFF) * (1 - frac) + (c2         & 0xFF) * frac);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Shared body of the three glow-colour resolvers: explicit glow colours if the stack has any, else the
     *  glint's layer-0 colour, else white. {@code wallClock} picks the trim-texture clock over the game clock;
     *  {@code phaseFraction} shifts the point of the colour loop. The glint layer-0 fallback is wall-clock on
     *  every path, so the ring/edge half-step holds regardless of which clock the explicit branch used. */
    private static int resolveGlow(ItemStack stack, boolean wallClock, float phaseFraction) {
        int[] glowColors = CustomGlint.getGlowColors(stack);
        if (glowColors.length > 0) {
            float speed = CustomGlint.getGlowSpeed(stack);
            boolean interpolate = CustomGlint.getGlowInterpolate(stack);
            return wallClock ? computeAnimatedGlowColorGui(glowColors, speed, interpolate)
                             : computeAnimatedGlowColor(glowColors, speed, interpolate, phaseFraction);
        }
        Data glint = CustomGlint.readForRender(stack);
        return glint != null ? computeAnimatedColor(glint, 0, phaseFraction) : 0xFFFFFFFF;
    }

    /** Resolves a glowing item's outline colour: its glow colours (animated) if set, else its glint
     *  layer-0 colour, else white. Shared by the flat-item and special-item glow-outline capture paths. */
    public static int resolveGlowColor(ItemStack stack) {
        return resolveGlow(stack, false, GLOW_RING_PHASE_OFFSET);
    }

    /** Edge/inner-tint colour for a glowing item's trim texture ("white × rgb"). Same resolution as
     *  {@link #resolveGlowColor} but at phase 0, so the tinted edge sits exactly {@link #GLOW_RING_PHASE_OFFSET}
     *  behind the outline ring, a stable half-step (edge red while the ring is blue, and back). Critically it
     *  uses the SAME clock as the ring per branch (game-time for explicit glow colours, wall-clock for the glint
     *  layer-0 fallback), so the half-step never drifts the way a wall-vs-game-clock split would. */
    public static int resolveGlowColorTint(ItemStack stack) {
        return resolveGlow(stack, false, 0.0f);
    }

    /** Wall-clock (GUI) counterpart of {@link #resolveGlowColor}, for the trim TEXTURE tint. Animates the glow
     *  colours on wall-clock so the tinted edge ("white × rgb") desyncs from the game-time glow outline: the
     *  edge and the ring show different colours at once. Auto glow follows glint layer 0 (already wall-clock). */
    public static int resolveGlowColorGui(ItemStack stack) {
        return resolveGlow(stack, true, 0.0f);
    }

    // ── Glint render-state support (glow outline moved to GlowOutlineRenderer) ──────

    /** Guards re-entrance during glint passes that re-enter the item/model render. Kept as a
     *  recursion guard for the glint paths (e.g. item foil). */
    public static final ThreadLocal<Boolean> IN_OUTLINE = ThreadLocal.withInitial(() -> false);

    /** Reload hooks appended by compat modules; invoked by {@link #clearTextures()} so each
     *  compat can release its own {@code DynamicTexture}s without {@code CustomGlintRenderer}
     *  needing to know about them. */
    public static final List<Runnable> additionalReloadCleanup = new CopyOnWriteArrayList<>();

    // The old stencil/shader glow-outline draw API (doModelOutline / doItemOutline /
    // doGuiItemOutline / doMultiModelOutline / doModelPartsOutline), the silhouette color helpers,
    // the RenderType texture reader, and FullColorOverrideConsumer were removed when the outline
    // was rebuilt as a post-process pass; see GlowOutlineRenderer. Glint rendering (forGlint /
    // forArmorGlint / forHorseArmorGlint / forMountArmorGlint / forEntityGlint) is unaffected.

    private CustomGlintRenderer() { super("", () -> {}, () -> {}); }

    // ── Shader-mod detection ──────────────────────────────────────────────────
    // Reflective so we don't need a compileOnly dep on the shader mod. Resolved once and
    // cached. Consumed by compat outline RTs to decide their shader-pack-safe routing.
    private static volatile boolean SHADER_LOOKUP_DONE = false;
    private static volatile Method SHADER_GET_INSTANCE = null;
    private static volatile Method SHADER_IS_IN_USE = null;

    public static boolean isShaderPackActive() {
        if (!SHADER_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_LOOKUP_DONE) {
                    try {
                        // Iris exposes this same API package under NeoForge; do not change it.
                        Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                        SHADER_GET_INSTANCE = api.getMethod("getInstance");
                        SHADER_IS_IN_USE = api.getMethod("isShaderPackInUse");
                    } catch (Throwable ignored) {
                        SHADER_GET_INSTANCE = null;
                        SHADER_IS_IN_USE = null;
                    }
                    SHADER_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADER_IS_IN_USE == null) return false;
        try {
            Object inst = SHADER_GET_INSTANCE.invoke(null);
            return (Boolean) SHADER_IS_IN_USE.invoke(inst);
        } catch (Throwable t) {
            return false;
        }
    }

    // Under a shader pack the first-person hand is not drawn via GameRenderer.renderItemInHand /
    // ItemInHandRenderer.renderHandsWithItems. Iris relocates it into its own HAND_SOLID / HAND_TRANSLUCENT
    // phase inside the gbuffer pass, with a THIRD_PERSON display context. So our renderItemInHand /
    // renderHandsWithItems flags never arm and the held item misroutes to the world outline queue. Iris exposes
    // the current phase; when it is a HAND phase the item being drawn IS the FP held item. Reflective, no dep.
    private static volatile boolean IRIS_PHASE_LOOKUP_DONE = false;
    private static volatile Method IRIS_GET_PIPELINE_MANAGER = null;
    private static volatile Method IRIS_GET_PIPELINE_NULLABLE = null;
    private static volatile Method IRIS_GET_PHASE = null;
    private static volatile Object IRIS_PHASE_HAND_SOLID = null;
    private static volatile Object IRIS_PHASE_HAND_TRANSLUCENT = null;

    /** True while Iris is in its HAND_SOLID / HAND_TRANSLUCENT phase, i.e. drawing the first-person hand item
     *  under a shader pack. False when no shader mod is present / not resolvable / not the hand phase. */
    public static boolean isShaderHandPass() {
        if (!IRIS_PHASE_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!IRIS_PHASE_LOOKUP_DONE) {
                    try {
                        Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                        IRIS_GET_PIPELINE_MANAGER = iris.getMethod("getPipelineManager");
                        Class<?> pm = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
                        IRIS_GET_PIPELINE_NULLABLE = pm.getMethod("getPipelineNullable");
                        Class<?> wrp = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPipeline");
                        IRIS_GET_PHASE = wrp.getMethod("getPhase");
                        Class<?> phase = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPhase");
                        for (Object c : phase.getEnumConstants()) {
                            String n = ((Enum<?>) c).name();
                            if (n.equals("HAND_SOLID")) IRIS_PHASE_HAND_SOLID = c;
                            else if (n.equals("HAND_TRANSLUCENT")) IRIS_PHASE_HAND_TRANSLUCENT = c;
                        }
                    } catch (Throwable ignored) {
                        IRIS_GET_PIPELINE_MANAGER = null;
                        IRIS_GET_PHASE = null;
                    }
                    IRIS_PHASE_LOOKUP_DONE = true;
                }
            }
        }
        if (IRIS_GET_PIPELINE_MANAGER == null || IRIS_GET_PIPELINE_NULLABLE == null || IRIS_GET_PHASE == null)
            return false;
        try {
            Object pm = IRIS_GET_PIPELINE_MANAGER.invoke(null);
            if (pm == null) return false;
            Object pipe = IRIS_GET_PIPELINE_NULLABLE.invoke(pm);
            if (pipe == null) return false;
            Object ph = IRIS_GET_PHASE.invoke(pipe);
            return ph == IRIS_PHASE_HAND_SOLID || ph == IRIS_PHASE_HAND_TRANSLUCENT;
        } catch (Throwable t) {
            return false;
        }
    }

    // Under shader mods, every RenderType is mixed in to implement BlendingStateHolder with a
    // TransparencyType field (default GENERAL_TRANSPARENT). The batched FullyBufferedMultiBuffer-
    // Source flushes by TransparencyType in enum order (OPAQUE → OPAQUE_DECAL → GENERAL_TRANSPARENT
    // → DECAL → WATER_MASK → LINES). Items use GENERAL_TRANSPARENT; if our outline RT also sits
    // there, the order between item and outline within the same bucket is undefined → outline can
    // flush before item → depth buffer empty when outline draws → polygon-offset/front-face-cull
    // can't reject the interior → outline reads as a filled silhouette. Tagging the outline RT as
    // LINES (last bucket) forces the shader mod to flush ALL item geometry first, then our outline; depth
    // ordering works in every camera context (1P / 3P / GROUND). Reflective to avoid compileOnly.
    private static volatile boolean SHADER_TT_LOOKUP_DONE = false;
    private static volatile Method SHADER_TT_SET = null;
    private static volatile Object SHADER_TT_LINES = null;
    private static final Set<RenderType> SHADER_TT_TAGGED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void tagAsLateRenderForShaders(RenderType rt) {
        if (rt == null) return;
        if (SHADER_TT_TAGGED.contains(rt)) return;
        if (!SHADER_TT_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_TT_LOOKUP_DONE) {
                    try {
                        Class<?> ttCls = Class.forName("net.irisshaders.batchedentityrendering.impl.TransparencyType");
                        Class<?> bshCls = Class.forName("net.irisshaders.batchedentityrendering.impl.BlendingStateHolder");
                        SHADER_TT_SET = bshCls.getMethod("setTransparencyType", ttCls);
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        Object lines = Enum.valueOf((Class<? extends Enum>) ttCls, "LINES");
                        SHADER_TT_LINES = lines;
                    } catch (Throwable ignored) {
                        SHADER_TT_SET = null;
                        SHADER_TT_LINES = null;
                    }
                    SHADER_TT_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADER_TT_SET != null && SHADER_TT_LINES != null) {
            try { SHADER_TT_SET.invoke(rt, SHADER_TT_LINES); } catch (Throwable ignored) {}
        }
        SHADER_TT_TAGGED.add(rt);
    }

    // OPAQUE_DECAL counterpart of tagAsLateRenderForShaders. The FullyBufferedMultiBufferSource flushes by
    // TransparencyType in enum order (OPAQUE → OPAQUE_DECAL → GENERAL_TRANSPARENT → DECAL → WATER_MASK → LINES).
    // Tagging a glint OPAQUE_DECAL makes it flush right AFTER opaque geometry but BEFORE the GENERAL_TRANSPARENT
    // pass that draws translucent bases (the slime outer shell). So the glint depth-tests against a buffer
    // holding only stable opaque geometry, never the shell's own Iris-re-sorted translucent depth, which is what
    // made a LINES-tagged shell glint flicker with camera angle and drop out at distance. Reflective, no compileOnly.
    private static volatile boolean SHADER_TT_OD_LOOKUP_DONE = false;
    private static volatile Method SHADER_TT_OD_SET = null;
    private static volatile Object SHADER_TT_OPAQUE_DECAL = null;

    public static void tagAsOpaqueDecalForShaders(RenderType rt) {
        if (rt == null) return;
        if (SHADER_TT_TAGGED.contains(rt)) return;
        if (!SHADER_TT_OD_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_TT_OD_LOOKUP_DONE) {
                    try {
                        Class<?> ttCls = Class.forName("net.irisshaders.batchedentityrendering.impl.TransparencyType");
                        Class<?> bshCls = Class.forName("net.irisshaders.batchedentityrendering.impl.BlendingStateHolder");
                        SHADER_TT_OD_SET = bshCls.getMethod("setTransparencyType", ttCls);
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        Object od = Enum.valueOf((Class<? extends Enum>) ttCls, "OPAQUE_DECAL");
                        SHADER_TT_OPAQUE_DECAL = od;
                    } catch (Throwable ignored) {
                        SHADER_TT_OD_SET = null;
                        SHADER_TT_OPAQUE_DECAL = null;
                    }
                    SHADER_TT_OD_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADER_TT_OD_SET != null && SHADER_TT_OPAQUE_DECAL != null) {
            try { SHADER_TT_OD_SET.invoke(rt, SHADER_TT_OPAQUE_DECAL); } catch (Throwable ignored) {}
        }
        SHADER_TT_TAGGED.add(rt);
    }


    // The shader mod's shadow pass re-invokes the full entity/item render pipeline to populate the
    // shadowmap. If we run our outline path during shadows, two things go wrong:
    //   1) The shadow pass writes into a separate framebuffer with its own format constraints,
    //      and getBuffer() returns a buffer with a different vertex format than we expect →
    //      "Not filled all elements of the vertex" crash on endVertex.
    //   2) Even if it didn't crash, outline geometry has no business depth-writing into the
    //      shadowmap; it would just produce wrong shadows for the outline shell.
    // Skip the whole outline path when shadows are being rendered. Reflective lookup so we
    // don't need a compileOnly dep.
    private static volatile boolean SHADOW_LOOKUP_DONE = false;
    private static volatile Method SHADOW_IS_RENDERING = null;

    public static boolean isInShadowPass() {
        if (!SHADOW_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADOW_LOOKUP_DONE) {
                    try {
                        Class<?> cls = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState");
                        SHADOW_IS_RENDERING = cls.getMethod("areShadowsCurrentlyBeingRendered");
                    } catch (Throwable ignored) {
                        SHADOW_IS_RENDERING = null;
                    }
                    SHADOW_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADOW_IS_RENDERING == null) return false;
        try {
            return (Boolean) SHADOW_IS_RENDERING.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }
}
