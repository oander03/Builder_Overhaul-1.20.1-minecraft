package net.hydroset.buildpreviewer.screen;

import net.hydroset.buildpreviewer.PreviewManager;
import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.hydroset.buildpreviewer.networking.ModMessages;
import net.hydroset.buildpreviewer.networking.ServerboundScrollPacket;
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

import static java.awt.SystemColor.menu;

public class PreviewMenu extends AbstractContainerMenu {
    private final PreviewBlockEntity blockEntity;

    public int scrollOffset = 0;

    public void scrollTo(int newOffset) {
        int totalItems = blockEntity.getRequiredItems().size();
        int totalRows = (int) Math.ceil(totalItems / 9.0);
        int maxScroll = Math.max(0, totalRows - 3);
        this.scrollOffset = Math.max(0, Math.min(newOffset, maxScroll));

        // Force the slots to point to new data indices
        for (int i = 0; i < 27; i++) {
            if (this.slots.get(i) instanceof ScrollableSlot scrollSlot) {
                scrollSlot.setIndex((scrollOffset * 9) + i);
            }
        }

        if (this.blockEntity.getLevel().isClientSide) {
            // TELL THE SERVER: "Hey, I scrolled!"
            ModMessages.sendToServer(new ServerboundScrollPacket(this.scrollOffset));
        } else {
            // THE SERVER'S TURN:
            // 1. Tell the server-side slots to update too
            // 2. Force a full inventory sync back to the client
            this.broadcastChanges();
            this.sendAllDataToRemote();
        }
    }

    private void updateSlotIndexes() {
        for (int i = 0; i < 27; i++) {
            if (this.slots.get(i) instanceof ScrollableSlot scrollSlot) {
                scrollSlot.setIndex((scrollOffset * 9) + i);
            }
        }
    }


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

            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 9; j++) {
                    final int slotIndex = j + i * 9;
                    int x = 8 + j * 18;
                    int y = 18 + i * 18;

                    // Use ScrollableSlot instead of SlotItemHandler
                    this.addSlot(new ScrollableSlot(handler, slotIndex, x, y) {
                        @Override
                        public boolean mayPickup(Player player) {
                            return !net.hydroset.buildpreviewer.PreviewManager.isInPreview(player.getUUID());
                        }

                        @Override
                        public boolean mayPlace(ItemStack stack) {
                            if (net.hydroset.buildpreviewer.PreviewManager.isInPreview(menuPlayer.getUUID())) return false;

                            // Get the requirement based on the CURRENT scrolled index
                            int currentIndex = getSlotIndex();
                            if (currentIndex < itemList.size()) {
                                Item requiredItem = itemList.get(currentIndex);
                                int totalNeeded = reqs.get(requiredItem);
                                return stack.is(requiredItem) && this.getItem().getCount() < totalNeeded;
                            }
                            return false;
                        }

                        @Override
                        public int getMaxStackSize() {
                            int currentIndex = getSlotIndex();
                            if (currentIndex < itemList.size()) {
                                return reqs.get(itemList.get(currentIndex));
                            }
                            return 64;
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

        if (slot != null && slot.hasItem()) {
            ItemStack itemstack1 = slot.getItem();
            itemstack = itemstack1.copy();

            // 1. FROM Screen Slots (0-26) TO Player Inventory (27+)
            if (index < 27) {
                if (!this.moveItemStackTo(itemstack1, 27, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            }
            // 2. FROM Player Inventory TO Block Entity (The fix is here)
            else {
                boolean moved = false;
                var handler = this.blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);

                if (handler != null) {
                    Map<Item, Integer> reqs = this.blockEntity.getRequiredItems();
                    List<Item> itemList = new ArrayList<>(reqs.keySet());

                    // Check EVERY requirement, even if it's currently scrolled off-screen
                    for (int i = 0; i < itemList.size(); i++) {
                        Item requiredItem = itemList.get(i);
                        if (itemstack1.is(requiredItem)) {
                            int totalNeeded = reqs.get(requiredItem);
                            ItemStack currentInSlot = handler.getStackInSlot(i);

                            if (currentInSlot.getCount() < totalNeeded) {
                                int canAccept = totalNeeded - currentInSlot.getCount();
                                int toMove = Math.min(canAccept, itemstack1.getCount());

                                // Manually move the items into the handler
                                ItemStack moveStack = itemstack1.split(toMove);
                                handler.insertItem(i, moveStack, false);
                                moved = true;
                            }
                        }
                        if (itemstack1.isEmpty()) break;
                    }
                }
                if (!moved) return ItemStack.EMPTY;
            }

            if (itemstack1.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            // Final sync check
            this.broadcastChanges();
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
