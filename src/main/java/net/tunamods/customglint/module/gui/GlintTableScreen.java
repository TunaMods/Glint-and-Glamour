package net.tunamods.customglint.module.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.client.ClientInput;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.ModConfigPaths;
import net.tunamods.customglint.common.client.GlintClientConfig;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.item.ModComponents;
import net.tunamods.customglint.module.client.GlintTableModelClient;
import net.tunamods.customglint.module.menu.GlintTableMenu;
import net.tunamods.customglint.module.network.GlintDeletePrintedPacket;
import net.tunamods.customglint.module.network.GlintDeleteServerBlueprintPacket;
import net.tunamods.customglint.module.network.GlintDepositPacket;
import net.tunamods.customglint.module.network.GlintGiveDesignPacket;
import net.tunamods.customglint.module.network.GlintImportPacket;
import net.tunamods.customglint.module.network.GlintPrintPacket;
import net.tunamods.customglint.module.network.GlintServerBlueprintsSyncPacket;
import net.tunamods.customglint.module.network.GlintPrintedSyncPacket;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;
import net.tunamods.customglint.module.network.GlintWithdrawPacket;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The Glint Table editor screen (client-only). Everything the player builds lives in transient fields here
 * and is sent to the server as one print packet; the block entity only owns the physical slots.
 *
 * <p>Conventions used throughout:
 * <ul>
 *   <li>Layout constants are window-relative pixels. Add {@code leftPos} / {@code topPos} to reach screen
 *       coordinates. Every hit test must use the same constants the matching draw uses.</li>
 *   <li>{@code mod*} fields are the build state of the ACTIVE layer; committed layers live in
 *       {@code lowerLayers} / {@code upperLayers}.</li>
 *   <li>{@code extract*} methods are vanilla render hooks; {@code draw*} methods are this screen's own
 *       painting, called from {@link #extractContents}.</li>
 * </ul>
 */
public class GlintTableScreen extends AbstractContainerScreen<GlintTableMenu> {

    // ── Layout (mockup): two scrollable grids flank a center column ───────────
    // 1 left grid = empty/blank design palette; 2 right grid = printed (colored/glow) trims.
    private static final int GRID_COLS = 6, GRID_ROWS = 7, CELL = 18;
    private static final int LGRID_X = 8, RGRID_X = 221, GRID_Y = 22;

    // Center column boxes. The preview is sized to fit an integer-zoomed (5×) 80px glint icon snugly, an
    // 82px box (≈1px inside the bevel). Bottom stays aligned to the grids' recess bottom (150); only the top
    // moves down. A non-integer zoom would sub-pixel-seam the trim's edge/body layers against the glow halo.
    private static final int PREVIEW_X = 129, PREVIEW_Y = 68, PREVIEW_W = 82, PREVIEW_H = 82;   // 3
    private static final int PRINT_X = 128, PRINT_Y = 165, PRINT_W = 84, PRINT_H = 14;          // 7 (centered under the preview/color strip)

    // Color-shard strip: a row of up to 8 swatches for the active layer's colors, mirroring the layer strip
    // but BELOW the preview (tops flush with the preview's bottom edge). Same x/cell metrics as the layer strip.
    private static final int COLOR_STRIP_X = 122, COLOR_CELL = 12, COLOR_ICON = 12;
    private static final int COLOR_STRIP_Y = PREVIEW_Y + PREVIEW_H; // 150, top flush with preview bottom

    // ── Active skin palette ───────────────────────────────────────────────────
    // The selected skin's colors, copied here by applySkin(). Layout is identical across skins; only these
    // values differ. The names match the original constants so all the draw code below reads unchanged.
    private GlintTableSkin skin = GlintTableSkin.DEFAULT;
    private int DIM_GHOST, DIM_PREVIEW; // dim overlays (inactive tear / dye-hint, ghost-item preview)
    private int RING_MAIN, RING_DONOR;                       // selection rings (main / merge-donor)
    private int GUI_FACE, GUI_SHADOW; // panel / bevel palette
    private int SLOT_DARK;                                   // slot-well / shard bevel shadow
    private int LABEL_HDR, COST_OK, COST_BAD;                // text + status colors
    private int COLOR_UNSET, BTN_DISABLED;                   // unset shard / disabled button face
    private int HOVER_TINT, BTN_HOVER;                       // hover tint, button hover face

    /** Copy a skin's palette into the same-named fields the draw code reads. */
    private void applySkin(GlintTableSkin s) {
        skin = s;
        GUI_FACE = s.guiFace; GUI_SHADOW = s.guiShadow;
        SLOT_DARK = s.slotDark;
        LABEL_HDR = s.labelHdr; COST_OK = s.costOk; COST_BAD = s.costBad;
        RING_MAIN = s.ringMain; RING_DONOR = s.ringDonor;
        DIM_GHOST = s.dimGhost; DIM_PREVIEW = s.dimPreview;
        COLOR_UNSET = s.colorUnset; BTN_DISABLED = s.btnDisabled;
        HOVER_TINT = s.hoverTint; BTN_HOVER = s.btnHover;
    }

    /** Step the skin by {@code dir} (+1 next / -1 previous, wrapping) and persist the choice. */
    private void cycleSkin(int dir) {
        int n = GlintTableSkin.ALL.length;
        int idx = Math.floorMod(GlintTableSkin.indexOf(skin) + dir, n);
        applySkin(GlintTableSkin.ALL[idx]);
        GlintClientConfig.setGlintTableSkin(idx);
        // The placed block follows the GUI skin, re-mesh loaded chunks so it updates immediately.
        GlintTableModelClient.refresh();
    }

    // Skin-cycle button (top-right corner of the window, above the right grid); 342 = the window width, so the
    // button sits 4px in from the right edge.
    private static final int SKIN_BTN_W = 50, SKIN_BTN_H = 12;
    private static final int SKIN_BTN_X = 342 - SKIN_BTN_W - 4;
    private static final int SKIN_BTN_Y = 5;

    private final List<String> trims = new ArrayList<>();
    private final Map<String, ItemStack> trimCache = new HashMap<>();
    private int gridScroll = 0;

    // Right grid (2), the player's printed (painted) trim library, synced into CLIENT_PRINTED.
    private int printScroll = 0;

    // Which grid's scrollbar is being dragged (0 = left, 1 = right, -1 = none).
    private int draggingGrid = -1;
    // Whether the Import picker's scrollbar thumb is being dragged.
    private boolean draggingImport = false;

    // Grid selections: left-click sets the main (layer-1) design; right-click sets the merge donor (its
    // colors fold into the main, the main keeps its design). selectedPrinted is a finished trim picked
    // from the right library (loaded into preview + main slot). The donor can be an empty-design trim
    // (left grid) or a printed trim (right grid); selectedDonorPrinted marks the always-owned latter.
    private String selectedMain  = null;
    private ItemStack selectedDonor = ItemStack.EMPTY;
    private boolean selectedDonorPrinted = false;
    private ItemStack selectedPrinted = ItemStack.EMPTY;

    // The printed-library ghost (main / merge) last CONFIRMED present in CLIENT_PRINTED. If that exact trim is
    // later withdrawn/deleted from the library, the ghost is dropped. Tracking "was confirmed present" (by
    // identity) means a just-placed ghost isn't cleared during the brief window before its deposit re-syncs.
    private ItemStack lastValidatedPrinted = ItemStack.EMPTY;
    private ItemStack lastValidatedDonor = ItemStack.EMPTY;

    // Multi-layer build: the controls always edit the ACTIVE layer; committed layers live below/above it
    // (render order = lowerLayers, active, upperLayers). "Add Layer" locks the active into lowerLayers and
    // starts a fresh one (one layer tear per extra layer); clicking a chip pulls that layer back to active.
    private final List<CustomGlint.Layer> lowerLayers = new ArrayList<>();
    private final List<CustomGlint.Layer> upperLayers = new ArrayList<>();
    private static final int MAX_LAYERS = 8; // matches the wand editor's 8-layer cap

    // Layer indicator strip: a row of design-icon chips filling the exact gap between the two scrollable
    // recesses (122..218 = 96px = 8 cells of 12), bottoms flush to the preview top (68). The [+] add chip
    // occupies a cell only until the 8-layer cap, so at most 8 cells span the column.
    private static final int LAYER_STRIP_X = 122, LAYER_CELL = 12, LAYER_ICON = 12;
    private static final int LAYER_STRIP_Y = PREVIEW_Y - LAYER_ICON; // icon bottom aligns to preview top

    // Modifier build state for the trim being built.
    //   speed/scale: 0.10×..8.0× (0.10 steps below 1×, whole steps above); default 1×. Cost = round(value).
    //   opacity:     0..8 glass → opaque..translucent (default 0 = no glass = fully opaque). Cost = count.
    private float modSpeed = 1.0f, modScale = 1.0f;
    private int modOpacity = 0;
    // Scroll motion: a SCROLL_* direction (default East), plus a frozen UV offset used only when STATIC.
    private int modScrollDir = CustomGlint.SCROLL_E;
    private float modScrollOffset = 0.0f;
    private boolean modInterpolate = true; // smooth colour blending between layer colours
    private boolean modGlow = false, modNamed = false; // glow / name-tag true-false toggles
    private boolean glowAuto = true;                    // glow color: auto (from layer 0) vs manual (dye)
    private boolean tearSimultaneous = true;            // tear mode chosen by the tear button (needs a tear)
    private boolean activeSourceSim = false;            // whether the active layer's SOURCE was simultaneous
                                                        // (so reverting it to sequential charges a sequential tear)
    // The active layer's colors, one entry per color shard (up to 8). Each shard is a list of dye indices
    // (0..15) blended into one colour: a single dye is a pure colour, several dyes average into a custom mix
    // that isn't in the dye table. An empty list is a freshly-added shard with no colour picked yet. The "+"
    // box adds a shard; clicking a shard selects it; right-click a dye sets it, shift-right-click mixes in.
    private final List<List<Integer>> colorShards = new ArrayList<>();
    // A glint trim's manual glow colours live in their own shard list, edited in the SAME strip as the layer
    // colours but only while the glow mode button is focused (right-click it). glowFocused routes the strip's
    // draw / click / dye-pick / hex at these instead of the active layer's colorShards.
    private final List<List<Integer>> glowShards = new ArrayList<>();
    private boolean glowFocused = false;
    // The color shard currently selected for editing (-1 = none). The dye bar highlights this shard's color,
    // and right-clicking a dye recolors it.
    private int selectedColorIdx = -1;
    // Custom-color encoding inside a shard's list: RAINBOW marks a shard whose colour comes from the rainbow
    // dye (a custom hex, entered in the hex box) instead of dye indices; CUSTOM_FLAG | rgb stores the hex.
    // RAINBOW sits one past the 16 vanilla dye indices, so it can share a shard list with them; CUSTOM_FLAG is
    // set well clear of the 24 colour bits it is OR'd with.
    private static final int RAINBOW = 16, CUSTOM_FLAG = 0x40000000;
    private EditBox hexBox;          // custom-hex entry, shown beside a rainbow/custom shard or color slot
    private boolean hexOpen = false; // whether the hex box is open
    private int hexShardIdx = -1;    // the shard the hex box edits (in HEX_SHARD mode)
    private int hexMode = HEX_SHARD; // what the hex box is editing
    private static final int HEX_SHARD = 0, HEX_GLOW = 1, HEX_NAME = 2;
    private int glowHex = -1, nameHex = -1; // custom glow / name hex colours (when a rainbow dye is in the slot)
    private ItemStack lastMain = ItemStack.EMPTY;           // last main-slot item, to detect a placed trim
    private String trimName = "";                       // custom name text (when Name = true)
    private EditBox nameBox;
    private static final int ALPHA_MIN = 32; // most-translucent alpha (at 8 glass)

    // The clickable buttons (created in init()). Labels and colours are pulled live each frame from their
    // suppliers, so only the buttons whose ENABLED/VISIBLE state syncButtons() flips need a field here; the
    // rest are added as bare widgets in addButtons(). Slot captions and values are still drawn by the screen.
    private BevelButton printBtn, glowModeBtn, glowToggleBtn, nameToggleBtn;
    private BevelButton offsetMinus, offsetPlus; // shown only while the scroll button is on Static
    private static final int SND_BTN_W = 12; // square toggle just left of the skin button
    private static final int IMP_BTN_W = 12; // square Import toggle just left of the sound button

    // Screen x of the two square header toggles. addButtons() places them here and extractTooltip() hit-tests
    // them here, so the 2px gaps between the three header buttons are written down once.
    private int sndBtnX() { return leftPos + SKIN_BTN_X - SND_BTN_W - 2; }
    private int impBtnX() { return sndBtnX() - IMP_BTN_W - 2; }

    // ── Import picker overlay ─────────────────────────────────────────────────
    // Lists two blueprint sources: the player's PERSONAL client trims (config/glint-and-glamour/trims/*.json on this
    // client, cross-world, freely deletable) and, on a dedicated server, the SHARED server blueprints synced
    // from the server (op-managed, only ops can delete). Picking one sends it to the server, which drops it
    // into the printed library as a LOCKED (dimmed) build target.
    private boolean showImportPicker = false;
    /** One row in the import picker. {@code server} = a shared server blueprint (op-deletable); otherwise a
     *  personal client file (this-client-deletable). */
    private record ImpEntry(String name, boolean server) {}
    private final List<ImpEntry> importAll = new ArrayList<>();  // every scanned blueprint (client + server)
    private List<ImpEntry> importFiltered = new ArrayList<>();   // the search-filtered view shown in the list
    private int importScroll = 0;
    private EditBox importSearchBox;
    // 10 rows of 13px plus the search bar keeps the panel inside the window's upper half, clear of the
    // player inventory; 160px wide fits a long file name without covering both grids.
    private static final int IMPORT_ROWS = 10, IMPORT_ROW_H = 13, IMP_PW = 160;
    private static final int IMP_TRASH_W = 11; // hover-only delete hotzone at the right edge of an import row

    // Scroll-direction control under the speed/opacity/scale slots (bottom-left). A button cycles the 8
    // compass presets + Static; a small −/value/+ offset stepper shows only while Static.
    private static final int SCROLL_X = 12, SCROLL_Y = 194, SCROLL_W = 100, SCROLL_H = 12; // centered on the left grid
    private static final int SCROLL_OFF_Y = 208; // static-offset stepper row

    // Interpolation toggle (bottom-right), centered on the right grid like the scroll button is on the left.
    private static final int INTERP_X = 217, INTERP_Y = 194, INTERP_W = 116, INTERP_H = 12;

    // Name / glow color sections below the Print button (center column)
    private static final int NAME_BOX_X = 129, NAME_BOX_Y = 186, NAME_BOX_W = 62;
    // Centered in the 129..211 span: 129 + (82-62)/2.
    private static final int GLOW_MODE_X = 139, GLOW_MODE_Y = 204, GLOW_MODE_W = 62, GLOW_MODE_H = 12;

    public GlintTableScreen(GlintTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 342, 334);
        this.titleLabelY = 6;
        this.inventoryLabelY = 242;
    }

    private static Identifier designRL(String name) {
        return CustomGlint.designFromName(name); // handles vanilla + chromatic sentinels
    }

    private static ItemStack trimStack(String name) {
        ItemStack s = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(s, designRL(name));
        return s;
    }

    @Override
    protected void init() {
        super.init();
        applySkin(GlintTableSkin.byIndex(GlintClientConfig.glintTableSkin()));
        // Warm all skin background textures now (idempotent) so cycling skins doesn't cold-load a PNG mid-click.
        GlintTableSkin.preloadTextures();
        addButtons();
        if (trims.isEmpty()) {
            trims.add(GlowTrimItem.STORAGE_KEY); // the Glow Trim is an empty trim, not a painted one
            trims.addAll(GlintTrimItem.PATTERNS);
        }
        if (trimCache.isEmpty()) {
            trimCache.put(GlowTrimItem.STORAGE_KEY, new ItemStack(ModItems.GLOW_TRIM.get()));
            for (String name : GlintTrimItem.PATTERNS) trimCache.put(name, trimStack(name));
        }
        this.titleLabelX = (this.imageWidth - this.font.width(this.title)) / 2;

        // A screen-customization mod (e.g. FancyMenu) re-runs init() to apply its layout, recreating these
        // boxes unfocused and (for the import search / hex) empty. Capture the live state first so a re-init
        // mid-edit doesn't drop focus or wipe a partial entry; restored just after each box is rebuilt below.
        boolean nameWasFocused   = nameBox != null && nameBox.isFocused();
        String  prevHexVal       = hexBox != null ? hexBox.getValue() : "";
        String  prevImportQuery  = importSearchBox != null ? importSearchBox.getValue() : "";

        nameBox = new EditBox(font, leftPos + NAME_BOX_X, topPos + NAME_BOX_Y, NAME_BOX_W, 12,
                Component.translatable("screen.customglint.glint_table.name"));
        nameBox.setMaxLength(32); // matches vanilla's anvil rename limit
        nameBox.setValue(trimName);
        nameBox.setResponder(s -> trimName = s);
        nameBox.setVisible(modNamed);
        addRenderableWidget(nameBox);
        if (nameWasFocused) { nameBox.setFocused(true); setFocused(nameBox); }

        // Custom-hex entry beside a rainbow/custom color shard (positioned + shown per-frame in extractContents).
        // Wide enough to show the full 6-digit hex. Added as an event-only widget (not a renderable) so it can
        // be drawn manually at the very end of extractContents, on top of the modifier buttons it may overlap.
        hexBox = new EditBox(font, leftPos, topPos, 56, COLOR_ICON, Component.translatable("screen.customglint.glint_table.hex"));
        hexBox.setMaxLength(6); // RRGGBB
        hexBox.setResponder(this::applyHex);
        hexBox.setVisible(false);
        addWidget(hexBox);
        if (hexOpen) { hexBox.setValue(prevHexVal); hexBox.setVisible(true); hexBox.setFocused(true); setFocused(hexBox); }

        // Import-picker search box: an event-only widget (drawn manually inside renderImportPicker so it sits
        // on top of the modal panel), hidden until the picker opens.
        importSearchBox = new EditBox(font, leftPos, topPos, IMP_PW - 4, 12,
                Component.translatable("screen.customglint.glint_table.import_search"));
        importSearchBox.setMaxLength(40);
        importSearchBox.setResponder(this::filterImport);
        importSearchBox.setVisible(false);
        addWidget(importSearchBox);
        if (showImportPicker) {
            importSearchBox.setValue(prevImportQuery);
            filterImport(prevImportQuery);
            importSearchBox.setVisible(true);
            importSearchBox.setFocused(true);
            setFocused(importSearchBox);
        }

        // Restore the in-progress build from the last time the table was open (once per screen instance, so a
        // window resize, which re-runs init(), doesn't clobber the live edits).
        if (!restored) { restoreBuild(); restored = true; }
    }

    @Override
    public void removed() {
        saveBuild(); // keep the editor state so reopening the table resumes the build instead of resetting
        GlintClientConfig.flush(); // persist any skin/sound toggles once, off the per-click path
        super.removed();
    }

    // ── In-progress build persistence ──────────────────────────────────────────
    // The editor controls (design pick, dye shards, modifiers, layers) live only in this transient screen,
    // so closing the table to grab materials would otherwise wipe the WIP. Snapshot it on close, restore on
    // open. Static + per-session: survives reopening the table, not a game restart. The physical slots
    // already persist in the block entity.
    private boolean restored = false;
    private static Build savedBuild;

    /** Drop the persisted in-progress build on disconnect so a later session can't resume the previous
     *  server's WIP trim (matches the sync-mirror clearing in {@code GlintTableClientInit}). */
    public static void clearSavedBuild() { savedBuild = null; }

    private static final class Build {
        String selectedMain, trimName;
        ItemStack selectedDonor, selectedPrinted;
        boolean selectedDonorPrinted, modInterpolate, modGlow, modNamed, glowAuto, tearSimultaneous, activeSourceSim;
        List<CustomGlint.Layer> lowerLayers, upperLayers;
        List<List<Integer>> colorShards, glowShards;
        boolean glowFocused;
        float modSpeed, modScale, modScrollOffset;
        int modOpacity, modScrollDir, selectedColorIdx, glowHex, nameHex;
    }

    private static List<List<Integer>> copyShards(List<List<Integer>> src) {
        List<List<Integer>> out = new ArrayList<>(src.size());
        for (List<Integer> s : src) out.add(new ArrayList<>(s));
        return out;
    }

    private void saveBuild() {
        Build b = new Build();
        b.selectedMain = selectedMain;
        b.selectedDonor = selectedDonor.copy();
        b.selectedDonorPrinted = selectedDonorPrinted;
        b.selectedPrinted = selectedPrinted.copy();
        b.lowerLayers = new ArrayList<>(lowerLayers);
        b.upperLayers = new ArrayList<>(upperLayers);
        b.colorShards = copyShards(colorShards);
        b.glowShards = copyShards(glowShards); b.glowFocused = glowFocused;
        b.modSpeed = modSpeed; b.modScale = modScale; b.modOpacity = modOpacity;
        b.modScrollDir = modScrollDir; b.modScrollOffset = modScrollOffset;
        b.modInterpolate = modInterpolate; b.modGlow = modGlow; b.modNamed = modNamed;
        b.glowAuto = glowAuto; b.tearSimultaneous = tearSimultaneous; b.activeSourceSim = activeSourceSim;
        b.selectedColorIdx = selectedColorIdx; b.glowHex = glowHex; b.nameHex = nameHex;
        b.trimName = trimName;
        savedBuild = b;
    }

    private void restoreBuild() {
        Build b = savedBuild;
        if (b == null) return;
        selectedMain = b.selectedMain;
        selectedDonor = b.selectedDonor == null ? ItemStack.EMPTY : b.selectedDonor.copy();
        selectedDonorPrinted = b.selectedDonorPrinted;
        selectedPrinted = b.selectedPrinted == null ? ItemStack.EMPTY : b.selectedPrinted.copy();
        lowerLayers.clear(); lowerLayers.addAll(b.lowerLayers);
        upperLayers.clear(); upperLayers.addAll(b.upperLayers);
        colorShards.clear(); colorShards.addAll(copyShards(b.colorShards));
        glowShards.clear(); if (b.glowShards != null) glowShards.addAll(copyShards(b.glowShards)); glowFocused = b.glowFocused;
        modSpeed = b.modSpeed; modScale = b.modScale; modOpacity = b.modOpacity;
        modScrollDir = b.modScrollDir; modScrollOffset = b.modScrollOffset;
        modInterpolate = b.modInterpolate; modGlow = b.modGlow; modNamed = b.modNamed;
        glowAuto = b.glowAuto; tearSimultaneous = b.tearSimultaneous; activeSourceSim = b.activeSourceSim;
        selectedColorIdx = b.selectedColorIdx; glowHex = b.glowHex; nameHex = b.nameHex;
        trimName = b.trimName == null ? "" : b.trimName;
        if (nameBox != null) { nameBox.setValue(trimName); nameBox.setVisible(modNamed); }
    }

    /** Reset the whole working trim back to a blank editor (right-click the main slot). */
    private void clearBuild() {
        selectedMain = null;
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
        selectedPrinted = ItemStack.EMPTY;
        lowerLayers.clear();
        upperLayers.clear();
        colorShards.clear();
        glowShards.clear();
        glowFocused = false;
        selectedColorIdx = -1;
        modSpeed = modScale = 1.0f;
        modOpacity = 0;
        modScrollDir = CustomGlint.SCROLL_E;
        modScrollOffset = 0.0f;
        modInterpolate = true;
        modGlow = modNamed = false;
        glowAuto = true;
        tearSimultaneous = true;
        activeSourceSim = false;
        glowHex = nameHex = -1;
        trimName = "";
        if (nameBox != null) { nameBox.setValue(""); nameBox.setVisible(false); }
        closeHex();
    }

    /** Drop a ghost-selected printed trim (main slot build, or merge donor) once it's been removed from the
     *  printed library (withdrawn / deleted). A ghost you can no longer pull a real copy of shouldn't keep
     *  previewing. Only fires after the selection was confirmed present, so a trim just placed here (deposited
     *  + ghosted) isn't cleared during the tick before its library sync arrives. Called once per frame. */
    private void reconcilePrintedSelections() {
        // Main-slot build tied to a printed trim.
        if (selectedPrinted.isEmpty()) {
            lastValidatedPrinted = ItemStack.EMPTY;
        } else if (printedContains(selectedPrinted)) {
            lastValidatedPrinted = selectedPrinted;
        } else if (lastValidatedPrinted == selectedPrinted) { // was in the library, now gone → removed
            clearBuild();
        }
        // Merge donor, only a printed-library donor can be removed this way (a palette design donor isn't).
        if (!selectedDonorPrinted || selectedDonor.isEmpty()) {
            lastValidatedDonor = ItemStack.EMPTY;
        } else if (printedContains(selectedDonor)) {
            lastValidatedDonor = selectedDonor;
        } else if (lastValidatedDonor == selectedDonor) {
            selectedDonor = ItemStack.EMPTY;
            selectedDonorPrinted = false;
            lastValidatedDonor = ItemStack.EMPTY;
        }
    }

    /** Whether the printed library currently holds a trim value-equal to {@code s}, ignoring the import-lock
     *  flag so that CRAFTING a selected import (which unlocks its entry in place) doesn't read as a removal. */
    private static boolean printedContains(ItemStack s) {
        ItemStack a = unlockedForMatch(s);
        for (ItemStack e : GlintPrintedSyncPacket.CLIENT_PRINTED)
            if (ItemStack.isSameItemSameComponents(a, unlockedForMatch(e))) return true;
        return false;
    }

    /** A copy with the import-lock component stripped (only when present), so a locked and unlocked copy of
     *  the same trim compare equal. */
    private static ItemStack unlockedForMatch(ItemStack s) {
        if (!isImportLocked(s)) return s;
        ItemStack c = s.copy();
        c.remove(ModComponents.IMPORT_LOCKED.get());
        return c;
    }

    /** Shift-move a table slot's item back into the player inventory (server-authoritative). */
    private void quickMoveSlot(int slot) {
        if (minecraft != null && minecraft.gameMode != null)
            minecraft.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    /** The main (slot-1) design picked from the left grid, on its own (the donor is shown in slot 2). */
    private ItemStack selectionPreview() {
        if (selectedMain == null) return ItemStack.EMPTY;
        ItemStack main = trimCache.get(selectedMain);
        return main == null ? ItemStack.EMPTY : main.copy();
    }

    /** True when the trim being built is a Glow Trim (main slot / selected printed / the palette Glow entry).
     *  Computed from the RAW selection, never via {@link #activeTrim()} or {@link #donorStack()}, so it is
     *  safe to call from those without recursion. */
    private boolean glowMainSelected() {
        ItemStack main = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (!main.isEmpty()) return main.getItem() instanceof GlowTrimItem;
        if (!selectedPrinted.isEmpty()) return selectedPrinted.getItem() instanceof GlowTrimItem;
        return GlowTrimItem.STORAGE_KEY.equals(selectedMain);
    }

    /** The merge donor (slot 2): a physical trim in that slot wins, else the right-click ghost selection. */
    private ItemStack donorStack() {
        if (glowMainSelected()) return ItemStack.EMPTY; // a Glow Trim can't merge: no donor at all
        ItemStack phys = menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem();
        if (phys.getItem() instanceof GlintTrimItem) return phys;
        return selectedDonor;
    }

    /** The donor's colors (empty if there's no donor / it's a colorless design). */
    private int[] donorColors() {
        ItemStack d = donorStack();
        return d.getItem() instanceof GlintTrimItem ? GlintTrimItem.getColors(d) : new int[0];
    }

    /** Whether the current donor is owned, physical trims and the printed library always are; a left-grid
     *  design donor must be in the stored set. No donor counts as fine. */
    private boolean donorOwned() {
        if (menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem().getItem() instanceof GlintTrimItem) return true;
        if (selectedDonor.isEmpty() || selectedDonorPrinted) return true;
        String name = trimDesignName(selectedDonor);
        return name != null && GlintStoredSyncPacket.CLIENT_STORED.contains(name);
    }

    // Per-render-pass memo for the two preview stacks. activeTrim()/previewSource() rebuild ItemStacks
    // (copy + component encode) and are each called many times across one draw cascade. Build state is
    // immutable for the span of a single extractContents draw pass, every mutation happens in an input
    // handler, between frames, so the result is cached for the pass and recomputed fresh next pass. The
    // memo is only armed across the draw cascade (extractContents), so input-time callers compute live.
    private boolean frameMemo = false;
    private ItemStack frameActiveTrim, framePreviewSource, frameActiveIcon;
    // canPrint() is a pure function of the same (build + menu-slot) state and runs ~3× per draw pass
    // (syncButtons + the Print button's label/face suppliers). Memoized across the same window.
    private Boolean frameCanPrint;
    // Per-layer strip-icon cache. layerIcon() builds an ItemStack and runs a full CustomGlint.write encode;
    // the strip redraws it for every chip every frame. Layers are immutable records added/removed as whole
    // units (never mutated in place), so an instance-keyed cache yields the same icon until the layer set
    // changes, when fresh Layer instances miss and rebuild. Screen-scoped, GC'd with the screen.
    private final Map<CustomGlint.Layer, ItemStack> layerIconCache = new IdentityHashMap<>();

    /** The single ACTIVE layer being edited: the main slot (shown as-is), else a selection with the modifier
     *  build state applied (speed / scale / opacity). The merge-slot donor is NOT folded in; an uncommitted
     *  donor is a live preview only (its own stacked layer), costing/counting nothing until [+] commits it. */
    private ItemStack activeTrim() {
        if (frameMemo && frameActiveTrim != null) return frameActiveTrim;
        ItemStack result = activeTrimBase();
        if (frameMemo) frameActiveTrim = result;
        return result;
    }

    /** The active layer's base (the main slot as-is, else the selection with modifiers applied) WITHOUT
     *  the donor folded in. The preview stacks the merge-slot donor as its own layer(s), so folding its
     *  colours into the base here too would show them twice. */
    private ItemStack activeTrimBase() {
        ItemStack main = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (!main.isEmpty()) return main;
        ItemStack sel = selectedPrinted.isEmpty() ? selectionPreview() : selectedPrinted;
        return applyMods(sel);
    }

    /** The full multi-layer trim previewed/printed: lowerLayers, then the active layer, then upperLayers. */
    private ItemStack previewSource() {
        if (frameMemo && framePreviewSource != null) return framePreviewSource;
        ItemStack result = computePreviewSource(true);
        if (frameMemo) framePreviewSource = result;
        return result;
    }

    /** The built trim WITHOUT the live merge-slot donor folded in: the main-slot ghost shows only the
     *  player's own build (slot-1 design + committed layers), so a trim staged in the merge slot previews
     *  its merge ONLY in the big preview box, not back on the main slot. */
    private ItemStack mainSlotSource() {
        return computePreviewSource(false);
    }

    private ItemStack computePreviewSource(boolean includeDonor) {
        // Active layer WITHOUT the donor merged in; the merge-slot trim is previewed live as its own stacked
        // layer(s) below (as if already merged with the main slot), so folding its colours into the base too
        // would show them twice. Pressing [+] is what actually commits the donor into upperLayers.
        ItemStack active = activeTrimBase();
        CustomGlint.Layer[] donor = includeDonor ? donorLayers() : new CustomGlint.Layer[0];
        if (lowerLayers.isEmpty() && upperLayers.isEmpty() && donor.length == 0) return active; // single-layer fast path
        // Committed + donor layers render through renderLayer(): a colorless layer shows white (blank-design
        // look) while its stored colors stay empty, so it edits as unset (not rainbow) and can't be printed.
        List<CustomGlint.Layer> all = new ArrayList<>();
        for (CustomGlint.Layer l : lowerLayers) all.add(renderLayer(l));
        boolean activeValid = active.getItem() instanceof GlintTrimItem;
        if (activeValid) {
            CustomGlint.Data ad = CustomGlint.read(active);
            if (ad != null) Collections.addAll(all, ad.layers());
        }
        for (CustomGlint.Layer l : upperLayers) all.add(renderLayer(l));
        for (CustomGlint.Layer l : donor) all.add(renderLayer(l)); // live donor preview, stacked on top (not yet committed via [+])
        if (all.isEmpty()) return active;
        ItemStack carrier = activeValid ? active.copy() : new ItemStack(ModItems.GLINT_TRIM.get());
        if (!activeValid) GlintTrimItem.setPattern(carrier, all.get(0).design()); // base icon from first layer
        CustomGlint.write(carrier, all.toArray(new CustomGlint.Layer[0]));
        return carrier;
    }

    /** A layer as it should RENDER: a colorless non-chromatic layer defaults to white (matching a blank
     *  design trim's look), while the stored layer keeps its empty colors, so the editor shows it as unset
     *  rather than a rainbow shard, and the print stays blocked until a colour is added. */
    private static CustomGlint.Layer renderLayer(CustomGlint.Layer l) {
        if (l.colors().length > 0 || CustomGlint.isChromatic(l.design())) return l;
        return new CustomGlint.Layer(l.design(), new int[]{0xFFFFFFFF}, l.speed(), l.interpolate(),
                l.patternScale(), l.simultaneous(), l.scrollDir(), l.scrollOffset(), l.seed());
    }

    /** Snapshot the active layer's current build as a {@link CustomGlint.Layer}, or null if it has no
     *  design. An uncolored (no dye chosen) active layer is still captured, with empty colors, so
     *  switching layers preserves it as the player-facing "empty" placeholder instead of dropping it. */
    private CustomGlint.Layer captureActive() {
        ItemStack a = activeTrim();
        if (!(a.getItem() instanceof GlintTrimItem)) return null;
        Identifier design = GlintTrimItem.getPattern(a);
        if (design == null) return null;
        // White-fill an empty (colorless) layer so it stays readable: CustomGlint.read() rejects a non-chromatic
        // layer with zero colors (returns null), which would blank the active layer's chip and, once this layer
        // is committed to lower/upperLayers via a swap/edit, break the whole multi-layer preview. writeColors
        // leaves a chromatic layer's empty palette untouched (read() accepts that).
        int[] colors = GlintTrimItem.writeColors(design, GlintTrimItem.getColors(a));
        boolean sim = false;
        CustomGlint.Data d = CustomGlint.read(a);
        if (d != null && d.layers().length > 0) sim = d.layers()[0].simultaneous();
        return new CustomGlint.Layer(design, colors, GlintTrimItem.getSpeed(a), modInterpolate,
                GlintTrimItem.getScale(a), sim, GlintTrimItem.getScrollDir(a), GlintTrimItem.getScrollOffset(a),
                GlintTrimItem.getSeed(a));
    }

    /** Load a committed layer back into the editable controls (it becomes the active layer). */
    private void loadControlsFromLayer(CustomGlint.Layer l) {
        selectedPrinted = ItemStack.EMPTY;
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
        selectedMain = gridNameFor(l.design());
        modSpeed = Math.max(0.10f, Math.min(8.0f, l.speed()));
        modScale = Math.max(0.10f, Math.min(8.0f, l.patternScale()));
        modScrollDir = l.scrollDir();
        modScrollOffset = l.scrollOffset();
        modInterpolate = l.interpolate();
        tearSimultaneous = l.simultaneous();
        activeSourceSim = l.simultaneous();
        int[] c = l.colors();
        modOpacity = opacityFromColors(c);
        colorShards.clear();
        selectedColorIdx = -1;
        for (int col : c) if (!isFillWhite(col)) addColorShard(col & 0xFFFFFF);
    }

    /** A single-dye shard (pure colour). */
    private static List<Integer> newShard(int dye) {
        List<Integer> s = new ArrayList<>();
        s.add(dye);
        return s;
    }

    /** The pure-white writeColors() stamps on an otherwise-colorless layer so it stays renderable (read()
     *  rejects a non-chromatic layer with zero colors). It is NOT a player-chosen colour; the white dye is
     *  0xFFF9FFFE, so the editor loads it as an EMPTY shard, letting the player pick the colour themselves. */
    private static boolean isFillWhite(int color) {
        return (color & 0xFFFFFF) == 0xFFFFFF;
    }

    private void addColorShard(int rgb) { addShardTo(colorShards, rgb); }

    /** Append a shard for a stored colour to {@code shards} (up to 8): a dye shard when the rgb matches a dye,
     *  else a custom-hex shard. Shared by the layer strip and the glow strip. */
    private void addShardTo(List<List<Integer>> shards, int rgb) {
        if (shards.size() >= 8) return;
        for (int i = 0; i < 16; i++) if (dyeRgb(i) == rgb) { shards.add(newShard(i)); return; }
        List<Integer> custom = new ArrayList<>();
        custom.add(CUSTOM_FLAG | (rgb & 0xFFFFFF));
        shards.add(custom);
    }

    /** Load a painted trim into the editor for preview/editing: its glow/name + active-layer mods, and,
     *  for a multi-layer trim, split its layers across the strip (first layer active, the rest as upper
     *  layers) so every shard shows its own design + transforms. A single-layer trim stays the selection. */
    private void loadFromTrim(ItemStack trim) {
        selectedPrinted = trim;
        selectedMain = null;
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
        lowerLayers.clear();
        upperLayers.clear();
        loadModsFrom(trim); // glow / name / custom-name + the (single-layer) active mods
        CustomGlint.Data d = CustomGlint.read(trim);
        if (d != null && d.layers().length > 1) {
            CustomGlint.Layer[] ls = d.layers();
            for (int i = 1; i < ls.length; i++) upperLayers.add(ls[i]);
            loadControlsFromLayer(ls[0]); // first layer becomes the active one (keeps glow/name above)
        }
    }

    /** The left-grid / design name for a design identifier: a bare pattern name for this mod's own designs,
     *  {@code namespace:name} for another mod's. Matches the keys in {@code GlintTrimItem.PATTERNS}. */
    private static String gridNameFor(Identifier p) {
        if (p.equals(CustomGlint.VANILLA)) return "vanilla";
        String name = GlintTrimItem.extractPatternName(p);
        return p.getNamespace().equals("customglint") ? name : p.getNamespace() + ":" + name;
    }

    private int totalLayers() {
        return lowerLayers.size() + upperLayers.size() + (captureActive() != null ? 1 : 0);
    }

    private int layerTearCount() {
        return menu.slots.get(GlintTableMenu.SLOT_LAYER_TEAR).getItem().getCount();
    }

    /** A committed layer is valid when it has 1..8 colors and its design is one the player owns. */
    private boolean layerValid(CustomGlint.Layer l) {
        if (l.colors().length == 0 || l.colors().length > 8) return false;
        return GlintStoredSyncPacket.CLIENT_STORED.contains(gridNameFor(l.design()));
    }

    /** A layer with no player-chosen colour: a non-chromatic empty layer carries only the synthetic white fill,
     *  a chromatic one an empty palette. Either way the player still owes a colour, so this mirrors the active
     *  layer's {@code countSelectedDyes() == 0} test for the committed layers. */
    private static boolean layerColorless(CustomGlint.Layer l) {
        for (int c : l.colors()) if (!isFillWhite(c)) return false;
        return true;
    }

    /** True when any layer of the build (the active one or a committed one) still lacks a colour. */
    private boolean anyLayerColorless(int baseColorCount) {
        if (baseColorCount == 0 && countSelectedDyes() == 0) return true;
        for (CustomGlint.Layer l : lowerLayers) if (layerColorless(l)) return true;
        for (CustomGlint.Layer l : upperLayers) if (layerColorless(l)) return true;
        return false;
    }

    /** The trim whose exact data the print must reproduce, a placed physical trim, else a selected
     *  printed-library trim. A left-grid design pick is a fresh build with no inherent layer/simultaneous
     *  state, so it returns EMPTY (nothing to reproduce). */
    private ItemStack reproSource() {
        ItemStack main = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (main.getItem() instanceof GlintTrimItem) return main;
        return selectedPrinted.isEmpty() ? ItemStack.EMPTY : selectedPrinted;
    }

    /** A copy of the base trim with the current speed / scale / opacity build state baked in. */
    private ItemStack applyMods(ItemStack base) {
        // A Glow Trim carries no glint design; it can only take colors, applied as its glow colors. It is a
        // single, non-layerable trim, so the color-shard strip drives its glow colours and nothing else.
        if (base.getItem() instanceof GlowTrimItem) {
            ItemStack s = base.copy();
            s.setCount(1);
            int[] cols = glowTrimColors();
            if (cols.length > 0) { CustomGlint.setGlowColors(s, cols); CustomGlint.setGlowing(s, true); }
            else { CustomGlint.clearGlowColors(s); CustomGlint.setGlowing(s, false); }
            CustomGlint.setGlowAnim(s, modSpeed, modInterpolate); // speed + interpolation drive the glow cycle
            applyCustomName(s);
            return s;
        }
        if (!(base.getItem() instanceof GlintTrimItem)) return base;
        ItemStack s = base.copy();
        GlintTrimItem.setSpeed(s, modSpeed);
        GlintTrimItem.setScale(s, modScale);
        GlintTrimItem.setScrollDir(s, modScrollDir);
        GlintTrimItem.setScrollOffset(s, modScrollOffset);
        GlintTrimItem.setColors(s, buildColors()); // colors come from the dye bar (with opacity applied)
        GlintTrimItem.setGlowing(s, modGlow);
        CustomGlint.setGlowing(s, modGlow);
        // Manual glow: colours come from the glow shard strip (up to 8). Auto glow / glow off carries no glow
        // colour list (the outline then follows glint layer 0).
        if (modGlow && !glowAuto) {
            int[] gcols = glowManualColors();
            if (gcols.length > 0) CustomGlint.setGlowColors(s, gcols); else CustomGlint.clearGlowColors(s);
        } else {
            CustomGlint.clearGlowColors(s);
        }
        applyCustomName(s);
        // Final glint Data write carrying the chosen interpolation and the active layer's simultaneous state.
        Identifier pat = GlintTrimItem.getPattern(s);
        if (pat != null) {
            boolean sim = activeSim();
            CustomGlint.write(s, pat, GlintTrimItem.getColors(s), GlintTrimItem.getSpeed(s), modInterpolate,
                    GlintTrimItem.getScale(s), sim, GlintTrimItem.getScrollDir(s), GlintTrimItem.getScrollOffset(s));
        }
        return s;
    }

    /** Stamp the typed name onto a preview stack when naming is on, coloured by the name dye slot (or its
     *  custom hex when a rainbow dye sits there), falling back to white when the slot is empty. */
    private void applyCustomName(ItemStack s) {
        if (!modNamed || trimName.isEmpty()) return;
        int nc = slotColor(GlintTableMenu.SLOT_NAME_DYE, nameHex);
        int rgb = nc >= 0 ? nc : 0xFFFFFF;
        s.set(DataComponents.CUSTOM_NAME, Component.literal(trimName).withStyle(st -> st.withColor(TextColor.fromRgb(rgb))));
    }

    /** Glass-count → glint alpha. 0 glass = fully opaque (255); each +1 fades toward {@link #ALPHA_MIN}. */
    private int modAlpha() {
        return Math.round(255f - modOpacity * (255f - ALPHA_MIN) / 8f);
    }

    /** The inverse of {@link #modAlpha}: recover the glass count from a stored layer's baked-in alpha (taken
     *  from its first colour, which all its colours share). A colourless layer reads as fully opaque. */
    private static int opacityFromColors(int[] colors) {
        int alpha = colors.length > 0 ? (colors[0] >>> 24) & 0xFF : 255;
        return Math.max(0, Math.min(8, Math.round((255 - alpha) * 8f / (255f - ALPHA_MIN))));
    }

    /** The layer's colors, from the dye slots the player has *selected* (up to 8). Empty when nothing is
     *  chosen, a fresh layer is player-facing "empty" (no glint shown, can't print), not auto-white. The
     *  player adds white the same as any other colour if they want it. */
    private int[] buildColors() {
        return packShardColors(colorShards, modAlpha());
    }

    /** Pack each shard's mixed RGB into an ARGB int with {@code alpha} in the high byte, skipping unset /
     *  rainbow-pending shards, capped at 8. Shared by the glint colours (opacity alpha) and the opaque glow
     *  colours ({@code alpha == 0xFF}). */
    private int[] packShardColors(List<List<Integer>> shards, int alpha) {
        List<Integer> cols = new ArrayList<>();
        for (List<Integer> shard : shards) {
            int rgb = mixRgb(shard);
            if (rgb < 0) continue; // unset / rainbow-pending, no color yet
            if (cols.size() >= 8) break;
            cols.add((alpha << 24) | rgb);
        }
        int[] arr = new int[cols.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = cols.get(i);
        return arr;
    }

    /** A Glow Trim's colours, taken from the MAIN shard strip (a Glow Trim has no glint layer, so the strip
     *  edits its glow colours directly). Opaque: glow has no opacity dimension, so the glass control's alpha
     *  never applies. Contrast {@link #glowManualColors}, which reads the separate manual-glow strip. */
    private int[] glowTrimColors() {
        return packShardColors(colorShards, 0xFF);
    }

    private int countSelectedDyes() {
        int n = 0;
        for (List<Integer> shard : colorShards) if (mixRgb(shard) >= 0) n++;
        return n;
    }

    /** The shard list the colour strip is currently editing: the manual-glow colours while the glow mode button
     *  is focused, otherwise the active layer's colours. All strip UI (draw / click / dye-pick / hex) routes
     *  through this so the one strip serves both. */
    private List<List<Integer>> editShards() { return glowFocused ? glowShards : colorShards; }

    /** True when the manual-glow colour shards are in play (glow enabled, manual, on a glint trim, not a Glow
     *  Trim, whose colours ARE the main strip). */
    private boolean glowShardsActive() { return modGlow && !glowAuto && !glowMainSelected(); }

    /** How many glow colour shards have a colour picked (manual glow). */
    private int glowColorCount() {
        int n = 0;
        for (List<Integer> shard : glowShards) if (mixRgb(shard) >= 0) n++;
        return n;
    }

    /** Manual-glow colours (opaque) from the glow shard list, for the preview + glowColors write. */
    private int[] glowManualColors() {
        return packShardColors(glowShards, 0xFF);
    }

    /** Cumulative dye cost across the whole trim: a per-shade count (dye index 0..15) plus a rainbow-dye count.
     *  Every colour shard on the active layer and every colour on each committed layer charges its own dye:
     *  a shade reused across shards / layers costs one dye each time (no de-duplication), mirroring
     *  {@link GlintTableMenu#print}. Colours already on a placed base trim are free (already paid for). */
    private record DyeReq(int[] counts, int rainbow) {}

    private DyeReq dyeReq() {
        int[] counts = new int[16];
        int rainbow = 0;
        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        int[] baseColors = base.getItem() instanceof GlintTrimItem ? GlintTrimItem.getColors(base) : new int[0];
        int budget = Math.max(0, 8 - baseColors.length);
        int added = 0;
        // Active layer: driven by the colour shards. A mix charges each of its component dyes; a rainbow /
        // custom-hex shard charges one rainbow dye.
        for (List<Integer> shard : colorShards) {
            int rgb = mixRgb(shard);
            if (rgb < 0 || added >= budget) continue;
            if (containsRgb(baseColors, rgb)) continue; // already on the base trim, free
            added++;
            if (isCustomShard(shard)) { rainbow++; continue; }
            for (int d : shard) if (d >= 0 && d < 16) counts[d]++; // each component dye of the mix, cumulative
        }
        // Committed layers below/above the active one add their colours on top (no de-dup with the active layer).
        for (CustomGlint.Layer l : lowerLayers) rainbow += layerDyeReq(l, counts);
        for (CustomGlint.Layer l : upperLayers) rainbow += layerDyeReq(l, counts);
        // Manual glow colours charge dyes too, cumulative with everything above (their own budget of 8, separate
        // from the glint colour count).
        if (glowShardsActive()) {
            int gadded = 0;
            for (List<Integer> shard : glowShards) {
                int rgb = mixRgb(shard);
                if (rgb < 0 || gadded >= 8) continue;
                gadded++;
                if (isCustomShard(shard)) { rainbow++; continue; }
                for (int d : shard) if (d >= 0 && d < 16) counts[d]++;
            }
        }
        return new DyeReq(counts, rainbow);
    }

    /** Fold one committed layer's colours into {@code counts}; returns the rainbow dyes it needs (any non-dye
     *  colour). The synthetic white fill an empty layer carries is not a chosen colour, so it's skipped. */
    private static int layerDyeReq(CustomGlint.Layer l, int[] counts) {
        int rainbow = 0;
        for (int color : l.colors()) {
            if (isFillWhite(color)) continue;
            int idx = dyeIndexForRgb(color & 0xFFFFFF);
            if (idx >= 0) counts[idx]++; else rainbow++;
        }
        return rainbow;
    }

    private static boolean containsRgb(int[] colors, int rgb) {
        int c = rgb & 0xFFFFFF;
        for (int col : colors) if ((col & 0xFFFFFF) == c) return true;
        return false;
    }

    /** RGB (lower 24 bits) → the vanilla dye index whose mod-palette colour matches, or -1 if it isn't a dye
     *  shade (a mix / custom colour, which costs a rainbow dye instead). */
    private static int dyeIndexForRgb(int color) {
        int rgb = color & 0xFFFFFF;
        for (int i = 0; i < 16; i++) if (dyeRgb(i) == rgb) return i;
        return -1;
    }

    /** How many of vanilla dye {@code i} the dye bar currently holds. */
    private int dyeSlotCount(int i) {
        return menu.slots.get(GlintTableMenu.SLOT_DYE_START + i).getItem().getCount();
    }

    /** Whether the dye bar (and rainbow slot) hold enough dye to pay the whole print's cumulative dye cost. */
    private boolean dyesAffordable() {
        DyeReq req = dyeReq();
        for (int i = 0; i < 16; i++) if (dyeSlotCount(i) < req.counts()[i]) return false;
        return req.rainbow() == 0
                || menu.slots.get(GlintTableMenu.SLOT_RAINBOW_DYE).getItem().getCount() >= req.rainbow();
    }

    /** A shard's RGB: a custom hex (rainbow dye) returns that colour; a rainbow shard with no hex yet, or an
     *  empty shard, returns -1; otherwise the equal-weight average of its dyes' colours (one dye = that
     *  colour, several = a mix). */
    private static int mixRgb(List<Integer> shard) {
        if (shard.isEmpty()) return -1;
        if (shard.size() == 1) {
            int v = shard.get(0);
            if (v == RAINBOW) return -1;                 // rainbow chosen, no hex entered yet
            if ((v & CUSTOM_FLAG) != 0) return v & 0xFFFFFF; // custom hex colour
        }
        int r = 0, g = 0, b = 0, n = 0;
        for (int d : shard) {
            if (d < 0 || d >= 16) continue;              // skip non-dye markers
            int rgb = dyeRgb(d); r += (rgb >> 16) & 0xFF; g += (rgb >> 8) & 0xFF; b += rgb & 0xFF; n++;
        }
        if (n == 0) return -1;
        return ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    /** A shard whose colour comes from the rainbow dye (a custom hex, or the rainbow marker awaiting one). */
    private static boolean isCustomShard(List<Integer> shard) {
        if (shard.size() != 1) return false;
        int v = shard.get(0);
        return v == RAINBOW || (v & CUSTOM_FLAG) != 0;
    }

    private boolean hasShiftDown() {
        return ClientInput.hasShiftDown();
    }

    /** The mod-palette RGB of dye index {@code idx} (= dye ordinal), matching the dye recipes and the trim
     *  tooltip's colour-name table, NOT {@code DyeColor.getTextColor()}, whose values differ for several
     *  dyes (e.g. lime) and would tooltip as a hex code instead of the colour name. */
    private static int dyeRgb(int idx) {
        return GlintTrimItem.DYE_COLORS[idx] & 0xFFFFFF;
    }

    /** Seed the modifier build state from a trim's current values (so re-printing reproduces it). */
    private void loadModsFrom(ItemStack trim) {
        colorShards.clear();
        glowShards.clear();
        glowFocused = false;
        selectedColorIdx = -1;
        if (trim.getItem() instanceof GlintTrimItem) {
            modSpeed = Math.max(0.10f, Math.min(8.0f, GlintTrimItem.getSpeed(trim)));
            modScale = Math.max(0.10f, Math.min(8.0f, GlintTrimItem.getScale(trim)));
            modScrollDir = GlintTrimItem.getScrollDir(trim);
            modScrollOffset = GlintTrimItem.getScrollOffset(trim);
            int[] c = GlintTrimItem.getColors(trim);
            modOpacity = opacityFromColors(c);
            // Rebuild the color shards from the trim's colors: a colour matching a dye becomes that dye
            // shard, anything else (a mix or rainbow custom hex) becomes a custom-hex shard.
            for (int col : c) if (!isFillWhite(col)) addColorShard(col & 0xFFFFFF);
            modGlow = GlintTrimItem.isGlowing(trim);
            modNamed = trim.has(DataComponents.CUSTOM_NAME);
            Component nm = trim.get(DataComponents.CUSTOM_NAME);
            trimName = (modNamed && nm != null) ? nm.getString() : "";
            if (nameBox != null) nameBox.setValue(trimName);
            int[] glowCols = CustomGlint.getGlowColors(trim);
            glowAuto = glowCols.length == 0; // override colors => manual
            for (int col : glowCols) if (!isFillWhite(col)) addShardTo(glowShards, col & 0xFFFFFF);
            CustomGlint.Data d = CustomGlint.read(trim);
            if (d != null && d.layers().length > 0) {
                tearSimultaneous = d.layers()[0].simultaneous();
                modInterpolate = d.layers()[0].interpolate();
            }
            activeSourceSim = tearSimultaneous; // this layer's source mode (for the sequential-revert charge)
        } else if (trim.getItem() instanceof GlowTrimItem) {
            // A Glow Trim's colours are its glow colours; load them into the shard strip. It has no glint
            // design / scale / opacity / layers; speed + interpolation drive its glow cycle (restored from
            // the trim).
            modSpeed = Math.max(0.10f, Math.min(8.0f, CustomGlint.getGlowSpeed(trim)));
            modScale = 1.0f;
            modOpacity = 0;
            modScrollDir = CustomGlint.SCROLL_E;
            modScrollOffset = 0.0f;
            modInterpolate = CustomGlint.getGlowInterpolate(trim);
            modGlow = false;
            activeSourceSim = false;
            for (int col : GlowTrimItem.getColors(trim)) if (!isFillWhite(col)) addColorShard(col & 0xFFFFFF);
            modNamed = trim.has(DataComponents.CUSTOM_NAME);
            Component nm = trim.get(DataComponents.CUSTOM_NAME);
            trimName = (modNamed && nm != null) ? nm.getString() : "";
            if (nameBox != null) nameBox.setValue(trimName);
        } else {
            modSpeed = modScale = 1.0f;
            modOpacity = 0;
            modScrollDir = CustomGlint.SCROLL_E;
            modScrollOffset = 0.0f;
            modInterpolate = true;
            modGlow = modNamed = false;
            trimName = "";
            activeSourceSim = false;
            if (nameBox != null) nameBox.setValue("");
        }
    }

    // ── Render ──────────────────────────────────────────────────────────────

    @Override
    public void extractContents(GuiGraphicsExtractor g, int mx, int my, float dt) {
        // Drop a main/merge ghost whose printed trim was just withdrawn/deleted from the library.
        reconcilePrintedSelections();

        // When a finished trim is placed in the main slot, pull all its values into the controls.
        ItemStack curMain = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (!ItemStack.matches(curMain, lastMain)) {
            lastMain = curMain.copy();
            if (curMain.getItem() instanceof GlintTrimItem) loadModsFrom(curMain);
        }

        // Always keep one color shard present: a fresh layer starts with a single unset shard (no colour)
        // so there's a visible starting point. Removing the last shard re-seeds an unset one. The lone/final
        // shard is always selected (it can't be deselected) so a dye click always lands somewhere.
        // Glow can only be focused (its shards edited) while it's actually in play; drop focus otherwise so the
        // strip falls back to the active layer.
        if (!glowShardsActive()) glowFocused = false;
        List<List<Integer>> edit = editShards();
        if (edit.isEmpty()) edit.add(new ArrayList<>());
        if (edit.size() == 1) selectedColorIdx = 0;

        // A Glow Trim can't merge, so drop any donor carried over from a previous glint build so no merge
        // preview / donor ring lingers.
        if (glowMainSelected() && !selectedDonor.isEmpty()) {
            selectedDonor = ItemStack.EMPTY;
            selectedDonorPrinted = false;
        }

        // Neither Glow nor Name is force-off when its material is absent: both preview live off the toggle,
        // and the missing glowstone / name tag only blocks the print (spelled out in the Print tooltip). Manual
        // glow now picks its colours in the shard strip, so the old single glow-dye slot stays hidden.

        // Drive the conditional dye slots + name field from the current toggle state.
        menu.showNameDye = modNamed;
        menu.showGlowDye = false;
        if (nameBox != null) nameBox.setVisible(modNamed);

        // Hex entry box: positioned flush to the right of whatever it edits (shard, or glow/name slot);
        // auto-closes if its target is gone (shard removed, or the slot no longer holds a rainbow dye).
        if (hexBox != null) {
            boolean show = hexOpen;
            int hx = 0, hy = 0;
            if (hexMode == HEX_GLOW) {
                Slot s = menu.slots.get(GlintTableMenu.SLOT_GLOW_DYE);
                show = hexOpen && menu.showGlowDye && rainbowInSlot(GlintTableMenu.SLOT_GLOW_DYE);
                hx = leftPos + s.x + 18; hy = topPos + s.y;
            } else if (hexMode == HEX_NAME) {
                Slot s = menu.slots.get(GlintTableMenu.SLOT_NAME_DYE);
                show = hexOpen && menu.showNameDye && rainbowInSlot(GlintTableMenu.SLOT_NAME_DYE);
                hx = leftPos + s.x + 18; hy = topPos + s.y;
            } else {
                show = hexOpen && hexShardIdx >= 0 && hexShardIdx < editShards().size()
                        && isCustomShard(editShards().get(hexShardIdx));
                hx = leftPos + COLOR_STRIP_X + hexShardIdx * COLOR_CELL + COLOR_ICON + 1;
                hy = topPos + COLOR_STRIP_Y;
            }
            if (hexOpen && !show) closeHex();
            hexBox.setVisible(show);
            if (show) { hexBox.setX(hx); hexBox.setY(hy); }
        }

        // Arm the preview-stack memo for the pure draw cascade below (state is settled after the prologue
        // above). finally-disarmed so a thrown draw can't leave a stale cache visible to input handlers.
        frameActiveTrim = null; framePreviewSource = null; frameActiveIcon = null; frameCanPrint = null; frameMemo = true;
        try {
            // Refresh button enabled/label state inside the memo window so its canPrint()/activeTrim()
            // cascade shares the same per-frame preview-stack cache the draw calls below reuse (it used to
            // run before the memo was armed and rebuilt the preview stacks from scratch).
            syncButtons();
            drawBackground(g);
            super.extractContents(g, mx, my, dt); // labels + real slot contents (+ name box) on top of the panel
            drawTrimGrid(g, mx, my);
            drawPrintedGrid(g, mx, my);
            drawMainPreview(g);
            drawDonorPreview(g);
            drawMainSlotRings(g);
            drawLayerStrip(g, mx, my);
            drawPreview(g);
            drawColorStrip(g, mx, my);
            drawModifierControls(g);
            drawNameGlowSection(g);
            drawDyeSelection(g);
            drawSectionLabels(g);
            // Drawn last so it overlays the modifier buttons (interp / true-false) it can sit on top of.
            if (hexBox != null) hexBox.extractRenderState(g, mx, my, dt);
            // The import picker is modal, drawn on top of everything else in the window.
            if (showImportPicker) renderImportPicker(g, mx, my, dt);
            drawImportMsg(g); // transient save confirmation, on top of everything
        } finally {
            frameMemo = false;
        }
    }

    /** Draw the window title + inventory label in the skin's header color (vanilla hardcodes dark gray). */
    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
        g.text(font, title, titleLabelX, titleLabelY, LABEL_HDR, false);
        g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL_HDR, false);
    }

    /** Vanilla draws tooltips for real slots; add hover tooltips for the two scrollable grids (printed
     *  trims + the design palette), which are custom-drawn, not real slots. */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor g, int mx, int my) {
        if (showImportPicker) return; // the modal import picker owns the screen; suppress slot/grid tooltips under it
        super.extractTooltip(g, mx, my);
        if (!menu.getCarried().isEmpty()) return; // don't tooltip while a stack is on the cursor
        int btnY = topPos + SKIN_BTN_Y;
        if (inBox(mx, my, impBtnX(), btnY, IMP_BTN_W, SKIN_BTN_H)) {
            g.setTooltipForNextFrame(font, List.of(
                    Component.translatable("screen.customglint.glint_table.import"),
                    Component.translatable("screen.customglint.glint_table.import_rclick").withStyle(ChatFormatting.GRAY)),
                    Optional.empty(), mx, my);
            return;
        }
        if (inBox(mx, my, leftPos + SKIN_BTN_X, btnY, SKIN_BTN_W, SKIN_BTN_H)) {
            g.setTooltipForNextFrame(font, Component.translatable("screen.customglint.glint_table.skin_tooltip"), mx, my);
            return;
        }
        if (inBox(mx, my, sndBtnX(), btnY, SND_BTN_W, SKIN_BTN_H)) {
            g.setTooltipForNextFrame(font, Component.translatable("screen.customglint.glint_table.sound",
                    Component.translatable(GlintClientConfig.glintTableSound()
                            ? "screen.customglint.glint_table.on" : "screen.customglint.glint_table.off")), mx, my);
            return;
        }
        if (inBox(mx, my, leftPos + PRINT_X, topPos + PRINT_Y, PRINT_W, PRINT_H)) {
            g.setTooltipForNextFrame(font, printTooltip(), Optional.empty(), mx, my);
            return;
        }
        // Modifier buttons carry their own explanatory tooltip (scroll / interpolation / glow / name / type /
        // the ± steppers); show whichever one the mouse is over.
        for (var child : this.children()) {
            if (!(child instanceof BevelButton bb) || bb.tooltip == null || !bb.visible) continue;
            if (!inBox(mx, my, bb.getX(), bb.getY(), bb.getWidth(), bb.getHeight())) continue;
            List<Component> lines = bb.tooltip.get();
            if (!lines.isEmpty()) { g.setTooltipForNextFrame(font, lines, Optional.empty(), mx, my); return; }
        }
        // Main / merge trim slots: a right-click clears the working trim (main) or the merge donor (merge). The
        // slots usually hold a ghost PREVIEW rather than a physical item, so key off the shown stack (physical
        // or ghost), not hasItem(); otherwise the hint vanishes exactly when there's a build to clear.
        ItemStack mainShown = mainSlotSource();
        if (overSlot(mx, my, GlintTableMenu.SLOT_TRIM) && !mainShown.isEmpty()) {
            appendSlotHint(g, mainShown, "screen.customglint.glint_table.hint.clear_build", mx, my);
            return;
        }
        ItemStack mergeShown = donorStack();
        if (overSlot(mx, my, GlintTableMenu.SLOT_TRIM_B) && !mergeShown.isEmpty()) {
            appendSlotHint(g, mergeShown, "screen.customglint.glint_table.hint.clear_merge", mx, my);
            return;
        }

        // Layer strip: explain each chip's click behaviour (custom-drawn, not real slots).
        int cy = topPos + LAYER_STRIP_Y;
        CustomGlint.Layer[] dl = donorLayers();
        for (Chip c : layerChips()) {
            if (!inBox(mx, my, leftPos + c.x(), cy, LAYER_ICON, LAYER_ICON)) continue;
            if (c.kind() == 3) { // [+] add chip: cap message when disabled, otherwise the add hint
                if (dl.length > 0 && totalLayers() + dl.length > MAX_LAYERS)
                    g.setTooltipForNextFrame(font, Component.translatable(
                            "screen.customglint.glint_table.add_layer_cap", MAX_LAYERS), mx, my);
                else
                    g.setTooltipForNextFrame(font, Component.translatable(
                            "screen.customglint.glint_table.hint.add_layer"), mx, my);
                return;
            }
            // A layer chip: name its trim design, then the click hint (active = remove, else edit).
            Identifier design = c.kind() == 0 ? lowerLayers.get(c.index()).design()
                    : c.kind() == 2 ? upperLayers.get(c.index()).design()
                    : activeLayerDesign();
            List<Component> lines = new ArrayList<>();
            if (design != null) lines.add(designDisplayName(design));
            lines.add(Component.translatable(c.kind() == 1
                    ? "screen.customglint.glint_table.hint.remove_layer"
                    : "screen.customglint.glint_table.hint.edit_layer").withStyle(ChatFormatting.GRAY));
            if (c.kind() != 1 && captureActive() != null)
                lines.add(Component.translatable("screen.customglint.glint_table.hint.layer_swap").withStyle(ChatFormatting.GRAY));
            g.setTooltipForNextFrame(font, lines, Optional.empty(), mx, my);
            return;
        }

        // Color-shard strip: select / remove the active layer's colours (and edit a rainbow shard's hex).
        // Hidden (no shard tooltips) while nothing is previewing, matching drawColorStrip.
        if (colorShardsVisible()) {
            List<List<Integer>> shards = editShards();
            int csy = topPos + COLOR_STRIP_Y;
            int n = Math.min(shards.size(), 8);
            for (int k = 0; k < n; k++) {
                if (!inBox(mx, my, leftPos + COLOR_STRIP_X + k * COLOR_CELL, csy, COLOR_ICON, COLOR_ICON)) continue;
                List<Component> lines = new ArrayList<>();
                lines.add(shardColorLabel(shards.get(k))); // header: the shard's actual colour
                if (selectedColorIdx == k) {
                    if (isCustomShard(shards.get(k)))
                        lines.add(Component.translatable("screen.customglint.glint_table.hint.shard_hex").withStyle(ChatFormatting.GRAY));
                    lines.add(Component.translatable("screen.customglint.glint_table.hint.shard_remove").withStyle(ChatFormatting.GRAY));
                } else {
                    lines.add(Component.translatable("screen.customglint.glint_table.hint.shard_select").withStyle(ChatFormatting.GRAY));
                    if (selectedColorIdx >= 0)
                        lines.add(Component.translatable("screen.customglint.glint_table.hint.shard_swap").withStyle(ChatFormatting.GRAY));
                }
                g.setTooltipForNextFrame(font, lines, Optional.empty(), mx, my);
                return;
            }
            if (n < 8 && !hexOpen // trailing "+" box
                    && inBox(mx, my, leftPos + COLOR_STRIP_X + n * COLOR_CELL, csy, COLOR_ICON, COLOR_ICON)) {
                g.setTooltipForNextFrame(font, Component.translatable(
                        "screen.customglint.glint_table.hint.add_color"), mx, my);
                return;
            }
        }

        // Dye bar: the 16 shade slots + the rainbow slot recolour the SELECTED shard and work even when empty
        // (the dye is only charged at print), which isn't obvious from looking, so spell out the click actions.
        for (int i = 0; i < 16; i++) {
            if (!overSlot(mx, my, GlintTableMenu.SLOT_DYE_START + i)) continue;
            g.setTooltipForNextFrame(font, List.of(
                    Component.translatable("color.minecraft." + DyeColor.byId(i).getName()),
                    Component.translatable("screen.customglint.glint_table.hint.dye_set").withStyle(ChatFormatting.GRAY),
                    Component.translatable("screen.customglint.glint_table.hint.dye_mix").withStyle(ChatFormatting.GRAY)),
                    Optional.empty(), mx, my);
            return;
        }
        if (overSlot(mx, my, GlintTableMenu.SLOT_RAINBOW_DYE)) {
            Slot rs = menu.slots.get(GlintTableMenu.SLOT_RAINBOW_DYE);
            ItemStack rstack = rs.hasItem() ? rs.getItem() : ModItems.RAINBOW_DYE.get().getDefaultInstance();
            List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, rstack));
            lines.add(Component.translatable("screen.customglint.glint_table.hint.rainbow_hex").withStyle(ChatFormatting.GRAY));
            g.setTooltipForNextFrame(font, lines, Optional.empty(), mx, my);
            return;
        }

        // Right grid (printed library): item tooltip + how the mouse buttons act on it.
        int ri = gridIndexAt(mx, my, leftPos + RGRID_X, topPos + GRID_Y, printScroll, GlintPrintedSyncPacket.CLIENT_PRINTED.size());
        if (ri >= 0) {
            ItemStack stack = GlintPrintedSyncPacket.CLIENT_PRINTED.get(ri);
            List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, stack));
            lines.add(Component.translatable("screen.customglint.glint_table.hint.load_trim").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("screen.customglint.glint_table.hint.right_merge").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable(isImportLocked(stack)
                    ? "screen.customglint.glint_table.hint.shift_delete"
                    : "screen.customglint.glint_table.hint.shift_withdraw").withStyle(ChatFormatting.GRAY));
            g.setTooltipForNextFrame(font, lines, Optional.empty(), mx, my);
            return;
        }
        // Left grid (design palette): item tooltip + how the mouse buttons act on it.
        int li = gridIndexAt(mx, my, leftPos + LGRID_X, topPos + GRID_Y, gridScroll, trims.size());
        if (li >= 0) {
            String name = trims.get(li);
            ItemStack stack = trimCache.get(name);
            if (stack != null) {
                List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, stack));
                lines.add(Component.translatable("screen.customglint.glint_table.hint.pick_main").withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("screen.customglint.glint_table.hint.right_merge").withStyle(ChatFormatting.GRAY));
                if (GlintStoredSyncPacket.CLIENT_STORED.contains(name))
                    lines.add(Component.translatable("screen.customglint.glint_table.hint.shift_copy").withStyle(ChatFormatting.GRAY));
                g.setTooltipForNextFrame(font, lines, Optional.empty(), mx, my);
            }
        }
    }

    /** Draw a stack's item tooltip with one extra gray hint line appended. */
    private void appendSlotHint(GuiGraphicsExtractor g, ItemStack stack, String hintKey, int mx, int my) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, stack));
        lines.add(Component.translatable(hintKey).withStyle(ChatFormatting.GRAY));
        g.setTooltipForNextFrame(font, lines, Optional.empty(), mx, my);
    }

    private void drawBackground(GuiGraphicsExtractor g) {
        int x = leftPos, y = topPos;

        // Skin background: window, grid recesses, preview box and the fixed slot wells.
        skin.windowPanel(g, x, y, imageWidth, imageHeight);

        // The name and glow dye wells show only while their slot is active.
        drawConditionalWell(g, GlintTableMenu.SLOT_NAME_DYE);
        drawConditionalWell(g, GlintTableMenu.SLOT_GLOW_DYE);

        // Ghost hints in table slots that have a meaningful "expected item".
        for (int i = 0; i < GlintTableMenu.TABLE_SIZE; i++) {
            if (!menu.slots.get(i).isActive()) continue;
            ItemStack ghost = ghostFor(i);
            if (!ghost.isEmpty()) drawGhostItem(g, menu.slots.get(i), ghost);
        }
    }

    /** Draws the well for a slot that is only present in some modes, while it is active. */
    private void drawConditionalWell(GuiGraphicsExtractor g, int containerSlot) {
        Slot s = menu.slots.get(containerSlot);
        if (s.isActive()) slotWell(g, leftPos + s.x - 1, topPos + s.y - 1);
    }

    /** A dye-palette ghost that cycles through the 16 vanilla dyes (~every 1.5s), offset by {@code phase}. */
    private static ItemStack cyclingDyeGhost(int phase) {
        int n = GlintTableMenu.DYE_ITEMS.length;
        int idx = (int) (((Util.getMillis() / 1500) + phase) % n);
        return new ItemStack(GlintTableMenu.DYE_ITEMS[idx]);
    }

    /** The faint "what goes here" hint item for a table slot, or EMPTY for none. */
    private ItemStack ghostFor(int containerSlot) {
        if (containerSlot >= GlintTableMenu.SLOT_DYE_START
                && containerSlot < GlintTableMenu.SLOT_DYE_START + 16) {
            return new ItemStack(GlintTableMenu.DYE_ITEMS[containerSlot - GlintTableMenu.SLOT_DYE_START]);
        }
        return switch (containerSlot) {
            case GlintTableMenu.SLOT_REDSTONE  -> new ItemStack(Items.REDSTONE);
            case GlintTableMenu.SLOT_SLIME     -> new ItemStack(Items.SLIME_BALL);
            case GlintTableMenu.SLOT_GLASS     -> new ItemStack(Items.GLASS);
            case GlintTableMenu.SLOT_GLOWSTONE -> new ItemStack(Items.GLOWSTONE_DUST);
            case GlintTableMenu.SLOT_NAMETAG   -> new ItemStack(Items.NAME_TAG);
            // SLOT_TRIM_B (slot 2) has no ghost hint, it only shows a right-clicked donor (drawDonorPreview).
            case GlintTableMenu.SLOT_TEAR      -> ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance();
            case GlintTableMenu.SLOT_TEAR_SEQ  -> ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance();
            case GlintTableMenu.SLOT_LAYER_TEAR -> ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance();
            case GlintTableMenu.SLOT_RAINBOW_DYE -> ModItems.RAINBOW_DYE.get().getDefaultInstance();
            // Any dye works in these slots, so cycle the ghost through the palette instead of a static white
            // dye. The two slots run out of phase so they don't show the same color at once.
            case GlintTableMenu.SLOT_NAME_DYE -> cyclingDyeGhost(0);
            case GlintTableMenu.SLOT_GLOW_DYE -> cyclingDyeGhost(8);
            default -> ItemStack.EMPTY;
        };
    }

    private void drawGhostItem(GuiGraphicsExtractor g, Slot slot, ItemStack ghost) {
        if (slot.hasItem()) return;
        int x = leftPos + slot.x, y = topPos + slot.y;
        g.item(ghost, x, y);
        g.fill(x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    /** The grid name of a trim stack (matches a PATTERNS entry), or null if it has no design. */
    private static String trimDesignName(ItemStack stack) {
        if (stack.getItem() instanceof GlowTrimItem) return GlowTrimItem.STORAGE_KEY;
        Identifier p = GlintTrimItem.getPattern(stack);
        return p == null ? null : gridNameFor(p);
    }

    /** Design highlighted as "main": the trim physically in the main slot wins, else the grid click. */
    private String highlightedMain() {
        String n = trimDesignName(menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem());
        return n != null ? n : selectedMain;
    }

    /** Design highlighted as "donor" in the LEFT grid: a trim physically in the merge slot wins, else a
     *  left-grid (design) ghost donor. A printed-library ghost donor is ringed in the right grid instead. */
    private String highlightedDonor() {
        String n = trimDesignName(menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem());
        if (n != null) return n;
        return (!selectedDonor.isEmpty() && !selectedDonorPrinted) ? trimDesignName(selectedDonor) : null;
    }

    /** Left grid (1): the empty-trim design palette; un-ghosts stored designs, rings the active ones. */
    private void drawTrimGrid(GuiGraphicsExtractor g, int mx, int my) {
        int gx = leftPos + LGRID_X, gy = topPos + GRID_Y;
        int total = trims.size();
        String mainName  = highlightedMain();
        String donorName = highlightedDonor();

        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int cx = gx + col * CELL, cy = gy + row * CELL;
                slotWell(g, cx - 1, cy - 1);

                int idx = (gridScroll + row) * GRID_COLS + col;
                if (idx >= total) continue;
                String name = trims.get(idx);
                g.item(trimCache.getOrDefault(name, ItemStack.EMPTY), cx, cy);
                if (!GlintStoredSyncPacket.CLIENT_STORED.contains(name)) g.fill(cx, cy, cx + 16, cy + 16, DIM_GHOST);
                if (name.equals(mainName))       border(g, cx - 1, cy - 1, 18, 18, RING_MAIN);
                else if (name.equals(donorName)) border(g, cx - 1, cy - 1, 18, 18, RING_DONOR);

                if (inBox(mx, my, cx, cy, 16, 16)) g.fill(cx, cy, cx + 16, cy + 16, HOVER_TINT);
            }
        }
        drawScrollbar(g, gx, gy, total, gridScroll);
    }

    /** The visible/usable slot count for the printed library: starts at 42, grows by rows to a 128 cap. */
    private int printedCapacity() {
        int size = GlintPrintedSyncPacket.CLIENT_PRINTED.size();
        int cells = ((size / GRID_COLS) + 2) * GRID_COLS; // enough rows for everything + one spare row
        return Math.max(GRID_ROWS * GRID_COLS, Math.min(128, cells));
    }

    /** Right grid (2): the player's printed (painted) trim library. */
    private void drawPrintedGrid(GuiGraphicsExtractor g, int mx, int my) {
        int gx = leftPos + RGRID_X, gy = topPos + GRID_Y;
        List<ItemStack> list = GlintPrintedSyncPacket.CLIENT_PRINTED;
        int cap = printedCapacity();

        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = (printScroll + row) * GRID_COLS + col;
                if (idx >= cap) continue; // beyond the grown capacity: no slot here
                int cx = gx + col * CELL, cy = gy + row * CELL;
                slotWell(g, cx - 1, cy - 1);

                if (idx < list.size()) {
                    ItemStack s = list.get(idx);
                    g.item(s, cx, cy);
                    // An imported-but-not-yet-crafted trim is dimmed and can't be withdrawn until the player
                    // prints a matching trim (see GlintTableMenu#storePrinted).
                    if (isImportLocked(s)) g.fill(cx, cy, cx + 16, cy + 16, DIM_PREVIEW);
                    if (!selectedPrinted.isEmpty() && ItemStack.isSameItemSameComponents(s, selectedPrinted))
                        border(g, cx - 1, cy - 1, 18, 18, RING_MAIN);
                    else if (selectedDonorPrinted && ItemStack.isSameItemSameComponents(s, selectedDonor))
                        border(g, cx - 1, cy - 1, 18, 18, RING_DONOR);
                }
                if (inBox(mx, my, cx, cy, 16, 16)) g.fill(cx, cy, cx + 16, cy + 16, HOVER_TINT);
            }
        }
        drawScrollbar(g, gx, gy, cap, printScroll);
    }

    /** True when a printed-library entry is an imported trim the player hasn't crafted yet (dimmed, locked). */
    private static boolean isImportLocked(ItemStack s) {
        return Boolean.TRUE.equals(s.get(ModComponents.IMPORT_LOCKED.get()));
    }

    // ── Import picker ─────────────────────────────────────────────────────────

    // Picker geometry, shared by the draw and the click handling. The panel is horizontally centred and starts
    // below the window title; its height is the row list plus the search bar strip above it (20) and 2px of
    // bottom padding. impListY() is where the first row's top edge sits.
    private int impPickerX() { return leftPos + (imageWidth - IMP_PW) / 2; }
    private int impPickerY() { return topPos + 24; }
    private int impPickerH() { return IMPORT_ROWS * IMPORT_ROW_H + 22; }
    private int impListY() { return impPickerY() + 20; }

    /** Toggle the import picker; on open, (re)scan the config dir and focus the search box. */
    private void toggleImportPicker() {
        showImportPicker = !showImportPicker;
        importScroll = 0;
        if (showImportPicker) {
            scanImportConfigs();
            if (importSearchBox != null) {
                importSearchBox.setValue("");
                setFocused(importSearchBox);
                importSearchBox.setFocused(true);
            }
        } else if (importSearchBox != null) {
            importSearchBox.setFocused(false);
        }
    }

    /** Rebuild the import list: this client's personal blueprints ({@code config/glint-and-glamour/trims/*.json},
     *  cross-world) plus, on a dedicated server, the shared server blueprints synced from the server. In
     *  single-player the server source is skipped; the integrated server reads the very same directory this
     *  local scan does, so listing both would double every entry. */
    private void scanImportConfigs() {
        importAll.clear();
        try {
            Path dir = ModConfigPaths.TRIMS_DIR;
            if (Files.exists(dir)) {
                // try-with-resources: Files.list holds an open directory handle that must be closed.
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> p.toString().endsWith(".json"))
                          .map(p -> p.getFileName().toString().replace(".json", ""))
                          .sorted()
                          .forEach(n -> importAll.add(new ImpEntry(n, false)));
                }
            }
        } catch (Exception ignored) {
            // No config dir / unreadable: leave the personal list empty.
        }
        if (!Minecraft.getInstance().hasSingleplayerServer()) {
            for (String n : GlintServerBlueprintsSyncPacket.CLIENT_SERVER_BLUEPRINTS.keySet())
                importAll.add(new ImpEntry(n, true));
        }
        filterImport(importSearchBox != null ? importSearchBox.getValue() : "");
    }

    private void filterImport(String query) {
        String lq = query == null ? "" : query.toLowerCase(Locale.ROOT);
        importFiltered = lq.isEmpty() ? new ArrayList<>(importAll)
                : importAll.stream().filter(e -> e.name().toLowerCase(Locale.ROOT).contains(lq)).collect(Collectors.toList());
        importScroll = Math.max(0, Math.min(importScroll, Math.max(0, importFiltered.size() - IMPORT_ROWS)));
    }

    /** True when the local player may manage the server's shared blueprints: single-player (full access) or an
     *  operator (permission level 2). Personal client blueprints are always managed by their own client. */
    private static boolean canManageServerTrims() {
        Minecraft mc = Minecraft.getInstance();
        return mc.hasSingleplayerServer()
                || (mc.player != null && mc.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }

    /** Import a chosen blueprint: a personal client one reads its local file; a shared server one uses the
     *  JSON synced from the server. Either way the parsed trim is sent to the server as a locked build target. */
    private void importSelected(ImpEntry entry) {
        String json;
        try {
            if (entry.server()) {
                json = GlintServerBlueprintsSyncPacket.CLIENT_SERVER_BLUEPRINTS.get(entry.name());
                if (json == null) return;
            } else {
                json = new String(Files.readAllBytes(ModConfigPaths.trimFile(entry.name())));
            }
        } catch (Exception ignored) {
            return; // unreadable file
        }
        sendImportFromJson(json);
        showImportPicker = false;
        if (importSearchBox != null) importSearchBox.setFocused(false);
    }

    /** Parse a blueprint's JSON into layers + glow + name and send it to the server, which adds it to the
     *  printed library as a locked (dimmed) build target. Silently ignores malformed JSON. */
    private void sendImportFromJson(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            List<CustomGlint.Layer> layers = new ArrayList<>();
            if (obj.has("layers")) {
                JsonArray arr = obj.getAsJsonArray("layers");
                for (int i = 0; i < Math.min(arr.size(), MAX_LAYERS); i++) {
                    JsonObject lo = arr.get(i).getAsJsonObject();
                    Identifier design = Identifier.parse(lo.get("design").getAsString());
                    List<Integer> colors = new ArrayList<>();
                    for (JsonElement e : lo.getAsJsonArray("colors"))
                        colors.add((int) Long.parseLong(e.getAsString().replace("0x", ""), 16));
                    int[] carr = colors.stream().mapToInt(Integer::intValue).toArray();
                    float speed = lo.has("speed") ? lo.get("speed").getAsFloat() : 1.0f;
                    boolean interp = !lo.has("interpolate") || lo.get("interpolate").getAsBoolean();
                    float pscale = lo.has("patternScale") ? lo.get("patternScale").getAsFloat() : 1.0f;
                    boolean sim = !lo.has("simultaneous") || lo.get("simultaneous").getAsBoolean();
                    int scroll = lo.has("scroll") ? lo.get("scroll").getAsInt() : CustomGlint.SCROLL_E;
                    float offset = lo.has("offset") ? lo.get("offset").getAsFloat() : 0.0f;
                    layers.add(new CustomGlint.Layer(design, carr, speed, interp, pscale, sim, scroll, offset));
                }
            }
            if (layers.isEmpty()) return;

            boolean glowing = obj.has("glowing") && obj.get("glowing").getAsBoolean();
            // Manual glow colors, present only in table-saved trims (the wand export omits them); optional so
            // older / wand-exported files still load.
            int[] glowColors = new int[0];
            if (obj.has("glowColors")) {
                List<Integer> gc = new ArrayList<>();
                for (JsonElement e : obj.getAsJsonArray("glowColors"))
                    gc.add((int) Long.parseLong(e.getAsString().replace("0x", ""), 16));
                glowColors = gc.stream().mapToInt(Integer::intValue).toArray();
            }
            String displayName = obj.has("displayName") ? obj.get("displayName").getAsString() : "";
            int nameColor = 0xFFFFFFFF;
            if (obj.has("nameColor")) {
                int rgb = (int) Long.parseLong(obj.get("nameColor").getAsString().replace("0x", ""), 16) & 0xFFFFFF;
                nameColor = (rgb << 8) | 0xFF; // packed as (rgb << 8) | alpha, mirroring the wand editor
            }
            ClientPacketDistributor.sendToServer(new GlintImportPacket(
                    layers.toArray(new CustomGlint.Layer[0]), glowing, glowColors, displayName, nameColor));
        } catch (Exception ignored) {
            // Malformed JSON: ignore.
        }
    }

    /** Right-click Import: save the current preview build (every layer, its colors, and all modifiers) to
     *  {@code config/glint-and-glamour/trims/<name>.json}, the same format the Import list and wand editor read.
     *  This is the "design a trim you don't own and come back to it later" half of the feature: you can
     *  build and save anything freely; it just can't be printed until you own + pay for it. */
    private void saveCurrentAsImport() {
        ItemStack src = previewSource();
        CustomGlint.Data data = src.getItem() instanceof GlintTrimItem ? CustomGlint.read(src) : null;
        if (data == null || data.layers().length == 0) {
            actionBar(Component.translatable("screen.customglint.glint_table.import_save_empty"));
            return;
        }
        try {
            Path dir = ModConfigPaths.TRIMS_DIR;
            Files.createDirectories(dir);

            JsonObject root = new JsonObject();
            root.addProperty("glowing", CustomGlint.isGlowing(src));
            int[] glowColors = CustomGlint.getGlowColors(src);
            if (glowColors.length > 0) {
                JsonArray gc = new JsonArray();
                for (int c : glowColors) gc.add(String.format("0x%08X", c));
                root.add("glowColors", gc);
            }
            if (src.has(DataComponents.CUSTOM_NAME)) {
                Component hover = src.getHoverName();
                root.addProperty("displayName", hover.getString());
                TextColor color = hover.getStyle().getColor();
                if (color != null) root.addProperty("nameColor", String.format("0x%06X", color.getValue() & 0xFFFFFF));
            }

            JsonArray layersArray = new JsonArray();
            for (CustomGlint.Layer layer : data.layers()) {
                JsonObject lo = new JsonObject();
                lo.addProperty("design", layer.design().toString());
                JsonArray colors = new JsonArray();
                for (int c : layer.colors()) colors.add(String.format("0x%08X", c));
                lo.add("colors", colors);
                lo.addProperty("speed", layer.speed());
                lo.addProperty("interpolate", layer.interpolate());
                lo.addProperty("patternScale", layer.patternScale());
                lo.addProperty("simultaneous", layer.simultaneous());
                lo.addProperty("scroll", layer.scrollDir());
                lo.addProperty("offset", layer.scrollOffset());
                layersArray.add(lo);
            }
            root.add("layers", layersArray);

            String base = sanitizeFileName(!trimName.isEmpty() ? trimName : designLabel(data.layers()[0].design()));
            Path file = uniqueTrimFile(dir, base);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                gson.toJson(root, writer);
            }
            String fn = file.getFileName().toString().replace(".json", "");
            actionBar(Component.translatable("screen.customglint.glint_table.import_saved", fn));
        } catch (Exception e) {
            actionBar(Component.translatable("screen.customglint.glint_table.import_save_failed"));
        }
    }

    // Transient "saved / couldn't save" confirmation, shown in-screen for a couple seconds (an actionbar
    // overlay would be hidden behind the open GUI).
    private Component importMsg = null;
    private long importMsgUntil = 0;

    private void actionBar(Component c) {
        importMsg = c;
        importMsgUntil = Util.getMillis() + 2500;
    }

    private void drawImportMsg(GuiGraphicsExtractor g) {
        if (importMsg == null || Util.getMillis() >= importMsgUntil) return;
        int tw = font.width(importMsg);
        int cx = leftPos + imageWidth / 2, ty = topPos + 18;
        g.fill(cx - tw / 2 - 3, ty - 2, cx + tw / 2 + 3, ty + 10, 0xE0000000);
        g.text(font, importMsg, cx - tw / 2, ty, 0xFFFFFFFF, false);
    }

    /** A filesystem-safe trim file base name: lowercased, non-[a-z0-9_-] runs collapsed to '_', trimmed. */
    private static String sanitizeFileName(String s) {
        String cleaned = s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "trim" : cleaned;
    }

    /** {@code base.json}, or {@code base_2.json}, {@code base_3.json}… so saving never clobbers a prior trim. */
    private static Path uniqueTrimFile(Path dir, String base) {
        Path p = dir.resolve(base + ".json");
        for (int n = 2; Files.exists(p); n++) p = dir.resolve(base + "_" + n + ".json");
        return p;
    }

    private static String designLabel(Identifier design) {
        if (design.equals(CustomGlint.VANILLA)) return "vanilla";
        String nm = GlintTrimItem.extractPatternName(design);
        return nm == null ? "trim" : nm;
    }

    // The picker is a modal overlay, so it keeps its own fixed dark palette rather than the active skin's: it
    // has to stay legible on top of whichever background the skin painted underneath.
    private void renderImportPicker(GuiGraphicsExtractor g, int mx, int my, float dt) {
        int ox = impPickerX(), oy = impPickerY(), h = impPickerH();
        g.fill(ox - 1, oy - 1, ox + IMP_PW + 1, oy + h + 1, 0xFF666666); // 1px border
        g.fill(ox, oy, ox + IMP_PW, oy + h, 0xEE111111);                 // near-opaque panel

        if (importSearchBox != null) {
            importSearchBox.setX(ox + 2);
            importSearchBox.setY(oy + 3);
            importSearchBox.setWidth(IMP_PW - 4);
            importSearchBox.setVisible(true);
            importSearchBox.extractRenderState(g, mx, my, dt);
        }

        int listY = impListY(), sbX = ox + IMP_PW - 5;
        if (importFiltered.isEmpty()) {
            g.text(font, Component.translatable("screen.customglint.glint_table.import_empty"), ox + 4, listY + 2, 0xFF888888, false);
        }
        boolean canManageServer = canManageServerTrims();
        for (int i = 0; i < IMPORT_ROWS && importScroll + i < importFiltered.size(); i++) {
            ImpEntry e = importFiltered.get(importScroll + i);
            int ry = listY + i * IMPORT_ROW_H;
            boolean hovered = inBox(mx, my, ox, ry, sbX - ox, IMPORT_ROW_H);
            if (hovered) g.fill(ox, ry, sbX, ry + IMPORT_ROW_H, 0x40FFFFFF);
            // Server (shared) blueprints get a small padlock and a gold tint; personal client ones stay plain.
            int textX = ox + 4;
            if (e.server()) {
                drawLockIcon(g, ox + 4, ry + 3, 0xFFE0C060);
                textX = ox + 12;
            }
            g.text(font, Component.literal(e.name()), textX, ry + 2, e.server() ? 0xFFE0C060 : 0xFFDDDDDD, false);
            // Trash on the far right of the hovered row: personal rows always; server rows only for ops/single-player.
            if (hovered && (!e.server() || canManageServer)) {
                boolean onTrash = inBox(mx, my, sbX - IMP_TRASH_W, ry, IMP_TRASH_W, IMPORT_ROW_H);
                GuiHelpers.drawTrashIcon(g, sbX - IMP_TRASH_W + 2, ry + 3, onTrash ? 0xFFFF5555 : 0xFFB05050);
            }
        }

        if (importFiltered.size() > IMPORT_ROWS) {
            int trackH = IMPORT_ROWS * IMPORT_ROW_H;
            g.fill(sbX, listY, sbX + SCROLLBAR_W, listY + trackH, 0xFF2A2A2A);
            int thumbH = Math.max(SCROLLBAR_MIN_THUMB, trackH * IMPORT_ROWS / importFiltered.size());
            int thumbY = listY + (int) ((trackH - thumbH) * (float) importScroll / (importFiltered.size() - IMPORT_ROWS));
            g.fill(sbX, thumbY, sbX + SCROLLBAR_W, thumbY + thumbH, 0xFF888888);
        }
    }

    /** Tiny 5×7 padlock glyph marking a shared server blueprint. Origin = top-left. */
    private static void drawLockIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x + 1, y, x + 4, y + 1, color);         // shackle top
        g.fill(x + 1, y + 1, x + 2, y + 3, color);     // shackle left
        g.fill(x + 3, y + 1, x + 4, y + 3, color);     // shackle right
        g.fill(x, y + 3, x + 5, y + 7, color);         // body
    }

    /** Delete a personal client blueprint: remove its {@code config/glint-and-glamour/trims/<name>.json}, then rescan. */
    private void deleteImport(String name) {
        try {
            Files.deleteIfExists(ModConfigPaths.trimFile(name));
        } catch (Exception ignored) {
            // Locked/unremovable file: leave it; the rescan below simply keeps showing it.
        }
        scanImportConfigs();
    }

    /** Delete a shared server blueprint (op/single-player only): ask the server to remove it, drop it from the
     *  local mirror for instant feedback, then rescan. The server's authoritative re-sync follows. */
    private void deleteServerBlueprint(String name) {
        ClientPacketDistributor.sendToServer(new GlintDeleteServerBlueprintPacket(name));
        GlintServerBlueprintsSyncPacket.CLIENT_SERVER_BLUEPRINTS.remove(name);
        scanImportConfigs();
    }

    // Scrollbar geometry, shared by the draw, the hit test and the drag-to-row mapping so they can't drift
    // apart. The thumb floors at 8px because a proportional thumb on a long list gets too small to grab.
    private static final int SCROLLBAR_W = 4, SCROLLBAR_MIN_THUMB = 8;

    /** How many grid rows {@code total} entries fill. */
    private static int gridRowCount(int total) {
        return (total + GRID_COLS - 1) / GRID_COLS;
    }

    /** The highest scroll row for {@code total} entries (0 when everything already fits). */
    private static int maxScrollRow(int total) {
        return Math.max(0, gridRowCount(total) - GRID_ROWS);
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int gx, int gy, int total, int scroll) {
        int maxRow = maxScrollRow(total);
        if (maxRow <= 0) return;
        int trackX = gx + GRID_COLS * CELL + 1, trackH = GRID_ROWS * CELL;
        g.fill(trackX, gy, trackX + SCROLLBAR_W, gy + trackH, SLOT_DARK);
        int thumbH = Math.max(SCROLLBAR_MIN_THUMB, trackH * GRID_ROWS / gridRowCount(total));
        int thumbY = gy + (int) ((trackH - thumbH) * (float) scroll / maxRow);
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, GUI_SHADOW);
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W - 1, thumbY + thumbH - 1, GUI_FACE); // lit top-left bevel
    }

    /** True if (mx,my) is on a grid's scrollbar track. gx/gy are the grid's top-left. */
    private static boolean onScrollbar(double mx, double my, int gx, int gy, int total) {
        if (maxScrollRow(total) <= 0) return false; // nothing to scroll
        return inBox(mx, my, gx + GRID_COLS * CELL + 1, gy, SCROLLBAR_W, GRID_ROWS * CELL);
    }

    /** Map a mouse Y on the track to a scroll row (thumb-centred, clamped), matching drawScrollbar's geometry. */
    private static int scrollFromMouse(double my, int gy, int total) {
        int maxRow = maxScrollRow(total);
        if (maxRow <= 0) return 0;
        int trackH = GRID_ROWS * CELL;
        int thumbH = Math.max(SCROLLBAR_MIN_THUMB, trackH * GRID_ROWS / gridRowCount(total));
        return rowFromTrack(my, gy, trackH, thumbH, maxRow);
    }

    /** Import-picker equivalent of {@link #scrollFromMouse}: map a mouse Y on the Import scrollbar track to a
     *  row, matching renderImportPicker's thumb geometry. */
    private int importScrollFromMouse(double my) {
        int listY = impListY(), trackH = IMPORT_ROWS * IMPORT_ROW_H;
        int total = importFiltered.size(), max = Math.max(0, total - IMPORT_ROWS);
        if (max <= 0) return 0;
        int thumbH = Math.max(SCROLLBAR_MIN_THUMB, trackH * IMPORT_ROWS / total);
        return rowFromTrack(my, listY, trackH, thumbH, max);
    }

    /** Mouse Y → row for a scrollbar drag: the grab point is the thumb's centre, and the travel is the track
     *  minus the thumb, so the last row lands exactly when the thumb bottoms out. */
    private static int rowFromTrack(double my, int trackY, int trackH, int thumbH, int maxRow) {
        float f = (float) ((my - trackY - thumbH / 2.0) / (trackH - thumbH));
        return Math.round(Math.max(0f, Math.min(1f, f)) * maxRow);
    }

    /** Selected-but-not-placed preview ghosted into the empty main slot. Drawn solid when the
     *  selection is owned (a printed-library trim, or a stored design); dimmed only when unowned. */
    private void drawMainPreview(GuiGraphicsExtractor g) {
        Slot main = menu.slots.get(GlintTableMenu.SLOT_TRIM);
        if (main.hasItem()) return;
        // The main slot shows the trim as currently COMMITTED: all its committed layers with the active
        // layer's mods, but not the uncommitted merge donor (that joins only in the preview box until [+]
        // commits it). mainSlotSource() is exactly that (a donor-free build). Dimmed while the base isn't owned.
        ItemStack sel = mainSlotSource();
        if (sel.isEmpty()) return;
        int x = leftPos + main.x, y = topPos + main.y;
        g.item(sel, x, y);
        boolean owned = !selectedPrinted.isEmpty()
                || (selectedMain != null && GlintStoredSyncPacket.CLIENT_STORED.contains(selectedMain));
        if (!owned) g.fill(x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    /** Outline the two trim slots: slot 1 (main) green, slot 2 (the right-click donor / layer slot) gold. */
    private void drawMainSlotRings(GuiGraphicsExtractor g) {
        Slot s1 = menu.slots.get(GlintTableMenu.SLOT_TRIM);
        Slot s2 = menu.slots.get(GlintTableMenu.SLOT_TRIM_B);
        border(g, leftPos + s1.x - 1, topPos + s1.y - 1, 18, 18, RING_MAIN);
        border(g, leftPos + s2.x - 1, topPos + s2.y - 1, 18, 18, RING_DONOR);
    }

    /** Right-click-selected donor ghosted into the empty merge slot; dimmed when unowned. */
    private void drawDonorPreview(GuiGraphicsExtractor g) {
        Slot s = menu.slots.get(GlintTableMenu.SLOT_TRIM_B);
        if (s.hasItem() || selectedDonor.isEmpty()) return;
        int x = leftPos + s.x, y = topPos + s.y;
        g.item(selectedDonor, x, y);
        if (!donorOwned()) g.fill(x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    // ── Layer indicator strip ───────────────────────────────────────────────────

    /** A layer chip: x is screen-relative-to-window; kind 0=lower,1=active,2=upper,3=add; index into list. */
    private record Chip(int x, int kind, int index) {}

    /** The chip layout in render order: lowerLayers, the active layer (if valid), upperLayers, then [+]. */
    private List<Chip> layerChips() {
        List<Chip> chips = new ArrayList<>();
        int i = 0;
        // A Glow Trim is a single, non-layerable trim: it gets exactly the one layer-1 chip and no [+] add chip
        // (it can never carry lower/upper layers). A Glint Trim gets its layer stack + the add chip as usual.
        boolean glow = activeTrim().getItem() instanceof GlowTrimItem;
        for (int k = 0; k < lowerLayers.size(); k++) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 0, k));
        if (glow || activeTrim().getItem() instanceof GlintTrimItem) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 1, 0));
        for (int k = 0; k < upperLayers.size(); k++) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 2, k));
        if (i < MAX_LAYERS && !glow) chips.add(new Chip(LAYER_STRIP_X + i * LAYER_CELL, 3, 0)); // [+] only while under the cap
        return chips;
    }

    /** An icon stack for a committed layer, carrying its full transforms (design, colors, speed, scale,
     *  scroll, simultaneous) so the chip animates exactly like the layer does, not just design + colors. */
    private ItemStack layerIcon(CustomGlint.Layer l) {
        ItemStack cached = layerIconCache.get(l);
        if (cached != null) return cached;
        ItemStack s = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(s, l.design());          // base sprite + CustomModelData
        CustomGlint.write(s, new CustomGlint.Layer[]{ renderLayer(l) }); // colorless layer → white, like a blank trim
        layerIconCache.put(l, s);
        return s;
    }

    /** Glint-only chip icon for the ACTIVE layer. {@link #captureActive()} mints a fresh {@link CustomGlint.Layer}
     *  every call, so feeding it to the identity-keyed {@link #layerIconCache} would add one never-reused entry
     *  per frame, a slow leak while the table stays open. Build it once per draw pass via the frame memo and
     *  keep it out of layerIconCache (whose entries are the stable lower/upper-layer instances that DO reuse). */
    private ItemStack activeLayerIcon() {
        if (frameMemo && frameActiveIcon != null) return frameActiveIcon;
        CustomGlint.Layer active = captureActive();
        ItemStack result;
        if (active == null) {
            result = activeTrim(); // no design yet, fall back to the live active stack (already frame-memoized)
        } else {
            result = new ItemStack(ModItems.GLINT_TRIM.get());
            GlintTrimItem.setPattern(result, active.design());          // base sprite + CustomModelData
            CustomGlint.write(result, new CustomGlint.Layer[]{ active }); // glint-only (no glow halo)
        }
        if (frameMemo) frameActiveIcon = result;
        return result;
    }

    /** Draw the layer chips above the preview: a design icon per layer (active ringed) plus a [+] add. The
     *  strip is always shown; chips render only for layers that exist (active layer plus committed ones), and
     *  the [+] add chip is always present. Layer tears are required only at print, not to preview. */
    private void drawLayerStrip(GuiGraphicsExtractor g, int mx, int my) {
        for (Chip c : layerChips()) {
            int x = leftPos + c.x(), y = topPos + LAYER_STRIP_Y;
            if (c.kind() == 3) { // add chip
                drawAddChip(g, x, y, LAYER_ICON, canAddLayer(), mx, my);
                continue;
            }
            g.fill(x, y, x + LAYER_ICON, y + LAYER_ICON, SLOT_DARK); // chip backing (exact cell)
            // Shards show only each layer's DESIGN glint, never the glow halo, the active shard is built
            // from its captured layer (glow-free) just like the committed ones, not the glowing activeTrim().
            ItemStack icon = c.kind() == 0 ? layerIcon(lowerLayers.get(c.index()))
                    : c.kind() == 2 ? layerIcon(upperLayers.get(c.index()))
                    : activeLayerIcon();
            drawCellItem(g, icon, x, y, LAYER_ICON);
            shardBevel(g, x, y, LAYER_ICON); // menu-style sunken frame
            // While glow is focused the colour strip edits the glow colours, not this layer, so drop the active
            // layer's selection ring so it reads as unfocused (the glow mode button carries the highlight).
            if (c.kind() == 1 && !glowFocused) border(g, x, y, LAYER_ICON, LAYER_ICON, RING_MAIN); // active layer
            if (inBox(mx, my, x, y, LAYER_ICON, LAYER_ICON))
                g.fill(x, y, x + LAYER_ICON, y + LAYER_ICON, HOVER_TINT);
        }
    }

    // ── Color-shard strip (active layer's colors, mirrors the layer strip but below the preview) ──────

    /** The colour shards belong to the active layer, so the strip only shows them while a design is being
     *  previewed; with no preview it collapses to just the "+" box, mirroring the layer strip hiding its
     *  layer-1 chip until there's a design. */
    private boolean colorShardsVisible() {
        ItemStack a = activeTrim();
        // A Glow Trim can only take colors (its glow colours), so its shard strip shows too.
        return a.getItem() instanceof GlintTrimItem || a.getItem() instanceof GlowTrimItem;
    }

    private void drawColorStrip(GuiGraphicsExtractor g, int mx, int my) {
        List<List<Integer>> shards = editShards();
        int n = colorShardsVisible() ? Math.min(shards.size(), 8) : 0;
        for (int k = 0; k < n; k++) {
            int x = leftPos + COLOR_STRIP_X + k * COLOR_CELL, y = topPos + COLOR_STRIP_Y;
            List<Integer> shard = shards.get(k);
            int rgb = mixRgb(shard);
            if (rgb < 0 && isCustomShard(shard)) {       // rainbow chosen, no hex yet → show the rainbow dye icon
                g.fill(x, y, x + COLOR_ICON, y + COLOR_ICON, SLOT_DARK);
                drawCellItem(g, ModItems.RAINBOW_DYE.get().getDefaultInstance(), x, y, COLOR_ICON);
            } else {
                g.fill(x, y, x + COLOR_ICON, y + COLOR_ICON, rgb < 0 ? COLOR_UNSET : 0xFF000000 | rgb);
            }
            shardBevel(g, x, y, COLOR_ICON);                                              // menu-style sunken frame
            if (k == selectedColorIdx) border(g, x, y, COLOR_ICON, COLOR_ICON, RING_MAIN);
            if (inBox(mx, my, x, y, COLOR_ICON, COLOR_ICON))
                g.fill(x, y, x + COLOR_ICON, y + COLOR_ICON, HOVER_TINT);
        }
        // "+" add-color box after the shards, hidden while the hex box is open so it doesn't paint over it
        // (the hex EditBox is drawn last, at the end of extractContents). With no preview it's the only thing
        // on the strip and shows disabled/red (like the layer strip's [+]), since there's no layer to add a
        // colour to yet.
        if (n < 8 && !hexOpen) {
            int x = leftPos + COLOR_STRIP_X + n * COLOR_CELL, y = topPos + COLOR_STRIP_Y;
            drawAddChip(g, x, y, COLOR_ICON, colorShardsVisible(), mx, my);
        }
    }

    /** The square "+" chip both strips end with: a raised button face that lights on hover and greys out
     *  when there is nothing to add. */
    private void drawAddChip(GuiGraphicsExtractor g, int x, int y, int size, boolean enabled, int mx, int my) {
        boolean hover = enabled && inBox(mx, my, x, y, size, size);
        raisedPanel(g, x, y, size, size, hover ? BTN_HOVER : (enabled ? GUI_FACE : BTN_DISABLED));
        centered(g, "+", x + size / 2, y + 2, enabled ? LABEL_HDR : COST_BAD);
    }

    /** Draw an item scaled down from its native 16px into a {@code size}px strip cell. */
    private static void drawCellItem(GuiGraphicsExtractor g, ItemStack stack, int x, int y, int size) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(size / 16f, size / 16f);
        g.item(stack, 0, 0);
        pose.popMatrix();
    }

    /** Color-strip clicks: left-click a shard selects it (the dye bar then highlights/recolors it),
     *  right-click removes it, and the trailing "+" adds a new (unset) shard. Returns true if hit. */
    private boolean colorStripClick(double mx, double my, int button) {
        if (!colorShardsVisible()) return false; // no preview: the strip is just an inert "+", nothing to click
        List<List<Integer>> shards = editShards();
        int y = topPos + COLOR_STRIP_Y;
        int n = Math.min(shards.size(), 8);
        for (int k = 0; k < n; k++) {
            if (!inBox(mx, my, leftPos + COLOR_STRIP_X + k * COLOR_CELL, y, COLOR_ICON, COLOR_ICON)) continue;
            if (button == InputConstants.MOUSE_BUTTON_LEFT && hasShiftDown() && selectedColorIdx >= 0 && selectedColorIdx != k
                    && selectedColorIdx < shards.size()) { // shift-left-click another shard: swap the two colours
                Collections.swap(shards, selectedColorIdx, k);
                selectedColorIdx = k; // selection follows the colour we moved
                closeHex();
            } else if (button == InputConstants.MOUSE_BUTTON_RIGHT && selectedColorIdx == k) { // right-click the SELECTED shard: delete it (select first)
                shards.remove(k);
                selectedColorIdx = -1;
                closeHex();
            } else if (button != InputConstants.MOUSE_BUTTON_RIGHT && selectedColorIdx == k) { // left re-click the selected shard
                // A rainbow/custom shard toggles the hex entry box; a normal shard does nothing (no unselect).
                if (isCustomShard(shards.get(k))) { if (hexOpen) closeHex(); else openHex(k); }
            } else {                                 // any click on an unselected shard selects it, never unselects
                selectedColorIdx = k;
                closeHex();
            }
            return true;
        }
        // "+" box: add a new unset shard, selected (hidden while editing hex).
        if (n < 8 && !hexOpen && inBox(mx, my, leftPos + COLOR_STRIP_X + n * COLOR_CELL, y, COLOR_ICON, COLOR_ICON)) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) { shards.add(new ArrayList<>()); selectedColorIdx = n; closeHex(); }
            return true;
        }
        return false;
    }

    /** Open the hex box for a rainbow/custom shard, seeded with its current hex (empty if none yet). */
    private void openHex(int k) {
        hexMode = HEX_SHARD;
        hexShardIdx = k;
        int v = editShards().get(k).get(0);
        openHexWith((v & CUSTOM_FLAG) != 0 ? String.format("%06X", v & 0xFFFFFF) : "");
    }

    /** Open the hex box for the custom glow ({@link #HEX_GLOW}) or name ({@link #HEX_NAME}) colour. */
    private void openHexColor(int mode) {
        hexMode = mode;
        int cur = mode == HEX_GLOW ? glowHex : nameHex;
        openHexWith(cur >= 0 ? String.format("%06X", cur) : "");
    }

    private void openHexWith(String val) {
        hexOpen = true;
        hexBox.setValue(val);
        hexBox.setFocused(true);
        setFocused(hexBox);
    }

    private void closeHex() {
        hexOpen = false;
        hexShardIdx = -1;
        hexMode = HEX_SHARD;
        if (hexBox != null) hexBox.setFocused(false);
    }

    /** Apply the hex box's text (a valid 6-digit hex) to whatever it's editing: a shard's custom colour, or
     *  the custom glow / name colour. */
    private void applyHex(String s) {
        if (!hexOpen) return;
        String hex = s.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 6) return;
        int rgb = Integer.parseInt(hex, 16) & 0xFFFFFF;
        switch (hexMode) {
            case HEX_GLOW -> glowHex = rgb;
            case HEX_NAME -> nameHex = rgb;
            default -> {
                List<List<Integer>> shards = editShards();
                if (hexShardIdx < 0 || hexShardIdx >= shards.size()) return;
                List<Integer> shard = shards.get(hexShardIdx);
                shard.clear();
                shard.add(CUSTOM_FLAG | rgb);
            }
        }
    }

    /** Whether the rainbow dye occupies a given color slot (glow / name). */
    private boolean rainbowInSlot(int slotConst) {
        return menu.slots.get(slotConst).getItem().getItem() == ModItems.RAINBOW_DYE.get();
    }

    private boolean canAddLayer() {
        // Ownership is NOT required to stage a layer, you can build a full trim from any designs; the print
        // gates each committed layer on ownership (layerValid + server ownsDesign) and reports it as an issue.
        if (activeTrim().getItem() instanceof GlowTrimItem) return false; // a Glow Trim can never be layered
        CustomGlint.Layer[] dl = donorLayers();
        return dl.length > 0
                && totalLayers() + dl.length <= MAX_LAYERS;
    }

    /** Every layer of the trim staged in slot 2 (the donor / layer slot), or empty if it has no design. A
     *  multi-layer donor contributes ALL its layers (a 3-layer donor adds 3 layers), one tear per layer. */
    private CustomGlint.Layer[] donorLayers() {
        ItemStack d = donorStack();
        if (!(d.getItem() instanceof GlintTrimItem)) return new CustomGlint.Layer[0];
        CustomGlint.Data dd = CustomGlint.read(d);
        if (dd != null && dd.layers().length > 1) return dd.layers(); // multi-layer donor: real per-layer data
        Identifier design = GlintTrimItem.getPattern(d);
        if (design == null) return new CustomGlint.Layer[0];
        // Single layer: use the trim's AUTHORED colors (empty stays empty), not the render Data, whose
        // writeColors() defaults an empty non-chromatic color list to white. That synthetic white would show
        // as a rainbow/custom shard when the merged layer is edited; a colorless donor must stay colorless.
        // Keep the render layer's timing/flags/seed when present.
        int[] colors = GlintTrimItem.getColors(d);
        CustomGlint.Layer r = dd != null && dd.layers().length == 1 ? dd.layers()[0] : null;
        if (r != null) return new CustomGlint.Layer[]{ new CustomGlint.Layer(design, colors, r.speed(),
                r.interpolate(), r.patternScale(), r.simultaneous(), r.scrollDir(), r.scrollOffset(), r.seed()) };
        return new CustomGlint.Layer[]{ new CustomGlint.Layer(design, colors, GlintTrimItem.getSpeed(d), true,
                GlintTrimItem.getScale(d), false, GlintTrimItem.getScrollDir(d), GlintTrimItem.getScrollOffset(d)) };
    }

    /** Promote the slot-2 trim's full layer stack on top of the active main, then clear the slot-2 selection.
     *  The active layer in slot 1 is left untouched. Adding a layer is free (it only stacks the preview); the
     *  layer tears it will cost are checked and consumed at print, not here. */
    private void addLayer() {
        CustomGlint.Layer[] dl = donorLayers();
        if (dl.length == 0 || totalLayers() + dl.length > MAX_LAYERS) return;
        Collections.addAll(upperLayers, dl);
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
    }

    /** Pull a committed layer back into the controls for editing, keeping render order intact. */
    private void editLayer(int kind, int k) {
        CustomGlint.Layer cur = captureActive();
        if (kind == 0) { // lower[k] → active
            CustomGlint.Layer clicked = lowerLayers.get(k);
            List<CustomGlint.Layer> head = new ArrayList<>(lowerLayers.subList(0, k));
            List<CustomGlint.Layer> tail = new ArrayList<>(lowerLayers.subList(k + 1, lowerLayers.size()));
            List<CustomGlint.Layer> newUpper = new ArrayList<>(tail);
            if (cur != null) newUpper.add(cur);
            newUpper.addAll(upperLayers);
            lowerLayers.clear(); lowerLayers.addAll(head);
            upperLayers.clear(); upperLayers.addAll(newUpper);
            loadControlsFromLayer(clicked);
        } else if (kind == 2) { // upper[k] → active
            CustomGlint.Layer clicked = upperLayers.get(k);
            List<CustomGlint.Layer> head = new ArrayList<>(upperLayers.subList(0, k));
            List<CustomGlint.Layer> tail = new ArrayList<>(upperLayers.subList(k + 1, upperLayers.size()));
            List<CustomGlint.Layer> newLower = new ArrayList<>(lowerLayers);
            if (cur != null) newLower.add(cur);
            newLower.addAll(head);
            lowerLayers.clear(); lowerLayers.addAll(newLower);
            upperLayers.clear(); upperLayers.addAll(tail);
            loadControlsFromLayer(clicked);
        }
    }

    /** Swap the active (selected) layer with a committed one, keeping the SAME layer active. The two chips
     *  exchange positions in the stack; the active editor state is untouched (it just moves), mirroring the
     *  color-shard swap. {@code kind}/{@code k} identify the clicked committed chip (0 = lower, 2 = upper). */
    private void swapLayers(int kind, int k) {
        CustomGlint.Layer active = captureActive();
        if (active == null) return; // no active layer to swap
        List<CustomGlint.Layer> combined = new ArrayList<>(lowerLayers);
        int activeIdx = combined.size();
        combined.add(active);
        combined.addAll(upperLayers);
        int p = (kind == 0) ? k : activeIdx + 1 + k;
        if (p < 0 || p >= combined.size() || p == activeIdx) return;
        Collections.swap(combined, activeIdx, p); // active layer now sits at p
        // Re-split around p so the active slot lands there; the active editor state stays as-is (no reload,
        // so the selected color shard and sub-edits survive).
        lowerLayers.clear(); lowerLayers.addAll(combined.subList(0, p));
        upperLayers.clear(); upperLayers.addAll(combined.subList(p + 1, combined.size()));
    }

    /** Delete the active (selected) layer: discard its build and promote an adjacent committed layer into
     *  active, or reset to a blank editor when it was the only layer (mirrors deleting the last color shard,
     *  which re-seeds an empty one). */
    private void removeActiveLayer() {
        if (!upperLayers.isEmpty()) { loadControlsFromLayer(upperLayers.remove(0)); return; }
        if (!lowerLayers.isEmpty()) { loadControlsFromLayer(lowerLayers.remove(lowerLayers.size() - 1)); return; }
        clearBuild(); // no other layers left, blank the editor
    }

    /** Center preview slot (3): the current trim rendered large. */
    private void drawPreview(GuiGraphicsExtractor g) {
        int bx = leftPos + PREVIEW_X, by = topPos + PREVIEW_Y;
        ItemStack src = previewSource();
        if (src.isEmpty()) return;

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(bx + PREVIEW_W / 2f, by + PREVIEW_H / 2f);
        // Integer zoom only, a fractional zoom sub-pixel-seams the trim's edge/body layers and the glow halo.
        float scale = (Math.min(PREVIEW_W, PREVIEW_H) - 2) / 16; // int division floors to a whole multiple
        pose.scale(scale, scale);
        g.item(src, -8, -8);
        pose.popMatrix();
    }

    private DyeColor dyeIn(int slotConst) {
        return menu.slots.get(slotConst).getItem().get(DataComponents.DYE);
    }

    /** RGB of a glow/name colour slot: a custom hex when a rainbow dye sits in it ({@code customHex}, -1 if
     *  none yet), else the dye's palette colour, or -1 when the slot is empty. */
    private int slotColor(int slotConst, int customHex) {
        if (rainbowInSlot(slotConst)) return customHex;
        DyeColor d = dyeIn(slotConst);
        return d != null ? dyeRgb(d.ordinal()) : -1;
    }

    /** Green ring on every dye that makes up the selected shard (one for a pure colour, several for a mix;
     *  nothing if none selected or the shard is still unset). */
    private void drawDyeSelection(GuiGraphicsExtractor g) {
        List<List<Integer>> shards = editShards();
        if (selectedColorIdx < 0 || selectedColorIdx >= shards.size()) return;
        for (int idx : shards.get(selectedColorIdx)) {
            Slot s = (idx == RAINBOW || (idx & CUSTOM_FLAG) != 0)
                    ? menu.slots.get(GlintTableMenu.SLOT_RAINBOW_DYE)   // custom/rainbow → ring the rainbow slot
                    : menu.slots.get(GlintTableMenu.SLOT_DYE_START + idx);
            border(g, leftPos + s.x - 1, topPos + s.y - 1, 18, 18, RING_MAIN);
        }
    }

    /** True when the trim being built is a Glow Trim, its glint-glow controls don't apply. */
    private boolean glowTrimMain() {
        return previewSource().getItem() instanceof GlowTrimItem;
    }

    /** When naming is off, draws a non-clickable "Not Available" panel where the name text box would sit.
     *  (The name box widget shows when naming is on; the glow-mode button's label/colour are driven by
     *  {@code glowModeLabel}/{@code glowModeColor} on the widget itself, not here.) */
    private void drawNameGlowSection(GuiGraphicsExtractor g) {
        if (!modNamed) {
            // Cosmetic (non-clickable) panel in place of the text box, same length as it. The glow-mode
            // button beside it is a widget.
            int nx = leftPos + NAME_BOX_X, ny = topPos + NAME_BOX_Y;
            raisedPanel(g, nx, ny, NAME_BOX_W, 12, GUI_FACE);
            centeredScaled(g, label("not_available"), nx + NAME_BOX_W / 2, ny + 3, 0.85f, COST_BAD);
        }
    }

    /** The whole trim's material cost {redstone, slime, glass}: per layer, one redstone/slime for every ± step
     *  speed/scale sits off 1× and one glass per opacity level, tallied across the active + committed layers.
     *  The cost scales with how far a layer is tuned rather than being flat. Mirrors {@link GlintTableMenu#print}. */
    private int[] layerCosts() {
        // A Glow Trim has no pattern scale or opacity, so those cost nothing for it; only its glow-cycle speed
        // (redstone) counts.
        boolean glow = glowMainSelected();
        int red = CustomGlint.stepCost(modSpeed);
        int slime = glow ? 0 : CustomGlint.stepCost(modScale);
        int glass = glow ? 0 : modOpacity;
        if (!glow) {
            for (CustomGlint.Layer l : lowerLayers) { red += CustomGlint.stepCost(l.speed()); slime += CustomGlint.stepCost(l.patternScale()); glass += layerGlass(l); }
            for (CustomGlint.Layer l : upperLayers) { red += CustomGlint.stepCost(l.speed()); slime += CustomGlint.stepCost(l.patternScale()); glass += layerGlass(l); }
        }
        return new int[]{red, slime, glass};
    }
    /** A committed layer's glass cost, from the alpha baked into its first colour. */
    private static int layerGlass(CustomGlint.Layer l) { int[] c = l.colors(); return c.length > 0 ? CustomGlint.glassCost((c[0] >>> 24) & 0xFF) : 0; }

    /** Whether the ACTIVE layer is simultaneous. It simply follows the tear toggle, the preview reflects the
     *  chosen mode whether or not a tear is in the slot; the tear is only required (and consumed) at print. */
    private boolean activeSim() {
        return tearSimultaneous;
    }

    /** Committed layers that need a mode tear of the given mode ({@code sim} = simultaneous, else sequential):
     *  only a layer with ≥2 colors counts. A single-colour layer renders identically either way, so it defaults
     *  to sequential and costs no tear (mirrors GlintTableMenu.print). */
    private int committedModeLayers(boolean sim) {
        int n = 0;
        for (CustomGlint.Layer l : lowerLayers) if (l.simultaneous() == sim && l.colors().length >= 2) n++;
        for (CustomGlint.Layer l : upperLayers) if (l.simultaneous() == sim && l.colors().length >= 2) n++;
        return n;
    }

    /** Tears of one mode the build needs: one per committed layer of that mode with ≥2 colors, plus the active
     *  layer when it matches the mode and has ≥2 colors. A single-colour / colorless active layer costs none. */
    private int modeTearCost(boolean sim) {
        CustomGlint.Layer active = captureActive();
        boolean activeMatch = tearSimultaneous == sim && active != null && active.colors().length >= 2;
        return committedModeLayers(sim) + (activeMatch ? 1 : 0);
    }

    private int simTearCost() { return modeTearCost(true); }
    private int seqTearCost() { return modeTearCost(false); }

    /** True when a stack is a glint trim that already has a design picked, the minimum a print needs. An empty
     *  stack holds AIR, so the emptiness check is implicit. */
    private static boolean hasDesign(ItemStack s) {
        return s.getItem() instanceof GlintTrimItem && GlintTrimItem.getPattern(s) != null;
    }

    private boolean canPrint() {
        if (frameMemo && frameCanPrint != null) return frameCanPrint;
        boolean result = computeCanPrint();
        if (frameMemo) frameCanPrint = result;
        return result;
    }

    private boolean computeCanPrint() {
        // The active layer (built from the controls) must be valid, the server builds it from these fields.
        ItemStack src = activeTrim();
        // A Glow Trim only needs its glow colours (at least one) and the dyes to pay for them, plus redstone
        // when its glow-cycle speed is tuned off 1×. No design / layers / tears to validate.
        if (src.getItem() instanceof GlowTrimItem)
            return countSelectedDyes() > 0 && dyesAffordable()
                    && slotUnits(GlintTableMenu.SLOT_REDSTONE) >= layerCosts()[0];
        if (!hasDesign(src)) return false;
        // One layer tear per extra layer beyond the first, and never past the cap.
        if (totalLayers() > MAX_LAYERS) return false;
        if (layerTearCount() < totalLayers() - 1) return false;

        // Every committed layer must itself be valid + owned (the server rejects the print otherwise).
        for (CustomGlint.Layer l : lowerLayers) if (!layerValid(l)) return false;
        for (CustomGlint.Layer l : upperLayers) if (!layerValid(l)) return false;

        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        boolean fromBase = base.getItem() instanceof GlintTrimItem;

        // Can only print a design you own, a placed physical trim and the printed library are always
        // yours; a left-grid main selection must be in the stored (un-dimmed) set, and so must the donor.
        if (!fromBase && selectedPrinted.isEmpty()
                && (selectedMain == null || !GlintStoredSyncPacket.CLIENT_STORED.contains(selectedMain))) return false;
        if (!donorOwned()) return false;

        // Needs at least one color: the placed trim's existing colors, or at least one selected dye. The
        // merge-slot donor is NOT counted here; an uncommitted donor is preview-only and prints nothing until
        // [+] commits it as its own layer, so it can't overflow the active layer's 8-colour cap.
        int baseColorCount = fromBase ? GlintTrimItem.getColors(base).length : 0;
        if (anyLayerColorless(baseColorCount)) return false;

        // The table builds a single layer: a trim being reproduced that has more than one glint layer
        // can't be rebuilt here.
        ItemStack repro = reproSource();
        if (!repro.isEmpty()) {
            CustomGlint.Data d = CustomGlint.read(repro);
            if (d != null && d.layers().length > 1) return false;
        }
        // Every multi-colour layer (active + committed) needs a tear matching its mode: simultaneous or sequential.
        if (menu.slots.get(GlintTableMenu.SLOT_TEAR).getItem().getCount() < simTearCost()) return false;
        if (menu.slots.get(GlintTableMenu.SLOT_TEAR_SEQ).getItem().getCount() < seqTearCost()) return false;

        boolean baseGlowing = fromBase && CustomGlint.isGlowing(base);
        boolean baseHasGlowColors = fromBase && CustomGlint.getGlowColors(base).length > 0;
        boolean baseNamed = fromBase && base.has(DataComponents.CUSTOM_NAME);

        // Flat cost: one material per tuned layer across the whole trim (mirrors GlintTableMenu#print).
        int[] cost = layerCosts();
        if (slotUnits(GlintTableMenu.SLOT_REDSTONE) < cost[0]) return false;
        if (slotUnits(GlintTableMenu.SLOT_SLIME) < cost[1]) return false;
        if (slotUnits(GlintTableMenu.SLOT_GLASS) < cost[2]) return false;
        // Glow costs one glowstone per layer of the finished trim (active + committed).
        if (modGlow && !baseGlowing && menu.slots.get(GlintTableMenu.SLOT_GLOWSTONE).getItem().getCount() < totalLayers()) return false;
        if (modNamed && !baseNamed && !menu.slots.get(GlintTableMenu.SLOT_NAMETAG).hasItem()) return false;
        if (modGlow && !glowAuto && glowColorCount() == 0 && !baseHasGlowColors) return false;

        // Every colour across every layer now costs a dye (cumulative): the dye bar must hold enough of each
        // shade, plus one rainbow dye per custom / non-dye colour.
        if (!dyesAffordable()) return false;
        return true;
    }

    /** The STRUCTURAL reasons a print is blocked (design / layers / ownership / name+glow-colour choices),
     *  things that aren't a plain material shortage. Material shortages are shown separately in the always-on
     *  "Consumes" breakdown (red when short, green when met), so they're intentionally NOT repeated here. */
    private List<Component> printIssues() {
        List<Component> out = new ArrayList<>();
        ItemStack src = activeTrim();
        // A Glow Trim has no design/layers; its only requirement is at least one glow colour, so it never
        // shows "pick a design".
        if (src.getItem() instanceof GlowTrimItem) {
            if (countSelectedDyes() == 0) out.add(Component.translatable("screen.customglint.glint_table.issue.add_color"));
            return out;
        }
        if (!hasDesign(src)) {
            out.add(Component.translatable("screen.customglint.glint_table.issue.pick_design"));
            return out; // everything else depends on a chosen design
        }
        if (totalLayers() > MAX_LAYERS) out.add(Component.translatable("screen.customglint.glint_table.issue.too_many_layers", MAX_LAYERS));
        boolean badLayer = false;
        for (CustomGlint.Layer l : lowerLayers) if (!layerValid(l)) badLayer = true;
        for (CustomGlint.Layer l : upperLayers) if (!layerValid(l)) badLayer = true;
        if (badLayer) out.add(Component.translatable("screen.customglint.glint_table.issue.bad_layer"));

        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        boolean fromBase = base.getItem() instanceof GlintTrimItem;
        if (!fromBase && selectedPrinted.isEmpty()
                && (selectedMain == null || !GlintStoredSyncPacket.CLIENT_STORED.contains(selectedMain)))
            out.add(Component.translatable("screen.customglint.glint_table.issue.design_not_owned"));
        if (!donorOwned()) out.add(Component.translatable("screen.customglint.glint_table.issue.donor_not_owned"));

        int baseColorCount = fromBase ? GlintTrimItem.getColors(base).length : 0;
        // Flag a missing colour on ANY layer (active or a committed one), so the hint shows no matter which
        // layer is currently open, not just when the colourless layer happens to be the one being edited.
        if (anyLayerColorless(baseColorCount)) out.add(Component.translatable("screen.customglint.glint_table.issue.layer_missing_color"));

        ItemStack repro = reproSource();
        if (!repro.isEmpty()) {
            CustomGlint.Data d = CustomGlint.read(repro);
            if (d != null && d.layers().length > 1) out.add(Component.translatable("screen.customglint.glint_table.issue.no_multilayer"));
        }

        // Name tag and the manual-glow colour aren't in the Consumes list (a gate / a colour choice), so they
        // stay here as structural blockers.
        boolean baseNamed = fromBase && base.has(DataComponents.CUSTOM_NAME);
        boolean baseHasGlowColors = fromBase && CustomGlint.getGlowColors(base).length > 0;
        if (modNamed && !baseNamed && !menu.slots.get(GlintTableMenu.SLOT_NAMETAG).hasItem())
            out.add(Component.translatable("screen.customglint.glint_table.issue.name_needs", itemName(Items.NAME_TAG)));
        if (modGlow && !glowAuto && glowColorCount() == 0 && !baseHasGlowColors)
            out.add(Component.translatable("screen.customglint.glint_table.issue.glow_manual_needs"));
        return out;
    }

    /** The localized display name of an item, for material/requirement lines (auto-tracks the item's lang). */
    private static Component itemName(Item item) {
        return new ItemStack(item).getHoverName();
    }

    /** Hover breakdown for the Print button: every material the build will consume, each green when the
     *  slot holds enough and red when it's short, plus a header that flags an incomplete build. */
    private List<Component> printTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.customglint.glint_table.print").withStyle(ChatFormatting.WHITE));

        // No design chosen yet: nothing to build or consume, just say so. A Glow Trim has no design, so it
        // skips the pick-design guard and falls through to its glow-colour issues + dye/redstone breakdown.
        ItemStack src = activeTrim();
        if (!(src.getItem() instanceof GlowTrimItem) && !hasDesign(src)) {
            lines.add(Component.literal("• ").append(Component.translatable("screen.customglint.glint_table.issue.pick_design")).withStyle(ChatFormatting.RED));
            return lines;
        }

        // Structural blockers (non-material) up top; the material breakdown below is ALWAYS shown so every
        // requirement stays visible and just flips red→green as it's met.
        for (Component s : printIssues()) lines.add(Component.literal("• ").append(s).withStyle(ChatFormatting.RED));

        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        boolean fromBase = base.getItem() instanceof GlintTrimItem;
        boolean baseGlowing = fromBase && CustomGlint.isGlowing(base);

        // Everything from here is the material breakdown; remember where it starts so an empty one can say so.
        int firstMaterialLine = lines.size();
        // Dyes for every colour across all layers (cumulative: a shade reused costs a dye each time), plus one
        // rainbow dye per custom / non-dye colour.
        DyeReq req = dyeReq();
        for (int i = 0; i < 16; i++)
            if (req.counts()[i] > 0) reqLine(lines, itemName(GlintTableMenu.DYE_ITEMS[i]), req.counts()[i], GlintTableMenu.SLOT_DYE_START + i);
        if (req.rainbow() > 0) reqLine(lines, itemName(ModItems.RAINBOW_DYE.get()), req.rainbow(), GlintTableMenu.SLOT_RAINBOW_DYE);
        int[] cost = layerCosts();
        if (cost[0] > 0) reqLine(lines, itemName(Items.REDSTONE), cost[0], GlintTableMenu.SLOT_REDSTONE);
        if (cost[1] > 0) reqLine(lines, itemName(Items.SLIME_BALL), cost[1], GlintTableMenu.SLOT_SLIME);
        if (cost[2] > 0) reqLine(lines, itemName(Items.GLASS), cost[2], GlintTableMenu.SLOT_GLASS);
        if (modGlow && !baseGlowing) reqLine(lines, itemName(Items.GLOWSTONE_DUST), totalLayers(), GlintTableMenu.SLOT_GLOWSTONE);
        int extraLayers = lowerLayers.size() + upperLayers.size();
        if (extraLayers > 0) reqLine(lines, itemName(ModItems.GLINT_LAYER_TEAR.get()), extraLayers, GlintTableMenu.SLOT_LAYER_TEAR);
        // A mode tear per multi-colour layer: simultaneous and sequential each tallied across the whole trim.
        int simTears = simTearCost(), seqTears = seqTearCost();
        if (simTears > 0) reqLine(lines, itemName(ModItems.GLINT_TEAR_SIMULTANEOUS.get()), simTears, GlintTableMenu.SLOT_TEAR);
        if (seqTears > 0) reqLine(lines, itemName(ModItems.GLINT_TEAR_SEQUENTIAL.get()), seqTears, GlintTableMenu.SLOT_TEAR_SEQ);
        if (lines.size() == firstMaterialLine)
            lines.add(Component.translatable("screen.customglint.glint_table.nothing").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    /** One material line for {@link #printTooltip}: name + amount, green when the slot holds enough, red when
     *  short. Costs cap at one 64 stack per slot. */
    private void reqLine(List<Component> lines, Component name, int need, int slotConst) {
        int have = slotUnits(slotConst);
        ChatFormatting color = have >= need ? ChatFormatting.GREEN : ChatFormatting.RED;
        lines.add(Component.translatable("screen.customglint.glint_table.consume_line", name, need).withStyle(color));
    }

    /** Units a material slot holds toward its cost (1:1, capped at a 64 stack). */
    private int slotUnits(int slotConst) {
        return menu.slots.get(slotConst).getItem().getCount();
    }

    // ── Beveled widgets ───────────────────────────────────────────────────────

    /**
     * A skinned button backed by the widget system. The label, text colour and base face are pulled live
     * each frame from suppliers (so a button can show changing state without being recreated); drawing uses
     * the active skin's bevel. Left-click only by default; the skin button opts into right-click too.
     */
    private final class BevelButton extends AbstractWidget {
        private final Supplier<String> label;
        private final IntSupplier textColor;
        private final IntSupplier faceColor;
        private final int textDy;
        private final IntConsumer onPress;
        private final boolean rightToo;
        private Supplier<List<Component>> tooltip; // explanatory hover text (null = none)

        BevelButton(int x, int y, int w, int h, int textDy, boolean rightToo,
                    Supplier<String> label, IntSupplier textColor, IntSupplier faceColor, IntConsumer onPress) {
            super(x, y, w, h, Component.empty());
            this.label = label; this.textColor = textColor; this.faceColor = faceColor;
            this.textDy = textDy; this.onPress = onPress; this.rightToo = rightToo;
        }

        /** Attach an explanatory tooltip, shown while hovered (see {@link #extractTooltip}). Fluent. */
        BevelButton tip(Supplier<List<Component>> t) { this.tooltip = t; return this; }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
            int face = (active && isHovered()) ? BTN_HOVER : faceColor.getAsInt();
            raisedPanel(g, getX(), getY(), getWidth(), getHeight(), face);
            centered(g, label.get(), getX() + getWidth() / 2, getY() + textDy, textColor.getAsInt());
        }

        @Override
        protected boolean isValidClickButton(MouseButtonInfo info) {
            return info.button() == InputConstants.MOUSE_BUTTON_LEFT || (rightToo && info.button() == InputConstants.MOUSE_BUTTON_RIGHT);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            onPress.accept(event.button());
        }

        @Override
        public void playDownSound(SoundManager soundManager) {
            if (GlintClientConfig.glintTableSound()) super.playDownSound(soundManager);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            out.add(NarratedElementType.TITLE, Component.literal(label.get()));
        }
    }

    /** Create the table's buttons as widgets. Positions are fixed (slot-relative); labels/colours update live. */
    private void addButtons() {
        Slot glow = menu.slots.get(GlintTableMenu.SLOT_GLOWSTONE);
        Slot nameS = menu.slots.get(GlintTableMenu.SLOT_NAMETAG);
        Slot tear = menu.slots.get(GlintTableMenu.SLOT_TEAR);

        addRenderableWidget(new BevelButton(leftPos + SKIN_BTN_X, topPos + SKIN_BTN_Y, SKIN_BTN_W, SKIN_BTN_H, 2, true,
                () -> Component.translatable("screen.customglint.skin." + skin.name.toLowerCase(Locale.ROOT)).getString(),
                () -> LABEL_HDR, () -> GUI_FACE, b -> cycleSkin(b == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1)));

        addRenderableWidget(new BevelButton(sndBtnX(), topPos + SKIN_BTN_Y, SND_BTN_W, SKIN_BTN_H, 2, false,
                () -> "♪", () -> GlintClientConfig.glintTableSound() ? COST_OK : COST_BAD, () -> GUI_FACE,
                b -> GlintClientConfig.setGlintTableSound(!GlintClientConfig.glintTableSound())));

        // Import: left-click opens a picker of premade config trims (same source as the wand editor's
        // Import); right-click saves the current preview build to a config file so it can be imported later.
        // Sits to the left of the sound toggle.
        addRenderableWidget(new BevelButton(impBtnX(), topPos + SKIN_BTN_Y, IMP_BTN_W, SKIN_BTN_H, 2, true,
                () -> "↓", () -> LABEL_HDR, () -> GUI_FACE, b -> { if (b == InputConstants.MOUSE_BUTTON_RIGHT) saveCurrentAsImport(); else toggleImportPicker(); }));

        printBtn = addRenderableWidget(new BevelButton(leftPos + PRINT_X, topPos + PRINT_Y, PRINT_W, PRINT_H, 3, false,
                () -> label("print"), () -> canPrint() ? LABEL_HDR : COST_BAD, () -> canPrint() ? GUI_FACE : BTN_DISABLED,
                b -> { if (canPrint()) onPrint(); }));

        addRenderableWidget(new BevelButton(leftPos + INTERP_X, topPos + INTERP_Y, INTERP_W, INTERP_H, 2, false,
                () -> Component.translatable("screen.customglint.glint_table.interpolation", boolLabel(modInterpolate)).getString(), () -> modInterpolate ? COST_OK : LABEL_HDR,
                () -> GUI_FACE, b -> modInterpolate = !modInterpolate)
                .tip(() -> tipLines("screen.customglint.glint_table.tip.interpolation")));

        addRenderableWidget(new BevelButton(leftPos + SCROLL_X, topPos + SCROLL_Y, SCROLL_W, SCROLL_H, 2, false,
                () -> Component.translatable("screen.customglint.glint_table.scroll", GlintTrimItem.scrollLabel(modScrollDir)).getString(), () -> LABEL_HDR, () -> GUI_FACE,
                b -> modScrollDir = (modScrollDir + 1) % 9)
                .tip(() -> modScrollDir == CustomGlint.SCROLL_STATIC
                        ? tipLines("screen.customglint.glint_table.tip.scroll", "screen.customglint.glint_table.tip.scroll_static")
                        : tipLines("screen.customglint.glint_table.tip.scroll")));

        addRenderableWidget(new BevelButton(tearToggleCx() - 15, topPos + tear.y + 26, 30, 11, 2, false,
                () -> label(tearSimultaneous ? "sim" : "seq"), () -> LABEL_HDR, () -> GUI_FACE,
                b -> tearSimultaneous = !tearSimultaneous)
                .tip(() -> tipLines("screen.customglint.glint_table.tip.type")));

        // Left-click toggles Auto/Manual; right-click (while Manual) focuses the glow colour shards into the
        // colour strip (right-click again unfocuses). Forcing Manual on a focus right-click saves a click.
        glowModeBtn = addRenderableWidget(new BevelButton(leftPos + GLOW_MODE_X, topPos + GLOW_MODE_Y, GLOW_MODE_W, GLOW_MODE_H, 2, true,
                this::glowModeLabel, this::glowModeColor, () -> glowFocused ? BTN_HOVER : GUI_FACE, b -> {
                    if (b == InputConstants.MOUSE_BUTTON_RIGHT) {
                        if (glowFocused) glowFocused = false;
                        else { glowAuto = false; glowFocused = true; selectedColorIdx = glowShards.isEmpty() ? -1 : 0; }
                    } else {
                        glowAuto = !glowAuto;
                    }
                })
                .tip(() -> modGlow
                        ? tipLines("screen.customglint.glint_table.tip.glow_mode", "screen.customglint.glint_table.tip.glow_mode_manual")
                        : tipLines("screen.customglint.glint_table.tip.glow_mode", "screen.customglint.glint_table.tip.glow_mode_off")));

        glowToggleBtn = addRenderableWidget(new BevelButton(leftPos + glow.x - 7, topPos + glow.y + 26, 30, 11, 2, false,
                () -> glowTrimMain() ? naLabel() : boolLabel(modGlow).getString(),
                () -> glowTrimMain() ? COST_BAD : (modGlow ? COST_OK : LABEL_HDR),
                () -> GUI_FACE, b -> modGlow = !modGlow)
                .tip(() -> glowTrimMain()
                        ? tipLines("screen.customglint.glint_table.tip.glow_na")
                        : tipLines("screen.customglint.glint_table.tip.glow")));

        nameToggleBtn = addRenderableWidget(new BevelButton(leftPos + nameS.x - 7, topPos + nameS.y + 26, 30, 11, 2, false,
                () -> boolLabel(modNamed).getString(),
                () -> modNamed ? COST_OK : LABEL_HDR,
                () -> GUI_FACE, b -> modNamed = !modNamed)
                .tip(() -> tipLines("screen.customglint.glint_table.tip.name")));

        // −/+ steppers for speed / opacity / scale (the value + label between them are drawn by the screen).
        addStepperPair(GlintTableMenu.SLOT_REDSTONE, () -> modSpeed = GuiHelpers.stepDown(modSpeed), () -> modSpeed = GuiHelpers.stepUp(modSpeed), "screen.customglint.glint_table.tip.speed");
        addStepperPair(GlintTableMenu.SLOT_GLASS, () -> modOpacity = Math.max(0, modOpacity - 1), () -> modOpacity = Math.min(8, modOpacity + 1), "screen.customglint.glint_table.tip.opacity");
        addStepperPair(GlintTableMenu.SLOT_SLIME, () -> modScale = GuiHelpers.stepDown(modScale), () -> modScale = GuiHelpers.stepUp(modScale), "screen.customglint.glint_table.tip.scale");

        int ox = leftPos + SCROLL_X + SCROLL_W / 2, oy = topPos + SCROLL_OFF_Y;
        offsetMinus = stepper(ox - 15, oy, "-", () -> modScrollOffset = Math.max(0.0f, Math.round((modScrollOffset - 0.05f) * 20) / 20.0f), "screen.customglint.glint_table.tip.offset");
        offsetPlus  = stepper(ox + 6,  oy, "+", () -> modScrollOffset = Math.min(1.0f, Math.round((modScrollOffset + 0.05f) * 20) / 20.0f), "screen.customglint.glint_table.tip.offset");
    }

    /** A friendly, capitalized design name for a layer tooltip header (matches the trim item's naming). */
    private static Component designDisplayName(Identifier design) {
        String name;
        if (design.equals(CustomGlint.VANILLA)) name = "Vanilla";
        else {
            String p = GlintTrimItem.extractPatternName(design);
            name = p == null ? design.toString() : capitalize(p);
        }
        return Component.literal(name).withStyle(ChatFormatting.WHITE);
    }

    /** The active layer's design (the chip in the strip that edits the live controls), or null if none yet. */
    private Identifier activeLayerDesign() {
        ItemStack a = activeTrim();
        return a.getItem() instanceof GlintTrimItem ? GlintTrimItem.getPattern(a) : null;
    }

    /** A colour header naming a shard: its dye colour name, a custom {@code #hex}, "Rainbow" (chosen but no hex
     *  yet), or "No color yet" for an empty shard. The name is drawn in the colour itself. */
    private Component shardColorLabel(List<Integer> shard) {
        if (shard.isEmpty())
            return Component.translatable("screen.customglint.glint_table.shard_unset").withStyle(ChatFormatting.GRAY);
        int rgb = mixRgb(shard);
        if (rgb < 0) // a rainbow shard with no hex entered yet
            return Component.translatable("screen.customglint.glint_table.shard_rainbow").withStyle(ChatFormatting.GRAY);
        int dye = dyeIndexForRgb(rgb);
        String name = dye >= 0 ? capitalize(DyeColor.byId(dye).getName().replace("_", " "))
                               : "#" + String.format("%06X", rgb);
        return Component.literal(name).withColor(rgb);
    }

    /** Uppercase the first character (matches {@code GlintTrimItem.capitalize}); leaves the rest as-is. */
    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A clean white, multi-line tooltip built from lang keys (one line per key). Left unstyled so it renders
     *  the default tooltip white, matching the wand editor's control tooltips. */
    private static List<Component> tipLines(String... keys) {
        List<Component> lines = new ArrayList<>(keys.length);
        for (String k : keys) lines.add(Component.translatable(k));
        return lines;
    }

    /** Add a −/+ stepper pair under a modifier slot, at the same offsets the value text is centred on. */
    private void addStepperPair(int slotConst, Runnable minus, Runnable plus, String tipKey) {
        Slot s = menu.slots.get(slotConst);
        int cx = leftPos + s.x + 8, by = topPos + s.y + 26;
        stepper(cx - 15, by, "-", minus, tipKey);
        stepper(cx + 6, by, "+", plus, tipKey);
    }

    private BevelButton stepper(int bx, int by, String label, Runnable onPress, String tipKey) {
        return addRenderableWidget(new BevelButton(bx, by, 9, 9, 1, false,
                () -> label, () -> LABEL_HDR, () -> GUI_FACE, b -> onPress.run())
                .tip(tipKey == null ? null : () -> tipLines(tipKey)));
    }

    /** Refresh per-frame clickability (active) for the buttons whose enabled state depends on the build. */
    private void syncButtons() {
        printBtn.active = canPrint();
        glowModeBtn.active = modGlow && !glowTrimMain();
        glowToggleBtn.active = !glowTrimMain();
        nameToggleBtn.active = true; // usable without a name tag; the print gates on it
        offsetMinus.visible = offsetPlus.visible = (modScrollDir == CustomGlint.SCROLL_STATIC);
    }

    private String glowModeLabel() {
        if (glowTrimMain()) return naLabel();
        if (!modGlow) return label("glow_off");
        return label(glowAuto ? "glow_auto" : "glow_manual");
    }

    /** Localized "True"/"False" for the boolean toggle buttons. */
    private static Component boolLabel(boolean value) {
        return Component.translatable("screen.customglint.glint_table." + (value ? "true" : "false"));
    }

    /** Localized "N/A" shown when a toggle is unavailable. */
    private static String naLabel() {
        return label("na");
    }

    /** Resolved text for a {@code screen.customglint.glint_table.<suffix>} caption key. Every plain caption on
     *  this screen goes through here so the key prefix is written once. */
    private static String label(String suffix) {
        return Component.translatable("screen.customglint.glint_table." + suffix).getString();
    }

    private int glowModeColor() {
        return (glowTrimMain() || !modGlow) ? COST_BAD : LABEL_HDR;
    }

    /** Flat (no drop-shadow) centered text, like the vanilla container labels. */
    private void centered(GuiGraphicsExtractor g, String str, int cx, int y, int color) {
        g.text(font, str, cx - font.width(str) / 2, y, color, false);
    }

    /** {@link #centered} under a uniform scale about (cx, y). The font has no smaller size, so every caption
     *  and stepper value that must fit a narrow slot is drawn shrunk this way. */
    private void centeredScaled(GuiGraphicsExtractor g, String str, int cx, int y, float scale, int color) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, y);
        pose.scale(scale, scale);
        centered(g, str, 0, 0, color);
        pose.popMatrix();
    }

    /** 1px rectangle outline. */
    private void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    // The panel/bevel primitives delegate to the active skin so a skin can restyle them (gradients, tiling,
    // rivets) without changing any call site here. The default skin draws the original flat bevel.
    private void raisedPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, int face) {
        skin.raised(g, x, y, w, h, face);
    }

    private void shardBevel(GuiGraphicsExtractor g, int x, int y, int s) {
        skin.shardBevel(g, x, y, s);
    }

    private void slotWell(GuiGraphicsExtractor g, int sx, int sy) {
        skin.slotWell(g, sx, sy);
    }

    private String modValue(int slotConst) {
        return switch (slotConst) {
            case GlintTableMenu.SLOT_REDSTONE -> GuiHelpers.fmtVal(modSpeed) + "×";           // speed
            case GlintTableMenu.SLOT_GLASS    -> Math.round(modOpacity * 100f / 8f) + "%"; // translucency
            case GlintTableMenu.SLOT_SLIME    -> GuiHelpers.fmtVal(modScale) + "×";           // scale
            default -> "";
        };
    }

    /** Label + value + [-]/[+] under the speed / opacity / scale slots, plus plain labels under the
     *  glow / name / tear slots (always shown). */
    private void drawModifierControls(GuiGraphicsExtractor g) {
        drawModControl(g, GlintTableMenu.SLOT_REDSTONE, label("speed"));
        drawModControl(g, GlintTableMenu.SLOT_GLASS,    label("opacity"));
        drawModControl(g, GlintTableMenu.SLOT_SLIME,    label("scale"));
        drawControlLabel(g, GlintTableMenu.SLOT_GLOWSTONE, label("glow"));
        drawControlLabel(g, GlintTableMenu.SLOT_NAMETAG,   label("name_label"));
        drawTearLabel(g);
        // The static-offset value sits between its −/+ stepper widgets, shown only while on Static.
        if (modScrollDir == CustomGlint.SCROLL_STATIC) {
            fitValue(g, String.format("%.2f", modScrollOffset), leftPos + SCROLL_X + SCROLL_W / 2, topPos + SCROLL_OFF_Y + 1);
        }
    }

    /** Draw a stepper value centered at (cx, y), shrunk so it always fits the ~12px gap between the −/+
     *  buttons (capped at 0.8×) instead of bleeding into them on wide values like "100%" / "0.85". */
    private void fitValue(GuiGraphicsExtractor g, String val, int cx, int y) {
        int w = font.width(val);
        centeredScaled(g, val, cx, y, w > 0 ? Math.min(0.8f, 11f / w) : 0.8f, LABEL_HDR);
    }

    /** Center-x of the shared tear toggle: the midpoint between the two side-by-side tear slots. */
    private int tearToggleCx() {
        return (leftPos + menu.slots.get(GlintTableMenu.SLOT_TEAR).x + 8
                + leftPos + menu.slots.get(GlintTableMenu.SLOT_TEAR_SEQ).x + 8) / 2;
    }

    /** The caption above a control slot (Glow / Name); the True/False button itself is a widget. */
    private void drawControlLabel(GuiGraphicsExtractor g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        smallLabel(g, label, leftPos + s.x + 8, topPos + s.y + 19, LABEL_HDR);
    }

    /** The "Type" caption + the dimming of whichever tear slot isn't the toggled one; the Sim/Seq button
     *  itself is a widget. */
    private void drawTearLabel(GuiGraphicsExtractor g) {
        int cx = tearToggleCx(), top = topPos + menu.slots.get(GlintTableMenu.SLOT_TEAR).y;
        smallLabel(g, label("type"), cx, top + 19, LABEL_HDR);
        Slot off = menu.slots.get(tearSimultaneous ? GlintTableMenu.SLOT_TEAR_SEQ : GlintTableMenu.SLOT_TEAR);
        int dx = leftPos + off.x - 1, dy = topPos + off.y - 1;
        g.fill(dx, dy, dx + 18, dy + 18, DIM_GHOST);
    }

    /** Label + value under a modifier slot; the −/+ stepper buttons themselves are widgets. */
    private void drawModControl(GuiGraphicsExtractor g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        int cx = leftPos + s.x + 8, top = topPos + s.y;
        smallLabel(g, label, cx, top + 19, LABEL_HDR);
        fitValue(g, modValue(slotConst), cx, top + 27); // centered between the −/+ buttons, shrunk to fit
    }

    private void drawSectionLabels(GuiGraphicsExtractor g) {
        int x = leftPos, y = topPos;
        g.text(font, label("empty_trims"), x + LGRID_X, y + GRID_Y - 11, LABEL_HDR, false);
        g.text(font, label("printed"),     x + RGRID_X, y + GRID_Y - 11, LABEL_HDR, false);

        // Labels beneath the three top-row trim slots (main / layer-tear / layered).
        topSlotLabel(g, GlintTableMenu.SLOT_TRIM,       label("main"));
        topSlotLabel(g, GlintTableMenu.SLOT_LAYER_TEAR, label("layer"));
        topSlotLabel(g, GlintTableMenu.SLOT_TRIM_B,     label("merge"));
    }

    private void topSlotLabel(GuiGraphicsExtractor g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        smallLabel(g, label, leftPos + s.x + 8, topPos + s.y + 19, LABEL_HDR);
    }

    /** A 0.7× centered label, used for every slot caption so they share one size. */
    private void smallLabel(GuiGraphicsExtractor g, String label, int cx, int y, int color) {
        centeredScaled(g, label, cx, y, 0.7f, color);
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    private static boolean isTrim(ItemStack stack) {
        return stack.getItem() instanceof GlintTrimItem || stack.getItem() instanceof GlowTrimItem;
    }

    /** A "painted" trim (colored or glowing) files into the printed library; a blank design trim files into
     *  the palette. Mirrors {@code GlintTableMenu.isPainted} so the ghost's donor-owned flag matches where the
     *  deposit actually lands. */
    private static boolean isPaintedTrim(ItemStack stack) {
        if (stack.getItem() instanceof GlintTrimItem) return GlintTrimItem.getColors(stack).length > 0 || GlintTrimItem.isGlowing(stack);
        if (stack.getItem() instanceof GlowTrimItem) return GlowTrimItem.getColors(stack).length > 0;
        return false;
    }

    /** Half-open hit test in SCREEN coordinates: right and bottom edges belong to the next widget over, which
     *  is what keeps adjacent grid cells and strip chips from both claiming the same pixel. */
    private static boolean inBox(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** True when (mx, my) is over the cell area of either scrollable grid. */
    private boolean overEitherGrid(double mx, double my) {
        return inGrid(mx, my, leftPos + LGRID_X, topPos + GRID_Y)
                || inGrid(mx, my, leftPos + RGRID_X, topPos + GRID_Y);
    }

    /** Whether (mx, my) is over the 16×16 item area of a table slot. */
    private boolean overSlot(double mx, double my, int slotConst) {
        Slot s = menu.slots.get(slotConst);
        return inBox(mx, my, leftPos + s.x, topPos + s.y, 16, 16);
    }

    /** The grid index under (mx, my) for a grid at (gx, gy), or -1. */
    private static int gridIndexAt(double mx, double my, int gx, int gy, int scroll, int total) {
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                if (inBox(mx, my, gx + col * CELL, gy + row * CELL, 16, 16)) {
                    int idx = (scroll + row) * GRID_COLS + col;
                    return idx < total ? idx : -1;
                }
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int x = leftPos, y = topPos;

        // Import picker is modal: it swallows every click while open (search box, a row, or click-outside-to-close).
        if (showImportPicker) {
            int ox = impPickerX(), oy = impPickerY(), h = impPickerH();
            if (mx >= ox + 2 && mx < ox + IMP_PW - 2 && my >= oy + 3 && my < oy + 17) {
                if (importSearchBox != null) importSearchBox.mouseClicked(event, doubleClick);
                return true;
            }
            if (mx < ox || mx >= ox + IMP_PW || my < oy || my >= oy + h) { // click outside closes
                showImportPicker = false;
                if (importSearchBox != null) importSearchBox.setFocused(false);
                return true;
            }
            int listY = impListY(), sbX = ox + IMP_PW - 5, trackH = IMPORT_ROWS * IMPORT_ROW_H;
            if (importFiltered.size() > IMPORT_ROWS && inBox(mx, my, sbX, listY, SCROLLBAR_W, trackH)) {
                draggingImport = true;
                importScroll = importScrollFromMouse(my);
                return true;
            }
            if (my >= listY && mx >= ox && mx < sbX) {
                int row = (int) (my - listY) / IMPORT_ROW_H;
                int idx = importScroll + row;
                if (row >= 0 && row < IMPORT_ROWS && idx < importFiltered.size()) {
                    ImpEntry e = importFiltered.get(idx);
                    boolean trashShown = !e.server() || canManageServerTrims(); // server rows: op/single-player only
                    if (mx >= sbX - IMP_TRASH_W && trashShown) { // trash hotzone: delete, don't select
                        if (e.server()) deleteServerBlueprint(e.name()); // shared: ask the server to remove it
                        else deleteImport(e.name());                     // personal: delete the local file
                    } else {
                        importSelected(e);
                    }
                }
            }
            return true;
        }

        // A click inside the open hex box goes to the box (and is consumed) so it never falls through to a
        // modifier button drawn beneath it.
        if (hexOpen && hexBox != null && hexBox.isVisible()
                && mx >= hexBox.getX() && mx < hexBox.getX() + hexBox.getWidth()
                && my >= hexBox.getY() && my < hexBox.getY() + hexBox.getHeight()) {
            return hexBox.mouseClicked(event, doubleClick);
        }

        // Right-click the main slot clears the whole working trim; right-click the merge slot clears it. Any
        // physical trim in the slot is shift-moved back to the inventory first so it isn't lost.
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && menu.getCarried().isEmpty()) {
            if (overSlot(mx, my, GlintTableMenu.SLOT_TRIM)) {
                if (menu.slots.get(GlintTableMenu.SLOT_TRIM).hasItem()) quickMoveSlot(GlintTableMenu.SLOT_TRIM);
                clearBuild();
                return true;
            }
            if (overSlot(mx, my, GlintTableMenu.SLOT_TRIM_B)) {
                if (menu.slots.get(GlintTableMenu.SLOT_TRIM_B).hasItem()) quickMoveSlot(GlintTableMenu.SLOT_TRIM_B);
                selectedDonor = ItemStack.EMPTY;
                selectedDonorPrinted = false;
                return true;
            }
        }

        // Drag-in deposit: with a trim held on the cursor, clicking either scrollable grid deposits it into
        // the library (the grids aren't real slots, so this is the click-drag equivalent of shift-clicking).
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && isTrim(carried) && overEitherGrid(mx, my)) {
            ClientPacketDistributor.sendToServer(GlintDepositPacket.INSTANCE);
            return true;
        }
        // Placing a trim in the main / merge slot never leaves it there physically: the copy is filed into the
        // library (painted → printed, blank design → the palette) exactly like a grid deposit, and previewed as
        // a GHOST. The slots themselves reject placement (mayPlace=false), so the trim can't get stuck; pull the
        // real copy back out by shift-clicking it in the printed list (or the palette for a blank design).
        if (!carried.isEmpty() && isTrim(carried)) {
            if (overSlot(mx, my, GlintTableMenu.SLOT_TRIM)) {
                ItemStack ghost = carried.copy(); ghost.setCount(1);
                ClientPacketDistributor.sendToServer(GlintDepositPacket.INSTANCE);
                loadFromTrim(ghost);
                return true;
            }
            if (overSlot(mx, my, GlintTableMenu.SLOT_TRIM_B)) {
                ItemStack ghost = carried.copy(); ghost.setCount(1);
                ClientPacketDistributor.sendToServer(GlintDepositPacket.INSTANCE);
                selectedDonor = ghost;
                selectedDonorPrinted = isPaintedTrim(ghost); // painted goes to printed (always owned); blank to the palette
                return true;
            }
        }

        // Rainbow dye in the glow / name colour slot → right-click toggles its custom-hex picker (instead of
        // the vanilla slot pickup), so you can type any hex for the glow ring / item name colour. Right-clicking
        // the same slot again closes the box (mirrors the custom-shard toggle).
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (menu.showGlowDye && rainbowInSlot(GlintTableMenu.SLOT_GLOW_DYE) && overSlot(mx, my, GlintTableMenu.SLOT_GLOW_DYE)) {
                if (hexOpen && hexMode == HEX_GLOW) closeHex(); else openHexColor(HEX_GLOW);
                return true;
            }
            if (menu.showNameDye && rainbowInSlot(GlintTableMenu.SLOT_NAME_DYE) && overSlot(mx, my, GlintTableMenu.SLOT_NAME_DYE)) {
                if (hexOpen && hexMode == HEX_NAME) closeHex(); else openHexColor(HEX_NAME);
                return true;
            }
        }

        // Layer strip (same rule as the color strip): left-click selects a layer; right-click deletes it only
        // when it's the selected (active) one, so you select first, then right-click to delete. [+] adds.
        int cy = y + LAYER_STRIP_Y;
        for (Chip c : layerChips()) {
            if (!inBox(mx, my, x + c.x(), cy, LAYER_ICON, LAYER_ICON)) continue;
            glowFocused = false; // touching the layer strip returns the colour strip to the active layer
            if (c.kind() == 3) { if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) addLayer(); }         // [+] add chip
            else if (c.kind() == 1) { if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) removeActiveLayer(); } // selected layer: right-click deletes
            else if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && hasShiftDown()) swapLayers(c.kind(), c.index()); // shift-left: swap with the active layer
            else editLayer(c.kind(), c.index());                                 // unselected: any click selects it first
            return true;
        }

        // Color-shard strip: right-click a shard removes that color instance.
        if (colorStripClick(mx, my, event.button())) return true;

        // Dye bar: right-click a dye sets the SELECTED color shard's colour; shift-right-click MIXES the dye
        // into the shard (several dyes average into a custom colour not in the dye table). The slot need NOT
        // hold the dye, each slot maps to a fixed dye shade, so you can pick any colour to design with even
        // without owning it; the missing dye is added to the print cost (shown in the Print tooltip). Fully
        // consumed so vanilla never grabs/moves the dye stack; left-click is still vanilla pickup.
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            List<List<Integer>> editing = editShards();
            boolean shardSel = selectedColorIdx >= 0 && selectedColorIdx < editing.size();
            for (int i = 0; i < 16; i++) {
                if (overSlot(mx, my, GlintTableMenu.SLOT_DYE_START + i)) {
                    if (menu.getCarried().isEmpty() && shardSel) {
                        List<Integer> shard = editing.get(selectedColorIdx);
                        if (isCustomShard(shard)) { shard.clear(); shard.add(i); closeHex(); } // leave rainbow mode
                        // Shift-right-click toggles this dye in the mix: remove it if already added (so you can
                        // un-pick one colour without clearing the shard), otherwise blend it in (capped at 8).
                        else if (hasShiftDown()) { if (!shard.remove((Integer) i) && shard.size() < 8) shard.add(i); }
                        else { shard.clear(); shard.add(i); }                                   // set as the sole colour
                    }
                    return true; // swallow the right-click regardless, no vanilla pickup/place here
                }
            }
            // Rainbow dye → put the selected shard into custom-hex (rainbow) mode; click the shard to type a hex.
            // Works with an empty slot too; the rainbow dye is charged at print.
            if (overSlot(mx, my, GlintTableMenu.SLOT_RAINBOW_DYE)) {
                if (menu.getCarried().isEmpty() && shardSel) {
                    List<Integer> shard = editing.get(selectedColorIdx);
                    shard.clear(); shard.add(RAINBOW);
                }
                return true;
            }
        }

        // Grid scrollbars, left-click or drag the thumb to scroll (matches the creative inventory).
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (onScrollbar(mx, my, x + LGRID_X, y + GRID_Y, trims.size())) {
                draggingGrid = 0;
                gridScroll = scrollFromMouse(my, y + GRID_Y, trims.size());
                return true;
            }
            if (onScrollbar(mx, my, x + RGRID_X, y + GRID_Y, printedCapacity())) {
                draggingGrid = 1;
                printScroll = scrollFromMouse(my, y + GRID_Y, printedCapacity());
                return true;
            }
        }

        // Left grid (1), left-click picks the main design; right-click sets it as the merge donor (slot 2).
        int li = gridIndexAt(mx, my, x + LGRID_X, y + GRID_Y, gridScroll, trims.size());
        if (li >= 0) {
            String name = trims.get(li);
            // Shift-left-click pulls a free blank copy of an owned design into the inventory.
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && hasShiftDown()) {
                if (GlintStoredSyncPacket.CLIENT_STORED.contains(name))
                    ClientPacketDistributor.sendToServer(new GlintGiveDesignPacket(name));
                return true;
            }
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                if (glowMainSelected()) return true; // a Glow Trim can't merge, so ignore the right-click donor pick
                boolean same = !selectedDonorPrinted && name.equals(trimDesignName(selectedDonor));
                selectedDonor = same ? ItemStack.EMPTY : trimCache.getOrDefault(name, ItemStack.EMPTY).copy();
                selectedDonorPrinted = false;
            } else { // swap the active layer's design only, colors and the other build state carry over
                selectedMain = name; selectedPrinted = ItemStack.EMPTY;
                // A Glow Trim has no pattern scale / opacity; reset those glint-only mods so their cost never
                // shows for a glow build (speed + interpolation still apply and are kept).
                if (GlowTrimItem.STORAGE_KEY.equals(name)) { modScale = 1.0f; modOpacity = 0; }
            }
            return true;
        }

        // Right grid (2), left-click puts a printed trim in the main slot (the base, ghosted in slot 1);
        // right-click puts it in the merge slot (the donor, ghosted in slot 2). Both are preview selections,
        // not physical items (re-print to materialise a copy).
        int ri = gridIndexAt(mx, my, x + RGRID_X, y + GRID_Y, printScroll, GlintPrintedSyncPacket.CLIENT_PRINTED.size());
        if (ri >= 0) {
            // Shift-left-click pulls a real trim out of the library into the inventory (no-op if it's full).
            // An imported-but-not-yet-crafted trim is dimmed and locked; shift-clicking it instead deletes it
            // from the library.
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && hasShiftDown()) {
                if (isImportLocked(GlintPrintedSyncPacket.CLIENT_PRINTED.get(ri)))
                    ClientPacketDistributor.sendToServer(new GlintDeletePrintedPacket(ri));
                else
                    ClientPacketDistributor.sendToServer(new GlintWithdrawPacket(ri));
                return true;
            }
            ItemStack picked = GlintPrintedSyncPacket.CLIENT_PRINTED.get(ri).copy();
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                if (glowMainSelected()) return true; // a Glow Trim can't merge, so ignore the right-click donor pick
                boolean same = selectedDonorPrinted && ItemStack.isSameItemSameComponents(picked, selectedDonor);
                selectedDonor = same ? ItemStack.EMPTY : picked;
                selectedDonorPrinted = !same;
            } else {
                loadFromTrim(picked);
            }
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    /** The colour-shard selections as a dye-index array, one entry per non-empty shard. Shared by the glint
     *  and glow print paths (both send their colours the same way). */
    private int[][] shardDyeArray() { return shardDyeArray(colorShards); }

    private static int[][] shardDyeArray(List<List<Integer>> shards) {
        List<int[]> out = new ArrayList<>();
        for (List<Integer> shard : shards) {
            if (mixRgb(shard) >= 0) out.add(shard.stream().mapToInt(Integer::intValue).toArray());
        }
        return out.toArray(new int[0][]);
    }

    /** Send the current build to the server to consume materials/dyes and print the finished trim. The
     *  active layer rides the fields; the committed layers ride along as below/above tags. */
    private void onPrint() {
        ItemStack src = activeTrim();
        // A Glow Trim prints down a separate, simpler path: no design / layers / tears, just its glow colours.
        if (src.getItem() instanceof GlowTrimItem) {
            int[][] glowColorShards = shardDyeArray();
            if (glowColorShards.length == 0) return; // needs at least one glow colour
            ClientPacketDistributor.sendToServer(new GlintPrintPacket(
                    "", modSpeed, 1.0f, 0, false, false, modNamed, trimName, false,
                    CustomGlint.SCROLL_E, 0.0f, modInterpolate, glowHex, nameHex, glowColorShards, new int[0],
                    new CustomGlint.Layer[0], new CustomGlint.Layer[0], false, true, new int[0][]));
            return;
        }
        if (src.isEmpty() || !(src.getItem() instanceof GlintTrimItem)) return;
        Identifier design = GlintTrimItem.getPattern(src);
        if (design == null) return;
        int[][] shardDyes = shardDyeArray();
        int[][] glowShardDyes = glowShardsActive() ? shardDyeArray(glowShards) : new int[0][];
        CustomGlint.Layer[] below = lowerLayers.toArray(new CustomGlint.Layer[0]);
        CustomGlint.Layer[] above = upperLayers.toArray(new CustomGlint.Layer[0]);
        ClientPacketDistributor.sendToServer(new GlintPrintPacket(
                design.toString(), modSpeed, modScale, modOpacity, modGlow, glowAuto, modNamed, trimName, activeSim(),
                modScrollDir, modScrollOffset, modInterpolate, glowHex, nameHex, shardDyes, donorColors(), below, above, activeSourceSim, false,
                glowShardDyes));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Import picker: Escape or Enter closes it; other keys type into the search box.
        if (showImportPicker) {
            if (event.isEscape() || event.isConfirmation()) {
                showImportPicker = false;
                if (importSearchBox != null) importSearchBox.setFocused(false);
                return true;
            }
            // Force focus before forwarding: a screen-customization mod (e.g. FancyMenu) re-runs init() and
            // recreates this box unfocused, which would otherwise make it silently drop every key. Gate on the
            // picker's own open flag rather than isFocused() so a stale focus flag can't swallow input.
            if (importSearchBox != null) { importSearchBox.setFocused(true); importSearchBox.keyPressed(event); return true; }
        }
        // Enter closes whichever text box is being edited (mirrors the right-click-to-close toggle on the slots).
        if (event.isConfirmation()) { // Return / numpad Enter
            if (hexOpen) { closeHex(); setFocused(null); return true; }
            if (nameBox != null && nameBox.isFocused()) { nameBox.setFocused(false); setFocused(null); return true; }
        }
        // Hex box: gate on hexOpen (its authoritative state flag) + force focus. Its A-F digits include the
        // default inventory key (E), which would otherwise close the screen instead of entering the digit.
        if (hexOpen && hexBox != null && !event.isEscape()) {
            hexBox.setFocused(true);
            hexBox.keyPressed(event);
            return true;
        }
        if (nameBox != null && nameBox.isVisible() && nameBox.isFocused() && !event.isEscape()) {
            nameBox.keyPressed(event);
            return true; // swallow typing (incl. the inventory key) while editing the name
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (hexOpen && hexBox != null) { hexBox.setFocused(true); if (hexBox.charTyped(event)) return true; }
        if (showImportPicker && importSearchBox != null) { importSearchBox.setFocused(true); if (importSearchBox.charTyped(event)) return true; }
        if (nameBox != null && nameBox.isVisible() && nameBox.isFocused() && nameBox.charTyped(event)) return true;
        return super.charTyped(event);
    }

    /** Whether (mx, my) is over the cell area (not the scrollbar) of the grid whose top-left is (gx, gy). */
    private static boolean inGrid(double mx, double my, int gx, int gy) {
        return inBox(mx, my, gx, gy, GRID_COLS * CELL, GRID_ROWS * CELL);
    }

    private static int scrolled(int cur, int total, double dir) {
        return Math.max(0, Math.min(maxScrollRow(total), cur - (int) Math.signum(dir)));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingImport)    { importScroll = importScrollFromMouse(event.y()); return true; }
        if (draggingGrid == 0) { gridScroll = scrollFromMouse(event.y(), topPos + GRID_Y, trims.size()); return true; }
        if (draggingGrid == 1) { printScroll = scrollFromMouse(event.y(), topPos + GRID_Y, printedCapacity()); return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingGrid = -1;
        draggingImport = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int x = leftPos, y = topPos;
        if (showImportPicker) {
            int max = Math.max(0, importFiltered.size() - IMPORT_ROWS);
            importScroll = Math.max(0, Math.min(max, importScroll - (int) Math.signum(scrollY)));
            return true;
        }
        if (inGrid(mx, my, x + LGRID_X, y + GRID_Y)) {
            gridScroll = scrolled(gridScroll, trims.size(), scrollY);
            return true;
        }
        if (inGrid(mx, my, x + RGRID_X, y + GRID_Y)) {
            printScroll = scrolled(printScroll, printedCapacity(), scrollY);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
}
