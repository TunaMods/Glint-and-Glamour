package net.tunamods.customglint.common;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

/**
 * Server-safe data API for Glint & Glamour: NBT read/write, color and design constants, and the
 * auto-apply registries. The client-only rendering pipeline is
 * {@link net.tunamods.customglint.common.client.CustomGlintRenderer}, linked by full name here so this
 * class carries no client import and stays loadable on a dedicated server.
 *
 * <p>Naming follows what each pair of methods actually does, not one convention throughout:
 * read/write for plain value transfer, get/set for mutable stored state, from/to for NBT
 * serialization.
 */
public final class CustomGlint {

    private CustomGlint() {}

    // Scroll directions

    /** {@link Layer#scrollDir} values: the direction the animated glint drifts. {@code STATIC} freezes the
     *  animation and uses {@link Layer#scrollOffset} as a manual position instead. The eight compass values
     *  go counter-clockwise from East. Default is {@code E} (the historical horizontal scroll). */
    public static final int SCROLL_STATIC = 0, SCROLL_E = 1, SCROLL_NE = 2, SCROLL_N = 3, SCROLL_NW = 4,
            SCROLL_W = 5, SCROLL_SW = 6, SCROLL_S = 7, SCROLL_SE = 8;

    /** A glint never cycles more than this many colors per layer (the renderer fans out one draw per color
     *  and loops); every input path enforces it, and readInner clamps hand-authored NBT to it. */
    public static final int MAX_COLORS_PER_LAYER = 8;

    /** Cap on the number of glint layers read from NBT. Every input path (editor, Glint Table, layer-tear
     *  merge recipe) caps at this, and readInner clamps hand-authored NBT to it. */
    public static final int MAX_LAYERS = 8;

    /** Most-translucent glint alpha (at the 8th glass); 0 glass = fully opaque (255). Shared so the Glint
     *  Table's opacity↔alpha↔glass mapping matches on both the client (preview/cost) and the server (print). */
    public static final int GLINT_ALPHA_MIN = 32;

    /** Glint-Table material cost of a speed / scale value: the number of ± stepper clicks it sits from the 1×
     *  default (0.10 per click below 1×, 0.5 per click above), one redstone / slime each. Both sides tally
     *  this per layer. */
    public static int stepCost(float value) {
        if (value >= 1.0f) return Math.round((value - 1.0f) / 0.5f);
        return Math.round((1.0f - value) / 0.10f);
    }

    /** Glint-Table glass cost for a colour's alpha: the opacity level (0..8) it encodes, i.e. one glass per
     *  opacity click. Inverts the opacity→alpha mapping ({@code alpha = 255 - level*(255-MIN)/8}). */
    public static int glassCost(int alpha) {
        return Math.round((255f - alpha) * 8f / (255f - GLINT_ALPHA_MIN));
    }

    // Layer

