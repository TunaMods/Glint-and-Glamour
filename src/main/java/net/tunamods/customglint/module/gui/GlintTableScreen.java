package net.tunamods.customglint.module.gui;

import net.tunamods.customglint.module.item.ModItems;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.ModConfigPaths;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import net.tunamods.customglint.module.client.GlintGuiConfig;
import net.tunamods.customglint.module.client.GlintTableModelClient;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.menu.GlintTableMenu;
import net.tunamods.customglint.module.network.GlintDeletePrintedPacket;
import net.tunamods.customglint.module.network.GlintDeleteServerBlueprintPacket;
import net.tunamods.customglint.module.network.GlintDepositPacket;
import net.tunamods.customglint.module.network.GlintGiveDesignPacket;
import net.tunamods.customglint.module.network.GlintImportPacket;
import net.tunamods.customglint.module.network.GlintPrintPacket;
import net.tunamods.customglint.module.network.GlintPrintedSyncPacket;
import net.tunamods.customglint.module.network.GlintServerBlueprintsSyncPacket;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;
import net.tunamods.customglint.module.network.GlintWithdrawPacket;
import net.tunamods.customglint.module.network.ModNetworking;

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
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The Glint Table screen: a slot-based, multi-layer trim builder. A scrollable design palette (left) and the
 * player's printed-trim library (right) flank a center column with the live preview, layer strip, color
 * shards, modifier controls and the Print button. The container slots (trim, dyes, materials, tears, rainbow
 * dye) are real menu slots drawn over the skin's PNG window. Client-only: the menu owns the authoritative
 * state, this class only builds and previews.
 */
public class GlintTableScreen extends AbstractContainerScreen<GlintTableMenu> {

    private static final int GRID_COLS = 6, GRID_ROWS = 7, CELL = 18;
    private static final int LGRID_X = 8, RGRID_X = 221, GRID_Y = 22;

    private static final int PREVIEW_X = 129, PREVIEW_Y = 68, PREVIEW_W = 82, PREVIEW_H = 82;
    private static final int PRINT_X = 128, PRINT_Y = 165, PRINT_W = 84, PRINT_H = 14;

    private static final int COLOR_STRIP_X = 122, COLOR_CELL = 12, COLOR_ICON = 12;
    private static final int COLOR_STRIP_Y = PREVIEW_Y + PREVIEW_H; // 150

    // Active skin palette
    private GlintTableSkin skin = GlintTableSkin.DEFAULT;
    private int DIM_GHOST, DIM_PREVIEW;
    private int RING_MAIN, RING_DONOR;
    private int GUI_FACE, GUI_SHADOW;
    private int SLOT_DARK;
    private int LABEL_HDR, COST_OK, COST_BAD;
    private int COLOR_UNSET, BTN_DISABLED;
    private int HOVER_TINT, BTN_HOVER;

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

    private void cycleSkin(int dir) {
        int n = GlintTableSkin.ALL.length;
        int idx = Math.floorMod(GlintTableSkin.indexOf(skin) + dir, n);
        applySkin(GlintTableSkin.ALL[idx]);
        GlintGuiConfig.setTableSkin(idx);
        GlintTableModelClient.refresh(); // the placed block follows the GUI skin
    }

    private static final int SKIN_BTN_W = 50, SKIN_BTN_H = 12;
    private static final int SKIN_BTN_X = 342 - SKIN_BTN_W - 4;
    private static final int SKIN_BTN_Y = 5;
    private static final int SND_BTN_W = 12;

    private final List<String> trims = new ArrayList<>();
    private final Map<String, ItemStack> trimCache = new HashMap<>();
    private int gridScroll = 0;
    private int printScroll = 0;
    private int draggingGrid = -1;
    private boolean draggingImport = false;

    // Import picker overlay
    // Lists two blueprint sources: this client's PERSONAL trims (config/glint-and-glamour/trims/*.json, cross-world,
    // freely deletable) and, on a dedicated server, the SHARED server blueprints (op-managed). Picking one
    // sends it to the server, which files it into the printed library as a LOCKED (dimmed) build target.
    private boolean showImportPicker = false;
    /** One import row. {@code server} = a shared server blueprint (op-deletable); else a personal client file. */
    private record ImpEntry(String name, boolean server) {}
    private final List<ImpEntry> importAll = new ArrayList<>();
    private List<ImpEntry> importFiltered = new ArrayList<>();
    private int importScroll = 0;
    private EditBox importSearchBox;
    private static final int IMPORT_ROWS = 10, IMPORT_ROW_H = 13, IMP_PW = 160;
    private static final int IMP_TRASH_W = 11; // hover-only delete hotzone at a row's right edge
    private static final int IMP_BTN_W = 12;   // square Import toggle, left of the sound button
    private Component importMsg = null;         // transient save confirmation
    private long importMsgUntil = 0;

    private String selectedMain  = null;
    private ItemStack selectedDonor = ItemStack.EMPTY;
    private boolean selectedDonorPrinted = false;
    private ItemStack selectedPrinted = ItemStack.EMPTY;

    private final List<CustomGlint.Layer> lowerLayers = new ArrayList<>();
    private final List<CustomGlint.Layer> upperLayers = new ArrayList<>();
    private static final int MAX_LAYERS = CustomGlint.MAX_LAYERS; // same cap the NBT format and the print path enforce

    private static final int LAYER_STRIP_X = 122, LAYER_CELL = 12, LAYER_ICON = 12;
    private static final int LAYER_STRIP_Y = PREVIEW_Y - LAYER_ICON;

    private float modSpeed = 1.0f, modScale = 1.0f;
    private int modOpacity = 0;
    private int modScrollDir = CustomGlint.SCROLL_E;
    private float modScrollOffset = 0.0f;
    private boolean modInterpolate = true;
    private boolean modGlow = false, modNamed = false;
    private boolean glowAuto = true;
    private boolean tearSimultaneous = true;
    private boolean activeSourceSim = false;

    private final List<List<Integer>> colorShards = new ArrayList<>();
    // A glint trim's manual glow colours live in their own shard list, edited in the SAME strip as the layer
    // colours but only while the glow mode button is focused (right-click it). glowFocused routes the strip's
    // draw / click / dye-pick / hex at these instead of the active layer's colorShards.
    private final List<List<Integer>> glowShards = new ArrayList<>();
    private boolean glowFocused = false;
    private int selectedColorIdx = -1;
    // RAINBOW: dye-slot sentinel one past the 16 real dye indices (0..15). CUSTOM_FLAG: bit tagging a shard whose color came from the rainbow (custom-hex) dye.
    private static final int RAINBOW = 16, CUSTOM_FLAG = 0x40000000;
    private EditBox hexBox;
    private boolean hexOpen = false;
    private int hexShardIdx = -1;
    private int hexMode = HEX_SHARD;
    private static final int HEX_SHARD = 0, HEX_GLOW = 1, HEX_NAME = 2;
    private int glowHex = -1, nameHex = -1;
    private ItemStack lastMain = ItemStack.EMPTY;
    private String trimName = "";
    private EditBox nameBox;
    private static final int ALPHA_MIN = CustomGlint.GLINT_ALPHA_MIN; // shared so preview/cost tracks the table's opacity→alpha mapping

    private BevelButton printBtn, glowModeBtn, glowToggleBtn, nameToggleBtn;
    private BevelButton offsetMinus, offsetPlus;

    private static final int SCROLL_X = 12, SCROLL_Y = 194, SCROLL_W = 100, SCROLL_H = 12;
    private static final int SCROLL_OFF_Y = 208;
    private static final int INTERP_X = 217, INTERP_Y = 194, INTERP_W = 116, INTERP_H = 12;
    private static final int NAME_BOX_X = 129, NAME_BOX_Y = 186, NAME_BOX_W = 62;
    // Centers a 62px button in the 129..211 span: 129 + (82-62)/2.
    private static final int GLOW_MODE_X = 139, GLOW_MODE_Y = 204, GLOW_MODE_W = 62, GLOW_MODE_H = 12;

    // Per-draw memo of the preview stacks (rebuilt many times per cascade otherwise).
    private boolean frameMemo = false;
    private ItemStack frameActiveTrim, framePreviewSource, frameActiveIcon;
    private Boolean frameCanPrint;
    private boolean frameCaptureDone;
    private CustomGlint.Layer frameCaptureActive;
    private Integer frameTotalLayers;
    private final Map<CustomGlint.Layer, ItemStack> layerIconCache = new IdentityHashMap<>();

    private boolean restored = false;
    private static Build savedBuild;

    public GlintTableScreen(GlintTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 342;
        this.imageHeight = 334;
        this.titleLabelY = 6;
        this.inventoryLabelY = 242;
    }

    private static ResourceLocation designRL(String name) {
        return CustomGlint.designFromName(name);
    }

    private static ItemStack trimStack(String name) {
        ItemStack s = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(s, designRL(name));
        return s;
    }

    private static DyeColor dyeOf(ItemStack stack) {
        return stack.getItem() instanceof DyeItem di ? di.getDyeColor() : null;
    }

