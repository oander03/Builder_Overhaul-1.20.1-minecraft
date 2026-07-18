package net.hydroset.buildpreviewer.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.hydroset.buildpreviewer.BuildPreviewer;
import net.hydroset.buildpreviewer.PreviewManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;

import net.minecraft.client.renderer.Rect2i;
import java.util.Collections;
import java.util.List;

@JeiPlugin
public class BuildPreviewerJeiPlugin implements IModPlugin {

    private static final ResourceLocation PLUGIN_ID =
            new ResourceLocation(BuildPreviewer.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        Class<AbstractContainerScreen<?>> screenClass =
                (Class<AbstractContainerScreen<?>>) (Class<?>) AbstractContainerScreen.class;

        registration.addGuiContainerHandler(screenClass, new IGuiContainerHandler<AbstractContainerScreen<?>>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(AbstractContainerScreen<?> screen) {
                Minecraft mc = Minecraft.getInstance();
                boolean inPreview = mc.player != null && PreviewManager.isInPreview(mc.player.getUUID());
                boolean isBuilderMenu = screen.getMenu() instanceof net.hydroset.buildpreviewer.screen.PreviewMenu;

                if (!inPreview && !isBuilderMenu) {
                    return Collections.emptyList();
                }
                // Claim the ENTIRE screen as "extra gui area" — this tells JEI
                // the whole screen belongs to our own UI, so it draws nothing
                // (no ingredient list, no bookmarks panel) anywhere at all.
                int w = mc.getWindow().getGuiScaledWidth();
                int h = mc.getWindow().getGuiScaledHeight();
                return Collections.singletonList(new Rect2i(0, 0, w, h));
            }
        });
    }
}