package net.hydroset.buildpreviewer;

import net.hydroset.buildpreviewer.block.PreviewBlock;
import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.*;

import static net.hydroset.buildpreviewer.screen.PreviewHudOverlay.resetCache;


public class PreviewManager {

    private static final Map<UUID, ListTag> savedInventories = new HashMap<>();

    private static final Map<UUID, GameType> previousGameModes = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> playerAnchorPos = new java.util.concurrent.ConcurrentHashMap<>();
    public static final Map<UUID, Map<BlockPos, BuildSnapshot>> pendingCommit = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPos, BlockState>> sessionChanges = new java.util.concurrent.ConcurrentHashMap<>();


    public static class BuildSnapshot {
        public BlockState originalState;
        public final BlockState buildState;

        public BuildSnapshot(BlockState original, BlockState build) {
            this.originalState = original;
            this.buildState = build;
        }

        public void updateOriginalState(BlockState newState) {
            this.originalState = newState;
        }
    }



    public static void recordChange(UUID playerId, BlockPos pos, BlockState currentStateAtPos) {
        if (isInPreview(playerId)) {
            Map<BlockPos, BlockState> changes = sessionChanges.computeIfAbsent(playerId, k -> new HashMap<>());
            Map<BlockPos, BuildSnapshot> totalBuild = pendingCommit.get(playerId);

            if (!changes.containsKey(pos)) {
                if (totalBuild != null && totalBuild.containsKey(pos)) {
                    changes.put(pos, totalBuild.get(pos).originalState);
                } else {
                    changes.put(pos, currentStateAtPos);
                }
            }
        } else {
            pendingCommit.forEach((uuid, map) -> {
                // ✅ Only update snapshots for ACTIVE preview sessions
                if (!isInPreview(uuid)) return;

                if (!map.containsKey(pos)) return;

                BuildSnapshot snapshot = map.get(pos);

                // ✅ If the world change matches what preview placed, this is a natural
                // side-effect of preview building (grass->dirt, etc). Ignore it.
                if (currentStateAtPos.equals(snapshot.buildState)) return;

                // ✅ If the world change matches originalState, nothing really changed. Ignore.
                if (currentStateAtPos.equals(snapshot.originalState)) return;

                if (currentStateAtPos.isAir() && snapshot.buildState.isAir()) {
                    map.remove(pos);
                } else {
                    snapshot.updateOriginalState(currentStateAtPos);
                }
            });
        }
    }

    // Update your calculator to handle the "Future" Air block
    public static Map<Item, Integer> calculateRequiredItems(ServerPlayer player, @Nullable BlockPos pendingAir) {
        UUID id = player.getUUID();
        Map<BlockPos, BuildSnapshot> totalBuild = pendingCommit.get(id);
        Map<Item, Integer> requirements = new HashMap<>();

        if (totalBuild == null) return requirements;

        totalBuild.forEach((pos, snapshot) -> {
            // LATEST FIX: If this is the block we are currently breaking, treat it as AIR
            BlockState worldState = pos.equals(pendingAir) ? Blocks.AIR.defaultBlockState() : player.level().getBlockState(pos);
            BlockState originalState = snapshot.originalState;

            if (!worldState.equals(originalState) && !worldState.isAir()) {
                Item item = worldState.getBlock().asItem();
                if (item != Items.AIR) {
                    requirements.put(item, requirements.getOrDefault(item, 0) + 1);
                }
            }
        });
        return requirements;
    }

    public static void startInventoryPreview(ServerPlayer player) {
        // 1. Save logic (your existing code)
        ListTag inventoryTag = new ListTag();
        player.getInventory().save(inventoryTag);
        savedInventories.put(player.getUUID(), inventoryTag);

        // 2. Clear Main Inventory & Offhand
        player.getInventory().items.clear(); // This is cleaner than a loop
        player.getInventory().offhand.clear();

        // 2. Force a manual sync packet
        // We send the 'Inventory Menu' (the player's main inventory) to the client
        player.connection.send(new ClientboundContainerSetContentPacket(
                player.inventoryMenu.containerId,
                player.inventoryMenu.incrementStateId(),
                player.inventoryMenu.getItems(),
                player.inventoryMenu.getCarried()
        ));
    }

