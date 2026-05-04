package net.hydroset.buildpreviewer.networking;

import net.hydroset.buildpreviewer.PreviewManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class TogglePreviewPacket {
    private final BlockPos pos;

    // Constructor for the Client to create the packet
    public TogglePreviewPacket(BlockPos pos) {
        this.pos = pos;
    }

    // Constructor for the Server to reconstruct the packet from the buffer
    public TogglePreviewPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
    }

    // Writing the position to the buffer
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
    }

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                if (PreviewManager.isInPreview(player.getUUID())) {
                    PreviewManager.exitPreview(player);
                } else {
                    // Pass the position from the packet to the manager
                    PreviewManager.enterPreview(player, this.pos);
                }
            }
        });
        return true;
    }


}