package net.tunamods.customglint.module.gui;

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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.GlintClientConfig;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.network.GiveGlintTrimPacket;
import net.tunamods.customglint.module.network.GlintApplyPacket;
import net.tunamods.customglint.module.network.GlintServerBlueprintsSyncPacket;
import net.tunamods.customglint.module.network.GlintWandDeleteBlueprintPacket;
import net.tunamods.customglint.module.network.GlintWandRequestBlueprintsPacket;
import net.tunamods.customglint.module.network.GlintWandSaveBlueprintPacket;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The Glint Wand editor. Builds a glint (layers, colors, speed, scale, scroll, glow, name) against a live
 * item preview, then sends it to the server to apply, to give as an item or trim, or to save into the shared
 * blueprint pool. Client only: nothing here is reachable from a dedicated server.
 * <p>
 * The eight {@code layerXxx} lists are parallel and must stay the same length. Every render frame indexes
 * them by {@link #selectedLayer}, so any edit that changes one has to change all eight.
 */
public class GlintEditorScreen extends Screen {

    // ── Layout constants ────────────────────────────────────────────────────
    private static final int PANEL_W    = 300;
    private static final int PANEL_H    = 270;
    private static final int PREVIEW_SZ = 80;

    /** Layer cap, matching the Glint Table's; the tab strip is laid out for this many tabs. */
    private static final int MAX_LAYERS = 8;
    /** Colour a freshly added layer or swatch starts on (the wand's signature purple). */
    private static final int DEFAULT_COLOR = 0xFF8844EE;
    /** GLFW key code for Escape, which closes whichever overlay picker is open. */
    private static final int KEY_ESCAPE = 256;

    // ── Active skin ───────────────────────────────────────────────────────────
    // The window frame, divider and preview recess are baked into the skin PNG; the palette colours every
    // element the screen draws on top (buttons, swatches, labels). Persisted separately from the table skin.
    private GlintWandSkin skin = GlintWandSkin.DEFAULT;

    /** Step the skin by {@code dir} (+1 next / -1 previous, wrapping) and persist the choice. */
    private void cycleSkin(int dir) {
        int n = GlintWandSkin.ALL.length;
        int idx = Math.floorMod(GlintWandSkin.indexOf(skin) + dir, n);
        skin = GlintWandSkin.ALL[idx];
        GlintClientConfig.setGlintWandSkin(idx);
    }

    @Override
    public void removed() {
        GlintClientConfig.flush(); // persist any skin/sound toggles once, off the per-click path
        super.removed();
    }

    private static Identifier designRL(String name) {
        return CustomGlint.designFromName(name);
    }

    private static String designShortName(Identifier rl) {
        if (rl.equals(CustomGlint.VANILLA)) return "vanilla";
        String p = rl.getPath();
        String base = p.startsWith("textures/glint/") ? p.substring("textures/glint/".length()) : p;
        String name = base.endsWith(".png") ? base.substring(0, base.length() - 4) : base;
        return rl.getNamespace().equals("customglint") ? name : rl.getNamespace() + ":" + name;
    }

    // Item display names resolve a Component + format a String; the picker draws them every frame for every
    // visible row and the filter walks them on each search. Items are registry singletons, so the resolved
    // name is stable, cache it. Icon ItemStacks are cached the same way to skip the per-row allocation.
    private static final Map<Item, String> ITEM_NAME_CACHE = new IdentityHashMap<>();
    private static final Map<Item, ItemStack> ITEM_ICON_CACHE = new IdentityHashMap<>();

    private static String itemName(Item item) {
        return ITEM_NAME_CACHE.computeIfAbsent(item, it -> new ItemStack(it).getHoverName().getString());
    }

    private static ItemStack itemIcon(Item item) {
        return ITEM_ICON_CACHE.computeIfAbsent(item, ItemStack::new);
    }

    // ── State ───────────────────────────────────────────────────────────────
    private final InteractionHand wandHand;

    // per-layer state
    private final List<String>        layerDesigns      = new ArrayList<>();
    private final List<List<Integer>> layerColors       = new ArrayList<>();
    private final List<Float>         layerSpeeds       = new ArrayList<>();
    private final List<Boolean>       layerInterpolates = new ArrayList<>();
    private final List<Float>         layerScales       = new ArrayList<>();
    private final List<Boolean>       layerSimultaneous = new ArrayList<>();
    private final List<Integer>       layerScrollDirs   = new ArrayList<>();
    private final List<Float>         layerScrollOffsets = new ArrayList<>();
    private int selectedLayer = 0;

    private int editingColorIdx = 0;

    // glow state
    private boolean glowEnabled = false;
    private final List<Integer> glowOverrideColors = new ArrayList<>();
    private boolean editingGlowColor = false;
    private int editingGlowColorIdx = 0;

    // trim name and color
    private String trimName = "";
    private int trimNameColor = 0xFFFFFFFF;

    private int editR = 0x88, editG = 0x44, editB = 0xEE, editA = 0xFF;

    private Item      previewItem  = Items.NETHERITE_SWORD;
    private ItemStack previewStack = ItemStack.EMPTY;

    // ── Item-picker overlay ─────────────────────────────────────────────────
    private boolean    showPicker    = false;
    private List<Item> allItems      = null;
    private List<Item> filteredItems = new ArrayList<>();
    private int        pickerScroll  = 0;
    private static final int VISIBLE_ROWS = 8, ROW_H = 18;

    /** Which picker's scrollbar thumb the mouse is currently dragging: 0 none, 1 design, 2 import, 3 item. */
    private int scrollDrag = 0;

    // Shortest a scrollbar thumb may get, in px. The drawn thumb and the drag math have to agree on this or
    // the thumb jumps away from the cursor; the item picker gets a taller floor to match its taller rows.
    private static final int THUMB_MIN = 8;
    private static final int ITEM_THUMB_MIN = 10;

    // ── Design-picker overlay ───────────────────────────────────────────────
    private boolean      showDesignPicker = false;
    private List<String> filteredDesigns  = new ArrayList<>();
    private int          designScroll     = 0;
    private EditBox      designSearchBox;
    private static final int DESIGN_ROWS = 10, DESIGN_ROW_H = 13;

    // ── Glint import overlay (server blueprint pool) ──────────────────────────
    private boolean      showImportPicker = false;
    /** Full list of server blueprint names; {@link #availableGlints} is the search-filtered view. */
    private List<String> allGlints        = new ArrayList<>();
    private List<String> availableGlints  = new ArrayList<>();
    private int          importScroll     = 0;
    private EditBox      importSearchBox;
    /** Blueprint-pool size the list was last built from, so the picker rebuilds when the async sync lands. */
    private int          lastListedCount  = -1;
    private static final int IMPORT_ROWS = 10, IMPORT_ROW_H = 13;
    private static final int IMP_TRASH_W = 11; // trash hotzone at the right edge of a hovered row

    // ── Widget refs ─────────────────────────────────────────────────────────
    private EditBox hexBox, rBox, gBox, bBox, aBox;
    private EditBox searchBox;
    private EditBox trimNameBox, nameHexBox;
    private EditBox glowHexBox;
    private BevelButton skinBtn, soundBtn;

    private int px, py;

    // ── Construction ────────────────────────────────────────────────────────

    public GlintEditorScreen(InteractionHand hand) {
        super(Component.translatable("screen.customglint.glint_editor.title"));
        this.wandHand = hand;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            ItemStack wandStack = mc.player.getItemInHand(hand);
            CustomGlint.Data d = CustomGlint.read(wandStack);
            if (d != null) {
                for (CustomGlint.Layer layer : d.layers()) {
                    layerDesigns.add(designShortName(layer.design()));
                    List<Integer> lc = new ArrayList<>();
                    for (int c : layer.colors()) lc.add(c);
                    layerColors.add(lc);
                    layerSpeeds.add(layer.speed());
                    layerInterpolates.add(layer.interpolate());
                    layerScales.add(layer.patternScale());
                    layerSimultaneous.add(layer.simultaneous());
                    layerScrollDirs.add(layer.scrollDir());
                    layerScrollOffsets.add(layer.scrollOffset());
                }
            }
            glowEnabled = CustomGlint.isGlowing(wandStack);
            for (int c : CustomGlint.getGlowColors(wandStack)) glowOverrideColors.add(c);
        }
        if (layerDesigns.isEmpty()) {
            layerDesigns.add("sparkle");
            List<Integer> lc = new ArrayList<>();
            lc.add(DEFAULT_COLOR);
            layerColors.add(lc);
            layerSpeeds.add(1.0f);
            layerInterpolates.add(true);
            layerScales.add(1.0f);
            layerSimultaneous.add(true);
            layerScrollDirs.add(CustomGlint.SCROLL_E);
            layerScrollOffsets.add(0.0f);
        }
        loadEditRGB();
    }

    // ── Color helpers ────────────────────────────────────────────────────────

    private List<Integer> currentColors() {
        return layerColors.get(selectedLayer);
    }

    private void loadEditRGB() {
        int c;
        if (editingGlowColor && editingGlowColorIdx < glowOverrideColors.size()) {
            c = glowOverrideColors.get(editingGlowColorIdx);
        } else {
            List<Integer> colors = currentColors();
            c = editingColorIdx < colors.size() ? colors.get(editingColorIdx) : DEFAULT_COLOR;
        }
        editA = (c >> 24) & 0xFF;
        editR = (c >> 16) & 0xFF;
        editG = (c >>  8) & 0xFF;
        editB =  c        & 0xFF;
    }

    private void saveEditRGB() {
        if (editingGlowColor && editingGlowColorIdx < glowOverrideColors.size()) {
            glowOverrideColors.set(editingGlowColorIdx, (editA << 24) | (editR << 16) | (editG << 8) | editB);
        } else {
            List<Integer> colors = currentColors();
            if (editingColorIdx < colors.size())
                colors.set(editingColorIdx, (editA << 24) | (editR << 16) | (editG << 8) | editB);
        }
    }

    private void syncHexFromRGB() {
        seedBox(hexBox, String.format("%06X", (editR << 16) | (editG << 8) | editB), this::onHexChanged);
    }

    private void syncGlowHexBox() {
        if (glowHexBox == null || editingGlowColorIdx >= glowOverrideColors.size()) return;
        seedBox(glowHexBox, String.format("%06X", glowOverrideColors.get(editingGlowColorIdx) & 0xFFFFFF), s -> {
            if (s.length() == 6 && editingGlowColorIdx < glowOverrideColors.size()) {
                try {
                    int rgb = Integer.parseInt(s, 16);
                    int alpha = (glowOverrideColors.get(editingGlowColorIdx) >> 24) & 0xFF; // glow alpha is edited elsewhere, keep it
                    glowOverrideColors.set(editingGlowColorIdx, (alpha << 24) | rgb);
                    refreshPreview();
                } catch (NumberFormatException ignored) {}
            }
        });
    }

    private void syncChannelBoxes() {
        seedBox(rBox, String.valueOf(editR), this::onRChanged);
        seedBox(gBox, String.valueOf(editG), this::onGChanged);
        seedBox(bBox, String.valueOf(editB), this::onBChanged);
        seedBox(aBox, String.valueOf(editA), this::onAChanged);
    }

    /** Push a value into a box without echoing it back: the responder is detached for the setValue, so the
     *  box can't re-enter the edit path that just changed the colour. Null box (pre-init) is a no-op. */
    private static void seedBox(EditBox box, String value, Consumer<String> responder) {
        if (box == null) return;
        box.setResponder(null);
        box.setValue(value);
        box.setResponder(responder);
    }

    // ── EditBox responders ───────────────────────────────────────────────────

    private void onHexChanged(String s) {
        if (s.length() != 6) return;
        try {
            int rgb = Integer.parseInt(s, 16);
            editR = (rgb >> 16) & 0xFF;
            editG = (rgb >>  8) & 0xFF;
            editB =  rgb        & 0xFF;
            saveEditRGB();
            syncChannelBoxes();
            refreshPreview();
        } catch (NumberFormatException ignored) {}
    }

    private void onRChanged(String s) { onChannelChanged(s, c -> editR = c, true); }
    private void onGChanged(String s) { onChannelChanged(s, c -> editG = c, true); }
    private void onBChanged(String s) { onChannelChanged(s, c -> editB = c, true); }
    private void onAChanged(String s) { onChannelChanged(s, c -> editA = c, false); } // alpha isn't in the hex, so no hex re-sync

    /** Parse a 0..255 channel box, store it, and refresh. {@code syncHex} re-derives the hex from RGB (skipped
     *  for the alpha channel, which the hex doesn't carry); an out-of-range value snaps the box back. */
    private void onChannelChanged(String s, IntConsumer store, boolean syncHex) {
        try {
            int v = Integer.parseInt(s);
            int c = Math.max(0, Math.min(255, v));
            store.accept(c);
            saveEditRGB();
            if (syncHex) syncHexFromRGB();
            refreshPreview();
            if (c != v) syncChannelBoxes();
        } catch (NumberFormatException ignored) {}
    }

    // ── Preview ──────────────────────────────────────────────────────────────

    /** Snapshot the editor's per-layer state into a {@link CustomGlint.Layer} array (preview + packets). */
    private CustomGlint.Layer[] buildLayers() {
        CustomGlint.Layer[] layers = new CustomGlint.Layer[layerDesigns.size()];
        for (int i = 0; i < layers.length; i++) {
            int[] arr = layerColors.get(i).stream().mapToInt(Integer::intValue).toArray();
            layers[i] = new CustomGlint.Layer(designRL(layerDesigns.get(i)), arr,
                    layerSpeeds.get(i), layerInterpolates.get(i), layerScales.get(i), layerSimultaneous.get(i),
                    layerScrollDirs.get(i), layerScrollOffsets.get(i));
        }
        return layers;
    }

    /** The glow overrides as an int[] for the preview and packet APIs. Empty means auto, where the glow
     *  follows layer 0's colors. */
    private int[] glowColorArray() {
        return glowOverrideColors.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Save the current build (every layer, its colors, and all modifiers) to the server's shared blueprint
     *  pool. The wand only ever saves server-side (never the client's personal trims): the design is built
     *  into the same JSON the Glint Table reads and sent to the server, which writes it and re-syncs so it
     *  shows up in Import. Anyone holding the wand may save; the wand is the gate. */
    private void saveDesign() {
        CustomGlint.Layer[] layers = buildLayers();
        if (layers.length == 0) {
            showSaveMsg(Component.translatable("screen.customglint.glint_editor.save_empty"));
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("glowing", glowEnabled);
        if (glowEnabled && !glowOverrideColors.isEmpty()) {
            JsonArray gc = new JsonArray();
            for (int c : glowOverrideColors) gc.add(String.format("0x%08X", c));
            root.add("glowColors", gc);
        }
        if (!trimName.isEmpty()) {
            root.addProperty("displayName", trimName);
            root.addProperty("nameColor", String.format("0x%06X", (trimNameColor >>> 8) & 0xFFFFFF));
        }

        JsonArray layersArray = new JsonArray();
        for (CustomGlint.Layer layer : layers) {
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

        String base = !trimName.isEmpty() ? trimName : layerDesigns.get(0);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        ClientPacketDistributor.sendToServer(new GlintWandSaveBlueprintPacket(base, json));
        showSaveMsg(Component.translatable("screen.customglint.glint_editor.save_ok"));
    }

    // Transient "saved / couldn't save" confirmation, drawn in-screen for a couple seconds (an actionbar
    // overlay would sit behind the open GUI).
    private Component saveMsg = null;
    private long saveMsgUntil = 0;

    private void showSaveMsg(Component c) {
        saveMsg = c;
        saveMsgUntil = Util.getMillis() + 2500;
    }

    private void drawSaveMsg(GuiGraphicsExtractor g) {
        if (saveMsg == null || Util.getMillis() >= saveMsgUntil) return;
        int tw = font.width(saveMsg);
        int cx = px + PANEL_W / 2, ty = py + 8;
        g.fill(cx - tw / 2 - 3, ty - 2, cx + tw / 2 + 3, ty + 10, 0xE0000000);
        g.text(font, saveMsg, cx - tw / 2, ty, 0xFFFFFFFF, false);
    }

    private void refreshPreview() {
        previewStack = new ItemStack(previewItem);
        CustomGlint.Layer[] layers = buildLayers();
        CustomGlint.write(previewStack, layers);
        CustomGlint.clearGlowColors(previewStack);
        CustomGlint.setGlowing(previewStack, glowEnabled);
        if (glowEnabled && !glowOverrideColors.isEmpty()) {
            CustomGlint.setGlowColors(previewStack, glowColorArray());
        }
    }

    // ── Design picker helpers ────────────────────────────────────────────────

    private void filterDesigns(String query) {
        String lq = query.toLowerCase();
        filteredDesigns = lq.isEmpty() ? new ArrayList<>(GlintTrimItem.PATTERNS) : GlintTrimItem.PATTERNS.stream()
                .filter(d -> d.toLowerCase().contains(lq)) // lowercase the pattern too, matching the item/glint filters
                .collect(Collectors.toList());
        designScroll = Math.max(0, Math.min(designScroll, Math.max(0, filteredDesigns.size() - DESIGN_ROWS)));
    }

    private static final int DPW = 190, DPH = DESIGN_ROWS * DESIGN_ROW_H + 20;
    private static final int IPW = 190, IPH = IMPORT_ROWS * IMPORT_ROW_H + 20;

    private int dpX() { return px + 98; }
    private int dpY() { return py + 38; }
    private int ipX() { return px + 98; }
    private int ipY() { return py + 54; }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        px = (width  - PANEL_W) / 2;
        py = (height - PANEL_H) / 2;
        skin = GlintWandSkin.byIndex(GlintClientConfig.glintWandSkin());
        // Warm all skin background textures now (idempotent) so cycling skins doesn't cold-load a PNG mid-click.
        GlintWandSkin.preloadTextures();

        // Skin cycle (left-click next, right-click previous) + button-sound toggle, bottom of the left column.
        skinBtn = addRenderableWidget(new BevelButton(px + 8, py + 232, 62, 14, 3, true,
                () -> Component.translatable("screen.customglint.skin." + skin.name.toLowerCase(Locale.ROOT)).getString(),
                () -> skin.labelHdr, () -> skin.guiFace, b -> cycleSkin(b == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1)));
        soundBtn = addRenderableWidget(new BevelButton(px + 72, py + 232, 14, 14, 3, false,
                () -> "♪", () -> GlintClientConfig.glintTableSound() ? skin.costOk : skin.costBad,
                () -> skin.guiFace, b -> GlintClientConfig.setGlintTableSound(!GlintClientConfig.glintTableSound())));
        tip(skinBtn, "screen.customglint.glint_editor.tip.skin");
        tip(soundBtn, "screen.customglint.glint_editor.tip.sound");

        // Save the current build to the shared blueprint pool (see saveDesign), so it can be pulled back
        // later through Import or the Glint Table. Sits directly under the Skin button.
        tip(bevel(px + 8, py + 248, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.save_design").getString(), this::saveDesign),
                "screen.customglint.glint_editor.tip.save_design");

        // Design picker trigger button, full right-column width, shows current design
        tip(bevel(px + 100, py + 22, PANEL_W - 104, 14,
                () -> Component.translatable("screen.customglint.glint_editor.design_button",
                        layerDesigns.get(selectedLayer)).getString(),
                () -> {
                    filterDesigns(designSearchBox != null ? designSearchBox.getValue() : "");
                    designScroll = Math.max(0, filteredDesigns.indexOf(layerDesigns.get(selectedLayer)));
                    showDesignPicker = true;
                    if (designSearchBox != null) designSearchBox.setFocused(true);
                }), "screen.customglint.glint_editor.tip.design");

        // Design search box, managed manually (not added to renderables). Preserve the query across
        // an init() re-run (a screen-customization mod like FancyMenu triggers one) so a partially-typed
        // search isn't wiped, and re-focus if the picker is currently open.
        String prevDesignQuery = designSearchBox != null ? designSearchBox.getValue() : "";
        designSearchBox = new EditBox(font, 0, 0, DPW - 4, 12, Component.translatable("screen.customglint.glint_editor.search_designs"));
        designSearchBox.setMaxLength(30);
        designSearchBox.setResponder(s -> { designScroll = 0; filterDesigns(s); });
        designSearchBox.setValue(prevDesignQuery);
        if (showDesignPicker) designSearchBox.setFocused(true);
        filterDesigns(prevDesignQuery);

        // Remove last color [−]
        tip(bevel(px + 100 + currentColors().size() * 18, py + 50, 14, 14, () -> "−", () -> {
            List<Integer> colors = currentColors();
            if (colors.size() > 1) {
                colors.remove(colors.size() - 1);
                if (editingColorIdx >= colors.size()) editingColorIdx = colors.size() - 1;
                loadEditRGB();
                rebuildWidgets();
            }
        }), "screen.customglint.glint_editor.tip.remove_color");

        // Add color [+]
        tip(bevel(px + 100 + currentColors().size() * 18 + 16, py + 50, 14, 14, () -> "+", () -> {
            List<Integer> colors = currentColors();
            if (colors.size() < CustomGlint.MAX_COLORS_PER_LAYER) {
                colors.add(DEFAULT_COLOR);
                editingColorIdx = colors.size() - 1;
                loadEditRGB();
                rebuildWidgets();
            }
        }), "screen.customglint.glint_editor.tip.add_color");

        // RRGGBB for the swatch being edited; alpha lives in its own field below.
        hexBox = addRenderableWidget(new EditBox(font, px + 136, py + 68, 58, 12, Component.translatable("screen.customglint.glint_editor.hex_field")));
        hexBox.setMaxLength(6);
        hexBox.setValue(String.format("%06X", (editR << 16) | (editG << 8) | editB));
        hexBox.setResponder(this::onHexChanged);

        rBox = channelBox(py + 84,  "R", editR, this::onRChanged);
        gBox = channelBox(py + 100, "G", editG, this::onGChanged);
        bBox = channelBox(py + 116, "B", editB, this::onBChanged);
        aBox = channelBox(py + 132, "A", editA, this::onAChanged); // A = opacity

        // Speed and pattern scale, 0.10×..8.0× (0.10 steps below 1×, 0.5 above), matching the Glint Table.
        stepperPair(py + 152, layerSpeeds, "screen.customglint.glint_editor.tip.speed");
        stepperPair(py + 168, layerScales, "screen.customglint.glint_editor.tip.scale");

        // Smooth toggle
        tip(bevel(px + 100, py + 186, 90, 14,
                () -> Component.translatable("screen.customglint.glint_editor.smooth",
                        Component.translatable(layerInterpolates.get(selectedLayer)
                                ? "screen.customglint.glint_editor.on" : "screen.customglint.glint_editor.off")).getString(),
                () -> layerInterpolates.get(selectedLayer) ? skin.costOk : skin.labelHdr,
                () -> { layerInterpolates.set(selectedLayer, !layerInterpolates.get(selectedLayer)); refreshPreview(); }),
                "screen.customglint.glint_editor.tip.interpolation");

        // Simultaneous toggle
        tip(bevel(px + 196, py + 186, 96, 14,
                () -> Component.translatable("screen.customglint.glint_editor.mode",
                        Component.translatable(layerSimultaneous.get(selectedLayer)
                                ? "screen.customglint.glint_editor.mode_simultaneous" : "screen.customglint.glint_editor.mode_cycle")).getString(),
                () -> { layerSimultaneous.set(selectedLayer, !layerSimultaneous.get(selectedLayer)); refreshPreview(); }),
                "screen.customglint.glint_editor.tip.type");

        // Scroll direction, cycles the 8 compass presets + Static. Rebuilds widgets so the static-offset
        // stepper appears/disappears with the mode.
        int sd = layerScrollDirs.get(selectedLayer);
        tip(bevel(px + 100, py + 202, 90, 14,
                () -> Component.translatable("screen.customglint.glint_editor.scroll",
                        GlintTrimItem.scrollLabel(layerScrollDirs.get(selectedLayer))).getString(),
                () -> { layerScrollDirs.set(selectedLayer, (layerScrollDirs.get(selectedLayer) + 1) % 9); refreshPreview(); rebuildWidgets(); }),
                sd == CustomGlint.SCROLL_STATIC ? "screen.customglint.glint_editor.tip.scroll_static"
                                                : "screen.customglint.glint_editor.tip.scroll");

        // Static UV offset stepper, only shown (and only meaningful) when the layer is STATIC.
        if (sd == CustomGlint.SCROLL_STATIC) {
            tip(bevel(px + 196, py + 202, 14, 14, () -> "−", () -> {
                layerScrollOffsets.set(selectedLayer, Math.max(0.0f, Math.round((layerScrollOffsets.get(selectedLayer) - 0.05f) * 20) / 20.0f));
                refreshPreview();
            }), "screen.customglint.glint_editor.tip.offset");
            tip(bevel(px + 244, py + 202, 14, 14, () -> "+", () -> {
                layerScrollOffsets.set(selectedLayer, Math.min(1.0f, Math.round((layerScrollOffsets.get(selectedLayer) + 0.05f) * 20) / 20.0f));
                refreshPreview();
            }), "screen.customglint.glint_editor.tip.offset");
        }

        // Import a saved glint from the shared blueprint pool
        tip(bevel(px + 8, py + 100, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.import").getString(), () -> {
            showImportPicker = !showImportPicker;
            importScroll = 0;
            if (showImportPicker) {
                openImportList();
                if (importSearchBox != null) importSearchBox.setFocused(true);
            }
        }), "screen.customglint.glint_editor.tip.import");

        // Change preview item
        tip(bevel(px + 8, py + 116, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.change_item").getString(), () -> {
            if (allItems == null) allItems = BuiltInRegistries.ITEM.stream()
                    .filter(item -> { Identifier k = BuiltInRegistries.ITEM.getKey(item); return k == null || !k.getNamespace().equals("customglint"); })
                    .collect(Collectors.toList());
            // Keep the previous scroll position on reopen (handy after giving yourself an item); the search
            // responder resets it to the top only when the query text changes.
            filterItems(searchBox != null ? searchBox.getValue() : "");
            showPicker = true;
            searchBox.setFocused(true);
        }), "screen.customglint.glint_editor.tip.change_item");

        // Give new item with glint
        tip(bevel(px + 8, py + 132, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.give_item").getString(), () -> {
            String itemId = String.valueOf(BuiltInRegistries.ITEM.getKey(previewItem));
            ClientPacketDistributor.sendToServer(new GlintApplyPacket(wandHand, false, buildLayers(), itemId,
                    glowEnabled, glowColorArray(), trimName, trimNameColor));
        }), "screen.customglint.glint_editor.tip.give_item");

        // Give Glint Trim with current settings
        tip(bevel(px + 8, py + 148, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.give_trim").getString(), () -> {
            ClientPacketDistributor.sendToServer(new GiveGlintTrimPacket(buildLayers(), glowEnabled,
                    glowColorArray(), trimName, trimNameColor));
        }), "screen.customglint.glint_editor.tip.give_trim");

        // Apply glint to item already in the other hand
        tip(bevel(px + 8, py + 164, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.apply_hand").getString(), () -> {
            ClientPacketDistributor.sendToServer(new GlintApplyPacket(wandHand, false, buildLayers(), "",
                    glowEnabled, glowColorArray(), trimName, trimNameColor));
        }), "screen.customglint.glint_editor.tip.apply_hand");

        // Remove glint from item in the other hand
        tip(bevel(px + 8, py + 180, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.remove").getString(),
                () -> ClientPacketDistributor.sendToServer(new GlintApplyPacket(wandHand, true, new CustomGlint.Layer[0], "", false, new int[0]))),
                "screen.customglint.glint_editor.tip.remove");

        // Glow ON/OFF toggle
        tip(bevel(px + 8, py + 196, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.glow",
                        Component.translatable(glowEnabled ? "screen.customglint.glint_editor.on" : "screen.customglint.glint_editor.off")).getString(),
                () -> glowEnabled ? skin.costOk : skin.labelHdr,
                () -> { glowEnabled = !glowEnabled; refreshPreview(); }),
                "screen.customglint.glint_editor.tip.glow");

        // Custom Name toggle button
        tip(bevel(px + 8, py + 212, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.name",
                        Component.translatable(trimName.isEmpty() ? "screen.customglint.glint_editor.off" : "screen.customglint.glint_editor.on")).getString(),
                () -> trimName.isEmpty() ? skin.labelHdr : skin.costOk, () -> {
            if (!trimName.isEmpty()) {
                trimName = "";
                if (trimNameBox != null) trimNameBox.setVisible(false);
                if (nameHexBox != null) nameHexBox.setVisible(false);
            } else {
                trimName = "Custom";
                if (trimNameBox != null) {
                    trimNameBox.setValue(trimName);
                    trimNameBox.setVisible(true);
                }
                if (nameHexBox != null) nameHexBox.setVisible(true);
            }
        }), "screen.customglint.glint_editor.tip.name");

        // Name text field (positioned below glow info, aligned with Smooth button area). Re-seed the value
        // from trimName BEFORE wiring the responder, so an init() re-run (resize, or the rebuildWidgets that
        // follows loading an import) keeps the current name instead of blanking the box.
        trimNameBox = addRenderableWidget(new EditBox(font, px + 100, py + 254, 90, 12, Component.translatable("screen.customglint.glint_editor.trim_name")));
        trimNameBox.setMaxLength(32);
        trimNameBox.setValue(trimName);
        trimNameBox.setResponder(s -> trimName = s);
        trimNameBox.setVisible(!trimName.isEmpty());

        // Name color hex box
        nameHexBox = addRenderableWidget(new EditBox(font, px + 193, py + 254, 50, 12, Component.translatable("screen.customglint.glint_editor.name_color")));
        nameHexBox.setMaxLength(6);
        nameHexBox.setValue(String.format("%06X", (trimNameColor >>> 8) & 0xFFFFFF));
        nameHexBox.setResponder(s -> {
            if (s.length() == 6) {
                try {
                    int rgb = Integer.parseInt(s, 16);
                    trimNameColor = (rgb << 8) | 0xFF;
                } catch (NumberFormatException ignored) {}
            }
        });
        nameHexBox.setVisible(!trimName.isEmpty());

        // ── Glow color section (right column, py+218, pushed below the Scroll row) ─────────────

        if (glowOverrideColors.isEmpty()) {
            // Auto mode, offer switch to custom
            tip(bevel(px + 162, py + 218, 82, 14,
                    () -> Component.translatable("screen.customglint.glint_editor.auto_to_custom").getString(), () -> {
                List<Integer> l0 = layerColors.get(0);
                glowOverrideColors.add(l0.isEmpty() ? DEFAULT_COLOR : l0.get(0));
                editingGlowColor = true;
                editingGlowColorIdx = 0;
                loadEditRGB();
                syncChannelBoxes();
                syncHexFromRGB();
                rebuildWidgets();
            }), "screen.customglint.glint_editor.tip.glow_mode");
        } else {
            // Custom mode, offer switch back to auto
            tip(bevel(px + 162, py + 218, 82, 14,
                    () -> Component.translatable("screen.customglint.glint_editor.custom_to_auto").getString(), () -> {
                glowOverrideColors.clear();
                editingGlowColor = false;
                rebuildWidgets();
            }), "screen.customglint.glint_editor.tip.glow_mode");

            // Glow color hex input, fixed to the right of the toggle button
            glowHexBox = addRenderableWidget(new EditBox(font, px + 248, py + 220, 46, 12, Component.translatable("screen.customglint.glint_editor.glow_hex")));
            glowHexBox.setMaxLength(6);
            syncGlowHexBox();

            // Remove last glow color [−]
            bevel(px + 100 + glowOverrideColors.size() * 18, py + 234, 14, 14, () -> "−", () -> {
                if (glowOverrideColors.size() > 1) {
                    glowOverrideColors.remove(glowOverrideColors.size() - 1);
                    if (editingGlowColorIdx >= glowOverrideColors.size()) {
                        editingGlowColorIdx = glowOverrideColors.size() - 1;
                    }
                    if (editingGlowColor) { loadEditRGB(); syncChannelBoxes(); syncHexFromRGB(); }
                    rebuildWidgets();
                }
            });

            // Add glow color [+]
            bevel(px + 100 + glowOverrideColors.size() * 18 + 16, py + 234, 14, 14, () -> "+", () -> {
                if (glowOverrideColors.size() < CustomGlint.MAX_COLORS_PER_LAYER) {
                    glowOverrideColors.add(0xFFFFFFFF); // new glow colours start white, not the layer purple
                    editingGlowColorIdx = glowOverrideColors.size() - 1;
                    editingGlowColor = true;
                    loadEditRGB();
                    syncChannelBoxes();
                    syncHexFromRGB();
                    rebuildWidgets();
                }
            });
        }

        // Item picker search box, managed manually. Same preserve-across-init handling as the design box.
        String prevItemQuery = searchBox != null ? searchBox.getValue() : "";
        searchBox = new EditBox(font, 0, 0, 180, 12, Component.translatable("screen.customglint.glint_editor.search_items"));
        searchBox.setMaxLength(40);
        searchBox.setResponder(s -> { pickerScroll = 0; filterItems(s); });
        searchBox.setValue(prevItemQuery);
        if (showPicker) searchBox.setFocused(true);

        refreshPreview();
    }

    // ── Item picker ───────────────────────────────────────────────────────────

    private void filterItems(String query) {
        if (allItems == null) return;
        String lq = query.toLowerCase();
        filteredItems = lq.isEmpty() ? new ArrayList<>(allItems) : allItems.stream().filter(item -> {
            Identifier rl = BuiltInRegistries.ITEM.getKey(item);
            return (rl != null && rl.toString().contains(lq))
                    || itemName(item).toLowerCase().contains(lq);
        }).collect(Collectors.toList());
        pickerScroll = Math.max(0, Math.min(pickerScroll, Math.max(0, filteredItems.size() - VISIBLE_ROWS)));
    }

    private void syncWandState() {
        ClientPacketDistributor.sendToServer(new GlintApplyPacket(wandHand, false, buildLayers(), "",
                glowEnabled, glowColorArray(), trimName, trimNameColor, true));
    }

    /** Open the Import list: ask the server for the current shared blueprint pool (the wand has no server-side
     *  menu to hang an on-open sync from), then build the list from whatever is already synced. The reply
     *  arrives a tick later; {@link #renderImportPicker} rebuilds when it lands. */
    private void openImportList() {
        ClientPacketDistributor.sendToServer(new GlintWandRequestBlueprintsPacket());
        rebuildImportList();
    }

    /** Rebuild the Import list from the synced server blueprint pool (name → JSON), then re-apply the search. */
    private void rebuildImportList() {
        allGlints = new ArrayList<>(GlintServerBlueprintsSyncPacket.CLIENT_SERVER_BLUEPRINTS.keySet());
        allGlints.sort(String.CASE_INSENSITIVE_ORDER);
        lastListedCount = GlintServerBlueprintsSyncPacket.CLIENT_SERVER_BLUEPRINTS.size();
        filterGlints(importSearchBox != null ? importSearchBox.getValue() : "");
    }

    /** Load one blueprint from the synced pool into the editor, replacing every layer and modifier. */
    private void loadBlueprint(String name) {
        String json = GlintServerBlueprintsSyncPacket.CLIENT_SERVER_BLUEPRINTS.get(name);
        if (json == null) return;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            // Parse the untrusted trim file into temp lists, then commit to the live fields only on a fully
            // successful parse. A malformed file throws mid-loop; working on temps keeps the eight live
            // parallel lists equal-length (the render path indexes layerXxx.get(selectedLayer) every frame).
            List<String>        tDesigns    = new ArrayList<>();
            List<List<Integer>> tColors     = new ArrayList<>();
            List<Float>         tSpeeds     = new ArrayList<>();
            List<Boolean>       tInterp     = new ArrayList<>();
            List<Float>         tScales     = new ArrayList<>();
            List<Boolean>       tSim        = new ArrayList<>();
            List<Integer>       tScrollDirs = new ArrayList<>();
            List<Float>         tScrollOffs = new ArrayList<>();

            if (obj.has("layers")) {
                JsonArray layers = obj.getAsJsonArray("layers");
                for (int i = 0; i < Math.min(layers.size(), MAX_LAYERS); i++) {
                    JsonObject layer = layers.get(i).getAsJsonObject();
                    String design = layer.get("design").getAsString();
                    tDesigns.add(designShortName(Identifier.parse(design)));

                    List<Integer> colors = new ArrayList<>();
                    for (JsonElement e : layer.getAsJsonArray("colors")) {
                        String colorStr = e.getAsString();
                        colors.add((int) Long.parseLong(colorStr.replace("0x", ""), 16));
                    }
                    tColors.add(colors);
                    tSpeeds.add(layer.has("speed") ? layer.get("speed").getAsFloat() : 1.0f);
                    tInterp.add(layer.has("interpolate") ? layer.get("interpolate").getAsBoolean() : true);
                    tScales.add(layer.has("patternScale") ? layer.get("patternScale").getAsFloat() : 1.0f);
                    tSim.add(layer.has("simultaneous") ? layer.get("simultaneous").getAsBoolean() : true);
                    tScrollDirs.add(layer.has("scroll") ? layer.get("scroll").getAsInt() : CustomGlint.SCROLL_E);
                    tScrollOffs.add(layer.has("offset") ? layer.get("offset").getAsFloat() : 0.0f);
                }
            }

            if (tDesigns.isEmpty()) {
                tDesigns.add("sparkle");
                List<Integer> lc = new ArrayList<>();
                lc.add(DEFAULT_COLOR);
                tColors.add(lc);
                tSpeeds.add(1.0f);
                tInterp.add(true);
                tScales.add(1.0f);
                tSim.add(true);
                tScrollDirs.add(CustomGlint.SCROLL_E);
                tScrollOffs.add(0.0f);
            }

            // Parse succeeded, swap the temp lists into the live fields in one pass (all stay equal-length).
            layerDesigns.clear();       layerDesigns.addAll(tDesigns);
            layerColors.clear();        layerColors.addAll(tColors);
            layerSpeeds.clear();        layerSpeeds.addAll(tSpeeds);
            layerInterpolates.clear();  layerInterpolates.addAll(tInterp);
            layerScales.clear();        layerScales.addAll(tScales);
            layerSimultaneous.clear();  layerSimultaneous.addAll(tSim);
            layerScrollDirs.clear();    layerScrollDirs.addAll(tScrollDirs);
            layerScrollOffsets.clear(); layerScrollOffsets.addAll(tScrollOffs);

            selectedLayer = 0;
            // The imported layer may carry fewer colors than the one we were editing; reset so the render
            // path's colors.get(editingColorIdx) can't index past the new (shorter) list.
            editingColorIdx = 0;

            if (obj.has("glowing")) glowEnabled = obj.get("glowing").getAsBoolean();

            // Manual glow colors, saved by the Save Design / Glint Table export; absent → auto mode (glow
            // follows layer 0), so clear any leftover overrides from the trim we were editing.
            glowOverrideColors.clear();
            editingGlowColor = false;
            if (obj.has("glowColors")) {
                for (JsonElement e : obj.getAsJsonArray("glowColors"))
                    glowOverrideColors.add((int) Long.parseLong(e.getAsString().replace("0x", ""), 16));
            }

            if (obj.has("displayName")) {
                trimName = obj.get("displayName").getAsString();
            } else {
                trimName = "";
            }
            if (obj.has("nameColor")) {
                int rgb = (int) Long.parseLong(obj.get("nameColor").getAsString().replace("0x", ""), 16) & 0xFFFFFF;
                trimNameColor = (rgb << 8) | 0xFF;
            } else {
                trimNameColor = 0xFFFFFFFF;
            }

            if (trimNameBox != null) {
                trimNameBox.setValue(trimName);
                trimNameBox.setVisible(!trimName.isEmpty());
            }
            if (nameHexBox != null) {
                nameHexBox.setValue(String.format("%06X", (trimNameColor >>> 8) & 0xFFFFFF));
                nameHexBox.setVisible(!trimName.isEmpty());
            }
            loadEditRGB();
            syncChannelBoxes();
            syncHexFromRGB();
            refreshPreview();
            showImportPicker = false;
            syncWandState();
        } catch (Exception e) {
            // Malformed blueprint JSON. There is nothing useful to tell the player mid-edit, so drop it.
        }
    }

    // ── Text helpers (26.1 GuiGraphicsExtractor.text skips alpha==0 colors) ────

    // Flat text (no drop shadow), matching the Glint Table. A shadow muddies dark text on the light Default
    // skin; flat reads crisp on every theme.
    private void label(GuiGraphicsExtractor g, Component c, int x, int y, int color) {
        g.text(this.font, c, x, y, 0xFF000000 | color, false);
    }

    private void label(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(this.font, s, x, y, 0xFF000000 | color, false);
    }

    private void centered(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(this.font, s, x - this.font.width(s) / 2, y, 0xFF000000 | color, false);
    }

    // ── Beveled buttons (skinned widgets) ──────────────────────────────────────

    /**
     * A skinned button backed by the widget system, mirroring the Glint Table's. The label, text colour and
     * base face are pulled live each frame from suppliers, so a toggle button shows changing state without
     * being recreated. Left-click only by default; the skin button opts into right-click.
     */
    private final class BevelButton extends AbstractWidget {
        private final Supplier<String> label;
        private final IntSupplier textColor;
        private final IntSupplier faceColor;
        private final int textDy;
        private final IntConsumer onPress;
        private final boolean rightToo;
        Supplier<List<Component>> tooltip; // hover tooltip lines, or null for none

        BevelButton(int x, int y, int w, int h, int textDy, boolean rightToo,
                    Supplier<String> label, IntSupplier textColor, IntSupplier faceColor, IntConsumer onPress) {
            super(x, y, w, h, Component.empty());
            this.label = label; this.textColor = textColor; this.faceColor = faceColor;
            this.textDy = textDy; this.onPress = onPress; this.rightToo = rightToo;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
            int face = !active ? skin.btnDisabled : (isHovered() ? skin.btnHover : faceColor.getAsInt());
            skin.raised(g, getX(), getY(), getWidth(), getHeight(), face);
            centered(g, label.get(), getX() + getWidth() / 2, getY() + textDy, active ? textColor.getAsInt() : skin.labelDim);
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

    /** A skinned button with the default header text colour. */
    private BevelButton bevel(int x, int y, int w, int h, Supplier<String> label, Runnable onPress) {
        return addRenderableWidget(new BevelButton(x, y, w, h, (h - 8) / 2, false,
                label, () -> skin.labelHdr, () -> skin.guiFace, b -> onPress.run()));
    }

    /** A skinned button with a live text colour (so toggles can show on/off state). */
    private BevelButton bevel(int x, int y, int w, int h, Supplier<String> label, IntSupplier textColor, Runnable onPress) {
        return addRenderableWidget(new BevelButton(x, y, w, h, (h - 8) / 2, false,
                label, textColor, () -> skin.guiFace, b -> onPress.run()));
    }

    /** Attach a hover tooltip (one line per translatable key) to a button and return it, so button
     *  creation can be wrapped inline: {@code tip(bevel(...), "…tip.speed")}. */
    private BevelButton tip(BevelButton b, String... keys) {
        b.tooltip = () -> {
            List<Component> lines = new ArrayList<>(keys.length);
            for (String k : keys) lines.add(Component.translatable(k));
            return lines;
        };
        return b;
    }

    /** One 0..255 colour channel field in the right column. All four share an x and a width; only the row
     *  and the caption differ. */
    private EditBox channelBox(int y, String caption, int value, Consumer<String> responder) {
        EditBox box = addRenderableWidget(new EditBox(font, px + 116, y, 36, 12, Component.literal(caption)));
        box.setMaxLength(3);
        box.setValue(String.valueOf(value));
        box.setResponder(responder);
        return box;
    }

    /** A [−] / [+] pair that steps the selected layer's entry in {@code values} through the shared
     *  speed/scale curve. Both buttons share the tooltip key. */
    private void stepperPair(int y, List<Float> values, String tipKey) {
        tip(bevel(px + 148, y, 14, 14, () -> "−", () -> {
            values.set(selectedLayer, GuiHelpers.stepDown(values.get(selectedLayer)));
            refreshPreview();
        }), tipKey);
        tip(bevel(px + 196, y, 14, 14, () -> "+", () -> {
            values.set(selectedLayer, GuiHelpers.stepUp(values.get(selectedLayer)));
            refreshPreview();
        }), tipKey);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        // Skinned panel background, frame, divider and preview recess are baked into the skin PNG.
        skin.windowPanel(g, px, py, PANEL_W, PANEL_H);

        // Left labels
        label(g, Component.translatable("screen.customglint.glint_editor.preview"), px + 8, py + 8, skin.accent);

        // Preview box interior (the recess outline is part of the PNG; the item draws over it below).
        int bx = px + 8, by = py + 18;

        // Layer tabs
        int tabRowY = py + 6;
        for (int i = 0; i < layerDesigns.size(); i++) {
            int tx = px + 100 + i * 22;
            boolean sel = (i == selectedLayer);
            skin.raised(g, tx - 1, tabRowY - 1, 22, 16, sel ? skin.tabActive : skin.tabIdle);
            centered(g, String.valueOf(i + 1), tx + 10, tabRowY + 3, skin.labelHdr);
            if (sel && layerDesigns.size() > 1) {
                g.fill(tx + 13, tabRowY, tx + 20, tabRowY + 8, skin.costBad);
                label(g, "x", tx + 14, tabRowY + 1, 0xFFFFFF);
            }
        }
        if (layerDesigns.size() < MAX_LAYERS) {
            int plusX = px + 100 + layerDesigns.size() * 22;
            skin.raised(g, plusX - 1, tabRowY - 1, 22, 16, skin.guiFace);
            centered(g, "+", plusX + 10, tabRowY + 3, skin.costOk);
        }

        label(g, Component.translatable("screen.customglint.glint_editor.colors"), px + 100, py + 40, skin.accent);
        label(g, Component.translatable("screen.customglint.glint_editor.hex"), px + 100, py + 70, skin.labelDim);
        label(g, "R:", px + 100, py + 86, skin.chR);
        label(g, "G:", px + 100, py + 102, skin.chG);
        label(g, "B:", px + 100, py + 118, skin.chB);
        label(g, "A:", px + 100, py + 134, skin.labelDim);
        label(g, Component.translatable("screen.customglint.glint_editor.speed"), px + 100, py + 154, skin.labelDim);
        label(g, Component.translatable("screen.customglint.glint_editor.scale"), px + 100, py + 170, skin.labelDim);
        label(g, Component.translatable("screen.customglint.glint_editor.glow_color"), px + 100, py + 220, glowEnabled ? skin.accent : skin.labelDim);
        if (glowOverrideColors.isEmpty()) {
            label(g, Component.translatable("screen.customglint.glint_editor.glow_auto"), px + 100, py + 238, glowEnabled ? skin.labelDim : skin.btnDisabled);
        }

        // Widgets (buttons + added edit boxes). Background is drawn by the framework
        // before extractRenderState, so no renderBackground call here.
        super.extractRenderState(g, mx, my, dt);

        // Item preview
        if (!previewStack.isEmpty()) {
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(bx + PREVIEW_SZ / 2f, by + PREVIEW_SZ / 2f);
            pose.scale(5.0f, 5.0f);
            g.item(previewStack, -8, -8);
            pose.popMatrix();
        }

        // Color swatches
        List<Integer> colors = currentColors();
        for (int i = 0; i < colors.size(); i++) {
            int sx = px + 100 + i * 18;
            int sy = py + 50;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17,
                   i == editingColorIdx ? skin.ring : skin.guiShadow);
            g.fill(sx, sy, sx + 16, sy + 16, 0xFF000000 | (colors.get(i) & 0xFFFFFF));
        }

        int previewColor = (editingGlowColor && editingGlowColorIdx < glowOverrideColors.size())
                ? glowOverrideColors.get(editingGlowColorIdx)
                : (colors.isEmpty() ? 0 : colors.get(Math.min(editingColorIdx, colors.size() - 1)));
        g.fill(px + 120, py + 68, px + 132, py + 80, 0xFF000000 | (previewColor & 0xFFFFFF));

        // Glow override swatches
        if (!glowOverrideColors.isEmpty()) {
            for (int i = 0; i < glowOverrideColors.size(); i++) {
                int sx = px + 100 + i * 18;
                int sy = py + 234;
                g.fill(sx - 1, sy - 1, sx + 17, sy + 17,
                        (editingGlowColor && i == editingGlowColorIdx) ? skin.ring : skin.guiShadow);
                g.fill(sx, sy, sx + 16, sy + 16, 0xFF000000 | (glowOverrideColors.get(i) & 0xFFFFFF));
            }
        }

        centered(g, GuiHelpers.fmtVal(layerSpeeds.get(selectedLayer)) + "×", px + 175, py + 154, skin.labelHdr);
        centered(g, GuiHelpers.fmtVal(layerScales.get(selectedLayer)) + "×", px + 175, py + 170, skin.labelHdr);
        if (layerScrollDirs.get(selectedLayer) == CustomGlint.SCROLL_STATIC) {
            centered(g, String.format("%.2f", layerScrollOffsets.get(selectedLayer)), px + 227, py + 204, skin.labelHdr);
        }

        // Design picker overlay
        if (showDesignPicker) {
            g.nextStratum();
            renderDesignPicker(g, mx, my, dt);
        }

        // Import picker overlay
        if (showImportPicker) {
            g.nextStratum();
            renderImportPicker(g, mx, my, dt);
        }

        // Item picker overlay
        if (showPicker) {
            g.nextStratum();
            renderPicker(g, mx, my, dt);
        }

        drawSaveMsg(g); // transient save confirmation, on top of everything

        // Hover tooltips for the control buttons (suppressed while an overlay owns the mouse).
        if (!showDesignPicker && !showPicker && !showImportPicker) {
            boolean shown = false;
            for (var child : children()) {
                if (!(child instanceof BevelButton bb) || bb.tooltip == null || !bb.visible) continue;
                if (mx < bb.getX() || mx >= bb.getX() + bb.getWidth() || my < bb.getY() || my >= bb.getY() + bb.getHeight()) continue;
                List<Component> lines = bb.tooltip.get();
                if (!lines.isEmpty()) { g.setTooltipForNextFrame(font, lines, Optional.empty(), mx, my); shown = true; break; }
            }
            // The add-layer "+" tab is hand-drawn, not a widget, so tooltip it here.
            if (!shown && layerDesigns.size() < MAX_LAYERS) {
                int plusX = px + 100 + layerDesigns.size() * 22, plusY = py + 6;
                if (mx >= plusX - 1 && mx < plusX + 21 && my >= plusY - 1 && my < plusY + 15)
                    g.setTooltipForNextFrame(font, Component.translatable("screen.customglint.glint_editor.tip.add_layer"), mx, my);
            }
        }
    }

    // ── Design picker rendering ───────────────────────────────────────────────

    private void renderDesignPicker(GuiGraphicsExtractor g, int mx, int my, float dt) {
        int ox = dpX(), oy = dpY();

        g.fill(ox - 1, oy - 1, ox + DPW + 1, oy + DPH + 1, 0xFF666666);
        g.fill(ox, oy, ox + DPW, oy + DPH, 0xEE111111);

        designSearchBox.setX(ox + 2);
        designSearchBox.setY(oy + 3);
        designSearchBox.setWidth(DPW - 4);
        designSearchBox.extractRenderState(g, mx, my, dt);

        int listY = oy + 20;
        int sbX   = ox + DPW - 5;
        String active = layerDesigns.get(selectedLayer);

        for (int i = 0; i < DESIGN_ROWS && designScroll + i < filteredDesigns.size(); i++) {
            String d = filteredDesigns.get(designScroll + i);
            int ry = listY + i * DESIGN_ROW_H;
            boolean hovered = mx >= ox && mx < sbX && my >= ry && my < ry + DESIGN_ROW_H;
            if (d.equals(active)) g.fill(ox, ry, sbX, ry + DESIGN_ROW_H, 0x6044AA44);
            if (hovered)          g.fill(ox, ry, sbX, ry + DESIGN_ROW_H, 0x40FFFFFF);
            label(g, d, ox + 4, ry + 3, 0xDDDDDD);
        }

        drawListScrollbar(g, sbX, listY, DESIGN_ROWS * DESIGN_ROW_H, DESIGN_ROWS, filteredDesigns.size(), designScroll, THUMB_MIN);
    }

    private void renderImportPicker(GuiGraphicsExtractor g, int mx, int my, float dt) {
        int ox = ipX(), oy = ipY();

        g.fill(ox - 1, oy - 1, ox + IPW + 1, oy + IPH + 1, 0xFF666666);
        g.fill(ox, oy, ox + IPW, oy + IPH, 0xEE111111);

        if (importSearchBox == null) {
            importSearchBox = new EditBox(font, ox + 2, oy + 3, IPW - 4, 12, Component.translatable("screen.customglint.glint_editor.search_glints"));
            importSearchBox.setMaxLength(40);
            importSearchBox.setResponder(this::filterGlints);
        }
        importSearchBox.setX(ox + 2);
        importSearchBox.setY(oy + 3);
        importSearchBox.setWidth(IPW - 4);
        importSearchBox.extractRenderState(g, mx, my, dt);

        // The server's reply to the open-time request (or a save/delete re-sync) arrives asynchronously;
        // rebuild the list when the synced pool changes size so new/removed blueprints show up.
        if (GlintServerBlueprintsSyncPacket.CLIENT_SERVER_BLUEPRINTS.size() != lastListedCount) rebuildImportList();

        int listY = oy + 20;
        int sbX   = ox + IPW - 5;

        if (availableGlints.isEmpty()) {
            label(g, Component.translatable("screen.customglint.glint_editor.import_empty"), ox + 4, listY + 2, 0xFF888888);
        }
        for (int i = 0; i < IMPORT_ROWS && importScroll + i < availableGlints.size(); i++) {
            String glint = availableGlints.get(importScroll + i);
            int ry = listY + i * IMPORT_ROW_H;
            boolean hovered = mx >= ox && mx < sbX && my >= ry && my < ry + IMPORT_ROW_H;
            if (hovered) g.fill(ox, ry, sbX, ry + IMPORT_ROW_H, 0x40FFFFFF);
            label(g, glint, ox + 4, ry + 2, 0xDDDDDD);
            // Trash icon on the far right of a hovered row (mirrors the Glint Table's import list).
            if (hovered) {
                boolean onTrash = mx >= sbX - IMP_TRASH_W && mx < sbX;
                GuiHelpers.drawTrashIcon(g, sbX - IMP_TRASH_W + 2, ry + 3, onTrash ? 0xFFFF5555 : 0xFFB05050);
            }
        }

        drawListScrollbar(g, sbX, listY, IMPORT_ROWS * IMPORT_ROW_H, IMPORT_ROWS, availableGlints.size(), importScroll, THUMB_MIN);
    }

    private void filterGlints(String query) {
        String lq = query == null ? "" : query.toLowerCase();
        availableGlints = lq.isEmpty() ? new ArrayList<>(allGlints) : allGlints.stream()
                .filter(d -> d.toLowerCase().contains(lq))
                .collect(Collectors.toList());
        importScroll = Math.max(0, Math.min(importScroll, Math.max(0, availableGlints.size() - IMPORT_ROWS)));
    }

    /** Trash a server blueprint from the Import list: ask the server to delete it (no op gate; the wand is
     *  the gate), drop it from the local mirror for instant feedback, and rebuild the list. The server's
     *  authoritative re-sync follows. */
    private void deleteImport(String name) {
        ClientPacketDistributor.sendToServer(new GlintWandDeleteBlueprintPacket(name));
        GlintServerBlueprintsSyncPacket.CLIENT_SERVER_BLUEPRINTS.remove(name);
        rebuildImportList();
    }

    /** Vertical list scrollbar: dark track + grey thumb sized to the visible fraction. No-op when it all fits. */
    private void drawListScrollbar(GuiGraphicsExtractor g, int sbX, int listY, int trackH, int visibleRows, int total, int scroll, int minThumb) {
        if (total <= visibleRows) return;
        g.fill(sbX, listY, sbX + 4, listY + trackH, 0xFF2A2A2A);
        int thumbH = Math.max(minThumb, trackH * visibleRows / total);
        int thumbY = listY + (int)((trackH - thumbH) * (float) scroll / (total - visibleRows));
        g.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, 0xFF888888);
    }

    // ── Item picker rendering ─────────────────────────────────────────────────

    private static final int OW = 200, OH = VISIBLE_ROWS * ROW_H + 20;

    private int pickerOX() { return Math.max(2, Math.min(width - OW - 2, px + 8)); }
    private int pickerOY() { return Math.max(2, Math.min(height - OH - 2, py + 159)); }

    private void renderPicker(GuiGraphicsExtractor g, int mx, int my, float dt) {
        int ox = pickerOX(), oy = pickerOY();

        g.fill(ox - 1, oy - 1, ox + OW + 1, oy + OH + 1, 0xFF666666);
        g.fill(ox, oy, ox + OW, oy + OH, 0xEE111111);

        searchBox.setX(ox + 2);
        searchBox.setY(oy + 3);
        searchBox.setWidth(OW - 4);
        searchBox.extractRenderState(g, mx, my, dt);

        int listY = oy + 20;
        int sbX   = ox + OW - 6;

        for (int i = 0; i < VISIBLE_ROWS && pickerScroll + i < filteredItems.size(); i++) {
            Item item = filteredItems.get(pickerScroll + i);
            int ry = listY + i * ROW_H;
            boolean hovered = mx >= ox && mx < sbX && my >= ry && my < ry + ROW_H;
            if (hovered) g.fill(ox, ry, sbX, ry + ROW_H, 0x40FFFFFF);
            g.item(itemIcon(item), ox + 2, ry + 1);
            label(g, font.plainSubstrByWidth(itemName(item), OW - 30), ox + 20, ry + 5, 0xDDDDDD);
        }

        drawListScrollbar(g, sbX, listY, VISIBLE_ROWS * ROW_H, VISIBLE_ROWS, filteredItems.size(), pickerScroll, ITEM_THUMB_MIN);
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();

        // Design picker
        if (showDesignPicker) {
            int ox = dpX(), oy = dpY();

            if (mx >= ox + 2 && mx < ox + DPW - 2 && my >= oy + 3 && my < oy + 17)
                designSearchBox.mouseClicked(event, doubleClick);

            if (mx < ox || mx >= ox + DPW || my < oy || my >= oy + DPH) {
                showDesignPicker = false;
                return true;
            }

            int listY = oy + 20, sbX = ox + DPW - 5;
            if (onScrollbar(mx, my, sbX, listY, DESIGN_ROWS * DESIGN_ROW_H, filteredDesigns.size(), DESIGN_ROWS)) {
                scrollDrag = 1; dragScroll(my); return true;
            }
            if (my >= listY && mx < sbX) {
                int row = (int)(my - listY) / DESIGN_ROW_H;
                int idx = designScroll + row;
                if (row < DESIGN_ROWS && idx < filteredDesigns.size()) {
                    layerDesigns.set(selectedLayer, filteredDesigns.get(idx));
                    showDesignPicker = false;
                    refreshPreview();
                    Minecraft.getInstance().schedule(this::rebuildWidgets);
                }
            }
            return true;
        }

        if (showImportPicker) {
            int ox = ipX(), oy = ipY();

            if (mx >= ox + 2 && mx < ox + IPW - 2 && my >= oy + 3 && my < oy + 17) {
                if (importSearchBox != null) importSearchBox.mouseClicked(event, doubleClick);
            }

            if (mx < ox || mx >= ox + IPW || my < oy || my >= oy + IPH) {
                showImportPicker = false;
                return true;
            }

            int listY = oy + 20, sbX = ox + IPW - 5;
            if (onScrollbar(mx, my, sbX, listY, IMPORT_ROWS * IMPORT_ROW_H, availableGlints.size(), IMPORT_ROWS)) {
                scrollDrag = 2; dragScroll(my); return true;
            }
            if (my >= listY && mx < sbX) {
                int row = (int)(my - listY) / IMPORT_ROW_H;
                int idx = importScroll + row;
                if (row < IMPORT_ROWS && idx < availableGlints.size()) {
                    // Trash hotzone on the right edge deletes the blueprint; anywhere else loads it.
                    if (mx >= sbX - IMP_TRASH_W) {
                        deleteImport(availableGlints.get(idx));
                    } else {
                        loadBlueprint(availableGlints.get(idx));
                        rebuildWidgets();
                    }
                }
            }
            return true;
        }

        // Item picker
        if (showPicker) {
            int ox = pickerOX(), oy = pickerOY();

            if (mx >= ox + 2 && mx < ox + OW - 2 && my >= oy + 3 && my < oy + 17)
                searchBox.mouseClicked(event, doubleClick);

            if (mx < ox || mx >= ox + OW || my < oy || my >= oy + OH) {
                showPicker = false;
                return true;
            }

            int listY = oy + 20, sbX = ox + OW - 6;
            if (onScrollbar(mx, my, sbX, listY, VISIBLE_ROWS * ROW_H, filteredItems.size(), VISIBLE_ROWS)) {
                scrollDrag = 3; dragScroll(my); return true;
            }
            if (my >= listY && mx < sbX) {
                int row = (int)(my - listY) / ROW_H;
                int idx = pickerScroll + row;
                if (row < VISIBLE_ROWS && idx < filteredItems.size()) {
                    previewItem = filteredItems.get(idx);
                    showPicker = false;
                    refreshPreview();
                    syncWandState();
                }
            }
            return true;
        }

        // Layer tab clicks
        int tabRowY = py + 6;
        if (my >= tabRowY && my < tabRowY + 14) {
            for (int i = 0; i < layerDesigns.size(); i++) {
                int tx = px + 100 + i * 22;
                if (mx >= tx && mx < tx + 20) {
                    if (i == selectedLayer && layerDesigns.size() > 1 && mx >= tx + 13) {
                        layerDesigns.remove(i);
                        layerColors.remove(i);
                        layerSpeeds.remove(i);
                        layerInterpolates.remove(i);
                        layerScales.remove(i);
                        layerSimultaneous.remove(i);
                        layerScrollDirs.remove(i);
                        layerScrollOffsets.remove(i);
                        if (selectedLayer >= layerDesigns.size()) selectedLayer = layerDesigns.size() - 1;
                        editingColorIdx = 0;
                        loadEditRGB();
                        Minecraft.getInstance().schedule(this::rebuildWidgets);
                    } else if (i != selectedLayer) {
                        selectedLayer = i;
                        editingColorIdx = 0;
                        loadEditRGB();
                        Minecraft.getInstance().schedule(this::rebuildWidgets);
                    }
                    return true;
                }
            }
            if (layerDesigns.size() < MAX_LAYERS) {
                int plusX = px + 100 + layerDesigns.size() * 22;
                if (mx >= plusX && mx < plusX + 20) {
                    layerDesigns.add(layerDesigns.get(selectedLayer));
                    List<Integer> lc = new ArrayList<>();
                    lc.add(DEFAULT_COLOR);
                    layerColors.add(lc);
                    layerSpeeds.add(layerSpeeds.get(selectedLayer));
                    layerInterpolates.add(layerInterpolates.get(selectedLayer));
                    layerScales.add(layerScales.get(selectedLayer));
                    layerSimultaneous.add(layerSimultaneous.get(selectedLayer));
                    layerScrollDirs.add(layerScrollDirs.get(selectedLayer));
                    layerScrollOffsets.add(layerScrollOffsets.get(selectedLayer));
                    selectedLayer = layerDesigns.size() - 1;
                    editingColorIdx = 0;
                    loadEditRGB();
                    Minecraft.getInstance().schedule(this::rebuildWidgets);
                    return true;
                }
            }
        }

        // Layer swatch clicks
        if (my >= py + 50 && my < py + 66) {
            List<Integer> colors = currentColors();
            for (int i = 0; i < colors.size(); i++) {
                int sx = px + 100 + i * 18;
                if (mx >= sx && mx < sx + 16) {
                    editingGlowColor = false;
                    editingColorIdx = i;
                    loadEditRGB();
                    syncChannelBoxes();
                    syncHexFromRGB();
                    return true;
                }
            }
        }

        // Glow override swatch clicks
        if (!glowOverrideColors.isEmpty() && my >= py + 218 && my < py + 234) {
            for (int i = 0; i < glowOverrideColors.size(); i++) {
                int sx = px + 100 + i * 18;
                if (mx >= sx && mx < sx + 16) {
                    editingGlowColor = true;
                    editingGlowColorIdx = i;
                    loadEditRGB();
                    syncChannelBoxes();
                    syncHexFromRGB();
                    syncGlowHexBox();
                    return true;
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (showDesignPicker) return pickerKey(designSearchBox, event, () -> showDesignPicker = false);
        if (showImportPicker && importSearchBox != null) return pickerKey(importSearchBox, event, () -> showImportPicker = false);
        if (showPicker) return pickerKey(searchBox, event, () -> showPicker = false);
        return super.keyPressed(event);
    }

    /** Route a key to the open picker's search box; Esc closes the picker. The key is always consumed, so it
     *  never reaches the screen behind the overlay. */
    private boolean pickerKey(EditBox box, KeyEvent event, Runnable close) {
        // Force focus before forwarding: the box isn't a screen child, and screen-customization
        // mods (e.g. FancyMenu) re-run init() to apply layouts, recreating this box unfocused.
        // An unfocused EditBox silently drops every key/char, which reads as "typing does nothing".
        box.setFocused(true);
        if (box.keyPressed(event)) return true;
        if (event.key() == KEY_ESCAPE) close.run();
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (showDesignPicker) { designSearchBox.setFocused(true); return designSearchBox.charTyped(event); }
        if (showImportPicker && importSearchBox != null) { importSearchBox.setFocused(true); return importSearchBox.charTyped(event); }
        if (showPicker)       { searchBox.setFocused(true);       return searchBox.charTyped(event); }
        return super.charTyped(event);
    }

    /** True when (mx,my) is over a picker's scrollbar column, and there is something to scroll. */
    private static boolean onScrollbar(double mx, double my, int sbX, int listY, int trackH, int total, int visible) {
        return total > visible && mx >= sbX && mx < sbX + 4 && my >= listY && my < listY + trackH;
    }

    /** Map a mouse Y on a scrollbar track to a scroll row (thumb-centred, clamped), matching each picker's
     *  drawn thumb geometry. */
    private static int scrollAt(double my, int listY, int trackH, int minThumb, int total, int visible) {
        int max = Math.max(0, total - visible);
        if (max <= 0) return 0;
        int thumbH = Math.max(minThumb, trackH * visible / total);
        float f = (float) ((my - listY - thumbH / 2.0) / (trackH - thumbH));
        return Math.round(Math.max(0f, Math.min(1f, f)) * max);
    }

    /** Update the scroll of the picker whose scrollbar is being dragged. */
    private void dragScroll(double my) {
        if (scrollDrag == 1)      designScroll = scrollAt(my, dpY() + 20, DESIGN_ROWS * DESIGN_ROW_H, THUMB_MIN, filteredDesigns.size(), DESIGN_ROWS);
        else if (scrollDrag == 2) importScroll = scrollAt(my, ipY() + 20, IMPORT_ROWS * IMPORT_ROW_H, THUMB_MIN, availableGlints.size(), IMPORT_ROWS);
        else if (scrollDrag == 3) pickerScroll = scrollAt(my, pickerOY() + 20, VISIBLE_ROWS * ROW_H, ITEM_THUMB_MIN, filteredItems.size(), VISIBLE_ROWS);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (scrollDrag != 0) { dragScroll(event.y()); return true; }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        scrollDrag = 0;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double delta) {
        if (showDesignPicker) {
            int s = wheelScroll(mx, my, dpX(), dpY(), DPW, DPH, designScroll, filteredDesigns.size(), DESIGN_ROWS, delta);
            if (s >= 0) { designScroll = s; return true; }
        }
        if (showImportPicker) {
            int s = wheelScroll(mx, my, ipX(), ipY(), IPW, IPH, importScroll, availableGlints.size(), IMPORT_ROWS, delta);
            if (s >= 0) { importScroll = s; return true; }
        }
        if (showPicker) {
            int s = wheelScroll(mx, my, pickerOX(), pickerOY(), OW, OH, pickerScroll, filteredItems.size(), VISIBLE_ROWS, delta);
            if (s >= 0) { pickerScroll = s; return true; }
        }
        return super.mouseScrolled(mx, my, scrollX, delta);
    }

    /** One wheel notch of scroll for a picker list, clamped to its row count. Returns the new scroll row, or
     *  -1 when the cursor is outside that picker's panel and the wheel isn't ours to handle. */
    private static int wheelScroll(double mx, double my, int ox, int oy, int w, int h,
                                   int scroll, int total, int rows, double delta) {
        if (mx < ox || mx >= ox + w || my < oy || my >= oy + h) return -1;
        int maxScroll = Math.max(0, total - rows);
        return Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(delta)));
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
