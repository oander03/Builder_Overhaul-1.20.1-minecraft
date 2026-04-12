package net.hydroset.buildpreviewer.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.hydroset.buildpreviewer.BuildPreviewer;
import net.hydroset.buildpreviewer.networking.FinalizeBuildPacket;
import net.hydroset.buildpreviewer.networking.ModMessages;
import net.hydroset.buildpreviewer.networking.TogglePreviewPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;


import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PreviewScreen extends AbstractContainerScreen<PreviewMenu> {

    // Cached list of entries to render
    private List<Map.Entry<Item, Integer>> cachedReqList = new ArrayList<>();
    // To track if the requirements actually changed
    private int lastKnownMapHash = 0;

    private void updateRequirementCache() {
        Map<Item, Integer> requirements = this.menu.getBlockEntity().getRequiredItems();

        // Simple check: if the hash of the map changes, the content changed
        int currentHash = (requirements == null) ? 0 : requirements.hashCode();

        if (currentHash != lastKnownMapHash) {
            if (requirements == null || requirements.isEmpty()) {
                cachedReqList = new ArrayList<>();
            } else {
                // Only create the list here, once per data change!
                cachedReqList = new ArrayList<>(requirements.entrySet());
            }
            lastKnownMapHash = currentHash;
        }
    }

    public PreviewScreen(PreviewMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(BuildPreviewer.MOD_ID, "textures/gui/preview_block_gui.png");

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, TEXTURE);

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;

        guiGraphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Note: Inside renderLabels, (0,0) is already this.leftPos, this.topPos
        super.renderLabels(guiGraphics, mouseX, mouseY);

        Map<Item, Integer> requirements = this.menu.getBlockEntity().getRequiredItems();
        if (requirements == null) return;

        List<Map.Entry<Item, Integer>> reqList = new ArrayList<>(requirements.entrySet());
        for (int i = 0; i < reqList.size(); i++) {
            Slot slot = this.menu.slots.get(i);
            if (!slot.hasItem()) {
                // No need for leftPos/topPos here!
                guiGraphics.renderFakeItem(new ItemStack(reqList.get(i).getKey()), slot.x, slot.y);
                guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x80000000);
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, delta);

        // 1. Check for data updates
        updateRequirementCache();

        // 2. Use the cached list instead of creating a new one
        if (cachedReqList.isEmpty()) return;

        for (int i = 0; i < cachedReqList.size(); i++) {
            Slot slot = this.menu.slots.get(i);
            Item reqItem = cachedReqList.get(i).getKey();
            int totalNeeded = cachedReqList.get(i).getValue();

            int currentInSlot = slot.hasItem() ? slot.getItem().getCount() : 0;
            int remaining = totalNeeded - currentInSlot;

            if (remaining > 0) {
                int x = this.leftPos + slot.x;
                int y = this.topPos + slot.y;

                ItemStack ghostStack = new ItemStack(reqItem);
                ghostStack.setCount(1);

                guiGraphics.renderFakeItem(ghostStack, x, y);

                RenderSystem.enableBlend();
                guiGraphics.fill(x, y, x + 16, y + 16, 0x99000000);
                RenderSystem.disableBlend();

                String text = String.valueOf(remaining);
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(0, 0, 200);
                guiGraphics.drawString(this.font, text, x + 17 - this.font.width(text), y + 9, 0xFFFFFF);
                guiGraphics.pose().popPose();
            }
        }

        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void init() {
        super.init();
        net.minecraft.core.BlockPos pos = this.menu.getBlockEntity().getBlockPos();

        // Standard GUI width is 176.
        // We'll use two buttons that are 80 pixels wide each.
        int buttonWidth = 80;
        int spacing = 4; // Small gap between them

        // Calculate the starting X so the group of two is centered
        // (Total width of both buttons + gap is 164)
        int totalWidth = (buttonWidth * 2) + spacing;
        int startX = this.leftPos + (this.imageWidth / 2) - (totalWidth / 2);

        // Position them about 25 pixels above the GUI
        int buttonY = this.topPos - 25;

        // 1. Finalize Build Button (Left side)
        Button finalizeButton = Button.builder(Component.literal("Finalize"), (button) -> {
                    ModMessages.sendToServer(new FinalizeBuildPacket(pos));
                })
                .bounds(startX, buttonY, buttonWidth, 20)
                .build();

        // Set active status: Only active if NOT in preview mode
        // Note: You might need to check this on the client-side PreviewManager
        // or pass the state through the ContainerMenu.
        finalizeButton.active = !net.hydroset.buildpreviewer.PreviewManager.isInPreview(this.minecraft.player.getUUID());

        this.addRenderableWidget(finalizeButton);

        // 2. Toggle Preview Button (Right side)
        this.addRenderableWidget(Button.builder(Component.literal("Toggle"), (button) -> {
                    ModMessages.sendToServer(new TogglePreviewPacket(pos));
                    this.onClose();
        })
        .bounds(startX + buttonWidth + spacing, buttonY, buttonWidth, 20)
        .build());
    }
}