    public static Map<Item, Integer> calculateRequiredItemsFromMap(Map<BlockPos, BuildSnapshot> buildData) {        Map<Item, Integer> requirements = new HashMap<>();
        buildData.forEach((pos, snapshot) -> {
            if (!snapshot.buildState.equals(snapshot.originalState) && !snapshot.buildState.isAir()) {
                Item item = snapshot.buildState.getBlock().asItem();
                if (item != Items.AIR) {
                    requirements.put(item, requirements.getOrDefault(item, 0) + 1);
                }
            }
        });
        return requirements;
    }

    public static void enterPreview(ServerPlayer player, BlockPos pos) {
        UUID id = player.getUUID();
        Level level = player.level();


        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(PreviewBlock.ACTIVE)) {
            level.setBlock(pos, state.setValue(PreviewBlock.ACTIVE, true), 18);
        }

        if (level.getBlockEntity(pos) instanceof PreviewBlockEntity be) {

            // 1. Get the existing snapshots from the Block Entity
            Map<BlockPos, BuildSnapshot> savedBuild = be.getBuildSnapshots();
            Map<BlockPos, BuildSnapshot> validatedBuild = new HashMap<>();

            if (savedBuild != null) {
                savedBuild.forEach((blockPos, snapshot) -> {
                    BlockState worldState = level.getBlockState(blockPos);

                    // LOGIC: Check if this was a "Red Block" (intent to break)
                    // If the player wanted it to be Air, but it's ALREADY Air in the world,
                    // we skip adding it to the validatedBuild (effectively deleting it from memory).
                    if (snapshot.buildState.isAir() && worldState.isAir()) {
                        // Do nothing - this "red block" is already gone in survival.
                        return;
                    }

                    if (!worldState.equals(snapshot.buildState) && !worldState.equals(snapshot.originalState)) {
                        // Update the 'originalState' so rollbacks target the NEW survival block.
                        snapshot.updateOriginalState(worldState);
                    }

                    // Otherwise, keep it in the session
                    validatedBuild.put(blockPos, snapshot);
                });
            }

            // 2. Put the cleaned-up map into the manager's memory
            pendingCommit.put(id, validatedBuild);

            // 3. Optional: Sync the cleaned map back to the Block Entity immediately
            // so the "Shopping List" updates even before you exit.
            be.setBuildData(validatedBuild, calculateRequiredItemsFromMap(validatedBuild), id);
        }

        startInventoryPreview(player);
        previousGameModes.put(id, player.gameMode.getGameModeForPlayer());
        playerAnchorPos.put(id, pos);

