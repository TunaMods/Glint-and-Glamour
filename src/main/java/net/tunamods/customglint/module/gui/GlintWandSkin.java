package net.tunamods.customglint.module.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.tunamods.customglint.common.CustomGlint;

import java.util.Locale;

/**
 * A Glint Wand (editor) skin: a background PNG plus a palette for everything the screen draws over it
 * (buttons, swatches, labels, the channel fields). The window frame, column divider and preview recess
 * live in the PNG; the palette colours the rest so the same draw code reads on a light or dark theme. Add a
 * skin by appending it to {@link #ALL} with its palette and a matching {@code glint_wand_<name>.png}; the
 * in-window button cycles them and the choice persists.
 */
class GlintWandSkin extends SkinBase {

    int btnHover, btnDisabled;

    // Text palette. labelHdr = primary text; accent = section headers; labelDim = secondary captions;
    // chR/chG/chB = the R/G/B channel captions (kept hued, picked to read on this theme's background).
    int labelHdr, accent, labelDim;
    int chR, chG, chB;
    int costOk, costBad;

    // Layer-tab faces + the selection ring.
    int tabActive, tabIdle, ring;

    GlintWandSkin(String name) {
        super(name);
    }

    // Skins

    static final GlintWandSkin DEFAULT = new GlintWandSkin("Default");
    static final GlintWandSkin DARK = new GlintWandSkin("Dark");
    static final GlintWandSkin FORGE = new GlintWandSkin("Forge");

    static {
        DEFAULT.guiFace = 0xFFC6C6C6; DEFAULT.guiLight = 0xFFFFFFFF; DEFAULT.guiShadow = 0xFF555555; DEFAULT.guiBorder = 0xFF000000;
        DEFAULT.btnHover = 0xFFD6D6D6; DEFAULT.btnDisabled = 0xFF9A9A9A;
        DEFAULT.labelHdr = 0xFF404040; DEFAULT.accent = 0xFF8A6A00; DEFAULT.labelDim = 0xFF606060;
        DEFAULT.chR = 0xFFB02020; DEFAULT.chG = 0xFF1E8A1E; DEFAULT.chB = 0xFF2848C8;
        DEFAULT.costOk = 0xFF2FAA2F; DEFAULT.costBad = 0xFFC23030;
        DEFAULT.tabActive = 0xFF44AA44; DEFAULT.tabIdle = 0xFF9A9A9A; DEFAULT.ring = 0xFF2FAA2F;

        DARK.guiFace = 0xFF2B2B30; DARK.guiLight = 0xFF45454C; DARK.guiShadow = 0xFF161619; DARK.guiBorder = 0xFF000000;
        DARK.btnHover = 0xFF3C3C44; DARK.btnDisabled = 0xFF1A1A1D;
        DARK.labelHdr = 0xFFC8C8CE; DARK.accent = 0xFFFFD479; DARK.labelDim = 0xFF9A9AA2;
        DARK.chR = 0xFFE06666; DARK.chG = 0xFF66E066; DARK.chB = 0xFF6688FF;
        DARK.costOk = 0xFF4CC44C; DARK.costBad = 0xFFE05050;
        DARK.tabActive = 0xFF44AA44; DARK.tabIdle = 0xFF2A2A2E; DARK.ring = 0xFF55FF55;

        FORGE.guiFace = 0xFF52565C; FORGE.guiLight = 0xFFA9ACB0; FORGE.guiShadow = 0xFF1F2124; FORGE.guiBorder = 0xFF0B0C0D;
        FORGE.btnHover = 0xFF787B80; FORGE.btnDisabled = 0xFF2D2F33;
        FORGE.labelHdr = 0xFFE2E6EC; FORGE.accent = 0xFFFFC24A; FORGE.labelDim = 0xFFAFB3B9;
        FORGE.chR = 0xFFE07A6A; FORGE.chG = 0xFF86E06A; FORGE.chB = 0xFF7AA0E0;
        FORGE.costOk = 0xFF66E04C; FORGE.costBad = 0xFFE0604C;
        FORGE.tabActive = 0xFF66A04C; FORGE.tabIdle = 0xFF3A3D42; FORGE.ring = 0xFFFF8A3A;
    }

    static final GlintWandSkin[] ALL = { DEFAULT, DARK, FORGE };

    static {
        // Background PNG per skin: assets/customglint/textures/gui/glint_wand_<name>.png
        for (GlintWandSkin s : ALL) {
            s.texture(CustomGlint.res(
                    "textures/gui/glint_wand_" + s.name.toLowerCase(Locale.ROOT) + ".png"));
        }
    }

    /** Warm every skin's background PNG so cycling skins in-menu doesn't cold-load one mid-click. getTexture
     *  caches, so only the first open of a session does any work. */
    static void preloadTextures() {
        for (GlintWandSkin s : ALL) Minecraft.getInstance().getTextureManager().getTexture(s.bgTexture);
    }

    static GlintWandSkin byIndex(int i) {
        return ALL[Math.floorMod(i, ALL.length)];
    }

    static int indexOf(GlintWandSkin s) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i] == s) return i;
        return 0;
    }
}
