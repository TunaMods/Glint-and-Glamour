package net.tunamods.customglint.module.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.category.extensions.vanilla.smithing.ISmithingCategoryExtension;
import mezz.jei.api.recipe.vanilla.IJeiShapedRecipeBuilder;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.ItemLike;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.menu.GlintTableMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JEI integration: ingredient-info pages plus hand-built example recipes that preview the glint
 * crafts (tear, dye, merge, layer, black tear, speed, scale, glow trim) and the smithing application.
 *
 * <p>The real custom-serializer recipes don't carry component-aware previews (their ingredients are
 * item-only and would show blank trims), so these synthetic displays exist purely to show players a
 * concrete colored example of each craft. On 26.1 the old {@code getIngredients()}/{@code getResultItem()}
 * override path is gone; previews now ride the {@link SlotDisplay} system. Each slot is fed an
 * {@link ItemStackTemplate} built from a fully glinted stack, so the trim colors and the finished glint
 * render in the slot. Crafting examples use JEI's {@link IVanillaRecipeFactory#createShapedRecipeBuilder}
 * (per-slot {@link SlotDisplay} overrides); smithing examples build a {@link SmithingTransformRecipe}
 * whose result template carries the glint.
 */
@JeiPlugin
public class CustomGlintJeiPlugin implements IModPlugin {

    private static final Identifier UID = CustomGlint.res("jei_plugin");

