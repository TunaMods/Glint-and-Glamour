package net.tunamods.customglint.common;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

/**
 * Server-safe data API for Custom Glints. Item and entity glint state, color and design constants, and
 * the auto-apply registries live here. The rendering pipeline lives in
 * {@link net.tunamods.customglint.common.client.CustomGlintRenderer}, which is client-only and reads the
 * {@link Layer}/{@link Data} types from here. That class is named fully-qualified in javadoc on purpose:
 * this file imports nothing client-side, so it stays loadable on a dedicated server.
 *
 * <p>Naming: {@code read}/{@code write} move a whole set of glint layers, {@code get}/{@code set} touch a
 * single glow field, and {@code fromTag}/{@code toTag} plus the {@code *Tag} methods go through the
 * CompoundTag bridge.
 */
public final class CustomGlint {

    private CustomGlint() {}

    // ── Scroll directions ──────────────────────────────────────────────────────

    /** {@link Layer#scrollDir} values: the direction the animated glint drifts. {@code STATIC} freezes the
     *  animation and uses {@link Layer#scrollOffset} as a manual position instead. The eight compass values
     *  go counter-clockwise from East. Default is {@code E} (the historical horizontal scroll). */
    public static final int SCROLL_STATIC = 0, SCROLL_E = 1, SCROLL_NE = 2, SCROLL_N = 3, SCROLL_NW = 4,
            SCROLL_W = 5, SCROLL_SW = 6, SCROLL_S = 7, SCROLL_SE = 8;

    /** A glint never cycles more than this many colors per layer (the renderer fans out one draw per color
     *  and loops); every input path (wand editor, dye/merge recipes, packets) enforces it. */
    public static final int MAX_COLORS_PER_LAYER = 8;

    /** Most-translucent glint alpha (at the 8th glass); 0 glass = fully opaque (255). Shared so the Glint
     *  Table's opacity↔alpha↔glass mapping matches on both the client (preview/cost) and the server (print). */
    public static final int GLINT_ALPHA_MIN = 32;

    /** Glint-Table material cost of a speed / scale value: the number of ± stepper clicks it sits from the 1×
     *  default (0.10 per click below 1×, 0.5 per click above). Each click costs one redstone / slime, so a
     *  fully-tuned layer is a real material sink. Both sides tally this per layer. */
    public static int stepCost(float value) {
        if (value >= 1.0f) return Math.round((value - 1.0f) / 0.5f);
        return Math.round((1.0f - value) / 0.10f);
    }

    /** Glint-Table glass cost for a colour's alpha: the opacity level (0..8) it encodes, i.e. one glass per
     *  opacity click. Inverts the opacity→alpha mapping ({@code alpha = 255 - level*(255-MIN)/8}). */
    public static int glassCost(int alpha) {
        return Math.round((255f - alpha) * 8f / (255f - GLINT_ALPHA_MIN));
    }

    // ── Layer ─────────────────────────────────────────────────────────────────

    /** {@code int[]} through a list codec. The boxing round-trip is spelled out because
     *  {@code List#toArray} yields {@code Object[]}, never {@code int[]}. Shared by the layer colors and
     *  the glow colors, which serialize the same way. */
    private static final Codec<int[]> INT_ARRAY_CODEC = Codec.INT.listOf().xmap(
            list -> { int[] a = new int[list.size()]; for (int n = 0; n < a.length; n++) a[n] = list.get(n); return a; },
            arr -> Arrays.stream(arr).boxed().toList());

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

