package net.hydroset.buildpreviewer.block.entity;

import net.hydroset.buildpreviewer.block.entity.ModBlockEntities;
import net.hydroset.buildpreviewer.screen.PreviewMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

public class PreviewBlockEntity extends BlockEntity implements MenuProvider {
    // 27 slots like a chest
    private final ItemStackHandler itemHandler = new ItemStackHandler(27);

    public PreviewBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PREVIEW_BE.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Build Previewer");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new PreviewMenu(containerId, inventory, this);
    }

    // You'll also need to override saveAdditional and load for NBT saving
}