package net.hydroset.buildpreviewer.block.entity;

import net.hydroset.buildpreviewer.PreviewManager;
import net.hydroset.buildpreviewer.block.PreviewBlock;
import net.hydroset.buildpreviewer.block.entity.ModBlockEntities;
import net.hydroset.buildpreviewer.screen.PreviewMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
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

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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

    // 1. Send the data to the client when they arrive
    @Override
    public CompoundTag getUpdateTag() {
        // This sends data when the chunk loads or the player joins
        return saveWithoutMetadata();
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        // This receives that data on the Client side
        load(tag);
    }

    // 2. Packet sync (Standard for 1.20.1)
    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        // This creates the actual packet that travels over the network
        return ClientboundBlockEntityDataPacket.create(this);
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

        // Check if there is a build record at all (even if it's just breaking blocks)
        boolean hasPendingBuild = PreviewManager.pendingCommit.containsKey(id);

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
                player.sendSystemMessage(Component.literal("§cMissing: " + (entry.getValue() - providedItems.getOrDefault(entry.getKey(), 0)) + "x " + entry.getKey().getDescriptionId()));
                break;
            }
        }

        // 4. If the check passed, consume items and build!
        if (hasEverything) {
// 1. Consume what is actually needed for the build
            consumeRequiredItems();

            // 2. Eject anything LEFT OVER back to the player
            ejectItemsToPlayer(player);

            // 3. Commit the actual blocks to the world
            PreviewBlock.commitBuild(player);

            // 4. Clear the manager's memory
            PreviewManager.pendingCommit.remove(id);

            // 5. Clear this block's memory and SYNC to client
            this.requiredItems.clear();
            this.setChanged();

            // This line forces the server to tell the client "Hey, my data changed!"
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
                if (stack.getItem() == entry.getKey()) {
                    int shrinkBy = Math.min(stack.getCount(), toTake);
                    stack.shrink(shrinkBy);
                    toTake -= shrinkBy;
                }
                if (toTake <= 0) break;
            }
        }
    }

    public void drops() {
        // Replace 'itemHandler' with the name of your ItemStackHandler variable
        SimpleContainer inventory = new SimpleContainer(itemHandler.getSlots());
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            inventory.setItem(i, itemHandler.getStackInSlot(i));
        }

        Containers.dropContents(this.level, this.worldPosition, inventory);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);

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

        if (ownerUUID != null) {
            tag.putUUID("Owner", ownerUUID);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        this.requiredItems.clear();
        if (tag.contains("RequiredItems")) {
            CompoundTag itemsTag = tag.getCompound("RequiredItems");
            int size = tag.getInt("RequiredItemsSize");
            for (int i = 0; i < size; i++) {
                CompoundTag entryTag = itemsTag.getCompound("item_" + i);
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entryTag.getString("item")));
                int count = entryTag.getInt("count");
                if (item != Items.AIR) {
                    this.requiredItems.put(item, count);
                }
            }
        }

        if (tag.hasUUID("Owner")) {
            this.ownerUUID = tag.getUUID("Owner");
        }
    }

    private final LazyOptional<IItemHandler> lazyItemHandler = LazyOptional.of(() -> itemHandler);

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return lazyItemHandler.cast();
        }
        return super.getCapability(cap, side);
    }

    // You'll also need to override saveAdditional and load for NBT saving
}