    public record Layer(ResourceLocation design, int[] colors, float speed, boolean interpolate,
                        float patternScale, boolean simultaneous, int scrollDir, float scrollOffset, int seed) {
        /** Back-compat constructor: defaults the procedural-chromatic {@link #seed} to 0 (only
         *  {@link #CHROMATIC} layers use it), so the 8-arg call sites keep compiling unchanged. */
        public Layer(ResourceLocation design, int[] colors, float speed, boolean interpolate,
                     float patternScale, boolean simultaneous, int scrollDir, float scrollOffset) {
            this(design, colors, speed, interpolate, patternScale, simultaneous, scrollDir, scrollOffset, 0);
        }
        /** Back-compat constructor: defaults to the historical East scroll with no static offset, so every
         *  existing call site (auto-apply registries, commands, packets) keeps compiling unchanged. */
        public Layer(ResourceLocation design, int[] colors, float speed, boolean interpolate,
                     float patternScale, boolean simultaneous) {
            this(design, colors, speed, interpolate, patternScale, simultaneous, SCROLL_E, 0.0f, 0);
        }

        // Value equality (the record default compares the int[] by identity, which would break
        // renderer cache keys / recipe matching; every other field is value-equal).
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Layer l)) return false;
            return Float.compare(speed, l.speed) == 0 && interpolate == l.interpolate
                    && Float.compare(patternScale, l.patternScale) == 0 && simultaneous == l.simultaneous
                    && scrollDir == l.scrollDir && Float.compare(scrollOffset, l.scrollOffset) == 0
                    && seed == l.seed
                    && Objects.equals(design, l.design) && Arrays.equals(colors, l.colors);
        }
        @Override public int hashCode() {
            return Objects.hash(design, speed, interpolate, patternScale, simultaneous, scrollDir, scrollOffset, seed) * 31
                    + Arrays.hashCode(colors);
        }
    }

    // Data

    public record Data(Layer[] layers) {
        // Value equality over the Layer[] (the record default compares the array by identity, which would
        // break renderer cache keys / recipe matching).
        @Override public boolean equals(Object o) {
            return this == o || (o instanceof Data d && Arrays.equals(layers, d.layers));
        }
        @Override public int hashCode() { return Arrays.hashCode(layers); }
    }

    // Colors

    /** Parses a {@code "RRGGBB"} / {@code "#RRGGBB"} hex string to an opaque ARGB int. For string color
     *  sources, commands, data packs, the config, where the value isn't a compile-time constant. The
     *  named constants below come from {@link DyeColor}; only external string input flows through here. */
    public static int color(String hex) {
        if (hex == null) return WHITE;
        try {
            return Integer.parseUnsignedInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) | 0xFF000000;
        } catch (NumberFormatException e) {
            // Untrusted source (command / data pack / config): fall back to opaque white rather than throw.
            return WHITE;
        }
    }

    /** The named glint colors ARE Minecraft's dye colors, sourced from {@link DyeColor#getTextColor()}
     *  (the vivid per-dye colour) so they track vanilla instead of drifting. 1.20.1's {@code getTextColor()}
     *  returns {@code 0x00RRGGBB} with no alpha byte, so OR in opaque alpha (1.21.1+ already returns ARGB).
     *  {@link #BLACK} is the one exception, see below. */
    private static int dye(DyeColor c) { return 0xFF000000 | c.getTextColor(); }

    public static final int RED        = dye(DyeColor.RED);
    public static final int ORANGE     = dye(DyeColor.ORANGE);
    public static final int YELLOW     = dye(DyeColor.YELLOW);
    public static final int LIME       = dye(DyeColor.LIME);
    public static final int GREEN      = dye(DyeColor.GREEN);
    public static final int CYAN       = dye(DyeColor.CYAN);
    public static final int LIGHT_BLUE = dye(DyeColor.LIGHT_BLUE);
    public static final int BLUE       = dye(DyeColor.BLUE);
    public static final int PURPLE     = dye(DyeColor.PURPLE);
    public static final int MAGENTA    = dye(DyeColor.MAGENTA);
    public static final int PINK       = dye(DyeColor.PINK);
    public static final int BROWN      = dye(DyeColor.BROWN);
    public static final int WHITE      = dye(DyeColor.WHITE);
    public static final int LIGHT_GRAY = dye(DyeColor.LIGHT_GRAY);
    public static final int GRAY       = dye(DyeColor.GRAY);
    // Pure black, not DyeColor.BLACK's 0x1D1D21: the glint blend is additive, so black draws nothing.
    // That is the point of the swatch, it leaves a deliberate gap in a multi-colour cycle.
    public static final int BLACK      = 0xFF000000;

    // Designs

    /** {@code customglint:<path>} resource location helper. */
    public static ResourceLocation res(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    public static final ResourceLocation VANILLA    = new ResourceLocation("minecraft", "textures/misc/enchanted_glint_item.png");
    /** Procedural chromatic "design": it has NO PNG. Each {@link Layer} carries a random {@link Layer#seed};
     *  the chromatic shader synthesises per-seed oil-slick noise tinted by the layer's colours (or a rainbow
     *  hue fallback when none are set). Resolved by name via {@link #designFromName} ("chromatic" sentinel). */
    public static final ResourceLocation CHROMATIC  = res("chromatic");
    public static final ResourceLocation ARCS      = res("textures/glint/arcs.png");
    public static final ResourceLocation AURORA    = res("textures/glint/aurora.png");
    public static final ResourceLocation BLOBS     = res("textures/glint/blobs.png");
    public static final ResourceLocation CASCADE   = res("textures/glint/cascade.png");
    public static final ResourceLocation CHECKER   = res("textures/glint/checker.png");
    public static final ResourceLocation CHEVRON   = res("textures/glint/chevron.png");
    public static final ResourceLocation CORAL     = res("textures/glint/coral.png");
    public static final ResourceLocation CRACKS    = res("textures/glint/cracks.png");
    public static final ResourceLocation CROSSHATCH = res("textures/glint/crosshatch.png");
    public static final ResourceLocation CRYSTAL   = res("textures/glint/crystal.png");
    public static final ResourceLocation DEBRIS    = res("textures/glint/debris.png");
    public static final ResourceLocation DIAMONDS  = res("textures/glint/diamonds.png");
    public static final ResourceLocation DUNES     = res("textures/glint/dunes.png");
    public static final ResourceLocation EMBER     = res("textures/glint/ember.png");
    public static final ResourceLocation FEATHER   = res("textures/glint/feather.png");
    public static final ResourceLocation FIRE      = res("textures/glint/fire.png");
    public static final ResourceLocation FROST     = res("textures/glint/frost.png");
    public static final ResourceLocation GLITCH    = res("textures/glint/glitch.png");
    public static final ResourceLocation GLOW      = res("textures/glint/glow.png");
    public static final ResourceLocation GRID      = res("textures/glint/grid.png");
    public static final ResourceLocation HALO      = res("textures/glint/halo.png");
    public static final ResourceLocation HEXAGON   = res("textures/glint/hexagon.png");
    public static final ResourceLocation LIGHTNING = res("textures/glint/lightning.png");
    public static final ResourceLocation MARBLE    = res("textures/glint/marble.png");
    public static final ResourceLocation MATRIX    = res("textures/glint/matrix.png");
    public static final ResourceLocation MESH      = res("textures/glint/mesh.png");
    public static final ResourceLocation MOSAIC    = res("textures/glint/mosaic.png");
    public static final ResourceLocation NET       = res("textures/glint/net.png");
    public static final ResourceLocation OIL       = res("textures/glint/oil.png");
    public static final ResourceLocation PETAL     = res("textures/glint/petal.png");
    public static final ResourceLocation PLASMA    = res("textures/glint/plasma.png");
    public static final ResourceLocation PLATE     = res("textures/glint/plate.png");
    public static final ResourceLocation PRISM     = res("textures/glint/prism.png");
    public static final ResourceLocation PULSE     = res("textures/glint/pulse.png");
    public static final ResourceLocation RIPPLE    = res("textures/glint/ripple.png");
    public static final ResourceLocation SAND      = res("textures/glint/sand.png");
    public static final ResourceLocation SCALES    = res("textures/glint/scales.png");
    public static final ResourceLocation SHEEN     = res("textures/glint/sheen.png");
    public static final ResourceLocation SHIMMER   = res("textures/glint/shimmer.png");
    public static final ResourceLocation SILK      = res("textures/glint/silk.png");
    public static final ResourceLocation SLASH     = res("textures/glint/slash.png");
    public static final ResourceLocation SMOKE     = res("textures/glint/smoke.png");
    public static final ResourceLocation SOLID     = res("textures/glint/solid.png");
    public static final ResourceLocation SPARKLE   = res("textures/glint/sparkle.png");
    public static final ResourceLocation STARS     = res("textures/glint/stars.png");
    public static final ResourceLocation STATIC    = res("textures/glint/static.png");
    public static final ResourceLocation STRIPES   = res("textures/glint/stripes.png");
    public static final ResourceLocation SWIRL     = res("textures/glint/swirl.png");
    public static final ResourceLocation TIDE      = res("textures/glint/tide.png");
    public static final ResourceLocation TILE      = res("textures/glint/tile.png");
    public static final ResourceLocation VEIN      = res("textures/glint/vein.png");
    public static final ResourceLocation WAVE      = res("textures/glint/wave.png");
    public static final ResourceLocation WEAVE     = res("textures/glint/weave.png");
    public static final ResourceLocation ZIGZAG    = res("textures/glint/zigzag.png");

    public static final ResourceLocation[] PATTERNS = {
            VANILLA, CHROMATIC,
            ARCS, AURORA, BLOBS, CASCADE, CHECKER, CHEVRON, CORAL, CRACKS,
            CROSSHATCH, CRYSTAL, DEBRIS, DIAMONDS, DUNES, EMBER, FEATHER, FIRE,
            FROST, GLITCH, GLOW, GRID, HALO, HEXAGON, LIGHTNING, MARBLE,
            MATRIX, MESH, MOSAIC, NET, OIL, PETAL, PLASMA, PLATE,
            PRISM, PULSE, RIPPLE, SAND, SCALES, SHEEN, SHIMMER, SILK,
            SLASH, SMOKE, SOLID, SPARKLE, STARS, STATIC, STRIPES, SWIRL,
            TIDE, TILE, VEIN, WAVE, WEAVE, ZIGZAG
    };

    /** Resolves a design <em>name</em> (as stored on a Trim / typed in a command / shown in the picker) to
     *  its design {@link ResourceLocation}. Handles the {@code vanilla} sentinel and {@code chromatic}
     *  sentinel and {@code namespace:name} qualified names; everything else maps to
     *  {@code <ns>:textures/glint/<name>.png}. */
    public static ResourceLocation designFromName(String name) {
        if (name == null) return VANILLA;
        if ("vanilla".equals(name)) return VANILLA;
        if ("chromatic".equals(name)) return CHROMATIC;
        try {
            int c = name.indexOf(':');
            if (c >= 0) {
                return new ResourceLocation(name.substring(0, c), "textures/glint/" + name.substring(c + 1) + ".png");
            }
            return res("textures/glint/" + name + ".png");
        } catch (Exception e) {
            return VANILLA;
        }
    }

    /** A fresh nonzero seed for a new {@link #CHROMATIC} layer (0 means "no seed / not chromatic"), so two
     *  chromatic glints never share an oil-slick pattern. */
    public static int randomChromaticSeed() {
        int s;
        do { s = ThreadLocalRandom.current().nextInt(); } while (s == 0);
        return s;
    }

    public static boolean isChromatic(ResourceLocation design) { return CHROMATIC.equals(design); }
    public static boolean isChromatic(Layer layer) { return layer != null && CHROMATIC.equals(layer.design()); }

    /** Re-uses the seed already stored on {@code existing} for any incoming unseeded chromatic layer at the
     *  same index, so re-writing an item (a trim colour/speed edit, a no-op refresh) keeps its oil-slick
     *  pattern instead of dropping the seed. Does NOT roll fresh seeds: that happens once at commit
     *  ({@link #ensureChromaticSeeds}) so editor/table PREVIEWS (which re-build their stack every frame from
     *  unseeded layers) stay seed-stable and don't flicker. */
    private static Layer[] carryChromaticSeeds(Layer[] layers, @Nullable Data existing) {
        if (existing == null) return layers;
        Layer[] oldL = existing.layers();
        Layer[] out = layers;
        for (int i = 0; i < layers.length && i < oldL.length; i++) {
            Layer l = layers[i];
            if (isChromatic(l) && l.seed() == 0 && isChromatic(oldL[i]) && oldL[i].seed() != 0) {
                if (out == layers) out = layers.clone();
                out[i] = new Layer(l.design(), l.colors(), l.speed(), l.interpolate(), l.patternScale(),
                        l.simultaneous(), l.scrollDir(), l.scrollOffset(), oldL[i].seed());
            }
        }
        return out;
    }

    /** Returns {@code layers} with a fresh nonzero {@link Layer#seed} rolled into every {@link #CHROMATIC}
     *  layer that arrives unseeded (seed 0). Non-chromatic layers and already-seeded chromatic layers pass
     *  through unchanged. Call this server-side where layers are committed (packet handlers, command) so wire
     *  paths that build layers without rolling a seed still get unique patterns. */
    public static Layer[] ensureChromaticSeeds(Layer[] layers) {
        Layer[] out = layers;
        for (int i = 0; i < layers.length; i++) {
            Layer l = layers[i];
            if (isChromatic(l) && l.seed() == 0) {
                if (out == layers) out = layers.clone();
                out[i] = new Layer(l.design(), l.colors(), l.speed(), l.interpolate(), l.patternScale(),
                        l.simultaneous(), l.scrollDir(), l.scrollOffset(), randomChromaticSeed());
            }
        }
        return out;
    }

    /** Saturated colors only, no neutrals: the preset palette for downstream mod use. */
    public static final int[] VIBRANT_COLORS = {
            RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE, PURPLE, MAGENTA, PINK
    };

    /** All 16 named colors including neutrals: full palette for downstream mod use. */
    public static final int[] ALL_COLORS = {
            RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE, PURPLE, MAGENTA, PINK,
            BROWN, WHITE, LIGHT_GRAY, GRAY, BLACK
    };

    // NBT

    private static final String TAG              = MOD_ID;
    private static final String LAYERS_KEY       = "layers";
    private static final String GLOWING_KEY      = "glowing";
    private static final String GLOW_COLORS_KEY  = "glowColors";
    private static final String GLOW_SPEED_KEY   = "glowSpeed";
    private static final String GLOW_INTERP_KEY  = "glowInterp";
    private static final String DESIGN_KEY      = "design";
    private static final String COLORS_KEY      = "colors";
    private static final String SPEED_KEY       = "speed";
    private static final String INTERPOLATE_KEY = "interpolate";
    private static final String SCALE_KEY         = "scale";
    private static final String SIMULTANEOUS_KEY  = "simultaneous";
    private static final String SCROLL_KEY        = "scroll";
    private static final String OFFSET_KEY        = "offset";
    private static final String SEED_KEY          = "seed";

    @Nullable
    public static Data read(ItemStack stack) {
        if (!stack.hasTag()) return null;
        CompoundTag root = stack.getTag();
        if (!root.contains(TAG)) return null;
        return readInner(root.getCompound(TAG));
    }

    /** Per-thread cache of parsed glint {@link Data}, keyed on the {@code customglint} sub-tag. The render loop
     *  calls this every frame per item / armor piece; a plain {@link #read} re-parses NBT and allocates a fresh
     *  {@code Layer[]}/{@code int[]}/{@code Data} each call.
     *
     *  <p>{@link WeakHashMap} hashes with {@code equals}/{@code hashCode}, and {@link CompoundTag} compares by
     *  content, so the key is the tag's value, not its instance. Any change to the sub-tag (a layer write, or a
     *  glow edit in place) changes its hash and misses; two stacks with identical glint NBT share one entry,
     *  which is correct because they parse to the same Data. Entries drop once the key tag is GC'd; the
     *  {@link ThreadLocal} avoids locking between the render and integrated-server threads. */
    private static final ThreadLocal<WeakHashMap<CompoundTag, Data>> DATA_CACHE =
            ThreadLocal.withInitial(WeakHashMap::new);

    @Nullable
    public static Data readCached(ItemStack stack) {
        if (!stack.hasTag()) return null;
        CompoundTag root = stack.getTag();
        if (!root.contains(TAG)) return null;
        CompoundTag sub = root.getCompound(TAG); // existing nested instance, no allocation when present
        WeakHashMap<CompoundTag, Data> cache = DATA_CACHE.get();
        Data hit = cache.get(sub);
        if (hit != null) return hit;
        Data parsed = readInner(sub);
        if (parsed != null) cache.put(sub, parsed); // only cache successful parses; key off a stable instance
        return parsed;
    }

    /** Parses the inner glint CompoundTag (the value stored under {@link #TAG}) into a Data record, or null
     *  when absent/empty/invalid. Shared by {@link #read(ItemStack)} and {@link #fromTag} so entity reads
     *  don't have to build a throwaway carrier ItemStack per call. */
    @Nullable
    private static Data readInner(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return null;

        float globalSpeed = tag.contains(SPEED_KEY) ? tag.getFloat(SPEED_KEY) : 1.0f;
        if (globalSpeed <= 0) globalSpeed = 1.0f;
        boolean globalInterpolate = !tag.contains(INTERPOLATE_KEY) || tag.getBoolean(INTERPOLATE_KEY);
        float globalScale = tag.contains(SCALE_KEY) ? tag.getFloat(SCALE_KEY) : 1.0f;
        if (globalScale <= 0) globalScale = 1.0f;
        boolean globalSimultaneous = !tag.contains(SIMULTANEOUS_KEY) || tag.getBoolean(SIMULTANEOUS_KEY);

        Layer[] layers;
        if (tag.contains(LAYERS_KEY)) {
            ListTag list = tag.getList(LAYERS_KEY, Tag.TAG_COMPOUND);
            if (list.isEmpty()) return null;
            int n = Math.min(list.size(), MAX_LAYERS);
            layers = new Layer[n];
            for (int i = 0; i < n; i++) {
                CompoundTag lt = list.getCompound(i);
                String design = lt.getString(DESIGN_KEY);
                if (design.isEmpty()) return null;
                ResourceLocation designRl = ResourceLocation.tryParse(design);
                if (designRl == null) return null; // malformed design in NBT: don't crash the render loop
                boolean chromatic = isChromatic(designRl);
                int[] colors = lt.getIntArray(COLORS_KEY);
                // Chromatic layers are allowed to carry an empty palette (the shader falls back to a
                // greyscale slick); every other design needs at least one color.
                if (colors.length == 0 && !chromatic) return null;
                // Clamp: the renderer draws once per color, so cap the palette read from NBT.
                if (colors.length > MAX_COLORS_PER_LAYER) colors = Arrays.copyOf(colors, MAX_COLORS_PER_LAYER);
                float speed = lt.contains(SPEED_KEY) ? lt.getFloat(SPEED_KEY) : globalSpeed;
                if (speed <= 0) speed = 1.0f;
                boolean interpolate = lt.contains(INTERPOLATE_KEY) ? lt.getBoolean(INTERPOLATE_KEY) : globalInterpolate;
                float patternScale = lt.contains(SCALE_KEY) ? lt.getFloat(SCALE_KEY) : globalScale;
                if (patternScale <= 0) patternScale = 1.0f;
                boolean simultaneous = lt.contains(SIMULTANEOUS_KEY) ? lt.getBoolean(SIMULTANEOUS_KEY) : globalSimultaneous;
                int scrollDir = lt.contains(SCROLL_KEY) ? lt.getInt(SCROLL_KEY) : SCROLL_E;
                float scrollOffset = lt.contains(OFFSET_KEY) ? lt.getFloat(OFFSET_KEY) : 0.0f;
                int seed = lt.contains(SEED_KEY) ? lt.getInt(SEED_KEY) : 0;
                layers[i] = new Layer(designRl, colors, speed, interpolate, patternScale, simultaneous, scrollDir, scrollOffset, seed);
            }
        } else {
            // backward compat: old single-layer format
            String design = tag.getString(DESIGN_KEY);
            if (design.isEmpty()) return null;
            if (!tag.contains(COLORS_KEY)) return null;
            int[] colors = tag.getIntArray(COLORS_KEY);
            if (colors.length == 0) return null;
            if (colors.length > MAX_COLORS_PER_LAYER) colors = Arrays.copyOf(colors, MAX_COLORS_PER_LAYER);
            ResourceLocation designRl = ResourceLocation.tryParse(design);
            if (designRl == null) return null; // malformed design in NBT: don't crash the render loop
            layers = new Layer[]{ new Layer(designRl, colors, globalSpeed, globalInterpolate, globalScale, globalSimultaneous) };
        }

        return new Data(layers);
    }

    public static boolean has(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(TAG);
    }

    public static void write(ItemStack stack, Layer[] layers) {
        // An empty layer array would write a present-but-unreadable tag (has() true, read() null). Treat it
        // as a removal so the two stay consistent.
        if (layers == null || layers.length == 0) { remove(stack); return; }
        layers = carryChromaticSeeds(layers, read(stack));
        CompoundTag tag = new CompoundTag();
        carryGlow(stack.hasTag() ? stack.getTag().getCompound(TAG) : null, tag);
        tag.put(LAYERS_KEY, layersToList(layers));
        stack.getOrCreateTag().put(TAG, tag);
    }

    /** Copies the glow fields onto a freshly built glint tag. They share the sub-tag with the layers but are
     *  edited on their own, so replacing the layers must not drop them. No-op when there is nothing to carry. */
    private static void carryGlow(@Nullable CompoundTag from, CompoundTag to) {
        if (from == null) return;
        if (from.contains(GLOWING_KEY))     to.putBoolean(GLOWING_KEY, from.getBoolean(GLOWING_KEY));
        if (from.contains(GLOW_COLORS_KEY)) to.putIntArray(GLOW_COLORS_KEY, from.getIntArray(GLOW_COLORS_KEY));
        if (from.contains(GLOW_SPEED_KEY))  to.putFloat(GLOW_SPEED_KEY, from.getFloat(GLOW_SPEED_KEY));
        if (from.contains(GLOW_INTERP_KEY)) to.putBoolean(GLOW_INTERP_KEY, from.getBoolean(GLOW_INTERP_KEY));
    }

    /** Serializes a Layer[] into the {@code layers} ListTag stored under {@link #TAG}. */
    private static ListTag layersToList(Layer[] layers) {
        ListTag list = new ListTag();
        for (Layer layer : layers) {
            CompoundTag lt = new CompoundTag();
            lt.putString(DESIGN_KEY, layer.design().toString());
            lt.putIntArray(COLORS_KEY, layer.colors());
            lt.putFloat(SPEED_KEY, layer.speed());
            lt.putBoolean(INTERPOLATE_KEY, layer.interpolate());
            lt.putFloat(SCALE_KEY, layer.patternScale());
            lt.putBoolean(SIMULTANEOUS_KEY, layer.simultaneous());
            lt.putInt(SCROLL_KEY, layer.scrollDir());
            lt.putFloat(OFFSET_KEY, layer.scrollOffset());
            if (layer.seed() != 0) lt.putInt(SEED_KEY, layer.seed());
            list.add(lt);
        }
        return list;
    }

    public static void write(ItemStack stack, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        write(stack, new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) });
    }

    public static void write(ItemStack stack, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous, int scrollDir, float scrollOffset) {
        write(stack, new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous, scrollDir, scrollOffset) });
    }

    public static void write(ItemStack stack, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous, int scrollDir, float scrollOffset, int seed) {
        write(stack, new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous, scrollDir, scrollOffset, seed) });
    }

    public static void remove(ItemStack stack) {
        if (stack.hasTag()) stack.getTag().remove(TAG);
    }

    /** The stack's {@code customglint} sub-tag if present, else null. Read side of the glow accessors. This does
     *  NOT go through the {@link #readCached} Data cache: glow fields live in the same sub-tag but are read
     *  directly, so the cache-invalidation rule on the glint Data write path doesn't apply here. */
    @Nullable
    private static CompoundTag glowSubTag(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) return null;
        CompoundTag root = stack.getTag();
        return root.contains(TAG) ? root.getCompound(TAG) : null;
    }

    /** Mutate the {@code customglint} sub-tag in place (creating it if absent) and put it back. Leaves any
     *  glint layers already stored under the same tag untouched. */
    private static void mutateGlowSubTag(ItemStack stack, Consumer<CompoundTag> mut) {
        CompoundTag root = stack.getOrCreateTag();
        CompoundTag glintTag = root.contains(TAG) ? root.getCompound(TAG) : new CompoundTag();
        mut.accept(glintTag);
        root.put(TAG, glintTag);
    }

    public static boolean isGlowing(ItemStack stack) {
        CompoundTag tag = glowSubTag(stack);
        return tag != null && tag.getBoolean(GLOWING_KEY);
    }

    public static void setGlowing(ItemStack stack, boolean glowing) {
        mutateGlowSubTag(stack, tag -> tag.putBoolean(GLOWING_KEY, glowing));
    }

    /** Shared empty result for the no-glow-colours path (the common case for a glint-only glowing item),
     *  read per glowing surface per frame, avoids a fresh {@code new int[0]} each call. Never mutated. */
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    /** Glow Trim colors: drive the outline color animation independently of any glint Data. */
    public static int[] getGlowColors(ItemStack stack) {
        CompoundTag tag = glowSubTag(stack);
        return tag != null && tag.contains(GLOW_COLORS_KEY) ? tag.getIntArray(GLOW_COLORS_KEY) : EMPTY_INT_ARRAY;
    }

    public static boolean hasGlowColors(ItemStack stack) {
        return getGlowColors(stack).length > 0;
    }

    /** Sets glowColors AND glowing=true. Independent of any glint Data on the stack. */
    public static void setGlowColors(ItemStack stack, int[] colors) {
        if (colors != null && colors.length > MAX_COLORS_PER_LAYER) colors = Arrays.copyOf(colors, MAX_COLORS_PER_LAYER);
        final int[] capped = colors;
        mutateGlowSubTag(stack, tag -> {
            tag.putIntArray(GLOW_COLORS_KEY, capped);
            tag.putBoolean(GLOWING_KEY, true);
        });
    }

    public static void clearGlowColors(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(TAG)) return;
        CompoundTag tag = stack.getTag().getCompound(TAG);
        tag.remove(GLOW_COLORS_KEY);
    }

    /** Glow-outline animation speed (how fast the outline cycles its glow colors), default 1.0. */
    public static float getGlowSpeed(ItemStack stack) {
        CompoundTag tag = glowSubTag(stack);
        return tag != null && tag.contains(GLOW_SPEED_KEY) ? tag.getFloat(GLOW_SPEED_KEY) : 1.0f;
    }

    /** Whether the glow outline blends smoothly between its colors (default true) or steps hard between them. */
    public static boolean getGlowInterpolate(ItemStack stack) {
        CompoundTag tag = glowSubTag(stack);
        return tag == null || !tag.contains(GLOW_INTERP_KEY) || tag.getBoolean(GLOW_INTERP_KEY);
    }

    /** Sets the glow outline's animation speed + interpolation (kept alongside {@code glowColors}). */
    public static void setGlowAnim(ItemStack stack, float speed, boolean interpolate) {
        mutateGlowSubTag(stack, tag -> {
            tag.putFloat(GLOW_SPEED_KEY, speed);
            tag.putBoolean(GLOW_INTERP_KEY, interpolate);
        });
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        ItemStack stack = new ItemStack(item);
        write(stack, design, colors, speed, interpolate, patternScale, simultaneous);
        return stack;
    }

    public static void write(ItemStack stack, ResourceLocation design, int[] colors) {
        write(stack, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void write(ItemStack stack, ResourceLocation design, int color) {
        write(stack, design, new int[]{color}, 1.0f, true, 1.0f, true);
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int[] colors) {
        return glinted(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int color) {
        return glinted(item, design, new int[]{color}, 1.0f, true, 1.0f, true);
    }

    // Auto-apply registries
    //
    // Craft / fishing / mob-drop / loot all map an Item to a single-layer Data and apply it on the
    // matching event. They differ only in which event feeds them, so the storage and apply logic is
    // shared here; the public registerXGlint / applyXGlint names stay as thin per-source wrappers.

    private static void putGlint(Map<Item, Data> registry, Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        registry.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    private static void applyGlint(Map<Item, Data> registry, ItemStack stack) {
        Data data = registry.get(stack.getItem());
        if (data != null) write(stack, data.layers());
    }

    public static final Map<Item, Data> CRAFT_GLINTS = new HashMap<>();

    public static void registerCraftGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        putGlint(CRAFT_GLINTS, item, design, colors, speed, interpolate, patternScale, simultaneous);
    }

    public static void registerCraftGlint(Item item, ResourceLocation design, int[] colors) {
        registerCraftGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyCraftGlint(ItemStack stack) {
        applyGlint(CRAFT_GLINTS, stack);
    }

    public static final Map<Item, Data> FISHING_GLINTS = new HashMap<>();

    public static void registerFishingGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        putGlint(FISHING_GLINTS, item, design, colors, speed, interpolate, patternScale, simultaneous);
    }

    public static void registerFishingGlint(Item item, ResourceLocation design, int[] colors) {
        registerFishingGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyFishingGlint(ItemStack stack) {
        applyGlint(FISHING_GLINTS, stack);
    }

    public static final Map<Item, Data> MOB_DROP_GLINTS = new HashMap<>();

    public static void registerMobDropGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        putGlint(MOB_DROP_GLINTS, item, design, colors, speed, interpolate, patternScale, simultaneous);
    }

    public static void registerMobDropGlint(Item item, ResourceLocation design, int[] colors) {
        registerMobDropGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyMobDropGlint(ItemStack stack) {
        applyGlint(MOB_DROP_GLINTS, stack);
    }

    /** Loot is keyed by table first, then item: GlintLootModifier looks up the rolled table and applies any
     *  registered glint to the matching drops. */
    public static final Map<ResourceLocation, Map<Item, Data>> LOOT_GLINTS = new HashMap<>();

    public static void registerLootGlint(ResourceLocation lootTable, Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        putGlint(LOOT_GLINTS.computeIfAbsent(lootTable, k -> new HashMap<>()), item, design, colors, speed, interpolate, patternScale, simultaneous);
    }

    public static void registerLootGlint(ResourceLocation lootTable, Item item, ResourceLocation design, int[] colors) {
        registerLootGlint(lootTable, item, design, colors, 1.0f, true, 1.0f, true);
    }

    // Entity glint API
    //
    // Per-instance: NBT lives in the LivingEntity's persistent data under TAG (same schema as
    // items). Server writes; a client-side sync packet (GlintEntitySyncPacket) pushes the tag to
    // tracking players and the client renderer (EntityGlintRender) reads from the cache.
    //
    // Type-wide: ENTITY_GLINTS is a server-safe registry; the client renderer falls back to it
    // when no per-instance NBT exists, so all entities of the type render with the same glint
    // with no per-entity storage or sync.

    public static final Map<EntityType<?>, Data> ENTITY_GLINTS = new HashMap<>();

    public static void registerEntityGlint(EntityType<?> type, Data data) {
        ENTITY_GLINTS.put(type, data);
    }

    public static void registerEntityGlint(EntityType<?> type, ResourceLocation design, int[] colors) {
        registerEntityGlint(type, new Data(new Layer[]{ new Layer(design, colors, 1.0f, true, 1.0f, true) }));
    }

    @Nullable
    public static Data getEntityGlint(EntityType<?> type) {
        return ENTITY_GLINTS.get(type);
    }

    /** Reads the per-instance entity glint tag from persistentData. The sync packet writes the same tag
     *  client-side, so both sides read through here. */
    @Nullable
    public static Data readEntity(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return null;
        return fromTag(pd.getCompound(TAG));
    }

    public static boolean hasEntity(LivingEntity entity) {
        return entity.getPersistentData().contains(TAG);
    }

    public static void writeEntity(LivingEntity entity, Layer[] layers) {
        CompoundTag pd = entity.getPersistentData();
        CompoundTag glintTag = toTag(layers);
        carryGlow(pd.contains(TAG) ? pd.getCompound(TAG) : null, glintTag);
        pd.put(TAG, glintTag);
    }

    public static void removeEntity(LivingEntity entity) {
        entity.getPersistentData().remove(TAG);
    }

    public static boolean isEntityGlowing(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return false;
        return pd.getCompound(TAG).getBoolean(GLOWING_KEY);
    }

    public static void setEntityGlowing(LivingEntity entity, boolean glowing) {
        CompoundTag pd = entity.getPersistentData();
        CompoundTag glintTag = pd.contains(TAG) ? pd.getCompound(TAG) : new CompoundTag();
        glintTag.putBoolean(GLOWING_KEY, glowing);
        pd.put(TAG, glintTag);
    }

    /** Per-entity Glow Trim colors: drive the outline color animation independently of any
     *  glint Data, identical semantics to {@link #getGlowColors(ItemStack)} but on a mob. */
    public static int[] getEntityGlowColors(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return EMPTY_INT_ARRAY;
        CompoundTag tag = pd.getCompound(TAG);
        if (!tag.contains(GLOW_COLORS_KEY)) return EMPTY_INT_ARRAY;
        return tag.getIntArray(GLOW_COLORS_KEY);
    }

    public static boolean hasEntityGlowColors(LivingEntity entity) {
        return getEntityGlowColors(entity).length > 0;
    }

    /** Sets glowColors AND glowing=true on the entity. Call
     *  {@code EntityGlintEvents.broadcast(entity)} afterwards to push the change to tracking
     *  clients (the api jar registers the sync channel, no extra wiring needed). */
    public static void setEntityGlowColors(LivingEntity entity, int[] colors) {
        if (colors != null && colors.length > MAX_COLORS_PER_LAYER) colors = Arrays.copyOf(colors, MAX_COLORS_PER_LAYER);
        CompoundTag pd = entity.getPersistentData();
        CompoundTag glintTag = pd.contains(TAG) ? pd.getCompound(TAG) : new CompoundTag();
        glintTag.putIntArray(GLOW_COLORS_KEY, colors);
        glintTag.putBoolean(GLOWING_KEY, true);
        pd.put(TAG, glintTag);
    }

    public static void clearEntityGlowColors(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return;
        CompoundTag tag = pd.getCompound(TAG);
        tag.remove(GLOW_COLORS_KEY);
    }

    /** Returns the raw inner glint tag stored on an ItemStack (or empty CompoundTag if none). */
    public static CompoundTag itemGlintTag(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(TAG)) return new CompoundTag();
        return stack.getTag().getCompound(TAG).copy();
    }

    /** Returns the raw inner glint tag for sync packets (or empty CompoundTag if none). */
    public static CompoundTag entityGlintTag(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        return pd.contains(TAG) ? pd.getCompound(TAG).copy() : new CompoundTag();
    }

    /** Replaces the per-instance entity glint tag in one shot: the S→C sync handler and any full overwrite
     *  (the wand writing its build onto a mob) go through here. Empty/null tag clears the glint. */
    public static void writeEntityTag(LivingEntity entity, CompoundTag glintTag) {
        if (glintTag == null || glintTag.isEmpty()) entity.getPersistentData().remove(TAG);
        else entity.getPersistentData().put(TAG, glintTag.copy());
    }

    /** Replaces the per-item glint tag in one shot. Symmetric with {@link #writeEntityTag}:
     *  useful for transferring glint state between item and entity (e.g. capturing a mob's
     *  glint onto an item via {@code writeItemTag(stack, entityGlintTag(entity))}) or
     *  restoring from a stored tag in bulk. Empty/null tag clears the glint. */
    public static void writeItemTag(ItemStack stack, CompoundTag glintTag) {
        if (glintTag == null || glintTag.isEmpty()) {
            if (stack.hasTag()) stack.getTag().remove(TAG);
            return;
        }
        stack.getOrCreateTag().put(TAG, glintTag.copy());
    }

    // NBT serialization helpers (decoupled from ItemStack)

    /** Decodes the inner glint CompoundTag (the value stored under TAG) into a Data record, or null if invalid/missing. */
    @Nullable
    public static Data fromTag(@Nullable CompoundTag glintTag) {
        return readInner(glintTag);
    }

    /** Returns true if the inner glint tag has glowing=true. */
    public static boolean tagGlowing(@Nullable CompoundTag glintTag) {
        return glintTag != null && glintTag.getBoolean(GLOWING_KEY);
    }

    /** Returns the glowColors int[] from the inner glint tag (empty if absent). */
    public static int[] tagGlowColors(@Nullable CompoundTag glintTag) {
        if (glintTag == null || !glintTag.contains(GLOW_COLORS_KEY)) return EMPTY_INT_ARRAY;
        return glintTag.getIntArray(GLOW_COLORS_KEY);
    }

    /** Encodes a Layer[] into a fresh inner glint CompoundTag (the value placed under TAG). */
    public static CompoundTag toTag(Layer[] layers) {
        CompoundTag tag = new CompoundTag();
        tag.put(LAYERS_KEY, layersToList(layers));
        return tag;
    }
}
