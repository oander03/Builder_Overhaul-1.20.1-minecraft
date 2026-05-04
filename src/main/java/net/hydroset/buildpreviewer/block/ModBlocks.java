package net.hydroset.buildpreviewer.block;

import net.hydroset.buildpreviewer.BuildPreviewer;
import net.hydroset.buildpreviewer.item.ModItems;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public class ModBlocks
{
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, BuildPreviewer.MOD_ID);

    public static final RegistryObject<Block> BUILDACCESS_BLOCK = registerBlock("builder_access_block",
            () -> new PreviewBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                    .sound(SoundType.AMETHYST)
                    // Use a lambda that correctly references the static ACTIVE property
                    .lightLevel(state -> state.getValue(PreviewBlock.ACTIVE) ? 10 : 3)
                    //.emissiveRendering((state, level, pos) -> true)
                    .hasPostProcess((state, level, pos) -> true)
            ));

    private static <T extends Block> RegistryObject<T> registerBlock(String name, Supplier<T> block) {
        RegistryObject<T> toReturn = BLOCKS.register(name, block);
        registerBlockItem(name, toReturn);
        return toReturn;
    }

    private static <T extends Block> RegistryObject<Item> registerBlockItem(String name, RegistryObject<T> block) {
        return ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
