package net.hydroset.buildpreviewer.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.hydroset.buildpreviewer.PreviewManager;
import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class PreviewHudOverlay {

    private static List<Map.Entry<Item, Integer>> cachedItemList = new ArrayList<>();
    private static ItemStack[] cachedStacks = new ItemStack[0];
    private static int lastRequirementsHash = 0;
    private static int[] cachedCounts = new int[0];
    private static long lastCountUpdate = 0;
    private static Map<Item, Integer> cachedIndexMap = new HashMap<>();

    // HUD button state
    private static int hudBtnExitX, hudBtnExitY, hudBtnExitW = 90, hudBtnExitH = 14;
    private static int hudBtnPlaceholderX, hudBtnPlaceholderY, hudBtnPlaceholderW = 90, hudBtnPlaceholderH = 14;
    private static boolean hudBtnExitHovered = false;
    private static boolean hudBtnPlaceholderHovered = false;

    private static void updateCache(Map<Item, Integer> requiredItems) {
        int hash = requiredItems.hashCode();
        if (hash == lastRequirementsHash) return;

        lastRequirementsHash = hash;
        cachedItemList = new ArrayList<>(requiredItems.entrySet());
        cachedStacks = new ItemStack[cachedItemList.size()];
        cachedIndexMap = new HashMap<>();
        for (int i = 0; i < cachedItemList.size(); i++) {
            cachedStacks[i] = new ItemStack(cachedItemList.get(i).getKey());
            cachedIndexMap.put(cachedItemList.get(i).getKey(), i);
        }
    }

    private static void updateCountCache(PreviewBlockEntity previewBE) {
        long now = System.currentTimeMillis();
        if (now - lastCountUpdate < 100) return;
        lastCountUpdate = now;

        if (cachedCounts.length != cachedItemList.size()) {
            cachedCounts = new int[cachedItemList.size()];
        }

        previewBE.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            for (int i = 0; i < cachedCounts.length; i++) cachedCounts[i] = 0;
            for (int s = 0; s < handler.getSlots(); s++) {
                ItemStack stack = handler.getStackInSlot(s);
                if (stack.isEmpty()) continue;
                Integer idx = cachedIndexMap.get(stack.getItem());
                if (idx != null) cachedCounts[idx] += Math.max(0, stack.getCount());
            }
        });
    }

    public static void resetCache() {
        cachedItemList = new ArrayList<>();
        cachedStacks = new ItemStack[0];
        cachedCounts = new int[0];
        cachedIndexMap = new HashMap<>();
        lastRequirementsHash = 0;
        lastCountUpdate = 0;
    }

    // ✅ onRenderOverlay is completely gone

    @SubscribeEvent
    public static void onScreenRender(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PreviewManager.isInPreview(mc.player.getUUID())) {
            if (!cachedItemList.isEmpty()) resetCache();
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen)) return;

        BlockPos anchor = PreviewManager.getAnchorPos(mc.player.getUUID());
        if (anchor == null) return;

        BlockEntity be = mc.level.getBlockEntity(anchor);
        if (!(be instanceof PreviewBlockEntity previewBE)) return;

        int mouseX = (int)(mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth());
        int mouseY = (int)(mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight());

        renderHud(event.getGuiGraphics(), mc, previewBE, mouseX, mouseY);
        renderHudButtons(event.getGuiGraphics(), mc, mouseX, mouseY);   // ← new

        // Tooltip for item icons
        if (cachedItemList.isEmpty()) return;
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int slotSize = 16;
        int rowHeight = slotSize + 2;
        int padding = 2;
        int columnWidth = slotSize + padding;
        int maxRows = (screenHeight - padding * 2) / rowHeight;

        for (int i = 0; i < cachedItemList.size(); i++) {
            int col = i / maxRows;
            int row = i % maxRows;
            int iconX = padding + col * columnWidth;
            int iconY = padding + row * rowHeight;

            if (mouseX >= iconX && mouseX < iconX + slotSize && mouseY >= iconY && mouseY < iconY + slotSize) {
                event.getGuiGraphics().renderTooltip(mc.font, cachedStacks[i], mouseX, mouseY);
                break;
            }
        }
    }

    private static void renderHudButtons(GuiGraphics guiGraphics, Minecraft mc, int mouseX, int mouseY) {
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int margin = 6;
        int btnGap = 3;

        // Anchor both buttons to bottom-right
        hudBtnExitW = 90; hudBtnExitH = 14;
        hudBtnPlaceholderW = 90; hudBtnPlaceholderH = 14;

        hudBtnExitX = screenW - hudBtnExitW - margin;
        hudBtnExitY = screenH - hudBtnExitH - margin;
        hudBtnPlaceholderX = screenW - hudBtnPlaceholderW - margin;
        hudBtnPlaceholderY = hudBtnExitY - hudBtnPlaceholderH - btnGap;

        hudBtnExitHovered = mouseX >= hudBtnExitX && mouseX < hudBtnExitX + hudBtnExitW
                && mouseY >= hudBtnExitY && mouseY < hudBtnExitY + hudBtnExitH;
        hudBtnPlaceholderHovered = mouseX >= hudBtnPlaceholderX && mouseX < hudBtnPlaceholderX + hudBtnPlaceholderW
                && mouseY >= hudBtnPlaceholderY && mouseY < hudBtnPlaceholderY + hudBtnPlaceholderH;

        RenderSystem.enableBlend();

        // Exit Preview button
        drawHudButton(guiGraphics, mc, hudBtnExitX, hudBtnExitY, hudBtnExitW, hudBtnExitH,
                "⛏ Exit Preview", hudBtnExitHovered,
                0xCC3A2410, 0xCC5A3E2B, 0xFFFFEECC, 0xFF6B4C2A);   // warm brown — matches titlebar

        // Placeholder button
        drawHudButton(guiGraphics, mc, hudBtnPlaceholderX, hudBtnPlaceholderY, hudBtnPlaceholderW, hudBtnPlaceholderH,
                "✦ (Placeholder)", hudBtnPlaceholderHovered,
                0xCC1A2A1A, 0xCC2A4A2A, 0xFFAAEEAA, 0xFF3A6A3A);   // muted green

        RenderSystem.disableBlend();
    }

    private static void drawHudButton(GuiGraphics guiGraphics, Minecraft mc,
                                      int x, int y, int w, int h,
                                      String label, boolean hovered,
                                      int bgDark, int bgLight, int textColor, int borderColor) {

        int bg = hovered ? bgLight : bgDark;

        // Background fill
        guiGraphics.fill(x, y, x + w, y + h, bg);

        // 1px border — top/left lighter, bottom/right darker (classic MC bevel)
        int borderBright = borderColor;
        int borderShadow = borderColor & 0xFF000000 | ((borderColor & 0x00FEFEFE) >> 1); // half-brightness
        guiGraphics.fill(x,         y,         x + w,     y + 1,     borderBright); // top
        guiGraphics.fill(x,         y,         x + 1,     y + h,     borderBright); // left
        guiGraphics.fill(x,         y + h - 1, x + w,     y + h,     borderShadow); // bottom
        guiGraphics.fill(x + w - 1, y,         x + w,     y + h,     borderShadow); // right

        // Centered label
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 300.0f);
        int textX = x + (w - mc.font.width(label)) / 2;
        int textY = y + (h - mc.font.lineHeight) / 2 + 1;
        guiGraphics.drawString(mc.font, label, textX, textY, textColor, true);
        guiGraphics.pose().popPose();
    }

    private static void renderHud(GuiGraphics guiGraphics, Minecraft mc, PreviewBlockEntity previewBE, int mouseX, int mouseY) {
        Map<Item, Integer> requiredItems = previewBE.getRequiredItems();
        if (requiredItems == null || requiredItems.isEmpty()) return;

        updateCache(requiredItems);
        updateCountCache(previewBE);

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int slotSize = 16;
        int rowHeight = slotSize + 2;
        int padding = 2;
        int columnWidth = slotSize + padding;
        int maxRows = (screenHeight - padding * 2) / rowHeight;

        boolean inventoryOpen = mc.screen instanceof AbstractContainerScreen;

        for (int i = 0; i < cachedItemList.size(); i++) {
            Map.Entry<Item, Integer> entry = cachedItemList.get(i);
            int totalNeeded = entry.getValue();
            int currentInSlot = cachedCounts.length > i ? cachedCounts[i] : 0;
            int remaining = totalNeeded - currentInSlot;

            int col = i / maxRows;
            int row = i % maxRows;
            int iconX = padding + col * columnWidth;
            int iconY = padding + row * rowHeight;

            boolean hovered = inventoryOpen
                    && mouseX >= iconX && mouseX < iconX + slotSize
                    && mouseY >= iconY && mouseY < iconY + slotSize;

            guiGraphics.renderItem(cachedStacks[i], iconX, iconY);

            String countText;
            int textColor;

            if (hovered) {
                countText = "";
                textColor = 0;

                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(0, 0, 200.0f);
                RenderSystem.enableBlend();

                int xColor = 0xCCFF3333;
                int x0 = iconX + 3;
                int y0 = iconY + 3;
                int size = 10;

                for (int d = 0; d < size-1; d++) {
                    guiGraphics.fill(x0 + d, y0 + d, x0 + d + 2, y0 + d + 2, xColor);
                }
                for (int d = 0; d < size-1; d++) {
                    guiGraphics.fill(x0 + size - d - 2, y0 + d, x0 + size - d, y0 + d + 2, xColor);
                }

                RenderSystem.disableBlend();
                guiGraphics.pose().popPose();
            } else if (remaining <= 0) {
                countText = "✔";
                textColor = 0x55FF55;
            } else if (currentInSlot > 0) {
                countText = String.valueOf(remaining);
                float t = (float) currentInSlot / totalNeeded;
                int r = (int)(0x52 + t * (0xC9 - 0x52));
                int g = (int)(0x39 + t * (0xE9 - 0x39));
                int b = (int)(0x37 + t * (0x1D - 0x37));
                textColor = (0xFF << 24) | (r << 16) | (g << 8) | b;
            } else {
                countText = String.valueOf(remaining);
                textColor = 0xFF5555;
            }

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, 200.0f);
            if (!countText.isEmpty()) {
                int textX = iconX + 17 - mc.font.width(countText);
                int textY = iconY + 9;
                guiGraphics.drawString(mc.font, countText, textX, textY, textColor, true);
            }
            guiGraphics.pose().popPose();
        }
    }

    @SubscribeEvent
    public static void onScreenMouseClick(net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PreviewManager.isInPreview(mc.player.getUUID())) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen)) return;

        int mouseX = (int) event.getMouseX();
        int mouseY = (int) event.getMouseY();

        // --- HUD BUTTON CLICKS ---
        if (mouseX >= hudBtnExitX && mouseX < hudBtnExitX + hudBtnExitW
                && mouseY >= hudBtnExitY && mouseY < hudBtnExitY + hudBtnExitH) {
            BlockPos anchor = PreviewManager.getAnchorPos(mc.player.getUUID());
            if (anchor != null) {
                net.hydroset.buildpreviewer.networking.ModMessages.sendToServer(
                        new net.hydroset.buildpreviewer.networking.TogglePreviewPacket(anchor));
                mc.player.closeContainer();
            }
            return;
        }

        if (mouseX >= hudBtnPlaceholderX && mouseX < hudBtnPlaceholderX + hudBtnPlaceholderW
                && mouseY >= hudBtnPlaceholderY && mouseY < hudBtnPlaceholderY + hudBtnPlaceholderH) {
            // Placeholder action — replace with whatever you need later
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e(Placeholder button pressed)"));
            return;
        }

        // --- existing item-icon click loop below, unchanged ---
        if (cachedItemList.isEmpty()) return;

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int slotSize = 16;
        int rowHeight = slotSize + 2;
        int padding = 2;
        int columnWidth = slotSize + padding;
        int maxRows = (screenHeight - padding * 2) / rowHeight;

        for (int i = 0; i < cachedItemList.size(); i++) {
            int col = i / maxRows;
            int row = i % maxRows;
            int iconX = padding + col * columnWidth;
            int iconY = padding + row * rowHeight;

            if (mouseX >= iconX && mouseX < iconX + slotSize
                    && mouseY >= iconY && mouseY < iconY + slotSize) {

                BlockPos anchor = PreviewManager.getAnchorPos(mc.player.getUUID());
                if (anchor == null) return;

                Item clickedItem = cachedItemList.get(i).getKey();
                net.hydroset.buildpreviewer.networking.ModMessages.sendToServer(
                        new net.hydroset.buildpreviewer.networking.RemoveRequiredItemPacket(anchor, clickedItem)
                );

                resetCache();
                return;
            }
        }
    }
}