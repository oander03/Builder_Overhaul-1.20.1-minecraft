package net.hydroset.buildpreviewer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PreviewSavedData extends SavedData {

    private static final String DATA_NAME = "buildpreviewer_sessions";
    private final Map<UUID, BlockPos> anchorMap = new HashMap<>();

    public static PreviewSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                PreviewSavedData::load,
                PreviewSavedData::new,
                DATA_NAME
        );
    }

    public void saveSession(UUID playerId, BlockPos anchor) {
        anchorMap.put(playerId, anchor);
        setDirty();
    }

    public BlockPos getSession(UUID playerId) {
        return anchorMap.get(playerId);
    }

    public void removeSession(UUID playerId) {
        anchorMap.remove(playerId);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag sessions = new CompoundTag();
        anchorMap.forEach((uuid, pos) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", pos.asLong());
            sessions.put(uuid.toString(), entry);
        });
        tag.put("Sessions", sessions);
        return tag;
    }

    public static PreviewSavedData load(CompoundTag tag) {
        PreviewSavedData data = new PreviewSavedData();
        CompoundTag sessions = tag.getCompound("Sessions");
        for (String key : sessions.getAllKeys()) {
            UUID uuid = UUID.fromString(key);
            BlockPos pos = BlockPos.of(sessions.getCompound(key).getLong("pos"));
            data.anchorMap.put(uuid, pos);
        }
        return data;
    }
}