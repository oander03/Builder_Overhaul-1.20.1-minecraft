package net.hydroset.buildpreviewer.block.entity;

import net.hydroset.buildpreviewer.BuildPreviewer;
import net.hydroset.buildpreviewer.block.ModBlocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BuildPreviewer.MOD_ID);

    public static final RegistryObject<BlockEntityType<PreviewBlockEntity>> PREVIEW_BE =
            BLOCK_ENTITIES.register("preview_be", () ->
                    BlockEntityType.Builder.of(PreviewBlockEntity::new,
                            ModBlocks.BUILDACCESS_BLOCK.get()).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}