package net.tunamods.customglint.module.network;

import net.tunamods.customglint.common.CustomGlint;

import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * The standalone mod's packet channel. The ids below are wire format: never renumber one, never reuse a
 * retired one. The API jar has its own separate channel ({@code customglint_api:main}) for entity sync.
 */
public class ModNetworking {

    // Bumped whenever a packet is added or its format changes, so a mismatched client is refused at login with a
    // clear message instead of failing on the first unknown packet.
    private static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CustomGlint.res("main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    public static void register() {
        // 0: GlintApplyPacket       C→S  player applied or removed a custom glint from the glint wand editor
        CHANNEL.registerMessage(0, GlintApplyPacket.class, GlintApplyPacket::encode, GlintApplyPacket::decode, GlintApplyPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 1: GlintDesignSyncPacket  S→C  syncs data-pack design names to clients on join and reload
        CHANNEL.registerMessage(1, GlintDesignSyncPacket.class, GlintDesignSyncPacket::encode, GlintDesignSyncPacket::decode, GlintDesignSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 2: GlintMountArmorSyncPacket  S→C  syncs IaF hippogryph/hippocampus armor ItemStack to clients (compat: only sent by IaF compat code)
        CHANNEL.registerMessage(2, GlintMountArmorSyncPacket.class, GlintMountArmorSyncPacket::encode, GlintMountArmorSyncPacket::decode, GlintMountArmorSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 3: GiveGlintTrimPacket  C→S  gives a GlintTrimItem with current editor settings
        CHANNEL.registerMessage(3, GiveGlintTrimPacket.class, GiveGlintTrimPacket::encode, GiveGlintTrimPacket::decode, GiveGlintTrimPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 4: (reserved) was GlintEntitySyncPacket, moved to ApiNetworking under customglint_api:main
        // 5: GlintPrintPacket        C→S  Glint Table "Print" button: build + validate + output a trim
        CHANNEL.registerMessage(5, GlintPrintPacket.class, GlintPrintPacket::encode, GlintPrintPacket::decode, GlintPrintPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 6: GlintDepositPacket      C→S  drop a cursor-held trim into the table's library
        CHANNEL.registerMessage(6, GlintDepositPacket.class, GlintDepositPacket::encode, GlintDepositPacket::decode, GlintDepositPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 7: GlintWithdrawPacket     C→S  pull a printed trim out of the library
        CHANNEL.registerMessage(7, GlintWithdrawPacket.class, GlintWithdrawPacket::encode, GlintWithdrawPacket::decode, GlintWithdrawPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 8: GlintGiveDesignPacket   C→S  hand the player a free blank trim of a palette design
        CHANNEL.registerMessage(8, GlintGiveDesignPacket.class, GlintGiveDesignPacket::encode, GlintGiveDesignPacket::decode, GlintGiveDesignPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 9: GlintStoredSyncPacket   S→C  syncs the player's stored-design set to the client
        CHANNEL.registerMessage(9, GlintStoredSyncPacket.class, GlintStoredSyncPacket::encode, GlintStoredSyncPacket::decode, GlintStoredSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 10: GlintPrintedSyncPacket S→C  syncs the player's printed-trim library to the client
        CHANNEL.registerMessage(10, GlintPrintedSyncPacket.class, GlintPrintedSyncPacket::encode, GlintPrintedSyncPacket::decode, GlintPrintedSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 11: GlintImportPacket       C→S  Import list: add a config trim to the library as a locked build target
        CHANNEL.registerMessage(11, GlintImportPacket.class, GlintImportPacket::encode, GlintImportPacket::decode, GlintImportPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 12: GlintDeletePrintedPacket C→S  shift-click a still-locked imported trim → remove it from the library
        CHANNEL.registerMessage(12, GlintDeletePrintedPacket.class, GlintDeletePrintedPacket::encode, GlintDeletePrintedPacket::decode, GlintDeletePrintedPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        // Shared blueprints (server-curated + wand Save Design).
        // 13: GlintServerBlueprintsSyncPacket S→C  the dedicated server's shared blueprint trims, pushed on open
        CHANNEL.registerMessage(13, GlintServerBlueprintsSyncPacket.class, GlintServerBlueprintsSyncPacket::encode, GlintServerBlueprintsSyncPacket::decode, GlintServerBlueprintsSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 14: GlintDeleteServerBlueprintPacket C→S  op-only: delete one shared blueprint
        CHANNEL.registerMessage(14, GlintDeleteServerBlueprintPacket.class, GlintDeleteServerBlueprintPacket::encode, GlintDeleteServerBlueprintPacket::decode, GlintDeleteServerBlueprintPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 15: GlintWandSaveBlueprintPacket    C→S  wand "Save Design": save the build to the shared blueprint pool
        CHANNEL.registerMessage(15, GlintWandSaveBlueprintPacket.class, GlintWandSaveBlueprintPacket::encode, GlintWandSaveBlueprintPacket::decode, GlintWandSaveBlueprintPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 16: GlintWandDeleteBlueprintPacket  C→S  wand Import trash icon: delete a shared blueprint
        CHANNEL.registerMessage(16, GlintWandDeleteBlueprintPacket.class, GlintWandDeleteBlueprintPacket::encode, GlintWandDeleteBlueprintPacket::decode, GlintWandDeleteBlueprintPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 17: GlintWandRequestBlueprintsPacket C→S  wand Import open: request the current shared blueprint pool
        CHANNEL.registerMessage(17, GlintWandRequestBlueprintsPacket.class, GlintWandRequestBlueprintsPacket::encode, GlintWandRequestBlueprintsPacket::decode, GlintWandRequestBlueprintsPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        // Glint rules (/glint rule).
        // 18: GlintRulesSyncPacket S→C  the server's item rules, on join and after every rule change or reload
        CHANNEL.registerMessage(18, GlintRulesSyncPacket.class, GlintRulesSyncPacket::encode, GlintRulesSyncPacket::decode, GlintRulesSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
