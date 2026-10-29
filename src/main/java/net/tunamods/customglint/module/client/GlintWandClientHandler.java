package net.tunamods.customglint.module.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.tunamods.customglint.module.gui.GlintEditorScreen;

public final class GlintWandClientHandler {
    private GlintWandClientHandler() {}

    public static void openEditor(InteractionHand hand) {
        Minecraft.getInstance().gui.setScreen(new GlintEditorScreen(hand));
    }
}
