package net.hydroset.buildpreviewer.item;

import net.hydroset.buildpreviewer.BuildPreviewer;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BuildPreviewer.MOD_ID);

    public static final RegistryObject<Item> BUILDACCESS = ITEMS.register("builder_access",
            () -> new Item(new Item.Properties()));



    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
