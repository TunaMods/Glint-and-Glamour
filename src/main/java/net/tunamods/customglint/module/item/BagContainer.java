package net.tunamods.customglint.module.item;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * A Glint Bag's slots as a vanilla {@link Container} over the bag stack's {@code minecraft:container} component.
 * Every change is written straight back to the component, so the stack itself always holds the contents.
 * Only storable Glint items are accepted ({@link #canPlaceItem}); the empty stack is always allowed so a menu
 * can clear a slot.
 */
public final class BagContainer implements Container {
    private final ItemStack bag;
    private final NonNullList<ItemStack> items = NonNullList.withSize(GlintBagItem.SIZE, ItemStack.EMPTY);

    BagContainer(ItemStack bag) {
        this.bag = bag;
        bag.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
    }

    /** Merges {@code stack} into matching stacks first, then empty slots. Returns what didn't fit. */
    public ItemStack insert(ItemStack stack) {
        if (stack.isEmpty() || !canPlaceItem(0, stack)) return stack;
        ItemStack remaining = stack.copy();
        for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (int i = 0; i < items.size() && !remaining.isEmpty(); i++) {
                ItemStack cur = items.get(i);
                if (pass == 0 ? !ItemStack.isSameItemSameComponents(cur, remaining) : !cur.isEmpty()) continue;
                int room = getMaxStackSize(remaining) - cur.getCount();
                if (room <= 0) continue;
                int moved = Math.min(room, remaining.getCount());
                if (cur.isEmpty()) items.set(i, remaining.copyWithCount(moved));
                else cur.grow(moved);
                remaining.shrink(moved);
            }
        }
        if (remaining.getCount() != stack.getCount()) setChanged();
        return remaining;
    }

    @Override public int getContainerSize() { return items.size(); }

    @Override public boolean isEmpty() {
        for (ItemStack s : items) if (!s.isEmpty()) return false;
        return true;
    }

    @Override public ItemStack getItem(int slot) { return items.get(slot); }

    @Override public ItemStack removeItem(int slot, int count) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, count);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = ContainerHelper.takeItem(items, slot);
        setChanged();
        return removed;
    }

    @Override public void setItem(int slot, ItemStack itemStack) {
        items.set(slot, itemStack);
        setChanged();
    }

    @Override public void setChanged() {
        bag.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
    }

    @Override public boolean stillValid(Player player) { return true; }

    @Override public boolean canPlaceItem(int slot, ItemStack itemStack) {
        return itemStack.isEmpty() || GlintBagItem.canStore(itemStack);
    }

    @Override public void clearContent() {
        items.clear();
        setChanged();
    }
}
