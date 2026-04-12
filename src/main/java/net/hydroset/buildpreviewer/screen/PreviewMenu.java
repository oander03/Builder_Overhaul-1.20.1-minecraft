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

        // Create a final reference to the player from the inventory
        final Player menuPlayer = inv.player;

        // Add Block Inventory Slots (3x9 grid)
        this.blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            // 1. Convert the map to a fixed list so slot 0 always corresponds to item 0
            final Map<Item, Integer> reqs = this.blockEntity.getRequiredItems();
            final List<Item> itemList = new ArrayList<>(reqs.keySet());

            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 9; j++) {
                    final int slotIndex = j + i * 9; // Must be final
                    int x = 8 + j * 18;
                    int y = 18 + i * 18;

                    this.addSlot(new SlotItemHandler(handler, slotIndex, x, y) {
                        @Override
                        public boolean mayPickup(Player player) {
                            // Block taking items out if in preview
                            return !net.hydroset.buildpreviewer.PreviewManager.isInPreview(player.getUUID());
                        }

                        @Override
                        public boolean mayPlace(ItemStack stack) {
                            // 1. Block placing in preview
                            if (net.hydroset.buildpreviewer.PreviewManager.isInPreview(menuPlayer.getUUID())) return false;

                            // 2. Check if this slot corresponds to a requirement
                            if (slotIndex < itemList.size()) {
                                Item requiredItem = itemList.get(slotIndex);
                                int requiredAmount = reqs.get(requiredItem);
                                int currentAmount = this.getItem().getCount();

                                // Only allow if item matches AND we need more
                                return stack.is(requiredItem) && currentAmount < requiredAmount;
                            }
                            return false;
                        }

                        @Override
                        public int getMaxStackSize(ItemStack stack) {
                            if (slotIndex < itemList.size()) {
                                return reqs.get(itemList.get(slotIndex));
                            }
                            return super.getMaxStackSize(stack);
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

            // If the item is in the Block Entity (Slots 0-26)
            if (index < 27) {
                // Try to move it to the Player Inventory (Slots 27-62)
                if (!this.moveItemStackTo(itemstack1, 27, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            }
            // If the item is in the Player Inventory
            else {
                // Try to move it into the Block Entity (Slots 0-26)
                if (!this.moveItemStackTo(itemstack1, 0, 27, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (itemstack1.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
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
