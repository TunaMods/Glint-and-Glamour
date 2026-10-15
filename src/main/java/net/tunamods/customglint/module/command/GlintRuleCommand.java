package net.tunamods.customglint.module.command;

import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraftforge.registries.ForgeRegistries;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.rule.GlintRules;
import net.tunamods.customglint.module.rule.GlintRules.Kind;
import net.tunamods.customglint.module.rule.GlintRules.Rule;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /glint rule}: turns the glint on the item in your hand into a rule that saves to
 * {@code config/glint-and-glamour/rules.json} (see {@link GlintRules} for what each kind does).
 *
 * <pre>
 * /glint rule loot &lt;loot table&gt;   this item gets the glint when rolled from that loot table
 * /glint rule drop &lt;entity type&gt;  this item gets the glint when that mob drops it
 * /glint rule item                every stack of this item renders with the glint
 * /glint rule list                every rule
 * /glint rule remove &lt;item&gt;       delete every rule for that item
 * /glint rule remove &lt;item&gt; &lt;kind&gt; delete only its loot, drop or item rules
 * /glint rule reload              re-read rules.json after editing it by hand
 * </pre>
 *
 * Hung off {@code /glint}, so it inherits that command's op level 2 requirement.
 */
public final class GlintRuleCommand {
    private GlintRuleCommand() {}

    // Both providers read the server's own lists, so modded loot tables and mobs tab-complete too.
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TABLES = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(
                    ctx.getSource().getServer().getLootData().getKeys(LootDataType.TABLE), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ENTITIES = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(ForgeRegistries.ENTITY_TYPES.getKeys(), builder);

    // Only items that already have a rule, so remove never offers something it can't act on.
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_RULE_ITEMS = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(GlintRules.ruleItems(), builder);

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        // remove <item> clears all of the item's rules; remove <item> <kind> narrows it to one kind.
        RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> removeItem =
                Commands.argument("item", ResourceLocationArgument.id())
                    .suggests(SUGGEST_RULE_ITEMS)
                    .executes(ctx -> remove(ctx.getSource(), ResourceLocationArgument.getId(ctx, "item"), null));
        for (Kind kind : Kind.values()) {
            removeItem.then(Commands.literal(kind.id)
                    .executes(ctx -> remove(ctx.getSource(), ResourceLocationArgument.getId(ctx, "item"), kind)));
        }
        return Commands.literal("rule")
            .then(Commands.literal("loot")
                .then(Commands.argument("table", ResourceLocationArgument.id())
                    .suggests(SUGGEST_TABLES)
                    .executes(ctx -> add(ctx.getSource(), Kind.LOOT, ResourceLocationArgument.getId(ctx, "table")))))
            .then(Commands.literal("drop")
                .then(Commands.argument("entity", ResourceLocationArgument.id())
                    .suggests(SUGGEST_ENTITIES)
                    .executes(ctx -> add(ctx.getSource(), Kind.DROP, ResourceLocationArgument.getId(ctx, "entity")))))
            .then(Commands.literal("item")
                .executes(ctx -> add(ctx.getSource(), Kind.ITEM, null)))
            .then(Commands.literal("list")
                .executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("remove")
                .then(removeItem))
            .then(Commands.literal("reload")
                .executes(ctx -> reload(ctx.getSource())));
    }

