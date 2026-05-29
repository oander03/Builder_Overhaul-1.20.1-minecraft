package net.hydroset.buildpreviewer.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.hydroset.buildpreviewer.PreviewManager;
import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.hydroset.buildpreviewer.hologram.HologramRenderer;
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
    private static int hudBtnExitX, hudBtnExitY, hudBtnExitW = 94, hudBtnExitH = 20;
    private static int hudBtnHologramX, hudBtnHologramY, hudBtnHologramW = 20, hudBtnHologramH = 20;
    private static int hudBtnClearX, hudBtnClearY, hudBtnClearW = 20, hudBtnClearH = 20;
    private static boolean hudBtnExitHovered = false;
    private static boolean hudBtnHologramHovered = false;
    private static boolean hudBtnClearHovered = false;

    private static long clearBtnFirstClickTime = 0L;
    private static final long CLEAR_CONFIRM_TIMEOUT_MS = 3000L; // resets after 3s

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
        sliderValue = -1f; // reset so it re-reads from world on next preview entry
        sliderDragging = false;
        dayTimeField = null;
        dayTimeFieldOwner = null;
        inventoryAlpha = 1.0f;
        clearBtnFirstClickTime = 0L;
    }

    @SubscribeEvent
    public static void onPacketReceived(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        // reset on login
        dayTimeField = null;
        dayTimeFieldOwner = null;
        sliderValue = -1f;
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onRenderTick(net.minecraftforge.event.TickEvent.RenderTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PreviewManager.isInPreview(mc.player.getUUID())) return;
        if (sliderValue < 0f) return;

        long newTime = (long)(sliderValue * 24000);
        applyClientTime(mc, newTime);
    }

    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PreviewManager.isInPreview(mc.player.getUUID())) return;
        if (sliderValue < 0f) return;

        long newTime = (long)(sliderValue * 24000);
        applyClientTime(mc, newTime);
    }

    private static void renderHudCount(GuiGraphics guiGraphics, Minecraft mc, int remaining, int x, int y) {
        String text;
        if (remaining >= 1000) {
            text = (remaining / 1000) + "k";
        } else {
            text = String.valueOf(remaining);
        }

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x + 16, y + 16, 250.0f);

        float scale = 1.0f;

        if(remaining >= 100 && remaining < 1000) {
            scale = 0.75f;
        } else if(remaining >= 100000) {
            scale = 0.65f;
        } else if(remaining >= 10000) {
            scale = 0.75f;
        }

        guiGraphics.pose().scale(scale, scale, 1.0f);

        int textX = -mc.font.width(text);
        int textY = -mc.font.lineHeight + 1;
        guiGraphics.drawString(mc.font, text, textX, textY, 0xFF5555, true);

        guiGraphics.pose().popPose();
    }

    @SubscribeEvent
    public static void onScreenRender(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PreviewManager.isInPreview(mc.player.getUUID())) {
            if (!cachedItemList.isEmpty()) resetCache();
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen containerScreen)) return;
        if (!(containerScreen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)
                && !(containerScreen.getMenu() instanceof net.minecraft.world.inventory.InventoryMenu)) return;

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

    private static float inventoryAlpha = 1.0f;

    // Replace the two placeholder fields with these
    private static int sliderX, sliderY, sliderW = 94, sliderH = 20;
    private static boolean sliderDragging = false;
    private static float sliderValue = -1f; // -1 means "uninitialized, read from world"

    private static void renderHudButtons(GuiGraphics guiGraphics, Minecraft mc, int mouseX, int mouseY) {
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int margin = 6;
        int btnGap = 3;

        hudBtnExitW = 94; hudBtnExitH = 20;
        sliderW = 94; sliderH = 20;

        hudBtnExitX = screenW - hudBtnExitW - margin;
        hudBtnExitY = screenH - hudBtnExitH - margin;
        sliderX = screenW - sliderW - margin;
        sliderY = hudBtnExitY - sliderH - btnGap;
        hudBtnHologramX = screenW - hudBtnHologramW - margin;
        hudBtnHologramY = sliderY - hudBtnHologramH - btnGap;
        hudBtnClearW = 20; hudBtnClearH = 20;
        hudBtnClearX = hudBtnHologramX - hudBtnClearW - btnGap;
        hudBtnClearY = hudBtnHologramY;

        hudBtnExitHovered = mouseX >= hudBtnExitX && mouseX < hudBtnExitX + hudBtnExitW
                && mouseY >= hudBtnExitY && mouseY < hudBtnExitY + hudBtnExitH;

        hudBtnHologramHovered = mouseX >= hudBtnHologramX && mouseX < hudBtnHologramX + hudBtnHologramW
                && mouseY >= hudBtnHologramY && mouseY < hudBtnHologramY + hudBtnHologramH;


        hudBtnClearHovered = mouseX >= hudBtnClearX && mouseX < hudBtnClearX + hudBtnClearW
                && mouseY >= hudBtnClearY && mouseY < hudBtnClearY + hudBtnClearH;

        // Initialize slider from current world time if not yet set
        if (sliderValue < 0f && mc.level != null) {
            sliderValue = (mc.level.getDayTime() % 24000) / 24000f;
        }

        drawVanillaButton(guiGraphics, mc, hudBtnExitX, hudBtnExitY, hudBtnExitW, hudBtnExitH,
                "⛏ Exit Builder", hudBtnExitHovered);

// Replace the drawVanillaButton call for the hologram button:
        boolean hologramsOn = HologramRenderer.isHologramsEnabled();
        drawVanillaButton(guiGraphics, mc, hudBtnHologramX, hudBtnHologramY, hudBtnHologramW, hudBtnHologramH,
                hologramsOn ? "◆" : "◇", hudBtnHologramHovered);

        boolean clearPending = (System.currentTimeMillis() - clearBtnFirstClickTime) < CLEAR_CONFIRM_TIMEOUT_MS;
        drawVanillaButton(guiGraphics, mc, hudBtnClearX, hudBtnClearY, hudBtnClearW, hudBtnClearH,
                clearPending ? "§c✕" : "✕", hudBtnClearHovered);

// Tooltips — rendered last so they always appear on top
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 400.0f);
        if (hudBtnHologramHovered) {
            guiGraphics.renderTooltip(mc.font,
                    net.minecraft.network.chat.Component.literal(hologramsOn ? "Hide holograms" : "Show holograms"),
                    mouseX, mouseY);
        } else if (hudBtnClearHovered) {
            guiGraphics.renderTooltip(mc.font,
                    net.minecraft.network.chat.Component.literal(clearPending ? "§cAre you sure?" : "Clear all changes"),
                    mouseX, mouseY);
        } else if (mouseX >= sliderX && mouseX < sliderX + sliderW
        && mouseY >= sliderY && mouseY < sliderY + sliderH && !sliderDragging) {
        guiGraphics.renderTooltip(mc.font,
                net.minecraft.network.chat.Component.literal("Drag to change time of day"),
                mouseX, mouseY);
        }
        guiGraphics.pose().popPose();

        drawTimeSlider(guiGraphics, mc, mouseX, mouseY);
    }

    private static void drawTimeSlider(GuiGraphics guiGraphics, Minecraft mc, int mouseX, int mouseY) {
        net.minecraft.resources.ResourceLocation WIDGETS =
                new net.minecraft.resources.ResourceLocation("textures/gui/widgets.png");

        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();

        // Update drag state based on live mouse position + button held
        boolean mouseDown = net.minecraft.client.gui.screens.Screen.hasControlDown()
                ? false // don't interfere with ctrl+clicks
                : (org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                mc.getWindow().getWindow(),
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS);

        if (sliderDragging && !mouseDown) {
            sliderDragging = false;
        }

// Set target alpha based on drag state
        float targetAlpha = sliderDragging ? 0.15f : 1.0f;
        inventoryAlpha += (targetAlpha - inventoryAlpha) * 0.2f; // smooth lerp
        if (Math.abs(inventoryAlpha - targetAlpha) < 0.01f) inventoryAlpha = targetAlpha;

        // After drawing the slider track blits, before drawing the handle:
        boolean sliderHovered = mouseX >= sliderX && mouseX < sliderX + sliderW
                && mouseY >= sliderY && mouseY < sliderY + sliderH;

        if (sliderDragging || (mouseDown
                && mouseX >= sliderX && mouseX < sliderX + sliderW
                && mouseY >= sliderY && mouseY < sliderY + sliderH)) {
            sliderDragging = mouseDown;
            if (mouseDown) {
                sliderValue = (float)(mouseX - sliderX) / sliderW;
                sliderValue = Math.max(0f, Math.min(1f, sliderValue));
            }
        }




        // Replace the two track blits and two handle blits with this:
        RenderSystem.enableBlend();

// Track background

        guiGraphics.fill(sliderX, sliderY, sliderX + sliderW, sliderY + sliderH, 0x33FFFFFF);

// Filled portion left of handle
        int handleW = 8;
        int handleX = sliderX + (int)(sliderValue * (sliderW - handleW));
        guiGraphics.fill(sliderX, sliderY, handleX + handleW / 2, sliderY + sliderH,
                sliderHovered || sliderDragging ? 0x33BBBBBB : 0x33AAAAAA);


// Handle pip
        guiGraphics.fill(handleX, sliderY, handleX + handleW, sliderY + sliderH, 0xFF292929);
        RenderSystem.disableBlend();

        // 1. Hover tint first (underneath everything)
        if (sliderHovered && !sliderDragging) {
            RenderSystem.enableBlend();
            guiGraphics.fill(sliderX, sliderY, sliderX + sliderW, sliderY + sliderH, 0x40000000);
            RenderSystem.disableBlend();
        }

        // Label
        long ticks = (long)(sliderValue * 24000);
        long displayTime = (ticks + 6000) % 24000;
        int hours = (int)(displayTime / 1000);
        int minutes = (int)((displayTime % 1000) * 60 / 1000);
        String timeLabel = String.format("☀ %02d:%02d", hours, minutes);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 300.0f);
        int textX = sliderX + (sliderW - mc.font.width(timeLabel)) / 2;
        int textY = sliderY + (sliderH - mc.font.lineHeight) / 2 + 1;
        guiGraphics.drawString(mc.font, timeLabel, textX, textY, 0xFFFFFF, true);
        guiGraphics.pose().popPose();

        RenderSystem.disableBlend();

        // Apply time to client level every frame (not just while dragging)
        // This also keeps it locked when the server sends time updates
        if (mc.level != null && sliderValue >= 0f) {
            long newTime = (long)(sliderValue * 24000);
            applyClientTime(mc, newTime);
        }
    }

    private static java.lang.reflect.Field dayTimeField = null;
    private static Object dayTimeFieldOwner = null; // the ClientLevelData instance

    private static void applyClientTime(Minecraft mc, long newTime) {
        net.minecraft.client.multiplayer.ClientLevel level =
                (net.minecraft.client.multiplayer.ClientLevel) mc.level;
        if (level == null) return;

        // Find and cache the field on first call or when level changes
        if (dayTimeField == null || dayTimeFieldOwner == null) {
            long currentTime = level.getDayTime();

            // First try fields on ClientLevel itself
            for (java.lang.reflect.Field field : getAllFields(level.getClass())) {
                if (field.getType() != long.class) continue;
                field.setAccessible(true);
                try {
                    if (field.getLong(level) == currentTime) {
                        dayTimeField = field;
                        dayTimeFieldOwner = level;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            // If not found on level, check all object fields on ClientLevel for a nested object
            // that contains the dayTime (this is ClientLevelData)
            if (dayTimeField == null) {
                for (java.lang.reflect.Field objField : getAllFields(level.getClass())) {
                    if (objField.getType().isPrimitive() || objField.getType() == String.class) continue;
                    objField.setAccessible(true);
                    try {
                        Object obj = objField.get(level);
                        if (obj == null) continue;
                        for (java.lang.reflect.Field innerField : getAllFields(obj.getClass())) {
                            if (innerField.getType() != long.class) continue;
                            innerField.setAccessible(true);
                            try {
                                if (innerField.getLong(obj) == currentTime) {
                                    dayTimeField = innerField;
                                    dayTimeFieldOwner = obj;
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                    if (dayTimeField != null) break;
                }
            }
        }

        // Apply the time
        // Apply the time
        if (dayTimeField != null && dayTimeFieldOwner != null) {
            try {
                // Re-verify the field still holds a plausible dayTime value before writing
                long currentVal = dayTimeField.getLong(dayTimeFieldOwner);
                // If the server just wrote a wildly different value, our cached field is still right
                // Just overwrite unconditionally
                dayTimeField.setLong(dayTimeFieldOwner, newTime);
            } catch (Exception e) {
                dayTimeField = null;
                dayTimeFieldOwner = null;
            }
        }
    }

    private static java.util.List<java.lang.reflect.Field> getAllFields(Class<?> clazz) {
        java.util.List<java.lang.reflect.Field> fields = new java.util.ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(java.util.Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }

    private static void drawVanillaButton(GuiGraphics guiGraphics, Minecraft mc,
                                          int x, int y, int w, int h,
                                          String label, boolean hovered) {
        RenderSystem.enableBlend();

        guiGraphics.fill(x, y, x + w, y + h, 0x33FFFFFF);
        RenderSystem.disableBlend();
        RenderSystem.enableBlend();
        if(hovered) {
            guiGraphics.fill(x, y, x + w, y + h, 0x40000000);
        }
        RenderSystem.disableBlend();
        int textColor = 0xFFFFFF;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 300.0f);
        int textX = x + (w - mc.font.width(label)) / 2;
        int textY = y + (h - mc.font.lineHeight) / 2 + 1;
        guiGraphics.drawString(mc.font, label, textX, textY, textColor, false);
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
            }


            if (!hovered && remaining > 0) {
                renderHudCount(guiGraphics, mc, remaining, iconX, iconY);
            }
        }
    }

    @SubscribeEvent
    public static void onScreenRenderPre(net.minecraftforge.client.event.ScreenEvent.Render.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !PreviewManager.isInPreview(mc.player.getUUID())) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen containerScreen)) return;
        if (!(containerScreen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)
                && !(containerScreen.getMenu() instanceof net.minecraft.world.inventory.InventoryMenu)) return;

        if (inventoryAlpha < 0.999f) {
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, inventoryAlpha);
        }
    }

    @SubscribeEvent
    public static void onScreenRenderPost(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !PreviewManager.isInPreview(mc.player.getUUID())) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen containerScreen)) return;
        if (!(containerScreen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)
                && !(containerScreen.getMenu() instanceof net.minecraft.world.inventory.InventoryMenu)) return;

        // Always restore full color after the screen finishes rendering
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    @SubscribeEvent
    public static void onScreenMouseClick(net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PreviewManager.isInPreview(mc.player.getUUID())) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen containerScreen)) return;
        if (!(containerScreen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)
                && !(containerScreen.getMenu() instanceof net.minecraft.world.inventory.InventoryMenu)) return;

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



        if (mouseX >= hudBtnHologramX && mouseX < hudBtnHologramX + hudBtnHologramW
                && mouseY >= hudBtnHologramY && mouseY < hudBtnHologramY + hudBtnHologramH) {
            HologramRenderer.setHologramsEnabled(!HologramRenderer.isHologramsEnabled());
            return;
        }

        if (mouseX >= hudBtnClearX && mouseX < hudBtnClearX + hudBtnClearW
                && mouseY >= hudBtnClearY && mouseY < hudBtnClearY + hudBtnClearH) {
            long now = System.currentTimeMillis();
            if (now - clearBtnFirstClickTime < CLEAR_CONFIRM_TIMEOUT_MS) {
                // Second click — confirmed
                BlockPos anchor = PreviewManager.getAnchorPos(mc.player.getUUID());
                if (anchor != null) {
                    net.hydroset.buildpreviewer.networking.ModMessages.sendToServer(
                            new net.hydroset.buildpreviewer.networking.ClearAllBlocksPacket(anchor));
                }
                clearBtnFirstClickTime = 0L;
                resetCache();
            } else {
                // First click — arm the confirmation
                clearBtnFirstClickTime = now;
            }
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