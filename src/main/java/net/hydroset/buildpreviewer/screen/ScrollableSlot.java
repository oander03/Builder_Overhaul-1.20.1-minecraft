package net.hydroset.buildpreviewer.screen;

import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;

public class ScrollableSlot extends SlotItemHandler {
    private int index;

    public ScrollableSlot(IItemHandler itemHandler, int index, int xPosition, int yPosition) {
        super(itemHandler, index, xPosition, yPosition);
        this.index = index;
    }

    // This allows us to shift what part of the inventory this slot sees
    public void setIndex(int index) {
        this.index = index;
    }

    @Override
    public int getSlotIndex() {
        return this.index;
    }
}