    private static int add(CommandSourceStack source, Kind kind, @Nullable ResourceLocation target) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be a player: the rule copies the glint off your held item"));
            return 0;
        }
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        boolean hasLayers = CustomGlint.read(held) != null;
        // Loot and drop rules copy the glow outline too, so a glow-only item is a valid template for them. An
        // item rule renders the layers alone, so it needs at least one.
        if (held.isEmpty() || !(hasLayers || (kind != Kind.ITEM && CustomGlint.isGlowing(held)))) {
            source.sendFailure(Component.literal(
                    "Hold the item the rule is for, with its glint already on it (use the Glint Wand or /glint apply)"));
            return 0;
        }

        if (kind == Kind.LOOT && !source.getServer().getLootData().getKeys(LootDataType.TABLE).contains(target)) {
            source.sendFailure(Component.literal("Unknown loot table '" + target + "'"));
            return 0;
        }
        if (kind == Kind.DROP && !ForgeRegistries.ENTITY_TYPES.containsKey(target)) {
            source.sendFailure(Component.literal("Unknown entity type '" + target + "'"));
            return 0;
        }

        ResourceLocation item = ForgeRegistries.ITEMS.getKey(held.getItem());
        Rule rule = new Rule(kind, target, item, CustomGlint.itemGlintTag(held));
        boolean replaced = GlintRules.put(rule) != null;
        boolean saved = GlintRules.save();
        if (kind == Kind.ITEM) GlintRules.syncAll();

        String glowNote = kind == Kind.ITEM && CustomGlint.isGlowing(held)
                ? ". Item rules show the glint only, not the glow outline" : "";
        String msg = (replaced ? "Replaced rule: " : "Added rule: ") + describe(rule) + glowNote;
        source.sendSuccess(() -> Component.literal(msg), true);
        if (!saved) source.sendFailure(Component.literal("Could not write " + GlintRules.file() + "; the rule lasts until restart. See the log"));
        return 1;
    }

    private static int list(CommandSourceStack source) {
        List<Rule> rules = GlintRules.all();
        if (rules.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No glint rules yet. Hold a glinted item and use /glint rule loot, drop or item"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Glint rules (" + GlintRules.file() + "):"), false);
        for (Rule r : rules) {
            String line = "- " + describe(r);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return rules.size();
    }

    private static int remove(CommandSourceStack source, ResourceLocation item, @Nullable Kind kind) {
        List<Rule> removed = GlintRules.remove(item, kind);
        if (removed.isEmpty()) {
            String which = kind == null ? "no rules" : "no " + kind.id + " rules";
            source.sendFailure(Component.literal(item + " has " + which + ". See /glint rule list"));
            return 0;
        }
        boolean saved = GlintRules.save();
        if (removed.stream().anyMatch(r -> r.kind() == Kind.ITEM)) GlintRules.syncAll();
        for (Rule r : removed) {
            // LOOT and DROP wrote the glint into each stack's NBT, so those items keep it; ITEM never wrote anything.
            String note = r.kind() == Kind.ITEM ? "" : ". Items that already got this glint keep it";
            source.sendSuccess(() -> Component.literal("Removed rule: " + describe(r) + note), true);
        }
        if (!saved) source.sendFailure(Component.literal("Could not write " + GlintRules.file() + "; see the log"));
        return removed.size();
    }

    private static int reload(CommandSourceStack source) {
        int n = GlintRules.load();
        GlintRules.syncAll();
        source.sendSuccess(() -> Component.literal("Reloaded " + n + " glint rule" + (n == 1 ? "" : "s")
                + " from " + GlintRules.file() + " (bad entries are skipped and logged)"), true);
        return n;
    }

    /** One line per rule, e.g. {@code loot minecraft:entities/wither, minecraft:nether_star: wave, sparkle + glow}. */
    private static String describe(Rule r) {
        StringBuilder sb = new StringBuilder(r.kind().id);
        if (r.target() != null) sb.append(' ').append(r.target()).append(',');
        sb.append(' ').append(r.item()).append(": ");
        CustomGlint.Data data = CustomGlint.fromTag(r.glint());
        List<String> designs = new ArrayList<>();
        if (data != null) {
            for (CustomGlint.Layer l : data.layers())
                designs.add(l.design().equals(CustomGlint.VANILLA) ? "vanilla" : GlintTrimItem.extractPatternName(l.design()));
        }
        sb.append(designs.isEmpty() ? "no layers" : String.join(", ", designs));
        if (CustomGlint.tagGlowing(r.glint())) sb.append(" + glow");
        return sb.toString();
    }
}
