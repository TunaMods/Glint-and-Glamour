package net.tunamods.customglint.module.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * S→C: the server's item glint rules ({@code /glint rule item}), sent on join and after every rule change or
 * reload. The client swaps them into {@link CustomGlint#setRuleItemGlints}, which the render paths read through
 * {@code CustomGlint.readForRender}. Only item rules travel: loot and drop rules are applied on the server.
 */
public record GlintRulesSyncPacket(Map<ResourceLocation, CompoundTag> rules) implements CustomPacketPayload {

    public static final Type<GlintRulesSyncPacket> TYPE =
            new Type<>(CustomGlint.res("glint_rules_sync"));

    public static final StreamCodec<FriendlyByteBuf, GlintRulesSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.rules.size());
                        for (Map.Entry<ResourceLocation, CompoundTag> e : pkt.rules.entrySet()) {
                            buf.writeResourceLocation(e.getKey());
                            buf.writeNbt(e.getValue());
                        }
                    },
                    buf -> {
                        int count = buf.readVarInt();
                        Map<ResourceLocation, CompoundTag> rules = new LinkedHashMap<>(ModNetworking.syncListCapacity(count));
                        for (int i = 0; i < count; i++) {
                            ResourceLocation item = buf.readResourceLocation();
                            CompoundTag glint = buf.readNbt();
                            if (glint != null) rules.put(item, glint);
                        }
                        return new GlintRulesSyncPacket(rules);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintRulesSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Map<Item, CustomGlint.Data> glints = new HashMap<>();
            for (Map.Entry<ResourceLocation, CompoundTag> e : pkt.rules.entrySet()) {
                // An item this client doesn't have resolves to empty; skip it rather than glint air.
                Optional<Item> item = BuiltInRegistries.ITEM.getOptional(e.getKey());
                CustomGlint.Data data = CustomGlint.fromTag(e.getValue());
                if (item.isEmpty() || data == null) continue;
                glints.put(item.get(), data);
            }
            CustomGlint.setRuleItemGlints(glints);
        });
    }
}
