package net.hydroset.buildpreviewer.networking;

import net.hydroset.buildpreviewer.PreviewManager;
import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.function.Supplier;

public class RequestBuildSyncPacket {

    public RequestBuildSyncPacket() {}

    public RequestBuildSyncPacket(FriendlyByteBuf buf) {
        // no payload needed — server identifies the player/anchor itself
    }

    public void toBytes(FriendlyByteBuf buf) {
        // nothing to write
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;

            BlockPos anchor = PreviewManager.getAnchorPos(player.getUUID());
            if (anchor == null) return;

            if (player.level().getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
                Map<BlockPos, PreviewManager.BuildSnapshot> live =
                        PreviewManager.pendingCommit.get(player.getUUID());
                if (live != null) {
                    be.setBuildData(live, PreviewManager.calculateRequiredItemsFromMap(live), player.getUUID());
                    be.updateBlock();
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}