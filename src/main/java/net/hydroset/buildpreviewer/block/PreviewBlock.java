package net.hydroset.buildpreviewer.block;

import net.hydroset.buildpreviewer.PreviewManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.GameType;

public class PreviewBlock extends Block {

    public PreviewBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {

        // Only run logic on the server; the server will sync changes to the client
        if (!level.isClientSide()) {
            ServerPlayer serverPlayer = (ServerPlayer) player;

            if (PreviewManager.isInPreview(serverPlayer.getUUID())) {
                PreviewManager.exitPreview(serverPlayer);
            } else {
                PreviewManager.enterPreview(serverPlayer);
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}