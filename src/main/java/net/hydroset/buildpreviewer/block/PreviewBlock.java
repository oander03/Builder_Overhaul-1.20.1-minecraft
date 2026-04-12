package net.hydroset.buildpreviewer.block;

import net.hydroset.buildpreviewer.PreviewManager;
import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.GameType;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

import static net.hydroset.buildpreviewer.PreviewManager.pendingCommit;

public class PreviewBlock extends Block implements EntityBlock {

    public PreviewBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PreviewBlockEntity(pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof PreviewBlockEntity) {
                // Drop the contents of the Capability/Inventory
                // This assumes you are using ItemStackHandler or a SimpleContainer
                ((PreviewBlockEntity) blockEntity).drops();
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        // Check if this specific block is currently an active anchor
        // Since this method runs on the Client too, we check our PreviewManager
        if (PreviewManager.isInPreview(player.getUUID())) {
            BlockPos anchorPos = PreviewManager.getAnchorPos(player.getUUID());
            if (pos.equals(anchorPos)) {
                return 0.0F; // 0% progress made, making it unbreakable
            }
        }

        // Check if ANYONE else is using it (for multiplayer)
        for (BlockPos activeAnchor : PreviewManager.getAllAnchorPositions()) {
            if (pos.equals(activeAnchor)) {
                return 0.0F;
            }
        }

        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
        // Only show particles if the block is an active anchor
        boolean isActive = false;
        for (BlockPos anchor : PreviewManager.getAllAnchorPositions()) {
            if (pos.equals(anchor)) {
                isActive = true;
                break;
            }
        }

        if (isActive) {
            if (random.nextInt(4) == 0) { // Only spawn occasionally
                double x = pos.getX() + random.nextDouble();
                double y = pos.getY() + 1.1D; // Just above the block
                double z = pos.getZ() + random.nextDouble();

                // Using END_ROD particles for a "magical build" look
                level.addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD,
                        x, y, z, 0.0D, 0.05D, 0.0D);
            }
        }
    }
    public static void commitBuild(ServerPlayer player) {
        UUID id = player.getUUID();

        // 1. Get the complex snapshot (Original vs Build)
        Map<BlockPos, PreviewManager.BuildSnapshot> snapshotMap = PreviewManager.pendingCommit.get(id);

        if (snapshotMap != null) {
            Level level = player.level();

            // 2. PHYSICALLY PLACE THE BLOCKS BACK
            // Since the player is in Survival/at the Anchor, we must force the blocks
            // from the snapshot into the actual world.
            snapshotMap.forEach((pos, snapshot) -> {
                level.setBlock(pos, snapshot.buildState, 3 | 16);
            });

            // 3. NOW delete the backup
            PreviewManager.pendingCommit.remove(id);

            player.displayClientMessage(Component.literal("§aBuild finalized! Blocks placed permanently."), false);
        } else {
            player.displayClientMessage(Component.literal("§cError: No build data found to place!"), false);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {

        if (!level.isClientSide()) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof PreviewBlockEntity) {
                // This opens the UI/Container for the player
                NetworkHooks.openScreen((ServerPlayer) player, (MenuProvider) entity, pos);
            } else {
                throw new IllegalStateException("Our Container provider is missing!");
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}