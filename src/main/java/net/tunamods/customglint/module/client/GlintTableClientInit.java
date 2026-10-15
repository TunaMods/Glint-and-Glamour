package net.tunamods.customglint.module.client;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.tunamods.customglint.module.gui.GlintBagScreen;
import net.tunamods.customglint.module.gui.GlintTableScreen;
import net.tunamods.customglint.module.menu.ModMenuTypes;
import net.tunamods.customglint.module.network.GlintPrintedSyncPacket;
import net.tunamods.customglint.module.network.GlintServerBlueprintsSyncPacket;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;
import net.tunamods.customglint.common.CustomGlint;

import java.util.Map;

/** Client-only wiring for the Glint Table and Glint Bag: binds each menu type to its screen, registers the
 *  table's skin-swapping block model, and drops the sync mirrors on disconnect so a later session can't read
 *  stale data from the previous server. */
public final class GlintTableClientInit {
    private GlintTableClientInit() {}

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(GlintTableClientInit::onClientSetup);
        GlintTableModelClient.register(modEventBus);
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            GlintStoredSyncPacket.clearClient();
            GlintPrintedSyncPacket.clearClient();
            GlintServerBlueprintsSyncPacket.clearClient();
            CustomGlint.setRuleItemGlints(Map.of()); // the next server sends its own item rules on join
            GlintTableScreen.clearSavedBuild();
            GlintTableModelClient.clearTracked();
        });
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenuTypes.GLINT_TABLE_MENU.get(), GlintTableScreen::new);
            MenuScreens.register(ModMenuTypes.GLINT_BAG_MENU.get(), GlintBagScreen::new);
        });
    }
}
