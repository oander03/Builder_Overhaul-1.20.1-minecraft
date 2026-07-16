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

    // ---- Count-up animation support ----
    private static final long COUNT_ANIM_DURATION_MS = 450L;

    private static class CountAnim {
        float displayed = 0f;
        float animStart = 0f;
        int target = Integer.MIN_VALUE; // sentinel forces first detection to trigger an anim leg
        long startTime = 0L;
    }

    // Per-item "remaining" counter animations, keyed by item (for the left-side overlay icons)
    private static final Map<Item, CountAnim> remainingAnims = new HashMap<>();

    // Placed/Broken change-counter animation
    private static float displayedPlaced = 0f;
    private static float displayedBroken = 0f;
    private static float animStartPlaced = 0f;
    private static float animStartBroken = 0f;
    private static int animTargetPlaced = Integer.MIN_VALUE;
    private static int animTargetBroken = Integer.MIN_VALUE;
    private static long animStartTimePB = 0L;

    private static float easeOutCubic(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float f = t - 1f;
        return f * f * f + 1f;
    }
    // ---- end animation support ----

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

        // Prune animation state for items no longer in the list so the map doesn't grow forever
        remainingAnims.keySet().removeIf(item -> !cachedIndexMap.containsKey(item));
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

        // Reset animation state too
        remainingAnims.clear();
        displayedPlaced = 0f;
        displayedBroken = 0f;
        animStartPlaced = 0f;
        animStartBroken = 0f;
        animTargetPlaced = Integer.MIN_VALUE;
        animTargetBroken = Integer.MIN_VALUE;
        animStartTimePB = 0L;
    }

    private static String formatCount(int value) {
        if (value >= 1000) {
            return (value / 1000) + "k";
        }
        return String.valueOf(value);
    }

    private static float scaleForCount(int value) {
        float scale = 1.0f;
        if (value >= 100 && value < 1000) {
            scale = 0.80f;
        } else if (value >= 100000) {
            scale = 0.65f;
        } else if (value >= 10000) {
            scale = 0.75f;
        }
        return scale;
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

    // Fires once when any container/inventory screen opens while in preview.
    // Resets the counters to 0 so they visibly "count up" to the real values —
    // whether that's the (possibly stale) cached value already on hand, or the
    // corrected value that arrives a few frames later once RequestBuildSyncPacket
    // completes its round trip. Either way the motion stays smooth.
    @SubscribeEvent
    public static void onScreenOpenedForAnimation(net.minecraftforge.client.event.ScreenEvent.Init.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PreviewManager.isInPreview(mc.player.getUUID())) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen containerScreen)) return;
        if (!(containerScreen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)
                && !(containerScreen.getMenu() instanceof net.minecraft.world.inventory.InventoryMenu)) return;

        displayedPlaced = 0f;
        displayedBroken = 0f;
        animTargetPlaced = Integer.MIN_VALUE;
        animTargetBroken = Integer.MIN_VALUE;
        remainingAnims.clear();
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

        int[] bounds = getScreenBounds(containerScreen);
        renderHud(event.getGuiGraphics(), mc, previewBE, mouseX, mouseY);
        renderHudButtons(event.getGuiGraphics(), mc, mouseX, mouseY, bounds[0], bounds[1], bounds[2], bounds[3]);
        renderChangeCounter(event.getGuiGraphics(), mc, previewBE, mouseX, mouseY);

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

    private static int[] getScreenBounds(AbstractContainerScreen<?> screen) {
        try {
            java.lang.reflect.Field leftField = AbstractContainerScreen.class.getDeclaredField("leftPos");
            java.lang.reflect.Field topField = AbstractContainerScreen.class.getDeclaredField("topPos");
            java.lang.reflect.Field wField = AbstractContainerScreen.class.getDeclaredField("imageWidth");
            java.lang.reflect.Field hField = AbstractContainerScreen.class.getDeclaredField("imageHeight");
            leftField.setAccessible(true);
            topField.setAccessible(true);
            wField.setAccessible(true);
            hField.setAccessible(true);
            return new int[]{leftField.getInt(screen), topField.getInt(screen), wField.getInt(screen), hField.getInt(screen)};
        } catch (Exception e) {
            return new int[]{0, 0, 176, 166}; // fallback: vanilla inventory size
        }
    }

    private static int changeCounterBarX, changeCounterBarY, changeCounterBarW, changeCounterBarH;


    private static void renderHudButtons(GuiGraphics guiGraphics, Minecraft mc, int mouseX, int mouseY,
                                         int guiLeft, int guiTop, int guiWidth, int guiHeight) {
        int margin = 30;
        int btnGap = 3;

        hudBtnExitW = 94; hudBtnExitH = 20;
        sliderW = 94; sliderH = 20;
        hudBtnHologramW = 20; hudBtnHologramH = 20;
        hudBtnClearW = 20; hudBtnClearH = 20;

        // --- Top row: Exit + time slider, sitting above the menu ---
        // --- Top row: hologram toggle, clear, change counter, sitting above the menu ---
        int counterBarW = 94;
        int topRowWidth = hudBtnHologramW + btnGap + hudBtnClearW + btnGap + counterBarW;
        int topRowStartX = guiLeft + (guiWidth - topRowWidth) / 2;
        int topRowY = guiTop - margin - hudBtnHologramH;

        hudBtnHologramX = topRowStartX;
        hudBtnHologramY = topRowY;
        hudBtnClearX = hudBtnHologramX + hudBtnHologramW + btnGap;
        hudBtnClearY = topRowY;

        changeCounterBarX = hudBtnClearX + hudBtnClearW + btnGap;
        changeCounterBarY = topRowY;
        changeCounterBarW = counterBarW;
        changeCounterBarH = hudBtnClearH;

        // --- Bottom row: Exit + time slider, sitting below the menu ---
        int bottomRowWidth = hudBtnExitW + btnGap + sliderW;
        int bottomRowStartX = guiLeft + (guiWidth - bottomRowWidth) / 2;
        int bottomRowY = guiTop + guiHeight + margin - 2;

        hudBtnExitX = bottomRowStartX;
        hudBtnExitY = bottomRowY;
        sliderX = hudBtnExitX + hudBtnExitW + btnGap;
        sliderY = bottomRowY;

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
        if (hudBtnExitHovered) {
            guiGraphics.renderTooltip(mc.font,
                    net.minecraft.network.chat.Component.literal("Exit and return world back to normal."),
                    mouseX, mouseY);
        } else if (hudBtnHologramHovered) {
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

    private static final int COUNTER_BASE_FILL = 0x33FFFFFF; // matches drawVanillaButton's base fill
    private static final int COUNTER_GREEN_RGB = 0x00CC44;
    private static final int COUNTER_RED_RGB   = 0xCC2222;
    private static final int COUNTER_TINT_ALPHA = 0x70; // max tint opacity once fully "lit up"
    private static final float COUNTER_INTENSITY_CAP = 100f;


    private static void renderChangeCounter(GuiGraphics guiGraphics, Minecraft mc, PreviewBlockEntity previewBE, int mouseX, int mouseY) {
        int actualPlaced = previewBE.getPendingPlacedCount();
        int actualBroken = previewBE.getPendingBrokenCount();

        long now = System.currentTimeMillis();

        if (actualPlaced != animTargetPlaced || actualBroken != animTargetBroken) {
            animStartPlaced = displayedPlaced;
            animStartBroken = displayedBroken;
            animTargetPlaced = actualPlaced;
            animTargetBroken = actualBroken;
            animStartTimePB = now;
        }

        float t = (now - animStartTimePB) / (float) COUNT_ANIM_DURATION_MS;
        float eased = easeOutCubic(t);
        displayedPlaced = animStartPlaced + (animTargetPlaced - animStartPlaced) * eased;
        displayedBroken = animStartBroken + (animTargetBroken - animStartBroken) * eased;

        int shownPlaced = Math.round(displayedPlaced);
        int shownBroken = Math.round(displayedBroken);

        int barX = changeCounterBarX;
        int barY = changeCounterBarY;
        int barW = changeCounterBarW;
        int barH = changeCounterBarH;

// Intensity driven by the live animated values, so the color ramps up
        // in step with the count-up animation rather than jumping instantly.
        // Base fill matches the other HUD buttons (Exit Builder, hologram, clear) exactly.
        RenderSystem.enableBlend();
        guiGraphics.fill(barX, barY, barX + barW, barY + barH, COUNTER_BASE_FILL);

        // Intensity driven by the live animated values, so the color ramps up
        // in step with the count-up animation rather than jumping instantly.
        float greenIntensity = Math.min(1f, displayedPlaced / COUNTER_INTENSITY_CAP);
        float redIntensity = Math.min(1f, displayedBroken / COUNTER_INTENSITY_CAP);
        int greenAlpha = Math.round(greenIntensity * COUNTER_TINT_ALPHA);
        int redAlpha = Math.round(redIntensity * COUNTER_TINT_ALPHA);

        // Green fades OUT toward the center, red fades IN from the center — no hard
        // seam, and the middle stays as the plain base fill when either count is low.
        for (int col = 0; col < barW; col++) {
            float frac = barW <= 1 ? 0f : (float) col / (barW - 1);
            int columnColor;
            if (frac <= 0.5f) {
                float localT = frac / 0.5f;
                int a = Math.round(greenAlpha * (1f - localT));
                columnColor = (a << 24) | COUNTER_GREEN_RGB;
            } else {
                float localT = (frac - 0.5f) / 0.5f;
                int a = Math.round(redAlpha * localT);
                columnColor = (a << 24) | COUNTER_RED_RGB;
            }
            guiGraphics.fill(barX + col, barY, barX + col + 1, barY + barH, columnColor);
        }
        RenderSystem.disableBlend();

        String placedStr = formatCount(shownPlaced);
        String brokenStr = formatCount(shownBroken);
        float scale = Math.min(scaleForCount(shownPlaced), scaleForCount(shownBroken));

        String text = "§a+" + placedStr + " §7| §c-" + brokenStr;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 300.0f);

        float centerX = barX + barW / 2f;
        float centerY = barY + barH / 2f;
        guiGraphics.pose().translate(centerX, centerY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);

        int textX = -mc.font.width(text) / 2;
        int textY = -mc.font.lineHeight / 2;
        guiGraphics.drawString(mc.font, text, textX, textY, 0xFFFFFF, false);

        guiGraphics.pose().popPose();

        // Tooltip — rendered at a higher Z, after the text, using un-scaled bar bounds
        boolean counterHovered = mouseX >= barX && mouseX < barX + barW
                && mouseY >= barY && mouseY < barY + barH;
        if (counterHovered) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, 400.0f);
            guiGraphics.renderTooltip(mc.font,
                    net.minecraft.network.chat.Component.literal("Blocks placed | Blocks broken "),
                    mouseX, mouseY);
            guiGraphics.pose().popPose();
        }
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

