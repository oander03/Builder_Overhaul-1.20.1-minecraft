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
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
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


import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
            Items.SCULK_SHRIEKER
    );

    private static final Set<net.minecraft.world.level.block.Block> BANNED_BLOCKS_TO_BREAK = Set.of(
            Blocks.BEDROCK,
            Blocks.SPAWNER,
            Blocks.END_PORTAL_FRAME,
            Blocks.BARRIER,
            Blocks.COMMAND_BLOCK,
            Blocks.CHAIN_COMMAND_BLOCK,
            Blocks.REPEATING_COMMAND_BLOCK,
            Blocks.DRAGON_EGG
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

            // If it's in your list, stop EVERYTHING.
            // No animations, no sound, no spawning.
            if (BANNED_ITEMS.contains(item)) {
                event.setCanceled(true);
                event.setUseItem(net.minecraftforge.eventbus.api.Event.Result.DENY);
                return;
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
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PreviewManager.isInPreview(player.getUUID())) {

                // Get the anchor before we clear the manager data
                BlockPos anchor = PreviewManager.getAnchorPos(player.getUUID());
                Level level = player.level();

                // 1. Force the texture and particles OFF immediately
                if (anchor != null) {
                    BlockState state = level.getBlockState(anchor);
                    // Ensure we are actually looking at our PreviewBlock
                    if (state.hasProperty(net.hydroset.buildpreviewer.block.PreviewBlock.ACTIVE)) {
                        level.setBlock(anchor, state.setValue(net.hydroset.buildpreviewer.block.PreviewBlock.ACTIVE, false), 3);
                    }
                }
                // Force exit logic
                PreviewManager.exitPreview(player);

                if (anchor != null) {
                    player.teleportTo(anchor.getX() + 0.5, anchor.getY() + 1.0, anchor.getZ() + 0.5);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // 1. Get the world's default game mode (Creative, Survival, etc.)
            GameType defaultMode = player.getServer().getDefaultGameType();

            BlockPos anchor = PreviewManager.getAnchorPos(player.getUUID());
            Level level = player.level();

            // 2. If the world itself is a Creative world, we don't need to force anything.
            if (defaultMode == GameType.CREATIVE) return;

            // 3. If they are in Creative but the world is Survival, check if they are in a preview.
            if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE && !PreviewManager.isInPreview(player.getUUID())) {

                // 4. Final Safety: Check if the player is an Admin/OP (Permission Level 2+).
                // If they are an OP, assume they are in Creative intentionally and leave them alone.
                if (player.hasPermissions(2)) return;

                // Only if they are a normal player in a Survival world who isn't in a preview session
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

        if (event.getEntity() instanceof Player player) {
            UUID uuid = player.getUUID();

            if (!PreviewManager.isInPreview(player.getUUID())) {
                PreviewManager.recordChange(
                        player.getUUID(),
                        event.getPos(),
                        event.getPlacedBlock()
                );
            }

            if (PreviewManager.isInPreview(uuid)) {
                BlockPos pos = event.getPos();
                // Get the snapshot of what was there BEFORE the placement
                // We use level.getBlockState(pos) which should still be Air/OldBlock
                BlockState stateBefore = event.getLevel().getBlockState(pos);
                if (player instanceof ServerPlayer serverPlayer) {
                    PreviewManager.syncLiveCostToAnchor(serverPlayer, null);                }
                PreviewManager.recordChange(uuid, pos, stateBefore);

            }

        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBeforePlace(PlayerInteractEvent.RightClickBlock event) {
        UUID uuid = event.getEntity().getUUID();
        if (PreviewManager.isInPreview(uuid)) {
            // We look at the position the block WILL be placed in.
            // If they click the TOP of a block, the new block goes at Pos + Up.
            BlockPos targetPos = event.getPos().relative(event.getFace());
            BlockState currentState = event.getLevel().getBlockState(targetPos);

            // Record it now, before the placement happens
            PreviewManager.recordChange(uuid, targetPos, currentState);
        }
    }

    @SubscribeEvent
    public static void onMultiBlockPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.getEntity() instanceof Player player) {
            UUID uuid = player.getUUID();
            if (PreviewManager.isInPreview(uuid)) {
                // MultiPlaceEvent provides a list of all affected block snapshots
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
        // Wipe everything to prevent "Ghost Worlds"
        PreviewManager.pendingCommit.clear();
        // Clear other static maps here if needed
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {

        Player player = event.getPlayer();

        if (!PreviewManager.isInPreview(player.getUUID())) {
            PreviewManager.recordChange(
                    event.getPlayer().getUUID(),
                    event.getPos(),
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
            );
        }


        if (PreviewManager.isInPreview(player.getUUID())) {

            PreviewManager.recordChange(player.getUUID(), event.getPos(), event.getState());

            BlockPos targetPos = event.getPos();
            BlockPos anchorPos = PreviewManager.getAnchorPos(player.getUUID());
            BlockState state = event.getState();

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

            // Check if the block being broken is the specific anchor block
            if (targetPos.equals(anchorPos)) {
                event.setCanceled(true);
                player.displayClientMessage(Component.literal("You cannot break the anchor block while in preview!"), true);
            }

            // Check if ANY player in the world is currently using this block as their anchor
            boolean isBlockBusy = PreviewManager.getAllAnchorPositions().contains(targetPos);

            if (isBlockBusy) {
                event.setCanceled(true);
                event.getPlayer().displayClientMessage(Component.literal("§cThis block is currently locked in a Preview!"), true);
            }
            if (player instanceof ServerPlayer serverPlayer) {
                PreviewManager.syncLiveCostToAnchor(serverPlayer, event.getPos());            }
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
            // Check if the block is Dripstone, Sand, or Gravel
            if (fallingBlock.getBlockState().is(Blocks.POINTED_DRIPSTONE) ||
                    fallingBlock.getBlockState().is(BlockTags.SAND)) {

                BlockPos pos = fallingBlock.blockPosition();

                // If it's falling within the protection radius of an anchor
                for (BlockPos anchor : PreviewManager.getAllAnchorPositions()) {
                    if (pos.closerThan(anchor, 15)) { // 15 block protection radius
                        event.setCanceled(true); // Vaporize the falling block
                        return;
                    }
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
            // 1. Define your "No-Build Zone" radius (e.g., 5 blocks)
            double radius = 5.0;

            // 2. Search for any players (excluding the builder themselves) near the clicked block
            // We use AABB (Axis Aligned Bounding Box) to define the search area
            List<Player> nearbyPlayers = level.getEntitiesOfClass(Player.class,
                    new AABB(clickedPos).inflate(radius),
                    p -> p != player); // Don't count the person actually building

            if (!nearbyPlayers.isEmpty()) {
                event.setCanceled(true);
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.literal("§cYou are too close to another player to build!"), true);
                }
                return;
            }

            ItemStack stack = player.getItemInHand(event.getHand());

            // 1. Allow the Anchor Block always
            if (event.getPos().equals(PreviewManager.getAnchorPos(player.getUUID()))) return;

            // 2. UNIVERSAL RULE: If it's not a block, block it.
            // This stops modded "Fire Staves", "Destruction Wands", etc.
            if (!(stack.getItem() instanceof BlockItem)) {
                event.setCanceled(true);
                return;
            }

            // 3. Prevent clicking on other TileEntities (Chests/Machines from any mod)
            if (event.getLevel().getBlockEntity(event.getPos()) != null && !(player.isSecondaryUseActive())) {
                event.setCanceled(true);
                return;
            }
        }
    }
}