    @Override
    protected void init() {
        super.init();
        applySkin(GlintTableSkin.byIndex(GlintGuiConfig.tableSkin()));
        GlintTableSkin.preloadTextures();
        addButtons();
        if (trims.isEmpty()) {
            trims.add(GlowTrimItem.STORAGE_KEY);
            trims.addAll(GlintTrimItem.PATTERNS);
        }
        if (trimCache.isEmpty()) {
            trimCache.put(GlowTrimItem.STORAGE_KEY, new ItemStack(ModItems.GLOW_TRIM.get()));
            for (String name : GlintTrimItem.PATTERNS) trimCache.put(name, trimStack(name));
        }
        this.titleLabelX = (this.imageWidth - this.font.width(this.title)) / 2;

        nameBox = new EditBox(font, leftPos + NAME_BOX_X, topPos + NAME_BOX_Y, NAME_BOX_W, 12,
                Component.translatable("screen.customglint.glint_table.name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(trimName);
        nameBox.setResponder(s -> trimName = s);
        nameBox.setVisible(modNamed);
        addRenderableWidget(nameBox);

        hexBox = new EditBox(font, leftPos, topPos, 56, COLOR_ICON, Component.translatable("screen.customglint.glint_table.hex"));
        hexBox.setMaxLength(6);
        hexBox.setResponder(this::applyHex);
        hexBox.setVisible(false);
        addWidget(hexBox);

        // Import-picker search box: an event-only widget (drawn manually inside renderImportPicker so it sits
        // on top of the modal panel), hidden until the picker opens.
        String prevImportQuery = importSearchBox != null ? importSearchBox.getValue() : "";
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

        if (!restored) { restoreBuild(); restored = true; }
    }

    @Override
    public void removed() {
        saveBuild();
        GlintGuiConfig.flush();
        layerIconCache.clear();
        super.removed();
    }

    // In-progress build persistence
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

    private void quickMoveSlot(int slot) {
        if (minecraft != null && minecraft.gameMode != null)
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, minecraft.player);
    }

    // Selection

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

    private ItemStack donorStack() {
        if (glowMainSelected()) return ItemStack.EMPTY; // a Glow Trim can't merge: no donor at all
        ItemStack phys = menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem();
        if (phys.getItem() instanceof GlintTrimItem) return phys;
        return selectedDonor;
    }

    private int[] donorColors() {
        ItemStack d = donorStack();
        return d.getItem() instanceof GlintTrimItem ? GlintTrimItem.getColors(d) : new int[0];
    }

    private boolean donorOwned() {
        if (menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem().getItem() instanceof GlintTrimItem) return true;
        if (selectedDonor.isEmpty() || selectedDonorPrinted) return true;
        String name = trimDesignName(selectedDonor);
        return name != null && GlintStoredSyncPacket.CLIENT_STORED.contains(name);
    }

    /** The active base trim: main slot, else the selected/printed trim with the modifier build state applied.
     *  The merge-slot donor is NEVER folded in: an uncommitted donor is a live preview only (its own stacked
     *  layer in the preview box), costing / counting nothing against the active layer until {@code +} commits it. */
    private ItemStack activeBase() {
        ItemStack main = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (!main.isEmpty()) return main;
        ItemStack sel = selectedPrinted.isEmpty() ? selectionPreview() : selectedPrinted;
        return applyMods(sel);
    }

    private ItemStack activeTrim() {
        if (frameMemo && frameActiveTrim != null) return frameActiveTrim;
        ItemStack result = activeBase();
        if (frameMemo) frameActiveTrim = result;
        return result;
    }

    private ItemStack previewSource() {
        if (frameMemo && framePreviewSource != null) return framePreviewSource;
        ItemStack result = computePreviewSource();
        if (frameMemo) framePreviewSource = result;
        return result;
    }

    private ItemStack computePreviewSource() { return buildStack(true); }

    /** The trim as it is currently COMMITTED: the active layer (with its mods) plus every committed lower /
     *  upper layer, but WITHOUT the uncommitted merge donor. This is what the main slot shows: a placed 4-layer
     *  trim shows its 4 layers, and a merge donor only joins once {@code +} commits it into the layer stack. */
    private ItemStack committedTrim() { return buildStack(false); }

    /** Assemble the multi-layer trim. With {@code includeDonor} the merge donor is shown STACKED on top as its
     *  own layer(s) (the live preview); without it, only the committed layers (the main slot). The donor is
     *  never colour-folded into the active layer. No layer tear / ownership is needed to preview: printing an
     *  unowned or tear-less stack is still blocked. */
    private ItemStack buildStack(boolean includeDonor) {
        CustomGlint.Layer[] pending = includeDonor ? donorLayers() : new CustomGlint.Layer[0];
        boolean stackDonor = pending.length > 0;
        ItemStack active = activeBase();
        if (lowerLayers.isEmpty() && upperLayers.isEmpty() && !stackDonor) return active;
        List<CustomGlint.Layer> all = new ArrayList<>(lowerLayers);
        boolean activeValid = active.getItem() instanceof GlintTrimItem;
        if (activeValid) {
            CustomGlint.Data ad = CustomGlint.read(active);
            if (ad != null) Collections.addAll(all, ad.layers());
        }
        all.addAll(upperLayers);
        if (stackDonor) Collections.addAll(all, pending);
        if (all.isEmpty()) return active;
        ItemStack carrier = activeValid ? active.copy() : new ItemStack(ModItems.GLINT_TRIM.get());
        if (!activeValid) GlintTrimItem.setPattern(carrier, all.get(0).design());
        CustomGlint.write(carrier, all.toArray(new CustomGlint.Layer[0]));
        return carrier;
    }

    private CustomGlint.Layer captureActive() {
        if (frameMemo && frameCaptureDone) return frameCaptureActive;
        CustomGlint.Layer result = computeCaptureActive();
        if (frameMemo) { frameCaptureActive = result; frameCaptureDone = true; }
        return result;
    }

    private CustomGlint.Layer computeCaptureActive() {
        ItemStack a = activeTrim();
        if (!(a.getItem() instanceof GlintTrimItem)) return null;
        ResourceLocation design = GlintTrimItem.getPattern(a);
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
        int alpha = c.length > 0 ? (c[0] >>> 24) & 0xFF : 255;
        modOpacity = Math.max(0, Math.min(8, Math.round((255 - alpha) * 8f / (255f - ALPHA_MIN))));
        colorShards.clear();
        selectedColorIdx = -1;
        for (int col : c) if (!isFillWhite(col)) addColorShard(col & 0xFFFFFF);
    }

    private static List<Integer> newShard(int dye) {
        List<Integer> s = new ArrayList<>();
        s.add(dye);
        return s;
    }

    /** The synthetic fill writeColors() stamps on an otherwise-colorless layer so it stays renderable (read()
     *  rejects a non-chromatic layer with zero colors). It is NOT a player-chosen colour, so the editor loads
     *  it as an EMPTY shard. Matched EXACTLY against {@link GlintTrimItem#EMPTY_FILL}, never by RGB: a real
     *  white (0xFFFFFFFF via hex, or the 0xFFF9FFFE white dye) on a printed/imported trim must survive. */
    private static boolean isFillWhite(int color) {
        return color == GlintTrimItem.EMPTY_FILL;
    }

    private void addColorShard(int rgb) { addShardTo(colorShards, rgb); }

    /** Append a shard for a stored colour to {@code shards} (up to 8): a dye shard when the rgb matches a dye,
     *  else a custom-hex shard. Shared by the layer strip and the glow strip. */
    private void addShardTo(List<List<Integer>> shards, int rgb) {
        if (shards.size() >= CustomGlint.MAX_COLORS_PER_LAYER) return;
        for (int i = 0; i < 16; i++) if (dyeRgb(i) == rgb) { shards.add(newShard(i)); return; }
        List<Integer> custom = new ArrayList<>();
        custom.add(CUSTOM_FLAG | (rgb & 0xFFFFFF));
        shards.add(custom);
    }

    private void loadFromTrim(ItemStack trim) {
        selectedPrinted = trim;
        selectedMain = null;
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
        lowerLayers.clear();
        upperLayers.clear();
        loadModsFrom(trim);
        CustomGlint.Data d = CustomGlint.read(trim);
        if (d != null && d.layers().length > 1) {
            CustomGlint.Layer[] ls = d.layers();
            for (int i = 1; i < ls.length; i++) upperLayers.add(ls[i]);
            loadControlsFromLayer(ls[0]);
        }
    }

    private static String gridNameFor(ResourceLocation p) {
        if (p.equals(CustomGlint.VANILLA)) return "vanilla";
        String name = GlintTrimItem.extractPatternName(p);
        return p.getNamespace().equals("customglint") ? name : p.getNamespace() + ":" + name;
    }

    private int totalLayers() {
        if (frameMemo && frameTotalLayers != null) return frameTotalLayers;
        int result = lowerLayers.size() + upperLayers.size() + (captureActive() != null ? 1 : 0);
        if (frameMemo) frameTotalLayers = result;
        return result;
    }

    private int layerTearCount() {
        return menu.slots.get(GlintTableMenu.SLOT_LAYER_TEAR).getItem().getCount();
    }

    private boolean layerValid(CustomGlint.Layer l) {
        if (l.colors().length == 0 || l.colors().length > CustomGlint.MAX_COLORS_PER_LAYER) return false;
        return GlintStoredSyncPacket.CLIENT_STORED.contains(gridNameFor(l.design()));
    }

    /** A layer with no player-chosen colour: a non-chromatic empty layer carries only the synthetic white fill,
     *  a chromatic one an empty palette. Either way the player still owes a colour, so this mirrors the active
     *  layer's {@code countSelectedDyes() == 0} test for the committed layers. */
    private static boolean layerColorless(CustomGlint.Layer l) {
        for (int c : l.colors()) if (!isFillWhite(c)) return false;
        return true;
    }

    /** True when any layer of the build, the active one or a committed one, still lacks a colour. */
    private boolean anyLayerColorless(int baseColorCount) {
        if (baseColorCount == 0 && countSelectedDyes() == 0) return true;
        for (CustomGlint.Layer l : lowerLayers) if (layerColorless(l)) return true;
        for (CustomGlint.Layer l : upperLayers) if (layerColorless(l)) return true;
        return false;
    }

    private ItemStack reproSource() {
        ItemStack main = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (main.getItem() instanceof GlintTrimItem) return main;
        return selectedPrinted.isEmpty() ? ItemStack.EMPTY : selectedPrinted;
    }

    private ItemStack applyMods(ItemStack base) {
        // A Glow Trim carries no glint design. It can only take colors, applied as its glow colors. It is a
        // single, non-layerable trim, so the color-shard strip drives its glow colours and nothing else.
        if (base.getItem() instanceof GlowTrimItem) {
            ItemStack s = base.copy();
            s.setCount(1);
            int[] cols = glowShardColors();
            if (cols.length > 0) { CustomGlint.setGlowColors(s, cols); CustomGlint.setGlowing(s, true); }
            else { CustomGlint.clearGlowColors(s); CustomGlint.setGlowing(s, false); }
            CustomGlint.setGlowAnim(s, modSpeed, modInterpolate); // speed + interpolation drive the glow cycle
            if (modNamed && !trimName.isEmpty()) {
                int nc = slotColor(GlintTableMenu.SLOT_NAME_DYE, nameHex);
                int rgb = nc >= 0 ? nc : 0xFFFFFF;
                s.setHoverName(Component.literal(trimName).withStyle(st -> st.withColor(TextColor.fromRgb(rgb))));
            }
            return s;
        }
        if (!(base.getItem() instanceof GlintTrimItem)) return base;
        ItemStack s = base.copy();
        GlintTrimItem.setSpeed(s, modSpeed);
        GlintTrimItem.setScale(s, modScale);
        GlintTrimItem.setScrollDir(s, modScrollDir);
        GlintTrimItem.setScrollOffset(s, modScrollOffset);
        GlintTrimItem.setColors(s, buildColors());
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
        if (modNamed && !trimName.isEmpty()) {
            int nc = slotColor(GlintTableMenu.SLOT_NAME_DYE, nameHex);
            int rgb = nc >= 0 ? nc : 0xFFFFFF;
            s.setHoverName(Component.literal(trimName).withStyle(st -> st.withColor(TextColor.fromRgb(rgb))));
        }
        ResourceLocation pat = GlintTrimItem.getPattern(s);
        if (pat != null) {
            CustomGlint.write(s, pat, GlintTrimItem.writeColors(pat, GlintTrimItem.getColors(s)), GlintTrimItem.getSpeed(s), modInterpolate,
                    GlintTrimItem.getScale(s), activeSim(), GlintTrimItem.getScrollDir(s), GlintTrimItem.getScrollOffset(s), GlintTrimItem.getSeed(s));
        }
        return s;
    }

    private int modAlpha() {
        return Math.round(255f - modOpacity * (255f - ALPHA_MIN) / 8f);
    }

    /** Whether the ACTIVE layer is simultaneous. It follows the tear toggle directly: the preview reflects the
     *  chosen mode whether or not a tear is in the slot; the tear is only required (and consumed) at print. */
    private boolean activeSim() {
        return tearSimultaneous;
    }

    private int[] buildColors() {
        int alpha = modAlpha();
        List<Integer> cols = new ArrayList<>();
        for (List<Integer> shard : colorShards) {
            int rgb = mixRgb(shard);
            if (rgb < 0) continue;
            if (cols.size() >= CustomGlint.MAX_COLORS_PER_LAYER) break;
            cols.add((alpha << 24) | rgb);
        }
        return cols.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Picked colours from a shard list as OPAQUE colours (glow has no opacity dimension, so the glass/opacity
     *  control doesn't apply: every glow colour is full-alpha). */
    private int[] opaqueColors(List<List<Integer>> shards) {
        List<Integer> cols = new ArrayList<>();
        for (List<Integer> shard : shards) {
            int rgb = mixRgb(shard);
            if (rgb < 0) continue;
            if (cols.size() >= CustomGlint.MAX_COLORS_PER_LAYER) break;
            cols.add(0xFF000000 | rgb);
        }
        return cols.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Main-strip shard colours as opaque glow colours. */
    private int[] glowShardColors() { return opaqueColors(colorShards); }

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
    private int[] glowManualColors() { return opaqueColors(glowShards); }

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
        int budget = Math.max(0, CustomGlint.MAX_COLORS_PER_LAYER - baseColors.length);
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
        // Manual glow colours charge dyes too, cumulative with everything above (their own colour budget,
        // separate from the glint colour count).
        if (glowShardsActive()) {
            int gadded = 0;
            for (List<Integer> shard : glowShards) {
                int rgb = mixRgb(shard);
                if (rgb < 0 || gadded >= CustomGlint.MAX_COLORS_PER_LAYER) continue;
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

    private static int mixRgb(List<Integer> shard) {
        if (shard.isEmpty()) return -1;
        if (shard.size() == 1) {
            int v = shard.get(0);
            if (v == RAINBOW) return -1;
            if ((v & CUSTOM_FLAG) != 0) return v & 0xFFFFFF;
        }
        int r = 0, g = 0, b = 0, n = 0;
        for (int d : shard) {
            if (d < 0 || d >= 16) continue;
            int rgb = dyeRgb(d); r += (rgb >> 16) & 0xFF; g += (rgb >> 8) & 0xFF; b += rgb & 0xFF; n++;
        }
        if (n == 0) return -1;
        return ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    private static boolean isCustomShard(List<Integer> shard) {
        if (shard.size() != 1) return false;
        int v = shard.get(0);
        return v == RAINBOW || (v & CUSTOM_FLAG) != 0;
    }

    private static int dyeRgb(int idx) {
        return GlintTrimItem.DYE_COLORS[idx] & 0xFFFFFF;
    }

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
            int alpha = c.length > 0 ? (c[0] >>> 24) & 0xFF : 255;
            modOpacity = Math.max(0, Math.min(8, Math.round((255 - alpha) * 8f / (255f - ALPHA_MIN))));
            for (int col : c) if (!isFillWhite(col)) addColorShard(col & 0xFFFFFF);
            modGlow = GlintTrimItem.isGlowing(trim);
            modNamed = trim.hasCustomHoverName();
            trimName = modNamed ? trim.getHoverName().getString() : "";
            if (nameBox != null) nameBox.setValue(trimName);
            int[] glowCols = CustomGlint.getGlowColors(trim);
            glowAuto = glowCols.length == 0;
            for (int col : glowCols) if (!isFillWhite(col)) addShardTo(glowShards, col & 0xFFFFFF);
            CustomGlint.Data d = CustomGlint.read(trim);
            if (d != null && d.layers().length > 0) {
                tearSimultaneous = d.layers()[0].simultaneous();
                modInterpolate = d.layers()[0].interpolate();
            }
            activeSourceSim = tearSimultaneous;
        } else if (trim.getItem() instanceof GlowTrimItem) {
            // A Glow Trim's colours are its glow colours; load them into the shard strip. Speed + interpolation
            // drive its glow cycle (restored from the trim); it has no scale / opacity / layers.
            modSpeed = Math.max(0.10f, Math.min(8.0f, CustomGlint.getGlowSpeed(trim)));
            modScale = 1.0f;
            modOpacity = 0;
            modScrollDir = CustomGlint.SCROLL_E;
            modScrollOffset = 0.0f;
            modInterpolate = CustomGlint.getGlowInterpolate(trim);
            modGlow = false;
            activeSourceSim = false;
            for (int col : GlowTrimItem.getColors(trim)) if (!isFillWhite(col)) addColorShard(col & 0xFFFFFF);
            modNamed = trim.hasCustomHoverName();
            trimName = modNamed ? trim.getHoverName().getString() : "";
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

    // Render

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        // Drawn before the real slots: skin window, conditional wells, ghost hints.
        skin.windowPanel(g, leftPos, topPos, imageWidth, imageHeight);
        drawConditionalWell(g, GlintTableMenu.SLOT_NAME_DYE);
        drawConditionalWell(g, GlintTableMenu.SLOT_GLOW_DYE);
        for (int i = 0; i < GlintTableMenu.TABLE_SIZE; i++) {
            if (!menu.slots.get(i).isActive()) continue;
            ItemStack ghost = ghostFor(i);
            if (!ghost.isEmpty()) drawGhostItem(g, menu.slots.get(i), ghost);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        // AbstractContainerScreen.render does not dim. Every vanilla container screen calls this itself first,
        // and JEI listens for the background event it fires to place its ingredient list behind the window.
        renderBackground(g);
        prologue();

        // Arm the per-frame memo BEFORE super.render: the widget face/label suppliers (printBtn, glowModeBtn)
        // call canPrint()/previewSource() during super.render, and without the memo armed each of those re-runs
        // the full uncached path (ItemStack.copy + NBT writes) every frame. syncButtons() also runs first so
        // widget .active states are fresh for the same draw rather than a frame stale.
        frameActiveTrim = null; framePreviewSource = null; frameActiveIcon = null; frameCanPrint = null;
        frameCaptureDone = false; frameTotalLayers = null; frameMemo = true;
        try {
            syncButtons();
            super.render(g, mx, my, dt); // renderBg + slots + renderLabels + widgets + cursor + slot tooltips
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
            if (hexBox != null && hexBox.isVisible()) hexBox.render(g, mx, my, dt);
            // The custom panels above (trim/printed grids, previews) are drawn AFTER super.render, so any glowing
            // icons among them queue their glint/glow rings too late for the container Foreground drain (which
            // already fired inside super.render). Drain them here, BEFORE the modal import picker and our own
            // tooltips, so the rings stay under both. These overlays draw with depth test off, so a later drain
            // would composite them over the modal (the picker background is otherwise opaque and covers them).
            GlowOutlineRenderer.drainGui();
            // The import picker is modal, drawn on top of everything else in the window. The custom item draws
            // (preview, color shards, layer strip via drawScaledIcon) translate to Z 200 and then renderItem
            // adds its own +150, so their icons + glint foil land at Z≈350: above a naive Z-300 push. Vanilla
            // tooltips sit at Z 400. Push the panel above both so nothing (icons, counts, glint rings, tooltips)
            // pierces it.
            if (showImportPicker) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 450);
                renderImportPicker(g, mx, my, dt);
                g.pose().popPose();
            }
            drawImportMsg(g); // transient save confirmation, on top of everything
        } finally {
            frameMemo = false;
        }
        renderTableTooltip(g, mx, my);
    }

    /** Pre-draw state settling: runs before super.render so the conditional dye-slot flags + button states are
     *  correct for the slot/widget draw. */
    private void prologue() {
        ItemStack curMain = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (!ItemStack.matches(curMain, lastMain)) {
            lastMain = curMain.copy();
            if (curMain.getItem() instanceof GlintTrimItem) loadModsFrom(curMain);
        }
        // Glow can only be focused (its shards edited) while it's actually in play; drop focus otherwise so the
        // strip falls back to the active layer.
        if (!glowShardsActive()) glowFocused = false;
        List<List<Integer>> edit = editShards();
        if (edit.isEmpty()) edit.add(new ArrayList<>());
        if (edit.size() == 1) selectedColorIdx = 0;

        // A Glow Trim can't merge. Drop any donor carried over from a previous glint build so no merge
        // preview / donor ring lingers.
        if (glowMainSelected() && !selectedDonor.isEmpty()) {
            selectedDonor = ItemStack.EMPTY;
            selectedDonorPrinted = false;
        }

        // Neither Glow nor Name is force-off when its material is absent: both preview live off the toggle, and
        // the missing glowstone / name tag only blocks the print (spelled out in the Print tooltip). Manual glow
        // now picks its colours in the shard strip, so the old single glow-dye slot stays hidden.
        menu.showNameDye = modNamed;
        menu.showGlowDye = false;
        if (nameBox != null) nameBox.setVisible(modNamed);

        if (hexBox != null) {
            boolean show;
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
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0xFF000000 | LABEL_HDR, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFF000000 | LABEL_HDR, false);
    }

    private void renderTableTooltip(GuiGraphics g, int mx, int my) {
        if (showImportPicker) return; // the modal import picker owns the screen; suppress slot/grid tooltips under it
        if (!menu.getCarried().isEmpty()) return;

        // Import toggle button (left of the sound button).
        int impBtnX = leftPos + SKIN_BTN_X - SND_BTN_W - 2 - IMP_BTN_W - 2;
        if (mx >= impBtnX && mx < impBtnX + IMP_BTN_W
                && my >= topPos + SKIN_BTN_Y && my < topPos + SKIN_BTN_Y + SKIN_BTN_H) {
            g.renderComponentTooltip(font, List.of(
                    Component.translatable("screen.customglint.glint_table.import"),
                    Component.translatable("screen.customglint.glint_table.import_rclick").withStyle(ChatFormatting.GRAY)), mx, my);
            return;
        }
        if (mx >= leftPos + SKIN_BTN_X && mx < leftPos + SKIN_BTN_X + SKIN_BTN_W
                && my >= topPos + SKIN_BTN_Y && my < topPos + SKIN_BTN_Y + SKIN_BTN_H) {
            g.renderTooltip(font, Component.translatable("screen.customglint.glint_table.skin_tooltip"), mx, my);
            return;
        }
        if (mx >= leftPos + SKIN_BTN_X - SND_BTN_W - 2 && mx < leftPos + SKIN_BTN_X - 2
                && my >= topPos + SKIN_BTN_Y && my < topPos + SKIN_BTN_Y + SKIN_BTN_H) {
            g.renderTooltip(font, Component.translatable("screen.customglint.glint_table.sound",
                    Component.translatable(GlintGuiConfig.sound()
                            ? "screen.customglint.glint_table.on" : "screen.customglint.glint_table.off")), mx, my);
            return;
        }
        if (mx >= leftPos + PRINT_X && mx < leftPos + PRINT_X + PRINT_W
                && my >= topPos + PRINT_Y && my < topPos + PRINT_Y + PRINT_H) {
            g.renderComponentTooltip(font, printTooltip(), mx, my);
            return;
        }
        // Modifier buttons carry their own explanatory tooltip (scroll / interpolation / glow / name / type /
        // the ± steppers); show whichever one the mouse is over.
        for (var child : this.children()) {
            if (!(child instanceof BevelButton bb) || bb.tooltip == null || !bb.visible) continue;
            if (mx < bb.getX() || mx >= bb.getX() + bb.getWidth() || my < bb.getY() || my >= bb.getY() + bb.getHeight()) continue;
            List<Component> lines = bb.tooltip.get();
            if (!lines.isEmpty()) { g.renderComponentTooltip(font, lines, mx, my); return; }
        }
        // Main / merge trim slots: a right-click clears the working trim / merge donor. The slots usually hold
        // a ghost PREVIEW rather than a physical item, so key off the shown stack, not hasItem().
        ItemStack mainShown = activeBase();
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
        if (layerStripVisible()) {
            int cy = topPos + LAYER_STRIP_Y;
            CustomGlint.Layer[] dl = donorLayers();
            for (Chip c : layerChips()) {
                int cx = leftPos + c.x();
                if (mx < cx || mx >= cx + LAYER_ICON || my < cy || my >= cy + LAYER_ICON) continue;
                if (c.kind() == 3) {
                    if (dl.length > 0 && totalLayers() + dl.length > MAX_LAYERS)
                        g.renderTooltip(font, Component.translatable("screen.customglint.glint_table.add_layer_cap", MAX_LAYERS), mx, my);
                    else
                        g.renderTooltip(font, Component.translatable("screen.customglint.glint_table.hint.add_layer"), mx, my);
                    return;
                }
                ResourceLocation design = c.kind() == 0 ? lowerLayers.get(c.index()).design()
                        : c.kind() == 2 ? upperLayers.get(c.index()).design()
                        : activeLayerDesign();
                List<Component> lines = new ArrayList<>();
                if (design != null) lines.add(designDisplayName(design));
                lines.add(Component.translatable(c.kind() == 1
                        ? "screen.customglint.glint_table.hint.remove_layer"
                        : "screen.customglint.glint_table.hint.edit_layer").withStyle(ChatFormatting.GRAY));
                if (c.kind() != 1 && captureActive() != null)
                    lines.add(Component.translatable("screen.customglint.glint_table.hint.layer_swap").withStyle(ChatFormatting.GRAY));
                g.renderComponentTooltip(font, lines, mx, my);
                return;
            }
        }
        // Color-shard strip: select / remove the active layer's (or focused glow's) colours (and edit a rainbow
        // shard's hex).
        if (colorShardsVisible()) {
            List<List<Integer>> shards = editShards();
            int csy = topPos + COLOR_STRIP_Y;
            int n = Math.min(shards.size(), CustomGlint.MAX_COLORS_PER_LAYER);
            for (int k = 0; k < n; k++) {
                int cx = leftPos + COLOR_STRIP_X + k * COLOR_CELL;
                if (mx < cx || mx >= cx + COLOR_ICON || my < csy || my >= csy + COLOR_ICON) continue;
                List<Component> lines = new ArrayList<>();
                lines.add(shardColorLabel(shards.get(k)));
                if (selectedColorIdx == k) {
                    if (isCustomShard(shards.get(k)))
                        lines.add(Component.translatable("screen.customglint.glint_table.hint.shard_hex").withStyle(ChatFormatting.GRAY));
                    lines.add(Component.translatable("screen.customglint.glint_table.hint.shard_remove").withStyle(ChatFormatting.GRAY));
                } else {
                    lines.add(Component.translatable("screen.customglint.glint_table.hint.shard_select").withStyle(ChatFormatting.GRAY));
                    if (selectedColorIdx >= 0)
                        lines.add(Component.translatable("screen.customglint.glint_table.hint.shard_swap").withStyle(ChatFormatting.GRAY));
                }
                g.renderComponentTooltip(font, lines, mx, my);
                return;
            }
            if (n < CustomGlint.MAX_COLORS_PER_LAYER && !hexOpen) {
                int cx = leftPos + COLOR_STRIP_X + n * COLOR_CELL;
                if (mx >= cx && mx < cx + COLOR_ICON && my >= csy && my < csy + COLOR_ICON) {
                    g.renderTooltip(font, Component.translatable("screen.customglint.glint_table.hint.add_color"), mx, my);
                    return;
                }
            }
        }
        // Dye bar: the 16 shade slots recolour the SELECTED shard and work even when empty (charged at print).
        for (int i = 0; i < 16; i++) {
            Slot s = menu.slots.get(GlintTableMenu.SLOT_DYE_START + i);
            int sx = leftPos + s.x, sy = topPos + s.y;
            if (mx < sx || mx >= sx + 16 || my < sy || my >= sy + 16) continue;
            g.renderComponentTooltip(font, List.of(
                    Component.translatable("color.minecraft." + DyeColor.byId(i).getName()),
                    Component.translatable("screen.customglint.glint_table.hint.dye_set").withStyle(ChatFormatting.GRAY),
                    Component.translatable("screen.customglint.glint_table.hint.dye_mix").withStyle(ChatFormatting.GRAY)), mx, my);
            return;
        }
        {
            Slot rs = menu.slots.get(GlintTableMenu.SLOT_RAINBOW_DYE);
            int rx = leftPos + rs.x, ry = topPos + rs.y;
            if (mx >= rx && mx < rx + 16 && my >= ry && my < ry + 16) {
                ItemStack rstack = rs.hasItem() ? rs.getItem() : ModItems.RAINBOW_DYE.get().getDefaultInstance();
                List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, rstack));
                lines.add(Component.translatable("screen.customglint.glint_table.hint.rainbow_hex").withStyle(ChatFormatting.GRAY));
                g.renderComponentTooltip(font, lines, mx, my);
                return;
            }
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
            g.renderComponentTooltip(font, lines, mx, my);
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
                g.renderComponentTooltip(font, lines, mx, my);
            }
        }
    }

    /** Item tooltip (its own lines) + a trailing gray hint line, for the ghost-holding main/merge slots. */
    private void appendSlotHint(GuiGraphics g, ItemStack shown, String hintKey, int mx, int my) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, shown));
        lines.add(Component.translatable(hintKey).withStyle(ChatFormatting.GRAY));
        g.renderComponentTooltip(font, lines, mx, my);
    }

    private void drawConditionalWell(GuiGraphics g, int containerSlot) {
        Slot s = menu.slots.get(containerSlot);
        if (s.isActive()) slotWell(g, leftPos + s.x - 1, topPos + s.y - 1);
    }

    private static ItemStack cyclingDyeGhost(int phase) {
        int n = GlintTableMenu.DYE_ITEMS.length;
        int idx = (int) (((Util.getMillis() / 1500) + phase) % n);
        return new ItemStack(GlintTableMenu.DYE_ITEMS[idx]);
    }

    // The constant ghost hints (materials, palette dyes, tears) never change, but the tear ghosts run a
    // full CustomGlint.write through getDefaultInstance(); cache them so renderBg doesn't rebuild every
    // frame. The two cycling-dye wells animate and are rebuilt per call below.
    private static final Map<Integer, ItemStack> GHOST_CACHE = new HashMap<>();

    private ItemStack ghostFor(int containerSlot) {
        if (containerSlot == GlintTableMenu.SLOT_NAME_DYE) return cyclingDyeGhost(0);
        if (containerSlot == GlintTableMenu.SLOT_GLOW_DYE) return cyclingDyeGhost(8);
        return GHOST_CACHE.computeIfAbsent(containerSlot, GlintTableScreen::buildGhost);
    }

    private static ItemStack buildGhost(int containerSlot) {
        if (containerSlot >= GlintTableMenu.SLOT_DYE_START && containerSlot < GlintTableMenu.SLOT_DYE_START + 16) {
            return new ItemStack(GlintTableMenu.DYE_ITEMS[containerSlot - GlintTableMenu.SLOT_DYE_START]);
        }
        return switch (containerSlot) {
            case GlintTableMenu.SLOT_REDSTONE  -> new ItemStack(Items.REDSTONE);
            case GlintTableMenu.SLOT_SLIME     -> new ItemStack(Items.SLIME_BALL);
            case GlintTableMenu.SLOT_GLASS     -> new ItemStack(Items.GLASS);
            case GlintTableMenu.SLOT_GLOWSTONE -> new ItemStack(Items.GLOWSTONE_DUST);
            case GlintTableMenu.SLOT_NAMETAG   -> new ItemStack(Items.NAME_TAG);
            case GlintTableMenu.SLOT_TEAR      -> ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance();
            case GlintTableMenu.SLOT_TEAR_SEQ  -> ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance();
            case GlintTableMenu.SLOT_LAYER_TEAR -> ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance();
            case GlintTableMenu.SLOT_RAINBOW_DYE -> ModItems.RAINBOW_DYE.get().getDefaultInstance();
            // SLOT_NAME_DYE / SLOT_GLOW_DYE animate and are handled in ghostFor.
            default -> ItemStack.EMPTY;
        };
    }

    private void drawGhostItem(GuiGraphics g, Slot slot, ItemStack ghost) {
        if (slot.hasItem()) return;
        int x = leftPos + slot.x, y = topPos + slot.y;
        g.renderItem(ghost, x, y);
        overlay(g, x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    private static String trimDesignName(ItemStack stack) {
        if (stack.getItem() instanceof GlowTrimItem) return GlowTrimItem.STORAGE_KEY;
        ResourceLocation p = GlintTrimItem.getPattern(stack);
        if (p == null) return null;
        if (p.equals(CustomGlint.VANILLA)) return "vanilla";
        String name = GlintTrimItem.extractPatternName(p);
        return p.getNamespace().equals("customglint") ? name : p.getNamespace() + ":" + name;
    }

    private String highlightedMain() {
        String n = trimDesignName(menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem());
        return n != null ? n : selectedMain;
    }

    private String highlightedDonor() {
        String n = trimDesignName(menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem());
        if (n != null) return n;
        return (!selectedDonor.isEmpty() && !selectedDonorPrinted) ? trimDesignName(selectedDonor) : null;
    }

    private void drawTrimGrid(GuiGraphics g, int mx, int my) {
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
                g.renderItem(trimCache.getOrDefault(name, ItemStack.EMPTY), cx, cy);
                if (!GlintStoredSyncPacket.CLIENT_STORED.contains(name)) overlay(g, cx, cy, cx + 16, cy + 16, DIM_GHOST);
                if (name.equals(mainName))       border(g, cx - 1, cy - 1, 18, 18, RING_MAIN);
                else if (name.equals(donorName)) border(g, cx - 1, cy - 1, 18, 18, RING_DONOR);
                if (mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) overlay(g, cx, cy, cx + 16, cy + 16, HOVER_TINT);
            }
        }
        drawScrollbar(g, gx, gy, total, gridScroll);
    }

    /** Cells the printed grid scrolls over: the filled rows plus one spare row, never below one screenful and
     *  never past 128, the library cap the server enforces. */
    private int printedCapacity() {
        int size = GlintPrintedSyncPacket.CLIENT_PRINTED.size();
        int cells = ((size / GRID_COLS) + 2) * GRID_COLS;
        return Math.max(GRID_ROWS * GRID_COLS, Math.min(128, cells));
    }

    private void drawPrintedGrid(GuiGraphics g, int mx, int my) {
        int gx = leftPos + RGRID_X, gy = topPos + GRID_Y;
        List<ItemStack> list = GlintPrintedSyncPacket.CLIENT_PRINTED;
        int cap = printedCapacity();
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = (printScroll + row) * GRID_COLS + col;
                if (idx >= cap) continue;
                int cx = gx + col * CELL, cy = gy + row * CELL;
                slotWell(g, cx - 1, cy - 1);
                if (idx < list.size()) {
                    ItemStack s = list.get(idx);
                    g.renderItem(s, cx, cy);
                    // An imported-but-not-yet-crafted trim is dimmed + locked (shift-click deletes it).
                    if (isImportLocked(s)) overlay(g, cx, cy, cx + 16, cy + 16, DIM_PREVIEW);
                    if (!selectedPrinted.isEmpty() && ItemStack.isSameItemSameTags(s, selectedPrinted))
                        border(g, cx - 1, cy - 1, 18, 18, RING_MAIN);
                    else if (selectedDonorPrinted && ItemStack.isSameItemSameTags(s, selectedDonor))
                        border(g, cx - 1, cy - 1, 18, 18, RING_DONOR);
                }
                if (mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) overlay(g, cx, cy, cx + 16, cy + 16, HOVER_TINT);
            }
        }
        drawScrollbar(g, gx, gy, cap, printScroll);
    }

    private void drawScrollbar(GuiGraphics g, int gx, int gy, int total, int scroll) {
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        int maxRow = Math.max(0, rows - GRID_ROWS);
        if (maxRow <= 0) return;
        int trackX = gx + GRID_COLS * CELL + 1, trackH = GRID_ROWS * CELL;
        g.fill(trackX, gy, trackX + 4, gy + trackH, SLOT_DARK);
        int thumbH = Math.max(8, trackH * GRID_ROWS / rows);
        int thumbY = gy + (int) ((trackH - thumbH) * (float) scroll / maxRow);
        g.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, GUI_SHADOW);
        g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH - 1, GUI_FACE);
    }

    private boolean onScrollbar(double mx, double my, int gx, int gy, int total) {
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        if (rows - GRID_ROWS <= 0) return false;
        int trackX = gx + GRID_COLS * CELL + 1, trackH = GRID_ROWS * CELL;
        return mx >= trackX && mx < trackX + 4 && my >= gy && my < gy + trackH;
    }

    private int scrollFromMouse(double my, int gy, int total) {
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        int maxRow = Math.max(0, rows - GRID_ROWS);
        if (maxRow <= 0) return 0;
        int trackH = GRID_ROWS * CELL;
        int thumbH = Math.max(8, trackH * GRID_ROWS / rows);
        float f = (float) ((my - gy - thumbH / 2.0) / (trackH - thumbH));
        return Math.round(Math.max(0f, Math.min(1f, f)) * maxRow);
    }

    /** The main slot shows the trim as currently COMMITTED: all its committed layers with the active layer's
     *  mods, but not the uncommitted merge donor (that joins only in the preview box until {@code +} commits
     *  it). Dimmed while the base design isn't owned. */
    private void drawMainPreview(GuiGraphics g) {
        Slot main = menu.slots.get(GlintTableMenu.SLOT_TRIM);
        if (main.hasItem()) return;
        ItemStack sel = committedTrim();
        if (sel.isEmpty()) return;
        int x = leftPos + main.x, y = topPos + main.y;
        g.renderItem(sel, x, y);
        boolean owned = !selectedPrinted.isEmpty()
                || (selectedMain != null && GlintStoredSyncPacket.CLIENT_STORED.contains(selectedMain));
        if (!owned) overlay(g, x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    private void drawMainSlotRings(GuiGraphics g) {
        Slot s1 = menu.slots.get(GlintTableMenu.SLOT_TRIM);
        Slot s2 = menu.slots.get(GlintTableMenu.SLOT_TRIM_B);
        border(g, leftPos + s1.x - 1, topPos + s1.y - 1, 18, 18, RING_MAIN);
        border(g, leftPos + s2.x - 1, topPos + s2.y - 1, 18, 18, RING_DONOR);
    }

    private void drawDonorPreview(GuiGraphics g) {
        Slot s = menu.slots.get(GlintTableMenu.SLOT_TRIM_B);
        if (s.hasItem() || selectedDonor.isEmpty()) return;
        int x = leftPos + s.x, y = topPos + s.y;
        g.renderItem(selectedDonor, x, y);
        if (!donorOwned()) overlay(g, x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    // Layer strip

    private record Chip(int x, int kind, int index) {}

    private List<Chip> layerChips() {
        List<Chip> chips = new ArrayList<>();
        int i = 0;
        // A Glow Trim is a single, non-layerable trim: it gets exactly the one layer-1 chip and no [+] add chip
        // (it can never carry lower/upper layers). A Glint Trim gets its layer stack + the add chip as usual.
        boolean glow = activeTrim().getItem() instanceof GlowTrimItem;
        for (int k = 0; k < lowerLayers.size(); k++) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 0, k));
        if (glow || activeTrim().getItem() instanceof GlintTrimItem) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 1, 0));
        for (int k = 0; k < upperLayers.size(); k++) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 2, k));
        if (i < MAX_LAYERS && !glow) chips.add(new Chip(LAYER_STRIP_X + i * LAYER_CELL, 3, 0));
        return chips;
    }

    private ItemStack layerIcon(CustomGlint.Layer l) {
        ItemStack cached = layerIconCache.get(l);
        if (cached != null) return cached;
        ItemStack s = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(s, l.design());
        CustomGlint.write(s, new CustomGlint.Layer[]{ l });
        layerIconCache.put(l, s);
        return s;
    }

    private ItemStack activeLayerIcon() {
        if (frameMemo && frameActiveIcon != null) return frameActiveIcon;
        CustomGlint.Layer active = captureActive();
        ItemStack result;
        if (active == null) {
            result = activeTrim();
        } else {
            result = new ItemStack(ModItems.GLINT_TRIM.get());
            GlintTrimItem.setPattern(result, active.design());
            CustomGlint.write(result, new CustomGlint.Layer[]{ active });
        }
        if (frameMemo) frameActiveIcon = result;
        return result;
    }

    /** The layer strip is always visible so layer 1 (and the [+] add chip) is a constant starting point, like
     *  the color-shard strip; layer chips render only for layers that actually exist. */
    private boolean layerStripVisible() {
        return true;
    }

    /** The color-shard strip only means something once a design is being built (an active trim exists); before
     *  that it's an inert disabled "+". */
    private boolean colorShardsVisible() {
        ItemStack a = activeTrim();
        // A Glow Trim can only take colors (its glow colours), so its shard strip shows too.
        return a.getItem() instanceof GlintTrimItem || a.getItem() instanceof GlowTrimItem;
    }

    private void drawLayerStrip(GuiGraphics g, int mx, int my) {
        if (!layerStripVisible()) return;
        for (Chip c : layerChips()) {
            int x = leftPos + c.x(), y = topPos + LAYER_STRIP_Y;
            if (c.kind() == 3) {
                boolean ok = canAddLayer();
                boolean hover = ok && mx >= x && mx < x + LAYER_ICON && my >= y && my < y + LAYER_ICON;
                raisedPanel(g, x, y, LAYER_ICON, LAYER_ICON, hover ? BTN_HOVER : (ok ? GUI_FACE : BTN_DISABLED));
                centered(g, "+", x + LAYER_ICON / 2, y + 2, ok ? LABEL_HDR : COST_BAD);
                continue;
            }
            g.fill(x, y, x + LAYER_ICON, y + LAYER_ICON, SLOT_DARK);
            ItemStack icon = c.kind() == 0 ? layerIcon(lowerLayers.get(c.index()))
                    : c.kind() == 2 ? layerIcon(upperLayers.get(c.index()))
                    : activeLayerIcon();
            drawScaledIcon(g, icon, x, y, LAYER_ICON);
            shardBevel(g, x, y, LAYER_ICON);
            // While glow is focused the colour strip edits the glow colours, not this layer. Drop the active
            // layer's selection ring so it reads as unfocused (the glow mode button carries the highlight).
            if (c.kind() == 1 && !glowFocused) border(g, x, y, LAYER_ICON, LAYER_ICON, RING_MAIN);
            if (mx >= x && mx < x + LAYER_ICON && my >= y && my < y + LAYER_ICON)
                overlay(g, x, y, x + LAYER_ICON, y + LAYER_ICON, HOVER_TINT);
        }
    }

    // Color-shard strip

    private void drawColorStrip(GuiGraphics g, int mx, int my) {
        List<List<Integer>> shards = editShards();
        int n = colorShardsVisible() ? Math.min(shards.size(), CustomGlint.MAX_COLORS_PER_LAYER) : 0;
        for (int k = 0; k < n; k++) {
            int x = leftPos + COLOR_STRIP_X + k * COLOR_CELL, y = topPos + COLOR_STRIP_Y;
            List<Integer> shard = shards.get(k);
            int rgb = mixRgb(shard);
            if (rgb < 0 && isCustomShard(shard)) {
                g.fill(x, y, x + COLOR_ICON, y + COLOR_ICON, SLOT_DARK);
                drawScaledIcon(g, ModItems.RAINBOW_DYE.get().getDefaultInstance(), x, y, COLOR_ICON);
            } else {
                g.fill(x, y, x + COLOR_ICON, y + COLOR_ICON, rgb < 0 ? COLOR_UNSET : 0xFF000000 | rgb);
            }
            shardBevel(g, x, y, COLOR_ICON);
            if (k == selectedColorIdx) border(g, x, y, COLOR_ICON, COLOR_ICON, RING_MAIN);
            if (mx >= x && mx < x + COLOR_ICON && my >= y && my < y + COLOR_ICON)
                overlay(g, x, y, x + COLOR_ICON, y + COLOR_ICON, HOVER_TINT);
        }
        if (n < CustomGlint.MAX_COLORS_PER_LAYER && !hexOpen) {
            int x = leftPos + COLOR_STRIP_X + n * COLOR_CELL, y = topPos + COLOR_STRIP_Y;
            boolean on = colorShardsVisible();
            boolean hover = on && mx >= x && mx < x + COLOR_ICON && my >= y && my < y + COLOR_ICON;
            raisedPanel(g, x, y, COLOR_ICON, COLOR_ICON, hover ? BTN_HOVER : (on ? GUI_FACE : BTN_DISABLED));
            centered(g, "+", x + COLOR_ICON / 2, y + 2, on ? LABEL_HDR : COST_BAD);
        }
    }

    private boolean colorStripClick(double mx, double my, int button) {
        if (!colorShardsVisible()) return false; // no preview: the strip is just an inert "+", nothing to click
        List<List<Integer>> shards = editShards();
        int y = topPos + COLOR_STRIP_Y;
        int n = Math.min(shards.size(), CustomGlint.MAX_COLORS_PER_LAYER);
        for (int k = 0; k < n; k++) {
            int x = leftPos + COLOR_STRIP_X + k * COLOR_CELL;
            if (mx < x || mx >= x + COLOR_ICON || my < y || my >= y + COLOR_ICON) continue;
            if (button == 0 && hasShiftDown() && selectedColorIdx >= 0 && selectedColorIdx != k
                    && selectedColorIdx < shards.size()) { // shift-left-click another shard: swap the two colours
                Collections.swap(shards, selectedColorIdx, k);
                selectedColorIdx = k; // selection follows the colour we moved
                closeHex();
            } else if (button == 1 && selectedColorIdx == k) { // right-click the SELECTED shard: delete it
                shards.remove(k);
                selectedColorIdx = -1;
                closeHex();
            } else if (button != 1 && selectedColorIdx == k) { // left re-click the selected shard
                if (isCustomShard(shards.get(k))) { if (hexOpen) closeHex(); else openHex(k); }
            } else { // any click on an unselected shard selects it, never unselects
                selectedColorIdx = k;
                closeHex();
            }
            return true;
        }
        if (n < CustomGlint.MAX_COLORS_PER_LAYER && !hexOpen) {
            int x = leftPos + COLOR_STRIP_X + n * COLOR_CELL;
            if (mx >= x && mx < x + COLOR_ICON && my >= y && my < y + COLOR_ICON) {
                if (button == 0) { shards.add(new ArrayList<>()); selectedColorIdx = n; closeHex(); }
                return true;
            }
        }
        return false;
    }

    private void openHex(int k) {
        hexMode = HEX_SHARD;
        hexShardIdx = k;
        int v = editShards().get(k).get(0);
        openHexWith((v & CUSTOM_FLAG) != 0 ? String.format("%06X", v & 0xFFFFFF) : "");
    }

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

    private boolean rainbowInSlot(int slotConst) {
        return menu.slots.get(slotConst).getItem().getItem() == ModItems.RAINBOW_DYE.get();
    }

    /** The {@code +} layer shard is addable (white) whenever a trim is in the merge slot AND stacking it would
     *  not push past {@link #MAX_LAYERS}. Ownership and layer-tear cost are NOT gated here: they are preview-
     *  free like {@link #computePreviewSource()}, and an unowned / tear-short stack is still blocked at print. */
    private boolean canAddLayer() {
        if (activeTrim().getItem() instanceof GlowTrimItem) return false; // a Glow Trim can never be layered
        CustomGlint.Layer[] dl = donorLayers();
        return dl.length > 0 && totalLayers() + dl.length <= MAX_LAYERS;
    }

    private CustomGlint.Layer[] donorLayers() {
        ItemStack d = donorStack();
        if (!(d.getItem() instanceof GlintTrimItem)) return new CustomGlint.Layer[0];
        CustomGlint.Data dd = CustomGlint.read(d);
        if (dd != null && dd.layers().length > 0) return dd.layers();
        ResourceLocation design = GlintTrimItem.getPattern(d);
        if (design == null) return new CustomGlint.Layer[0];
        return new CustomGlint.Layer[]{ new CustomGlint.Layer(design, GlintTrimItem.getColors(d),
                GlintTrimItem.getSpeed(d), true, GlintTrimItem.getScale(d), false,
                GlintTrimItem.getScrollDir(d), GlintTrimItem.getScrollOffset(d)) };
    }

    private void addLayer() {
        CustomGlint.Layer[] dl = donorLayers();
        if (dl.length == 0 || totalLayers() + dl.length > MAX_LAYERS) return;
        Collections.addAll(upperLayers, dl);
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
    }

    private void editLayer(int kind, int k) {
        CustomGlint.Layer cur = captureActive();
        if (kind == 0) {
            CustomGlint.Layer clicked = lowerLayers.get(k);
            List<CustomGlint.Layer> head = new ArrayList<>(lowerLayers.subList(0, k));
            List<CustomGlint.Layer> tail = new ArrayList<>(lowerLayers.subList(k + 1, lowerLayers.size()));
            List<CustomGlint.Layer> newUpper = new ArrayList<>(tail);
            if (cur != null) newUpper.add(cur);
            newUpper.addAll(upperLayers);
            lowerLayers.clear(); lowerLayers.addAll(head);
            upperLayers.clear(); upperLayers.addAll(newUpper);
            loadControlsFromLayer(clicked);
        } else if (kind == 2) {
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
        if (active == null) return;
        List<CustomGlint.Layer> combined = new ArrayList<>(lowerLayers);
        int activeIdx = combined.size();
        combined.add(active);
        combined.addAll(upperLayers);
        int p = (kind == 0) ? k : activeIdx + 1 + k;
        if (p < 0 || p >= combined.size() || p == activeIdx) return;
        Collections.swap(combined, activeIdx, p);
        lowerLayers.clear(); lowerLayers.addAll(combined.subList(0, p));
        upperLayers.clear(); upperLayers.addAll(combined.subList(p + 1, combined.size()));
    }

    /** Delete the active (selected) layer: promote an adjacent committed layer into active, or blank the editor
     *  when it was the only one. */
    private void removeActiveLayer() {
        if (!upperLayers.isEmpty()) { loadControlsFromLayer(upperLayers.remove(0)); return; }
        if (!lowerLayers.isEmpty()) { loadControlsFromLayer(lowerLayers.remove(lowerLayers.size() - 1)); return; }
        clearBuild();
    }

    private void drawPreview(GuiGraphics g) {
        int bx = leftPos + PREVIEW_X, by = topPos + PREVIEW_Y;
        ItemStack src = previewSource();
        if (src.isEmpty()) return;
        // Mirror the wand editor's working GUI-glint path: scissor to the box + z 200 so the custom glint
        // foil (which depth-tests EQUAL) renders on top instead of being culled at a lower stratum.
        g.enableScissor(bx, by, bx + PREVIEW_W, by + PREVIEW_H);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(bx + PREVIEW_W / 2f, by + PREVIEW_H / 2f, 200);
        float scale = (Math.min(PREVIEW_W, PREVIEW_H) - 2) / 16f;
        pose.scale(scale, scale, 1f);
        g.renderItem(src, -8, -8);
        pose.popPose();
        g.disableScissor();
    }

    private DyeColor dyeIn(int slotConst) {
        return dyeOf(menu.slots.get(slotConst).getItem());
    }

    private int slotColor(int slotConst, int customHex) {
        if (rainbowInSlot(slotConst)) return customHex;
        DyeColor d = dyeIn(slotConst);
        return d != null ? dyeRgb(d.ordinal()) : -1;
    }

    private void drawDyeSelection(GuiGraphics g) {
        List<List<Integer>> shards = editShards();
        if (selectedColorIdx < 0 || selectedColorIdx >= shards.size()) return;
        for (int idx : shards.get(selectedColorIdx)) {
            Slot s = (idx == RAINBOW || (idx & CUSTOM_FLAG) != 0)
                    ? menu.slots.get(GlintTableMenu.SLOT_RAINBOW_DYE)
                    : menu.slots.get(GlintTableMenu.SLOT_DYE_START + idx);
            border(g, leftPos + s.x - 1, topPos + s.y - 1, 18, 18, RING_MAIN);
        }
    }

    private boolean glowTrimMain() {
        return previewSource().getItem() instanceof GlowTrimItem;
    }

    private void drawNameGlowSection(GuiGraphics g) {
        if (!modNamed) {
            int nx = leftPos + NAME_BOX_X, ny = topPos + NAME_BOX_Y;
            raisedPanel(g, nx, ny, NAME_BOX_W, 12, GUI_FACE);
            var pose = g.pose();
            pose.pushPose();
            pose.translate(nx + NAME_BOX_W / 2f, ny + 3f, 0);
            pose.scale(0.85f, 0.85f, 1f);
            centered(g, Component.translatable("screen.customglint.glint_table.not_available").getString(), 0, 0, COST_BAD);
            pose.popPose();
        }
    }

    /** Material costs {redstone, slime, glass} for the whole trim, mirroring {@code GlintTableMenu.print}:
     *  one redstone / slime per ± speed / scale step off 1×, one glass per opacity level, tallied across the
     *  active + every committed layer. A Glow Trim has no pattern scale or opacity, so those cost nothing for
     *  it: only its glow-cycle speed (redstone) counts. */
    private int[] layerCosts() {
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
    private static int layerGlass(CustomGlint.Layer l) { int[] c = l.colors(); return c.length > 0 ? CustomGlint.glassCost((c[0] >>> 24) & 0xFF) : 0; }

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

    private boolean canPrint() {
        if (frameMemo && frameCanPrint != null) return frameCanPrint;
        boolean result = computeCanPrint();
        if (frameMemo) frameCanPrint = result;
        return result;
    }

    private boolean computeCanPrint() {
        ItemStack src = activeTrim();
        // A Glow Trim only needs its glow colours (at least one) and the dyes to pay for them, plus redstone
        // when its glow-cycle speed is tuned off 1×. No design / layers / tears to validate.
        if (src.getItem() instanceof GlowTrimItem)
            return countSelectedDyes() > 0 && dyesAffordable()
                    && menu.slots.get(GlintTableMenu.SLOT_REDSTONE).getItem().getCount() >= layerCosts()[0];
        if (src.isEmpty() || !(src.getItem() instanceof GlintTrimItem) || GlintTrimItem.getPattern(src) == null) return false;
        if (totalLayers() > MAX_LAYERS) return false;
        if (layerTearCount() < totalLayers() - 1) return false;
        for (CustomGlint.Layer l : lowerLayers) if (!layerValid(l)) return false;
        for (CustomGlint.Layer l : upperLayers) if (!layerValid(l)) return false;

        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        boolean fromBase = base.getItem() instanceof GlintTrimItem;
        if (!fromBase && selectedPrinted.isEmpty()
                && (selectedMain == null || !GlintStoredSyncPacket.CLIENT_STORED.contains(selectedMain))) return false;
        if (!donorOwned()) return false;

        // The merge-slot donor is NOT counted against the active layer's colours: an uncommitted donor is
        // preview-only and prints nothing until [+] commits it as its own layer, so it can't overflow the cap.
        int baseColorCount = fromBase ? GlintTrimItem.getColors(base).length : 0;
        if (anyLayerColorless(baseColorCount)) return false;

        ItemStack repro = reproSource();
        if (!repro.isEmpty()) {
            CustomGlint.Data d = CustomGlint.read(repro);
            if (d != null && d.layers().length > 1) return false;
            if (d != null && d.layers().length == 1
                    && d.layers()[0].simultaneous() && d.layers()[0].colors().length >= 2
                    && tearSimultaneous && !menu.slots.get(GlintTableMenu.SLOT_TEAR).hasItem()) return false;
        }
        if (menu.slots.get(GlintTableMenu.SLOT_TEAR).getItem().getCount() < simTearCost()) return false;
        if (menu.slots.get(GlintTableMenu.SLOT_TEAR_SEQ).getItem().getCount() < seqTearCost()) return false;

        boolean baseGlowing = fromBase && CustomGlint.isGlowing(base);
        boolean baseHasGlowColors = fromBase && CustomGlint.getGlowColors(base).length > 0;
        boolean baseNamed = fromBase && base.hasCustomHoverName();
        int[] cost = layerCosts();
        if (menu.slots.get(GlintTableMenu.SLOT_REDSTONE).getItem().getCount() < cost[0]) return false;
        if (menu.slots.get(GlintTableMenu.SLOT_SLIME).getItem().getCount() < cost[1]) return false;
        if (menu.slots.get(GlintTableMenu.SLOT_GLASS).getItem().getCount() < cost[2]) return false;
        if (modGlow && !baseGlowing && menu.slots.get(GlintTableMenu.SLOT_GLOWSTONE).getItem().getCount() < totalLayers()) return false;
        if (modNamed && !baseNamed && !menu.slots.get(GlintTableMenu.SLOT_NAMETAG).hasItem()) return false;
        if (modGlow && !glowAuto && glowColorCount() == 0 && !baseHasGlowColors) return false;
        // Every colour across every layer now costs a dye (cumulative): the dye bar must hold enough of each
        // shade, plus one rainbow dye per custom / non-dye colour.
        if (!dyesAffordable()) return false;
        return true;
    }

    /** Structural blockers only (bad config, ownership, color count, plus the name-tag gate and manual-glow
     *  colour choice), shown red in the print tooltip. The consumed material shortfalls (redstone/slime/glass/
     *  glowstone/tears) are NOT listed here: they render as the always-visible cost breakdown that turns green
     *  per-line as each is satisfied. */
    private List<Component> printIssues() {
        List<Component> out = new ArrayList<>();
        ItemStack src = activeTrim();
        // A Glow Trim has no design/layers. Its only requirement is at least one glow colour, so it never
        // shows "pick a design".
        if (src.getItem() instanceof GlowTrimItem) {
            if (countSelectedDyes() == 0) out.add(Component.translatable("screen.customglint.glint_table.issue.add_color"));
            return out;
        }
        if (src.isEmpty() || !(src.getItem() instanceof GlintTrimItem) || GlintTrimItem.getPattern(src) == null) {
            out.add(Component.translatable("screen.customglint.glint_table.issue.pick_design"));
            return out;
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
        // layer is currently open, even when the colourless one isn't the layer being edited.
        if (anyLayerColorless(baseColorCount)) out.add(Component.translatable("screen.customglint.glint_table.issue.layer_missing_color"));

        ItemStack repro = reproSource();
        if (!repro.isEmpty()) {
            CustomGlint.Data d = CustomGlint.read(repro);
            if (d != null && d.layers().length > 1) out.add(Component.translatable("screen.customglint.glint_table.issue.no_multilayer"));
        }

        // The name tag and the manual-glow colour aren't in the Consumes list (a gate / a colour choice), so
        // they stay here as structural blockers.
        boolean baseNamed = fromBase && base.hasCustomHoverName();
        boolean baseHasGlowColors = fromBase && CustomGlint.getGlowColors(base).length > 0;
        if (modNamed && !baseNamed && !menu.slots.get(GlintTableMenu.SLOT_NAMETAG).hasItem())
            out.add(Component.translatable("screen.customglint.glint_table.issue.name_needs", itemName(Items.NAME_TAG)));
        if (modGlow && !glowAuto && glowColorCount() == 0 && !baseHasGlowColors)
            out.add(Component.translatable("screen.customglint.glint_table.issue.glow_manual_needs"));
        return out;
    }

    private static Component itemName(Item item) {
        return new ItemStack(item).getHoverName();
    }

    private List<Component> printTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.customglint.glint_table.print").withStyle(ChatFormatting.WHITE));
        // Structural blockers (config/ownership): always red. These don't gate the cost list below: the
        // material costs stay visible so a fulfilled cost turns green instead of vanishing.
        List<Component> issues = printIssues();
        for (Component s : issues) lines.add(Component.literal("• ").append(s).withStyle(ChatFormatting.RED));

        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        boolean fromBase = base.getItem() instanceof GlintTrimItem;
        boolean baseGlowing = fromBase && CustomGlint.isGlowing(base);
        boolean any = false;
        // Dyes for every colour across all layers (cumulative: a shade reused costs a dye each time), plus one
        // rainbow dye per custom / non-dye colour.
        DyeReq req = dyeReq();
        for (int i = 0; i < 16; i++)
            if (req.counts()[i] > 0) { reqLine(lines, itemName(GlintTableMenu.DYE_ITEMS[i]), req.counts()[i], GlintTableMenu.SLOT_DYE_START + i); any = true; }
        if (req.rainbow() > 0)       { reqLine(lines, itemName(ModItems.RAINBOW_DYE.get()), req.rainbow(), GlintTableMenu.SLOT_RAINBOW_DYE); any = true; }
        int[] cost = layerCosts();
        if (cost[0] > 0)             { reqLine(lines, itemName(Items.REDSTONE), cost[0], GlintTableMenu.SLOT_REDSTONE); any = true; }
        if (cost[1] > 0)             { reqLine(lines, itemName(Items.SLIME_BALL), cost[1], GlintTableMenu.SLOT_SLIME); any = true; }
        if (cost[2] > 0)             { reqLine(lines, itemName(Items.GLASS), cost[2], GlintTableMenu.SLOT_GLASS); any = true; }
        if (modGlow && !baseGlowing) { reqLine(lines, itemName(Items.GLOWSTONE_DUST), totalLayers(), GlintTableMenu.SLOT_GLOWSTONE); any = true; }
        int extraLayers = lowerLayers.size() + upperLayers.size();
        if (extraLayers > 0)         { reqLine(lines, itemName(ModItems.GLINT_LAYER_TEAR.get()), extraLayers, GlintTableMenu.SLOT_LAYER_TEAR); any = true; }
        int simTears = simTearCost();
        if (simTears > 0)            { reqLine(lines, itemName(ModItems.GLINT_TEAR_SIMULTANEOUS.get()), simTears, GlintTableMenu.SLOT_TEAR); any = true; }
        int seqTears = seqTearCost();
        if (seqTears > 0)            { reqLine(lines, itemName(ModItems.GLINT_TEAR_SEQUENTIAL.get()), seqTears, GlintTableMenu.SLOT_TEAR_SEQ); any = true; }
        if (!any && issues.isEmpty()) lines.add(Component.translatable("screen.customglint.glint_table.nothing").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    /** One material-cost line: "<item> ×<need>", green when the slot already holds enough, red when short. */
    private void reqLine(List<Component> lines, Component name, int need, int slotConst) {
        int have = menu.slots.get(slotConst).getItem().getCount();
        lines.add(Component.translatable("screen.customglint.glint_table.consume_line", name, need)
                .withStyle(have >= need ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    // Import picker

    /** True when a printed-library entry is an imported trim the player hasn't crafted yet (dimmed, locked). */
    private static boolean isImportLocked(ItemStack s) {
        return GlintTableMenu.isImportLocked(s);
    }

    private int impPickerX() { return leftPos + (imageWidth - IMP_PW) / 2; }
    private int impPickerY() { return topPos + 24; }
    private int impPickerH() { return IMPORT_ROWS * IMPORT_ROW_H + 22; }

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

    /** Rebuild the import list: this client's personal blueprints ({@code config/glint-and-glamour/trims/*.json})
     *  plus, on a dedicated server, the shared server blueprints synced from the server. Single-player skips
     *  the server source (the integrated server reads the very directory this scan does: listing both would
     *  double every entry). */
    private void scanImportConfigs() {
        importAll.clear();
        try {
            Path dir = ModConfigPaths.TRIMS_DIR;
            if (Files.exists(dir)) {
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

    /** Whether the local player may manage the server's shared blueprints: single-player, or an operator. */
    private static boolean canManageServerTrims() {
        Minecraft mc = Minecraft.getInstance();
        return mc.hasSingleplayerServer() || (mc.player != null && mc.player.hasPermissions(2));
    }

    /** Import a chosen blueprint: a personal client one reads its local file; a shared server one uses the JSON
     *  synced from the server. Either way the parsed trim is sent to the server as a locked build target. */
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
            return;
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
                    ResourceLocation design = new ResourceLocation(lo.get("design").getAsString());
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
                nameColor = (rgb << 8) | 0xFF; // packed as (rgb << 8) | alpha; server reads rgb = (nameColor >>> 8) & 0xFFFFFF
            }
            ModNetworking.CHANNEL.sendToServer(new GlintImportPacket(
                    layers.toArray(new CustomGlint.Layer[0]), glowing, glowColors, displayName, nameColor));
        } catch (Exception ignored) {
            // Malformed JSON: ignore.
        }
    }

    /** Right-click Import: save the current preview build to {@code config/glint-and-glamour/trims/<name>.json}, the
     *  same format the Import list and wand editor read. */
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
            if (src.hasCustomHoverName()) {
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

    private void actionBar(Component c) {
        importMsg = c;
        importMsgUntil = Util.getMillis() + 2500;
    }

    private void drawImportMsg(GuiGraphics g) {
        if (importMsg == null || Util.getMillis() >= importMsgUntil) return;
        int tw = font.width(importMsg);
        int cx = leftPos + imageWidth / 2, ty = topPos + 18;
        // Push above the item-icon Z layer (and the Z-450 import panel) so the confirmation isn't buried.
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        g.fill(cx - tw / 2 - 3, ty - 2, cx + tw / 2 + 3, ty + 10, 0xE0000000);
        g.drawString(font, importMsg, cx - tw / 2, ty, 0xFFFFFFFF, false);
        g.pose().popPose();
    }

    private static String sanitizeFileName(String s) {
        String cleaned = s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "trim" : cleaned;
    }

    private static Path uniqueTrimFile(Path dir, String base) {
        Path p = dir.resolve(base + ".json");
        for (int n = 2; Files.exists(p); n++) p = dir.resolve(base + "_" + n + ".json");
        return p;
    }

    private static String designLabel(ResourceLocation design) {
        if (design.equals(CustomGlint.VANILLA)) return "vanilla";
        String nm = GlintTrimItem.extractPatternName(design);
        return nm == null ? "trim" : nm;
    }

    /** Delete a personal client blueprint: remove its local file, then rescan. */
    private void deleteImport(String name) {
        try {
            Files.deleteIfExists(ModConfigPaths.trimFile(name));
        } catch (Exception ignored) {
            // Locked/unremovable file: leave it; the rescan keeps showing it.
        }
        scanImportConfigs();
    }

    /** Delete a shared server blueprint (op/single-player only): ask the server to remove it, drop the local
     *  mirror for instant feedback, then rescan. The server's authoritative re-sync follows. */
    private void deleteServerBlueprint(String name) {
        ModNetworking.CHANNEL.sendToServer(new GlintDeleteServerBlueprintPacket(name));
        GlintServerBlueprintsSyncPacket.CLIENT_SERVER_BLUEPRINTS.remove(name);
        scanImportConfigs();
    }

    private int importScrollFromMouse(double my) {
        int listY = impPickerY() + 20, trackH = IMPORT_ROWS * IMPORT_ROW_H;
        int total = importFiltered.size(), max = Math.max(0, total - IMPORT_ROWS);
        if (max <= 0) return 0;
        int thumbH = Math.max(8, trackH * IMPORT_ROWS / total);
        float f = (float) ((my - listY - thumbH / 2.0) / (trackH - thumbH));
        return Math.round(Math.max(0f, Math.min(1f, f)) * max);
    }

    private void renderImportPicker(GuiGraphics g, int mx, int my, float dt) {
        int ox = impPickerX(), oy = impPickerY(), h = impPickerH();
        g.fill(ox - 1, oy - 1, ox + IMP_PW + 1, oy + h + 1, 0xFF666666);
        g.fill(ox, oy, ox + IMP_PW, oy + h, 0xEE111111);

        if (importSearchBox != null) {
            importSearchBox.setX(ox + 2);
            importSearchBox.setY(oy + 3);
            importSearchBox.setWidth(IMP_PW - 4);
            importSearchBox.setVisible(true);
            importSearchBox.render(g, mx, my, dt);
        }

        int listY = oy + 20, sbX = ox + IMP_PW - 5;
        if (importFiltered.isEmpty()) {
            g.drawString(font, Component.translatable("screen.customglint.glint_table.import_empty"), ox + 4, listY + 2, 0xFF888888, false);
        }
        boolean canManageServer = canManageServerTrims();
        for (int i = 0; i < IMPORT_ROWS && importScroll + i < importFiltered.size(); i++) {
            ImpEntry e = importFiltered.get(importScroll + i);
            int ry = listY + i * IMPORT_ROW_H;
            boolean hovered = mx >= ox && mx < sbX && my >= ry && my < ry + IMPORT_ROW_H;
            if (hovered) g.fill(ox, ry, sbX, ry + IMPORT_ROW_H, 0x40FFFFFF);
            int textX = ox + 4;
            if (e.server()) {
                drawLockIcon(g, ox + 4, ry + 3, 0xFFE0C060);
                textX = ox + 12;
            }
            g.drawString(font, Component.literal(e.name()), textX, ry + 2, e.server() ? 0xFFE0C060 : 0xFFDDDDDD, false);
            if (hovered && (!e.server() || canManageServer)) {
                boolean onTrash = mx >= sbX - IMP_TRASH_W && mx < sbX && my >= ry && my < ry + IMPORT_ROW_H;
                GlintGuiWidgets.drawTrashIcon(g, sbX - IMP_TRASH_W + 2, ry + 3, onTrash ? 0xFFFF5555 : 0xFFB05050);
            }
        }

        if (importFiltered.size() > IMPORT_ROWS) {
            int trackH = IMPORT_ROWS * IMPORT_ROW_H;
            g.fill(sbX, listY, sbX + 4, listY + trackH, 0xFF2A2A2A);
            int thumbH = Math.max(8, trackH * IMPORT_ROWS / importFiltered.size());
            int thumbY = listY + (int) ((trackH - thumbH) * (float) importScroll / (importFiltered.size() - IMPORT_ROWS));
            g.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, 0xFF888888);
        }
    }

    /** Tiny padlock glyph marking a shared server blueprint. Origin = top-left. */
    private void drawLockIcon(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 1, y, x + 4, y + 1, color);
        g.fill(x + 1, y + 1, x + 2, y + 3, color);
        g.fill(x + 3, y + 1, x + 4, y + 3, color);
        g.fill(x, y + 3, x + 5, y + 7, color);
    }

    // Beveled widgets

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

        /** Attach an explanatory tooltip, shown while hovered (see {@link #renderTableTooltip}). Fluent. */
        BevelButton tip(Supplier<List<Component>> t) { this.tooltip = t; return this; }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float a) {
            int face = (active && isHovered()) ? BTN_HOVER : faceColor.getAsInt();
            raisedPanel(g, getX(), getY(), getWidth(), getHeight(), face);
            centered(g, label.get(), getX() + getWidth() / 2, getY() + textDy, textColor.getAsInt());
        }

        @Override
        public boolean mouseClicked(double mxd, double myd, int button) {
            if (!active || !visible) return false;
            if (button != 0 && !(rightToo && button == 1)) return false;
            if (!isMouseOver(mxd, myd)) return false;
            playDownSound(Minecraft.getInstance().getSoundManager());
            onPress.accept(button);
            return true;
        }

        @Override
        public void playDownSound(SoundManager soundManager) {
            if (GlintGuiConfig.sound()) super.playDownSound(soundManager);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            out.add(NarratedElementType.TITLE, Component.literal(label.get()));
        }
    }

    private void addButtons() {
        Slot glow = menu.slots.get(GlintTableMenu.SLOT_GLOWSTONE);
        Slot nameS = menu.slots.get(GlintTableMenu.SLOT_NAMETAG);
        Slot tear = menu.slots.get(GlintTableMenu.SLOT_TEAR);

        addRenderableWidget(new BevelButton(leftPos + SKIN_BTN_X, topPos + SKIN_BTN_Y, SKIN_BTN_W, SKIN_BTN_H, 2, true,
                () -> Component.translatable("screen.customglint.skin." + skin.name.toLowerCase(Locale.ROOT)).getString(),
                () -> LABEL_HDR, () -> GUI_FACE, b -> cycleSkin(b == 1 ? -1 : 1)));

        addRenderableWidget(new BevelButton(leftPos + SKIN_BTN_X - SND_BTN_W - 2, topPos + SKIN_BTN_Y, SND_BTN_W, SKIN_BTN_H, 2, false,
                () -> "♪", () -> GlintGuiConfig.sound() ? COST_OK : COST_BAD, () -> GUI_FACE,
                b -> GlintGuiConfig.setSound(!GlintGuiConfig.sound())));

        // Import: left-click opens a picker of premade config trims (personal + shared server); right-click
        // saves the current preview build to a config file so it can be imported later. Left of the sound toggle.
        addRenderableWidget(new BevelButton(leftPos + SKIN_BTN_X - SND_BTN_W - 2 - IMP_BTN_W - 2, topPos + SKIN_BTN_Y, IMP_BTN_W, SKIN_BTN_H, 2, true,
                () -> "↓", () -> LABEL_HDR, () -> GUI_FACE, b -> { if (b == 1) saveCurrentAsImport(); else toggleImportPicker(); }));

        printBtn = addRenderableWidget(new BevelButton(leftPos + PRINT_X, topPos + PRINT_Y, PRINT_W, PRINT_H, 3, false,
                () -> Component.translatable("screen.customglint.glint_table.print").getString(),
                () -> canPrint() ? LABEL_HDR : COST_BAD, () -> canPrint() ? GUI_FACE : BTN_DISABLED,
                b -> { if (canPrint()) onPrint(); }));

        addRenderableWidget(new BevelButton(leftPos + INTERP_X, topPos + INTERP_Y, INTERP_W, INTERP_H, 2, false,
                () -> Component.translatable("screen.customglint.glint_table.interpolation", boolLabel(modInterpolate)).getString(),
                () -> modInterpolate ? COST_OK : LABEL_HDR, () -> GUI_FACE, b -> modInterpolate = !modInterpolate)
                .tip(() -> tipLines("screen.customglint.glint_table.tip.interpolation")));

        addRenderableWidget(new BevelButton(leftPos + SCROLL_X, topPos + SCROLL_Y, SCROLL_W, SCROLL_H, 2, false,
                () -> Component.translatable("screen.customglint.glint_table.scroll", GlintTrimItem.scrollLabel(modScrollDir)).getString(),
                // 9 scroll modes: the 8 compass directions plus Static.
                () -> LABEL_HDR, () -> GUI_FACE, b -> modScrollDir = (modScrollDir + 1) % 9)
                .tip(() -> modScrollDir == CustomGlint.SCROLL_STATIC
                        ? tipLines("screen.customglint.glint_table.tip.scroll", "screen.customglint.glint_table.tip.scroll_static")
                        : tipLines("screen.customglint.glint_table.tip.scroll")));

        addRenderableWidget(new BevelButton(tearToggleCx() - 15, topPos + tear.y + 26, 30, 11, 2, false,
                () -> Component.translatable(tearSimultaneous ? "screen.customglint.glint_table.sim" : "screen.customglint.glint_table.seq").getString(),
                () -> LABEL_HDR, () -> GUI_FACE, b -> tearSimultaneous = !tearSimultaneous)
                .tip(() -> tipLines("screen.customglint.glint_table.tip.type")));

        // Left-click toggles Auto/Manual; right-click (while Manual) focuses the glow colour shards into the
        // colour strip (right-click again unfocuses). Forcing Manual on a focus right-click saves a click.
        glowModeBtn = addRenderableWidget(new BevelButton(leftPos + GLOW_MODE_X, topPos + GLOW_MODE_Y, GLOW_MODE_W, GLOW_MODE_H, 2, true,
                this::glowModeLabel, this::glowModeColor, () -> glowFocused ? BTN_HOVER : GUI_FACE, b -> {
                    if (b == 1) {
                        if (glowFocused) glowFocused = false;
                        else { glowAuto = false; glowFocused = true; selectedColorIdx = glowShards.isEmpty() ? -1 : 0; }
                    } else {
                        glowAuto = !glowAuto;
                    }
                })
                .tip(() -> modGlow
                        ? tipLines("screen.customglint.glint_table.tip.glow_mode", "screen.customglint.glint_table.tip.glow_mode_manual")
                        : tipLines("screen.customglint.glint_table.tip.glow_mode", "screen.customglint.glint_table.tip.glow_mode_off")));

        // Glow / Name toggles: freely usable (no material/ownership gate); the missing glowstone / name tag
        // only blocks the print, spelled out in the Print tooltip.
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

        addStepperPair(GlintTableMenu.SLOT_REDSTONE, () -> modSpeed = GlintGuiWidgets.stepDown(modSpeed), () -> modSpeed = GlintGuiWidgets.stepUp(modSpeed), "screen.customglint.glint_table.tip.speed");
        addStepperPair(GlintTableMenu.SLOT_GLASS, () -> modOpacity = Math.max(0, modOpacity - 1), () -> modOpacity = Math.min(8, modOpacity + 1), "screen.customglint.glint_table.tip.opacity");
        addStepperPair(GlintTableMenu.SLOT_SLIME, () -> modScale = GlintGuiWidgets.stepDown(modScale), () -> modScale = GlintGuiWidgets.stepUp(modScale), "screen.customglint.glint_table.tip.scale");

        int ox = leftPos + SCROLL_X + SCROLL_W / 2, oy = topPos + SCROLL_OFF_Y;
        offsetMinus = stepper(ox - 15, oy, "-", () -> modScrollOffset = Math.max(0.0f, Math.round((modScrollOffset - 0.05f) * 20) / 20.0f), "screen.customglint.glint_table.tip.offset");
        offsetPlus  = stepper(ox + 6,  oy, "+", () -> modScrollOffset = Math.min(1.0f, Math.round((modScrollOffset + 0.05f) * 20) / 20.0f), "screen.customglint.glint_table.tip.offset");
    }

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

    private void syncButtons() {
        printBtn.active = canPrint();
        glowModeBtn.active = modGlow && !glowTrimMain();
        glowToggleBtn.active = !glowTrimMain();
        nameToggleBtn.active = true; // usable without a name tag; the print gates on it
        offsetMinus.visible = offsetPlus.visible = (modScrollDir == CustomGlint.SCROLL_STATIC);
    }

    /** A clean-white, multi-line tooltip built from lang keys (one per line). Left unstyled so it renders the
     *  default tooltip white, matching the wand editor's control tooltips. */
    private static List<Component> tipLines(String... keys) {
        List<Component> lines = new ArrayList<>(keys.length);
        for (String k : keys) lines.add(Component.translatable(k));
        return lines;
    }

    /** A friendly, capitalized design name for a layer tooltip header. */
    private static Component designDisplayName(ResourceLocation design) {
        String name;
        if (design.equals(CustomGlint.VANILLA)) name = "Vanilla";
        else {
            String p = GlintTrimItem.extractPatternName(design);
            name = p == null ? design.toString() : capitalize(p);
        }
        return Component.literal(name).withStyle(ChatFormatting.WHITE);
    }

    /** The active layer's design (the chip that edits the live controls), or null if none yet. */
    private ResourceLocation activeLayerDesign() {
        ItemStack a = activeTrim();
        return a.getItem() instanceof GlintTrimItem ? GlintTrimItem.getPattern(a) : null;
    }

    /** A colour header naming a shard: its dye name, a custom {@code #hex}, "Rainbow", or "No color yet". */
    private Component shardColorLabel(List<Integer> shard) {
        if (shard.isEmpty())
            return Component.translatable("screen.customglint.glint_table.shard_unset").withStyle(ChatFormatting.GRAY);
        int rgb = mixRgb(shard);
        if (rgb < 0)
            return Component.translatable("screen.customglint.glint_table.shard_rainbow").withStyle(ChatFormatting.GRAY);
        int dye = dyeIndexForRgb(rgb);
        String name = dye >= 0 ? capitalize(DyeColor.byId(dye).getName().replace("_", " "))
                               : "#" + String.format("%06X", rgb);
        int frgb = rgb;
        return Component.literal(name).withStyle(st -> st.withColor(TextColor.fromRgb(frgb)));
    }

    private static int dyeIndexForRgb(int rgb) {
        for (int i = 0; i < 16; i++) if (dyeRgb(i) == rgb) return i;
        return -1;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String glowModeLabel() {
        if (glowTrimMain()) return naLabel();
        if (!modGlow) return Component.translatable("screen.customglint.glint_table.glow_off").getString();
        return Component.translatable(glowAuto
                ? "screen.customglint.glint_table.glow_auto" : "screen.customglint.glint_table.glow_manual").getString();
    }

    private static Component boolLabel(boolean value) {
        return Component.translatable(value ? "screen.customglint.glint_table.true" : "screen.customglint.glint_table.false");
    }

    private static String naLabel() {
        return Component.translatable("screen.customglint.glint_table.na").getString();
    }

    private static String label(String suffix) {
        return Component.translatable("screen.customglint.glint_table." + suffix).getString();
    }

    private int glowModeColor() {
        return (glowTrimMain() || !modGlow) ? COST_BAD : LABEL_HDR;
    }

    private void centered(GuiGraphics g, String str, int cx, int y, int color) {
        g.drawString(font, str, cx - font.width(str) / 2, y, 0xFF000000 | color, false);
    }

    /** Render an item icon scaled to fit a {@code size}px cell at (x, y), mirroring the (working) preview's
     *  render structure: centered translate at a raised z, then {@code scale}, then {@code renderItem(-8,-8)}
     *  so the item centers on the cell. (The corner-translate form did not honour the pose scale here.)
     *  A 1px inset keeps the icon clear of the shard bevel so it doesn't read as spilling past the frame. */
    private void drawScaledIcon(GuiGraphics g, ItemStack icon, int x, int y, int size) {
        g.enableScissor(x, y, x + size, y + size);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x + size / 2f, y + size / 2f, 200);
        float s = (size - 2) / 16f;
        pose.scale(s, s, 1f);
        g.renderItem(icon, -8, -8);
        pose.popPose();
        g.disableScissor();
    }

    /** A fill drawn above GUI items. Icons render at z≈150, so a plain {@code g.fill} (z 0) for a dim / hover
     *  overlay lands behind the icon and never shows; this raises it so the overlay sits on top. */
    private void overlay(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 240);
        g.fill(x0, y0, x1, y1, color);
        g.pose().popPose();
    }

    private void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void raisedPanel(GuiGraphics g, int x, int y, int w, int h, int face) {
        skin.raised(g, x, y, w, h, face);
    }

    private void shardBevel(GuiGraphics g, int x, int y, int s) {
        skin.shardBevel(g, x, y, s);
    }

    private void slotWell(GuiGraphics g, int sx, int sy) {
        skin.slotWell(g, sx, sy);
    }

    private String modValue(int slotConst) {
        return switch (slotConst) {
            case GlintTableMenu.SLOT_REDSTONE -> GlintGuiWidgets.fmtVal(modSpeed) + "×";
            case GlintTableMenu.SLOT_GLASS    -> Math.round(modOpacity * 100f / 8f) + "%";
            case GlintTableMenu.SLOT_SLIME    -> GlintGuiWidgets.fmtVal(modScale) + "×";
            default -> "";
        };
    }

    private void drawModifierControls(GuiGraphics g) {
        drawModControl(g, GlintTableMenu.SLOT_REDSTONE, label("speed"));
        drawModControl(g, GlintTableMenu.SLOT_GLASS,    label("opacity"));
        drawModControl(g, GlintTableMenu.SLOT_SLIME,    label("scale"));
        drawControlLabel(g, GlintTableMenu.SLOT_GLOWSTONE, label("glow"));
        drawControlLabel(g, GlintTableMenu.SLOT_NAMETAG,   label("name_label"));
        drawTearLabel(g);
        if (modScrollDir == CustomGlint.SCROLL_STATIC) {
            fitValue(g, String.format("%.2f", modScrollOffset), leftPos + SCROLL_X + SCROLL_W / 2, topPos + SCROLL_OFF_Y + 1);
        }
    }

    private void fitValue(GuiGraphics g, String val, int cx, int y) {
        int w = font.width(val);
        float vs = w > 0 ? Math.min(0.8f, 11f / w) : 0.8f;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, y, 0);
        pose.scale(vs, vs, 1f);
        centered(g, val, 0, 0, LABEL_HDR);
        pose.popPose();
    }

    private int tearToggleCx() {
        return (leftPos + menu.slots.get(GlintTableMenu.SLOT_TEAR).x + 8
                + leftPos + menu.slots.get(GlintTableMenu.SLOT_TEAR_SEQ).x + 8) / 2;
    }

    private void drawControlLabel(GuiGraphics g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        smallLabel(g, label, leftPos + s.x + 8, topPos + s.y + 19, LABEL_HDR);
    }

    private void drawTearLabel(GuiGraphics g) {
        int cx = tearToggleCx(), top = topPos + menu.slots.get(GlintTableMenu.SLOT_TEAR).y;
        smallLabel(g, label("type"), cx, top + 19, LABEL_HDR);
        Slot off = menu.slots.get(tearSimultaneous ? GlintTableMenu.SLOT_TEAR_SEQ : GlintTableMenu.SLOT_TEAR);
        int dx = leftPos + off.x - 1, dy = topPos + off.y - 1;
        overlay(g, dx, dy, dx + 18, dy + 18, DIM_GHOST);
    }

    private void drawModControl(GuiGraphics g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        int cx = leftPos + s.x + 8, top = topPos + s.y;
        smallLabel(g, label, cx, top + 19, LABEL_HDR);
        fitValue(g, modValue(slotConst), cx, top + 27);
    }

    private void drawSectionLabels(GuiGraphics g) {
        int x = leftPos, y = topPos;
        g.drawString(font, label("empty_trims"), x + LGRID_X, y + GRID_Y - 11, 0xFF000000 | LABEL_HDR, false);
        g.drawString(font, label("printed"),     x + RGRID_X, y + GRID_Y - 11, 0xFF000000 | LABEL_HDR, false);
        topSlotLabel(g, GlintTableMenu.SLOT_TRIM,       label("main"));
        topSlotLabel(g, GlintTableMenu.SLOT_LAYER_TEAR, label("layer"));
        topSlotLabel(g, GlintTableMenu.SLOT_TRIM_B,     label("merge"));
    }

    private void topSlotLabel(GuiGraphics g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        smallLabel(g, label, leftPos + s.x + 8, topPos + s.y + 19, LABEL_HDR);
    }

    private void smallLabel(GuiGraphics g, String label, int cx, int y, int color) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, y, 0);
        pose.scale(0.7f, 0.7f, 1f);
        centered(g, label, 0, 0, color);
        pose.popPose();
    }

    // Input

    private static boolean isTrim(ItemStack stack) {
        return stack.getItem() instanceof GlintTrimItem || stack.getItem() instanceof GlowTrimItem;
    }

    private boolean overEitherGrid(double mx, double my) {
        return overGrid(mx, my, LGRID_X) || overGrid(mx, my, RGRID_X);
    }

    private boolean overGrid(double mx, double my, int gx) {
        int x0 = leftPos + gx, y0 = topPos + GRID_Y;
        return mx >= x0 && mx < x0 + GRID_COLS * CELL && my >= y0 && my < y0 + GRID_ROWS * CELL;
    }

    private boolean overSlot(double mx, double my, int slotConst) {
        Slot s = menu.slots.get(slotConst);
        int sx = leftPos + s.x, sy = topPos + s.y;
        return mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16;
    }

    private int gridIndexAt(double mx, double my, int gx, int gy, int scroll, int total) {
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int cx = gx + col * CELL, cy = gy + row * CELL;
                if (mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) {
                    int idx = (scroll + row) * GRID_COLS + col;
                    return idx < total ? idx : -1;
                }
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = leftPos, y = topPos;

        // Import picker is modal: it swallows every click while open (search box, a row, or click-outside-to-close).
        if (showImportPicker) {
            int ox = impPickerX(), oy = impPickerY(), h = impPickerH();
            if (mx >= ox + 2 && mx < ox + IMP_PW - 2 && my >= oy + 3 && my < oy + 17) {
                if (importSearchBox != null) importSearchBox.mouseClicked(mx, my, button);
                return true;
            }
            if (mx < ox || mx >= ox + IMP_PW || my < oy || my >= oy + h) { // click outside closes
                showImportPicker = false;
                if (importSearchBox != null) importSearchBox.setFocused(false);
                return true;
            }
            int listY = oy + 20, sbX = ox + IMP_PW - 5, trackH = IMPORT_ROWS * IMPORT_ROW_H;
            if (importFiltered.size() > IMPORT_ROWS && mx >= sbX - 1 && mx < sbX + 6 && my >= listY && my < listY + trackH) {
                draggingImport = true;
                importScroll = importScrollFromMouse(my);
                return true;
            }
            if (my >= listY && mx >= ox && mx < sbX) {
                int row = (int) (my - listY) / IMPORT_ROW_H;
                int idx = importScroll + row;
                if (row >= 0 && row < IMPORT_ROWS && idx < importFiltered.size()) {
                    ImpEntry e = importFiltered.get(idx);
                    boolean trashShown = !e.server() || canManageServerTrims();
                    if (mx >= sbX - IMP_TRASH_W && trashShown) { // trash hotzone: delete, don't select
                        if (e.server()) deleteServerBlueprint(e.name());
                        else deleteImport(e.name());
                    } else {
                        importSelected(e);
                    }
                }
            }
            return true;
        }

        if (hexOpen && hexBox != null && hexBox.isVisible()
                && mx >= hexBox.getX() && mx < hexBox.getX() + hexBox.getWidth()
                && my >= hexBox.getY() && my < hexBox.getY() + hexBox.getHeight()) {
            return hexBox.mouseClicked(mx, my, button);
        }

        if (button == 1 && menu.getCarried().isEmpty()) {
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

        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && isTrim(carried) && overEitherGrid(mx, my)) {
            ModNetworking.CHANNEL.sendToServer(new GlintDepositPacket());
            return true;
        }

        if (button == 1) {
            if (menu.showGlowDye && rainbowInSlot(GlintTableMenu.SLOT_GLOW_DYE) && overSlot(mx, my, GlintTableMenu.SLOT_GLOW_DYE)) {
                if (hexOpen && hexMode == HEX_GLOW) closeHex(); else openHexColor(HEX_GLOW);
                return true;
            }
            if (menu.showNameDye && rainbowInSlot(GlintTableMenu.SLOT_NAME_DYE) && overSlot(mx, my, GlintTableMenu.SLOT_NAME_DYE)) {
                if (hexOpen && hexMode == HEX_NAME) closeHex(); else openHexColor(HEX_NAME);
                return true;
            }
        }

        if (layerStripVisible()) {
            int cy = y + LAYER_STRIP_Y;
            for (Chip c : layerChips()) {
                int cx = x + c.x();
                if (mx < cx || mx >= cx + LAYER_ICON || my < cy || my >= cy + LAYER_ICON) continue;
                glowFocused = false; // touching the layer strip returns the colour strip to the active layer
                if (c.kind() == 3) { if (button == 0) addLayer(); }                         // [+] add chip
                else if (c.kind() == 1) { if (button == 1) removeActiveLayer(); }            // active layer: right-click deletes
                else if (button == 0 && hasShiftDown()) swapLayers(c.kind(), c.index());     // shift-left: swap with the active layer
                else editLayer(c.kind(), c.index());                                         // unselected: any click selects it first
                return true;
            }
        }

        if (colorStripClick(mx, my, button)) return true;

        if (button == 1) {
            List<List<Integer>> editing = editShards();
            boolean shardSel = selectedColorIdx >= 0 && selectedColorIdx < editing.size();
            for (int i = 0; i < 16; i++) {
                Slot s = menu.slots.get(GlintTableMenu.SLOT_DYE_START + i);
                int sx = x + s.x, sy = y + s.y;
                if (mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16) {
                    // The slot need NOT hold the dye (it's charged at print), so any colour can be picked to
                    // design with even without owning it, the missing dye is added to the print cost.
                    if (menu.getCarried().isEmpty() && shardSel) {
                        List<Integer> shard = editing.get(selectedColorIdx);
                        if (isCustomShard(shard)) { shard.clear(); shard.add(i); closeHex(); } // leave rainbow mode
                        // Shift-right-click toggles this dye in the mix: remove it if already added, else blend it in.
                        else if (hasShiftDown()) { if (!shard.remove((Integer) i) && shard.size() < 8) shard.add(i); }
                        else { shard.clear(); shard.add(i); }
                    }
                    return true;
                }
            }
            Slot rs = menu.slots.get(GlintTableMenu.SLOT_RAINBOW_DYE);
            int rx = x + rs.x, ry = y + rs.y;
            if (mx >= rx && mx < rx + 16 && my >= ry && my < ry + 16) {
                if (menu.getCarried().isEmpty() && shardSel) {
                    List<Integer> shard = editing.get(selectedColorIdx);
                    shard.clear(); shard.add(RAINBOW);
                }
                return true;
            }
        }

        if (button == 0) {
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

        int li = gridIndexAt(mx, my, x + LGRID_X, y + GRID_Y, gridScroll, trims.size());
        if (li >= 0) {
            String name = trims.get(li);
            if (button == 0 && hasShiftDown()) {
                if (GlintStoredSyncPacket.CLIENT_STORED.contains(name))
                    ModNetworking.CHANNEL.sendToServer(new GlintGiveDesignPacket(name));
                return true;
            }
            if (button == 1) {
                if (glowMainSelected()) return true; // a Glow Trim can't merge, so ignore the right-click donor pick
                boolean same = !selectedDonorPrinted && name.equals(trimDesignName(selectedDonor));
                selectedDonor = same ? ItemStack.EMPTY : trimCache.getOrDefault(name, ItemStack.EMPTY).copy();
                selectedDonorPrinted = false;
            } else {
                selectedMain = name; selectedPrinted = ItemStack.EMPTY;
                // A Glow Trim has no pattern scale / opacity; reset those glint-only mods so their cost never
                // shows for a glow build (speed + interpolation still apply and are kept).
                if (GlowTrimItem.STORAGE_KEY.equals(name)) { modScale = 1.0f; modOpacity = 0; }
            }
            return true;
        }

        int ri = gridIndexAt(mx, my, x + RGRID_X, y + GRID_Y, printScroll, GlintPrintedSyncPacket.CLIENT_PRINTED.size());
        if (ri >= 0) {
            // Shift-left-click withdraws a real trim; an imported-but-not-yet-crafted (locked) entry is deleted.
            if (button == 0 && hasShiftDown()) {
                if (isImportLocked(GlintPrintedSyncPacket.CLIENT_PRINTED.get(ri)))
                    ModNetworking.CHANNEL.sendToServer(new GlintDeletePrintedPacket(ri));
                else
                    ModNetworking.CHANNEL.sendToServer(new GlintWithdrawPacket(ri));
                return true;
            }
            ItemStack picked = GlintPrintedSyncPacket.CLIENT_PRINTED.get(ri).copy();
            if (button == 1) {
                if (glowMainSelected()) return true; // a Glow Trim can't merge, so ignore the right-click donor pick
                boolean same = selectedDonorPrinted && ItemStack.isSameItemSameTags(picked, selectedDonor);
                selectedDonor = same ? ItemStack.EMPTY : picked;
                selectedDonorPrinted = !same;
            } else {
                loadFromTrim(picked);
            }
            return true;
        }

        return super.mouseClicked(mx, my, button);
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

    private void onPrint() {
        ItemStack src = activeTrim();
        // A Glow Trim prints down a separate, simpler path: no design / layers / tears, just its glow colours.
        if (src.getItem() instanceof GlowTrimItem) {
            int[][] glowColorShards = shardDyeArray();
            if (glowColorShards.length == 0) return; // needs at least one glow colour
            ModNetworking.CHANNEL.sendToServer(new GlintPrintPacket(
                    "", modSpeed, 1.0f, 0, false, false, modNamed, trimName, false,
                    CustomGlint.SCROLL_E, 0.0f, modInterpolate, glowHex, nameHex, glowColorShards, new int[0],
                    new CustomGlint.Layer[0], new CustomGlint.Layer[0], false, true, new int[0][]));
            return;
        }
        // Commit the previewed merge donor into real stacked layers (one per layer, one tear each) so the
        // printed trim matches the stacked preview. canAddLayer only checks the layer cap; ownership and layer
        // tears are validated by the server at print.
        if (canAddLayer()) addLayer();
        src = activeTrim();
        if (src.isEmpty() || !(src.getItem() instanceof GlintTrimItem)) return;
        ResourceLocation design = GlintTrimItem.getPattern(src);
        if (design == null) return;
        int[][] shardDyes = shardDyeArray();
        int[][] glowShardDyes = glowShardsActive() ? shardDyeArray(glowShards) : new int[0][];
        CustomGlint.Layer[] below = lowerLayers.toArray(new CustomGlint.Layer[0]);
        CustomGlint.Layer[] above = upperLayers.toArray(new CustomGlint.Layer[0]);
        ModNetworking.CHANNEL.sendToServer(new GlintPrintPacket(
                design.toString(), modSpeed, modScale, modOpacity, modGlow, glowAuto, modNamed, trimName, tearSimultaneous,
                modScrollDir, modScrollOffset, modInterpolate, glowHex, nameHex, shardDyes, donorColors(), below, above, activeSourceSim, false,
                glowShardDyes));
    }

    @Override
    public boolean keyPressed(int key, int sc, int mods) {
        // Import picker: Escape / Enter closes it; other keys type into the search box.
        if (showImportPicker) {
            if (key == 256 || key == 257 || key == 335) {
                showImportPicker = false;
                if (importSearchBox != null) importSearchBox.setFocused(false);
                return true;
            }
            if (importSearchBox != null) { importSearchBox.setFocused(true); importSearchBox.keyPressed(key, sc, mods); return true; }
        }
        if (key == 257 || key == 335) { // Enter / numpad Enter
            if (hexOpen) { closeHex(); setFocused(null); return true; }
            if (nameBox != null && nameBox.isFocused()) { nameBox.setFocused(false); setFocused(null); return true; }
        }
        if (nameBox != null && nameBox.isVisible() && nameBox.isFocused() && key != 256) {
            nameBox.keyPressed(key, sc, mods);
            return true;
        }
        if (hexBox != null && hexBox.isVisible() && hexBox.isFocused() && key != 256) {
            hexBox.keyPressed(key, sc, mods);
            return true;
        }
        return super.keyPressed(key, sc, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (showImportPicker && importSearchBox != null) { importSearchBox.setFocused(true); if (importSearchBox.charTyped(c, mods)) return true; }
        if (nameBox != null && nameBox.isVisible() && nameBox.isFocused() && nameBox.charTyped(c, mods)) return true;
        if (hexBox != null && hexBox.isVisible() && hexBox.isFocused() && hexBox.charTyped(c, mods)) return true;
        return super.charTyped(c, mods);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingImport)    { importScroll = importScrollFromMouse(my); return true; }
        if (draggingGrid == 0) { gridScroll = scrollFromMouse(my, topPos + GRID_Y, trims.size()); return true; }
        if (draggingGrid == 1) { printScroll = scrollFromMouse(my, topPos + GRID_Y, printedCapacity()); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingGrid = -1;
        draggingImport = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (showImportPicker) {
            int max = Math.max(0, importFiltered.size() - IMPORT_ROWS);
            importScroll = Math.max(0, Math.min(max, importScroll - (int) Math.signum(scrollY)));
            return true;
        }
        if (overGrid(mx, my, LGRID_X)) { gridScroll = scrolled(gridScroll, trims.size(), scrollY); return true; }
        if (overGrid(mx, my, RGRID_X)) { printScroll = scrolled(printScroll, printedCapacity(), scrollY); return true; }
        return super.mouseScrolled(mx, my, scrollY);
    }

    private int scrolled(int cur, int total, double dir) {
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        int maxRow = Math.max(0, rows - GRID_ROWS);
        return Math.max(0, Math.min(maxRow, cur - (int) Math.signum(dir)));
    }
}