// Set target alpha based on drag state — computed AFTER sliderDragging is
// updated above, so it reflects this frame's actual drag state immediately.
        float targetAlpha = sliderDragging ? 0.15f : 1.0f;
        inventoryAlpha += (targetAlpha - inventoryAlpha) * 0.2f; // smooth lerp
        if (Math.abs(inventoryAlpha - targetAlpha) < 0.01f) inventoryAlpha = targetAlpha;




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
        guiGraphics.drawString(mc.font, timeLabel, textX, textY, 0xFFFFFF, false);
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

        long now = System.currentTimeMillis();

        for (int i = 0; i < cachedItemList.size(); i++) {
            Map.Entry<Item, Integer> entry = cachedItemList.get(i);
            Item item = entry.getKey();
            int totalNeeded = entry.getValue();
            int currentInSlot = cachedCounts.length > i ? cachedCounts[i] : 0;
            int actualRemaining = totalNeeded - currentInSlot;

            // Drive this item's displayed count toward actualRemaining, animating
            // from 0 (after an inventory-open reset) or redirecting smoothly if the
            // real value changes mid-animation (e.g. once the sync packet lands).
            CountAnim anim = remainingAnims.computeIfAbsent(item, k -> {
                CountAnim a = new CountAnim();
                a.startTime = now;
                return a;
            });

            if (anim.target != actualRemaining) {
                anim.animStart = anim.displayed;
                anim.target = actualRemaining;
                anim.startTime = now;
            }

            float t = (now - anim.startTime) / (float) COUNT_ANIM_DURATION_MS;
            float eased = easeOutCubic(t);
            anim.displayed = anim.animStart + (anim.target - anim.animStart) * eased;

            int displayedRemaining = Math.round(anim.displayed);

            int col = i / maxRows;
            int row = i % maxRows;
            int iconX = padding + col * columnWidth;
            int iconY = padding + row * rowHeight;

            boolean hovered = inventoryOpen
                    && mouseX >= iconX && mouseX < iconX + slotSize
                    && mouseY >= iconY && mouseY < iconY + slotSize;

            guiGraphics.renderItem(cachedStacks[i], iconX, iconY);

            if (hovered) {
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


            if (!hovered && displayedRemaining > 0) {
                renderHudCount(guiGraphics, mc, displayedRemaining, iconX, iconY);
            }
        }
    }

    private static int captureTextureId = -1;
    private static int captureTexWidth = -1;
    private static int captureTexHeight = -1;

    private static void ensureCaptureTexture(int width, int height) {
        if (captureTextureId != -1 && captureTexWidth == width && captureTexHeight == height) return;
        if (captureTextureId != -1) {
            org.lwjgl.opengl.GL11.glDeleteTextures(captureTextureId);
        }
        captureTextureId = org.lwjgl.opengl.GL11.glGenTextures();
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, captureTextureId);
        org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA,
                width, height, 0, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        captureTexWidth = width;
        captureTexHeight = height;
    }

    // Snapshots the currently-rendered frame (the 3D world, drawn just before this
