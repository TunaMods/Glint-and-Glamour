package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

import net.tunamods.customglint.common.CustomGlint.Data;
import net.tunamods.customglint.common.CustomGlint.Layer;

/**
 * Client-only rendering backend. Split out of {@link CustomGlint} so that the data-API class
 * remains loadable on dedicated servers (where {@link RenderStateShard} and other client classes
 * are absent). Mods bundling the api jar should call render-pipeline methods through this class;
 * NBT/data API stays on {@link CustomGlint}.
 *
 * <p>Naming: reflective handles and their one-shot lookup flags are UPPER_SNAKE like constants, because
 * they are written once at first use and read-only after; ordinary mutable state stays lowerCamel.
 */
public final class CustomGlintRenderer extends RenderStateShard {

    // Texture cache

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, ResourceLocation> textureCache = new HashMap<>();

    public static ResourceLocation getTexture(ResourceLocation design) {
        if (textureCache.containsKey(design)) return textureCache.get(design);
        ResourceLocation result = generateTexture(design);
        textureCache.put(design, result);
        return result;
    }

    public static void clearTextures() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation loc : textureCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        textureCache.clear();
        for (Runnable r : additionalReloadCleanup) {
            try { r.run(); } catch (Throwable t) {
                LOGGER.warn("[{}/CustomGlint] additional reload cleanup threw", MOD_ID, t);
            }
        }
        for (RenderType rt : BY_GLINT.values())             unregisterFixedBuffer(rt);
        for (RenderType rt : BY_ARMOR_GLINT.values())       unregisterFixedBuffer(rt);
        for (RenderType rt : BY_HORSE_ARMOR_GLINT.values()) unregisterFixedBuffer(rt);
        for (RenderType rt : BY_MOUNT_ARMOR_GLINT.values()) unregisterFixedBuffer(rt);
        for (RenderType rt : BY_MOUNT_ARMOR_MASK.values())  unregisterFixedBuffer(rt);
        for (RenderType rt : BY_CHROMATIC.values())         unregisterFixedBuffer(rt);
        BY_GLINT.clear();
        GLINT_FAST.clear();
        BY_ARMOR_GLINT.clear();
        BY_HORSE_ARMOR_GLINT.clear();
        BY_MOUNT_ARMOR_GLINT.clear();
        BY_MOUNT_ARMOR_MASK.clear();
        BY_CHROMATIC.clear();
        GLINT_COLORS.clear();
        for (ResourceLocation loc : paletteCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        paletteCache.clear();
        // Drop tagged-RT references too, else each reload orphans the previous generation of compat
        // (EK decoration) RenderTypes here even after their own caches are evicted. They'd never GC.
        SHADER_TT_TAGGED.clear();
        for (Tinted t : TINTED.values()) releaseTinted(t);
        TINTED.clear();
        grayPixels.clear(); // re-stashed by generateTexture when a design is next asked for
        ChromaticTextureBaker.release(); // baked slicks are re-created on demand at the next draw
        if (whiteTex != null) { mc.getTextureManager().release(whiteTex); whiteTex = null; }
    }

    private static ResourceLocation generateTexture(ResourceLocation design) {
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

        int w = source.getWidth(), h = source.getHeight();
        NativeImage gray = new NativeImage(w, h, false);
        // The texels are kept as well as uploaded: the pre-tinted shader-pack path (see TINTED) rebuilds its
        // coloured copies from these, and re-reading the PNG per colour per frame would be IO per draw.
        int[] texels = new int[w * h];
        long lumSum = 0; // Σ lum×alpha, 0..255*255 per texel; feeds the design's mean energy (see envelopeDim)
        try {
            for (int y = 0, i = 0; y < h; y++) {
                for (int x = 0; x < w; x++, i++) {
                    // NativeImage pixel format is ABGR stored as int: (A<<24)|(B<<16)|(G<<8)|R
                    int pixel = source.getPixelRGBA(x, y);
                    int r =  pixel        & 0xFF;
                    int g = (pixel >>  8) & 0xFF;
                    int b = (pixel >> 16) & 0xFF;
                    int a = (pixel >> 24) & 0xFF;
                    int lum = (r + g + b) / 3;
                    texels[i] = (a << 24) | (lum << 16) | (lum << 8) | lum;
                    gray.setPixelRGBA(x, y, texels[i]);
                    lumSum += (long) lum * a;
                }
            }
        } finally {
            source.close();
        }
        // long divisor: a 256x256 data-pack design overflows this product in int arithmetic.
        float mean = lumSum / (float) ((long) w * h * 255 * 255);
        grayPixels.put(design, new Gray(texels, w, h, envelopeDim(mean)));

        String safePath = design.getNamespace() + "/" + design.getPath().replace('/', '_').replace('.', '_');
        ResourceLocation loc = CustomGlint.res("glint/" + safePath);
        DynamicTexture dt = new DynamicTexture(gray);
        mc.getTextureManager().register(loc, dt);
        configureGlintSampler(dt);
        return loc;
    }

    /** Sampler state every glint design is drawn with: REPEAT so {@code patternScale > 1} tiles, NEAREST
     *  because that is what the designs' look is calibrated against. The tinted copies
     *  ({@link #getTintedTexture}) have to match the greyscale originals here or they'd filter differently
     *  under a shader pack only. */
    private static void configureGlintSampler(DynamicTexture tex) {
        tex.bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    }

    // Pre-tinted design textures (shader-pack only)
    //
    // A texture glint is the greyscale design in Sampler0 times the layer's animated colour in ColorModulator,
    // and off-pack our own copy of vanilla's program multiplies the two (glint.fsh). Under a pack it doesn't:
    // Iris hands the draw to the pack's own gbuffers_armor_glint, and whether the colour survives is that
    // pack's business. Complementary and BSL multiply by gl_Color, which Iris feeds from ColorModulator, so
    // the split works. Photon's armor_glint samples gtexture and nothing else. gl_Color appears in neither of
    // its stages, so the colour is dropped and every glint renders greyscale. Nothing we can put in
    // ColorModulator fixes that; it is never read. Vanilla's glint carries no colour, so a pack leaving
    // gl_Color out of this program is a reasonable thing to write. Expect the next one to do it too.
    //
    // The one input every pack agrees on is the texture. So under a pack, bind a design that is ALREADY tinted
    // and leave ColorModulator white: same product the fragment computed before (tinted × white == greyscale ×
    // colour), so the packs that read gl_Color are unaffected and Photon gets the colour it was dropping.
    //
    // Gated on irisShouldOverrideShaders(), the same question resolveGlintShader() asks. Off-pack, and in the
    // GUI/HUD phase where our own program runs and reads ColorModulator correctly, none of this runs and the
    // draw is what it always was. That gate also keeps the cache down to what is on screen in the world rather
    // than every stack in an open chest.
    //
    // Baking the tint also gives the brightness calibration somewhere to live. See envelopeDim.

    /** One design's greyscale texels (ABGR, as {@link #generateTexture} packed them), its dimensions, and the
     *  under-pack brightness scalar {@link #envelopeDim} derived from its own coverage. */
    private record Gray(int[] px, int w, int h, float dim) {}

    // Vanilla's glint envelope
    //
    // Mean luminance of vanilla's enchanted_glint_item.png, measured off the 1.20.1 asset. Its peak is 0.66,
    // so it never goes near white. Packs that treat glint as emissive and amplify it are calibrated against
    // this texture, which makes it the only contract they all share.
    //
    // Our designs don't respect it: solid.png is mean 1.00, grid 0.63, crosshatch 0.54, and a dozen more sit
    // above vanilla with peaks pinned at 1.0. A pack tuned for vanilla is then handed up to 3x the energy it
    // expects, amplifies it as designed, and the item blows out to a solid slab with the texture lost
    // underneath. The pack is not wrong. So scale each design under a pack until its mean energy lands inside
    // vanilla's envelope, and a pack nobody here has tested is handled too, because our glint now looks like
    // the one it was tuned against.
    //
    // Deliberately measured against a vanilla asset rather than keyed on Iris.getCurrentPackName(): a table of
    // per-pack scalars was tried first and is not maintainable. Every new pack is a new entry, and the two
    // eyeballed numbers it held (0.6) turned out to be this envelope anyway. Don't reintroduce one.
    private static final float VANILLA_GLINT_MEAN = 0.36f;

    /** Under-pack brightness for a design of this mean energy: enough to bring it into vanilla's envelope,
     *  never more than 1.0. The clamp matters as much as the scale: sparse designs (sparkle at 0.05, ember at
     *  0.01) already sit far below vanilla, and scaling those up would blow a few scattered specks into
     *  something the design never meant to be. */
    private static float envelopeDim(float mean) {
        if (mean <= 0.0f) return 1.0f; // fully transparent or fully black design; nothing to scale
        return Math.min(1.0f, VANILLA_GLINT_MEAN / mean);
    }

    // Photon compat
    //
    // Photon is the one pack that consumes the glint texture through a raw power law, in
    // program/gbuffers_armor_glint.fsh:
    //     frag_color.rgb = (srgb_eotf_inv(armor_glint) * rec709_to_working_color) * ENCHANTMENT_GLINT_BRIGHTNESS;
    // srgb_eotf_inv is sRGB to linear, near enough pow(x, 2.2), and nothing walks it back. envelopeDim's scale is
    // linear, so the two compound instead of composing: a design dimmed to D reaches the screen at D^2.2, not D.
    // solid (D = 0.36) lands at 0.099 against vanilla's brightest texel at 0.66^2.2 = 0.40, i.e. 4x too dark, and
    // reads as no glint at all. Sparse designs clamp to D = 1.0, are untouched by the power law, and come out
    // vivid. That is the whole "some designs are dead, some are vivid" split, ordered by design density, and it
    // hits every glinted surface (items and armor as much as the slime shell).
    //
    // Pre-compensating with pow(D, 1/2.2) makes Photon's own pow(2.2) land the design at exactly D, which is the
    // scale every other pack already applies. This does not tune Photon to taste; it restores the intent.
    //
    // VANILLA_GLINT_MEAN's note bans keying on the pack name. What it bans is a table of EYEBALLED per-pack
    // scalars, which decays the moment a new pack ships. This is a different thing: one gate, an exponent read
    // out of that pack's own source, and derived arithmetic. Do not grow it into a table. If another pack looks
    // like it needs an entry, first check that it really does linearise without undoing it. BSL looks like it
    // qualifies and does not, because ALPHA_BLEND == 0 follows its pow(2.2) with a sqrt for a net pow(1.1).
    private static final double PHOTON_GLINT_GAMMA = 2.2;

