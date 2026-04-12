package net.hydroset.buildpreviewer.screen;

import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.SlotItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PreviewMenu extends AbstractContainerMenu {
    private final PreviewBlockEntity blockEntity;

    // Client-side constructor
    public PreviewMenu(int containerId, Inventory inv, FriendlyByteBuf extraData) {
        this(containerId, inv, inv.player.level().getBlockEntity(extraData.readBlockPos()));
    }

    // Server-side constructor
    public PreviewMenu(int containerId, Inventory inv, BlockEntity entity) {
        super(ModMenuTypes.PREVIEW_MENU.get(), containerId);
        this.blockEntity = (PreviewBlockEntity) entity;

        final Player menuPlayer = inv.player;

        this.blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            final Map<Item, Integer> reqs = this.blockEntity.getRequiredItems();
            final List<Item> itemList = new ArrayList<>(reqs.keySet());

            // YOUR EXISTING LOOPS - Keep these exactly here
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 9; j++) {
                    final int slotIndex = j + i * 9;
                    int x = 8 + j * 18;
                    int y = 18 + i * 18;

                    this.addSlot(new SlotItemHandler(handler, slotIndex, x, y) {
                        @Override
                        public boolean mayPickup(Player player) {
                            return !net.hydroset.buildpreviewer.PreviewManager.isInPreview(player.getUUID());
                        }

                        @Override
                        public boolean mayPlace(ItemStack stack) {
                            if (net.hydroset.buildpreviewer.PreviewManager.isInPreview(menuPlayer.getUUID())) return false;

                            if (slotIndex < itemList.size()) {
                                Item requiredItem = itemList.get(slotIndex);
                                int totalNeeded = reqs.get(requiredItem);
                                int currentAmount = this.getItem().getCount();

                                // FIX: Only allow placing if the item matches AND the slot isn't already full
                                return stack.is(requiredItem) && currentAmount < totalNeeded;
                            }
                            return false;
                        }

                        @Override
                        public int getMaxStackSize() {
                            // This handles the "hard limit" for dragging/dropping
                            if (slotIndex < itemList.size()) {
                                return reqs.get(itemList.get(slotIndex));
                            }
                            return 64;
                        }

                        @Override
                        public int getMaxStackSize(ItemStack stack) {
                            return getMaxStackSize();
                        }
                    });
                }
            }
        });

        addPlayerInventory(inv);
        addPlayerHotbar(inv);
    }

    @Override
    public ItemStack quickMoveStack(Player playerIn, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (net.hydroset.buildpreviewer.PreviewManager.isInPreview(playerIn.getUUID())) {
            return ItemStack.EMPTY;
        }

        if (slot != null && slot.hasItem()) {
            ItemStack itemstack1 = slot.getItem();
            itemstack = itemstack1.copy();

            // 1. FROM Block Entity (0-26) TO Player Inventory (27+)
            if (index < 27) {
                if (!this.moveItemStackTo(itemstack1, 27, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            }
            // 2. FROM Player Inventory (27+) TO Block Entity (0-26)
            else {
                boolean moved = false;
                // We manually loop through our requirement slots to bypass the 64-stack limit
                for (int i = 0; i < 27; i++) {
                    Slot targetSlot = this.slots.get(i);

                    // Check if this slot even accepts this item and isn't full
                    if (targetSlot.mayPlace(itemstack1)) {
                        int maxInside = targetSlot.getMaxStackSize(); // Your custom cost (e.g., 180)
                        int currentInside = targetSlot.getItem().getCount();
                        int canAccept = maxInside - currentInside;

                        if (canAccept > 0) {
                            int toMove = Math.min(canAccept, itemstack1.getCount());

                            if (targetSlot.getItem().isEmpty()) {
                                ItemStack newStack = itemstack1.copy();
                                newStack.setCount(toMove);
                                targetSlot.set(newStack);
                            } else {
                                targetSlot.getItem().grow(toMove);
                            }

                            itemstack1.shrink(toMove);
                            targetSlot.setChanged();
                            moved = true;
                        }
                    }

                    // If we've moved the whole stack, stop looking at slots
                    if (itemstack1.isEmpty()) break;
                }

                // If we couldn't move anything at all, return empty to stop the loop
                if (!moved) return ItemStack.EMPTY;
            }

            // Standard cleanup logic
            if (itemstack1.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            // If nothing changed during the move, something went wrong
            if (itemstack1.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(playerIn, itemstack1);
        }

        return itemstack;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    // Inside PreviewMenu.java
    public PreviewBlockEntity getBlockEntity() {
        return this.blockEntity;
    }

    private void addPlayerInventory(Inventory playerInventory) {
        for (int i = 0; i < 3; ++i) {
            for (int l = 0; l < 9; ++l) {
                this.addSlot(new Slot(playerInventory, l + i * 9 + 9, 8 + l * 18, 84 + i * 18));
            }
        }
    }

    private void addPlayerHotbar(Inventory playerInventory) {
        for (int i = 0; i < 9; ++i) {
            this.addSlot(new Slot(playerInventory, i, 8 + i * 18, 142));
        }
    }

}
