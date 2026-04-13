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

    private static final Map<UUID, GameType> previousGameModes = new HashMap<>();
    // Maps Player UUID to the specific BlockPos they started the preview from
    private static final Map<UUID, BlockPos> playerAnchorPos = new HashMap<>();
    private static final Map<UUID, ListTag> savedInventories = new HashMap<>();

    public static class BuildSnapshot {
        public final BlockState originalState;
        public final BlockState buildState;

        public BuildSnapshot(BlockState original, BlockState build) {
            this.originalState = original;
            this.buildState = build;
        }
    }



    public static final Map<UUID, Map<BlockPos, BuildSnapshot>> pendingCommit = new HashMap<>();
    // Stores: Player UUID -> (Map of Location -> Original BlockState)
    private static final Map<UUID, Map<BlockPos, BlockState>> sessionChanges = new HashMap<>();

    public static void recordChange(UUID playerId, BlockPos pos, BlockState originalState) {
        if (isInPreview(playerId)) {
            Map<BlockPos, BlockState> changes = sessionChanges.computeIfAbsent(playerId, k -> new HashMap<>());

            if (!changes.containsKey(pos)) {
                changes.put(pos, originalState);
            }
        } else {
            // IF NOT IN PREVIEW: If the player places a block manually,
            // we must remove it from the "Pending Commit" so the mod
            // stops trying to roll it back or track it as a preview.
            pendingCommit.forEach((uuid, map) -> {
                if (map.containsKey(pos)) {
                    map.remove(pos);
                }
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

    public static void enterPreview(ServerPlayer player, BlockPos pos) {
        UUID id = player.getUUID();
        Level level = player.level();

        // 1. Eject items as usual
        if (level.getBlockEntity(pos) instanceof PreviewBlockEntity be) {
            be.ejectItemsToPlayer(player);

            // --- NEW SYNC LOGIC ---
            // If the Block Entity has a saved build, "teach" the Manager about it again
            Map<BlockPos, BuildSnapshot> savedBuild = be.getBuildSnapshots();
            if (savedBuild != null && !savedBuild.isEmpty()) {
                // Repopulate the manager's static memory from the BE's NBT data
                pendingCommit.put(id, new HashMap<>(savedBuild));
            }
            // ----------------------
        }

        startInventoryPreview(player);
        previousGameModes.put(id, player.gameMode.getGameModeForPlayer());
        playerAnchorPos.put(id, pos);

        // This will now work because 'pendingCommit' was just filled above!
        restorePendingBuild(player);

        player.setGameMode(GameType.CREATIVE);
        player.displayClientMessage(Component.literal("§dResuming Preview Mode"), true);
    }

    private static void restorePendingBuild(ServerPlayer player) {
        UUID id = player.getUUID();
        BlockPos anchor = playerAnchorPos.get(id);

        if (anchor != null && player.level().getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            // Get the data directly from the BE's NBT-loaded map!
            Map<BlockPos, BuildSnapshot> buildData = be.getBuildSnapshots();

            if (!buildData.isEmpty()) {
                Level level = player.level();
                buildData.forEach((pos, snapshot) -> {
                    level.setBlock(pos, snapshot.buildState, 3 | 16);
                });
                player.displayClientMessage(Component.literal("§aRestored preview from saved data!"), true);
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

        // 1. MERGE SESSION INTO PENDING COMMIT
        Map<BlockPos, BlockState> currentSession = sessionChanges.get(id);
        Map<BlockPos, BuildSnapshot> complexSnapshot = pendingCommit.computeIfAbsent(id, k -> new HashMap<>());

        if (currentSession != null) {
            currentSession.forEach((pos, originalState) -> {
                if (!complexSnapshot.containsKey(pos)) {
                    complexSnapshot.put(pos, new BuildSnapshot(originalState, level.getBlockState(pos)));
                } else {
                    BuildSnapshot existing = complexSnapshot.get(pos);
                    complexSnapshot.put(pos, new BuildSnapshot(existing.originalState, level.getBlockState(pos)));
                }
            });
        }

        // 2. CALCULATE COST
        Map<Item, Integer> cost = calculateRequiredItems(player);

        // 3. SEND ALL DATA TO ANCHOR (Merged into one block for clarity)
        if (anchor != null && level.getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            // This is the line that was red—it now sees 'complexSnapshot' defined above
            be.setBuildData(complexSnapshot, cost, id);
            be.setRequiredItems(cost, id);
            // Force the sync to client
            level.sendBlockUpdated(anchor, level.getBlockState(anchor), level.getBlockState(anchor), 3);
        }


        // 4. HIDE THE BUILD (Rollback)
        if (complexSnapshot != null) {
            complexSnapshot.forEach((pos, snapshot) -> {
                level.setBlock(pos, snapshot.originalState, 3 | 16);
            });
        }



        // 5. RESTORE PLAYER STATE
        player.removeAllEffects();
        restoreInventory(player);
        player.setGameMode(previousGameModes.getOrDefault(id, GameType.SURVIVAL));

        // Inside exitPreview
        if (anchor != null) {
            BlockPos safePos = findSafeTeleportPos(level, anchor);
            // x + 0.5 and z + 0.5 centers you on the block
            // y gives you the floor of the air block
            player.teleportTo(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
        }

        // 7. CLEANUP ACTIVE SESSION DATA (But keep pendingCommit!)
        sessionChanges.remove(id);
        playerAnchorPos.remove(id);
        previousGameModes.remove(id);

        player.displayClientMessage(Component.literal("§ePreview hidden. Visit anchor to finalize build!"), true);
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