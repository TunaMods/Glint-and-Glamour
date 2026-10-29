package net.tunamods.customglint.module.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.ModConfigPaths;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.menu.GlintTableMenu;
import net.tunamods.customglint.module.menu.ModAttachments;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.util.Prediction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomModelData;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GlintCommand {

    private static final Map<String, Integer> COLORS = new LinkedHashMap<>();

    static {
        COLORS.put("red",        CustomGlint.RED);
        COLORS.put("orange",     CustomGlint.ORANGE);
        COLORS.put("yellow",     CustomGlint.YELLOW);
        COLORS.put("lime",       CustomGlint.LIME);
        COLORS.put("green",      CustomGlint.GREEN);
        COLORS.put("cyan",       CustomGlint.CYAN);
        COLORS.put("light_blue", CustomGlint.LIGHT_BLUE);
        COLORS.put("blue",       CustomGlint.BLUE);
        COLORS.put("purple",     CustomGlint.PURPLE);
        COLORS.put("magenta",    CustomGlint.MAGENTA);
        COLORS.put("pink",       CustomGlint.PINK);
        COLORS.put("brown",      CustomGlint.BROWN);
        COLORS.put("white",      CustomGlint.WHITE);
        COLORS.put("light_gray", CustomGlint.LIGHT_GRAY);
        COLORS.put("gray",       CustomGlint.GRAY);
        COLORS.put("black",      CustomGlint.BLACK);
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_DESIGNS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String name : GlintTrimItem.PATTERNS) {
                if (name.startsWith(remaining)) builder.suggest(name);
            }
            return builder.buildFuture();
        };

    private static final String[] SCROLL_NAMES =
        { "static", "e", "ne", "n", "nw", "w", "sw", "s", "se" };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_SCROLL =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String name : SCROLL_NAMES) {
                if (name.startsWith(remaining)) builder.suggest(name);
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_COLORS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining();
            boolean quoted = remaining.startsWith("\"");
            String inner   = quoted ? remaining.substring(1) : remaining;
            int lastComma  = inner.lastIndexOf(',');
            String prefix  = lastComma >= 0 ? inner.substring(0, lastComma + 1) : "";
            String partial = lastComma >= 0 ? inner.substring(lastComma + 1)    : inner;
            for (String name : COLORS.keySet()) {
                if (name.startsWith(partial.toLowerCase())) {
                    String value = prefix + name;
                    builder.suggest(quoted ? "\"" + value + "\"" : value);
                }
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("glint")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("apply")
                .then(Commands.argument("design", StringArgumentType.word())
                    .suggests(SUGGEST_DESIGNS)
                    .then(Commands.argument("colors", StringArgumentType.string())
                        .suggests(SUGGEST_COLORS)
                        .executes(applyAt(0))
                        .then(Commands.argument("speed", FloatArgumentType.floatArg(0.25f, 8.0f))
                            .executes(applyAt(1))
                            .then(Commands.argument("smooth", BoolArgumentType.bool())
                                .executes(applyAt(2))
                                .then(Commands.argument("scale", FloatArgumentType.floatArg(0.25f, 4.0f))
                                    .executes(applyAt(3))
                                    .then(Commands.argument("simultaneous", BoolArgumentType.bool())
                                        .executes(applyAt(4))
                                        .then(Commands.argument("direction", StringArgumentType.word())
                                            .suggests(SUGGEST_SCROLL)
                                            .executes(applyAt(5))))))))))
            .then(Commands.literal("remove")
                .executes(ctx -> remove(ctx.getSource())))
            .then(Commands.literal("glow")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(ctx -> glow(ctx.getSource(),
                        BoolArgumentType.getBool(ctx, "enabled")))))
            .then(Commands.literal("cheat")
                .executes(ctx -> cheat(ctx.getSource())))
            .then(Commands.literal("extract")
                .executes(ctx -> extract(ctx.getSource())))
            .then(Commands.literal("export")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> export(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))
            .then(GlintRuleCommand.node())
            .then(Commands.literal("entity")
                .then(Commands.argument("targets", EntityArgument.entities())
                    .then(Commands.literal("apply")
                        .then(Commands.argument("design", StringArgumentType.word())
                            .suggests(SUGGEST_DESIGNS)
                            .then(Commands.argument("colors", StringArgumentType.string())
                                .suggests(SUGGEST_COLORS)
                                .executes(applyEntityAt(0))
                                .then(Commands.argument("glowing", BoolArgumentType.bool())
                                    .executes(applyEntityAt(1))
                                    .then(Commands.argument("speed", FloatArgumentType.floatArg(0.25f, 8.0f))
                                        .executes(applyEntityAt(2))
                                        .then(Commands.argument("smooth", BoolArgumentType.bool())
                                            .executes(applyEntityAt(3))
                                            .then(Commands.argument("scale", FloatArgumentType.floatArg(0.25f, 4.0f))
                                                .executes(applyEntityAt(4))
                                                .then(Commands.argument("simultaneous", BoolArgumentType.bool())
                                                    .executes(applyEntityAt(5))
                                                    .then(Commands.argument("direction", StringArgumentType.word())
                                                        .suggests(SUGGEST_SCROLL)
                                                        .executes(applyEntityAt(6)))))))))))
                    .then(Commands.literal("remove")
                        .executes(ctx -> removeEntity(ctx.getSource(),
                            EntityArgument.getEntities(ctx, "targets"))))
                    .then(Commands.literal("glow")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> glowEntity(ctx.getSource(),
                                EntityArgument.getEntities(ctx, "targets"),
                                BoolArgumentType.getBool(ctx, "enabled"))))))));
    }

    // Both apply chains take their optional arguments in a fixed order, so the node at depth N has exactly
    // the first N of them parsed. One executor factory per chain therefore covers every depth: read an
    // argument once the depth reaches it, otherwise substitute the default.

    /** Executor for the {@code /glint apply} node at the given depth (0 = design + colors only). */
    private static Command<CommandSourceStack> applyAt(int level) {
        return ctx -> apply(ctx.getSource(),
            StringArgumentType.getString(ctx, "design"),
            StringArgumentType.getString(ctx, "colors"),
            level >= 1 ? FloatArgumentType.getFloat(ctx, "speed") : 1.0f,
            level >= 2 ? BoolArgumentType.getBool(ctx, "smooth") : true,
            level >= 3 ? FloatArgumentType.getFloat(ctx, "scale") : 1.0f,
            level >= 4 && BoolArgumentType.getBool(ctx, "simultaneous"),
            level >= 5 ? GlintTrimItem.scrollFromName(StringArgumentType.getString(ctx, "direction"))
                       : CustomGlint.SCROLL_E);
    }

    /** Executor for the {@code /glint entity <targets> apply} node at the given depth. The entity chain takes
     *  {@code glowing} first, so its depths do not line up with {@link #applyAt}'s. */
    private static Command<CommandSourceStack> applyEntityAt(int level) {
        return ctx -> applyEntity(ctx.getSource(),
            EntityArgument.getEntities(ctx, "targets"),
            StringArgumentType.getString(ctx, "design"),
            StringArgumentType.getString(ctx, "colors"),
            level >= 2 ? FloatArgumentType.getFloat(ctx, "speed") : 1.0f,
            level >= 3 ? BoolArgumentType.getBool(ctx, "smooth") : true,
            level >= 4 ? FloatArgumentType.getFloat(ctx, "scale") : 1.0f,
            level >= 5 && BoolArgumentType.getBool(ctx, "simultaneous"),
            level >= 1 && BoolArgumentType.getBool(ctx, "glowing"),
            level >= 6 ? GlintTrimItem.scrollFromName(StringArgumentType.getString(ctx, "direction"))
                       : CustomGlint.SCROLL_E);
    }

    /** Resolves a design name to its texture Identifier, or null (after sending a failure) if unknown. */
    @Nullable
    private static Identifier resolveDesign(CommandSourceStack source, String designName) {
        String key = designName.toLowerCase();
        if (!GlintTrimItem.PATTERNS.contains(key)) {
            source.sendFailure(Component.literal(
                "Unknown design '" + designName + "'. Valid: " + String.join(", ", GlintTrimItem.PATTERNS)));
            return null;
        }
        return CustomGlint.designFromName(key);
    }

    /** Parses a comma-separated color-name list into ARGB ints, or null (after sending a failure) on
     *  the first unknown color. */
    @Nullable
    private static int[] parseColors(CommandSourceStack source, String colorsArg) {
        String[] parts = colorsArg.split(",");
        if (parts.length > CustomGlint.MAX_COLORS_PER_LAYER) {
            source.sendFailure(Component.literal(
                "Too many colors (max " + CustomGlint.MAX_COLORS_PER_LAYER + "); got " + parts.length + "."));
            return null;
        }
        int[] colors = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i].trim().toLowerCase();
            Integer c = COLORS.get(name);
            if (c == null) {
                source.sendFailure(Component.literal(
                    "Unknown color '" + name + "'. Valid: " + String.join(", ", COLORS.keySet())));
                return null;
            }
            colors[i] = c;
        }
        return colors;
    }

    /** The commanding player, or null after sending the failure (console / command block). */
    @Nullable
    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) source.sendFailure(Component.literal("Must be a player"));
        return player;
    }

    /** The source's main-hand stack, or null after sending the failure (not a player, or empty hand). */
    @Nullable
    private static ItemStack requireHeldStack(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return null;
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) {
            source.sendFailure(Component.literal("Hold an item in your main hand"));
            return null;
        }
        return stack;
    }

    /** Report a per-entity command result: failure text on a zero count, else a success line with the
     *  pluralized "entit(y|ies)" suffix. Returns count so callers can {@code return} it as the command result. */
    private static int reportEntityResult(CommandSourceStack source, int count, String failMsg, String successPrefix) {
        if (count == 0) {
            source.sendFailure(Component.literal(failMsg));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(successPrefix + " " + count + " entit" + (count == 1 ? "y" : "ies")), false);
        return count;
    }

    private static int apply(CommandSourceStack source, String designName, String colorsArg,
                              float speed, boolean smooth, float scale, boolean simultaneous, int scrollDir) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        Identifier design = resolveDesign(source, designName);
        if (design == null) return 0;
        int[] colors = parseColors(source, colorsArg);
        if (colors == null) return 0;

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) {
            source.sendFailure(Component.literal("Hold an item in your main hand"));
            return 0;
        }

        int seed = CustomGlint.isChromatic(design) ? CustomGlint.randomChromaticSeed() : 0;
        CustomGlint.write(stack, design, colors, speed, smooth, scale, simultaneous, scrollDir, 0.0f, seed);
        source.sendSuccess(() -> Component.literal("Glint applied"), false);
        return 1;
    }

    private static int glow(CommandSourceStack source, boolean enabled) {
        ItemStack stack = requireHeldStack(source);
        if (stack == null) return 0;

        // Glow is independent of the glint: an item can carry a glowing outline with no custom glint at all.
        CustomGlint.setGlowing(stack, enabled);
        source.sendSuccess(() -> Component.literal(enabled ? "Glowing outline enabled" : "Glowing outline disabled"), false);
        return 1;
    }

    private static int remove(CommandSourceStack source) {
        ItemStack stack = requireHeldStack(source);
        if (stack == null) return 0;

        if (!CustomGlint.has(stack)) {
            source.sendFailure(Component.literal("Item has no custom glint"));
            return 0;
        }

        CustomGlint.remove(stack);
        source.sendSuccess(() -> Component.literal("Glint removed"), false);
        return 1;
    }

    /** Unlocks every Glint Table design at once: stores all built-in designs (+ the Glow Trim) into the
     *  player's design library so the whole left palette is usable, instead of depositing each trim one by
     *  one from creative. */
    private static int cheat(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        // Copy before mutating; the decoded attachment list is immutable (see GlintTableMenu#slotsChanged).
        List<String> stored = new ArrayList<>(player.getData(ModAttachments.STORED_DESIGNS.get()));
        int before = stored.size();
        if (!stored.contains(GlowTrimItem.STORAGE_KEY)) stored.add(GlowTrimItem.STORAGE_KEY);
        for (String name : GlintTrimItem.PATTERNS) if (!stored.contains(name)) stored.add(name);

        int added = stored.size() - before;
        player.setData(ModAttachments.STORED_DESIGNS.get(), stored);
        PacketDistributor.sendToPlayer(player, new GlintStoredSyncPacket(new ArrayList<>(stored)));
        GlintTableMenu.checkDesignAdvancements(player);

        final int n = added;
        source.sendSuccess(() -> Component.literal(n == 0
                ? "All Glint Table designs were already unlocked"
                : "Unlocked " + n + " Glint Table design" + (n == 1 ? "" : "s")), false);
        return 1;
    }

    private static int extract(CommandSourceStack source) {
        ItemStack held = requireHeldStack(source);
        if (held == null) return 0;
        ServerPlayer player = source.getPlayer();

        CustomGlint.Data data = CustomGlint.read(held);
        if (data == null) {
            source.sendFailure(Component.literal("Item has no custom glint"));
            return 0;
        }

        CustomGlint.Layer[] layers = data.layers();
        // A decoded Data may legally hold zero layers ({"layers":[]} via crafted give-NBT / datapack,
        // Data.CODEC sets no minimum). The multi-layer branch below dereferences layers[0], so guard here.
        if (layers.length == 0) {
            source.sendFailure(Component.literal("Item has no custom glint"));
            return 0;
        }
        ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());

        if (layers.length == 1) {
            CustomGlint.Layer layer = layers[0];
            for (int color : layer.colors()) GlintTrimItem.addColor(trim, color);
            GlintTrimItem.setSpeed(trim, layer.speed());
            GlintTrimItem.setScale(trim, layer.patternScale());
            GlintTrimItem.setScrollDir(trim, layer.scrollDir());
            GlintTrimItem.setScrollOffset(trim, layer.scrollOffset());
            GlintTrimItem.setPattern(trim, layer.design());
            GlintTrimItem.setGlowing(trim, CustomGlint.isGlowing(held));
            // setPattern/setGlowing re-emit the preview as a sequential layer; write the real one last so the
            // simultaneous flag and seed survive.
            CustomGlint.write(trim, layers);
            GlintTrimItem.setSeed(trim, layer.seed());
        } else {
            // Multi-layer: set flat tags from layer 0 for display, then copy the full glint tag verbatim
            CustomGlint.Layer layer0 = layers[0];
            for (int color : layer0.colors()) GlintTrimItem.addColor(trim, color);
            boolean glowing = CustomGlint.isGlowing(held);
            GlintTrimItem.setConfig(trim, layer0.design(), layer0.speed(), layer0.patternScale(), layer0.scrollDir(), layer0.scrollOffset(), glowing);
            // Copy the full multi-layer glint state verbatim from the held item.
            CustomGlint.writeState(trim, CustomGlint.readState(held));
            // Set CustomModelData without calling setGlowing (which would clobber the multi-layer tag).
            String name = layer0.design().equals(CustomGlint.VANILLA) ? "vanilla" : GlintTrimItem.extractPatternName(layer0.design());
            int idx = GlintTrimItem.PATTERNS.indexOf(name);
            // +1000 selects the glowing model variant; the model JSON keys glowing designs at index+1000.
            if (idx >= 0) trim.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(
                    List.of((float) ((glowing ? 1000 : 0) + idx + 1)),
                    List.of(), List.of(), List.of()));
        }

        if (!player.getInventory().add(trim)) player.drop(trim, false, Prediction.SERVER_ONLY);
        source.sendSuccess(() -> Component.literal("Glint Trim extracted"), false);
        return 1;
    }

    private static int export(CommandSourceStack source, String name) {
        ItemStack held = requireHeldStack(source);
        if (held == null) return 0;

        CustomGlint.Data data = CustomGlint.read(held);
        if (data == null) {
            source.sendFailure(Component.literal("Item has no custom glint"));
            return 0;
        }

        try {
            Path configDir = ModConfigPaths.TRIMS_DIR;
            Files.createDirectories(configDir);

            JsonObject root = new JsonObject();
            root.addProperty("name", name);
            root.addProperty("glowing", CustomGlint.isGlowing(held));

            if (held.has(DataComponents.CUSTOM_NAME)) {
                Component hover = held.getHoverName();
                root.addProperty("displayName", hover.getString());
                TextColor color = hover.getStyle().getColor();
                if (color != null) {
                    root.addProperty("nameColor", String.format("0x%06X", color.getValue() & 0xFFFFFF));
                }
            }

            JsonArray layersArray = new JsonArray();
            for (CustomGlint.Layer layer : data.layers()) {
                JsonObject layerObj = new JsonObject();
                layerObj.addProperty("design", layer.design().toString());

                JsonArray colorsArray = new JsonArray();
                for (int color : layer.colors()) {
                    colorsArray.add(String.format("0x%08X", color));
                }
                layerObj.add("colors", colorsArray);

                layerObj.addProperty("speed", layer.speed());
                layerObj.addProperty("interpolate", layer.interpolate());
                layerObj.addProperty("patternScale", layer.patternScale());
                layerObj.addProperty("simultaneous", layer.simultaneous());
                layerObj.addProperty("scroll", layer.scrollDir());
                layerObj.addProperty("offset", layer.scrollOffset());

                layersArray.add(layerObj);
            }
            root.add("layers", layersArray);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(root);

            Path file = configDir.resolve(name + ".json");
            try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                writer.write(json);
            }

            source.sendSuccess(() -> Component.literal("Glint trim exported to: " + file), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to export: " + e.getMessage()));
            return 0;
        }
    }

    private static int applyEntity(CommandSourceStack source, Collection<? extends Entity> targets,
                                   String designName, String colorsArg,
                                   float speed, boolean smooth, float scale, boolean simultaneous,
                                   boolean glowing, int scrollDir) {
        Identifier design = resolveDesign(source, designName);
        if (design == null) return 0;
        int[] colors = parseColors(source, colorsArg);
        if (colors == null) return 0;

        int seed = CustomGlint.isChromatic(design) ? CustomGlint.randomChromaticSeed() : 0;
        CustomGlint.Layer layer = new CustomGlint.Layer(design, colors, speed, smooth, scale, simultaneous, scrollDir, 0.0f, seed);
        CustomGlint.Layer[] layers = new CustomGlint.Layer[]{ layer };

        int count = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity le)) continue;
            CustomGlint.writeEntity(le, layers);
            CustomGlint.setEntityGlowing(le, glowing);
            count++;
        }
        return reportEntityResult(source, count, "No matching living entities", "Glint applied to");
    }

    private static int removeEntity(CommandSourceStack source, Collection<? extends Entity> targets) {
        int count = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity le)) continue;
            if (!CustomGlint.hasEntity(le)) continue;
            CustomGlint.removeEntity(le);
            count++;
        }
        return reportEntityResult(source, count, "No matching living entities had a glint", "Glint removed from");
    }

    private static int glowEntity(CommandSourceStack source, Collection<? extends Entity> targets, boolean enabled) {
        int count = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity le)) continue;
            // Glow is independent of the glint: an entity can glow with no custom glint (the outline
            // defaults to white when there are no glow colors or glint to draw its color from).
            CustomGlint.setEntityGlowing(le, enabled);
            count++;
        }
        return reportEntityResult(source, count, "No matching living entities",
                enabled ? "Glowing enabled on" : "Glowing disabled on");
    }
}
