package net.hydroset.buildpreviewer.screen;

import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.SlotItemHandler;

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

        // Add Block Inventory Slots (3x9 grid)
        this.blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 9; j++) {
                    this.addSlot(new SlotItemHandler(handler, j + i * 9, 8 + j * 18, 18 + i * 18));
                }
            }
        });

        addPlayerInventory(inv);
        addPlayerHotbar(inv);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Logic for Shift-Clicking items (Required to prevent crashes)
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
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
