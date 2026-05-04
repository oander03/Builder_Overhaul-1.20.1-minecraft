package net.hydroset.buildpreviewer.block;

import net.hydroset.buildpreviewer.PreviewManager;
import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.Map;
import net.minecraft.world.level.block.state.properties.BooleanProperty;


public class PreviewBlock extends Block implements EntityBlock {

    // 1. Define the property
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");


    public PreviewBlock(BlockBehaviour.Properties properties) {
        super(properties);
        // 2. Set default state to off
        this.registerDefaultState(this.stateDefinition.any().setValue(ACTIVE, false));
    }

    // 3. Register the property so Minecraft knows it exists
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PreviewBlockEntity(pos, state);
    }


    @Override
    public boolean isOcclusionShapeFullBlock(BlockState state, BlockGetter world, BlockPos pos) {
        return true; // Keeps the block solid, but allows us to layer effects
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
        if (state.getValue(ACTIVE)) {
            return 0.0F;
        }
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
        // Only show particles if the block is an active anchor
        if (state.getValue(ACTIVE)) {




            double x = pos.getX() + 0.5D + (random.nextDouble() - 0.5D) * 2.0D;
            double y = pos.getY() + 0.5D + (random.nextDouble() - 0.5D) * 2.0D;
            double z = pos.getZ() + 0.5D + (random.nextDouble() - 0.5D) * 2.0D;

            // 1. The "Heat" Core (Orange/Yellow)
            level.addParticle(ParticleTypes.HAPPY_VILLAGER,
                    x, y, z,
                    0.0D, 0.06D, 0.0D);
        }
    }
    // Notice we added the 'snapshots' parameter here
    public static void commitBuild(ServerPlayer player, Map<BlockPos, PreviewManager.BuildSnapshot> snapshots) {
        if (snapshots != null && !snapshots.isEmpty()) {
            Level level = player.level();

            snapshots.forEach((pos, snapshot) -> {
                // CHANGE: Use Flag 3 (Constants.BlockFlags.DEFAULT)
                // This ensures the block is placed firmly and the neighbors are notified correctly
                level.setBlock(pos, snapshot.buildState, 3);
            });

            player.displayClientMessage(Component.literal("§aBuild finalized!"), false);
        }
    }


    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {

        if (!level.isClientSide()) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof PreviewBlockEntity previewBE) {
                // Check permissions
                if (!previewBE.canPlayerAccess(player)) {
                    player.displayClientMessage(Component.literal("§cThis block is currently locked by another player!"), true);
                    return InteractionResult.FAIL;
                }

                NetworkHooks.openScreen((ServerPlayer) player, (MenuProvider) entity, pos);
            } else {
                throw new IllegalStateException("Our Container provider is missing!");
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}