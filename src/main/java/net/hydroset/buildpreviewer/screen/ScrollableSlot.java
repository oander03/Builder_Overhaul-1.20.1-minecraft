package net.hydroset.buildpreviewer.screen;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.SlotItemHandler;

public class ScrollableSlot extends SlotItemHandler {
    private int activeIndex; // This is the index that changes when you scroll

    public ScrollableSlot(IItemHandler itemHandler, int index, int xPosition, int yPosition) {
        super(itemHandler, index, xPosition, yPosition);
        this.activeIndex = index;
    }

    public void setIndex(int newIndex) {
        this.activeIndex = newIndex;
    }

    @Override
    public int getSlotIndex() {
        return this.activeIndex;
    }

    @Override
    public ItemStack getItem() {
        return this.getItemHandler().getStackInSlot(this.activeIndex);
    }

    @Override
    public void set(ItemStack stack) {
        ((IItemHandlerModifiable) this.getItemHandler())
                .setStackInSlot(this.activeIndex, stack);
        this.setChanged();
    }

    @Override
    public void onQuickCraft(ItemStack oldStackIn, ItemStack newStackIn) {
        // Required for some inventory operations
    }


    @Override
    public int getMaxStackSize() {
        // Tells the slot its absolute maximum capacity
        return 1000;
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        // This is the crucial one!
        // It tells the mouse-drag/click logic that even if it's 'Dirt',
        // this slot can hold more than 64.
        return this.getMaxStackSize();
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        // Ensure the item being placed matches the requirement for this slot
        // This prevents players from putting Cobblestone into a Dirt ghost slot.
        if (this.container instanceof PreviewMenu menu) {
            var reqList = new java.util.ArrayList<>(menu.getBlockEntity().getRequiredItems().entrySet());
            if (this.activeIndex < reqList.size()) {
                return stack.is(reqList.get(this.activeIndex).getKey());
            }
        }
        return super.mayPlace(stack);
    }

    @Override
    public ItemStack remove(int amount) {
        return this.getItemHandler().extractItem(this.activeIndex, amount, false);
    }
}