package net.tunamods.customglint.module.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.menu.ModMenuTypes;
import net.tunamods.customglint.module.gui.GlintBagScreen;
import net.tunamods.customglint.module.gui.GlintTableScreen;
import net.tunamods.customglint.module.network.GlintDesignSyncPacket;
import net.tunamods.customglint.module.network.GlintPrintedSyncPacket;
import net.tunamods.customglint.module.network.GlintServerBlueprintsSyncPacket;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;

import java.util.Map;

/** Client-only wiring for the Glint Table: binds the menu type to its screen. */
public final class GlintTableClientInit {
    private GlintTableClientInit() {}

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(GlintTableClientInit::onRegisterScreens);
        // Drop the client-side sync mirrors on disconnect so a later session can't read stale data from the
        // previous server before its first sync arrives.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            GlintStoredSyncPacket.clearClient();
            GlintPrintedSyncPacket.clearClient();
            GlintServerBlueprintsSyncPacket.clearClient();
            GlintDesignSyncPacket.clearClient();
            GlintTableScreen.clearSavedBuild();
            GlintTableModelClient.clearTracked();
            CustomGlint.setRuleItemGlints(Map.of()); // the next server sends its own item rules on join
        });
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.GLINT_TABLE_MENU.get(), GlintTableScreen::new);
        event.register(ModMenuTypes.GLINT_BAG_MENU.get(), GlintBagScreen::new);
    }
}
