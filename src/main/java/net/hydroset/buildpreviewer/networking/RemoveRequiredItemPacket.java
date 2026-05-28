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

            // 1. Roll back affected blocks in the world and remove from pendingCommit
            Map<BlockPos, PreviewManager.BuildSnapshot> pendingMap = PreviewManager.pendingCommit.get(playerId);
            if (pendingMap != null) {
                List<BlockPos> toRemove = new ArrayList<>();
                pendingMap.forEach((pos, snapshot) -> {
                    if (snapshot.buildState.getBlock().asItem() == item) {
                        if (level.hasChunkAt(pos)) {
                            level.setBlock(pos, snapshot.originalState, 2 | 16 | 32);
                        }
                        toRemove.add(pos);
                    }
                });
                toRemove.forEach(pendingMap::remove);
            }

            // 2. Also remove from sessionChanges so it doesn't get re-added on next recordAndSync
            Map<BlockPos, net.minecraft.world.level.block.state.BlockState> sessionMap =
                    PreviewManager.getSessionChanges(playerId);
            if (sessionMap != null && pendingMap != null) {
                // Remove session entries that no longer exist in pendingCommit
                sessionMap.entrySet().removeIf(e -> !pendingMap.containsKey(e.getKey()));
            }

            // 3. Sync BE with the cleaned pendingCommit — same as recordAndSync does
            Map<BlockPos, PreviewManager.BuildSnapshot> snapshots = be.getBuildSnapshots();
            snapshots.entrySet().removeIf(e -> e.getValue().buildState.getBlock().asItem() == item);

            Map<Item, Integer> newCost = PreviewManager.calculateRequiredItemsFromMap(
                    pendingMap != null ? pendingMap : snapshots
            );
            be.setBuildData(
                    pendingMap != null ? pendingMap : snapshots,
                    newCost,
                    playerId
            );

            PreviewManager.sendHologramUpdate(player);
            be.updateBlock();
        });
        ctx.get().setPacketHandled(true);
    }
}