// screen starts drawing) into our own texture. Nothing else can interfere with
// this later, since it's a plain copy, not a shared/global render state.
    private static void captureBackground(Minecraft mc) {
        int fbWidth = mc.getMainRenderTarget().width;
        int fbHeight = mc.getMainRenderTarget().height;
        ensureCaptureTexture(fbWidth, fbHeight);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, captureTextureId);
        org.lwjgl.opengl.GL11.glCopyTexSubImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, fbWidth, fbHeight);
    }

    // Draws the captured snapshot back over the menu's own rectangle, blended at
// the given alpha, AFTER the menu (and JEI, etc.) have already fully drawn.
// This is what actually produces the "see-through" look.
    private static void drawCapturedBackgroundOverlay(GuiGraphics guiGraphics, Minecraft mc,
                                                      int guiLeft, int guiTop, int guiWidth, int guiHeight,
                                                      float alpha) {
        if (captureTextureId == -1) return;

        double guiScale = mc.getWindow().getGuiScale();
        int fbX = (int) Math.round(guiLeft * guiScale);
        int fbY = (int) Math.round(guiTop * guiScale);
        int fbW = (int) Math.round(guiWidth * guiScale);
        int fbH = (int) Math.round(guiHeight * guiScale);

        // Framebuffer texture origin is bottom-left; flip V so the sampled patch
        // lines up with the same on-screen area we captured it from.
        float u0 = fbX / (float) captureTexWidth;
        float u1 = (fbX + fbW) / (float) captureTexWidth;
        float v0 = 1.0f - (fbY + fbH) / (float) captureTexHeight;
        float v1 = 1.0f - fbY / (float) captureTexHeight;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 450.0f); // draw above everything else this frame

        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, captureTextureId);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        org.joml.Matrix4f matrix = guiGraphics.pose().last().pose();
        com.mojang.blaze3d.vertex.Tesselator tess = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder buf = tess.getBuilder();
        buf.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);
        buf.vertex(matrix, guiLeft, guiTop + guiHeight, 0).uv(u0, v0).endVertex();
        buf.vertex(matrix, guiLeft + guiWidth, guiTop + guiHeight, 0).uv(u1, v0).endVertex();
        buf.vertex(matrix, guiLeft + guiWidth, guiTop, 0).uv(u1, v1).endVertex();
        buf.vertex(matrix, guiLeft, guiTop, 0).uv(u0, v1).endVertex();
        tess.end();

        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        guiGraphics.pose().popPose();
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onScreenRenderPre(net.minecraftforge.client.event.ScreenEvent.Render.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !PreviewManager.isInPreview(mc.player.getUUID())) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen containerScreen)) return;
        if (!(containerScreen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)
                && !(containerScreen.getMenu() instanceof net.minecraft.world.inventory.InventoryMenu)) return;

        if (inventoryAlpha < 0.999f) {
            captureBackground(mc); // grab the 3D world before anything draws over it this frame
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onScreenRenderPost(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !PreviewManager.isInPreview(mc.player.getUUID())) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen containerScreen)) return;
        if (!(containerScreen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)
                && !(containerScreen.getMenu() instanceof net.minecraft.world.inventory.InventoryMenu)) return;

        if (inventoryAlpha < 0.999f) {
            int screenW = mc.getWindow().getGuiScaledWidth();
            int screenH = mc.getWindow().getGuiScaledHeight();
            drawCapturedBackgroundOverlay(event.getGuiGraphics(), mc,
                    0, 0, screenW, screenH, 1.0f - inventoryAlpha);
        }
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

                // Optimistically splice the item out locally instead of resetCache().
                // Do NOT touch lastRequirementsHash — the server's data hasn't caught up
                // yet, and resetting it to 0 makes updateCache() think the stale data
                // is "new" and re-adds this item on the very next frame.
                List<Map.Entry<Item, Integer>> newList = new ArrayList<>(cachedItemList);
                newList.remove(i);
                remainingAnims.remove(clickedItem);
                cachedItemList = newList;
                cachedStacks = new ItemStack[cachedItemList.size()];
                cachedIndexMap = new HashMap<>();
                for (int j = 0; j < cachedItemList.size(); j++) {
                    cachedStacks[j] = new ItemStack(cachedItemList.get(j).getKey());
                    cachedIndexMap.put(cachedItemList.get(j).getKey(), j);
                }
                if (cachedCounts.length != cachedItemList.size()) {
                    cachedCounts = new int[cachedItemList.size()];
                }
                return;
            }
        }
    }
}