    /** The 8-color sample palette the merge examples (glint and glow) fan out into, one color per input trim. */
    private static final int[] MERGE_PALETTE =
            { 0xFFFF0000, 0xFF0000FF, 0xFF00FFFF, 0xFFFFFF00, 0xFF8800CC, 0xFF00FF00, 0xFFFF8000, 0xFFFF80A0 };

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        // Every Glint Trim is the same Item; its design/colors/glow live in NBT. Without a subtype interpreter
        // JEI keys all trims to one subtype and collapses the whole design selection (and the trim ingredient-
        // info page below) into a single entry. Key off pattern + colors + glow rather than the whole component
        // so a chromatic trim's random per-stack seed doesn't split identical designs.
        //
        // Only split for the INGREDIENT context (the ingredient list / bookmarks). For RECIPE lookup return NONE
        // so every trim shares one subtype; clicking any trim then surfaces all the trim-modifying display
        // recipes (tear / dye / merge / duplicate / speed / scale / opacity / glow / smithing), which each use a
        // fixed sample design and would otherwise only match that exact design+colors.
        // getSubtypeData returns null for "no subtype", the sentinel that collapses trims to one entry.
        registration.registerSubtypeInterpreter(ModItems.GLINT_TRIM.get(), (stack, ctx) -> {
            if (ctx == UidContext.Recipe) return null;
            Identifier pattern = GlintTrimItem.getPattern(stack);
            if (pattern == null) return null;
            StringBuilder key = new StringBuilder(pattern.toString());
            for (int color : GlintTrimItem.getColors(stack)) key.append(',').append(color);
            if (GlintTrimItem.isGlowing(stack)) key.append(":glow");
            return key.toString();
        });
        registration.registerSubtypeInterpreter(ModItems.GLOW_TRIM.get(), (stack, ctx) -> {
            if (ctx == UidContext.Recipe) return null;
            int[] colors = GlowTrimItem.getColors(stack);
            if (colors.length == 0) return null;
            StringBuilder key = new StringBuilder();
            for (int color : colors) key.append(color).append(',');
            return key.toString();
        });
    }

    @Override
    public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration registration) {
        // JEI's smithing category fills its slots from the recipe's item-only Ingredients, so the template
        // slot would show an undesigned trim. This extension feeds the actual colored trim and glinted result.
        registration.getSmithingCategory().addExtension(GlintSmithingDisplay.class, new GlintSmithingExtension());
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addIngredientInfo(new ItemStack(ModItems.GLINT_WAND.get()), VanillaTypes.ITEM_STACK,
            Component.literal("Right-click to open the Glint Editor and paint animated enchantment glints onto any item."));
        registration.addIngredientInfo(new ItemStack(ModItems.GLINT_TABLE_ITEM.get()), VanillaTypes.ITEM_STACK,
            Component.literal("Place it down and right-click to open the slot-based trim builder. Store designs, paint them with dyes, set brightness and animation, then print finished Glint Trims to apply at a smithing table."));

        List<ItemStack> trimVariants = new ArrayList<>();
        for (String patternName : GlintTrimItem.PATTERNS) {
            ItemStack trimVariant = new ItemStack(ModItems.GLINT_TRIM.get());
            Identifier patternRl = CustomGlint.designFromName(patternName); // handles vanilla + chromatic sentinels
            GlintTrimItem.setPattern(trimVariant, patternRl);
            trimVariants.add(trimVariant);
        }
        registration.addIngredientInfo(trimVariants, VanillaTypes.ITEM_STACK,
            Component.literal("Smithing template carrying a glint design. Craft with dyes to add colors, then apply to any item with Glowstone Dust at a smithing table."));
        registration.addIngredientInfo(ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with any glinted item to set all layers to Simultaneous mode, all colors render at once."));
        registration.addIngredientInfo(ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with any glinted item to set all layers to Sequential mode, colors cycle one at a time."));
        registration.addIngredientInfo(ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with two Glint Trims to merge their layer arrays into a single multi-layer trim (up to 8 layers)."));
        registration.addIngredientInfo(ModItems.GLINT_BLACK_TEAR.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with any glinted item to strip all glint data from it."));
        registration.addIngredientInfo(ModItems.RAINBOW_DYE.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Use it in the Glint Table to give a color shard any custom hex color. One Rainbow Dye is consumed per custom color."));
        registration.addIngredientInfo(ModItems.GLINT_BAG.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Holds Glint Trims, Tears, Rainbow Dye and Glint Table materials. Auto-collects glint loot as you play (shift-right-click to toggle). Shift-right-click a Glint Table to unload trims into its library and materials into its slots."));
        registration.addIngredientInfo(new ItemStack(ModItems.TRIM_POWDER.get()), VanillaTypes.ITEM_STACK,
            Component.literal("Smelt an unwanted Glint or Glow Trim into Trim Powder. Craft 4 Trim Powder with 2 Glowstone Dust to get a fresh random Glint Trim."));

        IVanillaRecipeFactory factory = registration.getVanillaRecipeFactory();
        List<RecipeHolder<CraftingRecipe>> crafting = new ArrayList<>();
        List<RecipeHolder<SmithingRecipe>> smithing = new ArrayList<>();

        // Tear: tear + colored trim -> same trim in the other render mode. One pair (sim + seq) per design.
        Identifier[] tearDesigns = { CustomGlint.WAVE, CustomGlint.STRIPES, CustomGlint.SPARKLE, CustomGlint.VANILLA, CustomGlint.CRYSTAL, CustomGlint.SWIRL };
        int[][] tearColors = {
            {0xFFFF0000, 0xFF0000FF},
            {0xFF00FF00, 0xFFFFFF00},
            {0xFF8800CC, 0xFFFF00FF, 0xFFFF80A0},
            {0xFFFF0000, 0xFFFF8000, 0xFFFFFF00, 0xFF00FF00},
            {0xFF00FFFF, 0xFF00AAFF, 0xFF0000FF, 0xFF8800CC, 0xFFFF80A0},
            {0xFFFF0000, 0xFFFF8000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFF8800CC, 0xFFFF80A0},
        };
        for (int i = 0; i < tearDesigns.length; i++) {
            addTear(crafting, factory, "jei_tear_sim_" + i, true,  tearDesigns[i], tearColors[i]);
            addTear(crafting, factory, "jei_tear_seq_" + i, false, tearDesigns[i], tearColors[i]);
        }

        // Dye: blank trim + dye -> trim with that color.
        addDye(crafting, factory, "jei_dye_0", CustomGlint.WAVE,    Items.DYE.red(),    GlintTrimItem.DYE_COLORS[14]);
        addDye(crafting, factory, "jei_dye_1", CustomGlint.STRIPES, Items.DYE.blue(),   GlintTrimItem.DYE_COLORS[11]);
        addDye(crafting, factory, "jei_dye_2", CustomGlint.SPARKLE, Items.DYE.cyan(),   GlintTrimItem.DYE_COLORS[9]);
        addDye(crafting, factory, "jei_dye_3", CustomGlint.VANILLA, Items.DYE.yellow(), GlintTrimItem.DYE_COLORS[4]);
        addDye(crafting, factory, "jei_dye_4", CustomGlint.CRYSTAL, Items.DYE.purple(), GlintTrimItem.DYE_COLORS[10]);
        addDye(crafting, factory, "jei_dye_5", CustomGlint.SWIRL,   Items.DYE.lime(),   GlintTrimItem.DYE_COLORS[5]);

        // Merge: several single-color trims -> one multi-color trim.
        for (int n = 2; n <= 8; n++) {
            int[] colors = new int[n];
            System.arraycopy(MERGE_PALETTE, 0, colors, 0, n);
            addMerge(crafting, factory, "jei_merge_" + (n - 2), CustomGlint.WAVE, colors);
        }

        // Duplicate: one trim -> two of the same trim; blank trim -> two blank trims.
        addDuplicate(crafting, factory, "jei_duplicate_0", CustomGlint.WAVE, 0xFFFF0000);
        addBlankDuplicate(crafting, factory, "jei_duplicate_1", CustomGlint.WAVE);

        // Layer tear: layer tear + two trims -> one two-layer trim.
        addLayer(crafting, factory, "jei_layer_0", CustomGlint.WAVE,    0xFFFF0000, CustomGlint.SPARKLE, 0xFF0000FF);
        addLayer(crafting, factory, "jei_layer_1", CustomGlint.VANILLA, 0xFFFF8000, CustomGlint.CRYSTAL, 0xFF00FFFF);
        addLayer(crafting, factory, "jei_layer_2", CustomGlint.STRIPES, 0xFF00FF00, CustomGlint.SWIRL,   0xFF8800CC);

        // Black tear: black tear + glinted item -> clean item.
        addBlackTear(crafting, factory, "jei_black_0", Items.DIAMOND_SWORD,      CustomGlint.WAVE,    0xFFFF0000);
        addBlackTear(crafting, factory, "jei_black_1", Items.GOLDEN_CHESTPLATE,  CustomGlint.SPARKLE, 0xFF00AAFF);
        addBlackTear(crafting, factory, "jei_black_2", Items.BOW,                CustomGlint.STRIPES, 0xFFFFFF00);
        addBlackTear(crafting, factory, "jei_black_3", Items.BOOK,               CustomGlint.VANILLA, 0xFF8800CC);

        // Speed: trim + redstone -> trim with faster animation (count = ticks).
        for (int n = 1; n <= 8; n++) {
            addAmount(crafting, factory, "jei_speed_" + n, CustomGlint.WAVE, 0xFFFF4400, Items.REDSTONE, n, true);
        }

        // Scale: trim + slime ball -> trim with larger pattern (visibly different tiling).
        for (int n = 1; n <= 8; n++) {
            addAmount(crafting, factory, "jei_scale_" + n, CustomGlint.SPARKLE, 0xFF00AAFF, Items.SLIME_BALL, n, false);
        }

        // Opacity: trim + glass -> trim with lower alpha (more glass = more transparent, 8 = fully faded).
        for (int n = 1; n <= 8; n++) {
            addAlpha(crafting, factory, "jei_alpha_" + n, CustomGlint.CRYSTAL, 0xFF00FFFF, n);
        }

        // Glow trim: trim + glowstone dust -> the glowing variant.
        addGlow(crafting, factory, "jei_glow_0", CustomGlint.WAVE,    0xFFFF0000);
        addGlow(crafting, factory, "jei_glow_1", CustomGlint.SPARKLE, 0xFF00AAFF);
        addGlow(crafting, factory, "jei_glow_2", CustomGlint.AURORA,  0xFFFFDD00);

        // Glow Trim dyeing: blank Glow Trim + any dye -> Glow Trim with that color. One consolidated entry
        // whose dye slot and result slot cycle all 16 dyes in lockstep.
        addGlowDyeCycling(crafting, factory, "jei_glow_dye");

        // Glow Trim merge: several single-color Glow Trims -> one multi-color Glow Trim.
        for (int n = 2; n <= 8; n++) {
            int[] colors = new int[n];
            System.arraycopy(MERGE_PALETTE, 0, colors, 0, n);
            addGlowMerge(crafting, factory, "jei_glow_merge_" + (n - 2), colors);
        }

        // Trim Powder recycle: 4 Trim Powder + 2 Glowstone Dust -> a fresh random Glint Trim. The output is
        // rolled at craft time, so a blank sample trim stands in for the "random" result (the item's info
        // page spells out that it's random).
        addPowderCraft(crafting, factory, "jei_trim_powder");

        registration.addRecipes(RecipeTypes.CRAFTING, crafting);

        // Smithing: trim + base item + glowstone dust -> the base item carrying the glint.
        addSmithing(smithing, "jei_smithing_0", CustomGlint.WAVE,    new int[]{0xFFFF0000},                                                 Items.DIAMOND_SWORD,      true);
        addSmithing(smithing, "jei_smithing_1", CustomGlint.CRYSTAL, new int[]{0xFF00FFFF, 0xFF00AAFF},                                     Items.DIAMOND_CHESTPLATE, true);
        addSmithing(smithing, "jei_smithing_2", CustomGlint.AURORA,  new int[]{0xFFFF6600, 0xFFFFDD00},                                     Items.BOW,                true);
        addSmithing(smithing, "jei_smithing_3", CustomGlint.SWIRL,   new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF}, Items.ELYTRA,             false);
        addSmithing(smithing, "jei_smithing_4", CustomGlint.VANILLA, new int[]{0xFFFFAA00},                                                 Items.ENCHANTED_BOOK,     true);

        // Glow smithing: Glow Trim + base item + glowstone dust -> the base item with a colored outline glow.
        addGlowSmithing(smithing, "jei_glow_smithing_0", new int[]{0xFFFF0000},             Items.DIAMOND_SWORD);
        addGlowSmithing(smithing, "jei_glow_smithing_1", new int[]{0xFF00AAFF, 0xFF00FFFF}, Items.DIAMOND_CHESTPLATE);
        addGlowSmithing(smithing, "jei_glow_smithing_2", new int[]{0xFFFFDD00},             Items.ELYTRA);

        registration.addRecipes(RecipeTypes.SMITHING, smithing);
    }

    // Glint Trim example builders.

    private void addTear(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                         boolean simultaneous, Identifier design, int[] colors) {
        // A tear flips the render mode: the simultaneous tear takes a sequential trim and outputs a
        // simultaneous one (and vice versa). Input is the opposite mode, output is the tear's mode.
        ItemStack tear = (simultaneous ? ModItems.GLINT_TEAR_SIMULTANEOUS : ModItems.GLINT_TEAR_SEQUENTIAL).get().getDefaultInstance();
        craftShapeless(out, f, id, trimMode(design, colors, simultaneous), tear, trimMode(design, colors, !simultaneous));
    }

    private void addDye(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                        Identifier design, Item dye, int color) {
        craftShapeless(out, f, id, trim(design, new int[]{color}), trim(design, new int[0]), new ItemStack(dye));
    }

    private void addMerge(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                          Identifier design, int[] colors) {
        ItemStack[] inputs = new ItemStack[colors.length];
        for (int i = 0; i < colors.length; i++) inputs[i] = trim(design, new int[]{colors[i]});
        craftShapeless(out, f, id, trim(design, colors), inputs);
    }

    private void addDuplicate(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                              Identifier design, int color) {
        // Mirrors GlintTrimDuplicateRecipe: trim in the center, glowstone dust below it, diamonds elsewhere.
        ItemStack trim = trim(design, new int[]{color});
        ItemStack result = trim.copy();
        result.setCount(2);
        ItemStack d = new ItemStack(Items.DIAMOND);
        ItemStack g = new ItemStack(Items.GLOWSTONE_DUST);
        craft(out, f, id, result, d, d, d, d, trim, d, d, g, d);
    }

    private void addLayer(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                          Identifier d1, int c1, Identifier d2, int c2) {
        // Mirrors GlintLayerTearRecipe.assemble: copy the first trim (keeping its config/colors/tooltip) and
        // overwrite its glint Data with both trims' layers combined. Building a bare trim instead leaves an
        // empty TrimConfig, which renders static and tooltips as "no color or data".
        ItemStack trim1 = trim(d1, new int[]{c1});
        ItemStack trim2 = trim(d2, new int[]{c2});
        CustomGlint.Data data1 = CustomGlint.read(trim1);
        CustomGlint.Data data2 = CustomGlint.read(trim2);
        int total = Math.min(data1.layers().length + data2.layers().length, 8); // 8 = the cap GlintLayerTearRecipe enforces
        CustomGlint.Layer[] combined = new CustomGlint.Layer[total];
        int fromD1 = Math.min(data1.layers().length, total);
        System.arraycopy(data1.layers(), 0, combined, 0, fromD1);
        int fromD2 = total - fromD1;
        if (fromD2 > 0) System.arraycopy(data2.layers(), 0, combined, fromD1, fromD2);
        ItemStack result = trim1.copy();
        result.setCount(1);
        CustomGlint.write(result, combined);
        craftShapeless(out, f, id, result, ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance(), trim1, trim2);
    }

    private void addBlackTear(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                              Item base, Identifier design, int color) {
        ItemStack glinted = CustomGlint.glinted(base, design, new int[]{color});
        craftShapeless(out, f, id, new ItemStack(base), ModItems.GLINT_BLACK_TEAR.get().getDefaultInstance(), glinted);
    }

    private void addAmount(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                           Identifier design, int color, Item reagent, int count, boolean speed) {
        ItemStack result = trim(design, new int[]{color});
        if (speed) GlintTrimItem.setSpeed(result, (float) count);
        else GlintTrimItem.setScale(result, count * 0.5f);
        ItemStack[] inputs = new ItemStack[count + 1];
        inputs[0] = trim(design, new int[]{color});
        for (int i = 0; i < count; i++) inputs[i + 1] = new ItemStack(reagent);
        craftShapeless(out, f, id, result, inputs);
    }

    private void addGlow(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                         Identifier design, int color) {
        // Mirrors GlintGlowTrimRecipe: a colored trim in the center surrounded by 8 Glowstone Dust (shaped).
        ItemStack result = trim(design, new int[]{color});
        GlintTrimItem.setGlowing(result, true);
        CustomGlint.setGlowing(result, true); // render-level flag that drives the glow outline
        ItemStack t = trim(design, new int[]{color});
        ItemStack g = new ItemStack(Items.GLOWSTONE_DUST);
        craft(out, f, id, result, g, g, g, g, t, g, g, g, g);
    }

    private void addAlpha(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                          Identifier design, int color, int count) {
        // Mirrors GlintTrimAlphaRecipe.assemble: N glass fades the trim's color, 8 glass = fully transparent.
        ItemStack result = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(result, design);
        int alpha = Math.round(count * 255f / 8f);
        GlintTrimItem.setColors(result, new int[]{ (alpha << 24) | (color & 0xFFFFFF) });
        ItemStack[] inputs = new ItemStack[count + 1];
        inputs[0] = trim(design, new int[]{color});
        for (int i = 0; i < count; i++) inputs[i + 1] = new ItemStack(Items.GLASS);
        craftShapeless(out, f, id, result, inputs);
    }

    private void addBlankDuplicate(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                                   Identifier design) {
        // Mirrors GlintTrimBlankDuplicateRecipe: a blank (colorless) trim center, glowstone below, diamonds
        // elsewhere -> two blank trims.
        ItemStack trim = trim(design, new int[0]);
        ItemStack result = trim.copy();
        result.setCount(2);
        ItemStack d = new ItemStack(Items.DIAMOND);
        ItemStack g = new ItemStack(Items.GLOWSTONE_DUST);
        craft(out, f, id, result, d, d, d, d, trim, d, d, g, d);
    }

    private void addSmithing(List<RecipeHolder<SmithingRecipe>> out, String id,
                             Identifier design, int[] colors, Item base, boolean simultaneous) {
        ItemStack trim = trim(design, colors);
        ItemStack result = new ItemStack(base);
        CustomGlint.write(result, design, colors, 1.0f, true, 1.0f, simultaneous);
        out.add(new RecipeHolder<SmithingRecipe>(key(id), new GlintSmithingDisplay(trim, ModItems.GLINT_TRIM.get(), base, result)));
    }

    // Glow Trim example builders.

    /** A Glow Trim stack carrying the given colors (renders its glowing outline preview via setColors). */
    private static ItemStack glowTrim(int[] colors) {
        ItemStack s = new ItemStack(ModItems.GLOW_TRIM.get());
        if (colors.length > 0) GlowTrimItem.setColors(s, colors);
        return s;
    }

    /** Dye: blank Glow Trim + any dye -> Glow Trim with that color (mirrors GlowTrimDyeRecipe). Built as one
     *  shapeless display whose dye slot and result slot are {@link SlotDisplay.Composite}s of all 16 dyes/colors
     *  in the same order, so JEI cycles them in lockstep: dye N shows the Glow Trim in color N. Mirrors the
     *  consolidated cycling dye entry on 1.21.1. */
    private void addGlowDyeCycling(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id) {
        Item[] dyes = GlintTableMenu.DYE_ITEMS;
        List<SlotDisplay> dyeDisplays = new ArrayList<>();
        List<SlotDisplay> resultDisplays = new ArrayList<>();
        for (int i = 0; i < dyes.length; i++) {
            dyeDisplays.add(display(new ItemStack(dyes[i])));
            resultDisplays.add(display(glowTrim(new int[]{GlintTrimItem.DYE_COLORS[i]})));
        }
        List<SlotDisplay> inputDisplays = List.of(display(glowTrim(new int[0])), new SlotDisplay.Composite(dyeDisplays));
        SlotDisplay resultDisplay = new SlotDisplay.Composite(resultDisplays);
        List<Ingredient> ingredients = List.of(Ingredient.of(ModItems.GLOW_TRIM.get()), Ingredient.of((ItemLike[]) dyes));
        CraftingRecipe recipe = new ShapelessRecipe(
                new Recipe.CommonInfo(true),
                new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                ItemStackTemplate.fromNonEmptyStack(glowTrim(new int[]{GlintTrimItem.DYE_COLORS[0]})),
                ingredients) {
            @Override
            public List<RecipeDisplay> display() {
                return List.<RecipeDisplay>of(new ShapelessCraftingRecipeDisplay(
                        inputDisplays, resultDisplay, new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)));
            }
        };
        out.add(new RecipeHolder<CraftingRecipe>(key(id), recipe));
    }

    /** Merge: several single-color Glow Trims -> one multi-color Glow Trim (mirrors GlowTrimMergeRecipe). */
    private void addGlowMerge(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                              int[] colors) {
        ItemStack[] inputs = new ItemStack[colors.length];
        for (int i = 0; i < colors.length; i++) inputs[i] = glowTrim(new int[]{colors[i]});
        craftShapeless(out, f, id, glowTrim(colors), inputs);
    }

    /** Trim Powder recycle: 4 Trim Powder + 2 Glowstone Dust -> a random Glint Trim (mirrors
     *  TrimPowderRecipe). Shown with a blank sample trim as the stand-in output. */
    private void addPowderCraft(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id) {
        ItemStack powder = new ItemStack(ModItems.TRIM_POWDER.get());
        ItemStack glow = new ItemStack(Items.GLOWSTONE_DUST);
        craftShapeless(out, f, id, trim(CustomGlint.WAVE, new int[0]), powder, powder, powder, powder, glow, glow);
    }

    /** Smithing: Glow Trim (template) + base + Glowstone Dust -> base carrying the glow outline (mirrors
     *  GlowTrimSmithingRecipe; leaves any existing glint on the base untouched). */
    private void addGlowSmithing(List<RecipeHolder<SmithingRecipe>> out, String id, int[] colors, Item base) {
        ItemStack template = glowTrim(colors);
        ItemStack result = new ItemStack(base);
        CustomGlint.setGlowColors(result, colors);
        CustomGlint.setGlowing(result, true);
        out.add(new RecipeHolder<SmithingRecipe>(key(id), new GlintSmithingDisplay(template, ModItems.GLOW_TRIM.get(), base, result)));
    }

    /**
     * Smithing display carrying the colored trim and glinted result for the {@link GlintSmithingExtension}
     * to render. It is a real {@link SmithingTransformRecipe} so JEI routes it through the smithing category;
     * the extension overrides the slots, since the category otherwise fills them from item-only Ingredients.
     */
    private static final class GlintSmithingDisplay extends SmithingTransformRecipe {
        private final ItemStack trimStack;
        private final Item baseItem;
        private final ItemStack resultStack;

        GlintSmithingDisplay(ItemStack trimStack, Item templateItem, Item baseItem, ItemStack resultStack) {
            super(new Recipe.CommonInfo(true),
                Optional.of(Ingredient.of(templateItem)),
                Ingredient.of(baseItem),
                Optional.of(Ingredient.of(Items.GLOWSTONE_DUST)),
                ItemStackTemplate.fromNonEmptyStack(resultStack));
            this.trimStack = trimStack;
            this.baseItem = baseItem;
            this.resultStack = resultStack;
        }
    }

    /** Feeds the colored trim into the template slot and the glinted item into the output slot, instead of
     *  the item-only Ingredients the default smithing category would render. */
    private static final class GlintSmithingExtension implements ISmithingCategoryExtension<GlintSmithingDisplay> {
        @Override
        public <T extends IIngredientAcceptor<T>> void setTemplate(GlintSmithingDisplay recipe, T acceptor) {
            acceptor.add(recipe.trimStack);
        }

        @Override
        public <T extends IIngredientAcceptor<T>> void setBase(GlintSmithingDisplay recipe, T acceptor) {
            acceptor.add(new ItemStack(recipe.baseItem));
        }

        @Override
        public <T extends IIngredientAcceptor<T>> void setAddition(GlintSmithingDisplay recipe, T acceptor) {
            acceptor.add(new ItemStack(Items.GLOWSTONE_DUST));
        }

        @Override
        public <T extends IIngredientAcceptor<T>> void setOutput(GlintSmithingDisplay recipe, T acceptor) {
            acceptor.add(recipe.resultStack);
        }
    }

    // Shared stack / display helpers.

    /** A glinted Glint Trim stack for previews (pattern then colors so the preview glint is rewritten). */
    private static ItemStack trim(Identifier design, int[] colors) {
        ItemStack s = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(s, design);
        if (colors.length > 0) GlintTrimItem.setColors(s, colors);
        return s;
    }

    /** A Glint Trim in the given render mode. Mirrors GlintTearApplyRecipe: build a (sequential) trim, then
     *  rewrite its layers with the requested simultaneous flag, which the standard setters never set true. */
    private static ItemStack trimMode(Identifier design, int[] colors, boolean simultaneous) {
        ItemStack s = trim(design, colors);
        CustomGlint.Data data = CustomGlint.read(s);
        if (data != null) {
            CustomGlint.Layer[] src = data.layers();
            CustomGlint.Layer[] out = new CustomGlint.Layer[src.length];
            for (int i = 0; i < src.length; i++) {
                CustomGlint.Layer l = src[i];
                out[i] = new CustomGlint.Layer(l.design(), l.colors(), l.speed(), l.interpolate(), l.patternScale(),
                    simultaneous, l.scrollDir(), l.scrollOffset(), l.seed());
            }
            CustomGlint.write(s, out);
        }
        return s;
    }

    /** A component-carrying slot display, so the glint renders in the JEI slot instead of a blank item. */
    private static SlotDisplay display(ItemStack stack) {
        return new SlotDisplay.ItemStackSlotDisplay(ItemStackTemplate.fromNonEmptyStack(stack));
    }

    private static ResourceKey<Recipe<?>> key(String id) {
        return ResourceKey.create(Registries.RECIPE, CustomGlint.res(id));
    }

    /**
     * Build a shaped crafting display laid out row-major in a 3-wide grid. Each input gets its own
     * pattern key and a {@link SlotDisplay} carrying its full stack, so glinted trims show their colors.
     */
    private void craft(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                       ItemStack result, ItemStack... inputs) {
        IJeiShapedRecipeBuilder builder = f.createShapedRecipeBuilder(CraftingBookCategory.MISC, display(result));
        int cols = Math.min(3, inputs.length);
        int rows = (inputs.length + cols - 1) / cols;
        int idx = 0;
        for (int r = 0; r < rows; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                if (idx < inputs.length) {
                    char ch = (char) ('A' + idx);
                    builder.define(ch, Ingredient.of(inputs[idx].getItem()), display(inputs[idx]));
                    row.append(ch);
                    idx++;
                } else {
                    row.append(' ');
                }
            }
            builder.pattern(row.toString());
        }
        out.add(new RecipeHolder<CraftingRecipe>(key(id), builder.build()));
    }

    /**
     * Build a SHAPELESS crafting display. JEI's crafting category reads {@link CraftingRecipe#display()} and
     * branches on {@link ShapelessCraftingRecipeDisplay} vs the shaped display, so a {@link ShapelessRecipe}
     * whose {@code display()} carries the colored {@link SlotDisplay}s renders as a shapeless craft with the
     * glint previews intact. Unlike {@link #craft}, this doesn't use the factory (there is no shapeless builder);
     * {@code f} is kept in the signature only so the builder helpers call it the same way.
     */
    private void craftShapeless(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                               ItemStack result, ItemStack... inputs) {
        List<Ingredient> ingredients = new ArrayList<>();
        List<SlotDisplay> inputDisplays = new ArrayList<>();
        for (ItemStack in : inputs) {
            ingredients.add(Ingredient.of(in.getItem()));
            inputDisplays.add(display(in));
        }
        SlotDisplay resultDisplay = display(result);
        CraftingRecipe recipe = new ShapelessRecipe(
                new Recipe.CommonInfo(true),
                new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                ItemStackTemplate.fromNonEmptyStack(result),
                ingredients) {
            @Override
            public List<RecipeDisplay> display() {
                return List.<RecipeDisplay>of(new ShapelessCraftingRecipeDisplay(
                        inputDisplays, resultDisplay, new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)));
            }
        };
        out.add(new RecipeHolder<CraftingRecipe>(key(id), recipe));
    }
}
