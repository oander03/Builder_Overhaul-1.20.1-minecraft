package net.hydroset.buildpreviewer.block.entity;

import net.hydroset.buildpreviewer.PreviewManager;
import net.hydroset.buildpreviewer.block.PreviewBlock;
import net.hydroset.buildpreviewer.block.entity.ModBlockEntities;
import net.hydroset.buildpreviewer.screen.PreviewMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraft.network.chat.Component;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PreviewBlockEntity extends BlockEntity implements MenuProvider {

    private Map<BlockPos, PreviewManager.BuildSnapshot> buildSnapshots = new HashMap<>();

    private final ItemStackHandler itemHandler = new ItemStackHandler(108) {

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }



        @Override
        public int getSlotLimit(int slot) {
            return 1000;
        }



        @Override

        protected int getStackLimit(int slot, @org.jetbrains.annotations.NotNull ItemStack stack) {

// This forces the handler to ignore the item's natural limit (64)

            return getSlotLimit(slot);

        }

    };


    public PreviewBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PREVIEW_BE.get(), pos, state);
    }

    public boolean canPlayerAccess(Player player) {
        // If no one is currently in preview, anyone can open the block to start one
        if (this.ownerUUID == null) return true;

        // If a session is active, ONLY the owner can open it
        return player.getUUID().equals(this.ownerUUID);
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

    // This handles the packet sent by level.sendBlockUpdated
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // This prepares the data to be sent to the client
    @Override
    public @NotNull CompoundTag getUpdateTag() {
        CompoundTag nbt = super.getUpdateTag();
        saveAdditional(nbt); // This ensures the Inventory and RequiredItems are included
        return nbt;
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        lazyItemHandler.invalidate();
        hopperBlocker.invalidate();
    }

    public void updateBlock() {
        if (level != null && !level.isClientSide) { // Change to !level.isClientSide
            // 1. Mark the data as changed so it saves to the disk
            this.setChanged();

            // 2. Tell the world to send a sync packet to all nearby clients
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }



    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            // 1. Load the data into the BlockEntity variables
            this.load(tag);

            // 2. Refresh the render/light data without triggering a network loop
            if (level != null && level.isClientSide) {
                // This is the LIGHTWEIGHT way to tell Minecraft to redraw the block
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
            }
        }
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        // This receives that data on the Client side
        load(tag);
    }



    // Inside PreviewBlockEntity.java
    private Map<Item, Integer> requiredItems = new LinkedHashMap<>();
    private UUID ownerUUID;

    public void setRequiredItems(Map<Item, Integer> cost, UUID owner) {
        this.requiredItems = new HashMap<>(cost);
        this.ownerUUID = owner;
        this.setChanged(); // Critical: Tells Minecraft to save the data!
    }
    // Inside PreviewBlockEntity.java

    public void checkRequirementsAndCommit(ServerPlayer player) {
        UUID id = player.getUUID();

        // CHANGE: Check this.buildSnapshots instead of PreviewManager.pendingCommit
        boolean hasPendingBuild = !this.buildSnapshots.isEmpty();

        if (!hasPendingBuild) {
            player.sendSystemMessage(Component.literal("§cNo active build to finalize!"));
            return;
        }

        // Calculate items normally
        Map<Item, Integer> providedItems = new HashMap<>();
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                providedItems.put(stack.getItem(), providedItems.getOrDefault(stack.getItem(), 0) + stack.getCount());
            }
        }

        // 3. DEFINE 'hasEverything' by checking the costs
        boolean hasEverything = true;
        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            if (providedItems.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                hasEverything = false;
                int missingCount = entry.getValue() - providedItems.getOrDefault(entry.getKey(), 0);

                player.sendSystemMessage(Component.literal("§cMissing: " + missingCount + "x ")
                        .append(Component.translatable(entry.getKey().getDescriptionId())));
                break;
            }
        }

        // 4. If the check passed, consume items and build!
        if (hasEverything) {
            consumeRequiredItems();
            ejectItemsToPlayer(player);

            // CRITICAL: Pass 'this.buildSnapshots' here!
            PreviewBlock.commitBuild(player, this.buildSnapshots);

            // 4. Clear the manager's memory (if they happened to go into preview)
            PreviewManager.pendingCommit.remove(id);

            // 5. Clear this block's memory
            this.buildSnapshots.clear();
            this.requiredItems.clear();

            // Clear the physical slots
            for (int i = 0; i < itemHandler.getSlots(); i++) {
                itemHandler.setStackInSlot(i, ItemStack.EMPTY);
            }

            this.setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            player.closeContainer();
        }
    }

    public void ejectItemsToPlayer(ServerPlayer player) {
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                // Give item to player, or drop it at their feet if full
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
                itemHandler.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
        this.setChanged();
    }

    public Map<Item, Integer> getRequiredItems() {
        // If this is null or empty, the screen won't draw anything!
        return this.requiredItems;
    }

    private void consumeRequiredItems() {
        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            int toTake = entry.getValue();
            for (int i = 0; i < itemHandler.getSlots(); i++) {
                ItemStack stack = itemHandler.getStackInSlot(i);
                if (stack.is(entry.getKey())) {
                    int shrinkBy = Math.min(stack.getCount(), toTake);
                    // Use itemHandler.extractItem to ensure the handler knows it changed
                    itemHandler.extractItem(i, shrinkBy, false);
                    toTake -= shrinkBy;
                }
                if (toTake <= 0) break;
            }
        }
        this.setChanged();
    }

    public void drops() {
        if (this.level == null) return;

        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);

            if (!stack.isEmpty()) {
                // While we still have items in this "over-stacked" stack
                while (stack.getCount() > 0) {
                    // Determine how much to drop in this specific pile (max 64)
                    int dropCount = Math.min(stack.getCount(), stack.getMaxStackSize());

                    // Create a copy to drop so we don't mutate the original incorrectly
                    ItemStack dropStack = stack.copy();
                    dropStack.setCount(dropCount);

                    // Drop the legal-sized stack at the block's position
                    Containers.dropItemStack(this.level, this.worldPosition.getX(),
                            this.worldPosition.getY(), this.worldPosition.getZ(),
                            dropStack);

                    // Reduce the original stack by what we just dropped
                    stack.shrink(dropCount);
                }
            }
        }
    }

    public void setBuildData(Map<BlockPos, PreviewManager.BuildSnapshot> snapshots, Map<Item, Integer> cost, UUID owner) {
        this.buildSnapshots = new HashMap<>(snapshots); // Removed the extra underscores
        this.requiredItems = new HashMap<>(cost);
        this.ownerUUID = owner;
        this.setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);

        ListTag snapshotList = new ListTag();
        for (Map.Entry<BlockPos, PreviewManager.BuildSnapshot> entry : buildSnapshots.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("pos", entry.getKey().asLong());
            entryTag.put("original", NbtUtils.writeBlockState(entry.getValue().originalState));
            entryTag.put("build", NbtUtils.writeBlockState(entry.getValue().buildState));
            snapshotList.add(entryTag);
        }
        tag.put("BuildSnapshots", snapshotList);
        // Save the required items map
        CompoundTag itemsTag = new CompoundTag();
        int i = 0;
        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            // Save the Registry Name of the item (e.g., "minecraft:stone")
            entryTag.putString("item", ForgeRegistries.ITEMS.getKey(entry.getKey()).toString());
            entryTag.putInt("count", entry.getValue());
            itemsTag.put("item_" + i, entryTag);
            i++;
        }
        tag.put("RequiredItems", itemsTag);
        tag.putInt("RequiredItemsSize", i);

        tag.put("Inventory", itemHandler.serializeNBT());

        if (ownerUUID != null) {
            tag.putUUID("Owner", ownerUUID);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        // Clear existing data before loading
        this.buildSnapshots.clear();
        this.requiredItems.clear();

        // IMPORTANT: Use BuiltInRegistries if level is null (happens during world load)
        var lookup = net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup();

        if (tag.contains("BuildSnapshots")) {
            ListTag snapshotList = tag.getList("BuildSnapshots", 10);
            for (int i = 0; i < snapshotList.size(); i++) {
                CompoundTag entryTag = snapshotList.getCompound(i);
                BlockPos pos = BlockPos.of(entryTag.getLong("pos"));

                // Read the NBT tags back into BlockState objects
                BlockState original = NbtUtils.readBlockState(lookup, entryTag.getCompound("original"));
                BlockState build = NbtUtils.readBlockState(lookup, entryTag.getCompound("build"));

                this.buildSnapshots.put(pos, new PreviewManager.BuildSnapshot(original, build));
            }
        }

        // Load your Required Items
        if (tag.contains("RequiredItems")) {
            CompoundTag itemsTag = tag.getCompound("RequiredItems");
            int size = tag.getInt("RequiredItemsSize");
            for (int i = 0; i < size; i++) {
                CompoundTag entryTag = itemsTag.getCompound("item_" + i);
                ResourceLocation itemID = new ResourceLocation(entryTag.getString("item"));
                Item item = ForgeRegistries.ITEMS.getValue(itemID);
                int count = entryTag.getInt("count");
                if (item != null && item != Items.AIR) {
                    this.requiredItems.put(item, count);
                }
            }
        }

        // ADD THIS LINE: This puts the items back into the slots
        if (tag.contains("Inventory")) {
            itemHandler.deserializeNBT(tag.getCompound("Inventory"));
        }

        if (tag.hasUUID("Owner")) {
            this.ownerUUID = tag.getUUID("Owner");
        }
    }

    public Map<BlockPos, PreviewManager.BuildSnapshot> getBuildSnapshots() {
        return this.buildSnapshots;
    }

    private final LazyOptional<IItemHandler> lazyItemHandler = LazyOptional.of(() -> itemHandler);

    private final LazyOptional<IItemHandler> hopperBlocker = LazyOptional.of(() -> new IItemHandler() {
        @Override public int getSlots() { return itemHandler.getSlots(); }
        @Override public @NotNull ItemStack getStackInSlot(int slot) { return itemHandler.getStackInSlot(slot); }

        // Return the full stack back to the hopper so it doesn't "eat" the item
        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            return stack;
        }

        // Prevent hoppers/pipes from sucking items out
        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override public int getSlotLimit(int slot) { return itemHandler.getSlotLimit(slot); }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) { return false; }
    });

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            // side == null means internal access (Player/GUI)
            if (side == null) {
                return lazyItemHandler.cast();
            }
            // side != null means external access (Hopper/Pipe/Cables)
            return hopperBlocker.cast();
        }
        return super.getCapability(cap, side);
    }
    // You'll also need to override saveAdditional and load for NBT saving
}