package net.hydroset.buildpreviewer.block.entity;

import net.hydroset.buildpreviewer.PreviewManager;
import net.hydroset.buildpreviewer.block.PreviewBlock;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
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

        private long lastSyncTime = 0;
        private static final long SYNC_INTERVAL_MS = 500; // sync at most 2x per second

        @Override
        protected void onContentsChanged(int slot) {
            setChanged(); // Always mark dirty for saving — this is fine

            if (level != null && !level.isClientSide) {
                long now = System.currentTimeMillis();
                if (now - lastSyncTime >= SYNC_INTERVAL_MS) {
                    lastSyncTime = now;
                    level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
                }
            }
        }



        @Override
        public int getSlotLimit(int slot) {
            return 999999;
        }

        @Override
        protected int getStackLimit(int slot, @NotNull ItemStack stack) {
            return 999999;
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
        return Component.literal("Required Items");
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

    public void dumpItemsToPlayer(ServerPlayer player) {
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            // Split into legal stack sizes and give to player
            while (stack.getCount() > 0) {
                int giveCount = Math.min(stack.getCount(), stack.getItem().getMaxStackSize());
                ItemStack toGive = stack.copy();
                toGive.setCount(giveCount);

                if (!player.getInventory().add(toGive)) {
                    // Inventory full — drop at player's feet instead
                    player.drop(toGive, false);
                }

                stack.shrink(giveCount);
            }

            // Clear the slot in the block entity
            itemHandler.setStackInSlot(i, ItemStack.EMPTY);
        }

        this.setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
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
        if (this.buildSnapshots.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cNo active build to finalize!"));
            return;
        }

        Map<BlockPos, PreviewManager.BuildSnapshot> blocksToPlace = new HashMap<>();
        Map<BlockPos, PreviewManager.BuildSnapshot> remainingSnapshots = new HashMap<>(this.buildSnapshots);

        for (Map.Entry<BlockPos, PreviewManager.BuildSnapshot> entry : this.buildSnapshots.entrySet()) {
            BlockPos pos = entry.getKey();
            PreviewManager.BuildSnapshot snapshot = entry.getValue();
            BlockState plannedState = snapshot.buildState;

            // Free removals
            if (plannedState.isAir()) {
                blocksToPlace.put(pos, snapshot);
                remainingSnapshots.remove(pos);
                continue;
            }

            // Skip secondary halves — they ride along for free with their primary
            if (PreviewManager.isSecondaryHalf(plannedState)) continue;

            // Try to consume one item for the primary half
            Item itemNeeded = plannedState.getBlock().asItem();
            if (itemNeeded != Items.AIR && hasAndConsumeSingleItem(itemNeeded)) {
                blocksToPlace.put(pos, snapshot);
                remainingSnapshots.remove(pos);

                // Find and add the secondary half automatically (no item cost)
                for (BlockPos neighbor : new BlockPos[]{
                        pos.north(), pos.south(), pos.east(), pos.west(), pos.above(), pos.below()}) {
                    PreviewManager.BuildSnapshot neighborSnapshot = this.buildSnapshots.get(neighbor);
                    if (neighborSnapshot != null
                            && PreviewManager.isSecondaryHalf(neighborSnapshot.buildState)
                            && neighborSnapshot.buildState.getBlock() == plannedState.getBlock()) {
                        blocksToPlace.put(neighbor, neighborSnapshot);
                        remainingSnapshots.remove(neighbor);
                        break;
                    }
                }
            }
        }

        if (!blocksToPlace.isEmpty()) {
            PreviewBlock.commitBuild(player, blocksToPlace);
            this.buildSnapshots = remainingSnapshots;
            this.requiredItems = PreviewManager.calculateRequiredItemsFromMap(this.buildSnapshots);
        } else {
            player.sendSystemMessage(Component.literal("§cNot enough items to place any more blocks!"));
        }

// Remove the old if (this.buildSnapshots.isEmpty()) block and just always close:
        if (this.buildSnapshots.isEmpty()) {
            this.requiredItems.clear();
        }
        player.closeContainer(); // Always close, regardless of remaining snapshots

        this.setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private boolean hasAndConsumeSingleItem(Item item) {
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (stack.is(item) && !stack.isEmpty()) {
                // Extract 1 item (false = not a simulation, actually do it)
                itemHandler.extractItem(i, 1, false);
                return true;
            }
        }
        return false;
    }

    public Map<Item, Integer> getRequiredItems() {
        // If this is null or empty, the screen won't draw anything!
        return this.requiredItems;
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




    private void saveBuildSnapshots(CompoundTag tag) {
        ListTag snapshotList = new ListTag();
        for (Map.Entry<BlockPos, PreviewManager.BuildSnapshot> entry : buildSnapshots.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("pos", entry.getKey().asLong());
            entryTag.put("original", NbtUtils.writeBlockState(entry.getValue().originalState));
            entryTag.put("build", NbtUtils.writeBlockState(entry.getValue().buildState));
            snapshotList.add(entryTag);
        }
        tag.put("BuildSnapshots", snapshotList);
    }

    // Add this field alongside your other fields
    private ListTag savedPlayerInventory = null;

    // Add these two methods
    public void savePlayerInventory(ListTag inventoryTag) {
        this.savedPlayerInventory = inventoryTag.copy();
        this.setChanged();
    }

    public ListTag getAndClearSavedInventory() {
        ListTag tag = this.savedPlayerInventory;
        this.savedPlayerInventory = null;
        this.setChanged();
        return tag;
    }

    // Full save to disk — everything
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        saveInventory(tag);
        saveRequiredItems(tag);
        saveBuildSnapshots(tag); // expensive, but only for disk
        if (ownerUUID != null) tag.putUUID("Owner", ownerUUID);
        // ✅ NEW: persist the player's real inventory so it survives restarts
        if (savedPlayerInventory != null) {
            tag.put("SavedPlayerInventory", savedPlayerInventory);
        }
        if (savedGameMode != null) tag.putInt("SavedGameMode", savedGameMode.getId());

    }

    private GameType savedGameMode = null;

    public void saveGameMode(GameType gameType) {
        this.savedGameMode = gameType;
        setChanged();
    }

    public GameType getAndClearSavedGameMode() {
        GameType gm = this.savedGameMode;
        this.savedGameMode = null;
        setChanged();
        return gm;
    }

    // Lightweight client sync — skip build snapshots entirely
    @Override
    public @NotNull CompoundTag getUpdateTag() {
        CompoundTag nbt = new CompoundTag();
        saveInventory(nbt);
        saveRequiredItems(nbt);
        if (ownerUUID != null) nbt.putUUID("Owner", ownerUUID);
        if (savedPlayerInventory != null) {
            nbt.put("SavedPlayerInventory", savedPlayerInventory); // ✅ add this
        }
        return nbt;
    }

    // Extract these into helper methods
    private void saveInventory(CompoundTag tag) {
        ListTag inventoryList = new ListTag();
        for (int j = 0; j < itemHandler.getSlots(); j++) {
            ItemStack stack = itemHandler.getStackInSlot(j);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putInt("Slot", j);
                slotTag.putString("Item", ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
                slotTag.putInt("Count", stack.getCount());
                inventoryList.add(slotTag);
            }
        }
        tag.put("Inventory", inventoryList);
    }

    private void saveRequiredItems(CompoundTag tag) {
        CompoundTag itemsTag = new CompoundTag();
        int i = 0;
        for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("item", ForgeRegistries.ITEMS.getKey(entry.getKey()).toString());
            entryTag.putInt("count", entry.getValue());
            itemsTag.put("item_" + i, entryTag);
            i++;
        }
        tag.put("RequiredItems", itemsTag);
        tag.putInt("RequiredItemsSize", i);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        var lookup = this.level != null ?
                this.level.holderLookup(Registries.BLOCK) :
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup();

        if (tag.contains("SavedPlayerInventory")) {
            this.savedPlayerInventory = tag.getList("SavedPlayerInventory", 10);
        }
        if (tag.contains("SavedGameMode")) {
            this.savedGameMode = GameType.byId(tag.getInt("SavedGameMode"));
        }

        if (tag.contains("BuildSnapshots")) {
            this.buildSnapshots.clear();
            ListTag snapshotList = tag.getList("BuildSnapshots", 10);
            for (int i = 0; i < snapshotList.size(); i++) {
                CompoundTag entryTag = snapshotList.getCompound(i);
                BlockPos pos = BlockPos.of(entryTag.getLong("pos"));
                BlockState original = NbtUtils.readBlockState(lookup, entryTag.getCompound("original"));
                BlockState build = NbtUtils.readBlockState(lookup, entryTag.getCompound("build"));
                this.buildSnapshots.put(pos, new PreviewManager.BuildSnapshot(original, build));
            }
        }

        if (tag.contains("RequiredItems")) {
            this.requiredItems.clear();
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

        if (tag.contains("Inventory")) {
            ListTag inventoryList = tag.getList("Inventory", 10);
            for (int i = 0; i < inventoryList.size(); i++) {
                CompoundTag slotTag = inventoryList.getCompound(i);
                int slot = slotTag.getInt("Slot");
                ResourceLocation itemId = new ResourceLocation(slotTag.getString("Item"));
                Item item = ForgeRegistries.ITEMS.getValue(itemId);
                int count = slotTag.getInt("Count");
                if (item != null && item != Items.AIR && slot < itemHandler.getSlots()) {
                    itemHandler.setStackInSlot(slot, new ItemStack(item, count));
                }
            }
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