        public static final Codec<Layer> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.fieldOf("design").forGetter(Layer::design),
                INT_ARRAY_CODEC.fieldOf("colors").forGetter(Layer::colors),
                Codec.FLOAT.optionalFieldOf("speed", 1.0f).forGetter(Layer::speed),
                Codec.BOOL.optionalFieldOf("interpolate", true).forGetter(Layer::interpolate),
                Codec.FLOAT.optionalFieldOf("scale", 1.0f).forGetter(Layer::patternScale),
                Codec.BOOL.optionalFieldOf("simultaneous", true).forGetter(Layer::simultaneous),
                Codec.INT.optionalFieldOf("scroll", SCROLL_E).forGetter(Layer::scrollDir),
                Codec.FLOAT.optionalFieldOf("offset", 0.0f).forGetter(Layer::scrollOffset),
                Codec.INT.optionalFieldOf("seed", 0).forGetter(Layer::seed)
        ).apply(i, Layer::new));

        // Value equality (the record default compares the int[] by identity, which would break item
        // stacking / ItemStack.matches / recipe matching; the old NBT was value-equal).
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

    // ── Data ─────────────────────────────────────────────────────────────────

    /** Array-aware {@code equals}/{@code hashCode} for the same reason as {@link Layer}: the record
     *  default would compare {@code layers} by identity. */
    public record Data(Layer[] layers) {
        public static final Codec<Data> CODEC = Layer.CODEC.listOf().xmap(
                list -> new Data(list.toArray(new Layer[0])),
                data -> List.of(data.layers())
        ).fieldOf("layers").codec();

        @Override public boolean equals(Object o) {
            return this == o || (o instanceof Data d && Arrays.equals(layers, d.layers));
        }
        @Override public int hashCode() { return Arrays.hashCode(layers); }
    }

    // ── GlintState ─────────────────────────────────────────────────────────────

    /**
     * The payload of the {@link CustomGlintComponents#GLINT} item data component: the glint {@link Data}
     * (nullable, a glow-only stack has none) plus the four independent glow fields. Codec-serialized for
     * both persistence and network sync. {@code equals}/{@code hashCode} are array-aware for the same
     * reason as {@link Layer}.
     */
    public record GlintState(@Nullable Data data, boolean glowing, int[] glowColors, float glowSpeed, boolean glowInterp) {
        public static final GlintState EMPTY = new GlintState(null, false, new int[0], 1.0f, true);

        public boolean isEmpty() {
            // glowSpeed / glowInterp are modifiers of an existing glow, meaningless without it, so they don't
            // count toward emptiness: a stack carrying only a non-default glow speed but no glow is still empty.
            return data == null && !glowing && glowColors.length == 0;
        }

        public static final MapCodec<GlintState> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Data.CODEC.optionalFieldOf("glint").forGetter(s -> Optional.ofNullable(s.data())),
                Codec.BOOL.optionalFieldOf("glowing", false).forGetter(GlintState::glowing),
                INT_ARRAY_CODEC.optionalFieldOf("glowColors", new int[0]).forGetter(GlintState::glowColors),
                // Glow-outline animation speed + interpolation, kept alongside the glow colors. Default 1.0 /
                // true so any pre-existing item decodes to the vanilla cycle (they're absent from its tag).
                Codec.FLOAT.optionalFieldOf("glowSpeed", 1.0f).forGetter(GlintState::glowSpeed),
                Codec.BOOL.optionalFieldOf("glowInterp", true).forGetter(GlintState::glowInterp)
        ).apply(i, (glint, glowing, colors, speed, interp) -> new GlintState(glint.orElse(null), glowing, colors, speed, interp)));

        public static final Codec<GlintState> CODEC = MAP_CODEC.codec();
        public static final StreamCodec<ByteBuf, GlintState> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GlintState g)) return false;
            return glowing == g.glowing && glowInterp == g.glowInterp && Float.compare(glowSpeed, g.glowSpeed) == 0
                    && Objects.equals(data, g.data) && Arrays.equals(glowColors, g.glowColors);
        }
        @Override public int hashCode() {
            return Objects.hash(data, glowing, glowSpeed, glowInterp) * 31 + Arrays.hashCode(glowColors);
        }
    }

    // ── Colors ────────────────────────────────────────────────────────────────

    /** Parses a {@code "RRGGBB"} / {@code "#RRGGBB"} hex string to an opaque ARGB int. For string color
     *  sources, commands, data packs, the config, where the value isn't a compile-time constant. The
     *  named constants below come from {@link DyeColor}; only external string input flows through here. */
    public static int color(String hex) {
        return Integer.parseUnsignedInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) | 0xFF000000;
    }

    // The 16 named glint colors ARE Minecraft's dye colors, from DyeColor.getTextColor() so they track
    // vanilla. getTextColor() returns 0x00RRGGBB (alpha 0), but the render path multiplies colour by alpha
    // and would draw nothing, so OR in full alpha. Consumers wanting bare RGB already mask with & 0xFFFFFF.
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
    public static final int BLACK      = dye(DyeColor.BLACK);

    // ── Designs ───────────────────────────────────────────────────────────────

    /** {@code customglint:<path>} resource location helper. */
    public static ResourceLocation res(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Resolves a design <em>name</em> (as stored on a Trim / typed in a command / shown in the picker) to
     *  its design {@link ResourceLocation}. Handles the {@code vanilla} sentinel and {@code namespace:name}
     *  qualified names; everything else maps to {@code <ns>:textures/glint/<name>.png}. Uses tryParse (not the
     *  throwing factory) so a malformed remote name falls back to vanilla instead of crashing the server tick. */
    public static ResourceLocation designFromName(@Nullable String name) {
        if (name == null) return VANILLA;
        if ("vanilla".equals(name)) return VANILLA;
        if ("chromatic".equals(name)) return CHROMATIC;
        int c = name.indexOf(':');
        ResourceLocation id = c >= 0
                ? ResourceLocation.tryParse(name.substring(0, c) + ":textures/glint/" + name.substring(c + 1) + ".png")
                : ResourceLocation.tryParse(MOD_ID + ":textures/glint/" + name + ".png");
        return id != null ? id : VANILLA;
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
    private static Layer[] carryChromaticSeeds(Layer[] layers, @Nullable GlintState existing) {
        if (existing == null || existing.data() == null) return layers;
        Layer[] oldL = existing.data().layers();
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

    public static final ResourceLocation VANILLA    = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/enchanted_glint_item.png");
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

    /** Saturated colors only, used by JEI plugin and trim creative tab for preset display. */
    public static final int[] VIBRANT_COLORS = {
            RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE, PURPLE, MAGENTA, PINK
    };

    /** All 16 named colors including neutrals: the full palette for downstream mod use. */
    public static final int[] ALL_COLORS = {
            RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE, PURPLE, MAGENTA, PINK,
            BROWN, WHITE, LIGHT_GRAY, GRAY, BLACK
    };

    // ── Item glint component ───────────────────────────────────────────────────
    // Item glint state lives in the typed CustomGlintComponents.GLINT data component (a GlintState).
    // Every field name ("layers"/"glint"/"glowing"/"glowColors"/…) lives in the codecs (Layer.CODEC /
    // Data.CODEC / GlintState.MAP_CODEC); there are no loose NBT key constants. The CompoundTag bridge
    // further down round-trips that same GlintState through its codec + NbtOps.

    @Nullable
    public static Data read(ItemStack stack) {
        GlintState state = stack.get(CustomGlintComponents.GLINT.get());
        return state == null ? null : state.data();
    }

    /** Render-side read: the stack's own glint, else the type-wide glint for its item ({@link #getItemGlint}).
     *  Only the render paths call this. Server logic uses {@link #read}, which never falls back, so a type-wide
     *  glint is never written to the component, copied by a recipe, or counted by the Glint Table. */
    @Nullable
    public static Data readForRender(ItemStack stack) {
        Data own = read(stack);
        return own != null ? own : getItemGlint(stack.getItem());
    }

    private static GlintState stateOrEmpty(ItemStack stack) {
        GlintState s = stack.get(CustomGlintComponents.GLINT.get());
        return s == null ? GlintState.EMPTY : s;
    }

    /** Sets the glint component, or removes it when the result is empty, so a cleared item carries no
     *  component at all (matching the old "remove the tag" behaviour and keeping stacks value-equal). */
    private static void setState(ItemStack stack, GlintState state) {
        if (state.isEmpty()) stack.remove(CustomGlintComponents.GLINT.get());
        else stack.set(CustomGlintComponents.GLINT.get(), state);
    }

    public static boolean has(ItemStack stack) {
        GlintState state = stack.get(CustomGlintComponents.GLINT.get());
        return state != null && !state.isEmpty();
    }

    public static void write(ItemStack stack, Layer[] layers) {
        GlintState existing = stack.get(CustomGlintComponents.GLINT.get());
        layers = carryChromaticSeeds(layers, existing);
        // write() overwrites the glint layers but preserves any glow state already on the stack (glint and
        // glow are independent, so applying a new Glint Trim must not nuke a Glow Trim's colors).
        boolean glowing = existing != null && existing.glowing();
        int[] glowColors = existing != null ? existing.glowColors() : new int[0];
        float glowSpeed = existing != null ? existing.glowSpeed() : 1.0f;
        boolean glowInterp = existing == null || existing.glowInterp();
        setState(stack, new GlintState(new Data(layers), glowing, glowColors, glowSpeed, glowInterp));
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

    public static void write(ItemStack stack, ResourceLocation design, int[] colors) {
        write(stack, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void write(ItemStack stack, ResourceLocation design, int color) {
        write(stack, design, new int[]{color}, 1.0f, true, 1.0f, true);
    }

    public static void remove(ItemStack stack) {
        stack.remove(CustomGlintComponents.GLINT.get());
    }

    public static boolean isGlowing(ItemStack stack) {
        if (stack.isEmpty()) return false;
        GlintState state = stack.get(CustomGlintComponents.GLINT.get());
        return state != null && state.glowing();
    }

    public static void setGlowing(ItemStack stack, boolean glowing) {
        GlintState s = stateOrEmpty(stack);
        setState(stack, new GlintState(s.data(), glowing, s.glowColors(), s.glowSpeed(), s.glowInterp()));
    }

    /** True iff the stack would render a glow outline ({@code isGlowing || hasGlowColors}). */
    public static boolean hasGlowEffect(ItemStack stack) {
        if (stack.isEmpty()) return false;
        GlintState state = stack.get(CustomGlintComponents.GLINT.get());
        return state != null && (state.glowing() || state.glowColors().length > 0);
    }

    private static final int[] EMPTY_COLORS = new int[0];

    /** Glow Trim colors: drive the outline color animation independently of any glint Data. */
    public static int[] getGlowColors(ItemStack stack) {
        if (stack.isEmpty()) return EMPTY_COLORS;
        GlintState state = stack.get(CustomGlintComponents.GLINT.get());
        return state == null ? EMPTY_COLORS : state.glowColors();
    }

    public static boolean hasGlowColors(ItemStack stack) {
        return getGlowColors(stack).length > 0;
    }

    /** Sets glowColors AND glowing=true. Independent of any glint Data on the stack. */
    public static void setGlowColors(ItemStack stack, int[] colors) {
        GlintState s = stateOrEmpty(stack);
        setState(stack, new GlintState(s.data(), true, colors, s.glowSpeed(), s.glowInterp()));
    }

    public static void clearGlowColors(ItemStack stack) {
        GlintState s = stack.get(CustomGlintComponents.GLINT.get());
        if (s == null || s.glowColors().length == 0) return;
        setState(stack, new GlintState(s.data(), s.glowing(), new int[0], s.glowSpeed(), s.glowInterp()));
    }

    /** Glow-outline animation speed (how fast the outline cycles its glow colors), default 1.0. */
    public static float getGlowSpeed(ItemStack stack) {
        return stateOrEmpty(stack).glowSpeed();
    }

    /** Whether the glow outline blends smoothly between its colors (default true) or steps hard between them. */
    public static boolean getGlowInterpolate(ItemStack stack) {
        return stateOrEmpty(stack).glowInterp();
    }

    /** Sets the glow outline's animation speed + interpolation (kept alongside {@code glowColors}). Only
     *  meaningful on a glowing stack; {@link GlintState#isEmpty()} ignores these, so calling it on an
     *  otherwise-empty stack still stores nothing. */
    public static void setGlowAnim(ItemStack stack, float speed, boolean interpolate) {
        GlintState s = stateOrEmpty(stack);
        setState(stack, new GlintState(s.data(), s.glowing(), s.glowColors(), speed, interpolate));
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        ItemStack stack = new ItemStack(item);
        write(stack, design, colors, speed, interpolate, patternScale, simultaneous);
        return stack;
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int[] colors) {
        return glinted(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int color) {
        return glinted(item, design, new int[]{color}, 1.0f, true, 1.0f, true);
    }

    // ── Auto-apply registries ─────────────────────────────────────────────────

    /** Writes the registered glint for {@code stack}'s item, if the registry has one. */
    private static void applyFrom(Map<Item, Data> registry, ItemStack stack) {
        Data data = registry.get(stack.getItem());
        if (data == null) return;
        write(stack, data.layers());
    }

    /** One-layer {@link Data}, the shape every {@code register*Glint} overload builds. */
    private static Data singleLayer(ResourceLocation design, int[] colors, float speed, boolean interpolate,
                                    float patternScale, boolean simultaneous) {
        return new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) });
    }

    /** Store a single-layer glint for {@code item} in {@code registry}; shared by the craft / fishing /
     *  mob-drop / loot wrappers. */
    private static void putGlint(Map<Item, Data> registry, Item item, ResourceLocation design, int[] colors,
                                 float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        registry.put(item, singleLayer(design, colors, speed, interpolate, patternScale, simultaneous));
    }

    public static final Map<Item, Data> CRAFT_GLINTS = new HashMap<>();

    public static void registerCraftGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        putGlint(CRAFT_GLINTS, item, design, colors, speed, interpolate, patternScale, simultaneous);
    }

    public static void registerCraftGlint(Item item, ResourceLocation design, int[] colors) {
        registerCraftGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyCraftGlint(ItemStack stack) {
        applyFrom(CRAFT_GLINTS, stack);
    }

    public static final Map<Item, Data> FISHING_GLINTS = new HashMap<>();

    public static void registerFishingGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        putGlint(FISHING_GLINTS, item, design, colors, speed, interpolate, patternScale, simultaneous);
    }

    public static void registerFishingGlint(Item item, ResourceLocation design, int[] colors) {
        registerFishingGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyFishingGlint(ItemStack stack) {
        applyFrom(FISHING_GLINTS, stack);
    }

    public static final Map<Item, Data> MOB_DROP_GLINTS = new HashMap<>();

    public static void registerMobDropGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        putGlint(MOB_DROP_GLINTS, item, design, colors, speed, interpolate, patternScale, simultaneous);
    }

    public static void registerMobDropGlint(Item item, ResourceLocation design, int[] colors) {
        registerMobDropGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyMobDropGlint(ItemStack stack) {
        applyFrom(MOB_DROP_GLINTS, stack);
    }

    /** Per-loot-table registry, read by the loot modifier when a table rolls one of these items. */
    public static final Map<ResourceLocation, Map<Item, Data>> LOOT_GLINTS = new HashMap<>();

    public static void registerLootGlint(ResourceLocation lootTable, Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        putGlint(LOOT_GLINTS.computeIfAbsent(lootTable, k -> new HashMap<>()), item, design, colors, speed, interpolate, patternScale, simultaneous);
    }

    public static void registerLootGlint(ResourceLocation lootTable, Item item, ResourceLocation design, int[] colors) {
        registerLootGlint(lootTable, item, design, colors, 1.0f, true, 1.0f, true);
    }

    // ── Item-type glints ──────────────────────────────────────────────────────
    //
    // Every stack of a registered item renders with its glint, with nothing written to the component: /give,
    // creative, other mods' loot and items that existed before the registration all show it. A stack's own
    // glint always wins. Read only through readForRender, so it is render-side only (layers, no glow outline).
    //
    // Two sources. ITEM_GLINTS is the public registry for mods, filled from code at load. The rule map is
    // owned by the full mod's /glint rule item command: the server syncs it to each client, which replaces it
    // wholesale through setRuleItemGlints. Rules take precedence, so a pack author can override a mod.

    public static final Map<Item, Data> ITEM_GLINTS = new HashMap<>();

    public static void registerItemGlint(Item item, Data data) {
        ITEM_GLINTS.put(item, data);
    }

    public static void registerItemGlint(Item item, ResourceLocation design, int[] colors) {
        registerItemGlint(item, singleLayer(design, colors, 1.0f, true, 1.0f, true));
    }

    /** Synced copy of the server's item rules. Replaced, never mutated, so the render thread can read it while
     *  a sync lands without locking. */
    private static volatile Map<Item, Data> ruleItemGlints = Map.of();

    /** Replaces the item rules (the full mod's sync packet, and an empty map on disconnect). */
    public static void setRuleItemGlints(Map<Item, Data> glints) {
        ruleItemGlints = Map.copyOf(glints);
    }

    /** The type-wide glint for {@code item}: an item rule first, then {@link #ITEM_GLINTS}, else null. */
    @Nullable
    public static Data getItemGlint(Item item) {
        Data rule = ruleItemGlints.get(item);
        return rule != null ? rule : ITEM_GLINTS.get(item);
    }

    // ── Entity glint API ──────────────────────────────────────────────────────
    //
    // Per-instance state is the synced ENTITY_GLINT attachment (a GlintState). Writing it server-side
    // auto-syncs to tracking clients and the renderer reads it directly. There is no manual sync packet.
    //
    // Type-wide: ENTITY_GLINTS is a server-safe registry; the renderer falls back to it when an entity
    // has no per-instance state, so all entities of the type render the same glint with no storage/sync.

    public static final Map<EntityType<?>, Data> ENTITY_GLINTS = new HashMap<>();

    public static void registerEntityGlint(EntityType<?> type, Data data) {
        ENTITY_GLINTS.put(type, data);
    }

    public static void registerEntityGlint(EntityType<?> type, ResourceLocation design, int[] colors) {
        registerEntityGlint(type, singleLayer(design, colors, 1.0f, true, 1.0f, true));
    }

    @Nullable
    public static Data getEntityGlint(EntityType<?> type) {
        return ENTITY_GLINTS.get(type);
    }

    private static GlintState entityState(LivingEntity entity) {
        GlintState s = entity.getExistingDataOrNull(CustomGlintComponents.ENTITY_GLINT);
        return s == null ? GlintState.EMPTY : s;
    }

    /** Stores the state, or removes the attachment when empty. Either path auto-syncs to trackers. */
    private static void setEntityState(LivingEntity entity, GlintState state) {
        if (state.isEmpty()) entity.removeData(CustomGlintComponents.ENTITY_GLINT);
        else entity.setData(CustomGlintComponents.ENTITY_GLINT, state);
    }

    /** The per-instance entity glint Data (null if none). */
    @Nullable
    public static Data readEntity(LivingEntity entity) {
        return entityState(entity).data();
    }

    public static boolean hasEntity(LivingEntity entity) {
        return !entityState(entity).isEmpty();
    }

    /** Sets the glint layers, preserving the entity's existing glow flags. Auto-syncs to trackers. */
    public static void writeEntity(LivingEntity entity, Layer[] layers) {
        GlintState cur = entityState(entity);
        setEntityState(entity, new GlintState(new Data(layers), cur.glowing(), cur.glowColors(), cur.glowSpeed(), cur.glowInterp()));
    }

    public static void removeEntity(LivingEntity entity) {
        entity.removeData(CustomGlintComponents.ENTITY_GLINT);
    }

    public static boolean isEntityGlowing(LivingEntity entity) {
        return entityState(entity).glowing();
    }

    public static void setEntityGlowing(LivingEntity entity, boolean glowing) {
        GlintState cur = entityState(entity);
        setEntityState(entity, new GlintState(cur.data(), glowing, cur.glowColors(), cur.glowSpeed(), cur.glowInterp()));
    }

    /** Per-entity Glow Trim colors: drive the outline color animation independently of any
     *  glint Data, identical semantics to {@link #getGlowColors(ItemStack)} but on a mob. */
    public static int[] getEntityGlowColors(LivingEntity entity) {
        return entityState(entity).glowColors();
    }

    public static boolean hasEntityGlowColors(LivingEntity entity) {
        return getEntityGlowColors(entity).length > 0;
    }

    /** Sets glowColors AND glowing=true on the entity. Auto-syncs to tracking clients (no manual broadcast). */
    public static void setEntityGlowColors(LivingEntity entity, int[] colors) {
        GlintState cur = entityState(entity);
        setEntityState(entity, new GlintState(cur.data(), true, colors, cur.glowSpeed(), cur.glowInterp()));
    }

    public static void clearEntityGlowColors(LivingEntity entity) {
        GlintState cur = entityState(entity);
        if (cur.glowColors().length == 0) return;
        setEntityState(entity, new GlintState(cur.data(), cur.glowing(), new int[0], cur.glowSpeed(), cur.glowInterp()));
    }

    // ── CompoundTag bridge (serialized snapshot / restore) ────────────────────
    // The GlintState codec driven through NbtOps, for the cases that genuinely need a serialized form:
    // a stored snapshot, give NBT, /data, a custom packet. An item tag and an entity tag share one shape
    // (same codec), so a mob's glint copies onto an item and back with no field-by-field translation.

    private static CompoundTag stateToTag(GlintState state) {
        if (state.isEmpty()) return new CompoundTag();
        return (CompoundTag) GlintState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
    }

    private static GlintState stateFromTag(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return GlintState.EMPTY;
        return GlintState.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(GlintState.EMPTY);
    }

    /** The stack's glint state as a CompoundTag (empty if none). The bridge format shared with entity
     *  NBT, so a mob's glint can be copied onto an item and vice-versa. */
    public static CompoundTag itemGlintTag(ItemStack stack) {
        return stateToTag(stateOrEmpty(stack));
    }

    /** The entity's glint state as a CompoundTag (empty if none). Same shape as {@link #itemGlintTag}. */
    public static CompoundTag entityGlintTag(LivingEntity entity) {
        return stateToTag(entityState(entity));
    }

    /** Replaces the per-instance entity glint state in one shot (empty/null clears it). Auto-syncs. */
    public static void writeEntityTag(LivingEntity entity, @Nullable CompoundTag glintTag) {
        setEntityState(entity, stateFromTag(glintTag));
    }

    /** Replaces the per-item glint state in one shot. Symmetric with {@link #writeEntityTag}, useful for
     *  transferring glint between item and entity (e.g. {@code writeItemTag(stack, entityGlintTag(entity))})
     *  or restoring from a stored tag in bulk. Empty/null tag clears the glint. */
    public static void writeItemTag(ItemStack stack, @Nullable CompoundTag glintTag) {
        setState(stack, stateFromTag(glintTag));
    }

    // ── Tag inspectors (pull fields out of a glint CompoundTag) ───────────────

    /** The glint {@link Data} from a glint tag, or null if none. */
    @Nullable
    public static Data fromTag(@Nullable CompoundTag glintTag) {
        return stateFromTag(glintTag).data();
    }

    /** True if the glint tag has glowing=true. */
    public static boolean tagGlowing(@Nullable CompoundTag glintTag) {
        return stateFromTag(glintTag).glowing();
    }

    /** The glowColors int[] from the glint tag (empty if absent). */
    public static int[] tagGlowColors(@Nullable CompoundTag glintTag) {
        return stateFromTag(glintTag).glowColors();
    }

    /** A glint tag holding just these layers (no glow flags). */
    public static CompoundTag toTag(Layer[] layers) {
        return stateToTag(new GlintState(new Data(layers), false, new int[0], 1.0f, true));
    }

}