        restorePendingBuild(player); // Now safe to call
        player.setGameMode(GameType.CREATIVE);
        player.displayClientMessage(Component.literal("§aEntering Build Mode"), true);
    }

    private static void restorePendingBuild(ServerPlayer player) {
        UUID id = player.getUUID();
        BlockPos anchor = playerAnchorPos.get(id);

        // Everything must stay inside this IF block so 'be' and 'level' are known
        if (anchor != null && player.level().getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            Map<BlockPos, BuildSnapshot> buildData = be.getBuildSnapshots();

            if (!buildData.isEmpty()) {
                Level level = player.level();

                buildData.forEach((pos, snapshot) -> {
                    // We use Flag 2 here to prevent neighbor updates (breaking torches/blocks)
                    level.setBlock(pos, snapshot.buildState, 2 | 16 | 32);
                });

                player.displayClientMessage(Component.literal("§aRestored preview from saved data!"), true);
            }
        }
        // Do not put buildData.forEach here! It is outside the brackets, so the variables are 'red'.
    }

    // Add to PreviewManager class fields
    private static final Map<UUID, Long> lastSyncTime = new HashMap<>();
    private static final long SYNC_INTERVAL_MS = 150;

    public static void recordAndSync(ServerPlayer player, BlockPos placedPos, BlockState stateBefore) {
        UUID id = player.getUUID();
        BlockPos anchor = playerAnchorPos.get(id);
        if (anchor == null) return;

        Map<BlockPos, BlockState> changes = sessionChanges.computeIfAbsent(id, k -> new HashMap<>());
        if (!changes.containsKey(placedPos)) {
            changes.put(placedPos, stateBefore);
        }

        if (player.level().getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            Map<BlockPos, BuildSnapshot> complexSnapshot = pendingCommit.computeIfAbsent(id, k -> new HashMap<>());

            changes.forEach((pos, original) -> {
                BlockState current = player.level().getBlockState(pos);
                complexSnapshot.put(pos, new BuildSnapshot(original, current));
            });

            Map<Item, Integer> liveCost = calculateRequiredItems(player, null);
            be.setRequiredItems(liveCost, id);

            // ✅ Throttled sync — updates client dynamically but not on every single block spam
            long now = System.currentTimeMillis();
            if (now - lastSyncTime.getOrDefault(id, 0L) >= SYNC_INTERVAL_MS) {
                lastSyncTime.put(id, now);
                be.updateBlock();
            }
        }
    }

    public static void restoreInventory(ServerPlayer player) {
        UUID id = player.getUUID();

        if (savedInventories.containsKey(id)) {
            // 1. Clear the "Creative" items they currently have in their hands/bag
            player.getInventory().items.clear();
            player.getInventory().offhand.clear();

            // 2. Load the old items back from the saved ListTag
            // This restores armor, off-hand, and main items automatically
            player.getInventory().load(savedInventories.get(id));

            // 3. Force a manual sync packet to the client
            // This is the "Magic Bullet" that makes items reappear instantly
            player.connection.send(new ClientboundContainerSetContentPacket(
                    player.inventoryMenu.containerId,
                    player.inventoryMenu.incrementStateId(),
                    player.inventoryMenu.getItems(),
                    player.inventoryMenu.getCarried()
            ));

            // 4. Cleanup the map so we don't leak memory
            savedInventories.remove(id);
        }
    }


    public static void exitPreview(ServerPlayer player) {
        UUID id = player.getUUID();
        Level level = player.level();
        BlockPos anchor = playerAnchorPos.get(id);
        lastSyncTime.remove(id);

        resetCache();

        Map<BlockPos, BlockState> currentSession = sessionChanges.get(id);
        Map<BlockPos, BuildSnapshot> complexSnapshot = pendingCommit.computeIfAbsent(id, k -> new HashMap<>());

        // changes state
        if (anchor != null) {
            BlockState state = level.getBlockState(anchor);
            if (state.hasProperty(PreviewBlock.ACTIVE)) {
                level.setBlock(anchor, state.setValue(PreviewBlock.ACTIVE, false), 18);
            }
        }

        // 1. Sync the current session into the complex snapshot
        if (currentSession != null) {
            currentSession.forEach((pos, originalState) -> {
                BlockState currentState = level.getBlockState(pos);
                if (!complexSnapshot.containsKey(pos)) {
                    complexSnapshot.put(pos, new BuildSnapshot(originalState, currentState));
                } else {
                    BuildSnapshot existing = complexSnapshot.get(pos);
                    // ✅ Keep the original buildState from when the block was placed,
                    // don't overwrite it with the current decayed world state
                    complexSnapshot.put(pos, new BuildSnapshot(existing.originalState, existing.buildState));
                }
            });
        }

        // 2. Save data to the Anchor
        Map<Item, Integer> cost = calculateRequiredItems(player, null);
        if (anchor != null && level.getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            be.setBuildData(complexSnapshot, cost, id);
            be.setChanged();
            level.sendBlockUpdated(anchor, level.getBlockState(anchor), level.getBlockState(anchor), 3);
        }

        complexSnapshot.forEach((pos, snapshot) -> {
            if (level.hasChunkAt(pos)) {
                BlockState worldNow = level.getBlockState(pos);

                // ✅ Always roll back if originalState is what we want to restore,
                // regardless of whether the world changed naturally (grass->dirt etc.)
                // Only skip rollback if something truly external happened that we don't own
                boolean previewStillOwnsBlock = worldNow.equals(snapshot.buildState)
                        || isNaturalDecayOf(worldNow, snapshot.buildState); // ✅ new helper

                if (previewStillOwnsBlock) {
                    level.setBlock(pos, snapshot.originalState, 2 | 16 | 128);
                } else {
                    snapshot.updateOriginalState(worldNow);
                }
            }
        });
        // 4. RESTORE PLAYER
        player.removeAllEffects();
        restoreInventory(player);
        player.setGameMode(previousGameModes.getOrDefault(id, GameType.SURVIVAL));

// Inside exitPreview
        if (anchor != null) {
            BlockPos safePos = findSafeTeleportPos(level, anchor);
            // Add 0.2 to Y to ensure the player "falls" into place rather than "spawning in" the floor
            player.teleportTo(safePos.getX() + 0.5, safePos.getY() + 0.2, safePos.getZ() + 0.5);
        }

        // 5. CLEANUP
        sessionChanges.remove(id);
        playerAnchorPos.remove(id);
        previousGameModes.remove(id);
        // Note: We keep pendingCommit so the 'shopping list' stays visible on the BE screen!
        player.displayClientMessage(Component.literal("§dExiting Build Mode"), true);

        playerAnchorPos.remove(id);

    }

    // Returns true if 'current' is a natural decay/change of 'placed'
