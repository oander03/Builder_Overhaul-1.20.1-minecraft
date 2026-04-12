package net.hydroset.buildpreviewer.networking;

import net.hydroset.buildpreviewer.PreviewManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class TogglePreviewPacket {

    // Empty constructor required for registration
    public TogglePreviewPacket() {}

    // Reading from the network (even if empty, required by Forge)
    public TogglePreviewPacket(FriendlyByteBuf buf) {}

    // Writing to the network
    public void toBytes(FriendlyByteBuf buf) {}

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            // HERE IS THE SERVER SIDE LOGIC
            ServerPlayer player = context.getSender();
            if (player != null) {
                if (PreviewManager.isInPreview(player.getUUID())) {
                    PreviewManager.exitPreview(player);
                } else {
                    PreviewManager.enterPreview(player);
                }
            }
        });
        return true;
    }
}