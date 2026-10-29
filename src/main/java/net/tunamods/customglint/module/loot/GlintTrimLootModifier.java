package net.tunamods.customglint.module.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class GlintTrimLootModifier extends LootModifier {

    public static final Supplier<MapCodec<GlintTrimLootModifier>> CODEC =
        Suppliers.memoize(() -> RecordCodecBuilder.mapCodec(inst ->
            codecStart(inst).apply(inst, GlintTrimLootModifier::new)));

    /** Triples a pattern's base weight when the drop happens in a biome that theme fits. */
    private static final float BIOME_MATCH_BONUS = 3.0f;

    /** Any pattern with no themed entry rolls at this weight, so the plain geometric designs stay common. */
    private static final float DEFAULT_WEIGHT = 1.0f;

    /**
     * A themed group: which biomes it fits (matched on the biome id path, so modded biomes with the usual
     * words in their name are covered too) and the patterns it favours with their base weights.
     */
    private record BiomeTheme(Predicate<String> fitsBiome, Map<String, Float> weights) {}

    private static final List<BiomeTheme> THEMES = List.of(
        new BiomeTheme(b -> b.contains("nether"),
            Map.of("fire", 10.0f, "ember", 10.0f, "plasma", 10.0f, "oil", 8.0f, "smoke", 8.0f)),
        new BiomeTheme(b -> b.contains("end"),
            Map.of("glitch", 10.0f, "matrix", 10.0f, "static", 10.0f, "vanilla", 8.0f, "arcs", 8.0f, "pulse", 8.0f)),
        new BiomeTheme(b -> b.contains("ocean"),
            Map.of("tide", 10.0f, "wave", 10.0f, "ripple", 10.0f, "coral", 9.0f, "scales", 9.0f, "silk", 8.0f, "net", 8.0f)),
        new BiomeTheme(b -> b.contains("desert") || b.contains("badlands"),
            Map.of("dunes", 10.0f, "sand", 10.0f, "solid", 8.0f, "swirl", 8.0f)),
        new BiomeTheme(b -> b.contains("forest") || b.contains("jungle") || b.contains("birch") || b.contains("bamboo"),
            Map.of("petal", 10.0f, "feather", 10.0f, "blobs", 9.0f, "cascade", 8.0f, "debris", 8.0f, "mosaic", 8.0f)),
        new BiomeTheme(b -> b.contains("mountain") || b.contains("peak") || b.contains("hill") || b.contains("stony"),
            Map.of("crystal", 10.0f, "diamonds", 10.0f, "vein", 9.0f, "cracks", 8.0f, "plate", 8.0f, "mesh", 8.0f, "grid", 8.0f, "tile", 8.0f)),
        new BiomeTheme(b -> b.contains("snow") || b.contains("frozen") || b.contains("ice"),
            Map.of("frost", 10.0f, "aurora", 10.0f, "shimmer", 8.0f)),
        new BiomeTheme(b -> b.contains("swamp") || b.contains("mangrove"),
            Map.of("weave", 10.0f)),
        // Mushroom fields ride along with plains.
        new BiomeTheme(b -> b.contains("plains") || b.contains("savanna") || b.contains("meadow") || b.contains("mushroom"),
            Map.of("stars", 10.0f, "lightning", 10.0f, "halo", 9.0f, "prism", 8.0f, "glow", 8.0f, "sheen", 8.0f, "sparkle", 8.0f))
    );

    /** Base weight per pattern, flattened from the themes. No pattern appears in two themes. */
    private static final Map<String, Float> PATTERN_WEIGHTS = THEMES.stream()
        .flatMap(t -> t.weights().entrySet().stream())
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    protected GlintTrimLootModifier(Optional<Holder<LootItemCondition>> condition, int priority) {
        super(condition, priority);
    }

    /** The biome id path the loot roll happened in. ORIGIN is optional in the loot context, so an unpositioned
     *  roll falls back to plains weighting. */
    private static String biomeName(LootContext context) {
        var origin = context.getOptional(LootContextParams.ORIGIN);
        if (origin == null) return "plains";
        var biome = context.getLevel().getBiome(new BlockPos((int) origin.x, (int) origin.y, (int) origin.z));
        return biome.unwrapKey().map(key -> key.identifier().getPath()).orElse("plains");
    }

    private static String selectPattern(LootContext context) {
        String biome = biomeName(context);
        // A biome can fit several themes ("snowy_plains" is both), and every match pays the same bonus,
        // so the boosted set is just the union.
        Set<String> boosted = THEMES.stream()
            .filter(t -> t.fitsBiome().test(biome))
            .flatMap(t -> t.weights().keySet().stream())
            .collect(Collectors.toSet());

        // Pre-compute each pattern's biome-adjusted weight, then do a single weighted pick over them.
        List<String> patterns = GlintTrimItem.PATTERNS;
        if (patterns.isEmpty()) return "vanilla"; // guard nextInt(0) / empty weighting
        float[] weights = new float[patterns.size()];
        float totalWeight = 0.0f;
        for (int i = 0; i < patterns.size(); i++) {
            String name = patterns.get(i);
            float weight = PATTERN_WEIGHTS.getOrDefault(name, DEFAULT_WEIGHT);
            if (boosted.contains(name)) weight *= BIOME_MATCH_BONUS;
            weights[i] = weight;
            totalWeight += weight;
        }

        float pick = context.getRandom().nextFloat() * totalWeight;
        for (int i = 0; i < patterns.size(); i++) {
            pick -= weights[i];
            if (pick <= 0) return patterns.get(i);
        }
        return patterns.get(context.getRandom().nextInt(patterns.size()));
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        // Chest loot tables only. Without this gate the LM also fires on mob spawn-equipment
        // rolls (no DAMAGE_SOURCE, no BLOCK_STATE), spamming "Could not equip item" warnings
        // when Mob.equip can't slot a Trim/Tear anywhere.
        Identifier tableId = context.getQueriedLootTableId();
        if (tableId == null || !tableId.getPath().startsWith("chests/")) return generatedLoot;

        // Trims: cascading rolls (22% for 1st, 12% for 2nd, 8% for 3rd). Safe to cascade now that loot trims
        // are always blank, so same design/type stacks and extra rolls don't clutter the inventory.
        int trimCount = 0;
        if (context.getRandom().nextFloat() < 0.22f) trimCount++;
        if (trimCount > 0 && context.getRandom().nextFloat() < 0.12f) trimCount++;
        if (trimCount > 1 && context.getRandom().nextFloat() < 0.08f) trimCount++;

        for (int t = 0; t < trimCount; t++) {
            // Glow trims share this pool as the rarer variant (~1 in 4 trim drops). Both drop blank.
            if (context.getRandom().nextFloat() < 0.25f) {
                generatedLoot.add(new ItemStack(ModItems.GLOW_TRIM.get()));
                continue;
            }
            String pattern = selectPattern(context);
            ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());
            Identifier patternLoc = CustomGlint.designFromName(pattern); // handles vanilla + chromatic sentinels
            GlintTrimItem.setPattern(trim, patternLoc);
            generatedLoot.add(trim);
        }

        // Tears and rainbow dye stack to 64 and get consumed in bulk, so they keep the cascading rolls: 20% for
        // 1st, 10% for 2nd, 5% for 3rd. Each tear type rolls independently.
        rollTearCascade(context, ModItems.GLINT_TEAR_SIMULTANEOUS.get(), generatedLoot);
        rollTearCascade(context, ModItems.GLINT_TEAR_SEQUENTIAL.get(), generatedLoot);
        rollTearCascade(context, ModItems.GLINT_LAYER_TEAR.get(), generatedLoot);
        rollTearCascade(context, ModItems.GLINT_BLACK_TEAR.get(), generatedLoot);
        rollTearCascade(context, ModItems.RAINBOW_DYE.get(), generatedLoot);

        return generatedLoot;
    }

    /** One item's cascading drop roll: 20% for the 1st, then 10% for a 2nd, then 5% for a 3rd. */
    private static void rollTearCascade(LootContext context, Item item, List<ItemStack> loot) {
        if (context.getRandom().nextFloat() < 0.20f) {
            loot.add(item.getDefaultInstance());
            if (context.getRandom().nextFloat() < 0.10f) {
                loot.add(item.getDefaultInstance());
                if (context.getRandom().nextFloat() < 0.05f)
                    loot.add(item.getDefaultInstance());
            }
        }
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
