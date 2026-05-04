package net.hydroset.buildpreviewer.networking;

import net.hydroset.buildpreviewer.PreviewManager;
import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class RemoveRequiredItemPacket {
    private final BlockPos anchorPos;
    private final ResourceLocation itemId;

    public RemoveRequiredItemPacket(BlockPos anchorPos, Item item) {
        this.anchorPos = anchorPos;
        this.itemId = ForgeRegistries.ITEMS.getKey(item);
    }

    public RemoveRequiredItemPacket(FriendlyByteBuf buf) {
        this.anchorPos = buf.readBlockPos();
        this.itemId = buf.readResourceLocation();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
        buf.writeResourceLocation(itemId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            Level level = player.level();

            if (!(level.getBlockEntity(anchorPos) instanceof PreviewBlockEntity be)) return;

            Item item = ForgeRegistries.ITEMS.getValue(itemId);
            if (item == null) return;

            UUID playerId = player.getUUID();

            // 1. Find all snapshots in pendingCommit where the buildState matches this item
            Map<BlockPos, PreviewManager.BuildSnapshot> pendingMap = PreviewManager.pendingCommit.get(playerId);
            if (pendingMap != null) {
                List<BlockPos> toRemove = new ArrayList<>();

                pendingMap.forEach((pos, snapshot) -> {
                    Item snapshotItem = snapshot.buildState.getBlock().asItem();
                    if (snapshotItem == item) {
                        // Roll back this block to its original state in the world
                        if (level.hasChunkAt(pos)) {
                            level.setBlock(pos, snapshot.originalState, 2 | 16 | 32);
                        }
                        toRemove.add(pos);
                    }
                });

                // 2. Remove them from pendingCommit
                toRemove.forEach(pendingMap::remove);
            }

            // 3. Do the same in the BlockEntity's buildSnapshots
            Map<BlockPos, PreviewManager.BuildSnapshot> snapshots = be.getBuildSnapshots();
            snapshots.entrySet().removeIf(entry ->
                    entry.getValue().buildState.getBlock().asItem() == item
            );

            // 4. Recalculate cost from what remains and sync
            Map<Item, Integer> newCost = PreviewManager.calculateRequiredItemsFromMap(snapshots);
            be.setRequiredItems(newCost, playerId);
            be.updateBlock();
        });
        ctx.get().setPacketHandled(true);
    }
}