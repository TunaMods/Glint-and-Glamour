package net.tunamods.customglint.module.menu;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-player Glint Table storage. On NeoForge 1.21.1 this lived in three {@code AttachmentType}s
 * (stored_designs / printed_trims / glint_table_contents); Forge 1.20.1 has no attachment API, so the same
 * three collections ride the player's {@code persistentData} under one {@code customglint_table} compound.
 * persistentData survives death and is saved with the player, matching the attachments' {@code copyOnDeath}.
 */
public final class GlintTablePlayerData {
    private GlintTablePlayerData() {}

    private static final String ROOT     = "customglint_table";
    private static final String STORED   = "stored_designs";
    private static final String PRINTED  = "printed_trims";
    private static final String CONTENTS = "contents";

    private static CompoundTag root(Player player) {
        CompoundTag pd = player.getPersistentData();
        if (!pd.contains(ROOT)) pd.put(ROOT, new CompoundTag());
        return pd.getCompound(ROOT);
    }

    // Stored design names

    public static List<String> storedDesigns(Player player) {
        List<String> out = new ArrayList<>();
        ListTag list = root(player).getList(STORED, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
        return out;
    }

    public static void setStoredDesigns(Player player, List<String> designs) {
        ListTag list = new ListTag();
        for (String d : designs) list.add(StringTag.valueOf(d));
        root(player).put(STORED, list);
    }

    // Printed (painted) trim library

    public static List<ItemStack> printedTrims(Player player) {
        List<ItemStack> out = new ArrayList<>();
        ListTag list = root(player).getList(PRINTED, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack s = ItemStack.of(list.getCompound(i));
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static void setPrintedTrims(Player player, List<ItemStack> trims) {
        ListTag list = new ListTag();
        for (ItemStack s : trims) if (!s.isEmpty()) list.add(s.save(new CompoundTag()));
        root(player).put(PRINTED, list);
    }

    // Table slot contents

    /** Fills {@code container} with the player's saved table contents (slot-indexed). */
    public static void loadContents(Player player, SimpleContainer container) {
        ListTag list = root(player).getList(CONTENTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag slotTag = list.getCompound(i);
            int slot = slotTag.getByte("Slot") & 255;
            if (slot < container.getContainerSize()) container.setItem(slot, ItemStack.of(slotTag));
        }
    }

    /** Persists {@code container} back into the player's table contents. */
    public static void saveContents(Player player, Container container) {
        ListTag list = new ListTag();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty()) continue;
            CompoundTag slotTag = new CompoundTag();
            slotTag.putByte("Slot", (byte) i);
            s.save(slotTag);
            list.add(slotTag);
        }
        root(player).put(CONTENTS, list);
    }
}
