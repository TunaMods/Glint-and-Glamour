package net.tunamods.customglint.module.menu;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.advancement.EightByEightTrimTrigger;
import net.tunamods.customglint.module.blueprint.ServerBlueprints;
import net.tunamods.customglint.module.advancement.ModTriggers;
import net.tunamods.customglint.module.block.ModBlocks;
import net.tunamods.customglint.module.item.ModComponents;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.item.GlintLayerTearItem;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.network.GlintPrintedSyncPacket;
import net.tunamods.customglint.module.network.GlintServerBlueprintsSyncPacket;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Server-side container for the Glint Table.
 *
 * Slot layout (table container, size {@value #TABLE_SIZE}):
 * <pre>
 *   0          main trim slot (Glint or Glow Trim, the active layer being edited)
 *   1..5       slime (scale) / redstone (speed) / glass (opacity) / glowstone (glow) / name tag (name)
 *   6          merge / layer slot (a second trim: merges its colors, or "Add Layer" promotes it)
 *   7          simultaneous-tear slot (paired with slot 27, the sequential tear, under a shared toggle)
 *   8..23      16 dye slots (one per {@link DyeColor})
 *   24         name-color dye (shown when Name)
 *   25         glow-color dye (shown when Glow + manual)
 *   26         layer-tear slot (one consumed per extra layer)
 *   27         sequential-tear slot (paired with slot 7 under the shared Sim/Seq toggle)
 * </pre>
 * followed by the standard 36 player-inventory slots.
 *
 * The slots mirror every item the trim-modifying recipes consume (see {@code module/recipe/}):
 * dye→color, redstone→speed, slime→scale, glowstone→glow, a second trim→merge or layer, tears→mode + layers.
 */
public class GlintTableMenu extends AbstractContainerMenu {

    public static final int SLOT_TRIM      = 0;
    public static final int SLOT_SLIME     = 1;
    public static final int SLOT_REDSTONE  = 2;
    public static final int SLOT_GLASS     = 3;
    public static final int SLOT_GLOWSTONE = 4;
    public static final int SLOT_NAMETAG   = 5;
    public static final int SLOT_TRIM_B    = 6;
    public static final int SLOT_TEAR      = 7;
    public static final int SLOT_DYE_START = 8;
    public static final int SLOT_NAME_DYE  = SLOT_DYE_START + 16; // 24, name-color dye (shown when Name)
    public static final int SLOT_GLOW_DYE  = SLOT_NAME_DYE + 1;   // 25, glow-color dye (shown when Glow + manual)
    public static final int SLOT_LAYER_TEAR = SLOT_GLOW_DYE + 1;  // 26, layer tears (one per extra layer)
    public static final int SLOT_TEAR_SEQ  = SLOT_LAYER_TEAR + 1; // 27, sequential tear (sits beside SLOT_TEAR, the simultaneous one)
    public static final int SLOT_RAINBOW_DYE = SLOT_TEAR_SEQ + 1; // 28, rainbow dye (17th dye-bar slot, enables custom-hex colors)

    /** Marks a custom-hex colour inside a print packet's shard array ({@code CUSTOM_FLAG | rgb}); costs one
     *  rainbow dye. Mirrors the screen's encoding. */
    private static final int CUSTOM_FLAG = 0x40000000;

    public static final int TABLE_SIZE = SLOT_RAINBOW_DYE + 1;   // 29

    /** Stack cap for every table slot except the name tag (a boolean gate, capped at 1). */
    private static final int SLOT_MAX = 64;

    private static final int INV_START = TABLE_SIZE;          // 29
    private static final int INV_END   = TABLE_SIZE + 36;     // 65

    /** Hard cap on a player's stored-design library. Design names are derived from client-supplied trim
     *  patterns, so without a bound a scripted client could grow this save-persisted list without limit. */
    private static final int MAX_STORED_DESIGNS = 128;

    /** Hard cap on a player's printed-trim library. Same reasoning as {@link #MAX_STORED_DESIGNS}, and each
     *  entry is a full ItemStack, so it is the heavier of the two lists to carry in a save. */
    private static final int MAX_PRINTED_TRIMS = 128;

    /** When true (only during {@link #depositBagContents}), the library store helpers mutate + persist but skip
     *  their per-call client sync, so a bulk deposit can push one combined sync instead of one per item. */
    private static boolean deferLibrarySync = false;

    /** Client-only flags set by the screen to show/hide the conditional name/glow dye slots. */
    public boolean showNameDye = false, showGlowDye = false;

    /** The 16 vanilla dye items, indexed by {@link DyeColor#getId()}. */
    public static final Item[] DYE_ITEMS = {
            Items.WHITE_DYE, Items.ORANGE_DYE, Items.MAGENTA_DYE, Items.LIGHT_BLUE_DYE,
            Items.YELLOW_DYE, Items.LIME_DYE, Items.PINK_DYE, Items.GRAY_DYE,
            Items.LIGHT_GRAY_DYE, Items.CYAN_DYE, Items.PURPLE_DYE, Items.BLUE_DYE,
            Items.BROWN_DYE, Items.GREEN_DYE, Items.RED_DYE, Items.BLACK_DYE
    };

    // The container is owned by the block entity (server) so its contents persist; the client gets a
    // transient mirror synced by the vanilla menu. The block-entity container forwards setChanged() to
    // this menu's slotsChanged() (which stores placed designs + syncs the grid).
    private final Container container;
    private final ContainerLevelAccess access;
    private final Player player;

    /** Client constructor, a transient container; the server syncs the real contents into it. */
    public GlintTableMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, new SimpleContainer(TABLE_SIZE) {
            @Override
            public int getMaxStackSize() {
                return SLOT_MAX; // mirror the block entity's raised cap so client/server agree
            }
        }, ContainerLevelAccess.NULL);
    }

    public GlintTableMenu(int containerId, Inventory inventory, Container container, ContainerLevelAccess access) {
        super(ModMenuTypes.GLINT_TABLE_MENU.get(), containerId);
        this.container = container;
        this.access = access;
        this.player = inventory.player;

        // NOTE: slots are added in container-index order so menu.slots.get(i) == container slot i
        // (the screen relies on that to look slots up by SLOT_* constant). Only x/y vary, keep the
        // add order. See the SLOT_* constants above for the authoritative index → role mapping; the
        // material/modifier slots are spread under the left panel to leave room for their labels +
        // value + [-]/[+] controls beneath each.
        // Main / merge trim slots reject physical placement (mayPlace=false): the screen turns a placed trim
        // into a library deposit + ghost preview, so these slots never hold a real item and it can't get stuck
        // where it couldn't be pulled back out. They still exist for layout (the ghost draws over them).
        addSlot(new FilteredSlot(container, SLOT_TRIM,      136, 19,  s -> false,                      SLOT_MAX)); // main (left of center, aligned to the grids' top row)
        // Speed/scale take loose slime balls / redstone only, capped at a 64 stack, the max cost the table charges.
        addSlot(new FilteredSlot(container, SLOT_SLIME,     90,  154, s -> s.is(Items.SLIME_BALL), SLOT_MAX)); // scale
        addSlot(new FilteredSlot(container, SLOT_REDSTONE,  18,  154, s -> s.is(Items.REDSTONE),   SLOT_MAX)); // speed
        addSlot(new FilteredSlot(container, SLOT_GLASS,     54,  154, s -> s.is(Items.GLASS),           SLOT_MAX)); // opacity
        addSlot(new FilteredSlot(container, SLOT_GLOWSTONE, 233, 154, s -> s.is(Items.GLOWSTONE_DUST),  SLOT_MAX)); // glow
        addSlot(new FilteredSlot(container, SLOT_NAMETAG,   267, 154, s -> s.is(Items.NAME_TAG),         1)); // name (boolean gate, one only)
        addSlot(new FilteredSlot(container, SLOT_TRIM_B,    188, 19,  s -> false,                      SLOT_MAX)); // layered (right of center, aligned to the grids' top row); placement rejected, see SLOT_TRIM note
        addSlot(new FilteredSlot(container, SLOT_TEAR,      292, 154, GlintTableMenu::isSimTear,        SLOT_MAX)); // simultaneous tear (left of the shared toggle)

        // 16 dye slots + the rainbow dye (17 cells total), full-width bar, recentered: 17*18 = 306 → x0 = 18.
        for (int i = 0; i < 16; i++) {
            Item dyeItem = DYE_ITEMS[i];
            addSlot(new FilteredSlot(container, SLOT_DYE_START + i, 18 + i * 18, 222, s -> s.getItem() == dyeItem, SLOT_MAX));
        }

        // Conditional color-dye slots in the center column below the Print button (added after the dyes
        // to keep menu.slots index == container index). Active client-side only when toggled on; always
        // active server-side so placement is accepted.
        // Centered on the name box (y 186, h12) and glow button (y 204, h12) rows respectively.
        addSlot(new ToggleDyeSlot(container, SLOT_NAME_DYE, 195, 184, () -> isServer() || showNameDye));
        addSlot(new ToggleDyeSlot(container, SLOT_GLOW_DYE, 195, 202, () -> isServer() || showGlowDye));

        // Layer-tear slot: top row, centered between the main (left) and layered (right) trim slots. One tear per extra layer.
        addSlot(new FilteredSlot(container, SLOT_LAYER_TEAR, 162, 20, GlintTableMenu::isLayerTear, SLOT_MAX));

        // Sequential tear slot: sits to the right of SLOT_TEAR (the simultaneous one); the two flank the
        // shared Sim/Seq toggle. Added last so menu.slots index == container index stays intact.
        addSlot(new FilteredSlot(container, SLOT_TEAR_SEQ, 310, 154, GlintTableMenu::isSeqTear, SLOT_MAX));

        // Rainbow dye: the 17th dye-bar cell (after the 16 dyes), enables custom-hex shard colors.
        addSlot(new FilteredSlot(container, SLOT_RAINBOW_DYE, 18 + 16 * 18, 222, GlintTableMenu::isRainbowDye, SLOT_MAX));

        addPlayerInventory(inventory, 90, 252);

        // Push the player's stored-design set + printed-trim library to the client when the table opens.
        if (player instanceof ServerPlayer sp) {
            consolidatePrintedLibrary(sp); // drop any leftover ghost that a real trim already covers
            syncStored(sp, sp.getData(ModAttachments.STORED_DESIGNS.get()));
            syncPrinted(sp, sp.getData(ModAttachments.PRINTED_TRIMS.get()));
            // On a dedicated server, push the shared blueprint trims so the client can list them alongside its
            // own personal ones. The integrated (single-player) server skips this: the client's local config
            // scan already covers the very same directory, so syncing would just duplicate every entry.
            if (sp.level().getServer().isDedicatedServer()) ServerBlueprints.syncToOffThread(sp);
            // Re-check the design-collection advancements on open so a player who already owns designs from
            // before this feature existed (or via another path) still earns them.
            checkDesignAdvancements(sp);
        }
    }

    private void addPlayerInventory(Inventory inv, int x, int y) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9, x + col * 18, y + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, x + col * 18, y + 58));
    }

    /** Base design names (the built-in {@link CustomGlint#PATTERNS}), the pool the collection advancements
     *  count against. Data-pack designs don't count toward "base" totals. */
    private static Set<String> baseDesignNames() {
        Set<String> set = new HashSet<>();
        for (ResourceLocation d : CustomGlint.PATTERNS)
            set.add(d.equals(CustomGlint.VANILLA) ? "vanilla" : GlintTrimItem.extractPatternName(d));
        return set;
    }

    /** Fire the design-collection advancements with the player's current count of BASE designs owned. Called
     *  after any change to the stored-design set (and on table open). Advancements are idempotent, so the
     *  extra fires are harmless. */
    public static void checkDesignAdvancements(ServerPlayer sp) {
        Set<String> base = baseDesignNames();
        int collected = 0;
        for (String s : sp.getData(ModAttachments.STORED_DESIGNS.get())) if (base.contains(s)) collected++;
        ModTriggers.DESIGNS_COLLECTED.get().trigger(sp, collected, base.size());
    }

    /** Pushes the player's stored-design set to their own client (the left grid's un-ghosting). */
    private static void syncStored(ServerPlayer sp, List<String> designs) {
        PacketDistributor.sendToPlayer(sp, new GlintStoredSyncPacket(new ArrayList<>(designs)));
    }

    /** Pushes the player's printed-trim library to their own client (the right grid). Empty stacks are left
     *  out; the receiving codec drops them anyway. */
    private static void syncPrinted(ServerPlayer sp, List<ItemStack> trims) {
        List<ItemStack> out = new ArrayList<>(trims.size());
        for (ItemStack s : trims) if (!s.isEmpty()) out.add(s);
        PacketDistributor.sendToPlayer(sp, new GlintPrintedSyncPacket(out));
    }

    /** A "painted" trim, one that has had color applied, lives in the printed library; an empty trim
     *  (a color-less design, or a default Glow Trim with no glow color) goes to the left palette. */
    private static boolean isPainted(ItemStack stack) {
        if (stack.getItem() instanceof GlintTrimItem) {
            return GlintTrimItem.getColors(stack).length > 0 || GlintTrimItem.isGlowing(stack);
        }
        if (stack.getItem() instanceof GlowTrimItem) {
            return GlowTrimItem.getColors(stack).length > 0;
        }
        return false;
    }

    /** Records one finished painted trim into the player's printed library (deduped, capped at
     *  {@link #MAX_PRINTED_TRIMS}) + syncs.
     *  Returns true only if it actually stored: false on a dedup hit or at the cap, so deposit callers can
     *  leave the physical trim in place instead of consuming it for nothing.
     *
     *  Crafting a trim that matches a dimmed IMPORTED entry clears its lock (un-dims it, makes it
     *  withdrawable) instead of appending a duplicate, so importing a trim then building it "pays off" the
     *  import. Matching is by {@link #trimSignature} (glint layers + glow, ignoring seed / custom name). */
    private static boolean storePrinted(ServerPlayer sp, ItemStack trim) {
        List<ItemStack> list = sp.getData(ModAttachments.PRINTED_TRIMS.get());
        String sig = trimSignature(trim);

        // First pass: unlock a matching import if one is dimmed in the library.
        List<ItemStack> cleaned = new ArrayList<>();
        boolean unlocked = false;
        for (ItemStack s : list) {
            if (s.isEmpty()) continue;
            if (!unlocked && isImportLocked(s) && trimSignature(s).equals(sig)) {
                ItemStack u = s.copy();
                u.remove(ModComponents.IMPORT_LOCKED.get());
                cleaned.add(u);
                unlocked = true;
            } else {
                cleaned.add(s);
            }
        }
        if (unlocked) {
            cleaned = consolidateGhosts(cleaned); // the just-unlocked real shadows any other matching ghost
            sp.setData(ModAttachments.PRINTED_TRIMS.get(), cleaned);
            if (!deferLibrarySync) syncPrinted(sp, cleaned);
            return true;
        }

        for (ItemStack s : cleaned) if (ItemStack.isSameItemSameComponents(s, trim)) return false; // already have this config
        if (cleaned.size() >= MAX_PRINTED_TRIMS) return false;
        ItemStack one = trim.copy();
        one.setCount(1);
        cleaned.add(one);
        cleaned = consolidateGhosts(cleaned); // the new real shadows any matching import ghost
        sp.setData(ModAttachments.PRINTED_TRIMS.get(), cleaned);
        if (!deferLibrarySync) syncPrinted(sp, cleaned);
        return true;
    }

    /** True when a printed-library entry is a not-yet-crafted import (dimmed, non-withdrawable). */
    private static boolean isImportLocked(ItemStack stack) {
        return Boolean.TRUE.equals(stack.get(ModComponents.IMPORT_LOCKED.get()));
    }

    /** Real (unlocked) trims always take priority: drop any import-locked ghost whose signature matches a real
     *  entry, so the two never coexist as a duplicate. Returns the same list when nothing changed. */
    private static List<ItemStack> consolidateGhosts(List<ItemStack> list) {
        Set<String> real = new HashSet<>();
        for (ItemStack s : list) if (!s.isEmpty() && !isImportLocked(s)) real.add(trimSignature(s));
        if (real.isEmpty()) return list;
        List<ItemStack> out = new ArrayList<>(list.size());
        boolean changed = false;
        for (ItemStack s : list) {
            if (!s.isEmpty() && isImportLocked(s) && real.contains(trimSignature(s))) { changed = true; continue; }
            out.add(s);
        }
        return changed ? out : list;
    }

    /** Consolidate the player's printed library in place (drop ghosts shadowed by a real trim) and persist it.
     *  Run on open so any pre-existing duplicate is cleaned up; storePrinted does the same after each change. */
    private static void consolidatePrintedLibrary(ServerPlayer sp) {
        List<ItemStack> list = sp.getData(ModAttachments.PRINTED_TRIMS.get());
        List<ItemStack> cleaned = consolidateGhosts(list);
        if (cleaned != list) sp.setData(ModAttachments.PRINTED_TRIMS.get(), cleaned);
    }

    /** A trim's identity for import matching: its glint layers (design + colors + timing/flags, seed
     *  excluded so a rolled chromatic seed doesn't block the match) plus the glow flag and glow colors. The
     *  custom name is intentionally left out, reproducing the glint and glow is enough to "craft" an import. */
    private static String trimSignature(ItemStack stack) {
        StringBuilder sb = new StringBuilder();
        CustomGlint.Data d = CustomGlint.read(stack);
        if (d != null) {
            for (CustomGlint.Layer l : d.layers()) {
                sb.append(l.design()).append('|');
                for (int c : l.colors()) sb.append(Integer.toHexString(c)).append(',');
                // A single-colour layer renders identically whether "simultaneous" or not, and the two build
                // paths set that flag differently for such layers (import normalizes every layer, print only
                // the active one). Canonicalize it to false so the same trim, imported vs printed, hashes the
                // same, otherwise it shows up as a duplicate the print-unlock never consolidates.
                boolean sim = l.simultaneous() && l.colors().length >= 2;
                sb.append(';').append(l.speed()).append(';').append(l.interpolate())
                  .append(';').append(l.patternScale()).append(';').append(sim)
                  .append(';').append(l.scrollDir()).append(';').append(l.scrollOffset()).append('#');
            }
        }
        sb.append("glow=").append(CustomGlint.isGlowing(stack)).append(';');
        for (int c : CustomGlint.getGlowColors(stack)) sb.append(Integer.toHexString(c)).append(',');
        return sb.toString();
    }

    /**
     * Import a premade trim from a config file (sent by the client's Import list): rebuild it, mark every
     * layer's design owned so it can be built, and drop it into the printed library as a LOCKED (dimmed,
     * non-withdrawable) entry. The lock clears only when the player prints a matching trim, so importing
     * hands out a build target, not a free finished trim. No-op if an identical trim is already in the library.
     */
    public void importTrim(CustomGlint.Layer[] layers, boolean glowing, int[] glowColors, String name, int nameColor) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (layers.length == 0) return;
        CustomGlint.Layer[] withSeeds = CustomGlint.ensureChromaticSeeds(layers);
        // A single-color layer is always sequential (simultaneous needs 2+ colors to mean anything, and
        // print() builds it that way), so store the import the same way it will be crafted, otherwise the
        // exact-match unlock would never fire on a 1-color import.
        for (int i = 0; i < withSeeds.length; i++) {
            CustomGlint.Layer l = withSeeds[i];
            if (l.simultaneous() && l.colors().length < 2)
                withSeeds[i] = new CustomGlint.Layer(l.design(), l.colors(), l.speed(), l.interpolate(),
                        l.patternScale(), false, l.scrollDir(), l.scrollOffset(), l.seed());
        }

        ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());
        CustomGlint.Layer l0 = withSeeds[0];
        GlintTrimItem.setPattern(trim, l0.design());
        for (int c : l0.colors()) GlintTrimItem.addColor(trim, c);
        GlintTrimItem.setSpeed(trim, l0.speed());
        GlintTrimItem.setScale(trim, l0.patternScale());
        GlintTrimItem.setScrollDir(trim, l0.scrollDir());
        GlintTrimItem.setScrollOffset(trim, l0.scrollOffset());
        GlintTrimItem.setGlowing(trim, glowing);
        CustomGlint.setGlowing(trim, glowing);
        if (glowColors.length > 0) CustomGlint.setGlowColors(trim, glowColors);
        if (!name.isEmpty()) {
            int rgb = (nameColor >>> 8) & 0xFFFFFF; // client packs the name colour as (rgb << 8) | alpha
            trim.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st -> st.withColor(TextColor.fromRgb(rgb))));
        }
        // Full multi-layer glint Data is authoritative; write() preserves the glow flag / glow colors set above.
        CustomGlint.write(trim, withSeeds);

        // Importing does NOT unlock the trim's designs in the left palette: the import is only a locked build
        // target, and handing over its designs would give them away for free. The player must own the designs
        // through the normal path to build (and thereby unlock) the import.
        storePrintedImport(sp, trim);
    }

    /** Adds an imported trim to the printed library as a locked (dimmed) entry, deduped by signature so a
     *  trim the player already owns or already imported isn't added again. Capped with the library. */
    private static boolean storePrintedImport(ServerPlayer sp, ItemStack trim) {
        List<ItemStack> list = sp.getData(ModAttachments.PRINTED_TRIMS.get());
        String sig = trimSignature(trim);
        List<ItemStack> updated = new ArrayList<>();
        for (ItemStack s : list) {
            if (s.isEmpty()) continue;
            if (trimSignature(s).equals(sig)) return false; // already imported or already owned
            updated.add(s);
        }
        if (updated.size() >= MAX_PRINTED_TRIMS) return false;
        ItemStack one = trim.copy();
        one.setCount(1);
        one.set(ModComponents.IMPORT_LOCKED.get(), true);
        updated.add(one);
        sp.setData(ModAttachments.PRINTED_TRIMS.get(), updated);
        syncPrinted(sp, updated);
        return true;
    }

    /**
     * Print a finished trim: build it from the sent params + the dye/material slots, validate and consume
     * the cost, then give it to the player and store it in the printed library. No-op on validation fail.
     */
    public void print(String designId, float speed, float scale, int opacity,
                      boolean glow, boolean glowAuto, boolean named, String name, boolean simultaneous,
                      int scrollDir, float scrollOffset, boolean interpolate, int nameHex, int[][] shardDyes,
                      CustomGlint.Layer[] belowLayers, CustomGlint.Layer[] aboveLayers,
                      int[][] glowShardDyes) {
        if (!(player instanceof ServerPlayer sp)) return;
        // Opacity is 0..8 glass levels; out of range it would wrap the alpha byte and dodge the glass cost.
        opacity = Math.max(0, Math.min(8, opacity));
        // tryParse (not parse): designId is a raw client-supplied string; a malformed one would otherwise
        // throw ResourceLocationException on the server thread inside enqueueWork.
        ResourceLocation design = ResourceLocation.tryParse(designId);
        if (design == null) return;

        // Extra layers that sit below / above the active layer (decoded + capped by the print packet). They
        // render around the active layer; each extra layer costs one layer tear. Their colors are free.
        int extraLayers = belowLayers.length + aboveLayers.length;
        if (extraLayers > 0) {
            if (container.getItem(SLOT_LAYER_TEAR).getCount() < extraLayers) return;
            for (CustomGlint.Layer l : belowLayers) if (!ownsDesign(sp, l.design())) return;
            for (CustomGlint.Layer l : aboveLayers) if (!ownsDesign(sp, l.design())) return;
        }

        // A filled trim in the main slot is the base: the print keeps its existing colors and the new
        // dyes are appended on top. With an empty main slot the print builds a fresh trim from scratch.
        ItemStack base = container.getItem(SLOT_TRIM);
        boolean fromBase = base.getItem() instanceof GlintTrimItem;

        // Printing from a design selection requires the player to own that design; a physical trim in the
        // slot is always owned (and placing it already stored its design).
        if (!fromBase && !ownsDesign(sp, design)) return;

        // New colors from the *selected* dye slots (with opacity applied), tracking which to consume.
        // Colors already on the base trim are kept for free (it already paid for them); only selected
        // dyes that aren't already on it get appended and cost a dye. A fresh build pulls all from dyes.
        int alpha = Math.round(255f - opacity * (255f - 32f) / 8f);
        int[] baseColors = fromBase ? GlintTrimItem.getColors(base) : new int[0];
        int colorBudget = Math.max(0, CustomGlint.MAX_COLORS_PER_LAYER - baseColors.length);
        List<Integer> newColors = new ArrayList<>();
        // Cumulative dye cost: every colour shard on every layer charges its own dye; a shade reused across
        // shards / layers costs one of that dye each time, no de-duplication. dyeUsed tracks the running
        // per-shade count so each colour validates against what the slot still holds after earlier claims.
        int[] dyeUsed = new int[16];
        int rainbowNeeded = 0; // custom-hex colours, each consuming one rainbow dye
        for (int[] shard : shardDyes) {
            if (shard.length == 0 || newColors.size() >= colorBudget) continue;
            // Custom-hex shard: use the rgb directly, costs one rainbow dye.
            if (shard.length == 1 && (shard[0] & CUSTOM_FLAG) != 0) {
                int rgb = shard[0] & 0xFFFFFF;
                if (container.getItem(SLOT_RAINBOW_DYE).getCount() < rainbowNeeded + 1) continue; // no rainbow dye left
                if (containsRgb(baseColors, rgb)) continue;
                newColors.add((alpha << 24) | rgb);
                rainbowNeeded++;
                continue;
            }
            int rgb = blendShard(shard, dyeUsed);
            if (rgb < 0) continue; // player doesn't have enough of one of the mix's dyes
            if (containsRgb(baseColors, rgb)) continue; // already on the base trim, free, no consume
            newColors.add((alpha << 24) | rgb);
            for (int idx : shard) dyeUsed[idx]++; // charge each component dye, cumulative
        }
        // The committed layers (below/above the active one) also cost dyes, cumulatively: every colour of every
        // committed layer wants its own dye, stacking on top of the active layer's claims (no de-dup). The
        // synthetic white fill an empty layer carries is skipped, it isn't a chosen colour. A non-dye colour
        // takes a rainbow dye instead.
        int committedRainbow = 0;
        for (CustomGlint.Layer[] group : new CustomGlint.Layer[][]{belowLayers, aboveLayers}) {
            for (CustomGlint.Layer l : group) {
                for (int color : l.colors()) {
                    if ((color & 0xFFFFFF) == 0xFFFFFF) continue; // synthetic empty-layer fill, not a real colour
                    int idx = dyeIndexForRgb(color);
                    if (idx < 0) { committedRainbow++; continue; }
                    ItemStack ds = container.getItem(SLOT_DYE_START + idx);
                    if (dyeOf(ds) == null || ds.getCount() < dyeUsed[idx] + 1) return; // required dye missing / not enough
                    dyeUsed[idx]++;
                }
            }
        }
        if (container.getItem(SLOT_RAINBOW_DYE).getCount() < rainbowNeeded + committedRainbow) return;
        rainbowNeeded += committedRainbow;

        // At least one color is required: the placed trim's existing colors, or a newly selected dye.
        if (baseColors.length + newColors.size() == 0) return;
        if (baseColors.length + newColors.size() > CustomGlint.MAX_COLORS_PER_LAYER) return;

        // Flat cost: one material per LAYER (active + every extra layer) that tunes speed/scale off 1× or sets
        // any opacity, so the cost is the total for the whole trim, not just the active layer.
        // One redstone/slime per ± step speed/scale sits off 1×, one glass per opacity level, tallied across the
        // active + every committed layer (mirrors GlintTableScreen.layerCosts). Tuning a trim is a real cost now.
        int redCost = CustomGlint.stepCost(speed);
        int slimeCost = CustomGlint.stepCost(scale);
        int glassCost = opacity;
        for (CustomGlint.Layer l : belowLayers) { redCost += CustomGlint.stepCost(l.speed()); slimeCost += CustomGlint.stepCost(l.patternScale()); glassCost += layerGlass(l); }
        for (CustomGlint.Layer l : aboveLayers) { redCost += CustomGlint.stepCost(l.speed()); slimeCost += CustomGlint.stepCost(l.patternScale()); glassCost += layerGlass(l); }

        // Validate every required material is present before consuming anything.
        if (redCost > 0 && container.getItem(SLOT_REDSTONE).getCount() < redCost) return;
        if (slimeCost > 0 && container.getItem(SLOT_SLIME).getCount() < slimeCost) return;
        if (glassCost > 0 && container.getItem(SLOT_GLASS).getCount() < glassCost) return;
        // A base trim already carries its glow / name / glow-color, so re-printing them is free; only
        // glow/name that wasn't there needs the glowstone / name tag / glow-color dye.
        boolean baseGlowing = fromBase && CustomGlint.isGlowing(base);
        boolean baseHasGlowColors = fromBase && CustomGlint.getGlowColors(base).length > 0;
        boolean baseNamed = fromBase && base.has(DataComponents.CUSTOM_NAME);
        // Glow costs one glowstone per layer of the finished trim (active + every committed layer): an 8-layer
        // glowing trim burns 8 glowstone. Only charged when glow is newly added (a base already glowing is free).
        int glowstoneCost = (glow && !baseGlowing) ? extraLayers + 1 : 0;
        if (glowstoneCost > 0 && container.getItem(SLOT_GLOWSTONE).getCount() < glowstoneCost) return;
        if (named && !baseNamed && container.getItem(SLOT_NAMETAG).isEmpty()) return;
        DyeColor nameDye = dyeOf(container.getItem(SLOT_NAME_DYE));
        // A rainbow dye in the name slot supplies a custom hex colour instead of a dye colour.
        boolean nameRainbow = isRainbowDye(container.getItem(SLOT_NAME_DYE));
        int nameColor = nameRainbow ? nameHex : dyeColor(nameDye);

        // Manual glow colours: built from the glow shards (its own list of up to 8, separate from the glint
        // colours), each charging a dye cumulatively on top of everything above. Auto glow / glow off / a base
        // already glowing contribute none. A shard whose dye isn't present (or short) is skipped.
        List<Integer> glowColors = new ArrayList<>();
        if (glow && !glowAuto) {
            GlowShards resolved = resolveGlowShards(glowShardDyes, dyeUsed, rainbowNeeded);
            glowColors = resolved.colors();
            rainbowNeeded = resolved.rainbowUsed();
            if (glowColors.isEmpty() && !baseHasGlowColors) return; // manual glow needs at least one colour
        }

        // Build the trim: start from the placed trim (keeping its colors/layers) or a blank one, then
        // append the newly selected dye colors (addColor caps the total at 8).
        ItemStack trim;
        if (fromBase) {
            trim = base.copy();
            trim.setCount(1);
        } else {
            trim = new ItemStack(ModItems.GLINT_TRIM.get());
        }
        for (int c : newColors) GlintTrimItem.addColor(trim, c);
        GlintTrimItem.setSpeed(trim, speed);
        GlintTrimItem.setScale(trim, scale);
        GlintTrimItem.setScrollDir(trim, scrollDir);
        GlintTrimItem.setScrollOffset(trim, scrollOffset);
        GlintTrimItem.setPattern(trim, design);
        // Mode tears: every multi-colour layer costs one tear matching how its colours animate: a simultaneous
        // tear for a simultaneous layer, a sequential tear for a sequential one. A single-colour layer renders
        // the same either way, so it costs no tear. The active layer keeps its chosen mode, so the print BLOCKS
        // on a missing tear instead of quietly printing the other mode.
        int committedSim = 0, committedSeq = 0;
        for (CustomGlint.Layer[] group : new CustomGlint.Layer[][]{belowLayers, aboveLayers}) {
            for (CustomGlint.Layer l : group) {
                if (l.colors().length < 2) continue;
                if (l.simultaneous()) committedSim++; else committedSeq++;
            }
        }
        boolean activeMulti = GlintTrimItem.getColors(trim).length >= 2;
        int simTearsUsed = committedSim + (simultaneous && activeMulti ? 1 : 0);
        int seqTearsUsed = committedSeq + (!simultaneous && activeMulti ? 1 : 0);
        if (container.getItem(SLOT_TEAR).getCount() < simTearsUsed) return;     // not enough simultaneous tears
        if (container.getItem(SLOT_TEAR_SEQ).getCount() < seqTearsUsed) return; // not enough sequential tears
        GlintTrimItem.setGlowing(trim, glow);
        CustomGlint.setGlowing(trim, glow);
        if (glow && !glowAuto && !glowColors.isEmpty())
            CustomGlint.setGlowColors(trim, glowColors.stream().mapToInt(Integer::intValue).toArray());
        if (named && !name.isEmpty()) {
            int rgb = nameColor >= 0 ? nameColor : 0xFFFFFF;
            trim.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st -> st.withColor(TextColor.fromRgb(rgb))));
        }
        // Final active-layer Data write carrying the chosen interpolation (and simultaneous when a tear is
        // present). Write it LAST, setGlowing/setPattern reset interpolate/simultaneous, so an earlier
        // write would be clobbered. (write() preserves glowing/glowColors.)
        int seed = CustomGlint.isChromatic(design) ? CustomGlint.randomChromaticSeed() : 0;
        CustomGlint.write(trim, design, GlintTrimItem.getColors(trim), speed, interpolate, scale,
                simultaneous && activeMulti,
                GlintTrimItem.getScrollDir(trim), GlintTrimItem.getScrollOffset(trim), seed);
        if (seed != 0) GlintTrimItem.setSeed(trim, seed);

        // Splice the extra layers around the freshly built active layer (below + active + above) and write
        // the multi-layer Data. write() preserves the glow flags; the custom name is a separate component.
        if (extraLayers > 0) {
            CustomGlint.Data activeData = CustomGlint.read(trim);
            List<CustomGlint.Layer> all = new ArrayList<>();
            Collections.addAll(all, belowLayers);
            if (activeData != null) Collections.addAll(all, activeData.layers());
            Collections.addAll(all, aboveLayers);
            CustomGlint.write(trim, all.toArray(new CustomGlint.Layer[0]));
        }

        // Consume the cost.
        if (redCost > 0) container.removeItem(SLOT_REDSTONE, redCost);
        if (slimeCost > 0) container.removeItem(SLOT_SLIME, slimeCost);
        if (glassCost > 0) container.removeItem(SLOT_GLASS, glassCost);
        for (int i = 0; i < 16; i++) if (dyeUsed[i] > 0) container.removeItem(SLOT_DYE_START + i, dyeUsed[i]);
        if (rainbowNeeded > 0) container.removeItem(SLOT_RAINBOW_DYE, rainbowNeeded); // one per custom-hex colour
        if (glowstoneCost > 0) container.removeItem(SLOT_GLOWSTONE, glowstoneCost);
        // The name tag is a gate, not an ingredient: having one in the slot enables custom names, but it
        // is never consumed.
        if (simTearsUsed > 0) container.removeItem(SLOT_TEAR, simTearsUsed);
        if (seqTearsUsed > 0) container.removeItem(SLOT_TEAR_SEQ, seqTearsUsed);
        // The name dye supplies the custom-name colour; consume it only when a name was actually written.
        if (named && !name.isEmpty() && (nameDye != null || nameRainbow)) container.removeItem(SLOT_NAME_DYE, 1);
        if (extraLayers > 0) container.removeItem(SLOT_LAYER_TEAR, extraLayers); // one tear per extra layer

        // Output: give to the player (drop overflow) and store in the printed library. Give a copy: addItem
        // empties the stack it is handed, and trim is read below for the library and the advancements.
        ItemStack given = trim.copy();
        if (!sp.addItem(given)) sp.drop(given, false);
        storePrinted(sp, trim);

        // A trim printed with the full 8 colors earns "Ratatouille"; a layered trim earns "Like Ogres", the
        // full 8 layers earns "How many cheeses?", and 8 layers each with all 8 colors earns "In this Economy?".
        if (GlintTrimItem.getColors(trim).length >= CustomGlint.MAX_COLORS_PER_LAYER) ModTriggers.EIGHT_COLOR_TRIM.get().trigger(sp);
        CustomGlint.Data printed = CustomGlint.read(trim);
        int layers = printed != null ? printed.layers().length : 0;
        if (layers >= 2) ModTriggers.LAYERED_TRIM.get().trigger(sp);
        if (layers >= 8) ModTriggers.EIGHT_LAYER_TRIM.get().trigger(sp);
        if (EightByEightTrimTrigger.matches(printed)) ModTriggers.EIGHT_BY_EIGHT_TRIM.get().trigger(sp);
    }

    /**
     * Print a Glow Trim: a glow-only trim carries no glint design, layers, speed/scale or opacity, just its
     * glow colours. The colours come from the selected dye shards (one dye per shade, one rainbow dye per
     * custom colour, exactly like the glint print, cumulative with no de-dup), the dyes are consumed, and the
     * finished Glow Trim is given to the player + stored in the printed library. No-op on validation fail.
     */
    public void printGlow(int[][] shardDyes, float speed, boolean interpolate, boolean named, String name, int nameHex) {
        if (!(player instanceof ServerPlayer sp)) return;
        float safeSpeed = Float.isFinite(speed) ? Math.max(0.10f, Math.min(8.0f, speed)) : 1.0f;
        int redCost = CustomGlint.stepCost(safeSpeed); // glow-cycle speed off 1× costs redstone, like a glint layer
        if (redCost > 0 && container.getItem(SLOT_REDSTONE).getCount() < redCost) return;

        // Resolve the glow colours from the selected shards (opaque, glow has no opacity dimension), tracking
        // which dyes to consume. A shard whose dye isn't present (or short) is skipped; a custom-hex shard
        // costs a rainbow dye. Cumulative: each shard charges its own dye, no de-duplication.
        int[] dyeUsed = new int[16];
        GlowShards resolved = resolveGlowShards(shardDyes, dyeUsed, 0);
        List<Integer> colors = resolved.colors();
        int rainbowNeeded = resolved.rainbowUsed();
        if (colors.isEmpty()) return; // a glow trim needs at least one colour

        // Optional custom name (name tag is a gate, not consumed; the name dye supplies the colour).
        DyeColor nameDye = dyeOf(container.getItem(SLOT_NAME_DYE));
        boolean nameRainbow = isRainbowDye(container.getItem(SLOT_NAME_DYE));
        int nameColor = nameRainbow ? nameHex : dyeColor(nameDye);
        if (named && !name.isEmpty() && container.getItem(SLOT_NAMETAG).isEmpty()) return;

        ItemStack trim = new ItemStack(ModItems.GLOW_TRIM.get());
        for (int c : colors) GlowTrimItem.addColor(trim, c); // stores under the colors tag + writes the glow preview
        CustomGlint.setGlowing(trim, true);
        CustomGlint.setGlowAnim(trim, safeSpeed, interpolate); // speed + interpolation drive the glow cycle
        if (named && !name.isEmpty()) {
            int rgb = nameColor >= 0 ? nameColor : 0xFFFFFF;
            trim.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st -> st.withColor(TextColor.fromRgb(rgb))));
        }

        // Consume the cost.
        if (redCost > 0) container.removeItem(SLOT_REDSTONE, redCost);
        for (int i = 0; i < 16; i++) if (dyeUsed[i] > 0) container.removeItem(SLOT_DYE_START + i, dyeUsed[i]);
        if (rainbowNeeded > 0) container.removeItem(SLOT_RAINBOW_DYE, rainbowNeeded);
        if (named && !name.isEmpty() && (nameDye != null || nameRainbow)) container.removeItem(SLOT_NAME_DYE, 1);

        ItemStack given = trim.copy(); // addItem empties the stack it is handed, and trim is stored below
        if (!sp.addItem(given)) sp.drop(given, false);
        storePrinted(sp, trim);
    }

    /** Storage-library name for a trim stack (a Glint design name, or the Glow Trim sentinel). Null when the
     *  stack carries no design, which callers use as "this isn't depositable". */
    @Nullable
    private static String designName(ItemStack stack) {
        if (stack.getItem() instanceof GlowTrimItem) return GlowTrimItem.STORAGE_KEY;
        ResourceLocation pattern = GlintTrimItem.getPattern(stack);
        if (pattern == null) return null;
        return pattern.equals(CustomGlint.VANILLA) ? "vanilla" : GlintTrimItem.extractPatternName(pattern);
    }

    /**
     * The first time a trim sits in the main or merge slot, "store" its design on the player (one-way;
     * withdrawing the trim does not un-store it). Server-side only; pushes the updated set to the client.
     */
    @Override
    public void slotsChanged(Container changed) {
        super.slotsChanged(changed);
        if (!(player instanceof ServerPlayer sp)) return;

        // NOTE: the attachment's list is immutable once decoded from a save (Codec.listOf), so copy
        // before mutating, mutating it in place throws for any player whose data was reloaded.
        List<String> stored = sp.getData(ModAttachments.STORED_DESIGNS.get());
        List<String> updated = null;
        for (int idx : new int[]{SLOT_TRIM, SLOT_TRIM_B}) {
            ItemStack s = container.getItem(idx);
            if (!isAnyTrim(s)) continue;
            String name = designName(s);
            if (name == null || stored.contains(name) || (updated != null && updated.contains(name))) continue;
            if ((updated != null ? updated.size() : stored.size()) >= MAX_STORED_DESIGNS) continue;
            if (updated == null) updated = new ArrayList<>(stored);
            updated.add(name);
        }
        if (updated != null) {
            sp.setData(ModAttachments.STORED_DESIGNS.get(), updated);
            syncStored(sp, updated);
            checkDesignAdvancements(sp);
        }
    }

    /** Teach every design a trim carries (all glint layers, or the Glow Trim key) so a trim deposited into the
     *  library stays fully re-printable from its ghost. Each store is idempotent + deduped. */
    private static void storeTrimDesigns(ServerPlayer sp, ItemStack trim) {
        if (trim.getItem() instanceof GlowTrimItem) { storeDesign(sp, GlowTrimItem.STORAGE_KEY); return; }
        CustomGlint.Data d = CustomGlint.read(trim);
        if (d != null) {
            for (CustomGlint.Layer l : d.layers()) {
                String dn = l.design().equals(CustomGlint.VANILLA) ? "vanilla" : GlintTrimItem.extractPatternName(l.design());
                if (dn != null) storeDesign(sp, dn);
            }
        }
        String base = designName(trim); // fallback for a trim with no glint Data yet
        if (base != null) storeDesign(sp, base);
    }

    /** Records a single design into the player's storage library (the scrollable grid) and syncs it. */
    private static boolean storeDesign(ServerPlayer sp, String name) {
        List<String> stored = sp.getData(ModAttachments.STORED_DESIGNS.get());
        if (stored.contains(name) || stored.size() >= MAX_STORED_DESIGNS) return false;
        // Copy before mutating, the decoded attachment list is immutable (see slotsChanged).
        List<String> updated = new ArrayList<>(stored);
        updated.add(name);
        sp.setData(ModAttachments.STORED_DESIGNS.get(), updated);
        if (!deferLibrarySync) syncStored(sp, updated);
        checkDesignAdvancements(sp);
        return true;
    }

    /** Equal-weight blend of one shard's component dyes into an RGB (no alpha), matching what the client
     *  previews. Returns -1 when a component index is out of range or its slot no longer holds enough after
     *  the claims already tallied in {@code dyeUsed}. Callers skip empty shards, so the divide is safe. */
    private int blendShard(int[] shard, int[] dyeUsed) {
        int r = 0, g = 0, b = 0;
        for (int idx : shard) {
            ItemStack ds = idx >= 0 && idx < 16 ? container.getItem(SLOT_DYE_START + idx) : ItemStack.EMPTY;
            if (idx < 0 || idx >= 16 || dyeOf(ds) == null || ds.getCount() < dyeUsed[idx] + 1) return -1;
            int rgb = GlintTrimItem.DYE_COLORS[idx] & 0xFFFFFF; // mod palette (matches the tooltip names + recipes)
            r += (rgb >> 16) & 0xFF; g += (rgb >> 8) & 0xFF; b += rgb & 0xFF;
        }
        int n = shard.length;
        return ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    /** Glow colours resolved from dye shards, plus the running rainbow-dye total they left behind. */
    private record GlowShards(List<Integer> colors, int rainbowUsed) {}

    /** Resolve glow-colour shards into opaque colours, charging each component dye into {@code dyeUsed}
     *  cumulatively. A shard whose dye is missing or short is skipped; a custom-hex shard takes a rainbow dye
     *  instead. {@code rainbowUsed} carries in the rainbow dyes already claimed by the same print, so the slot
     *  is always checked against the running total. Shared by the glint print and the Glow Trim print. */
    private GlowShards resolveGlowShards(int[][] shardDyes, int[] dyeUsed, int rainbowUsed) {
        List<Integer> colors = new ArrayList<>();
        for (int[] shard : shardDyes) {
            if (shard.length == 0 || colors.size() >= CustomGlint.MAX_COLORS_PER_LAYER) continue;
            if (shard.length == 1 && (shard[0] & CUSTOM_FLAG) != 0) {
                if (container.getItem(SLOT_RAINBOW_DYE).getCount() < rainbowUsed + 1) continue;
                colors.add(0xFF000000 | (shard[0] & 0xFFFFFF));
                rainbowUsed++;
                continue;
            }
            int rgb = blendShard(shard, dyeUsed);
            if (rgb < 0) continue;
            colors.add(0xFF000000 | rgb);
            for (int idx : shard) dyeUsed[idx]++; // charge each component dye, cumulative
        }
        return new GlowShards(colors, rainbowUsed);
    }

    /** The {@link DyeColor} an item dyes with (vanilla {@link DyeItem}), or null if it isn't a dye. */
    @Nullable
    private static DyeColor dyeOf(ItemStack stack) {
        return stack.getItem() instanceof DyeItem di ? di.getDyeColor() : null;
    }

    /** Mod-palette RGB (no alpha) for a dye, or -1 for none. DyeColor is a non-extensible vanilla enum with
     *  exactly 16 constants and {@link GlintTrimItem#DYE_COLORS} has 16 entries, so the index is safe today;
     *  the explicit bound just keeps a future palette/enum drift from throwing on a client-supplied dye. */
    private static int dyeColor(DyeColor dye) {
        if (dye == null) return -1;
        int idx = dye.ordinal();
        if (idx < 0 || idx >= GlintTrimItem.DYE_COLORS.length) return -1;
        return GlintTrimItem.DYE_COLORS[idx] & 0xFFFFFF;
    }

    /** RGB (lower 24 bits) → the vanilla dye index whose mod-palette colour matches, or -1 if it isn't a dye
     *  shade (a mix / custom colour, which costs a rainbow dye instead). */
    private static int dyeIndexForRgb(int color) {
        int rgb = color & 0xFFFFFF;
        for (int i = 0; i < GlintTrimItem.DYE_COLORS.length; i++)
            if ((GlintTrimItem.DYE_COLORS[i] & 0xFFFFFF) == rgb) return i;
        return -1;
    }

    private boolean isServer() {
        return !player.level().isClientSide();
    }

    /** Right-click on a dye slot is reserved for the screen's color-selection gesture, so block its
     *  pickup/place at the menu level (runs on both sides), the screen's click-swallow alone misses
     *  release/drag edge cases under fast spam. Left-click and shift still behave normally. */
    @Override
    public void clicked(int slotId, int button, ClickType input, Player clicker) {
        if (input == ClickType.PICKUP && button == 1
                && slotId >= SLOT_DYE_START && slotId < SLOT_DYE_START + 16) {
            return;
        }
        super.clicked(slotId, button, input, clicker);
    }

    /** True if {@code rgb} (lower 24 bits) matches the RGB of any color in {@code colors}. */
    private static boolean containsRgb(int[] colors, int rgb) {
        for (int c : colors) if ((c & 0xFFFFFF) == rgb) return true;
        return false;
    }

    /** Whether the player has stored (un-dimmed) the given design, the print-ownership gate. */
    private static boolean ownsDesign(ServerPlayer sp, ResourceLocation design) {
        String dn = design.equals(CustomGlint.VANILLA) ? "vanilla" : GlintTrimItem.extractPatternName(design);
        return sp.getData(ModAttachments.STORED_DESIGNS.get()).contains(dn);
    }

    /** A committed layer's glass cost, from the alpha baked into its first colour (mirrors the client). */
    private static int layerGlass(CustomGlint.Layer l) { int[] c = l.colors(); return c.length > 0 ? CustomGlint.glassCost((c[0] >>> 24) & 0xFF) : 0; }

    private static boolean isAnyTrim(ItemStack stack) {
        return stack.getItem() instanceof GlintTrimItem || stack.getItem() instanceof GlowTrimItem;
    }

    /** The simultaneous-mode tear ({@link #SLOT_TEAR}). */
    private static boolean isSimTear(ItemStack stack) {
        return stack.getItem() == ModItems.GLINT_TEAR_SIMULTANEOUS.get();
    }

    /** The sequential-mode tear ({@link #SLOT_TEAR_SEQ}). */
    private static boolean isSeqTear(ItemStack stack) {
        return stack.getItem() == ModItems.GLINT_TEAR_SEQUENTIAL.get();
    }

    private static boolean isRainbowDye(ItemStack stack) {
        return stack.getItem() == ModItems.RAINBOW_DYE.get();
    }

    private static boolean isLayerTear(ItemStack stack) {
        return stack.getItem() instanceof GlintLayerTearItem;
    }

    /** Table slots a shift-clicked stack may flow into, in priority order, or empty if none accept it. */
    private static int[] candidateSlots(ItemStack stack) {
        if (isAnyTrim(stack))               return new int[]{SLOT_TRIM, SLOT_TRIM_B};
        if (isLayerTear(stack))             return new int[]{SLOT_LAYER_TEAR};
        if (isSimTear(stack))               return new int[]{SLOT_TEAR};
        if (isSeqTear(stack))               return new int[]{SLOT_TEAR_SEQ};
        if (stack.is(Items.SLIME_BALL))     return new int[]{SLOT_SLIME};
        if (stack.is(Items.REDSTONE))       return new int[]{SLOT_REDSTONE};
        if (stack.is(Items.GLASS))          return new int[]{SLOT_GLASS};
        if (stack.is(Items.GLOWSTONE_DUST)) return new int[]{SLOT_GLOWSTONE};
        if (stack.is(Items.NAME_TAG))       return new int[]{SLOT_NAMETAG};
        if (isRainbowDye(stack))            return new int[]{SLOT_RAINBOW_DYE};
        DyeColor dye = dyeOf(stack);
        if (dye != null)                    return new int[]{SLOT_DYE_START + dye.getId()};
        return new int[0];
    }

    /** Drag-in deposit (server): the cursor-held trim is dropped onto a scrollable grid. Deposits one into
     *  the library, a painted trim into the printed library, an empty design into the palette, and consumes
     *  it from the cursor. The grids aren't real slots, so this is the click-drag equivalent of shift-click. */
    public void depositCarried() {
        if (!(player instanceof ServerPlayer sp)) return;
        ItemStack carried = getCarried();
        if (carried.isEmpty() || !isAnyTrim(carried)) return;
        boolean stored;
        if (isPainted(carried)) {
            stored = storePrinted(sp, carried);
            // Placing/depositing a real trim also teaches its designs, so the ghost it leaves behind stays fully
            // re-printable (a painted trim's design is owned via the printed library, matching a printed/imported
            // one). This replaces the design-store that physical placement used to get from slotsChanged.
            storeTrimDesigns(sp, carried);
        } else if (designName(carried) != null) {
            stored = storeDesign(sp, designName(carried));
        } else return;
        if (!stored) return; // already in the library (or library full): keep the trim, don't consume it
        carried.shrink(1);
        setCarried(carried);
    }

    /**
     * Bulk-deposit a Glint Bag's contents into the player's libraries (shift-right-click the table with a bag).
     * Empty trims register their design into the stored-design palette; painted trims go into the printed
     * library. Only the first of each new design/config is consumed from the bag; once it's registered, the
     * remaining duplicates stay in the bag (there's nothing left to learn from them).
     */
    public static void depositBagContents(ServerPlayer sp, ItemStack bag) {
        IItemHandler handler = bag.getCapability(Capabilities.ItemHandler.ITEM);
        if (handler == null) return;

        // A bag can carry dozens of trims; syncing on every store would burst a packet per deposit. Defer the
        // library sync through the loop, then push the stored/printed state once at the end.
        int storedBefore = sp.getData(ModAttachments.STORED_DESIGNS.get()).size();
        boolean printedChanged = false;
        deferLibrarySync = true;
        try {
            for (int i = 0; i < handler.getSlots(); i++) {
                if (!isAnyTrim(handler.getStackInSlot(i))) continue;
                // Deposit one at a time; each newly-stored copy is consumed, and the moment a copy is a
                // duplicate (or the library is full) we stop on this slot, leaving the rest in the bag.
                while (true) {
                    ItemStack s = handler.getStackInSlot(i);
                    if (s.isEmpty()) break;
                    boolean stored;
                    if (isPainted(s)) {
                        stored = storePrinted(sp, s);
                        storeTrimDesigns(sp, s);
                        if (stored) printedChanged = true;
                    } else if (designName(s) != null) {
                        stored = storeDesign(sp, designName(s));
                    } else break;
                    if (!stored) break;
                    handler.extractItem(i, 1, false);
                }
            }
        } finally {
            deferLibrarySync = false;
        }

        List<String> storedNow = sp.getData(ModAttachments.STORED_DESIGNS.get());
        if (storedNow.size() != storedBefore) {
            syncStored(sp, storedNow);
            checkDesignAdvancements(sp);
        }
        if (printedChanged) syncPrinted(sp, sp.getData(ModAttachments.PRINTED_TRIMS.get()));

        depositBagMaterials(sp, handler);
    }

    /**
     * Move the bag's table materials (dyes, redstone, slime, glass, glowstone, name tag, tears, rainbow dye)
     * into the player's Glint Table slots, so shift-right-clicking the table also stocks it. Trims are handled
     * by the library deposit above and skipped here. Each material fills its own slot up to the slot's cap; the
     * remainder stays in the bag.
     */
    private static void depositBagMaterials(ServerPlayer sp, IItemHandler handler) {
        SimpleContainer table = new SimpleContainer(TABLE_SIZE);
        sp.getData(ModAttachments.GLINT_TABLE_CONTENTS.get()).copyInto(table.getItems());
        boolean changed = false;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (s.isEmpty() || isAnyTrim(s)) continue; // trims go to the library, not the build slots
            int[] targets = candidateSlots(s);
            if (targets.length == 0) continue; // not a table material (e.g. a Black Tear)
            int slot = targets[0];
            int cap = slot == SLOT_NAMETAG ? 1 : SLOT_MAX;
            ItemStack cur = table.getItem(slot);
            if (!cur.isEmpty() && !ItemStack.isSameItemSameComponents(cur, s)) continue; // occupied by something else
            int space = cap - cur.getCount();
            if (space <= 0) continue;
            ItemStack pulled = handler.extractItem(i, Math.min(space, s.getCount()), false);
            if (pulled.isEmpty()) continue;
            if (cur.isEmpty()) {
                table.setItem(slot, pulled);
            } else {
                cur.grow(pulled.getCount());
                table.setItem(slot, cur);
            }
            changed = true;
        }
        if (changed) sp.setData(ModAttachments.GLINT_TABLE_CONTENTS.get(), ItemContainerContents.fromItems(table.getItems()));
    }

    /** Withdraw (server): shift-click a trim in the printed library pulls one copy into the player's
     *  inventory and removes it from the library. If the inventory is full, the trim stays put (no-op). */
    public void withdrawPrinted(int index) {
        if (!(player instanceof ServerPlayer sp)) return;
        List<ItemStack> list = sp.getData(ModAttachments.PRINTED_TRIMS.get());
        if (index < 0 || index >= list.size()) return;
        ItemStack trim = list.get(index);
        if (trim.isEmpty()) return;
        if (isImportLocked(trim)) return; // an imported trim stays in the library until it's actually crafted
        ItemStack one = trim.copy();
        one.setCount(1);
        if (!sp.addItem(one)) return; // inventory full, leave it in the library
        removePrintedAt(sp, list, index);
    }

    /** Drops the entry at {@code index} from the printed library, persists the result and re-syncs it. */
    private static void removePrintedAt(ServerPlayer sp, List<ItemStack> list, int index) {
        List<ItemStack> updated = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (i == index) continue;
            ItemStack s = list.get(i);
            if (!s.isEmpty()) updated.add(s);
        }
        sp.setData(ModAttachments.PRINTED_TRIMS.get(), updated);
        syncPrinted(sp, updated);
    }

    /** Delete (server): shift-click a still-locked imported trim in the printed library removes it outright.
     *  Only import-locked (un-crafted) entries can be deleted this way; a real printed trim is withdrawn. */
    public void deletePrinted(int index) {
        if (!(player instanceof ServerPlayer sp)) return;
        List<ItemStack> list = sp.getData(ModAttachments.PRINTED_TRIMS.get());
        if (index < 0 || index >= list.size()) return;
        ItemStack trim = list.get(index);
        if (trim.isEmpty() || !isImportLocked(trim)) return; // only imported, un-crafted entries are deletable
        removePrintedAt(sp, list, index);
    }

    /** Delete (server): an op removes one of the server's shared blueprint trims. Requires op permission on a
     *  dedicated server; deletes the matching config file and re-syncs the shared list to the player. */
    public void deleteServerBlueprint(ServerPlayer sp, String name) {
        if (!sp.level().getServer().isDedicatedServer()) return; // single-player uses the client store
        if (!sp.hasPermissions(2)) return; // ops only (level 2)
        if (!ServerBlueprints.safeName(name)) return; // a crafted packet must not escape the trims dir
        ServerBlueprints.delete(name);
        ServerBlueprints.syncTo(sp);
    }

    /** Give the player a free blank trim of a palette design (shift-click in the left grid). No-op if the
     *  inventory is full. The colors are empty, it's just the design template. */
    public void giveDesignCopy(String name) {
        if (!(player instanceof ServerPlayer sp)) return;
        ItemStack stack;
        if (GlowTrimItem.STORAGE_KEY.equals(name)) {
            stack = new ItemStack(ModItems.GLOW_TRIM.get());
        } else {
            stack = new ItemStack(ModItems.GLINT_TRIM.get());
            // designFromName uses tryParse, so the client-supplied name arriving from GlintGiveDesignPacket
            // falls back to vanilla instead of throwing on the server thread.
            GlintTrimItem.setPattern(stack, CustomGlint.designFromName(name));
        }
        sp.addItem(stack); // drops nothing if full, the copy is free, so no overflow handling needed
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < TABLE_SIZE) {
            // Table slot → player inventory
            if (!moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
        } else if (isAnyTrim(stack) && (isPainted(stack) || designName(stack) != null)) {
            // Player inventory → library: a painted (colored/glow) trim deposits its full config into
            // the right "printed" library; an empty (colorless) trim deposits its design into the left
            // palette. Either way one trim is consumed; this bypasses the build slots.
            // Consume the physical trim ONLY if the bank actually recorded it; a dedup hit or a full library
            // leaves the trim in the player's inventory instead of destroying it.
            boolean stored = true;
            if (player instanceof ServerPlayer sp) {
                if (isPainted(stack)) { stored = storePrinted(sp, stack); storeTrimDesigns(sp, stack); }
                else stored = storeDesign(sp, designName(stack));
            }
            if (!stored) return ItemStack.EMPTY; // leave the trim, no consumption
            stack.shrink(1);
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
            return ItemStack.EMPTY; // returning EMPTY stops the quick-move loop after one trim
        } else {
            // Player inventory → matching table slot(s)
            int[] candidates = candidateSlots(stack);
            if (candidates.length == 0) return ItemStack.EMPTY;
            boolean moved = false;
            for (int c : candidates) {
                if (moveItemStackTo(stack, c, c + 1, false)) {
                    moved = true;
                    if (stack.isEmpty()) break;
                }
            }
            if (!moved) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();

        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.GLINT_TABLE_BLOCK.get());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // Contents are stored per player and saved on every change, so closing the screen keeps them as-is
        // (they are not ejected back to the player).
    }

    /** Dye-only slot that is hidden/shown by a client toggle (via {@link Slot#isActive()}). */
    private static final class ToggleDyeSlot extends Slot {
        private final BooleanSupplier active;

        ToggleDyeSlot(Container container, int index, int x, int y, BooleanSupplier active) {
            super(container, index, x, y);
            this.active = active;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return dyeOf(stack) != null || isRainbowDye(stack); // any dye, plus the rainbow dye
        }

        @Override
        public int getMaxStackSize() {
            return SLOT_MAX;
        }

        @Override
        public boolean isActive() {
            return active.getAsBoolean();
        }
    }

    /** Slot that only accepts items matching a predicate and caps its own stack size. */
    private static final class FilteredSlot extends Slot {
        private final Predicate<ItemStack> filter;
        private final int max;

        FilteredSlot(Container container, int index, int x, int y, Predicate<ItemStack> filter, int max) {
            super(container, index, x, y);
            this.filter = filter;
            this.max = max;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return filter.test(stack);
        }

        @Override
        public int getMaxStackSize() {
            return max;
        }
    }
}
