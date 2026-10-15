package net.tunamods.customglint.module.rule;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.ModConfigPaths;
import net.tunamods.customglint.module.network.GlintRulesSyncPacket;
import net.tunamods.customglint.module.network.ModNetworking;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Glint rules: "this item always comes with this glint", set in-game with {@code /glint rule} and saved to
 * {@code config/glint-and-glamour/rules.json} so a modpack can ship them. Server-side; the only thing a client
 * ever sees is the item rules, through {@link GlintRulesSyncPacket}.
 *
 * <p>Three kinds, picked by how the item reaches the player:
 * <ul>
 *   <li>{@link Kind#LOOT}: the item gets the glint when it is rolled from one loot table (chests, mob drops,
 *       fishing, archaeology). Applied by {@code GlintLootModifier}.</li>
 *   <li>{@link Kind#DROP}: the item gets the glint when one mob type drops it, including drops a mod adds in
 *       code instead of through a loot table. Applied from {@code LivingDropsEvent}.</li>
 *   <li>{@link Kind#ITEM}: every stack of the item renders with the glint, wherever it came from. Nothing is
 *       written to NBT; clients look it up at render time ({@link CustomGlint#getItemGlint}).</li>
 * </ul>
 * LOOT and DROP write the glint into the stack, glow included, so the item keeps it after the rule is removed.
 * None of the three replace a glint the stack already has.
 *
 * <p>Items and targets are kept as ids, never resolved and dropped on load, so a rule for a mod that is missing
 * for one launch is not deleted from the file the next time it is saved.
 */
public final class GlintRules {
    private GlintRules() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Kind {
        LOOT("loot", "table"), DROP("drop", "entity"), ITEM("item", null);

        /** Name in the JSON {@code type} field and in {@code /glint rule list}. */
        public final String id;
        /** JSON field holding the target id, or null for a kind with no target. */
        @Nullable final String targetKey;

        Kind(String id, @Nullable String targetKey) {
            this.id = id;
            this.targetKey = targetKey;
        }

        @Nullable
        static Kind byId(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return null;
        }
    }

    /** {@code target} is the loot table (LOOT) or entity type (DROP) id, null for ITEM. {@code glint} is the
     *  inner {@code customglint} tag copied off the item the rule was made from. */
    public record Rule(Kind kind, @Nullable ResourceLocation target, ResourceLocation item, CompoundTag glint) {
        boolean sameSlot(Rule o) {
            return kind == o.kind && item.equals(o.item) && Objects.equals(target, o.target);
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();

    public static Path file() {
        return ModConfigPaths.CONFIG_DIR.resolve("rules.json");
    }

    /** All rules in file order. */
    public static List<Rule> all() {
        return Collections.unmodifiableList(RULES);
    }

    /** Adds {@code rule}, replacing the rule for the same kind, target and item if there is one. Returns the
     *  replaced rule, or null. Call {@link #save} and {@link #syncAll} afterwards. */
    @Nullable
    public static Rule put(Rule rule) {
        for (int i = 0; i < RULES.size(); i++) {
            if (RULES.get(i).sameSlot(rule)) return RULES.set(i, rule);
        }
        RULES.add(rule);
        return null;
    }

    /** Removes every rule for {@code item}, or only those of {@code kind} when it is non-null, and returns what
     *  was removed (empty when nothing matched). Call {@link #save} and {@link #syncAll} afterwards. */
    public static List<Rule> remove(ResourceLocation item, @Nullable Kind kind) {
        List<Rule> removed = new ArrayList<>();
        RULES.removeIf(r -> {
            boolean match = r.item.equals(item) && (kind == null || r.kind == kind);
            if (match) removed.add(r);
            return match;
        });
        return removed;
    }

    /** Ids of every item that has at least one rule, for {@code /glint rule remove} tab completion. */
    public static List<ResourceLocation> ruleItems() {
        List<ResourceLocation> out = new ArrayList<>();
        for (Rule r : RULES) if (!out.contains(r.item)) out.add(r.item);
        return out;
    }

    // Applying

    /** LOOT rules for {@code table}: glint every matching stack in {@code loot} that has no glint yet. */
    public static void applyLoot(ResourceLocation table, Collection<ItemStack> loot) {
        for (Rule r : RULES) {
            if (r.kind != Kind.LOOT || !table.equals(r.target)) continue;
            for (ItemStack stack : loot) applyTo(r, stack);
        }
    }

    /** DROP rules for {@code type}: glint every matching dropped stack that has no glint yet. */
    public static void applyDrops(EntityType<?> type, Collection<ItemEntity> drops) {
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (typeId == null) return;
        for (Rule r : RULES) {
            if (r.kind != Kind.DROP || !typeId.equals(r.target)) continue;
            for (ItemEntity drop : drops) applyTo(r, drop.getItem());
        }
    }

    private static void applyTo(Rule r, ItemStack stack) {
        if (stack.isEmpty() || CustomGlint.has(stack)) return;
        if (!r.item.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))) return;
        CustomGlint.writeItemTag(stack, r.glint);
    }

    // Item rule sync

    /** Item id to glint tag for every ITEM rule, the payload of {@link GlintRulesSyncPacket}. */
    public static Map<ResourceLocation, CompoundTag> itemRules() {
        Map<ResourceLocation, CompoundTag> out = new LinkedHashMap<>();
        for (Rule r : RULES) if (r.kind == Kind.ITEM) out.put(r.item, r.glint);
        return out;
    }

    public static void syncTo(ServerPlayer player) {
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new GlintRulesSyncPacket(itemRules()));
    }

    public static void syncAll() {
        ModNetworking.CHANNEL.send(PacketDistributor.ALL.noArg(), new GlintRulesSyncPacket(itemRules()));
    }

    // File

    /** Replaces the in-memory rules with the file's. A missing file means no rules; a bad entry is logged and
     *  skipped, and the rest still load. Returns how many rules loaded. */
    public static int load() {
        RULES.clear();
        Path file = file();
        if (!Files.exists(file)) return 0;
        JsonArray rules;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file));
            rules = root.getAsJsonObject().getAsJsonArray("rules");
        } catch (Exception e) {
            LOGGER.error("Could not read {}: {}", file, e.toString());
            return 0;
        }
        if (rules == null) return 0;
        for (int i = 0; i < rules.size(); i++) {
            try {
                RULES.add(fromJson(rules.get(i).getAsJsonObject()));
            } catch (Exception e) {
                LOGGER.warn("Skipping rule {} in {}: {}", i + 1, file, e.getMessage());
            }
        }
        return RULES.size();
    }

    /** Writes the rules to the file. Returns false (and logs) if the write failed. */
    public static boolean save() {
        JsonArray rules = new JsonArray();
        for (Rule r : RULES) rules.add(toJson(r));
        JsonObject root = new JsonObject();
        root.add("rules", rules);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), gson.toJson(root));
            return true;
        } catch (Exception e) {
            LOGGER.error("Could not write {}: {}", file(), e.toString());
            return false;
        }
    }

    private static JsonObject toJson(Rule r) {
        JsonObject o = new JsonObject();
        o.addProperty("type", r.kind.id);
        if (r.kind.targetKey != null) o.addProperty(r.kind.targetKey, r.target.toString());
        o.addProperty("item", r.item.toString());
        o.addProperty("glint", r.glint.toString()); // SNBT, the same text /data get prints
        return o;
    }

    private static Rule fromJson(JsonObject o) throws Exception {
        String type = str(o, "type");
        Kind kind = Kind.byId(type);
        if (kind == null) throw new IllegalArgumentException("unknown type \"" + type + "\" (use loot, drop or item)");
        ResourceLocation target = null;
        if (kind.targetKey != null) target = id(o, kind.targetKey);
        ResourceLocation item = id(o, "item");
        CompoundTag glint;
        try {
            glint = TagParser.parseTag(str(o, "glint"));
        } catch (Exception e) {
            throw new IllegalArgumentException("\"glint\" is not valid SNBT: " + e.getMessage());
        }
        if (CustomGlint.fromTag(glint) == null && !CustomGlint.tagGlowing(glint))
            throw new IllegalArgumentException("\"glint\" has no layers and no glow");
        return new Rule(kind, target, item, glint);
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) throw new IllegalArgumentException("missing \"" + key + "\"");
        return o.get(key).getAsString();
    }

    private static ResourceLocation id(JsonObject o, String key) {
        ResourceLocation id = ResourceLocation.tryParse(str(o, key));
        if (id == null) throw new IllegalArgumentException("bad id in \"" + key + "\"");
        return id;
    }
}
