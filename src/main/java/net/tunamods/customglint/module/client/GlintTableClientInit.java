package net.tunamods.customglint.module.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.module.gui.GlintBagScreen;
import net.tunamods.customglint.module.gui.GlintTableScreen;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.menu.ModMenuTypes;
import net.tunamods.customglint.module.network.GlintPrintedSyncPacket;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;

import java.util.Map;
import java.util.stream.Collectors;

/** Client-only wiring for the Glint Table: binds the menu type to its screen + drops the sync mirrors on
 *  disconnect so a later session can't read stale data from the previous server. */
public final class GlintTableClientInit {
    private GlintTableClientInit() {}

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(GlintTableClientInit::onRegisterScreens);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            GlintStoredSyncPacket.clearClient();
            GlintPrintedSyncPacket.clearClient();
            GlintTableScreen.clearSavedBuild();
            GlintTableModelClient.clearTracked();
            CustomGlint.setRuleItemGlints(Map.of()); // the next server sends its own item rules on join
        });
        // Feed the shared GUI design atlas the data-pack-inclusive design list (built-ins + synced designs), so
        // every trim icon on a palette batches through the one atlas RenderType instead of a draw per design.
        // Reading the live PATTERNS each build means data-pack designs join on the next (re)build; invalidate on
        // login so a reconnect restitches against the new server's synced set.
        CustomGlintRenderer.setGuiAtlasDesignSource(
                () -> GlintTrimItem.PATTERNS.stream().map(CustomGlint::designFromName).collect(Collectors.toList()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) ->
                CustomGlintRenderer.invalidateGuiDesignAtlas());
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.GLINT_TABLE_MENU.get(), GlintTableScreen::new);
        event.register(ModMenuTypes.GLINT_BAG_MENU.get(), GlintBagScreen::new);
    }
}
