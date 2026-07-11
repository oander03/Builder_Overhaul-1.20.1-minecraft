package net.hydroset.buildpreviewer;

import net.hydroset.buildpreviewer.block.PreviewBlock;
import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.hydroset.buildpreviewer.hologram.HologramSyncPacket;
import net.hydroset.buildpreviewer.hologram.ModNetwork;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.*;

public class PreviewManager {

    private static final Map<UUID, ListTag> savedInventories = new HashMap<>();

    private static final Map<UUID, GameType> previousGameModes = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> playerAnchorPos = new java.util.concurrent.ConcurrentHashMap<>();
    public static final Map<UUID, Map<BlockPos, BuildSnapshot>> pendingCommit = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPos, BlockState>> sessionChanges = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Map<UUID, Set<BlockPos>> secondaryBlocks = new java.util.concurrent.ConcurrentHashMap<>();

    public static void markAsSecondaryBlock(UUID playerId, BlockPos pos) {
        secondaryBlocks.computeIfAbsent(playerId, k -> new HashSet<>()).add(pos);
    }

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
                if (!map.containsKey(pos)) return;

                BuildSnapshot snapshot = map.get(pos);

                // Only guard against "same as build" if this session is LIVE —
                // meaning the preview block is physically in the world right now.
                // For inactive sessions the world is already rolled back, so
                // matching buildState is pure coincidence, not a side-effect.
                if (isInPreview(uuid)) {
                    if (currentStateAtPos.equals(snapshot.buildState)) return;
                    if (currentStateAtPos.equals(snapshot.originalState)) return;
                }

