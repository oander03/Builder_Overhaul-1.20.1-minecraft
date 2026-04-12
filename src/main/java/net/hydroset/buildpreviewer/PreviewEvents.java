package net.hydroset.buildpreviewer;

import net.minecraft.core.BlockPos;
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
    public static void onInteract(PlayerInteractEvent.RightClickBlock event) {
        if (PreviewManager.isInPreview(event.getEntity().getUUID())) {
            Item item = event.getItemStack().getItem();

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

                // If they are falling into the void, snap them back to the anchor
                if (player.getY() < player.level().getMinBuildHeight()) {
                    BlockPos anchor = PreviewManager.getAnchorPos(player.getUUID());
                    if (anchor != null) {
                        player.teleportTo(anchor.getX() + 0.5, anchor.getY() + 1.0, anchor.getZ() + 0.5);
                        player.sendSystemMessage(Component.literal("§cYou fell into the void! Teleported to anchor."));
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
                // Force exit logic
                PreviewManager.exitPreview(player);

                // Teleport them back to the anchor so they don't log back in mid-air
                BlockPos anchor = PreviewManager.getAnchorPos(player.getUUID());
                if (anchor != null) {
                    player.teleportTo(anchor.getX() + 0.5, anchor.getY() + 1.0, anchor.getZ() + 0.5);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // If they are somehow still in Creative but NOT in our map
            // (This handles the 'I logged out in Creative' bug)
            if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE && !PreviewManager.isInPreview(player.getUUID())) {
                // Check if they should be in survival (you might need a custom capability to track this persistently)
                player.setGameMode(GameType.SURVIVAL);
            }
        }
    }

    @SubscribeEvent
    public static void onRedstoneInteract(PlayerInteractEvent.RightClickBlock event) {
        if (PreviewManager.isInPreview(event.getEntity().getUUID())) {
            BlockState state = event.getLevel().getBlockState(event.getPos());

            // Block interaction with common redstone triggers
            if (state.getBlock() instanceof ButtonBlock ||
                    state.getBlock() instanceof LeverBlock ||
                    state.getBlock() instanceof PressurePlateBlock) {

                event.setCanceled(true);
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
            if (PreviewManager.isInPreview(uuid)) {
                BlockPos pos = event.getPos();
                // Get the snapshot of what was there BEFORE the placement
                // We use level.getBlockState(pos) which should still be Air/OldBlock
                BlockState stateBefore = event.getLevel().getBlockState(pos);

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
    public static void onSurvivalBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;

        BlockPos pos = event.getPos();
        UUID playerId = event.getPlayer().getUUID();

        // Check if the player has a pending build
        if (PreviewManager.pendingCommit.containsKey(playerId)) {
            Map<BlockPos, PreviewManager.BuildSnapshot> snapshot = PreviewManager.pendingCommit.get(playerId);

            // If the block they just broke in Survival is in our "Restore" list...
            if (snapshot.containsKey(pos)) {
                // Update the 'originalState' to Air so it doesn't reappear
                // OR simply remove it from the snapshot if it was a block they placed
                snapshot.remove(pos);

                // Re-sync the shopping list since the world has changed
                // This prevents them from having to "pay" for a block they just broke
                if (event.getPlayer() instanceof ServerPlayer serverPlayer) {
                    Map<Item, Integer> newCost = PreviewManager.calculateRequiredItems(serverPlayer);
                    // Update the Anchor BlockEntity with the new cost
                    PreviewManager.updateAnchorCost(serverPlayer, newCost);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (PreviewManager.isInPreview(player.getUUID())) {
            BlockPos targetPos = event.getPos();
            BlockPos anchorPos = PreviewManager.getAnchorPos(player.getUUID());

            PreviewManager.recordChange(player.getUUID(), event.getPos(), event.getState());

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