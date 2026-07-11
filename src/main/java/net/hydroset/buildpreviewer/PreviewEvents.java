package net.hydroset.buildpreviewer;

import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;


import java.util.*;

import static net.hydroset.buildpreviewer.PreviewManager.findSafeTeleportPos;

@Mod.EventBusSubscriber(modid = BuildPreviewer.MOD_ID)
public class PreviewEvents {

    private static final Set<Item> BANNED_ITEMS = Set.of(
            Items.TNT_MINECART,
            Items.FLINT_AND_STEEL,
            Items.LAVA_BUCKET,
            Items.FIRE_CHARGE,
            Items.END_CRYSTAL,
            Items.WATER_BUCKET,
            Items.BEDROCK,
            Items.COMMAND_BLOCK,
            Items.COMMAND_BLOCK_MINECART,
            Items.CHAIN_COMMAND_BLOCK,
            Items.REPEATING_COMMAND_BLOCK,
            Items.BARRIER,
            Items.SPAWNER,
            Items.END_PORTAL_FRAME,
            Items.SCULK_SHRIEKER,
            Items.WITHER_SKELETON_SKULL,
            Items.CARVED_PUMPKIN,
            Items.NETHER_STAR,
            //Items.POWDER_SNOW_BUCKET,
            Items.ARMOR_STAND,
            Items.ITEM_FRAME,
            Items.GLOW_ITEM_FRAME,
            Items.PAINTING,
            Items.CHORUS_FRUIT,
            Items.ENDER_PEARL,
            Items.RESPAWN_ANCHOR,
            Items.TNT,
            Items.ENDER_EYE,
            Items.BUCKET,
            Items.STRUCTURE_BLOCK,
            Items.STRUCTURE_VOID,
            Items.JIGSAW,
            Items.LIGHT,
            Items.DRAGON_EGG,
            Items.REINFORCED_DEEPSLATE,
            Items.TURTLE_EGG,
            Items.SPRUCE_SAPLING,
            Items.ACACIA_SAPLING,
            Items.BIRCH_SAPLING,
            Items.CHERRY_SAPLING,
            Items.OAK_SAPLING,
            Items.DARK_OAK_SAPLING,
            Items.JUNGLE_SAPLING
    );

    private static final Set<net.minecraft.world.level.block.Block> BANNED_BLOCKS_TO_BREAK = Set.of(
            Blocks.BEDROCK,
            Blocks.SPAWNER,
            Blocks.END_PORTAL_FRAME,
            Blocks.BARRIER,
            Blocks.COMMAND_BLOCK,
            Blocks.CHAIN_COMMAND_BLOCK,
            Blocks.REPEATING_COMMAND_BLOCK,
            Blocks.DRAGON_EGG,
            Blocks.REINFORCED_DEEPSLATE,
            Blocks.STRUCTURE_BLOCK,
            Blocks.JIGSAW,
            Blocks.LIGHT,
            Blocks.END_GATEWAY,
            Blocks.SCULK_SHRIEKER
            //Blocks.TRIAL_SPAWNER
    );

