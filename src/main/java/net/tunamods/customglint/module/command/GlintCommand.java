package net.tunamods.customglint.module.command;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

import net.neoforged.neoforge.network.PacketDistributor;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.ModConfigPaths;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.menu.GlintTableMenu;
import net.tunamods.customglint.module.menu.ModAttachments;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;

/**
 * The op-gated {@code /glint} command tree: apply, remove and glow on the held item, entity glints on a
 * target selector, plus the cheat/extract/export utilities. Every branch is permission level 2, so it is a
 * creative and server-admin tool; players get the same operations through the wand and the Glint Table.
 */
public class GlintCommand {

    /** Palette the {@code colors} argument accepts, in wand-swatch order. Names match the vanilla dyes. */
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
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("apply")
                .then(Commands.argument("design", StringArgumentType.word())
                    .suggests(SUGGEST_DESIGNS)
                    .then(Commands.argument("colors", StringArgumentType.string())
                        .suggests(SUGGEST_COLORS)
                        .executes(ctx -> apply(ctx.getSource(),
                            StringArgumentType.getString(ctx, "design"),
                            StringArgumentType.getString(ctx, "colors"),
                            1.0f, true, 1.0f, false))
                        .then(Commands.argument("speed", FloatArgumentType.floatArg(0.25f, 8.0f))
                            .executes(ctx -> apply(ctx.getSource(),
                                StringArgumentType.getString(ctx, "design"),
                                StringArgumentType.getString(ctx, "colors"),
                                FloatArgumentType.getFloat(ctx, "speed"), true, 1.0f, false))
                            .then(Commands.argument("smooth", BoolArgumentType.bool())
                                .executes(ctx -> apply(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "design"),
                                    StringArgumentType.getString(ctx, "colors"),
                                    FloatArgumentType.getFloat(ctx, "speed"),
                                    BoolArgumentType.getBool(ctx, "smooth"), 1.0f, false))
                                .then(Commands.argument("scale", FloatArgumentType.floatArg(0.25f, 4.0f))
                                    .executes(ctx -> apply(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "design"),
                                        StringArgumentType.getString(ctx, "colors"),
                                        FloatArgumentType.getFloat(ctx, "speed"),
                                        BoolArgumentType.getBool(ctx, "smooth"),
                                        FloatArgumentType.getFloat(ctx, "scale"), false))
                                    .then(Commands.argument("simultaneous", BoolArgumentType.bool())
                                        .executes(ctx -> apply(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "design"),
                                            StringArgumentType.getString(ctx, "colors"),
                                            FloatArgumentType.getFloat(ctx, "speed"),
                                            BoolArgumentType.getBool(ctx, "smooth"),
                                            FloatArgumentType.getFloat(ctx, "scale"),
                                            BoolArgumentType.getBool(ctx, "simultaneous"))))))))))
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
                                .executes(ctx -> applyEntity(ctx.getSource(),
                                    EntityArgument.getEntities(ctx, "targets"),
                                    StringArgumentType.getString(ctx, "design"),
                                    StringArgumentType.getString(ctx, "colors"),
                                    1.0f, true, 1.0f, false, false))
                                .then(Commands.argument("glowing", BoolArgumentType.bool())
                                    .executes(ctx -> applyEntity(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "targets"),
                                        StringArgumentType.getString(ctx, "design"),
                                        StringArgumentType.getString(ctx, "colors"),
                                        1.0f, true, 1.0f, false,
                                        BoolArgumentType.getBool(ctx, "glowing")))
                                    .then(Commands.argument("speed", FloatArgumentType.floatArg(0.25f, 8.0f))
                                        .executes(ctx -> applyEntity(ctx.getSource(),
                                            EntityArgument.getEntities(ctx, "targets"),
                                            StringArgumentType.getString(ctx, "design"),
                                            StringArgumentType.getString(ctx, "colors"),
                                            FloatArgumentType.getFloat(ctx, "speed"), true, 1.0f, false,
                                            BoolArgumentType.getBool(ctx, "glowing")))
                                        .then(Commands.argument("smooth", BoolArgumentType.bool())
                                            .executes(ctx -> applyEntity(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "targets"),
                                                StringArgumentType.getString(ctx, "design"),
                                                StringArgumentType.getString(ctx, "colors"),
                                                FloatArgumentType.getFloat(ctx, "speed"),
                                                BoolArgumentType.getBool(ctx, "smooth"), 1.0f, false,
                                                BoolArgumentType.getBool(ctx, "glowing")))
                                            .then(Commands.argument("scale", FloatArgumentType.floatArg(0.25f, 4.0f))
                                                .executes(ctx -> applyEntity(ctx.getSource(),
                                                    EntityArgument.getEntities(ctx, "targets"),
                                                    StringArgumentType.getString(ctx, "design"),
                                                    StringArgumentType.getString(ctx, "colors"),
                                                    FloatArgumentType.getFloat(ctx, "speed"),
                                                    BoolArgumentType.getBool(ctx, "smooth"),
                                                    FloatArgumentType.getFloat(ctx, "scale"), false,
                                                    BoolArgumentType.getBool(ctx, "glowing")))
                                                .then(Commands.argument("simultaneous", BoolArgumentType.bool())
                                                    .executes(ctx -> applyEntity(ctx.getSource(),
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        StringArgumentType.getString(ctx, "design"),
                                                        StringArgumentType.getString(ctx, "colors"),
                                                        FloatArgumentType.getFloat(ctx, "speed"),
                                                        BoolArgumentType.getBool(ctx, "smooth"),
                                                        FloatArgumentType.getFloat(ctx, "scale"),
                                                        BoolArgumentType.getBool(ctx, "simultaneous"),
                                                        BoolArgumentType.getBool(ctx, "glowing")))))))))))
                    .then(Commands.literal("remove")
                        .executes(ctx -> removeEntity(ctx.getSource(),
                            EntityArgument.getEntities(ctx, "targets"))))
                    .then(Commands.literal("glow")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> glowEntity(ctx.getSource(),
                                EntityArgument.getEntities(ctx, "targets"),
                                BoolArgumentType.getBool(ctx, "enabled"))))))));
    }

    private static int applyEntity(CommandSourceStack source, Collection<? extends Entity> targets,
                                   String designName, String colorsArg,
                                   float speed, boolean smooth, float scale, boolean simultaneous,
                                   boolean glowing) {
        ResourceLocation design = resolveDesign(source, designName);
        if (design == null) return 0;
        int[] colors = parseColors(source, colorsArg);
        if (colors == null) return 0;

        CustomGlint.Layer layer = new CustomGlint.Layer(design, colors, speed, smooth, scale, simultaneous);
        CustomGlint.Layer[] layers = CustomGlint.ensureChromaticSeeds(new CustomGlint.Layer[]{ layer });

        int count = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity le)) continue;
            CustomGlint.writeEntity(le, layers);
            CustomGlint.setEntityGlowing(le, glowing);
            count++;
        }
        if (count == 0) {
            source.sendFailure(Component.literal("No matching living entities"));
            return 0;
        }
        final int n = count;
        source.sendSuccess(() -> Component.literal("Glint applied to " + n + " entit" + (n == 1 ? "y" : "ies")), false);
        return count;
    }

    private static int removeEntity(CommandSourceStack source, Collection<? extends Entity> targets) {
        int count = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity le)) continue;
            if (!CustomGlint.hasEntity(le)) continue;
            CustomGlint.removeEntity(le);
            count++;
        }
        if (count == 0) {
            source.sendFailure(Component.literal("No matching living entities had a glint"));
            return 0;
        }
        final int n = count;
        source.sendSuccess(() -> Component.literal("Glint removed from " + n + " entit" + (n == 1 ? "y" : "ies")), false);
        return count;
    }

    private static int glowEntity(CommandSourceStack source, Collection<? extends Entity> targets, boolean enabled) {
        int count = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity le)) continue;
            if (enabled && !CustomGlint.hasEntity(le)) continue;
            CustomGlint.setEntityGlowing(le, enabled);
            count++;
        }
        if (count == 0) {
            source.sendFailure(Component.literal(enabled
                ? "No matching living entities have a glint to glow"
                : "No matching living entities"));
            return 0;
        }
        final int n = count;
        source.sendSuccess(() -> Component.literal((enabled ? "Glowing enabled on " : "Glowing disabled on ") + n + " entit" + (n == 1 ? "y" : "ies")), false);
        return count;
    }

    private static int apply(CommandSourceStack source, String designName, String colorsArg,
                              float speed, boolean smooth, float scale, boolean simultaneous) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        ResourceLocation design = resolveDesign(source, designName);
        if (design == null) return 0;
        int[] colors = parseColors(source, colorsArg);
        if (colors == null) return 0;

        ItemStack stack = requireHeldItem(source, player);
        if (stack == null) return 0;

        CustomGlint.write(stack, CustomGlint.ensureChromaticSeeds(
                new CustomGlint.Layer[]{ new CustomGlint.Layer(design, colors, speed, smooth, scale, simultaneous) }));
        source.sendSuccess(() -> Component.literal("Glint applied"), false);
        return 1;
    }

    private static int glow(CommandSourceStack source, boolean enabled) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        ItemStack stack = requireHeldItem(source, player);
        if (stack == null) return 0;

        // No glint required; glow is independent of glint Data. A glow-only item (no glowColors, no
        // glint) outlines in white by default; if a glint is present the outline picks up its colour.
        CustomGlint.setGlowing(stack, enabled);
        source.sendSuccess(() -> Component.literal(enabled ? "Glowing outline enabled" : "Glowing outline disabled"), false);
        return 1;
    }

    private static int remove(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        ItemStack stack = requireHeldItem(source, player);
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
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        ItemStack held = requireHeldItem(source, player);
        if (held == null) return 0;

        CustomGlint.Data data = CustomGlint.read(held);
        if (data == null) {
            source.sendFailure(Component.literal("Item has no custom glint"));
            return 0;
        }

        CustomGlint.Layer[] layers = data.layers();
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
            // Multi-layer: set the display config from layer 0, then copy the full glint tag verbatim.
            CustomGlint.Layer layer0 = layers[0];
            for (int color : layer0.colors()) GlintTrimItem.addColor(trim, color);
            boolean glowing = CustomGlint.isGlowing(held);
            // setConfig sets the trim's display config WITHOUT rewriting the single-layer preview glint, so
            // the multi-layer tag copied below isn't clobbered.
            GlintTrimItem.setConfig(trim, layer0.design(), layer0.speed(), layer0.patternScale(),
                    layer0.scrollDir(), layer0.scrollOffset(), glowing);
            // Copy the full multi-layer glint tag verbatim from the held item.
            CustomGlint.writeItemTag(trim, CustomGlint.itemGlintTag(held));
            // Set CustomModelData without calling setGlowing (which would clobber the multi-layer tag).
            // Same index scheme as GlintTrimItem: glowing variants sit in a +1000 band, and +1 keeps
            // index 0 (the default model) free.
            String name = layer0.design().equals(CustomGlint.VANILLA) ? "vanilla" : GlintTrimItem.extractPatternName(layer0.design());
            int idx = GlintTrimItem.PATTERNS.indexOf(name);
            if (idx >= 0) trim.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData((glowing ? 1000 : 0) + idx + 1));
        }

        if (!player.getInventory().add(trim)) player.drop(trim, false);
        source.sendSuccess(() -> Component.literal("Glint Trim extracted"), false);
        return 1;
    }

    private static int export(CommandSourceStack source, String name) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        ItemStack held = requireHeldItem(source, player);
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
            int[] glowCols = CustomGlint.getGlowColors(held);
            if (glowCols.length > 0) {
                JsonArray glowArray = new JsonArray();
                for (int c : glowCols) glowArray.add(String.format("0x%08X", c));
                root.add("glowColors", glowArray);
            }

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

            source.sendSuccess(() -> Component.literal("Glint trim exported to: " + file.toString()), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to export: " + e.getMessage()));
            return 0;
        }
    }

    // Shared argument/source checks. Each reports its own failure message and returns null, so callers
    // only need a null check before carrying on.

    /** The command sender as a player, or null (after sending "Must be a player") when the source isn't one. */
    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) source.sendFailure(Component.literal("Must be a player"));
        return player;
    }

    /** The player's main-hand item, or null (after sending "Hold an item...") when the hand is empty. */
    private static ItemStack requireHeldItem(CommandSourceStack source, ServerPlayer player) {
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) {
            source.sendFailure(Component.literal("Hold an item in your main hand"));
            return null;
        }
        return stack;
    }

    /** Resolves a design name to its ResourceLocation, or reports the valid names and returns null. */
    private static ResourceLocation resolveDesign(CommandSourceStack source, String designName) {
        String key = designName.toLowerCase();
        if (!GlintTrimItem.PATTERNS.contains(key)) {
            source.sendFailure(Component.literal(
                "Unknown design '" + designName + "'. Valid: " + String.join(", ", GlintTrimItem.PATTERNS)));
            return null;
        }
        // designFromName handles the vanilla/chromatic sentinels and namespace:name forms, and uses tryParse
        // (not the throwing factory) so a malformed data-pack design name can't crash the tick.
        return CustomGlint.designFromName(key);
    }

    /** Parses a comma-separated color-name list to ARGB (capped at MAX_COLORS_PER_LAYER, since the renderer
     *  fans out one draw per color), or reports the offending name and returns null. */
    private static int[] parseColors(CommandSourceStack source, String colorsArg) {
        String[] parts = colorsArg.split(",");
        if (parts.length > CustomGlint.MAX_COLORS_PER_LAYER)
            parts = Arrays.copyOf(parts, CustomGlint.MAX_COLORS_PER_LAYER);
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
}