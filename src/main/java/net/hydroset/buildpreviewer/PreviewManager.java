package net.hydroset.buildpreviewer;

import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;


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
            // If anyone breaks a block in the world in Survival,
            // no preview should ever try to roll it back to a previous state.
            pendingCommit.forEach((uuid, map) -> {
                map.remove(pos);
            });
        }
    }

    public static void restoreBuildFromSave(UUID playerUUID, BlockPos entityPos) {
        // Only restore if the manager doesn't already have them in memory
        if (!pendingCommit.containsKey(playerUUID)) {

            // 1. You need to get the list of blocks that were being previewed.
            // If you saved the blueprint/schematic to the BlockEntity,
            // you'd retrieve it here. For now, we'll tell the manager
            // this player is "In Preview" again.

            // Example: If your Toggle logic normally creates the list,
            // you might call a shared method here:
            // List<BlockPos> blueprint = calculateBlueprint(entityPos);
            // pendingCommit.put(playerUUID, blueprint);

            System.out.println("Restored preview session for: " + playerUUID);
        }
    }

    public static Map<Item, Integer> calculateRequiredItems(ServerPlayer player) {
        UUID id = player.getUUID();
        // LOOK HERE: Get the pendingCommit (total build) instead of sessionChanges
        Map<BlockPos, BuildSnapshot> totalBuild = pendingCommit.get(id);
        Map<Item, Integer> requirements = new HashMap<>();

        if (totalBuild == null) return requirements;

        totalBuild.forEach((pos, snapshot) -> {
            // We compare the 'buildState' (what the player wants)
            // to the 'originalState' (what was there before the mod touched it)
            BlockState plannedState = snapshot.buildState;
            BlockState originalState = snapshot.originalState;

            // If the planned block is different from what was originally there
            if (!plannedState.equals(originalState)) {
                // If the player planned a block (not air)
                if (!plannedState.isAir()) {
                    Item item = plannedState.getBlock().asItem();
                    if (item != Items.AIR) {
                        requirements.put(item, requirements.getOrDefault(item, 0) + 1);
                    }
                }
            }
        });
        return requirements;
    }

    public static void rollbackWorld(ServerPlayer player) {
        UUID id = player.getUUID();
        // We get the original session changes before they are cleared
        Map<BlockPos, BlockState> changes = sessionChanges.get(id);

        if (changes != null) {
            Level level = player.level();
            changes.forEach((pos, originalState) -> {
                level.setBlock(pos, originalState, 3 | 16);
            });
        }
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

    private static Map<Item, Integer> calculateRequiredItemsFromMap(Map<BlockPos, BuildSnapshot> buildData) {
        Map<Item, Integer> requirements = new HashMap<>();
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

        if (level.getBlockEntity(pos) instanceof PreviewBlockEntity be) {
            be.ejectItemsToPlayer(player);

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
        player.displayClientMessage(Component.literal("§dEntering Build Mode"), true);
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

        Map<BlockPos, BlockState> currentSession = sessionChanges.get(id);
        Map<BlockPos, BuildSnapshot> complexSnapshot = pendingCommit.computeIfAbsent(id, k -> new HashMap<>());

        // 1. Sync the current session into the complex snapshot
        if (currentSession != null) {
            currentSession.forEach((pos, originalState) -> {
                BlockState currentState = level.getBlockState(pos);
                if (!complexSnapshot.containsKey(pos)) {
                    complexSnapshot.put(pos, new BuildSnapshot(originalState, currentState));
                } else {
                    BuildSnapshot existing = complexSnapshot.get(pos);
                    complexSnapshot.put(pos, new BuildSnapshot(existing.originalState, currentState));
                }
            });
        }

        // 2. Save data to the Anchor
        Map<Item, Integer> cost = calculateRequiredItems(player);
        if (anchor != null && level.getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            be.setBuildData(complexSnapshot, cost, id);
            be.setChanged();
            level.sendBlockUpdated(anchor, level.getBlockState(anchor), level.getBlockState(anchor), 3);
        }

        complexSnapshot.forEach((pos, snapshot) -> {
            if (level.hasChunkAt(pos)) {
                BlockState worldNow = level.getBlockState(pos);

                // 1. Check if the block in the world has changed since we ENTERED preview
                // (i.e., someone placed a block in Survival/World while we were previewing)
                boolean worldChangedExternally = !worldNow.equals(snapshot.buildState);

                // 2. We only want to rollback if the block in the world is still our "Preview" block.
                // If worldNow != snapshot.buildState, it means something else happened there,
                // and we should update our 'originalState' so we don't overwrite the new block later.
                if (worldChangedExternally) {
                    // Update the snapshot so the "Memory" knows the world has a new reality
                    snapshot.updateOriginalState(worldNow);
                } else {
                    // If the world still shows our preview block, roll it back to the original
                    level.setBlock(pos, snapshot.originalState, 2 | 16);
                }
            }
        });
        // 4. RESTORE PLAYER
        player.removeAllEffects();
        restoreInventory(player);
        player.setGameMode(previousGameModes.getOrDefault(id, GameType.SURVIVAL));

        if (anchor != null) {
            BlockPos safePos = findSafeTeleportPos(level, anchor);
            player.teleportTo(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
        }

        // 5. CLEANUP
        sessionChanges.remove(id);
        playerAnchorPos.remove(id);
        previousGameModes.remove(id);
        // Note: We keep pendingCommit so the 'shopping list' stays visible on the BE screen!
        player.displayClientMessage(Component.literal("§aExiting Build Mode"), true);

    }

    public static boolean isInPreview(UUID id) {
        return playerAnchorPos.containsKey(id);
    }

    public static BlockPos findSafeTeleportPos(Level level, BlockPos startPos) {
        // 1. First, check the column DIRECTLY above the block (x=0, z=0)
        // This loop checks y=1 (right on top), then y=2, then y=3
        for (int y = 1; y <= 4; y++) {
            BlockPos centerCheck = startPos.above(y);
            if (level.getBlockState(centerCheck).isAir() &&
                    level.getBlockState(centerCheck.above()).isAir()) {
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

    public static void updateAnchorCost(ServerPlayer player, Map<Item, Integer> cost) {
        BlockPos anchor = playerAnchorPos.get(player.getUUID());
        if (anchor == null) {
            // If they aren't in preview, we check the last known anchor if you store it,
            // or iterate to find the PreviewBlockEntity they are using.
        }

        if (anchor != null && player.level().getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            be.setRequiredItems(cost, player.getUUID());
        }
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