    /** {@link #envelopeDim}'s scale, corrected for how the active pack consumes it. */
    private static float packCorrectedDim(float dim) {
        if (dim >= 1.0f) return dim; // an unscaled design survives any transfer function unchanged
        if (!packAppliesGlintGamma()) return dim;
        return (float) Math.pow(dim, 1.0 / PHOTON_GLINT_GAMMA);
    }

    private static volatile String PACK_GAMMA_LAST_NAME = null;
    private static volatile boolean PACK_GAMMA_LAST_RESULT = false;

    /** True while the active pack raises the glint texture to a power and leaves it there. Photon only. */
    private static boolean packAppliesGlintGamma() {
        String name = currentPackName();
        if (name == null) return false;
        if (!name.equals(PACK_GAMMA_LAST_NAME)) { // recomputed only when the pack actually changes
            PACK_GAMMA_LAST_NAME = name;
            PACK_GAMMA_LAST_RESULT = name.toLowerCase(Locale.ROOT).contains("photon");
        }
        return PACK_GAMMA_LAST_RESULT;
    }

    private static volatile boolean PACK_NAME_LOOKUP_DONE = false;
    private static volatile Field IRIS_PACK_NAME = null;

    /** The active shaderpack's name, or null with no shader mod / no pack. Reads Iris's private
     *  {@code currentPackName}: neither IrisApi nor Iris exposes a public accessor in 1.8.0, and an access
     *  transformer only reaches Minecraft classes, so there is no cleaner route to it. */
    @Nullable
    public static String currentPackName() {
        if (!PACK_NAME_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!PACK_NAME_LOOKUP_DONE) {
                    try {
                        Field f = Class.forName("net.irisshaders.iris.Iris").getDeclaredField("currentPackName");
                        f.setAccessible(true);
                        IRIS_PACK_NAME = f;
                    } catch (Throwable ignored) {
                        IRIS_PACK_NAME = null;
                    }
                    PACK_NAME_LOOKUP_DONE = true;
                }
            }
        }
        if (IRIS_PACK_NAME == null) return null;
        try {
            return (String) IRIS_PACK_NAME.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Tint source per design. Populated by {@link #generateTexture}, dropped on resource reload. */
    private static final Map<ResourceLocation, Gray> grayPixels = new HashMap<>();

    private static final class Tinted {
        final ResourceLocation loc;
        final DynamicTexture tex;
        int lastColor = -1; // packed premultiplied colour last uploaded; -1 = nothing uploaded yet
        Tinted(ResourceLocation loc, DynamicTexture tex) { this.loc = loc; this.tex = tex; }
    }

    /** One tinted design per glint RenderType key. Bounded for the same reason the model-path RT caches are:
     *  a scene of many differently-coloured glints would otherwise grow one texture per config for the whole
     *  session. Access-ordered LRU; eviction frees the texture rather than orphaning it. */
    private static final int TINT_CACHE_CAP = 256;
    private static int tintSerial;
    private static final Map<String, Tinted> TINTED = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Tinted> eldest) {
            if (size() <= TINT_CACHE_CAP) return false;
            releaseTinted(eldest.getValue());
            return true;
        }
    };

    private static void releaseTinted(Tinted t) {
        Minecraft.getInstance().getTextureManager().release(t.loc); // unregister; frees the GL id
        t.tex.close();                                              // and the NativeImage behind it
    }

    private static int to255(float v) {
        int i = (int) (v * 255.0f + 0.5f);
        return i < 0 ? 0 : Math.min(i, 255);
    }

    /**
     * Bind Sampler0 and ColorModulator for one texture-glint draw: a pre-tinted design and a white modulator
     * under a shader pack, the greyscale design and the colour itself everywhere else. {@code boost} scales the
     * colour on the way in, clamped to 1, so it can only lift a colour that is not already saturated.
     */
    public static void bindGlintTexture(String key, ResourceLocation design, float[] holder, float boost) {
        float r = Math.min(1.0f, holder[0] * boost);
        float g = Math.min(1.0f, holder[1] * boost);
        float b = Math.min(1.0f, holder[2] * boost);
        ResourceLocation tinted = irisShouldOverrideShaders()
                ? getTintedTexture(key, design, r, g, b, holder[3]) : null;
        if (tinted != null) {
            RenderSystem.setShaderTexture(0, tinted);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            RenderSystem.setShaderTexture(0, getTexture(design));
            RenderSystem.setShaderColor(r, g, b, holder[3]);
        }
    }

    public static void bindGlintTexture(String key, ResourceLocation design, float[] holder) {
        bindGlintTexture(key, design, holder, 1.0f);
    }

    /**
     * The design tinted by this colour, re-uploading only when the colour actually moves: a static or
     * simultaneous layer pays one upload and then nothing, a sequential one pays a 64×64 multiply per frame.
     * Null if the design never generated, which sends the caller back to the greyscale path.
     *
     * <p>The colour is scaled by the design's {@link #envelopeDim} on the way in, so a full-coverage design
     * reaches the pack's glint program carrying no more energy than vanilla's would. It rides the multiply the
     * tint already does, and scaling here rather than at the call site keeps it off the off-pack path.
     *
     * <p>Unlike the chromatic bake this is safe to call mid-pass, which is where every {@code setupRenderState}
     * runs: it uploads to a texture and never touches an FBO, so there is no framebuffer binding to lose (see
     * the LOAD-BEARING note on {@link ChromaticTextureBaker#textureId}).
     */
    private static ResourceLocation getTintedTexture(String key, ResourceLocation design,
                                                     float r, float g, float b, float a) {
        Gray src = grayPixels.get(design);
        if (src == null) return null;
        float dim = packCorrectedDim(src.dim());
        int packed = (to255(a) << 24) | (to255(r * dim) << 16) | (to255(g * dim) << 8) | to255(b * dim);
        Tinted t = TINTED.get(key);
        if (t != null && t.lastColor == packed) return t.loc;
        if (t == null) {
            DynamicTexture dt = new DynamicTexture(new NativeImage(src.w(), src.h(), false));
            ResourceLocation loc = CustomGlint.res("glint_tinted/" + Integer.toHexString(tintSerial++));
            Minecraft.getInstance().getTextureManager().register(loc, dt);
            configureGlintSampler(dt);
            t = new Tinted(loc, dt);
            TINTED.put(key, t); // access-ordered: the new entry is newest, so an eviction here never hits it
        }
        NativeImage img = t.tex.getPixels();
        if (img == null) return null;
        int tr = (packed >> 16) & 0xFF, tg = (packed >> 8) & 0xFF, tb = packed & 0xFF, ta = (packed >>> 24) & 0xFF;
        int[] px = src.px();
        for (int y = 0, i = 0; y < src.h(); y++) {
            for (int x = 0; x < src.w(); x++, i++) {
                int p = px[i];
                int lum = p & 0xFF;         // greyscale, so R == G == B here; read whichever
                int pa = (p >>> 24) & 0xFF;
                // ABGR, matching generateTexture's packing. (v*c + 127)/255 is the rounded 8-bit product.
                img.setPixelRGBA(x, y, (((pa  * ta + 127) / 255) << 24)
                                     | (((lum * tb + 127) / 255) << 16)
                                     | (((lum * tg + 127) / 255) <<  8)
                                     |  ((lum * tr + 127) / 255));
            }
        }
        t.tex.upload();
        t.lastColor = packed;
        return t.loc;
    }

    // Render types

    /** Assigned by RenderBuffersMixin on RenderBuffers construction; null until then. */
    public static SortedMap<RenderType, BufferBuilder> fixedBufferRegistry;
    public static final ThreadLocal<ItemStack> CURRENT_ITEM_STACK = new ThreadLocal<>();
    public static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    /** Premultiply an ARGB int into a 4-float shader color (rgb scaled by alpha, alpha slot forced to 1).
     *  Every glint fan-out (items, armor, entities, and the compat glue) fills {@link #COLOR_BUF} this way
     *  before looking up its RenderType. */
    public static void fillPremul(float[] buf, int argb) {
        float a = ((argb >> 24) & 0xFF) / 255.0f;
        buf[0] = ((argb >> 16) & 0xFF) / 255.0f * a;
        buf[1] = ((argb >>  8) & 0xFF) / 255.0f * a;
        buf[2] = ( argb        & 0xFF) / 255.0f * a;
        buf[3] = 1.0f;
    }

    /** Per-key mutable float[4] holders; RenderType lambdas close over these references and read them each frame. */
    private static final Map<String, float[]>    GLINT_COLORS          = new HashMap<>();
    private static final Map<String, RenderType> BY_GLINT              = new HashMap<>();
    private static final Map<String, RenderType> BY_ARMOR_GLINT        = boundedGlintCache();
    private static final Map<String, RenderType> BY_HORSE_ARMOR_GLINT  = boundedGlintCache();
    private static final Map<String, RenderType> BY_MOUNT_ARMOR_GLINT  = boundedGlintCache();
    private static final Map<ResourceLocation, RenderType> BY_MOUNT_ARMOR_MASK = new HashMap<>();

    /** The model-path glint RT caches (armor / horse / mount / entity) are keyed by the full glint config, so a
     *  scene throwing many distinct per-instance palettes at them (varied glints across many mobs, {@code /glint
     *  entity} spam) would otherwise grow one RenderType + native BufferBuilder per config for the whole session,
     *  the same accumulation {@link #BY_CHROMATIC} is capped for. Access-ordered LRU; eviction recycles the RT's
     *  builder ({@link #unregisterFixedBuffer}) and drops its colour holder. {@link #BY_GLINT} stays uncapped: its
     *  entries are pinned by the {@link #GLINT_FAST} memo, and held-item configs are inherently few. */
    private static final int GLINT_CACHE_CAP = 256;
    private static Map<String, RenderType> boundedGlintCache() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, RenderType> eldest) {
                if (size() <= GLINT_CACHE_CAP) return false;
                unregisterFixedBuffer(eldest.getValue());
                GLINT_COLORS.remove(eldest.getKey());
                return true;
            }
        };
    }

    // Fixed-buffer registration + builder recycling
    // Each custom glint RenderType needs a dedicated BufferBuilder in the fixed-buffer map. In 1.20.1 a
    // BufferBuilder's backing buffer is a raw MemoryUtil.memAlloc (no Cleaner, no close()), so simply dropping
    // a builder when its RT is removed (chromatic LRU eviction, resource reload, EpicKnightsGlintRT) orphans
    // that off-heap allocation. Recycle removed builders through this pool and hand them back on the next
    // registration so native memory is bounded by the peak live RT count instead of growing for the session.
    // Render-thread only (same as the BY_* maps), so no synchronization.
    private static final ArrayDeque<BufferBuilder> RECYCLED_BUILDERS = new ArrayDeque<>();

    private static BufferBuilder obtainBuilder(int size) {
        BufferBuilder bb = RECYCLED_BUILDERS.poll();
        if (bb == null) return new BufferBuilder(size);
        bb.discard(); // clear any leftover state from its previous RT before reuse
        return bb;
    }

    /** Register {@code rt}'s dedicated builder into the captured registry and the live fixed-buffer map
     *  (usually the same instance), reusing a pooled builder when one is free. Idempotent. */
    public static void registerFixedBuffer(RenderType rt) {
        SortedMap<RenderType, BufferBuilder> live = null;
        try { live = Minecraft.getInstance().renderBuffers().fixedBuffers; } catch (Throwable ignored) {}
        if (fixedBufferRegistry != null && !fixedBufferRegistry.containsKey(rt))
            fixedBufferRegistry.put(rt, obtainBuilder(rt.bufferSize()));
        if (live != null && live != fixedBufferRegistry && !live.containsKey(rt))
            live.put(rt, obtainBuilder(rt.bufferSize()));
    }

    /** Remove {@code rt} from the fixed-buffer map(s) and recycle its builder(s) rather than orphaning the
     *  native buffer. Used by {@link #clearTextures}, the chromatic LRU eviction, and EpicKnightsGlintRT. */
    public static void unregisterFixedBuffer(RenderType rt) {
        if (fixedBufferRegistry != null) {
            BufferBuilder bb = fixedBufferRegistry.remove(rt);
            if (bb != null) RECYCLED_BUILDERS.offer(bb);
        }
        try {
            SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
            if (live != null && live != fixedBufferRegistry) {
                BufferBuilder bb = live.remove(rt);
                if (bb != null) RECYCLED_BUILDERS.offer(bb);
            }
        } catch (Throwable ignored) {}
    }

    /** Fast path for {@link #forGlint}: the resolved (RenderType, colour holder) memoised per immutable
     *  {@link Layer}, indexed by (colorIdx, isItem). The steady state then skips rebuilding the ~10-part
     *  String key (and its {@code Arrays.toString}) plus the map probes every frame for a cache that always
     *  hits after warmup. It just refreshes the holder the RT's shader closure reads. {@code readCached}
     *  hands back stable Layer instances; the resolved RT stays registered until {@link #clearTextures}
     *  (which clears this memo too), so the fast path needs no live-map probe. */
    private record GlintRT(RenderType rt, float[] holder) {}
    private static final Map<Layer, GlintRT[]> GLINT_FAST = new WeakHashMap<>();

    // Scroll direction → UV drift
    // The glint motif drifts along one of eight compass directions (or freezes when STATIC). The pattern
    // *appears* to move opposite to the UV drift, so these are negated from screen direction; texture-V runs
    // DOWN so North (pattern up) drifts +V. This directional drift REPLACES the vanilla 3-rotation scroll for
    // every surface (default East = the historical horizontal scroll look). Shared immutable vectors avoid a
    // per-flush float[] allocation.
    private static final float SQ = 0.70710677f; // 1/√2
    private static final float[] SU_E  = {-1f, 0f},  SU_NE = {-SQ, SQ}, SU_N  = {0f, 1f},  SU_NW = {SQ, SQ},
                                 SU_W  = { 1f, 0f},  SU_SW = { SQ,-SQ}, SU_S  = {0f,-1f},  SU_SE = {-SQ,-SQ},
                                 SU_STATIC = {0f, 0f};
    private static final ThreadLocal<float[]> SCROLL_BUF = ThreadLocal.withInitial(() -> new float[2]);
    /** Reused per-thread texture matrix for the scroll/chromatic shards. These run in a RenderType setup
     *  lambda once per glint flush per frame, so a fresh Matrix4f each call is needless GC churn. {@code
     *  translation()} fully overwrites the matrix, so no explicit {@code identity()} is needed. */
    private static final ThreadLocal<Matrix4f> SCROLL_MAT = ThreadLocal.withInitial(Matrix4f::new);

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
            // 110000/30000 ms periods and the 8× rate mirror vanilla's enchant-glint scroll (GlintTexturing).
            long t = (long) (Util.getMillis() * 8.0 * layer.speed());
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
        Matrix4f m = SCROLL_MAT.get().translation(sc[0], sc[1], 0.0F);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scaleU * layer.patternScale(), scaleV * layer.patternScale(), 1.0f);
        m.translate(-0.5f, -0.5f, 0.0f);
        RenderSystem.setTextureMatrix(m);
    }

    /** Armor / horse / mount / entity directional-drift texture matrix (uniform model-UV scale). */
    private static void setModelScrollMatrix(Layer layer, int colorIdx, float scale) {
        float[] sc = scrollAmount(layer, colorIdx);
        Matrix4f m = SCROLL_MAT.get().translation(sc[0], sc[1], 0.0F);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scale * layer.patternScale());
        m.translate(-0.5f, -0.5f, 0.0f);
        RenderSystem.setTextureMatrix(m);
    }

    // Glint core shader
    // The texture glints draw through OUR OWN copy of vanilla's rendertype_glint program instead of binding
    // vanilla's. The GLSL is identical (assets/customglint/shaders/core/glint.{vsh,fsh} are verbatim copies);
    // only the JSON's declared blend differs, and that difference is the entire reason this exists. See the
    // LOAD-BEARING note above CHROMATIC_MODEL_UV_SCALE.
    private static ShaderInstance glintShader;

    /** Shader shard for every texture-glint RenderType (items, armor, entities, horse/mount armor, and the
     *  compat builders). Resolved per draw by {@link #resolveGlintShader()}. */
    public static final RenderStateShard.ShaderStateShard GLINT_SHADER_SHARD =
            new RenderStateShard.ShaderStateShard(CustomGlintRenderer::resolveGlintShader);

    /**
     * LOAD-BEARING: which glint program to bind, decided per draw against Iris's own gate.
     *
     * <p>Iris/Oculus hangs BOTH of these on {@code ShaderRenderingPipeline.shouldOverrideShaders()}
     * (verified in oculus-mc1.20.1-1.8.0):
     * <ul>
     *   <li>{@code MixinGameRenderer.iris$overrideGlintShader}: while true, vanilla's
     *       {@code getRendertypeGlintShader()} returns the PACK's GLINT program (an {@code ExtendedShader}).</li>
     *   <li>{@code MixinShaderInstance.iris$lockDepthColorState}: while true, {@code ShaderInstance.apply()} on
     *       any shader that is NOT an {@code ExtendedShader}/{@code FallbackShader} calls
     *       {@code DepthColorStorage.disableDepthColor()}, i.e. {@code colorMask(false,false,false,false)}.</li>
     * </ul>
     *
     * <p>So the two are exactly complementary. While it is TRUE, our own program would be colour-masked to
     * nothing, and vanilla's accessor is the only way to get a program Iris will actually draw, so use that.
     * While it is FALSE (GUI/HUD and off-pack) nothing is overridden or masked, so use ours, and its declared
     * blend is what keeps the glint from filling the item.
     *
     * <p>Ask Iris the same question it asks itself rather than substituting our own {@link #isRenderingWorld()}
     * flag: the two do not agree at every phase, and a mismatch silently binds the wrong program's blend.
     */
    private static ShaderInstance resolveGlintShader() {
        if (glintShader == null || irisShouldOverrideShaders()) return GameRenderer.getRendertypeGlintShader();
        return glintShader;
    }

    // Procedural chromatic glint
    // The chromatic design has no PNG, so a custom core shader (registered via RegisterShadersEvent) synthesises
    // an oil-slick from value-noise. That shader no longer draws the glint: ChromaticTextureBaker runs it once per
    // frame into an offscreen texture, and the glint RTs below sample that texture through the ordinary
    // GLINT_SHADER_SHARD, so chromatic is just design #56 as far as the render path is concerned. Up to 8 colours
    // ride a 1px palette strip the BAKE reads on Sampler1; the glint draw itself binds only the baked slick. One
    // RenderType per (colours, speed, scale, seed, surface): a single draw, no per-colour fan-out.
    //
    // It used to bind its own program at draw time, which is the root of every workaround this file has since shed:
    // Iris colour-masks a non-ExtendedShader to nothing during the level pass (see resolveGlintShader), so the draw
    // had to be captured and replayed after the pack composited, and a post-composite draw is downstream of the
    // pack's TAA resolve, which is why chromatic was the only thing in the frame that flickered under BSL/Bliss.
    private static ShaderInstance chromaticShader;
    /** Each applied chromatic trim rolls a fresh seed, and the RenderType key includes that seed, so without
     *  a bound these accumulate one RenderType + BufferBuilder per distinct seed for the whole session. Cap
     *  with an access-ordered LRU whose eviction unregisters the evicted RT (recycling its builder via
     *  {@link #unregisterFixedBuffer}), mirroring {@link #clearTextures()}. */
    private static final int CHROMATIC_CACHE_CAP = 256;
    private static final Map<String, RenderType> BY_CHROMATIC =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, RenderType> eldest) {
                    if (size() <= CHROMATIC_CACHE_CAP) return false;
                    unregisterFixedBuffer(eldest.getValue()); // recycle the evicted RT's builder, don't orphan it
                    return true;
                }
            };

    /** 1px-tall palette strips (one RGBA texel per colour) bound to Sampler1, keyed by colour set. Cleared on
     *  resource reload alongside the texture cache. */
    private static final Map<String, ResourceLocation> paletteCache = new HashMap<>();
    private static ResourceLocation whiteTex; // see getWhiteTexture

    /** Mod-bus listener (hooked from {@link CustomGlintClientInit}): registers both core shaders: the texture
     *  glint (our copy of vanilla's program, differing only in its declared blend) and the procedural
     *  chromatic. Both are POSITION_TEX so they slot into the same fixed-buffer paths. */
    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            MOD_ID + ":glint",
                            DefaultVertexFormat.POSITION_TEX),
                    shader -> glintShader = shader);
        } catch (Exception e) {
            LOGGER.error("[{}/CustomGlint] failed to register glint shader", MOD_ID, e);
        }
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            MOD_ID + ":chromatic",
                            DefaultVertexFormat.POSITION_TEX),
                    shader -> chromaticShader = shader);
        } catch (Exception e) {
            LOGGER.error("[{}/CustomGlint] failed to register chromatic shader", MOD_ID, e);
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

    /** The chromatic BAKE program, for {@link ChromaticTextureBaker}. Never bound by a glint RenderType any more:
     *  chromatic draws through {@link #GLINT_SHADER_SHARD} like every other design, and this only ever draws the
     *  baker's offscreen quad. */
    static ShaderInstance chromaticShader() { return chromaticShader; }

    static String colorsKey(int[] colors) {
        StringBuilder sb = new StringBuilder(colors.length * 7);
        for (int c : colors) sb.append(Integer.toHexString(c)).append('_');
        return sb.length() == 0 ? "rainbow" : sb.toString();
    }

    /** Palette strip for Sampler1: width = max(1, colours), one opaque RGBA texel per colour (RGB only;
     *  the shader applies its own brightness). With no colours a 1px white keeps Sampler1 bound; the shader
     *  reads colour-count 0 and falls back to a full-spectrum rainbow, so the strip's contents go unused. */
    static ResourceLocation getPaletteTexture(int[] colors) {
        String key = colorsKey(colors);
        ResourceLocation existing = paletteCache.get(key);
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
        paletteCache.put(key, loc);
        return loc;
    }

    /** A colourless chromatic trim (the creative-tab template) renders this neutral white→grey→dark slick
     *  instead of the full-spectrum rainbow fallback: the recognisable "greyscale" look. */
    private static final int[] CHROMATIC_EMPTY_PALETTE = { 0xFFFFFFFF, 0xFF8A8A8A, 0xFF3A3A3A };

    /** Resolve a chromatic layer's palette: its own colours, or the greyscale template when it has none. */
    public static int[] chromaticColors(int[] colors) {
        return colors.length == 0 ? CHROMATIC_EMPTY_PALETTE : colors;
    }

    /** Chromatic texture matrix: scales the baked slick about the texture centre exactly like the texture glint's
     *  scroll matrix does, with no drift. Chromatic's motion is baked into the texture, one frame at a time, so
     *  the sampler only has to place it. The bake spreads {@code DENSITY} noise cells across the texture, so a
     *  surface scaled by S shows S x DENSITY cells, the same density the pre-bake shader produced from
     *  {@code noiseCoord * DENSITY}, which is why every caller's scaleU/scaleV carried over untouched. */
    private static void setChromaticMatrix(Layer layer, float scaleU, float scaleV) {
        Matrix4f m = SCROLL_MAT.get().translation(0.0f, 0.0f, 0.0f);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scaleU * layer.patternScale(), scaleV * layer.patternScale(), 1.0f);
        m.translate(-0.5f, -0.5f, 0.0f);
        RenderSystem.setTextureMatrix(m);
    }

    /** Builds (or returns cached) a chromatic RenderType for one layer. {@code layering} matches the surface
     *  the glint draws on (NO_LAYERING for items / horse / entity, VIEW_OFFSET_Z_LAYERING for worn armor),
     *  exactly mirroring the texture-glint depth setup so the EQUAL_DEPTH_TEST pass lines up. */
    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          RenderStateShard.LayeringStateShard layering) {
        return chromaticRT(layer, tag, scaleU, scaleV, layering, VertexFormat.Mode.QUADS, false, false);
    }

    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          RenderStateShard.LayeringStateShard layering, VertexFormat.Mode mode) {
        return chromaticRT(layer, tag, scaleU, scaleV, layering, mode, false, false);
    }

    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          RenderStateShard.LayeringStateShard layering, VertexFormat.Mode mode,
                                          boolean lateForShaders) {
        return chromaticRT(layer, tag, scaleU, scaleV, layering, mode, lateForShaders, false);
    }

    // {@code lateBucketTag} tags the RT into the shader-pack LINES bucket (tagAsLateRenderForShaders), for a
    // surface that must flush AFTER the mod's own draw lands its depth. Epic Knights decorations are the only user:
    // their glint EQUAL-tests against a depth pre-pass, and without the tag FullyBuffered can schedule the glint
    // first, leaving EQUAL to compare against clear-depth. Distinct from lateForShaders, which is the slime shell's
    // OPAQUE_DECAL flag and also flips depth/write-mask/layering.
    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          RenderStateShard.LayeringStateShard layering, VertexFormat.Mode mode,
                                          boolean lateForShaders, boolean lateBucketTag) {
        int[] colors = chromaticColors(layer.colors());
        final ResourceLocation white = getWhiteTexture();
        String key = tag + "|" + colorsKey(colors) + "|" + layer.speed() + "|" + layer.patternScale()
                + "|" + layer.seed() + "|" + scaleU + "|" + scaleV + "|" + mode + "|" + (lateForShaders ? "late" : "")
                + "|" + (lateBucketTag ? "lb" : "");
        RenderType cached = BY_CHROMATIC.computeIfAbsent(key, k -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_chromatic|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    mode,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            // The SAME shard the 54 PNG designs use, which is the whole point: under a pack this
                            // resolves to the pack's own GLINT program, so chromatic draws in-gbuffer and the pack
                            // TAA-resolves it like everything else. Binding our private program here is what
                            // Iris colour-masked to nothing, and what the post-composite replay existed to undo.
                            .setShaderState(GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(white, false, false) {
                                @Override public void setupRenderState() {
                                    // Resolved per draw rather than captured: this marks the config wanted for the
                                    // next bake and keeps it warm in the baker's LRU. Filtering and GL_REPEAT are
                                    // set on the baked texture itself (ChromaticTextureBaker.configureTexture), so
                                    // super's blur/mipmap binding is deliberately skipped.
                                    RenderSystem.setShaderTexture(0, ChromaticTextureBaker.textureId(layer, colors));
                                    // White on every path: the slick carries its own colour, so there is nothing to
                                    // tint. The shell path used to pre-boost here to fight the shell's overdraw. It
                                    // draws on top of the shell now, so there is no overdraw left to fight.
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            // Translucent shell (slime) chromatic glint: no depth write, CULL, LEQUAL and the lift,
                            // flushed late on top of the shell. Exactly its texture twin. See forHorseArmorGlint for
                            // why all four are load-bearing and why the lift is only safe in that combination.
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(lateForShaders ? CULL : NO_CULL)
                            .setDepthTestState(lateForShaders ? LEQUAL_DEPTH_TEST : EQUAL_DEPTH_TEST)
                            .setLayeringState(lateForShaders ? SHELL_GLINT_LIFT_LAYERING : layering)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_chromatic_texturing|" + k.hashCode(),
                                    () -> setChromaticMatrix(layer, scaleU, scaleV), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
        // Both flags want the same LINES bucket now, for different reasons: lateForShaders so the shell glint lands
        // on top of the shell, lateBucketTag so an EK decoration glint flushes after its own depth pre-pass.
        if (lateForShaders || lateBucketTag) tagAsLateRenderForShaders(cached);
        return cached;
    }

    // LOAD-BEARING: every glint RenderType here pairs GLINT_TRANSPARENCY with a shader whose JSON declares
    // that SAME blend: customglint:glint for the texture glints, customglint:chromatic for these. Neither may
    // bind vanilla's rendertype_glint, and neither may drop its "blend" section. Why:
    //
    // ShaderInstance.apply() calls blend.apply() from INSIDE the draw (BufferUploader.drawWithShader), i.e.
    // AFTER setupRenderState ran the transparency shard, so the shader's declared blend OVERRIDES the
    // RenderType's. BlendMode.apply() then compares against a STATIC lastApplied and no-ops when it equals()
    // it, so the effective blend depends on which shader drew last, globally. Two ways that bites:
    //   - No "blend" section parses to BlendMode() with opaque=true, whose apply() calls disableBlend(). A
    //     glint drawn with blend OFF replaces the item surface instead of adding to it: the item reads as a
    //     solid glint-coloured silhouette with its texture gone. That was the "chromatic fills, but only if a
    //     texture glint drew first this frame" bug: the glint pinned lastApplied non-opaque, so chromatic's
    //     opaque mode flipped it back and disabled blend.
    //   - Vanilla's rendertype_glint declares srcalpha/1-srcalpha, so binding it overrides our additive with
    //     an alpha blend on some draws but not others, purely by draw order.
    // Declaring the shard's exact blend (blendFuncSeparate(SRC_COLOR, ONE, ZERO, ONE) -> srccolor/one +
    // srcalpha zero/one) on both shaders makes apply() either a no-op, leaving the shard's state, or a
    // re-apply of that identical state: same GL either way, in any draw order.
    //
    // Note lastApplied is static and NOT GL state, so a GL probe in a RenderType shard reads perfectly correct
    // blend right up to the broken draw. Do not go looking for this class of bug there.
    /** Noise UV scale for armor / elytra / horse-armor / entity-body chromatic. */
    private static final float CHROMATIC_MODEL_UV_SCALE = 8.0f;

    // The slime outer shell is the one surface a glint cannot simply draw onto. Its rules live on the shards in
    // forHorseArmorGlint; the short version is LINES bucket (on top of the shell), LEQUAL + a lift to clear the
    // shell's coplanar depth, CULL so the lift doesn't flicker, and never a depth write.
    //
    // There used to be a SHELL_GLINT_SHADER_BOOST here, a 2.6x pre-brighten of the shell glint's colour. It
    // existed because the glint drew UNDER the shell and was attenuated by it, and it never worked: both call
    // sites clamped at Math.min(1, colour*BOOST), so any bright colour saturated and came out unboosted, which is
    // why raising 1.6 to 2.6 did nothing. Drawing the glint on top of the shell removes the attenuation it was
    // compensating for. Do not reintroduce a boost to paper over a dim shell glint; find what is eating it.
    //
    // TRIED (do NOT retry): replacing this whole scheme with a stencil two-pass: mask the shell's visible
    // pixels in OPAQUE_DECAL, then draw the glint in the LINES bucket with NO_DEPTH_TEST + stencil EQUAL, so it
    // lands on top of the shell undimmed. Dropping the depth write cost the view-independent self-occlusion this
    // RT's COLOR_DEPTH_WRITE provides, and produced exactly what the note above predicts: every face visible
    // from every side, plus angle-dependent dropout. The stencil mark is not a substitute for the depth write.
    //
    // (That note predates CULL. Its "every face visible from every side" was NO_CULL competing with itself, which
    // culling settles, so the two-pass is no longer structurally doomed. It is still not worth retrying: a stencil
    // route needs a reserved value, and nextStencilSlot() already hands outlines 1..255 across the whole byte with
    // mount armor holding 0x80, so a third consumer means bit-per-item isolation. The lift needs no stencil.)

    // How far the shell glint rides toward the camera, as a view-space scale, and the ONE knob for this surface.
    // It has to clear the shell's own depth, and the number is set by a shader pack rather than by us.
    //
    // Complementary Reimagined pulls slimes toward the camera in its entity program (gbuffers_entities.glsl):
    //     } else if (entityId == 50084) { // Slime, Chicken
    //         gl_Position.z -= 0.00015;
    //     }
    // gated on FLICKERING_FIX, which its common.glsl defines unconditionally, with entity.50084=slime chicken in
    // entity.properties. The shell picks that shift up. Our glint goes through gbuffers_armor_glint, a bare
    // ftransform() with no such mutation, so the shell lands IN FRONT of the glint and LEQUAL loses every fragment.
    // BSL, Bliss and Photon have no gl_Position.z mutation at all, which is the whole reason this was ever a
    // Complementary-only bug. Nothing about Iris ignoring VIEW_OFFSET_Z_LAYERING was true: the shard works fine
    // there, it was just outgunned 6 to 1. Any note claiming that shard is "a no-op under Iris" is measuring this.
    //
    // Both pushes are the same shape, which is what makes one constant enough. The pack's is dz_ndc = -1.5e-4/|z|,
    // and a view-space scale S gives dz_ndc = 2n*(S-1)/|z| with 2n = 0.1 (the far plane cancels out of 2fn/(f-n),
    // so render distance is irrelevant). Beating it needs (1-S) > 1.5e-3. Vanilla VIEW_OFFSET_Z_LAYERING's
    // 0.99975586 manages only 2.4e-5 of ndc against the pack's 1.5e-4, hence the 6x shortfall. 0.997 gives 3.0e-4,
    // i.e. 2x margin, and matched shape holds that margin at every range.
    //
    // Cost: the glint rides (1-S)*|z| = 0.003*|z| blocks toward the camera, so ~0.03 blocks at 10 and 0.3 at 100,
    // and draws 0.3% smaller on screen. Both are sub-pixel on a slime. Lower S if some pack shifts slimes harder,
    // raise it toward 1 if a glint bleeds through a wall.
    private static final float SHELL_GLINT_LIFT_SCALE = 0.997f;

    // Vanilla's VIEW_OFFSET_Z_LAYERING with a bigger scale, for the reason above. A view-space scale, not a
    // glDepthRange squeeze: the pack's push scales with distance, so a flat window-depth bias would be too small
    // up close and bleed through walls far away. This one tracks it.
    private static final RenderStateShard.LayeringStateShard SHELL_GLINT_LIFT_LAYERING =
            new RenderStateShard.LayeringStateShard("customglint_shell_glint_lift",
                    () -> {
                        PoseStack stack = RenderSystem.getModelViewStack();
                        stack.pushPose();
                        stack.scale(SHELL_GLINT_LIFT_SCALE, SHELL_GLINT_LIFT_SCALE, SHELL_GLINT_LIFT_SCALE);
                        RenderSystem.applyModelViewMatrix();
                    },
                    () -> {
                        RenderSystem.getModelViewStack().popPose();
                        RenderSystem.applyModelViewMatrix();
                    });

    /** Returns the buffer a chromatic layer's geometry should be fed into: a straight {@code src.getBuffer(crt)}
     *  on every path.
     *
     *  <p>It used to hand the geometry to a post-composite replay under an active pack, because a chromatic draw
     *  bound our own private program, which Iris colour-masks to nothing during the level pass. The slick is a
     *  baked texture now ({@link ChromaticTextureBaker}), so chromatic binds {@link #GLINT_SHADER_SHARD} like the
     *  54 PNG designs, resolves to the pack's own GLINT program, and draws in-gbuffer at full strength, with the
     *  pack's TAA resolving it, which the replay by construction could never do.
     *
     *  <p>Kept as the call site every fan-out already routes through (core mixins, EK, Mekanism, backpacks,
     *  EntityGlintRender) rather than churning them all to {@code getBuffer}, and as the one place to re-introduce
     *  special handling if a chromatic surface ever needs it again. */
    public static VertexConsumer chromaticWorldBuffer(MultiBufferSource src, RenderType crt) {
        return src.getBuffer(crt);
    }

    /** Flat item / 3D held-item chromatic glint (EQUAL depth, no polygon offset). isItem mirrors
     *  {@link #forGlint}'s atlas-calibrated scale so the slick density matches the texture glints. */
    public static RenderType forChromaticGlint(Data glint, int layerIdx, boolean isItem) {
        if (chromaticShader == null) return null;
        // Flat item: uvScale = atlasW/16 (per-axis atlasH/16) cancels the block-atlas sprite compression so a
        // 16px sprite spans patternScale UV units → identical GUI + world. 3D items (trident) keep uvScale 1.0.
        float scaleU = 1.0f, scaleV = 1.0f;
        if (isItem) {
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            scaleU = atlas.width / 16.0f;
            scaleV = atlas.height / 16.0f;
        }
        return chromaticRT(glint.layers()[layerIdx], "item|" + isItem + "|L" + layerIdx, scaleU, scaleV, NO_LAYERING);
    }

    /** Worn-armor chromatic glint (EQUAL depth + VIEW_OFFSET_Z_LAYERING, matching armorCutoutNoCull). */
    public static RenderType forChromaticArmorGlint(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "armor|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                VIEW_OFFSET_Z_LAYERING);
    }

    /** Horse-armor / entity-body chromatic glint (EQUAL depth + NO_LAYERING, matching entityCutoutNoCull). */
    public static RenderType forChromaticEntityGlint(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "entity|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                NO_LAYERING);
    }

    /** Stencil-gated chromatic mount-armor glint (vanilla horse barding / IaF mounts). Chromatic's baked slick is
     *  one full-colour texture rather than the per-colour grayscale fan-out {@link #forMountArmorGlint} builds, so
     *  this stays its own factory, but it shares that one's stencil bit 0x80 EQUAL test, so the draw only lands on
     *  the armor texels {@link #forMountArmorStencilMask} marked. The mask is needed because this geometry is not
     *  this surface: HorseArmorLayer renders the whole horse model with the barding texture, so the armor mesh is
     *  also the saddle and body mesh, and only the barding texture's alpha tells them apart.
     *
     *  @param armorTex retained for call-site symmetry with {@link #forMountArmorStencilMask}; no longer read. It
     *                  used to be alpha-tested by the post-composite sibling, which stood in for the stencil bit
     *                  because that bit does not survive the composite. Chromatic draws in-pass now, so the stencil
     *                  itself does the gating again, the same way the texture glint always has. */
    public static RenderType forChromaticMountArmorGlint(Data glint, int layerIdx, ResourceLocation armorTex) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "mount|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                mountArmorGlintTestLayering());
    }

    /** Chromatic armor-decoration glint for the SHADER-PACK path (Epic Knights). Mirrors
     *  {@code EpicKnightsGlintRT.forDecorationGlintShader} exactly: EQUAL depth against the decoration depth
     *  pre-pass, no layering (the pre-pass writes raw projected depth, so both compare raw depths and match), and
     *  the LINES-bucket late tag so it flushes after the decoration's own depth lands under FullyBuffered. */
    public static RenderType forChromaticDecorationGlint(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "ek-deco-sh|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE,
                CHROMATIC_MODEL_UV_SCALE, NO_LAYERING, VertexFormat.Mode.QUADS, false, true);
    }

    /** Chromatic armor-decoration glint for the STENCIL paths (Epic Knights, shaders off / no shader mod).
     *  {@code layering} carries the decoration's stencil-slot EQUAL test; the caller caches the result and registers
     *  the fixed buffer, matching the texture-glint factories beside it in EK.
     *
     *  <p>Uncached, unlike every other chromatic RT. EK bakes a fresh {@link #nextStencilSlot()} into
     *  {@code layering} every frame, so the key moves with slot allocation order: through {@link #BY_CHROMATIC}, an
     *  LRU capped at {@link #CHROMATIC_CACHE_CAP} whose eviction recycles the RT's builder, that rebuilds
     *  RenderTypes every frame on the hot path.
     *
     *  <p>LEQUAL rather than the EQUAL the built-in chromatic RTs use: EK's decoration depth misses EQUAL by an FP
     *  epsilon, which is why the stencil exists at all (see EpicKnightsGlintRT's header). The stencil does the
     *  trimming here, not the depth func. */
    public static RenderType buildChromaticStencilGlint(Layer layer, String key,
                                                        RenderStateShard.LayeringStateShard layering) {
        if (chromaticShader == null) return null;
        int[] colors = chromaticColors(layer.colors());
        final ResourceLocation white = getWhiteTexture();
        return RenderType.create(
                MOD_ID + ":custom_chromatic_stencil|" + key.hashCode(),
                DefaultVertexFormat.POSITION_TEX,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(GLINT_SHADER_SHARD)
                        .setTextureState(new TextureStateShard(white, false, false) {
                            @Override public void setupRenderState() {
                                RenderSystem.setShaderTexture(0, ChromaticTextureBaker.textureId(layer, colors));
                                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                            }
                            @Override public void clearRenderState() {
                                super.clearRenderState();
                                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                            }
                        })
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setLayeringState(layering)
                        .setTransparencyState(GLINT_TRANSPARENCY)
                        .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_chromatic_stencil_texturing|" + key.hashCode(),
                                () -> setChromaticMatrix(layer, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE),
                                RenderSystem::resetTextureMatrix))
                        .createCompositeState(false));
    }

    /** TRIANGLES-mode entity-body chromatic glint, for renderers that draw through a triangle-list
     *  RenderType (Epic Fight patched entity meshes). */
    public static RenderType forChromaticEntityGlintTriangles(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "entity|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                NO_LAYERING, VertexFormat.Mode.TRIANGLES);
    }

    /** Translucent-surface entity chromatic glint (slime outer shell). Now exactly its texture-glint twin
     *  {@link #forEntityGlintTranslucent}, one design apart: the same LINES bucket on top of the shell, the same
     *  LEQUAL plus lift to clear its coplanar depth, the same CULL, and the same refusal to write depth.
     *
     *  <p>It used to defer under a pack instead, because our private chromatic program landed in Iris's gbuffer and
     *  the pack's lighting dimmed it. The slick is a baked texture now, so this rides the pack's own GLINT program
     *  like the texture glint always did, and the whole deferred path (with the in-shader occlusion test its
     *  sibling needed, since a shell cannot depth-test against the composited buffer at all) is gone. Only
     *  EntityGlintRender picks the translucent variants, and only while a pack is active. */
    public static RenderType forChromaticEntityGlintTranslucent(Data glint, int layerIdx, boolean triangles) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "entity|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                NO_LAYERING, triangles ? VertexFormat.Mode.TRIANGLES : VertexFormat.Mode.QUADS, true);
    }

    public static RenderType forArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "armor|" + layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx + "|" + layer.scrollDir() + "|" + layer.scrollOffset();
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_ARMOR_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_armor_glint|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    bindGlintTexture(k, tex, holder);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            // armorCutoutNoCull draws with VIEW_OFFSET_Z_LAYERING (polygonOffset -1,-10),
                            // writing depth as D-ε. Match it: EQUAL depth + the same layering so the glint
                            // tests at D-ε too. LEQUAL is visible but bleeds through transparent cutout holes;
                            // EQUAL without the layering tests at raw D and the glint disappears.
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_armor_glint_texturing",
                                    () -> setModelScrollMatrix(layer, colorIdx, 1.0f), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
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

    /** TRIANGLES-mode entity-body glint, for renderers that draw through a triangle-list RenderType
     *  (Epic Fight patched entity meshes). Same render state as {@link #forEntityGlint}; only the
     *  primitive mode differs, so a triangle vertex stream assembles correctly instead of shattering
     *  into quads. */
    public static RenderType forEntityGlintTriangles(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx, VertexFormat.Mode.TRIANGLES, false);
    }

    /**
     * Entity glint for a TRANSLUCENT base surface (e.g. the slime's outer shell), under an active shader pack
     * only. A distinct RT instance from the opaque one, so the opaque path's state is never touched.
     *
     * <p>One rule governs this surface, and {@link #forHorseArmorGlint}'s shard comments carry the detail: the
     * glint draws AFTER the shell (LINES bucket) so the shell cannot dim it, and it earns the right to by
     * writing no depth at all. Everything else follows from that. LEQUAL then tests against the shell's own
     * coplanar depth, so it needs the lift to land in front rather than behind, and the lift needs CULL or the
     * shell's near and far faces flicker against each other.
     *
     * <p>Both halves of this were shipped separately and both failed. Drawing before the shell (OPAQUE_DECAL)
     * gave a stable depth reference and a glint too dim to see. Nudging depth in front of the shell made it
     * bright and erased the shell on BSL, Bliss and Proton. They are the same mechanism pulling opposite ways,
     * and drawing late is what stops having to choose.
     */
    public static RenderType forEntityGlintTranslucent(Data glint, int layerIdx, float[] frameColor, int colorIdx,
                                                       boolean triangles) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx,
                triangles ? VertexFormat.Mode.TRIANGLES : VertexFormat.Mode.QUADS, true);
    }

    // Horse armor uses entityCutoutNoCull (no polygon offset / no VIEW_OFFSET_Z_LAYERING).
    // forArmorGlint uses EQUAL + VIEW_OFFSET_Z_LAYERING: wrong offset → invisible on horses.
    // This variant keeps EQUAL + NO_LAYERING so depth matches, and scale 1.0 matches forArmorGlint visually.
    public static RenderType forHorseArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx, VertexFormat.Mode.QUADS, false);
    }

    private static RenderType forHorseArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx,
                                                 VertexFormat.Mode mode, boolean lateForShaders) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "horse|" + layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx + "|" + layer.scrollDir() + "|" + layer.scrollOffset() + "|" + mode + "|" + (lateForShaders ? "late" : "");
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_HORSE_ARMOR_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_horse_armor_glint|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    mode,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    bindGlintTexture(k, tex, holder);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            // The shell glint writes NO depth, on any pack. Drawing late leaves it nothing to prepass
                            // for, and writing nothing is what guarantees the shell renders exactly as it would with
                            // no glint on it. It is also half of why the lift below is survivable now.
                            .setWriteMaskState(COLOR_WRITE)
                            // CULL is what makes the lift below work, and the pair only works together. A lift was
                            // tried alone and flickered: with NO_CULL the shell's near and far faces rasterise the
                            // same pixel and compete for the LEQUAL, so the winner flips as the camera orbits.
                            // Dropping the far faces leaves one fragment per pixel and the compare is settled by
                            // geometry instead of submission order. Convex cube, so culling IS its self-occlusion.
                            // CULL was also tried alone and "killed far-away glint", which is the lift's job to fix.
                            // Opaque glint keeps NO_CULL.
                            .setCullState(lateForShaders ? CULL : NO_CULL)
                            // Translucent base surface (slime outer shell = entity_translucent), under a pack only.
                            // This flushes in the LINES bucket, AFTER the shell, so the shell can no longer paint
                            // over the glint and dim it. The cost is the depth reference: the buffer now holds the
                            // shell's own depth at these pixels, and LEQUAL against a coplanar surface lands at D+e
                            // and vanishes. That is exactly what the lift below buys back. World occlusion still
                            // comes free from this test, since terrain and bodies in front of the slime sit in the
                            // same buffer. Opaque entity glint keeps EQUAL.
                            .setDepthTestState(lateForShaders ? LEQUAL_DEPTH_TEST : EQUAL_DEPTH_TEST)
                            // The lift, so LEQUAL clears the shell's depth instead of landing behind it. It is not
                            // VIEW_OFFSET_Z_LAYERING: that shard's 0.99976 is 6x too small to clear the slime-
                            // specific clip-z shift Complementary's FLICKERING_FIX gives the shell. See
                            // SHELL_GLINT_LIFT_SCALE for the pack's own code and the arithmetic.
                            //
                            // A lift is only safe because this RT writes no depth. The shard version erased the
                            // shell precisely because it ran in OPAQUE_DECAL WITH a depth write, in FRONT of the
                            // shell, so the shell failed its own LEQUAL against our lifted depth. Here the shell has
                            // already drawn and we write nothing, so the lift moves our own fragments and nothing
                            // else. Never pair a lift with a depth write again. Opaque glint keeps NO_LAYERING.
                            .setLayeringState(lateForShaders ? SHELL_GLINT_LIFT_LAYERING : NO_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_horse_armor_glint_texturing",
                                    () -> setModelScrollMatrix(layer, colorIdx, 1.0f), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
        // LINES is the last bucket the shader mod flushes, after the GENERAL_TRANSPARENT one the shell draws in, so
        // the glint lands on top of the shell rather than underneath it. That ordering is where the brightness comes
        // from, and it replaces an OPAQUE_DECAL tag that bought a stable depth reference and paid in overdraw.
        if (lateForShaders) tagAsLateRenderForShaders(cached);
        return cached;
    }

    /**
     * Stencil-mask write RT for IaF mount armor (dragon / hippogryph / hippocampus). The mount
     * body shares the same EntityModel as the armor layer, so an EQUAL_DEPTH glint RT (the
     * scheme {@link #forHorseArmorGlint} uses for vanilla horse armor) passes depth on every
     * face of the mount, not just the armor. Vanilla horse armor avoids this because its
     * armor mesh is a separate model, but IaF reuses the parent mount model with an
     * alpha-cutout armor texture.
     *
     * This RT renders the parent model with the armor texture through entity-cutout's
     * alpha-discard shader and writes only stencil bit {@code 0x80} at opaque texels;
     * the LayeringStateShard does the GL state in setup/clear so timing is deterministic
     * across BufferSource flushes. Bit 0x80 is paired with {@link #forMountArmorGlint}'s
     * stencil EQUAL 0x80 test, constraining the glint draw to the same armor pixels.
     *
     * Bit isolation: outline slots {@link #nextStencilSlot} use values 1..255 and may set
     * bit 0x80 at silhouettes when their slot lands in 128..255. To stay independent, this
     * mask layering clears bit 0x80 across the framebuffer at setup (mask=0x80, glClear),
     * and writes/reads only that bit (stencilMask=0x80). Outline's lower bits stay intact.
     */
    public static RenderType forMountArmorStencilMask(ResourceLocation tex) {
        RenderType cached = BY_MOUNT_ARMOR_MASK.computeIfAbsent(tex, t -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":mount_armor_mask|" + t.toString().hashCode(),
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                            .setTextureState(new TextureStateShard(t, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(NO_WRITE)
                            .setLayeringState(mountArmorMaskLayering())
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
        return cached;
    }

    private static RenderStateShard.LayeringStateShard mountArmorMaskLayering() {
        return new RenderStateShard.LayeringStateShard(
                "custom_glint_mount_armor_mask",
                () -> {
                    Minecraft.getInstance().getMainRenderTarget().enableStencil();
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glClearStencil(0);
                    if (pendingFrameStencilClear) {
                        GL11.glStencilMask(0xFF);
                        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
                        pendingFrameStencilClear = false;
                    } else {
                        // Clear only our reserved bit so outline slot bits remain intact.
                        GL11.glStencilMask(0x80);
                        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
                    }
                    GL11.glStencilMask(0x80);
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0x80, 0x80);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
                },
                () -> {
                    GL11.glStencilMask(0xFF);
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                });
    }

    /**
     * Stencil-gated armor glint for IaF mounts. Same render state as
     * {@link #forHorseArmorGlint} (EQUAL depth, no polygon offset, glint transparency,
     * glint shader + scrolling matrix) but its layering shard tests stencil bit 0x80
     * EQUAL 1 instead of NO_LAYERING; only the texels marked by
     * {@link #forMountArmorStencilMask} draw.
     */
    public static RenderType forMountArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "mount|" + layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx + "|" + layer.scrollDir() + "|" + layer.scrollOffset();
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_MOUNT_ARMOR_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_mount_armor_glint|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    bindGlintTexture(k, tex, holder);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            .setLayeringState(mountArmorGlintTestLayering())
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_mount_armor_glint_texturing",
                                    () -> setModelScrollMatrix(layer, colorIdx, 1.0f), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
        return cached;
    }

    private static RenderStateShard.LayeringStateShard mountArmorGlintTestLayering() {
        return new RenderStateShard.LayeringStateShard(
                "custom_glint_mount_armor_glint_test",
                () -> {
                    Minecraft.getInstance().getMainRenderTarget().enableStencil();
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0x00);
                    GL11.glStencilFunc(GL11.GL_EQUAL, 0x80, 0x80);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                },
                () -> {
                    GL11.glStencilMask(0xFF);
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                });
    }

    public static RenderType forGlint(Data glint, int layerIdx, float[] frameColor, boolean isItem, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        // Fast path (layer 0 only): skip rebuilding the String key + map probes every frame and just refresh
        // the colour holder the RT's shader closure reads. The memo is keyed by Layer VALUE, so it must NOT
        // serve layerIdx > 0: two identical-value layers at different indices would resolve to the SAME
        // RenderType, and applyGlint would hand VertexMultiConsumer the same delegate twice ("Duplicate
        // delegates" under Sodium/Embeddium). Higher layers take the slow path, whose key includes layerIdx.
        boolean canMemo = layerIdx == 0 && colorIdx < CustomGlint.MAX_COLORS_PER_LAYER;
        int fastSlot = canMemo ? colorIdx * 2 + (isItem ? 1 : 0) : -1; // 2 slots per color: {non-item, item}
        if (canMemo) {
            GlintRT[] fast = GLINT_FAST.get(layer);
            if (fast != null && fast[fastSlot] != null) {
                GlintRT gr = fast[fastSlot];
                System.arraycopy(frameColor, 0, gr.holder(), 0, 4);
                return gr.rt();
            }
        }
        if (getTexture(layer.design()) == null) return null;
        String key = layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.interpolate() + "|" + isItem + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx + "|" + layer.scrollDir() + "|" + layer.scrollOffset();
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_GLINT.computeIfAbsent(key, k -> {
            // isItem=true → flat item model (sword, tool, etc.) → scale 8.0 matches vanilla glint().
            // isItem=false → 3D entity model (trident, etc.) → 1.0 gives visible pattern detail;
            // vanilla entityGlint() uses 0.16 but that tiles too infrequently for custom designs.
            // Atlas dims are read here (on cache miss only); they're baked into the texturing shard below,
            // and the cache is cleared on resource reload if the atlas ever resizes. 1024x512 is the vanilla
            // reference block-atlas size the 8.0 scale is calibrated against, so designs hold aspect ratio
            // when another mod inflates the atlas to a non-square size.
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            int atlasW = atlas.width;
            int atlasH = atlas.height;
            float scaleU = isItem ? (8.0f * atlasW / 1024.0f) : 1.0f;
            float scaleV = isItem ? (8.0f * atlasH / 512.0f) : 1.0f;
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_glint|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    bindGlintTexture(k, tex, holder);
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
                                    () -> setItemScrollMatrix(layer, colorIdx, scaleU, scaleV),
                                    RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
        if (canMemo) {
            GlintRT[] fast = GLINT_FAST.get(layer);
            // 16 slots = MAX_COLORS_PER_LAYER colors × 2 (non-item, item), indexed by fastSlot above.
            if (fast == null) { fast = new GlintRT[CustomGlint.MAX_COLORS_PER_LAYER * 2]; GLINT_FAST.put(layer, fast); }
            fast[fastSlot] = new GlintRT(cached, holder);
        }
        return cached;
    }

    public static int computeAnimatedColor(Data glint, int layerIdx) {
        return computeAnimatedColor(glint, layerIdx, 0.0f);
    }

    /** As {@link #computeAnimatedColor(Data, int)} but shifts the loop by {@code phaseFraction} of a full
     *  cycle (0.5 = half a loop). Used by the glow ring so it never shows the glint's own colour. */
    public static int computeAnimatedColor(Data glint, int layerIdx, float phaseFraction) {
        Layer layer = glint.layers()[layerIdx];
        return animateColors(layer.colors(), layer.speed(), layer.interpolate(), phaseFraction);
    }

    /** Animates through an int[] color array using game time. Default speed=1, interpolate=true. */
    public static int computeAnimatedGlowColor(int[] colors) {
        return computeAnimatedGlowColor(colors, 1.0f, true);
    }

    /** Animates through an int[] color array using game time, at {@code speed} (a higher speed cycles
     *  faster, mirroring the glint layer speed) and either blending between colors ({@code interpolate}) or
     *  stepping hard between them. */
    public static int computeAnimatedGlowColor(int[] colors, float speed, boolean interpolate) {
        return computeAnimatedGlowColor(colors, speed, interpolate, 0.0f);
    }

    /** As {@link #computeAnimatedGlowColor(int[], float, boolean)} but shifts the loop by
     *  {@code phaseFraction} of a full cycle (0.5 = half a loop). */
    public static int computeAnimatedGlowColor(int[] colors, float speed, boolean interpolate, float phaseFraction) {
        return animateColors(colors, speed, interpolate, phaseFraction);
    }

    /** Core colour-loop animator. Cycles {@code colors} on game time; {@code speed} scales the rate,
     *  {@code interpolate} blends vs. steps between colours, and {@code phaseFraction} shifts the whole
     *  loop by that fraction of a full cycle (0 = no shift, matching the original animation exactly). */
    private static int animateColors(int[] colors, float speed, boolean interpolate, float phaseFraction) {
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
        if (!Float.isFinite(speed) || speed <= 0) speed = 1.0f;
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        // One full color cycle spans 20 ticks (1 second) per color at speed 1.
        float totalTicks = (20.0f * colors.length) / speed;
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        // Shift by phaseFraction of the loop and wrap back into [0, length).
        t += phaseFraction * colors.length;
        t = ((t % colors.length) + colors.length) % colors.length;
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

    /** Guards re-entrance during the glow-outline special-item capture: set true while a special / 3D
     *  BEWLR item is re-rendered into the recording buffer (see {@code ItemRendererMixin}), so the
     *  re-render's nested {@code ItemRenderer.render} RETURN doesn't recurse into capture and
     *  {@code applyGlint} routes to the bare recording buffer instead of fanning glint layers. */
    public static final ThreadLocal<Boolean> IN_OUTLINE = ThreadLocal.withInitial(() -> false);

    /** Phase offset (fraction of a full colour loop) applied to the glow OUTLINE ring so it never shows the
     *  same colour as the glint's own animated tint at the same instant. Half a cycle = maximum contrast:
     *  when the inner glint is on one colour, the ring is on the opposite side of the loop. */
    public static final float GLOW_RING_PHASE_OFFSET = 0.5f;

    /** Glow-outline colour for an item: prefers the Glow Trim colours ({@code glowColors} NBT),
     *  falls back to glint layer 0, else white. Consumed by the post-process glow outline
     *  ({@link GlowOutlineRenderer}). The ring is shifted by {@link #GLOW_RING_PHASE_OFFSET} so it stays
     *  out of phase with the glint's inner tint (which animates at offset 0). */
    public static int resolveGlowColor(ItemStack stack) {
        int[] glow = CustomGlint.getGlowColors(stack);
        if (glow.length > 0)
            return computeAnimatedGlowColor(glow, CustomGlint.getGlowSpeed(stack),
                    CustomGlint.getGlowInterpolate(stack), GLOW_RING_PHASE_OFFSET);
        Data glint = CustomGlint.readCached(stack);
        return glint != null ? computeAnimatedColor(glint, 0, GLOW_RING_PHASE_OFFSET) : 0xFFFFFFFF;
    }

    /** Once-per-frame stencil-clear gate. Armed at frame start by {@code CustomGlintClientInit}'s
     *  RenderTickEvent.START; the first stencil-mask setup of the frame clears the stencil buffer
     *  and unsets it. Used by the mount-armor glint mask ({@link #forMountArmorStencilMask}) and the
     *  Epic Knights decoration glint mask, which constrain the glint to the armor/decoration shape. */
    public static volatile boolean pendingFrameStencilClear = true;

    /** Reload hooks appended by compat modules; invoked by {@link #clearTextures()} so each
     *  compat can release its own {@code DynamicTexture}s without {@code CustomGlintRenderer}
     *  needing to know about them. */
    public static final List<Runnable> additionalReloadCleanup = new CopyOnWriteArrayList<>();

    // Disables both color and depth writes, for the stencil mask pass that constrains glint to
    // the armor/decoration silhouette (mount armor, EK decorations).
    private static final RenderStateShard.WriteMaskStateShard NO_WRITE =
            new RenderStateShard.WriteMaskStateShard(false, false);

    // Per-glint stencil-slot pool
    // Allocates a unique stencil value (1..255) per glint-mask call so overlapping decoration
    // glints (multiple Epic Knights decorations on one figure) don't share a stencil silhouette.
    private static int stencilSlotCounter = 0;

    /** Reserve a unique stencil slot value (1..255) for this glint-mask call. Wraps if exceeded. */
    public static int nextStencilSlot() {
        stencilSlotCounter++;
        if (stencilSlotCounter > 255) stencilSlotCounter = 1;
        return stencilSlotCounter;
    }

    /** Frame-start reset; called from {@code CustomGlintClientInit}. */
    public static void resetStencilSlots() { stencilSlotCounter = 0; }

    // OutputStateShard that binds the main render target. Exposed for compat glint RTs (Epic Knights
    // decoration depth pre-write under a shader pack) that need the Oculus-no-pack-safe FBO binding.
    private static final int[] SAVED_FBO = new int[1];
    public static final RenderStateShard.OutputStateShard FORCE_MAIN_TARGET =
            new RenderStateShard.OutputStateShard("custom_glint_force_main_target",
                () -> {
                    SAVED_FBO[0] = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                    Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                },
                () -> {
                    GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, SAVED_FBO[0]);
                });

    private CustomGlintRenderer() { super("", () -> {}, () -> {}); }

    // Shader-mod detection
    // Reflective so we don't need a compileOnly dep on the shader mod. Common shader
    // mods expose the same public detection surface, resolved once and cached as a
    // reflect.Method (see SHADER_GET_INSTANCE / SHADER_IS_IN_USE) and reused per call.
    //
    // Consumed by the glow-outline drain timing (LevelRendererMixin routes the world drain
    // through GlowOutlineRenderer.drainWorldShaderPack when a pack is active) and by the Epic
    // Knights decoration glint path, which swaps to a depth-prewrite + late-bucket shader RT
    // under a pack instead of the plain EQUAL-depth glint.
    private static volatile boolean SHADER_LOOKUP_DONE = false;
    private static volatile Method SHADER_GET_INSTANCE = null;
    private static volatile Method SHADER_IS_IN_USE = null;

    public static boolean isShaderPackActive() {
        if (!SHADER_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_LOOKUP_DONE) {
                    try {
                        // Package path applies on Forge as well. Do not change it.
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

    /**
     * True iff a shader mod is installed (detection API class resolved), regardless of whether
     * a shaderpack is currently active. The shader mod replaces the buffer-source pipeline
     * whenever it's loaded, so the Epic Knights decoration glint switches to its late-bucket
     * shader RT on mod presence, not just on an active pack.
     */
    public static boolean isShaderModInstalled() {
        if (!SHADER_LOOKUP_DONE) isShaderPackActive();
        return SHADER_IS_IN_USE != null;
    }

    // Iris/Oculus renders the first-person hand under its OWN captured gbuffer projection (the world /
    // sprint-FOV one), not the vanilla hand-FOV projection that RenderSystem.getProjectionMatrix() still
    // holds. The glow-outline FP drain must replay the item silhouette under that same projection or the
    // ring self-occludes (draws behind the item) and offsets on sprint. Read it reflectively; no compileOnly
    // Oculus dep; null when no shader mod is present or the class can't be resolved.
    private static volatile boolean IRIS_PROJ_LOOKUP_DONE = false;
    private static volatile Object IRIS_CRS_INSTANCE = null;
    private static volatile Method IRIS_GET_GBUFFER_PROJ = null;

    /** The projection Iris is currently drawing gbuffers (incl. the FP hand) with, or null when a shader mod
     *  isn't present / resolvable. The returned matrix is Iris's live instance: copy it, don't retain it. */
    @Nullable
    public static Matrix4f getShaderGbufferProjection() {
        if (!IRIS_PROJ_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!IRIS_PROJ_LOOKUP_DONE) {
                    try {
                        Class<?> crs = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
                        IRIS_CRS_INSTANCE = crs.getField("INSTANCE").get(null);
                        IRIS_GET_GBUFFER_PROJ = crs.getMethod("getGbufferProjection");
                    } catch (Throwable ignored) {
                        IRIS_CRS_INSTANCE = null;
                        IRIS_GET_GBUFFER_PROJ = null;
                    }
                    IRIS_PROJ_LOOKUP_DONE = true;
                }
            }
        }
        if (IRIS_GET_GBUFFER_PROJ == null || IRIS_CRS_INSTANCE == null) return null;
        try {
            Object m = IRIS_GET_GBUFFER_PROJ.invoke(IRIS_CRS_INSTANCE);
            return (m instanceof Matrix4f) ? (Matrix4f) m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // Under a shader pack the first-person hand is not drawn via GameRenderer.renderItemInHand /
    // ItemInHandRenderer.renderHandsWithItems. Iris relocates it into its own HAND_SOLID / HAND_TRANSLUCENT
    // rendering phase inside the gbuffer pass, with a THIRD_PERSON display context. So our renderItemInHand /
    // renderHandsWithItems flags never arm and the held item misroutes to the world outline queue. Iris exposes
    // the current phase; when it is a HAND phase the item being drawn IS the FP held item. Reflective; no dep.
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
    // → DECAL → WATER_MASK → LINES). The geometry a glint draws over uses GENERAL_TRANSPARENT; if the
    // glint RT also sits there, the order within the same bucket is undefined → the glint can flush
    // before the base geometry → depth buffer empty when the glint draws → its EQUAL depth test can't
    // line up. Tagging the glint RT as LINES (last bucket) forces the shader mod to flush ALL base
    // geometry first, then the glint. Depth ordering works in every camera context (1P / 3P / GROUND).
    // Used by the Epic Knights decoration depth-prewrite + glint RTs. Reflective to avoid compileOnly.
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

    // True only between LevelRenderer.renderLevel HEAD and RETURN, i.e. while the 3D world (and the
    // entities in it) are being drawn. Compat that re-renders worn armor/curios through a mod's own
    // renderer (Mekanism special armor, Artifacts) uses this to skip the inventory player preview and
    // GUI item icons, whose GUI-ortho context would otherwise stretch our entity-space glint/glow into a
    // giant projected ray. Set by LevelRendererMixin; reset each frame at RenderTick START.
    private static volatile boolean renderingWorld = false;

    public static boolean isRenderingWorld() { return renderingWorld; }

    public static void setRenderingWorld(boolean value) { renderingWorld = value; }

    // Iris's own "am I overriding/masking shaders right now" gate, mirrored exactly; see resolveGlintShader
    // for why this and not our renderingWorld flag. Iris's MixinGameRenderer asks it as
    //   pipeline instanceof ShaderRenderingPipeline && ((ShaderRenderingPipeline) pipeline).shouldOverrideShaders()
    // so the instanceof matters: a non-shader pipeline must read false, not throw. Reflective (no compileOnly
    // dep on Oculus); false whenever Iris is absent or no pack is active.
    private static volatile boolean IRIS_OVERRIDE_LOOKUP_DONE = false;
    private static volatile Method IRIS_OVR_PIPELINE_MANAGER = null;
    private static volatile Method IRIS_OVR_PIPELINE_NULLABLE = null;
    private static volatile Method IRIS_OVR_SHOULD_OVERRIDE = null;
    private static volatile Class<?> IRIS_OVR_SHADER_PIPELINE = null;

    public static boolean irisShouldOverrideShaders() {
        if (!IRIS_OVERRIDE_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!IRIS_OVERRIDE_LOOKUP_DONE) {
                    try {
                        IRIS_OVR_PIPELINE_MANAGER = Class.forName("net.irisshaders.iris.Iris")
                                .getMethod("getPipelineManager");
                        IRIS_OVR_PIPELINE_NULLABLE = Class.forName("net.irisshaders.iris.pipeline.PipelineManager")
                                .getMethod("getPipelineNullable");
                        IRIS_OVR_SHADER_PIPELINE = Class.forName("net.irisshaders.iris.pipeline.ShaderRenderingPipeline");
                        IRIS_OVR_SHOULD_OVERRIDE = IRIS_OVR_SHADER_PIPELINE.getMethod("shouldOverrideShaders");
                    } catch (Throwable ignored) {
                        IRIS_OVR_PIPELINE_MANAGER = null;
                        IRIS_OVR_PIPELINE_NULLABLE = null;
                        IRIS_OVR_SHADER_PIPELINE = null;
                        IRIS_OVR_SHOULD_OVERRIDE = null;
                    }
                    IRIS_OVERRIDE_LOOKUP_DONE = true;
                }
            }
        }
        if (IRIS_OVR_SHOULD_OVERRIDE == null) return false;
        try {
            Object pm = IRIS_OVR_PIPELINE_MANAGER.invoke(null);
            if (pm == null) return false;
            Object pipe = IRIS_OVR_PIPELINE_NULLABLE.invoke(pm);
            if (!IRIS_OVR_SHADER_PIPELINE.isInstance(pipe)) return false;
            return (Boolean) IRIS_OVR_SHOULD_OVERRIDE.invoke(pipe);
        } catch (Throwable t) {
            return false;
        }
    }

}
