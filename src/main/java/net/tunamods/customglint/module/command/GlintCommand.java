package net.tunamods.customglint.module.command;

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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.entity.EntityGlintEvents;
import net.tunamods.customglint.module.ModConfigPaths;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.menu.GlintTableMenu;
import net.tunamods.customglint.module.menu.GlintTablePlayerData;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;
import net.tunamods.customglint.module.network.ModNetworking;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The {@code /glint} command. Held-item subcommands act on the sender's main hand; the {@code entity}
 * subcommand acts on selector-matched living entities and broadcasts every change through
 * {@link EntityGlintEvents} so trackers see it. Everything here runs on the server, so no client-only
 * class may be referenced from this file.
 */
public class GlintCommand {

    /** Argument bounds for the animation speed multiplier: below 0.25x the glint reads as static. */
    private static final float SPEED_MIN = 0.25f, SPEED_MAX = 8.0f;
    /** Argument bounds for the pattern scale: past 4x the design tiles too finely to read on an item. */
    private static final float SCALE_MIN = 0.25f, SCALE_MAX = 4.0f;

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
            .requires(s -> s.hasPermission(2)) // op level 2, the usual bar for world-editing commands
            .then(Commands.literal("apply")
                .then(Commands.argument("design", StringArgumentType.word())
                    .suggests(SUGGEST_DESIGNS)
                    .then(Commands.argument("colors", StringArgumentType.string())
                        .suggests(SUGGEST_COLORS)
                        .executes(ctx -> apply(ctx.getSource(),
                            StringArgumentType.getString(ctx, "design"),
                            StringArgumentType.getString(ctx, "colors"),
                            1.0f, true, 1.0f, false))
                        .then(Commands.argument("speed", FloatArgumentType.floatArg(SPEED_MIN, SPEED_MAX))
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
                                .then(Commands.argument("scale", FloatArgumentType.floatArg(SCALE_MIN, SCALE_MAX))
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
                                    .then(Commands.argument("speed", FloatArgumentType.floatArg(SPEED_MIN, SPEED_MAX))
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
                                            .then(Commands.argument("scale", FloatArgumentType.floatArg(SCALE_MIN, SCALE_MAX))
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

    // Held-item subcommands.

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

        CustomGlint.Layer layer = new CustomGlint.Layer(design, colors, speed, smooth, scale, simultaneous);
        CustomGlint.write(stack, CustomGlint.ensureChromaticSeeds(new CustomGlint.Layer[]{ layer }));
        source.sendSuccess(() -> Component.literal("Glint applied"), false);
        return 1;
    }

    private static int glow(CommandSourceStack source, boolean enabled) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        ItemStack stack = requireHeldItem(source, player);
        if (stack == null) return 0;

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

        List<String> stored = new ArrayList<>(GlintTablePlayerData.storedDesigns(player));
        int before = stored.size();
        if (!stored.contains(GlowTrimItem.STORAGE_KEY)) stored.add(GlowTrimItem.STORAGE_KEY);
        for (String name : GlintTrimItem.PATTERNS) if (!stored.contains(name)) stored.add(name);

        int added = stored.size() - before;
        GlintTablePlayerData.setStoredDesigns(player, stored);
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new GlintStoredSyncPacket(new ArrayList<>(stored)));
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
            trim.getOrCreateTag().putFloat(GlintTrimItem.SPEED_TAG, layer.speed());
            trim.getOrCreateTag().putFloat(GlintTrimItem.SCALE_TAG, layer.patternScale());
            trim.getOrCreateTag().putInt(GlintTrimItem.SCROLL_TAG, layer.scrollDir());
            trim.getOrCreateTag().putFloat(GlintTrimItem.OFFSET_TAG, layer.scrollOffset());
            GlintTrimItem.setPattern(trim, layer.design());
            GlintTrimItem.setGlowing(trim, CustomGlint.isGlowing(held));
            // setPattern/setGlowing re-emit the preview as a sequential layer; write the real one last so the
            // simultaneous flag and seed survive (same fix as GiveGlintTrimPacket).
            CustomGlint.write(trim, layers);
            GlintTrimItem.setSeed(trim, layer.seed());
        } else {
            // Multi-layer: set flat tags from layer 0 for display, then copy the full glint tag verbatim
            CustomGlint.Layer layer0 = layers[0];
            for (int color : layer0.colors()) GlintTrimItem.addColor(trim, color);
            trim.getOrCreateTag().putFloat(GlintTrimItem.SPEED_TAG, layer0.speed());
            trim.getOrCreateTag().putFloat(GlintTrimItem.SCALE_TAG, layer0.patternScale());
            trim.getOrCreateTag().putString(GlintTrimItem.PATTERN_TAG, layer0.design().toString());
            if (held.hasTag() && held.getTag().contains(CustomGlintMod.MOD_ID)) {
                trim.getOrCreateTag().put(CustomGlintMod.MOD_ID, held.getTag().get(CustomGlintMod.MOD_ID).copy());
            }
            // Set glowing and CustomModelData without calling setGlowing (which would clobber the multi-layer tag).
            // Glowing variants sit in the +1000 CustomModelData band; +1 keeps 0 meaning "no override".
            boolean glowing = CustomGlint.isGlowing(held);
            trim.getOrCreateTag().putBoolean(GlintTrimItem.GLOWING_TAG, glowing);
            String name = layer0.design().equals(CustomGlint.VANILLA) ? "vanilla" : GlintTrimItem.extractPatternName(layer0.design());
            int idx = GlintTrimItem.PATTERNS.indexOf(name);
            if (idx >= 0) trim.getOrCreateTag().putInt("CustomModelData", (glowing ? 1000 : 0) + idx + 1);
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

            if (held.hasCustomHoverName()) {
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

    // Entity subcommands. Every mutation runs server-side and is broadcast to the entity's trackers.

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

        int count = forEachLiving(source, targets, "No matching living entities", le -> {
            CustomGlint.writeEntity(le, layers);
            CustomGlint.setEntityGlowing(le, glowing);
            EntityGlintEvents.broadcast(le);
            return true;
        });
        if (count == 0) return 0;
        source.sendSuccess(() -> Component.literal("Glint applied to " + entityCount(count)), false);
        return count;
    }

    private static int removeEntity(CommandSourceStack source, Collection<? extends Entity> targets) {
        int count = forEachLiving(source, targets, "No matching living entities had a glint", le -> {
            if (!CustomGlint.hasEntity(le)) return false;
            CustomGlint.removeEntity(le);
            EntityGlintEvents.broadcast(le);
            return true;
        });
        if (count == 0) return 0;
        source.sendSuccess(() -> Component.literal("Glint removed from " + entityCount(count)), false);
        return count;
    }

    private static int glowEntity(CommandSourceStack source, Collection<? extends Entity> targets, boolean enabled) {
        int count = forEachLiving(source, targets, "No matching living entities", le -> {
            CustomGlint.setEntityGlowing(le, enabled);
            EntityGlintEvents.broadcast(le);
            return true;
        });
        if (count == 0) return 0;
        source.sendSuccess(() -> Component.literal(
                (enabled ? "Glowing enabled on " : "Glowing disabled on ") + entityCount(count)), false);
        return count;
    }

    // Shared helpers.

    /** Runs {@code action} on each living target and counts the ones it reports as changed; sends
     *  {@code emptyMessage} as a failure when nothing matched. */
    private static int forEachLiving(CommandSourceStack source, Collection<? extends Entity> targets,
                                     String emptyMessage, Predicate<LivingEntity> action) {
        int count = 0;
        for (Entity e : targets) {
            if (e instanceof LivingEntity le && action.test(le)) count++;
        }
        if (count == 0) source.sendFailure(Component.literal(emptyMessage));
        return count;
    }

    private static String entityCount(int n) {
        return n + (n == 1 ? " entity" : " entities");
    }

    /** Resolves a design name to its ResourceLocation, or reports the valid names and returns null. */
    private static ResourceLocation resolveDesign(CommandSourceStack source, String designName) {
        String key = designName.toLowerCase();
        if (!GlintTrimItem.PATTERNS.contains(key)) {
            source.sendFailure(Component.literal(
                "Unknown design '" + designName + "'. Valid: " + String.join(", ", GlintTrimItem.PATTERNS)));
            return null;
        }
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
}
