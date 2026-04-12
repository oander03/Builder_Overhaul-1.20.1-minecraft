package net.hydroset.buildpreviewer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PreviewManager {

    private static final Map<UUID, GameType> previousGameModes = new HashMap<>();
    // Maps Player UUID to the specific BlockPos they started the preview from
    private static final Map<UUID, BlockPos> playerAnchorPos = new HashMap<>();

    public static void enterPreview(ServerPlayer player, BlockPos pos) {
        UUID id = player.getUUID();
        previousGameModes.put(id, player.gameMode.getGameModeForPlayer());
        playerAnchorPos.put(id, pos);

        player.setGameMode(GameType.CREATIVE);
    }

    public static void exitPreview(ServerPlayer player) {
        UUID id = player.getUUID();
        GameType previous = previousGameModes.getOrDefault(id, GameType.SURVIVAL);
        player.setGameMode(previous);
        playerAnchorPos.remove(id);
        previousGameModes.remove(id);

    }

    public static boolean isInPreview(UUID id) {
        return playerAnchorPos.containsKey(id);
    }

    public static BlockPos getAnchorPos(UUID id) {
        return playerAnchorPos.get(id);
    }
}