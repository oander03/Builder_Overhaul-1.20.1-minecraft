package net.hydroset.buildpreviewer.networking;

import net.hydroset.buildpreviewer.PreviewManager;
import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class ClearAllBlocksPacket {
    private final BlockPos anchorPos;

    public ClearAllBlocksPacket(BlockPos anchorPos) {
        this.anchorPos = anchorPos;
    }

    public ClearAllBlocksPacket(FriendlyByteBuf buf) {
        this.anchorPos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(anchorPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            Level level = player.level();
            if (!(level.getBlockEntity(anchorPos) instanceof PreviewBlockEntity be)) return;

            UUID playerId = player.getUUID();

            // 1. Roll back every block in pendingCommit and clear it
            Map<BlockPos, PreviewManager.BuildSnapshot> pendingMap =
                    PreviewManager.pendingCommit.get(playerId);
            if (pendingMap != null) {
                pendingMap.forEach((pos, snapshot) -> {
                    if (level.hasChunkAt(pos)) {
                        level.setBlock(pos, snapshot.originalState, 2 | 16 | 32);
                    }
                });
                pendingMap.clear();
            }

            // 2. Clear session changes so nothing gets re-added on next recordAndSync
            Map<BlockPos, net.minecraft.world.level.block.state.BlockState> sessionMap =
                    PreviewManager.getSessionChanges(playerId);
            if (sessionMap != null) {
                sessionMap.clear();
            }

            // 3. Clear the BE data
            be.setBuildData(
                    new java.util.HashMap<>(),
                    new java.util.HashMap<>(),
                    playerId
            );
            be.updateBlock();

            // 4. Clear holograms on the client
            PreviewManager.sendHologramUpdate(player);
        });
        ctx.get().setPacketHandled(true);
    }
}