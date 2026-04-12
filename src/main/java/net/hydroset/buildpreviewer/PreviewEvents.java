package net.hydroset.buildpreviewer;

import net.hydroset.buildpreviewer.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BuildPreviewer.MOD_ID)
public class PreviewEvents {

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();

        // If the player is in "Preview Mode"
        if (PreviewManager.isInPreview(player.getUUID())) {
            // If they are trying to break the Access Block, STOP them
            if (event.getState().is(ModBlocks.BUILDACCESS_BLOCK.get())) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();

        // If the player is in "Preview Mode"
        if (PreviewManager.isInPreview(player.getUUID())) {
            BlockState state = level.getBlockState(pos);

            // If the block they clicked is NOT the Access Block, STOP the interaction
            if (!state.is(ModBlocks.BUILDACCESS_BLOCK.get())) {
                event.setCanceled(true);
                // Optional: send a message so they know why
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.literal("You can only interact with the Access Block in Preview!"), true);
                }
            }
        }
    }
}
