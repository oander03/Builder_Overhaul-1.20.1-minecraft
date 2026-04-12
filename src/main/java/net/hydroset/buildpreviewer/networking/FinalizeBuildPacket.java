package net.hydroset.buildpreviewer.networking;

import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class FinalizeBuildPacket {
    private final BlockPos pos;

    public FinalizeBuildPacket(BlockPos pos) {
        this.pos = pos;
    }

    public FinalizeBuildPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
    }

    

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
    }

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && player.level().getBlockEntity(pos) instanceof PreviewBlockEntity be) {
                // This triggers the item check logic we wrote earlier
                be.checkRequirementsAndCommit(player);
            }
        });
        return true;
    }
}