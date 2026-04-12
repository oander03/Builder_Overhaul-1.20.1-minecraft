package net.hydroset.buildpreviewer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BuildPreviewer.MOD_ID)
public class PreviewEvents {

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (PreviewManager.isInPreview(player.getUUID())) {
            BlockPos targetPos = event.getPos();
            BlockPos anchorPos = PreviewManager.getAnchorPos(player.getUUID());

            // Check if the block being broken is the specific anchor block
            if (targetPos.equals(anchorPos)) {
                event.setCanceled(true);
                player.displayClientMessage(Component.literal("You cannot break the anchor block while in preview!"), true);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (PreviewManager.isInPreview(player.getUUID())) {
            BlockPos clickedPos = event.getPos();
            BlockPos anchorPos = PreviewManager.getAnchorPos(player.getUUID());

            // If the position clicked is NOT the exact coordinate of the starting block
            if (!clickedPos.equals(anchorPos)) {
                event.setCanceled(true);
                if (!event.getLevel().isClientSide) {
                    player.displayClientMessage(Component.literal("You can only interact with your original Access Block!"), true);
                }
            }
        }
    }
}