package net.tunamods.customglint.module.network;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModNetworking {

    // Bumped whenever a payload is added or its format changes, so a mismatched client is refused at login with
    // a clear message instead of failing on the first unknown payload.
    private static final String PROTOCOL = "2";

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModNetworking::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);

        // GlintApplyPacket       C→S  player applied or removed a custom glint from the glint wand editor
        registrar.playToServer(GlintApplyPacket.TYPE, GlintApplyPacket.STREAM_CODEC, GlintApplyPacket::handle);
        // GlintDesignSyncPacket  S→C  syncs data-pack design names to clients on join and reload
        registrar.playToClient(GlintDesignSyncPacket.TYPE, GlintDesignSyncPacket.STREAM_CODEC, GlintDesignSyncPacket::handle);
        // GiveGlintTrimPacket  C→S  gives a GlintTrimItem with current editor settings
        registrar.playToServer(GiveGlintTrimPacket.TYPE, GiveGlintTrimPacket.STREAM_CODEC, GiveGlintTrimPacket::handle);
        // GlintStoredSyncPacket  S→C  per-player set of Glint Table "stored" design names
        registrar.playToClient(GlintStoredSyncPacket.TYPE, GlintStoredSyncPacket.STREAM_CODEC, GlintStoredSyncPacket::handle);
        // GlintPrintedSyncPacket S→C  per-player library of finished (painted) trims
        registrar.playToClient(GlintPrintedSyncPacket.TYPE, GlintPrintedSyncPacket.STREAM_CODEC, GlintPrintedSyncPacket::handle);
        // GlintPrintPacket       C→S  Glint Table "Print", consume materials/dyes, output the trim
        registrar.playToServer(GlintPrintPacket.TYPE, GlintPrintPacket.STREAM_CODEC, GlintPrintPacket::handle);
        // GlintDepositPacket     C→S  drag-in: deposit the cursor-held trim into the table's library
        registrar.playToServer(GlintDepositPacket.TYPE, GlintDepositPacket.STREAM_CODEC, GlintDepositPacket::handle);
        // GlintWithdrawPacket    C→S  shift-click: pull a printed trim out of the library into the inventory
        registrar.playToServer(GlintWithdrawPacket.TYPE, GlintWithdrawPacket.STREAM_CODEC, GlintWithdrawPacket::handle);
        // GlintDeletePrintedPacket C→S shift-click: delete a still-locked imported trim from the library
        registrar.playToServer(GlintDeletePrintedPacket.TYPE, GlintDeletePrintedPacket.STREAM_CODEC, GlintDeletePrintedPacket::handle);
        // GlintGiveDesignPacket  C→S  shift-click: pull a free blank trim of a palette design into the inventory
        registrar.playToServer(GlintGiveDesignPacket.TYPE, GlintGiveDesignPacket.STREAM_CODEC, GlintGiveDesignPacket::handle);
        // GlintImportPacket      C→S  Import list: add a config trim to the library as a locked (dimmed) build target
        registrar.playToServer(GlintImportPacket.TYPE, GlintImportPacket.STREAM_CODEC, GlintImportPacket::handle);
        // GlintServerBlueprintsSyncPacket S→C  dedicated server's shared blueprint trims, pushed on table open
        registrar.playToClient(GlintServerBlueprintsSyncPacket.TYPE, GlintServerBlueprintsSyncPacket.STREAM_CODEC, GlintServerBlueprintsSyncPacket::handle);
        // GlintDeleteServerBlueprintPacket C→S  op-only: delete one of the server's shared blueprint trims
        registrar.playToServer(GlintDeleteServerBlueprintPacket.TYPE, GlintDeleteServerBlueprintPacket.STREAM_CODEC, GlintDeleteServerBlueprintPacket::handle);
        // GlintWandSaveBlueprintPacket    C→S  wand "Save Design": save the build to the shared blueprint pool
        registrar.playToServer(GlintWandSaveBlueprintPacket.TYPE, GlintWandSaveBlueprintPacket.STREAM_CODEC, GlintWandSaveBlueprintPacket::handle);
        // GlintWandDeleteBlueprintPacket  C→S  wand Import trash icon: delete a shared blueprint
        registrar.playToServer(GlintWandDeleteBlueprintPacket.TYPE, GlintWandDeleteBlueprintPacket.STREAM_CODEC, GlintWandDeleteBlueprintPacket::handle);
        // GlintWandRequestBlueprintsPacket C→S  wand Import open: request the current shared blueprint pool
        registrar.playToServer(GlintWandRequestBlueprintsPacket.TYPE, GlintWandRequestBlueprintsPacket.STREAM_CODEC, GlintWandRequestBlueprintsPacket::handle);
        // GlintRulesSyncPacket S→C  the server's item rules (/glint rule item), on join and after every change
        registrar.playToClient(GlintRulesSyncPacket.TYPE, GlintRulesSyncPacket.STREAM_CODEC, GlintRulesSyncPacket::handle);
    }
}