                // Always update for inactive sessions regardless of what the block is
                if (currentStateAtPos.isAir() && snapshot.buildState.isAir()) {
                    map.remove(pos);
                } else {
                    snapshot.updateOriginalState(currentStateAtPos);
                }
            });
        }
    }

    public static Map<Item, Integer> calculateRequiredItems(ServerPlayer player, @Nullable BlockPos pendingAir) {
        UUID id = player.getUUID();
        Map<BlockPos, BuildSnapshot> totalBuild = pendingCommit.get(id);
        Map<Item, Integer> requirements = new HashMap<>();

        if (totalBuild == null) return requirements;

        totalBuild.forEach((pos, snapshot) -> {
            BlockState worldState = pos.equals(pendingAir)
                    ? Blocks.AIR.defaultBlockState()
                    : player.level().getBlockState(pos);
            BlockState originalState = snapshot.originalState;

            // Skip if world hasn't changed from original
            if (worldState.equals(originalState)) return;
            if (worldState.isAir()) return;
            if (isSecondaryHalf(worldState)) return;

            Item item = worldState.getBlock().asItem();
            if (item == Items.AIR) return;

            // Count how many of this item are represented at this position
            int buildCount = getStackCount(worldState);
            int originalCount = (originalState.getBlock() == worldState.getBlock())
                    ? getStackCount(originalState)
                    : 0;
            int delta = buildCount - originalCount;

            if (delta > 0) {
                requirements.merge(item, delta, Integer::sum);
            }
        });

        return requirements;
    }



    public static void startInventoryPreview(ServerPlayer player) {
        UUID id = player.getUUID();
        ListTag inventoryTag = new ListTag();
        player.getInventory().save(inventoryTag);
        savedInventories.put(id, inventoryTag);

        BlockPos anchor = playerAnchorPos.get(id);
        if (anchor != null && player.level().getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            be.savePlayerInventory(inventoryTag);
            be.saveGameMode(previousGameModes.getOrDefault(id, GameType.SURVIVAL)); // ✅ persist it
        }

        player.getInventory().items.clear();
        player.getInventory().offhand.clear();
        player.connection.send(new ClientboundContainerSetContentPacket(
                player.inventoryMenu.containerId,
                player.inventoryMenu.incrementStateId(),
                player.inventoryMenu.getItems(),
                player.inventoryMenu.getCarried()
        ));
    }

    public static void restoreInventory(ServerPlayer player) {
        UUID id = player.getUUID();

        // Try in-memory first (normal exit), fall back to BE (post-restart exit)
        ListTag inventoryTag = savedInventories.remove(id);

        if (inventoryTag == null) {
            // ✅ NEW: Grab it from the block entity instead
            BlockPos anchor = playerAnchorPos.get(id);
            if (anchor != null && player.level().getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
                inventoryTag = be.getAndClearSavedInventory();
            }
        }

        if (inventoryTag == null) return; // Nothing to restore

        player.getInventory().items.clear();
        player.getInventory().offhand.clear();
        player.getInventory().load(inventoryTag);

        player.connection.send(new ClientboundContainerSetContentPacket(
                player.inventoryMenu.containerId,
                player.inventoryMenu.incrementStateId(),
                player.inventoryMenu.getItems(),
                player.inventoryMenu.getCarried()
        ));
    }

    public static Map<Item, Integer> calculateRequiredItemsFromMap(Map<BlockPos, BuildSnapshot> buildData) {
        Map<Item, Integer> requirements = new HashMap<>();

        buildData.forEach((pos, snapshot) -> {
            if (snapshot.buildState.equals(snapshot.originalState)) return;
            if (snapshot.buildState.isAir()) return;
            if (isSecondaryHalf(snapshot.buildState)) return;

            Item item = snapshot.buildState.getBlock().asItem();
            if (item == Items.AIR) return;

            int buildCount = getStackCount(snapshot.buildState);
            int originalCount = (snapshot.originalState.getBlock() == snapshot.buildState.getBlock())
                    ? getStackCount(snapshot.originalState)
                    : 0;
            int delta = buildCount - originalCount;

            if (delta > 0) {
                requirements.merge(item, delta, Integer::sum);
            }
        });

        return requirements;
    }

    public static void recordSessionChange(UUID playerId, BlockPos pos, BlockState originalState) {
        Map<BlockPos, BlockState> changes = sessionChanges.computeIfAbsent(playerId, k -> new HashMap<>());
        // Only record if we haven't seen this pos yet — preserve the true original
        if (!changes.containsKey(pos)) {
            changes.put(pos, originalState);
        }
    }

    // Add this field
    private static final Map<UUID, Map<BlockPos, BlockState>> pendingPartnerStates = new java.util.concurrent.ConcurrentHashMap<>();

    public static void capturePendingPartner(UUID playerId, BlockPos partnerPos, BlockState partnerState) {
        pendingPartnerStates.computeIfAbsent(playerId, k -> new HashMap<>()).put(partnerPos, partnerState);
    }

    public static BlockState consumePendingPartner(UUID playerId, BlockPos pos) {
        Map<BlockPos, BlockState> map = pendingPartnerStates.get(playerId);
        if (map == null) return null;
        return map.remove(pos);
    }



    public static boolean isSecondaryHalf(BlockState state) {
        // Beds: skip the HEAD part (foot is placed first, head is secondary)
        if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            return state.getValue(net.minecraft.world.level.block.BedBlock.PART)
                    == net.minecraft.world.level.block.state.properties.BedPart.HEAD;
        }
        // Doors: skip the UPPER half
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            return state.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER;
        }
        // Tall plants (sunflower, lilac, rose bush, peony, tall grass, large fern)
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER;
        }
        return false;
    }

    public static void enterPreview(ServerPlayer player, BlockPos pos) {
        UUID id = player.getUUID();
        Level level = player.level();

        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(PreviewBlock.ACTIVE)) {
            level.setBlock(pos, state.setValue(PreviewBlock.ACTIVE, true), 18);
        }

        if (level.getBlockEntity(pos) instanceof PreviewBlockEntity be) {
            be.dumpItemsToPlayer(player);
        }

        if (level.getBlockEntity(pos) instanceof PreviewBlockEntity be) {
            Map<BlockPos, BuildSnapshot> savedBuild = be.getBuildSnapshots();
            Map<BlockPos, BuildSnapshot> validatedBuild = new HashMap<>();

            if (savedBuild != null) {
                savedBuild.forEach((blockPos, snapshot) -> {
                    BlockState worldState = level.getBlockState(blockPos);
                    if (snapshot.buildState.isAir() && worldState.isAir()) return;
                    if (!worldState.equals(snapshot.buildState) && !worldState.equals(snapshot.originalState)) {
                        snapshot.updateOriginalState(worldState);
                    }
                    validatedBuild.put(blockPos, snapshot);
                });
            }

            pendingCommit.put(id, validatedBuild);
            be.setBuildData(validatedBuild, calculateRequiredItemsFromMap(validatedBuild), id);
        }

        // ✅ Set anchor BEFORE startInventoryPreview so the BE lookup inside it succeeds
        playerAnchorPos.put(id, pos);
        previousGameModes.put(id, player.gameMode.getGameModeForPlayer());

        // At the end of enterPreview, after playerAnchorPos.put(id, pos):
        PreviewSavedData.get((net.minecraft.server.level.ServerLevel) level).saveSession(id, pos);


        startInventoryPreview(player); // now playerAnchorPos.get(id) returns pos correctly

        sendHologramUpdate(player);

        restorePendingBuild(player);
        player.setGameMode(GameType.CREATIVE);
        player.displayClientMessage(Component.literal("§aEntering Build Mode"), true);
    }

    public static void rehydrateSession(UUID playerId, BlockPos anchor) {
        playerAnchorPos.put(playerId, anchor);
        // Don't touch previousGameModes — exitPreview will default to SURVIVAL if missing
    }

    public static void flushSessionChanges(ServerPlayer player) {
        UUID id = player.getUUID();
        Map<BlockPos, BlockState> currentSession = sessionChanges.get(id);
        if (currentSession == null) return;

        Map<BlockPos, BuildSnapshot> complexSnapshot = pendingCommit.computeIfAbsent(id, k -> new HashMap<>());
        Level level = player.level();

        currentSession.forEach((pos, originalState) -> {
            BlockState currentState = level.getBlockState(pos);
            if (!complexSnapshot.containsKey(pos)) {
                complexSnapshot.put(pos, new BuildSnapshot(originalState, currentState));
            } else {
                complexSnapshot.computeIfPresent(pos, (k, existing) -> new BuildSnapshot(existing.originalState, existing.buildState));
            }
        });

        // Persist flushed data to the block entity
        BlockPos anchor = playerAnchorPos.get(id);
        if (anchor != null && player.level().getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            be.setBuildData(complexSnapshot, calculateRequiredItemsFromMap(complexSnapshot), id);
            be.setChanged();
        }
    }

    public static void sendHologramUpdate(ServerPlayer player) {
        UUID id = player.getUUID();
        Map<BlockPos, BuildSnapshot> snapshot = pendingCommit.get(id);

        Map<BlockPos, HologramSyncPacket.HologramEntry> entries = new java.util.HashMap<>();

        if (snapshot != null) {
            snapshot.forEach((pos, snap) -> {
                boolean buildIsAir = snap.buildState.isAir();
                boolean originalIsAir = snap.originalState.isAir();

                if (buildIsAir && originalIsAir) return; // nothing changed

                if (!buildIsAir && !snap.buildState.equals(snap.originalState)) {
                    // Block was PLACED  → blue ghost at this position showing the placed block
                    entries.put(pos, new HologramSyncPacket.HologramEntry(snap.buildState, true));
                } else if (buildIsAir && !originalIsAir) {
                    // Block was BROKEN  → red ghost at this position showing what used to be there
                    entries.put(pos, new HologramSyncPacket.HologramEntry(snap.originalState, false));
                }
            });
        }

        // Send to the specific player (empty map = clear all holograms)
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new HologramSyncPacket(entries)
        );
    }

    private static int getStackCount(BlockState state) {
        if (state.isAir()) return 0;

        // Slabs: a DOUBLE slab = 2 items, single = 1
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)
                    == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE ? 2 : 1;
        }
        // Candles: CANDLES property 1-4
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.CANDLES)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.CANDLES);
        }
        // Sea Pickles: PICKLES property 1-4
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.PICKLES)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PICKLES);
        }
        // Turtle Eggs: EGGS property 1-4
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.EGGS)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.EGGS);
        }
        return 1;
    }

    public static Map<BlockPos, BlockState> getSessionChanges(UUID playerId) {
        return sessionChanges.get(playerId);
    }

    public static void tryRehydrateOnLogin(ServerPlayer player) {
        UUID id = player.getUUID();
        net.minecraft.server.level.ServerLevel level =
                (net.minecraft.server.level.ServerLevel) player.level();

        BlockPos anchor = PreviewSavedData.get(level).getSession(id);
        System.out.println("[Preview] Login rehydrate. Anchor from SavedData: " + anchor);

        if (anchor != null) {
            // Check what the BE actually has
            if (level.getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
                System.out.println("[Preview] BE snapshot count on login: " + be.getBuildSnapshots().size());
            } else {
                System.out.println("[Preview] ERROR: No BE found at anchor on login");
            }
            rehydrateSession(id, anchor);
        }
    }

    public static void rehydrateAndExit(ServerPlayer player) {
        UUID id = player.getUUID();
        BlockPos anchor = playerAnchorPos.get(id);
        if (anchor == null) return;

        Level level = player.level();

        if (level.getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            Map<BlockPos, BuildSnapshot> savedSnapshots = be.getBuildSnapshots();
            if (savedSnapshots != null && !savedSnapshots.isEmpty()) {
                pendingCommit.put(id, new HashMap<>(savedSnapshots));

                // Calculate cost from raw snapshot data BEFORE exitPreview can wipe it.
                // Use calculateRequiredItemsFromMap (compares buildState vs originalState)
                // rather than calculateRequiredItems (compares world state vs originalState),
                // because the world has already been rolled back on logout.
                Map<Item, Integer> correctCost = calculateRequiredItemsFromMap(savedSnapshots);
                be.setRequiredItems(correctCost, id);
                be.setChanged();
            }
        }

        exitPreview(player);

        // exitPreview clears pendingCommit entries but keeps the BE's requiredItems intact.
        // Re-push the cost one more time after exit so it survives the setBuildData call
        // inside exitPreview (which recalculates from an already-rolled-back world = empty).
        if (level.getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            if (be.getRequiredItems().isEmpty() && !be.getBuildSnapshots().isEmpty()) {
                Map<Item, Integer> correctCost = calculateRequiredItemsFromMap(be.getBuildSnapshots());
                be.setRequiredItems(correctCost, id);
            }
            be.updateBlock();
        }
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

    public static void recordAndSync(ServerPlayer player, BlockPos placedPos,
                                     BlockState stateBefore, @Nullable BlockPos pendingAir) {
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
                BlockState captured = consumePendingPartner(id, pos);
                BlockState current;
                if (pos.equals(pendingAir)) {
                    current = Blocks.AIR.defaultBlockState();
                } else if (captured != null) {
                    complexSnapshot.put(pos, new BuildSnapshot(captured, Blocks.AIR.defaultBlockState()));
                    return;
                } else {
                    current = player.level().getBlockState(pos);
                }
                complexSnapshot.put(pos, new BuildSnapshot(original, current));
            });

            Map<Item, Integer> liveCost = calculateRequiredItems(player, pendingAir);
            be.setRequiredItems(liveCost, id);
            // NOTE: no be.updateBlock() here anymore — this only updates server-side
            // bookkeeping (and setChanged() for disk-save safety). No packet is sent.
            // The client will explicitly ask for a fresh sync when it opens the inventory.
        }

        sendHologramUpdate(player); // unchanged — holograms still need to be live in the world
    }

    public static void forceSessionEntry(UUID playerId, BlockPos pos, BlockState originalState) {
        sessionChanges.computeIfAbsent(playerId, k -> new HashMap<>())
                .putIfAbsent(pos, originalState);
    }


    public static void exitPreview(ServerPlayer player) {
        UUID id = player.getUUID();
        Level level = player.level();
        BlockPos anchor = playerAnchorPos.get(id);
        lastSyncTime.remove(id);
        // 3. Top of exitPreview() — BEFORE the rollback loop (to clear ghosts):
        sendHologramUpdate(player);


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
        GameType previousMode = previousGameModes.getOrDefault(id, null);
        if (previousMode == null && anchor != null
                && level.getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            previousMode = be.getAndClearSavedGameMode();
        }
        player.setGameMode(previousMode != null ? previousMode : GameType.SURVIVAL);

// Inside exitPreview
        if (anchor != null) {
            BlockPos safePos = findSafeTeleportPos(level, anchor);
            // Add 0.2 to Y to ensure the player "falls" into place rather than "spawning in" the floor
            player.teleportTo(safePos.getX() + 0.5, safePos.getY() + 0.2, safePos.getZ() + 0.5);
        }

        // 5. CLEANUP
        // At the end of exitPreview cleanup section:
        PreviewSavedData.get((net.minecraft.server.level.ServerLevel) level).removeSession(id);
        sessionChanges.remove(id);
        playerAnchorPos.remove(id);
        previousGameModes.remove(id);
        secondaryBlocks.remove(id);
        pendingPartnerStates.remove(id);

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

    public static void cleanupSessionOnly(UUID id) {
        sessionChanges.remove(id);
        previousGameModes.remove(id);
        lastSyncTime.remove(id);
        savedInventories.remove(id);
        playerAnchorPos.remove(id);
        pendingPartnerStates.remove(id);
        // Intentionally keep: pendingCommit (shopping list), SavedData (anchor lookup)
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