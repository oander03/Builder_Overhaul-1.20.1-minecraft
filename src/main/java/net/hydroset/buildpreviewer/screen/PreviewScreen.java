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
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int direction = delta > 0 ? -1 : 1;
        menu.scrollTo(menu.scrollOffset + direction);

        // Force the client to recognize the slot changes immediately
        return true;
    }

    private boolean isScrolling = false;

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;

        // Check if mouse is within the scroll bar column
        if (mouseX >= x + 174 && mouseX <= x + 174 + 12) {
            if (mouseY >= y + 18 && mouseY <= y + 18 + 54) {
                this.isScrolling = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.isScrolling = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.isScrolling) {
            int y = (height - imageHeight) / 2 + 18;
            int totalItems = menu.getBlockEntity().getRequiredItems().size();
            int totalRows = (int) Math.ceil(totalItems / 9.0);
            int maxScroll = Math.max(0, totalRows - 3);

            float scrollProgress = ((float)mouseY - y - 7.5F) / (54.0F - 15.0F);
            int newOffset = (int)((scrollProgress * maxScroll) + 0.5);
            menu.scrollTo(newOffset);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    // Use the villager2 texture as requested
    private static final ResourceLocation SCROLLER_TEXTURE =
            new ResourceLocation(BuildPreviewer.MOD_ID, "textures/gui/container/scroll_bar.png");

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;

        // 1. Draw Main Background
        guiGraphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);

        // 2. Logic for scrolling
        int totalItems = menu.getBlockEntity().getRequiredItems().size();
        int totalRows = (int) Math.ceil(totalItems / 9.0);
        int maxScroll = Math.max(0, totalRows - 3);

        int scrollBarX = x + 174;
        int scrollBarYTop = y + 18;
        int scrollBarHeight = 54;

        // --- DEBUG: If you see a weird pink/black box, the path is wrong.
        // --- If you see nothing, the UV (176, 0) is pointing to a transparent pixel.

// Updated constants for your 512x256 texture
        int textureWidth = 512;
        int textureHeight = 256;

// The dimensions of the scroll handle itself in the PNG
        int handleWidth = 6;
        int handleHeight = 27; // Increased to match your crop

        if (maxScroll > 0) {
            float scrollFraction = (float) menu.scrollOffset / maxScroll;
            // Adjust handleY calculation to account for the larger handle height
            int handleY = scrollBarYTop + (int) (scrollFraction * (scrollBarHeight - handleHeight));

            // ACTIVE HANDLE (U=0)
            guiGraphics.blit(SCROLLER_TEXTURE, scrollBarX, handleY, 0.0F, 199.0F, handleWidth, handleHeight, textureWidth, textureHeight);
        } else {
            // INACTIVE HANDLE (U=12)
            // This shifts the U coordinate 12 pixels to the right to grab the second bar
            guiGraphics.blit(SCROLLER_TEXTURE, scrollBarX, scrollBarYTop, 12.0F, 199.0F, handleWidth, handleHeight, textureWidth, textureHeight);
        }
    }



    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Note: Inside renderLabels, (0,0) is already this.leftPos, this.topPos
        super.renderLabels(guiGraphics, mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        // 1. Draw the dark background tint
        renderBackground(guiGraphics);

        // --- SLOT BYPASS TRICK ---
        // We temporarily "empty" the 27 build slots so super.render()
        // doesn't draw the vanilla solid items or the white quantity numbers.
        ItemStack[] tempStacks = new ItemStack[27];
        for (int i = 0; i < 27; i++) {
            Slot slot = this.menu.slots.get(i);
            tempStacks[i] = slot.getItem().copy(); // Store a copy of the real item
            slot.set(ItemStack.EMPTY);             // Make the slot look empty to the super method
        }

        // 2. Call super to render the GUI frame, the Player Inventory, and Tooltips
        super.render(guiGraphics, mouseX, mouseY, delta);

        // 3. IMMEDIATELY restore the items so we can use them for our custom logic
        for (int i = 0; i < 27; i++) {
            this.menu.slots.get(i).set(tempStacks[i]);
        }
        // --- END TRICK ---

        // 4. Update our cache (ensures we aren't doing Map conversions 100x a second)
        updateRequirementCache();

        if (cachedReqList.isEmpty()) return;

        // 5. Custom Requirement Rendering
        int startIndex = menu.scrollOffset * 9;

        // Inside PreviewScreen.java render loop
        for (int i = 0; i < 27; i++) {
            Slot slot = this.menu.slots.get(i);
            int reqIndex = slot.getSlotIndex();

            if (reqIndex >= cachedReqList.size()) continue;

            Item reqItem = cachedReqList.get(reqIndex).getKey();
            int totalNeeded = cachedReqList.get(reqIndex).getValue();

// FIX: Use the tempStacks array we saved during the bypass trick!
            ItemStack stackInSlot = tempStacks[i];

            int currentInSlot = (stackInSlot.is(reqItem)) ? stackInSlot.getCount() : 0;
            int remaining = totalNeeded - currentInSlot;

            int x = this.leftPos + slot.x;
            int y = this.topPos + slot.y;

            // --- DRAW LOGIC ---
            if (!stackInSlot.isEmpty()) {
                guiGraphics.renderItem(stackInSlot, x, y);
                guiGraphics.renderItemDecorations(this.font, stackInSlot, x, y, "");
            } else if (remaining > 0) {
                guiGraphics.renderFakeItem(new ItemStack(reqItem), x, y);
            }

            // --- GREEN HIGHLIGHT LOGIC ---
            if (!stackInSlot.isEmpty() && currentInSlot >= totalNeeded) {
                RenderSystem.enableBlend();
                guiGraphics.fill(x, y, x + 16, y + 16, 0x8000FF00); // Green
                RenderSystem.disableBlend();

            } else if (remaining > 0) {
                // If slot is empty and we still need items, draw the GHOST block
                ItemStack ghostStack = new ItemStack(reqItem);
                guiGraphics.renderFakeItem(ghostStack, x, y);
            }

            // --- DRAW OVERLAYS ---
            RenderSystem.enableBlend();

            if (remaining > 0) {
                // LAYER A: DARK OVERLAY (As long as we still need items)
                // Color: 60% black (0x99000000)
                guiGraphics.fill(x, y, x + 16, y + 16, 0x99000000);

                RenderSystem.disableBlend(); // Temporarily disable blend for the text

                // --- DRAW TEXT LAYER (High Z-Index) ---
                String text = String.valueOf(remaining);
                guiGraphics.pose().pushPose();

                // Move the text to the absolute front (Z=250)
                guiGraphics.pose().translate(0, 0, 250.0f);

                int textX = x + 17 - this.font.width(text);
                int textY = y + 9;

                // Draw with shadow (true) for better readability
                guiGraphics.drawString(this.font, text, textX, textY, 0xFFFFFF, true);

                guiGraphics.pose().popPose();

                // Re-enable blend for any subsequent overlaps
                RenderSystem.enableBlend();

            } else if (!stackInSlot.isEmpty() && currentInSlot >= totalNeeded) {
                // THE NEW LAYER: GREEN OVERLAY (After cost is fully met)
                // Color: 50% opacity Green (0x8000FF00)
                // (Only draw if there's actually an item in the slot)
                guiGraphics.fill(x, y, x + 16, y + 16, 0x8000FF00);
            }

            RenderSystem.disableBlend(); // Final blend disable
        }

        // 6. Finally, render the tooltips over everything else
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