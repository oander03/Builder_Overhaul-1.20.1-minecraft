package net.hydroset.buildpreviewer.networking;

import net.hydroset.buildpreviewer.BuildPreviewer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModMessages {
    private static SimpleChannel INSTANCE;

    // Every packet needs a unique ID
    private static int packetId = 0;
    private static int id() {
        return packetId++;
    }

    // Inside your ModMessages.java or PacketHandler.java
    public static void register() {
        SimpleChannel net = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(BuildPreviewer.MOD_ID, "messages"))
                .networkProtocolVersion(() -> "1.0")
                .clientAcceptedVersions(s -> true)
                .serverAcceptedVersions(s -> true)
                .simpleChannel();

        INSTANCE = net;

        // Register Toggle (ID 0)
        net.messageBuilder(TogglePreviewPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(TogglePreviewPacket::new)
                .encoder(TogglePreviewPacket::toBytes)
                .consumerMainThread(TogglePreviewPacket::handle)
                .add();

        // REGISTER FINALIZE (ID 1) - THIS IS THE MISSING STEP
        net.messageBuilder(FinalizeBuildPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(FinalizeBuildPacket::new)
                .encoder(FinalizeBuildPacket::toBytes)
                .consumerMainThread(FinalizeBuildPacket::handle)
                .add();

        net.messageBuilder(ServerboundScrollPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(ServerboundScrollPacket::new)
                .encoder(ServerboundScrollPacket::toBytes)
                .consumerMainThread(ServerboundScrollPacket::handle)
                .add();

        net.messageBuilder(RemoveRequiredItemPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(RemoveRequiredItemPacket::new)
                .encoder(RemoveRequiredItemPacket::encode)
                .consumerMainThread(RemoveRequiredItemPacket::handle)
                .add();

        net.messageBuilder(ClearAllBlocksPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(ClearAllBlocksPacket::new)
                .encoder(ClearAllBlocksPacket::encode)
                .consumerMainThread(ClearAllBlocksPacket::handle)
                .add();

        net.messageBuilder(RequestBuildSyncPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(RequestBuildSyncPacket::new)
                .encoder(RequestBuildSyncPacket::toBytes)
                .consumerMainThread(RequestBuildSyncPacket::handle)
                .add();

    }

    public static <MSG> void sendToServer(MSG message) {
        INSTANCE.sendToServer(message);
    }

    public static <MSG> void sendToPlayer(MSG message, ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}