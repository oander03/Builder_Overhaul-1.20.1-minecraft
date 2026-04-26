package net.hydroset.buildpreviewer;

import net.hydroset.buildpreviewer.block.entity.ModBlockEntities;
import net.hydroset.buildpreviewer.item.ModItems;
import net.hydroset.buildpreviewer.block.ModBlocks;
import com.mojang.logging.LogUtils;
import net.hydroset.buildpreviewer.networking.ModMessages;
import net.hydroset.buildpreviewer.screen.ModMenuTypes;
import net.hydroset.buildpreviewer.screen.PreviewScreen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(BuildPreviewer.MOD_ID)
public class BuildPreviewer
{
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "build_previewer";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    public BuildPreviewer(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();

        // Register the commonSetup method for mod loading
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        ModItems.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        modEventBus.addListener(this::clientSetup);


        // 2. IMPORTANT: Initialize the Networking Channel
        ModMessages.register();

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);



        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        // context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {

    }

    // Update your existing clientSetup method to look like this:
    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // This links the Menu to the Screen
            MenuScreens.register(ModMenuTypes.PREVIEW_MENU.get(), PreviewScreen::new);

            // ADD THIS LINE:
            // It allows the emissive texture (_e.png) to be rendered as an overlay
            // This is the most common reason it fails.
            // Ensure this points to the correct block in ModBlocks.
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.BUILDACCESS_BLOCK.get(), RenderType.cutout());
        });
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        if(event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.BUILDACCESS);
            event.accept(ModBlocks.BUILDACCESS_BLOCK);
        }
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {

    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {

        }
    }
}
