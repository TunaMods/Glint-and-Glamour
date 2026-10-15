package net.tunamods.customglint.module.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.tunamods.customglint.common.CustomGlint;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * S→C: the server's item glint rules ({@code /glint rule item}), sent on join and after every rule change or
 * reload. The client swaps them into {@link CustomGlint#setRuleItemGlints}, which the render paths read through
 * {@code CustomGlint.readCached}. Only item rules travel: loot and drop rules are applied on the server.
 */
public class GlintRulesSyncPacket {

    /** Sanity cap on the wire count; one rule per item, so a real set is far smaller. */
    private static final int MAX_RULES = 4096;

    private final Map<ResourceLocation, CompoundTag> rules;

    public GlintRulesSyncPacket(Map<ResourceLocation, CompoundTag> rules) {
        this.rules = rules;
    }

    public static void encode(GlintRulesSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.rules.size());
        for (Map.Entry<ResourceLocation, CompoundTag> e : pkt.rules.entrySet()) {
            buf.writeResourceLocation(e.getKey());
            buf.writeNbt(e.getValue());
        }
    }

    public static GlintRulesSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_RULES) throw new DecoderException("Bad rule count: " + count);
        Map<ResourceLocation, CompoundTag> rules = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            ResourceLocation item = buf.readResourceLocation();
            CompoundTag glint = buf.readNbt();
            if (glint != null) rules.put(item, glint);
        }
        return new GlintRulesSyncPacket(rules);
    }

    public static void handle(GlintRulesSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetHandlers.work(ctx, () -> {
            Map<Item, CustomGlint.Data> glints = new HashMap<>();
            for (Map.Entry<ResourceLocation, CompoundTag> e : pkt.rules.entrySet()) {
                Item item = ForgeRegistries.ITEMS.getValue(e.getKey());
                CustomGlint.Data data = CustomGlint.fromTag(e.getValue());
                // An item this client doesn't have resolves to air; skip it rather than glint every empty slot.
                if (item == null || item == Items.AIR || data == null) continue;
                glints.put(item, data);
            }
            CustomGlint.setRuleItemGlints(glints);
        });
    }
}
