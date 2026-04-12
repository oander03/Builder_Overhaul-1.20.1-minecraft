package net.hydroset.buildpreviewer;

import net.minecraft.world.level.GameType;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PreviewManager {

    private static final Set<UUID> playersInPreview = new HashSet<>();
    private static final Map<UUID, GameType> previousGameModes = new HashMap<>();

    public static void enterPreview(ServerPlayer player) {
        UUID id = player.getUUID();
        previousGameModes.put(id, player.gameMode.getGameModeForPlayer());

        playersInPreview.add(id);


        player.setGameMode(GameType.CREATIVE);
    }

    public static void exitPreview(ServerPlayer player) {
        UUID id = player.getUUID();
        GameType previous = previousGameModes.getOrDefault(id, GameType.SURVIVAL);
        player.setGameMode(previous);
        playersInPreview.remove(id);
        previousGameModes.remove(id);

    }

    public static boolean isInPreview(UUID id) {
        return playersInPreview.contains(id);
    }
}