// e.g. grass block naturally became dirt when something was placed on top
    private static boolean isNaturalDecayOf(BlockState current, BlockState placed) {
        // Grass -> Dirt
        if (placed.is(Blocks.GRASS_BLOCK) && current.is(Blocks.DIRT)) return true;
        // Mycelium -> Dirt
        if (placed.is(Blocks.MYCELIUM) && current.is(Blocks.DIRT)) return true;
        // Farmland -> Dirt (trampled)
        if (placed.is(Blocks.FARMLAND) && current.is(Blocks.DIRT)) return true;
        // Dirt Path -> Dirt (when covered)
        if (placed.is(Blocks.DIRT_PATH) && current.is(Blocks.DIRT)) return true;
        // Snow layer melting
        if (placed.is(Blocks.SNOW) && current.is(Blocks.AIR)) return true;
        return false;
    }

    public static boolean isInPreview(UUID id) {
        return playerAnchorPos.containsKey(id);
    }

    public static BlockPos findSafeTeleportPos(Level level, BlockPos startPos) {
        // 1. First, check the column DIRECTLY above the block (x=0, z=0)
        // This loop checks y=1 (right on top), then y=2, then y=3
        for (int y = 1; y <= 4; y++) {
            BlockPos centerCheck = startPos.above(y);
            // Use canPassThrough or check collision shape
            if (level.getBlockState(centerCheck).getCollisionShape(level, centerCheck).isEmpty() &&
                    level.getBlockState(centerCheck.above()).getCollisionShape(level, centerCheck.above()).isEmpty()) {
                return centerCheck;
            }
        }

        // 2. If the center column is blocked, search the surrounding 3x3 area
        for (int y = 1; y <= 3; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    // Skip the center because we already checked it above
                    if (x == 0 && z == 0) continue;

                    BlockPos offsetPos = startPos.offset(x, y, z);
                    if (level.getBlockState(offsetPos).isAir() &&
                            level.getBlockState(offsetPos.above()).isAir()) {
                        return offsetPos;
                    }
                }
            }
        }

        // 3. Absolute fallback: just one block up
        return startPos.above();
    }

    public static Collection<BlockPos> getAllAnchorPositions() {
        return playerAnchorPos.values();
    }

    public static java.util.Set<java.util.UUID> getAllActivePlayers() {
        return playerAnchorPos.keySet();
    }

    private static void sendRequirementsToAnchor(ServerPlayer player, Map<Item, Integer> cost) {
        BlockPos anchor = playerAnchorPos.get(player.getUUID());
        if (anchor != null) {
            // Get the BlockEntity at the anchor position
            net.minecraft.world.level.block.entity.BlockEntity be = player.level().getBlockEntity(anchor);

            // Check if it's actually our PreviewBlockEntity
            if (be instanceof net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity previewBE) {
                // We pass the "Shopping List" and the Player's UUID to the block entity
                previewBE.setRequiredItems(cost, player.getUUID());
            }
        }
    }

    public static BlockPos getAnchorPos(UUID id) {
        return playerAnchorPos.get(id);
    }
}