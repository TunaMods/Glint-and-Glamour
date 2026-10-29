package net.tunamods.customglint.module.menu;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.module.item.BagContainer;
import net.tunamods.customglint.module.item.GlintBagItem;

/**
 * Server-side container for the {@link GlintBagItem}. Slots 0..{@link GlintBagItem#SIZE} are the bag's item
 * handler (only storable Glint items); the rest are the player inventory. The slot the open bag occupies is
 * locked so it can't be moved or swapped out from under the menu.
 */
public class GlintBagMenu extends AbstractContainerMenu {

    private final Player player;
    private final ItemStack bag;
    private final BagContainer handler;

    /** Client constructor; the hand is sent as a boolean in the open packet. */
    public GlintBagMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBoolean() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
    }

    public GlintBagMenu(int containerId, Inventory inventory, InteractionHand hand) {
        super(ModMenuTypes.GLINT_BAG_MENU.get(), containerId);
        this.player = inventory.player;
        this.bag = player.getItemInHand(hand);
        this.handler = GlintBagItem.contents(bag);

        // The player-inventory slot the bag lives in. Off-hand isn't shown in this menu, so it can't be moved
        // anyway; only a hotbar/main-inventory bag needs locking.
        int lockedIndex = hand == InteractionHand.MAIN_HAND ? inventory.getSelectedSlot() : -1;

        // Bag grid (6×9), laid out like a large chest.
        for (int row = 0; row < GlintBagItem.ROWS; row++)
            for (int col = 0; col < GlintBagItem.COLS; col++)
                addSlot(bagSlot(handler, col + row * GlintBagItem.COLS, 8 + col * 18, 18 + row * 18));

        // Player inventory, positioned to match minecraft's generic_54 background.
        int invY = 18 + GlintBagItem.ROWS * 18 + 13; // +13: gap between the bag grid and player inventory
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++) {
                int idx = col + row * 9 + 9;
                addSlot(lockableSlot(inventory, idx, 8 + col * 18, invY + row * 18, idx == lockedIndex));
            }
        for (int col = 0; col < 9; col++)
            addSlot(lockableSlot(inventory, col, 8 + col * 18, invY + 58, col == lockedIndex));
    }

    /** A bag grid slot. Vanilla {@link Slot#mayPlace} accepts anything, so it defers to the bag's filter. */
    private static Slot bagSlot(BagContainer bag, int index, int x, int y) {
        return new Slot(bag, index, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return bag.canPlaceItem(index, stack);
            }
        };
    }

    private static Slot lockableSlot(Inventory inv, int index, int x, int y, boolean locked) {
        if (!locked) return new Slot(inv, index, x, y);
        return new Slot(inv, index, x, y) {
            @Override
            public boolean mayPickup(Player p) {
                return false; // can't pull the open bag out of its slot
            }
        };
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int bagEnd = GlintBagItem.SIZE;
        int invEnd = bagEnd + 36;

        if (index < bagEnd) {
            // Bag → player inventory.
            if (!moveItemStackTo(stack, bagEnd, invEnd, true)) return ItemStack.EMPTY;
        } else {
            // Player inventory → bag, storable items only.
            if (!GlintBagItem.canStore(stack)) return ItemStack.EMPTY;
            if (!moveItemStackTo(stack, 0, bagEnd, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return !bag.isEmpty() && bag.getItem() instanceof GlintBagItem
                && (player.getMainHandItem() == bag || player.getOffhandItem() == bag);
    }
}