    @SubscribeEvent
    public static void preventDropsDuringPreview(BlockEvent.BreakEvent event) {
        if (PreviewManager.isInPreview(event.getPlayer().getUUID())) {
            // This ensures that even if a block "pops" due to physics,
            // it doesn't leave an item behind to clutter the world.
            event.setExpToDrop(0);
        }
    }

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PreviewManager.isInPreview(player.getUUID())) {
                // Access the server-side advancement tracker
                net.minecraft.server.PlayerAdvancements advancements = player.getAdvancements();

                // In 1.20.2+, use getAdvancementHolder()
                // In older versions, use getAdvancement()
                var advancement = event.getAdvancement();

                net.minecraft.advancements.AdvancementProgress progress = advancements.getOrStartProgress(advancement);

                if (progress.isDone()) {
                    // If revokeCriterion is red, try revoke(advancement, criterion)
                    // or ensure you are passing the correct Advancement object
                    for (String criterion : progress.getCompletedCriteria()) {
                        advancements.revoke(advancement, criterion);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntitySpawn(EntityJoinLevelEvent event) {
        // Check if the entity is one of the "dangerous" ones
        if (event.getEntity() instanceof net.minecraft.world.entity.vehicle.MinecartTNT ||
                event.getEntity() instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) {

            // Find if a Preview Player is the one who put it there
            for (UUID playerId : PreviewManager.getAllActivePlayers()) {
                Player player = event.getLevel().getPlayerByUUID(playerId);
                if (player != null && player.blockPosition().closerThan(event.getEntity().blockPosition(), 7)) {
                    event.setCanceled(true); // Vaporize the entity before it spawns
                    return;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onDimensionChange(net.minecraftforge.event.entity.EntityTravelToDimensionEvent event) {
        // Check if the entity traveling is a player
        if (event.getEntity() instanceof Player player) {
            // Check if that player is currently in Preview Mode
            if (PreviewManager.isInPreview(player.getUUID())) {
                // Cancel the teleportation
                event.setCanceled(true);

                // Notify the player
                if (!player.level().isClientSide) {
                    player.displayClientMessage(Component.literal("§cYou cannot change dimensions while in Preview Mode!"), true);
                    player.displayClientMessage(Component.literal("§eExit preview at the anchor first."), false);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.RightClickBlock event) {
        if (PreviewManager.isInPreview(event.getEntity().getUUID())) {
            Item item = event.getItemStack().getItem();
            BlockState targetedBlock = event.getLevel().getBlockState(event.getPos());

            // Prevent interacting with portals
            if (targetedBlock.is(Blocks.NETHER_PORTAL) || targetedBlock.is(Blocks.END_PORTAL)) {
                event.setCanceled(true);
                return;
            }

            // Block ALL interaction with Respawn Anchors
            if (targetedBlock.is(Blocks.RESPAWN_ANCHOR)) {
                event.setCanceled(true);
                return;
            }

            // Block any block that opens a GUI/menu (anvils, crafting tables,
            // enchanting tables, grindstones, looms, etc.) but has no block entity
            // (those are caught separately). This covers all vanilla interactive blocks.
            BlockPos pos = event.getPos();
            Level level = event.getLevel();
            if (targetedBlock.getMenuProvider(level, pos) != null) {
                event.setCanceled(true);
                event.setUseBlock(net.minecraftforge.eventbus.api.Event.Result.DENY);
                return;
            }

            // If it's in your list, stop EVERYTHING.
            if (BANNED_ITEMS.contains(item)) {
                event.setCanceled(true);
                event.setUseItem(net.minecraftforge.eventbus.api.Event.Result.DENY);
            }
        }
    }

    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        // If an explosion is triggered by a player in Preview Mode (e.g., a modded TNT)
        if (event.getExplosion().getIndirectSourceEntity() instanceof Player player) {
            if (PreviewManager.isInPreview(player.getUUID())) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerDrop(ItemTossEvent event) {
        Player player = event.getPlayer();

        if (PreviewManager.isInPreview(player.getUUID())) {
            // Cancel the toss
            event.setCanceled(true);

            // Return the item to the player's inventory so it doesn't just disappear
            player.getInventory().add(event.getEntity().getItem());

            if (!player.level().isClientSide) {
                player.displayClientMessage(Component.literal("§cYou cannot drop items in Preview Mode!"), true);
            }
        }
    }

    @SubscribeEvent
    public static void onPreviewDamage(LivingHurtEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (PreviewManager.isInPreview(player.getUUID())) {
                // Cancel ALL damage in preview mode (Void, /kill, etc.)
                event.setCanceled(true);

                // Inside PreviewEvents.onPreviewDamage
                if (player.getY() < player.level().getMinBuildHeight()) {
                    BlockPos anchor = PreviewManager.getAnchorPos(player.getUUID());
                    if (anchor != null) {
                        // Use the same logic here!
                        BlockPos safePos = findSafeTeleportPos(player.level(), anchor);
                        player.teleportTo(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
                        player.sendSystemMessage(Component.literal("§cYou fell into the void! Teleported to safety."));
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onTNTIgnite(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.item.PrimedTnt tnt) {
            BlockPos tntPos = tnt.blockPosition();

            // Loop through the UUIDs of everyone in preview
            for (java.util.UUID playerId : PreviewManager.getAllActivePlayers()) {
                // Find the actual player object on the server using the UUID
                Player player = event.getLevel().getPlayerByUUID(playerId);

                if (player != null) {
                    // If the player is within 10 blocks of the igniting TNT
                    if (player.blockPosition().closerThan(tntPos, 10)) {
                        event.setCanceled(true); // Kill the TNT entity

                        if (!event.getLevel().isClientSide) {
                            player.displayClientMessage(net.minecraft.network.chat.Component.literal("§cTNT ignition is disabled in Preview!"), true);
                        }
                        return;
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onProjectileSpawn(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Projectile projectile) {
            // Check if the person who shot/threw it is a player
            if (projectile.getOwner() instanceof Player player) {
                // If that player is in Preview Mode, delete the projectile immediately
                if (PreviewManager.isInPreview(player.getUUID())) {
                    event.setCanceled(true);

                    // Optional: Notify the player
                    if (!event.getLevel().isClientSide) {
                        player.displayClientMessage(Component.literal("§cProjectiles are disabled in Preview Mode!"), true);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!PreviewManager.isInPreview(player.getUUID())) return;

        UUID id = player.getUUID();
        BlockPos anchor = PreviewManager.getAnchorPos(id);
        Level level = player.level();

        // 1. Flush session changes
        PreviewManager.flushSessionChanges(player);

        // 2. Save to BE
        Map<BlockPos, PreviewManager.BuildSnapshot> snapshots = PreviewManager.pendingCommit.get(id);

        System.out.println("[Preview] Logging out. Snapshot count: " + (snapshots != null ? snapshots.size() : 0));

        if (anchor != null && level.getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
            if (snapshots != null) {
                be.setBuildData(snapshots, PreviewManager.calculateRequiredItemsFromMap(snapshots), id);
            }
            be.setChanged();
            System.out.println("[Preview] BE saved. Snapshot count in BE: " + be.getBuildSnapshots().size());
        } else {
            System.out.println("[Preview] ERROR: Could not find BE at anchor " + anchor);
        }

        // 3. Roll back world
        if (snapshots != null) {
            snapshots.forEach((pos, snapshot) -> {
                if (level.hasChunkAt(pos)) {
                    level.setBlock(pos, snapshot.originalState, 2 | 16 | 128);
                }
            });
        }

        // 4. Turn off ACTIVE
        if (anchor != null) {
            BlockState anchorState = level.getBlockState(anchor);
            if (anchorState.hasProperty(net.hydroset.buildpreviewer.block.PreviewBlock.ACTIVE)) {
                level.setBlock(anchor, anchorState.setValue(net.hydroset.buildpreviewer.block.PreviewBlock.ACTIVE, false), 3);
            }
        }

        PreviewManager.cleanupSessionOnly(id);
    }



    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        PreviewManager.tryRehydrateOnLogin(player);

        if (PreviewManager.isInPreview(player.getUUID())) {
            Objects.requireNonNull(player.getServer()).tell(new net.minecraft.server.TickTask(
                    player.getServer().getTickCount() + 2, () -> {
                PreviewManager.rehydrateAndExit(player);  // ← changed from exitPreview
                player.sendSystemMessage(Component.literal(
                        "§eYour preview session was automatically ended because you logged out."));
            }
            ));
        } else {
            GameType defaultMode = player.getServer().getDefaultGameType();
            if (defaultMode == GameType.CREATIVE) return;
            if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
                if (player.hasPermissions(2)) return;
                player.setGameMode(GameType.SURVIVAL);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerAttack(AttackEntityEvent event) {
        // Blocks the initial "click" to attack any entity
        if (PreviewManager.isInPreview(event.getEntity().getUUID())) {
            event.setCanceled(true);
            event.getEntity().displayClientMessage(Component.literal("§cInteracting with entities is disabled!"), true);
        }
    }

    @SubscribeEvent
    public static void onPlaceFluid(FillBucketEvent event) {
        // Blocks the use of buckets (Lava, Water, or modded fluids)
        if (PreviewManager.isInPreview(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        // Prevent clicking on entities (like opening Villager GUIs or hitting Armor Stands)
        if (PreviewManager.isInPreview(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID uuid = player.getUUID();

        if (!PreviewManager.isInPreview(uuid)) {
            if (isRealPlayer(player)) {
                BlockState stateBefore = pendingPlacementState.remove(uuid);
                // ✅ We want to record what the world NOW looks like at this pos
                // (the newly placed block), not what was there before placement.
                // originalState should reflect the real survival world state.
                BlockState placedState = event.getPlacedBlock();
                PreviewManager.recordChange(uuid, event.getPos(), placedState);
            }
            return;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            BlockState stateBefore = pendingPlacementState.remove(uuid);
            if (stateBefore == null) {
                stateBefore = Blocks.AIR.defaultBlockState();
            }
            // null pendingAir = this is a place, not a break, no override needed
            PreviewManager.recordAndSync(serverPlayer, event.getPos(), stateBefore, null);
        }
    }

    // Add this as a class field in PreviewEvents
    private static final Map<UUID, BlockState> pendingPlacementState = new HashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBeforePlace(PlayerInteractEvent.RightClickBlock event) {
        UUID uuid = event.getEntity().getUUID();

        // Right-clicking a block just to open its GUI (e.g. our anchor) is not a
        // placement attempt and must not be recorded as a pending state change.
        if (!(event.getItemStack().getItem() instanceof BlockItem)) {
            return;
        }

        BlockPos targetPos = event.getPos().relative(event.getFace());
        BlockState currentState = event.getLevel().getBlockState(targetPos);
        pendingPlacementState.put(uuid, currentState);

        if (PreviewManager.isInPreview(uuid)) {
            PreviewManager.recordChange(uuid, targetPos, currentState);
        }
    }
    @SubscribeEvent
    public static void onMultiBlockPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.getEntity() instanceof Player player) {
            UUID uuid = player.getUUID();
            if (PreviewManager.isInPreview(uuid)) {
                event.getReplacedBlockSnapshots().forEach(snapshot -> {
                    PreviewManager.recordChange(uuid, snapshot.getPos(), snapshot.getReplacedBlock());
                });
            }
        }
    }

    @SubscribeEvent
    public static void onBlockDrops(BlockEvent.BreakEvent event) {
        BlockPos pos = event.getPos();
        for (Map<BlockPos, PreviewManager.BuildSnapshot> buildMap : PreviewManager.pendingCommit.values()) {
            if (buildMap.containsKey(pos)) {
                // This is a preview block; ensure it drops NOTHING if it somehow breaks
                event.setExpToDrop(0);
                // Note: Canceled BreakEvents usually don't drop items anyway,
                // but this is a solid backup.
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        PreviewManager.pendingCommit.clear();
    }

    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;

        // Every 100 ticks (~5 seconds), force-save session state to disk
        // so a crash can be recovered from
        for (UUID uuid : PreviewManager.getAllActivePlayers()) {
            BlockPos anchor = PreviewManager.getAnchorPos(uuid);
            if (anchor == null) continue;

            net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) continue;

            // Find the player to get their level
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) continue;

            if (player.level().getBlockEntity(anchor) instanceof PreviewBlockEntity be) {
                // Force a full disk save of the block entity
                // This ensures buildSnapshots (with originalStates) is always up to date on disk
                be.setChanged();
            }
        }
    }

    @SubscribeEvent
    public static void onItemEntitySpawn(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity)) return;
        if (event.getLevel().isClientSide()) return;

        BlockPos spawnPos = event.getEntity().blockPosition();

        for (UUID playerId : PreviewManager.getAllActivePlayers()) {
            // Only suppress drops for players who are currently IN preview
            if (!PreviewManager.isInPreview(playerId)) continue;

            Map<BlockPos, PreviewManager.BuildSnapshot> buildMap = PreviewManager.pendingCommit.get(playerId);
            if (buildMap == null) continue;

            if (buildMap.containsKey(spawnPos)) {
                event.setCanceled(true);
                return;
            }
            for (Direction dir : Direction.values()) {
                if (buildMap.containsKey(spawnPos.relative(dir))) {
                    event.setCanceled(true);
                    return;
                }
            }
        }
    }



    private static boolean isRealPlayer(Player player) {
        return player instanceof ServerPlayer
                && !(player instanceof net.minecraftforge.common.util.FakePlayer);
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {

        Player player = event.getPlayer();

        // ✅ Only record survival changes if a real player caused them
        // Grass spreading, leaf decay etc. have a "fake" player — check it's a real ServerPlayer
        // In onBlockBreak, non-preview branch:
        if (!PreviewManager.isInPreview(player.getUUID())) {
            if (isRealPlayer(player)) {
                PreviewManager.recordChange(player.getUUID(), event.getPos(), event.getState());
            }
        }
        else {

            BlockPos targetPos = event.getPos();
            BlockPos anchorPos = PreviewManager.getAnchorPos(player.getUUID());
            BlockState state = event.getState();

            // Check if the block being broken is the specific anchor block
            if (targetPos.equals(anchorPos)) {
                event.setCanceled(true);
                player.displayClientMessage(Component.literal("You cannot break the anchor block while in preview!"), true);
                return;
            }

            // Prevent breaking block entities that contain items
            if (event.getLevel().getBlockEntity(targetPos) instanceof net.minecraft.world.Container container) {
                if (!container.isEmpty()) {
                    event.setCanceled(true);
                    if (!event.getLevel().isClientSide()) {
                        player.displayClientMessage(Component.literal("§cEmpty the container before breaking it in Preview Mode!"), true);
                    }
                    return;
                }
            }

            // --- NEW: Check Banned Blocks List ---
            if (BANNED_BLOCKS_TO_BREAK.contains(state.getBlock())) {
                event.setCanceled(true);
                if (!event.getLevel().isClientSide()) {
                    player.displayClientMessage(Component.literal("§cThis block is protected and cannot be broken!"), true);
                }
                return;
            }
            // Block breaking of Portals
            if (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY)) {
                event.setCanceled(true);
                return;
            }

            BlockPos pos = event.getPos();
            // 2. Prevent breaking the FRAME (Obsidian) or any block touching a portal
            // We check North, South, East, West, Up, and Down
            for (Direction direction : Direction.values()) {
                BlockState neighborState = event.getLevel().getBlockState(pos.relative(direction));
                if (neighborState.is(Blocks.NETHER_PORTAL)) {
                    event.setCanceled(true);
                    if (!event.getLevel().isClientSide()) {
                        player.displayClientMessage(Component.literal("§cCannot break blocks touching a portal!"), true);
                    }
                    return;
                }
            }

            Player breakingPlayer = event.getPlayer();

            // 1. GLOBAL PROTECTION: Check if ANYONE has this block in their preview
            for (Map<BlockPos, PreviewManager.BuildSnapshot> buildMap : PreviewManager.pendingCommit.values()) {
                if (buildMap.containsKey(pos)) {
                    // Check if the builder is trying to break their own preview block
                    if (PreviewManager.isInPreview(breakingPlayer.getUUID())) {
                        // Let the existing preview logic handle it (record change, etc.)
                        // This part is already handled further down in your file
                    } else {
                        // If a random survival player tries to mine it:
                        event.setCanceled(true);
                        breakingPlayer.displayClientMessage(Component.literal("§cThis is a preview block and cannot be mined!"), true);
                        return;
                    }
                }
            }

            // Check if ANY player in the world is currently using this block as their anchor
            boolean isBlockBusy = PreviewManager.getAllAnchorPositions().contains(targetPos);

            if (isBlockBusy) {
                event.setCanceled(true);
                event.getPlayer().displayClientMessage(Component.literal("§cThis block is currently locked in a Preview!"), true);
                return;
            }


            PreviewManager.recordChange(player.getUUID(), event.getPos(), event.getState());

            if (player instanceof ServerPlayer serverPlayer) {
                recordDoubleBlockPartner(serverPlayer, pos, state, event.getLevel());
                PreviewManager.recordAndSync(serverPlayer, event.getPos(), event.getState(), event.getPos());
            }
        }
    }

    private static void recordDoubleBlockPartner(ServerPlayer player, BlockPos brokenPos, BlockState brokenState, net.minecraft.world.level.LevelAccessor level) {
        BlockPos partnerPos = null;

        // Beds: find the other half
        if (brokenState.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            net.minecraft.world.level.block.state.properties.BedPart part =
                    brokenState.getValue(net.minecraft.world.level.block.BedBlock.PART);
            net.minecraft.core.Direction facing = brokenState.getValue(net.minecraft.world.level.block.BedBlock.FACING);
            if (part == net.minecraft.world.level.block.state.properties.BedPart.FOOT) {
                partnerPos = brokenPos.relative(facing); // head is in front
            } else {
                partnerPos = brokenPos.relative(facing.getOpposite()); // foot is behind
            }
        }
        // Doors: find the other half
        else if (brokenState.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf half =
                    brokenState.getValue(net.minecraft.world.level.block.DoorBlock.HALF);
            if (half == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                partnerPos = brokenPos.above();
            } else {
                partnerPos = brokenPos.below();
            }
        }
        // Tall plants (sunflower, lilac, etc.)
        else if (brokenState.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf half =
                    brokenState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF);
            if (half == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                partnerPos = brokenPos.above();
            } else {
                partnerPos = brokenPos.below();
            }
        }

        if (partnerPos == null) return;

        BlockState partnerState = level.getBlockState(partnerPos);

        if (partnerState.getBlock() == brokenState.getBlock()) {
            // Capture NOW while both halves are still in the world
            PreviewManager.capturePendingPartner(player.getUUID(), partnerPos, partnerState);
            PreviewManager.forceSessionEntry(player.getUUID(), partnerPos, partnerState);
        }
    }

    @SubscribeEvent
    public static void onPistonPush(PistonEvent.Pre event) {
        BlockPos targetPos = event.getPos().relative(event.getDirection());

        if (PreviewManager.getAllAnchorPositions().contains(targetPos)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        // Get all blocks currently being used as anchors
        java.util.Collection<BlockPos> anchors = PreviewManager.getAllAnchorPositions();

        // Remove any anchor blocks from the explosion's target list
        event.getAffectedBlocks().removeIf(anchors::contains);
    }

    @SubscribeEvent
    public static void onFallingBlock(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof FallingBlockEntity fallingBlock) {
            BlockPos pos = fallingBlock.blockPosition();

            for (BlockPos anchor : PreviewManager.getAllAnchorPositions()) {
                if (pos.closerThan(anchor, 15)) {
                    event.setCanceled(true);
                    return;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onBlockInteract(PlayerInteractEvent.LeftClickBlock event) {
        BlockPos pos = event.getPos();
        if (event.getLevel().getBlockEntity(pos) instanceof PreviewBlockEntity be) {
            if (!be.canPlayerAccess(event.getEntity())) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (PreviewManager.isInPreview(player.getUUID())) {
            BlockPos clickedPos = event.getPos();
            Level level = event.getLevel();

            // No-build zone check
            double radius = 5.0;
            List<Player> nearbyPlayers = level.getEntitiesOfClass(Player.class,
                    new AABB(clickedPos).inflate(radius),
                    p -> p != player);

            if (!nearbyPlayers.isEmpty()) {
                event.setCanceled(true);
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.literal("§cYou are too close to another player to build!"), true);
                }
                return;
            }

            // Always allow interacting with the anchor block itself
            if (event.getPos().equals(PreviewManager.getAnchorPos(player.getUUID()))) return;

            // Block interaction with any block entity (chests, furnaces, etc.)
            // but NOT anvils, crafting tables, or other "use" blocks without inventories
            if (event.getLevel().getBlockEntity(event.getPos()) != null && !(player.isSecondaryUseActive())) {
                event.setCanceled(true);
                return;
            }

            // Only block non-BlockItem usage if the clicked block is a plain block
            // (i.e. not an interactive block like anvil, crafting table, enchanting table)
            ItemStack stack = player.getItemInHand(event.getHand());
            BlockState clickedState = level.getBlockState(clickedPos);

            boolean isInteractiveBlock = clickedState.getMenuProvider(level, clickedPos) != null
                    || clickedState.is(net.minecraft.tags.BlockTags.DOORS)
                    || clickedState.is(net.minecraft.tags.BlockTags.TRAPDOORS)
                    || clickedState.is(net.minecraft.tags.BlockTags.FENCE_GATES);

            if (!isInteractiveBlock && !(stack.getItem() instanceof BlockItem)) {
                event.setCanceled(true);
            }
        }
    }
}