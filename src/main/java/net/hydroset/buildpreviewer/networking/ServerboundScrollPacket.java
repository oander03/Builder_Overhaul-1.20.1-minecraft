package net.hydroset.buildpreviewer.networking;

import net.hydroset.buildpreviewer.screen.PreviewMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ServerboundScrollPacket {
    private final int scrollOffset;

    public ServerboundScrollPacket(int scrollOffset) {
        this.scrollOffset = scrollOffset;
    }

    public ServerboundScrollPacket(FriendlyByteBuf buf) {
        this.scrollOffset = buf.readInt();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(scrollOffset);
    }
    
    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && player.containerMenu instanceof PreviewMenu menu) {
                // Update the server's version of the menu
                menu.scrollTo(this.scrollOffset);
            }
        });
        return true;
    }
}