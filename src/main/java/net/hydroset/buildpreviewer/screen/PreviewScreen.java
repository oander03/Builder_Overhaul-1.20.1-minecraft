package net.hydroset.buildpreviewer.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.hydroset.buildpreviewer.BuildPreviewer;
import net.hydroset.buildpreviewer.block.entity.PreviewBlockEntity;
import net.hydroset.buildpreviewer.networking.FinalizeBuildPacket;
import net.hydroset.buildpreviewer.networking.ModMessages;
import net.hydroset.buildpreviewer.networking.TogglePreviewPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;


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

    private static class Bubble {
        float x, y;
        float baseSpeed; // base speed, multiplied dynamically
        float speed;     // can remove this field or keep as alias — replace usage below
        float alpha;
        int size;

        Bubble(float x, float y, float baseSpeed, float alpha, int size) {
            this.x = x;
            this.y = y;
            this.baseSpeed = baseSpeed;
            this.speed = baseSpeed; // kept for compatibility but we'll use baseSpeed
            this.alpha = alpha;
            this.size = size;
        }
    }

    private final List<List<Bubble>> slotBubbles = new ArrayList<>();
    private long lastBubbleTime = 0;
    private final float[] completionFlash = new float[27];
    private final boolean[] wasComplete = new boolean[27];

    public PreviewScreen(PreviewMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth  = 184;
        this.imageHeight = 224;
    }

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(BuildPreviewer.MOD_ID, "textures/gui/preview_block_gui.png");

    private static final ResourceLocation TEXTURE_SCROLL =
            new ResourceLocation(BuildPreviewer.MOD_ID, "textures/gui/preview_block_gui_scroll.png");

    private static final ResourceLocation SLOT_TEXTURE =
            new ResourceLocation(BuildPreviewer.MOD_ID, "textures/gui/single_slot_gui.png");

    private static final ResourceLocation TEXTURE_ACTIVE =
            new ResourceLocation(BuildPreviewer.MOD_ID, "textures/gui/preview_block_gui_active.png");

    private static final ResourceLocation TEXTURE_SCROLL_ACTIVE =
            new ResourceLocation(BuildPreviewer.MOD_ID, "textures/gui/preview_block_gui_scroll_active.png");

    private static final ResourceLocation WIDGETS_LOCATION =
            new ResourceLocation("textures/gui/widgets.png");

    /** The visible texture width (your PNG). imageWidth is wider to allow scrollbar overflow. */
    private static final int TEXTURE_RENDER_WIDTH = 184;

    private long clearBtnFirstClickTime = 0L;
    private static final long CLEAR_CONFIRM_TIMEOUT_MS = 3000L;
    private Button hologramButton;
    private Button clearButton;

    // ---- Change counter (placed/broken) ----
    private static final long COUNT_ANIM_DURATION_MS = 450L;
    private static final int COUNTER_BAR_ALPHA = 0x70; // max opacity once fully "lit up"
    private static final int COUNTER_GREEN_RGB = 0x00CC44;
    private static final int COUNTER_RED_RGB   = 0xCC2222;
    private static final float COUNTER_INTENSITY_CAP = 100f;

    private float displayedPlaced = 0f;
    private float displayedBroken = 0f;
    private float animStartPlaced = 0f;
    private float animStartBroken = 0f;
    private int animTargetPlaced = Integer.MIN_VALUE;
    private int animTargetBroken = Integer.MIN_VALUE;
    private long animStartTimePB = 0L;

    private int counterBarX, counterBarY, counterBarW, counterBarH;

    private static float easeOutCubic(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float f = t - 1f;
        return f * f * f + 1f;
    }

    private static String formatCount(int value) {
        if (value >= 1000) return (value / 1000) + "k";
        return String.valueOf(value);
    }

    private static float scaleForCount(int value) {
        if (value >= 100000) return 0.65f;
        if (value >= 10000) return 0.75f;
        if (value >= 100) return 0.80f;
        return 1.0f;
    }

    private static int lerpColor(int colorA, int colorB, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aA = (colorA >> 24) & 0xFF, rA = (colorA >> 16) & 0xFF, gA = (colorA >> 8) & 0xFF, bA = colorA & 0xFF;
        int aB = (colorB >> 24) & 0xFF, rB = (colorB >> 16) & 0xFF, gB = (colorB >> 8) & 0xFF, bB = colorB & 0xFF;
        int a = Math.round(aA + (aB - aA) * t);
        int r = Math.round(rA + (rB - rA) * t);
        int g = Math.round(gA + (gB - gA) * t);
        int b = Math.round(bA + (bB - bA) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void renderChangeCounter(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        PreviewBlockEntity be = this.menu.getBlockEntity();
        int actualPlaced = be.getPendingPlacedCount();
        int actualBroken = be.getPendingBrokenCount();

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

        float greenIntensity = Math.min(1f, displayedPlaced / COUNTER_INTENSITY_CAP);
        float redIntensity = Math.min(1f, displayedBroken / COUNTER_INTENSITY_CAP);
        int greenAlpha = Math.round(greenIntensity * COUNTER_BAR_ALPHA);
        int redAlpha = Math.round(redIntensity * COUNTER_BAR_ALPHA);
        int greenColor = (greenAlpha << 24) | COUNTER_GREEN_RGB;
        int redColor = (redAlpha << 24) | COUNTER_RED_RGB;

        boolean hovered = mouseX >= counterBarX && mouseX < counterBarX + counterBarW
                && mouseY >= counterBarY && mouseY < counterBarY + counterBarH;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Draw the actual vanilla button 9-slice texture — same atlas region Button
        // itself uses. 66 = normal state strip, 86 = hovered strip in widgets.png.
        int textureY = hovered ? 86 : 66;
        guiGraphics.blitNineSliced(WIDGETS_LOCATION, counterBarX, counterBarY, counterBarW, counterBarH, 20, 4, 200, 20, 0, textureY);

        // Left half tints green (alpha scales with placed count), right half tints red
        // (alpha scales with broken count). At 0/0 both are fully transparent, so the
        // raw button texture shows through untinted.
// Smooth per-column gradient instead of two flat halves — green fades OUT toward
        // the center, red fades IN from the center, so there's no hard seam and the
        // middle stays clean (near-transparent) when either count is low.
        // Inset by 1px on all sides so the button's black border stays visible.
        for (int col = 1; col < counterBarW - 1; col++) {
            float frac = counterBarW <= 1 ? 0f : (float) col / (counterBarW - 1);
            int columnColor;
            if (frac <= 0.5f) {
                float localT = frac / 0.5f; // 0 at left edge -> 1 at center
                int a = Math.round(greenAlpha * (1f - localT));
                columnColor = (a << 24) | COUNTER_GREEN_RGB;
            } else {
                float localT = (frac - 0.5f) / 0.5f; // 0 at center -> 1 at right edge
                int a = Math.round(redAlpha * localT);
                columnColor = (a << 24) | COUNTER_RED_RGB;
            }
            guiGraphics.fill(counterBarX + col, counterBarY + 1, counterBarX + col + 1, counterBarY + counterBarH - 1, columnColor);
        }
        RenderSystem.disableBlend();

        String placedStr = formatCount(shownPlaced);
        String brokenStr = formatCount(shownBroken);
        float scale = Math.min(scaleForCount(shownPlaced), scaleForCount(shownBroken));
        String text = "§a+" + placedStr + " §7| §c-" + brokenStr;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 300.0f);
        float centerX = counterBarX + counterBarW / 2f;
        float centerY = counterBarY + counterBarH / 2f;
        guiGraphics.pose().translate(centerX, centerY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        int textX = -this.font.width(text) / 2;
        int textY = -this.font.lineHeight / 2;
        guiGraphics.drawString(this.font, text, textX, textY, 0xFFFFFF, true);
        guiGraphics.pose().popPose();

        if (hovered) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, 400.0f);
            guiGraphics.renderTooltip(this.font,
                    Component.literal("Blocks placed | Blocks broken"),
                    mouseX, mouseY);
            guiGraphics.pose().popPose();
        }
    }

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
        int x = this.leftPos;
        int y = this.topPos;

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
            int y = this.topPos + 18;
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

        int x = this.leftPos;
        int y = this.topPos;

        // After:
        int totalItems = menu.getBlockEntity().getRequiredItems().size();
        int totalRows = (int) Math.ceil(totalItems / 9.0);
        boolean needsScroll = totalRows > 3;

// With this:
        boolean inPreview = net.hydroset.buildpreviewer.PreviewManager.isInPreview(this.minecraft.player.getUUID());
        ResourceLocation activeTex = inPreview
                ? (needsScroll ? TEXTURE_SCROLL_ACTIVE : TEXTURE_ACTIVE)
                : (needsScroll ? TEXTURE_SCROLL : TEXTURE);
        guiGraphics.blit(activeTex, x, y, 0, 0, TEXTURE_RENDER_WIDTH, imageHeight);
        int maxScroll = needsScroll ? totalRows - 3 : 0;

        int scrollBarX = x + 172;
        int scrollBarYTop = y + 40;
        int scrollBarHeight = 54;

        // --- DEBUG: If you see a weird pink/black box, the path is wrong.
        // --- If you see nothing, the UV (176, 0) is pointing to a transparent pixel.

// Updated constants for your 512x256 texture
        int textureWidth = 512;
        int textureHeight = 256;

// The dimensions of the scroll handle itself in the PNG
        int handleWidth = 6;
        int handleHeight = 27; // Increased to match your crop

        if (needsScroll) {
            float scrollFraction = menu.smoothScrollOffset / maxScroll;
            int handleY = scrollBarYTop + (int) (scrollFraction * (scrollBarHeight - handleHeight));
            guiGraphics.blit(SCROLLER_TEXTURE, scrollBarX, handleY, 0.0F, 199.0F, handleWidth, handleHeight, textureWidth, textureHeight);
        } else {
            guiGraphics.blit(SCROLLER_TEXTURE, scrollBarX, scrollBarYTop, 12.0F, 199.0F, handleWidth, handleHeight, textureWidth, textureHeight);
        }
    }

    private final long startNanos = System.nanoTime();
    private final float[] wave2Amplitude = new float[27];  // current disturbance amplitude per slot
    private int[] lastItemCount = new int[27];
    // initialized to -1 so first placement always triggers the spike
    { java.util.Arrays.fill(lastItemCount, -1); }

    private final float[] bubbleSpeedMult = new float[27];


    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean inPreview = net.hydroset.buildpreviewer.PreviewManager.isInPreview(this.minecraft.player.getUUID());

        // "Blueprint Builder" title — green in preview, pink normally
        if (inPreview) {
            guiGraphics.drawString(this.font, "Blueprint Builder", 24, 11, 0x2b3311, false);
            guiGraphics.drawString(this.font, "Blueprint Builder", 23, 10, 0xc7e59d, false);
        } else {
            guiGraphics.drawString(this.font, "Blueprint Builder", 24, 11, 0x111111, false);
            guiGraphics.drawString(this.font, "Blueprint Builder", 23, 10, 0xccb1bd, false);
        }

        // "Committing" / "Constructing" label — green in preview, dark pink normally
        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(0.8f, 0.8f, 1.0f);
        if (inPreview) {
            guiGraphics.drawString(this.font, "Building", (int)(129/0.8), (int)(11/0.8), 0x659f00, false);
        } else {
            guiGraphics.drawString(this.font, "Committing", (int)(125/0.8), (int)(11/0.8), 0x63333c, false);
        }
        guiGraphics.pose().popPose();

        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(0.9f, 0.9f, 1.0f);
        guiGraphics.drawString(this.font, this.title, (int)(8/0.9), (int)(30/0.9), 0x404040, false);
        guiGraphics.pose().popPose();

        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(0.9f, 0.9f, 1.0f);
        guiGraphics.drawString(this.font, this.playerInventoryTitle, (int)(8/0.9), (int)(97/0.9), 0x404040, false);
        guiGraphics.pose().popPose();
    }

    // Animated fill progress per slot (smoothly lerps toward real value)
    private float[] animatedFill = new float[27];

    private void renderSlotCount(GuiGraphics guiGraphics, int remaining, int x, int y) {
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

        int textX = (int)(-this.font.width(text) * 1.0f);
        int textY = (int)(-this.font.lineHeight * 1.0f) + 1;
        guiGraphics.drawString(this.font, text, textX, textY, 0xFF5555, true);

        guiGraphics.pose().popPose();
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
            tempStacks[i] = slot.getItem().copy();
            slot.set(ItemStack.EMPTY);
        }

        super.render(guiGraphics, mouseX, mouseY, delta);



// --- COVER VANILLA HIGHLIGHT ON EMPTY REQUIREMENT SLOTS ---
        for (int i = 0; i < 27; i++) {
            Slot slot = this.menu.slots.get(i);
            int slotX = this.leftPos + slot.x;
            int slotY = this.topPos + slot.y;
            if (mouseX >= slotX - 1 && mouseX < slotX + 17 && mouseY >= slotY - 1 && mouseY < slotY + 17) {
                if (tempStacks[i].isEmpty()) {
                    RenderSystem.setShader(GameRenderer::getPositionTexShader);
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                    guiGraphics.blit(SLOT_TEXTURE, slotX, slotY, 1, 1, 16, 16, 19, 19);
                }
            }
        }



        // 3. IMMEDIATELY restore the items so we can use them for our custom logic
        for (int i = 0; i < 27; i++) {
            this.menu.slots.get(i).set(tempStacks[i]);
        }
        // --- END TRICK ---

        // 4. Update our cache (ensures we aren't doing Map conversions 100x a second)
        updateRequirementCache();


        // Lerp smooth scroll toward target
        float scrollLerpSpeed = 0.2f;
        menu.smoothScrollOffset += (menu.targetScrollOffset - menu.smoothScrollOffset) * scrollLerpSpeed;
// Snap to exact value when close enough to avoid infinite creep
        if (Math.abs(menu.smoothScrollOffset - menu.targetScrollOffset) < 0.005f) {
            menu.smoothScrollOffset = menu.targetScrollOffset;
        }



        // Inside PreviewScreen.java render loop
        for (int i = 0; i < 27; i++) {
            Slot slot = this.menu.slots.get(i);
            int reqIndex = slot.getSlotIndex();

            if (reqIndex >= cachedReqList.size()) continue;

            Item reqItem = cachedReqList.get(reqIndex).getKey();


// Inside PreviewScreen.java -> render() loop
            ItemStack stackInSlot = tempStacks[i];

// Force the count to an Integer to prevent byte-wrapping glitches
            int currentInSlot = (stackInSlot.is(reqItem)) ? Math.max(0, stackInSlot.getCount()) : 0;
            int totalNeeded = cachedReqList.get(reqIndex).getValue();
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
            float targetFill = (totalNeeded > 0) ? (float) currentInSlot / totalNeeded : 0f;
// Lerp speed: 0.04f feels like slow water fill; increase for faster
            float lerpSpeed = 0.03f;
            if (animatedFill[i] < targetFill) {
                animatedFill[i] = Math.min(targetFill, animatedFill[i] + lerpSpeed);
            } else if (animatedFill[i] > targetFill) {
                // Snap down immediately when items are removed (feels more responsive)
                animatedFill[i] = targetFill;
            }
            float fillProgress = animatedFill[i];

            // --- COMPLETION FLASH TRIGGER ---
            boolean isComplete = fillProgress >= 1.0f && remaining <= 0;
            if (isComplete && !wasComplete[i]) {
                completionFlash[i] = 1.0f; // trigger flash
            }
            wasComplete[i] = isComplete;

// Decay flash each frame
            if (completionFlash[i] > 0f) {
                completionFlash[i] = Math.max(0f, completionFlash[i] - 0.04f);
            }

            // --- SLOT BACKGROUND IMAGE ---
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            guiGraphics.blit(SLOT_TEXTURE, x - 1, y - 1, 0, 0, 19, 19, 19, 19);

// --- DRAW OVERLAYS ---
            RenderSystem.enableBlend();

            if (remaining > 0 || fillProgress < 1.0f) {
                int overlayColor;
                if (fillProgress <= 0f) {
                    overlayColor = 0x99000000; // dark, empty
                } else {
                    float t = fillProgress;
                    int r = (int)(0x52 + t * (0xC9 - 0x52));
                    int g = (int)(0x39 + t * (0xE9 - 0x39));
                    int b = (int)(0x37 + t * (0x1D - 0x37));
                    overlayColor = (0x99 << 24) | (r << 16) | (g << 8) | b;
                }

                // --- WATER FILL EFFECT ---
                // Instead of filling the whole slot, fill from the bottom up
                int slotBottom = y + 16;
                int fillHeight = (int)(fillProgress * 16);

// Draw dark overlay on unfilled top (we'll refine per-column below)
// First pass: fill entire slot dark, then paint the wave over it
                guiGraphics.fill(x, y, x + 16, slotBottom, 0x99000000);

                // --- ITEM COUNT SPIKE DETECTION (must be outside fillHeight check!) ---
                int currentCount = stackInSlot.is(reqItem) ? stackInSlot.getCount() : 0;
                if (currentCount > lastItemCount[i]) {
                    wave2Amplitude[i] = 5.5f;
                    bubbleSpeedMult[i] = 4.0f;
                }
                lastItemCount[i] = currentCount;

                wave2Amplitude[i] *= 0.96f;
                if (wave2Amplitude[i] < 0.01f) wave2Amplitude[i] = 0f;

                bubbleSpeedMult[i] *= 0.96f;
                if (bubbleSpeedMult[i] < 0.01f) bubbleSpeedMult[i] = 0f;

                if (fillHeight > 0) {
                    double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

                    double wave1Time = elapsedSeconds * 1.8;
                    double wave1Amp  = 0.6;

                    double wave2Time = elapsedSeconds * 9.0;  // fast frequency
                    float w2amp = wave2Amplitude[i];

                    double shimmerTime = elapsedSeconds * 5.0;
                    float shimmerAlpha = (float)(0.4 + 0.35 * Math.sin(shimmerTime));
                    int shimmerA = (int)(shimmerAlpha * 255) << 24;
                    int shimmerColor = shimmerA | 0x00FFFFFF;

                    for (int col = 0; col < 16; col++) {
                        // Wave 1: subtle constant ripple
                        double w1 = Math.sin(wave1Time*1.3 + col * 0.37) * wave1Amp*1.3;
                        // Wave 2: fast decaying disturbance ripple
                        double w2 = Math.sin(wave2Time*2.8 + col * 0.20) * w2amp*0.8;

                        int waveOffset = (int) Math.round(w1 + w2);

                        int baseFillTop = slotBottom - fillHeight;
                        int colFillTop  = Math.max(y, Math.min(slotBottom - 1, baseFillTop + waveOffset));

                        int overlayColorCol;
                        if (fillProgress <= 0f) {
                            overlayColorCol = 0x99000000;
                        } else {
                            float t = fillProgress;
                            int r = (int)(0x52 + t * (0xC9 - 0x52));
                            int g = (int)(0x39 + t * (0xE9 - 0x39));
                            int b = (int)(0x37 + t * (0x1D - 0x37));
                            overlayColorCol = (0x99 << 24) | (r << 16) | (g << 8) | b;
                        }

                        guiGraphics.fill(x + col, colFillTop, x + col + 1, slotBottom, overlayColorCol);

                        if (fillProgress < 1.0f) {
                            guiGraphics.fill(x + col, colFillTop, x + col + 1, colFillTop + 1, shimmerColor);
                            if (colFillTop + 1 < slotBottom) {
                                guiGraphics.fill(x + col, colFillTop + 1, x + col + 1, colFillTop + 2, shimmerColor & 0x88FFFFFF);
                            }
                        }
                    }
                }

                RenderSystem.enableBlend();

                // --- DRAW TEXT ---
                renderSlotCount(guiGraphics, remaining, x, y);

                RenderSystem.enableBlend();

            } else {
                // Fully filled — green overlay
                guiGraphics.fill(x, y, x + 16, y + 16, 0x8000FF00);

                // Checkmark
                String check = "✔";
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(0, 0, 250.0f);
                int checkX = x + 17 - this.font.width(check);
                int checkY = y + 9;
                guiGraphics.drawString(this.font, check, checkX, checkY, 0x55FF55, true);
                guiGraphics.pose().popPose();
            }

            // --- BUBBLE SYSTEM ---
            long now = System.currentTimeMillis();
            List<Bubble> bubbles = slotBubbles.get(i);

// Spawn new bubbles — works whether filling or fully green
            boolean shouldSpawn = fillProgress > 0.05f; // only if there's something to bubble in
            if (shouldSpawn && now - lastBubbleTime > 120) { // spawn rate: every 120ms across all active slots
                if (bubbles.size() < 6 && Math.random() < 0.1) { // max 6 bubbles per slot
                    // Cube the random value to strongly bias toward the outer edges
                    double edgeBias = Math.pow(Math.random(), 2);
                    float bx;
                    if (Math.random() < 0.5) {
                        // Left side: x+1 to x+8, weighted toward x+1
                        bx = x + 1 + (float)(edgeBias * 7);
                    } else {
                        // Right side: x+8 to x+15, weighted toward x+15
                        bx = x + 8 + (float)((1.0 - edgeBias) * 7);
                    }
                    float startY;
                    if (fillProgress >= 1.0f) {
                        startY = y + 15; // fully filled: start from bottom
                    } else {
                        int baseFillTop = (y + 16) - (int)(fillProgress * 16);
                        startY = baseFillTop + 1 + (float)(Math.random() * ((y + 15) - baseFillTop));
                    }
                    float speed = 0.015f + (float)(Math.random() * 0.025f);
                    float alpha = 0.5f + (float)(Math.random() * 0.4f);
                    int size = Math.random() < 0.3 ? 2 : 1;
                    bubbles.add(new Bubble(bx, startY, speed, alpha, size));
                }
            }

// Only reset spawn timer once per frame across all slots
            if (i == 26) lastBubbleTime = now;

// Update + draw bubbles
            int baseFillTop = (y + 16) - (int)(fillProgress * 16);
            int ceilingY = (fillProgress >= 1.0f) ? y : baseFillTop;

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, 50.0f);
            RenderSystem.enableBlend();

            final float slotBubbleMult = 1.0f + bubbleSpeedMult[i] * 2.0f;

            // Update and cull
            bubbles.removeIf(b -> {
                b.y -= b.baseSpeed * slotBubbleMult;
                return b.y < ceilingY;
            });

            // Draw survivors
            for (Bubble b : bubbles) {
                float distToSurface = b.y - ceilingY;
                float fadeAlpha = Math.min(1.0f, distToSurface / 4.0f);
                int a = (int)(b.alpha * fadeAlpha * 255);
                if (a <= 0) continue;
                int color = (a << 24) | 0x00FFFFFF;
                guiGraphics.fill((int) b.x, (int) b.y, (int) b.x + b.size, (int) b.y + b.size, color);
            }



            RenderSystem.disableBlend();
            guiGraphics.pose().popPose();

            // --- HOVER HIGHLIGHT ---
            if (mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17) {
                if (!stackInSlot.isEmpty() || remaining > 0) {
                    guiGraphics.pose().pushPose();
                    guiGraphics.pose().translate(0, 0, 300.0f);
                    RenderSystem.enableBlend();
                    guiGraphics.fill(x, y, x + 16, y + 16, 0x55FFFFFF);
                    RenderSystem.disableBlend();
                    guiGraphics.pose().popPose();
                }
            }

        }

        // --- COMPLETION FLASH PASS (renders over everything) ---
        for (int i = 0; i < 27; i++) {
            if (completionFlash[i] <= 0f) continue;

            Slot slot = this.menu.slots.get(i);
            int reqIndex = slot.getSlotIndex();
            if (reqIndex >= cachedReqList.size()) continue;

            int x = this.leftPos + slot.x;
            int y = this.topPos + slot.y;

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, 400.0f); // above everything including items (Z=200) and text (Z=250)
            RenderSystem.enableBlend();

            // Full white flash fill — punchy initial pop
            int flashA = (int)(completionFlash[i] * 230);
            guiGraphics.fill(x, y, x + 16, y + 16, (flashA << 24) | 0x00FFFFFF);

            // Contracting bright green border ring
            float ringProgress = 1.0f - completionFlash[i]; // 0=just triggered, 1=fully faded
            int inset = (int)(ringProgress * 7);
            int ringA = (int)(completionFlash[i] * 255);
            int ringColor = (ringA << 24) | 0x0055FF55;

            if (inset < 7) {
                guiGraphics.fill(x + inset,     y + inset,      x + 16 - inset, y + inset + 2,  ringColor); // top
                guiGraphics.fill(x + inset,     y + 14 - inset, x + 16 - inset, y + 16 - inset, ringColor); // bottom
                guiGraphics.fill(x + inset,     y + inset,      x + inset + 2,  y + 16 - inset, ringColor); // left
                guiGraphics.fill(x + 14-inset,  y + inset,      x + 16 - inset, y + 16 - inset, ringColor); // right
            }

            RenderSystem.disableBlend();
            guiGraphics.pose().popPose();
        }

// 6. Finally, render the tooltips over everything else
// --- CUSTOM GHOST TOOLTIP ---
        for (int i = 0; i < 27; i++) {
            Slot slot = this.menu.slots.get(i);
            int reqIndex = slot.getSlotIndex();

            if (reqIndex >= cachedReqList.size()) continue;

            int x = this.leftPos + slot.x;
            int y = this.topPos + slot.y;

            // Check if mouse is hovering this slot
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                Item reqItem = cachedReqList.get(reqIndex).getKey();
                ItemStack stackInSlot = tempStacks[i];

                // Only show ghost tooltip if the slot is empty (no real item to show tooltip for)
                if (stackInSlot.isEmpty()) {
                    guiGraphics.renderTooltip(this.font, new ItemStack(reqItem), mouseX, mouseY);
                }
            }
        }

        renderChangeCounter(guiGraphics, mouseX, mouseY);

        renderTooltip(guiGraphics, mouseX, mouseY);
    }



    private Button toggleButton;
    private Button finalizeButton;


    @Override
    protected void init() {
        super.init();
        net.minecraft.core.BlockPos pos = this.menu.getBlockEntity().getBlockPos();
        

        slotBubbles.clear();
        for (int i = 0; i < 27; i++) {
            slotBubbles.add(new ArrayList<>());
        }

        int finalizeWidth = 62;
        int toggleWidth = 94;
        int spacing = 4;

        int totalWidth = finalizeWidth + toggleWidth + spacing;
        int startX = this.leftPos + (this.imageWidth / 2) - (totalWidth / 2) -3;
        int buttonY = this.topPos + this.imageHeight - 28;

// 1. Finalize Button
        this.finalizeButton = Button.builder(Component.literal("♦ Finalize"), (button) -> {
                    ModMessages.sendToServer(new FinalizeBuildPacket(pos));
                })
                .bounds(startX, buttonY, finalizeWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Commit the blueprint and place all blocks into the world.")))
                .build();
        Map<net.minecraft.world.item.Item, Integer> required = this.menu.getBlockEntity().getRequiredItems();
        boolean hasContent = required != null && !required.isEmpty();
        this.finalizeButton.active = !net.hydroset.buildpreviewer.PreviewManager.isInPreview(this.minecraft.player.getUUID())
                && this.menu.getBlockEntity().hasPendingChanges();
        this.addRenderableWidget(this.finalizeButton);

// 2. Toggle Button
        boolean inPreview = net.hydroset.buildpreviewer.PreviewManager.isInPreview(this.minecraft.player.getUUID());
        this.toggleButton = Button.builder(
                        inPreview ? Component.literal("⛏ Exit Builder") : Component.literal("⛏ Enter Builder"),
                        (button) -> {
                            ModMessages.sendToServer(new TogglePreviewPacket(pos));
                            this.onClose();
                        })
                .bounds(startX + finalizeWidth + spacing, buttonY, toggleWidth, 20)
                .tooltip(inPreview
                        ? Tooltip.create(Component.literal("Exit and return world back to normal."))
                        : Tooltip.create(Component.literal("Go into a creative builder mode to build a blueprint.")))
                .build();
        this.addRenderableWidget(this.toggleButton);

        // Mini buttons — placed to the left of the finalize button
        this.hologramButton = Button.builder(
                        Component.literal(net.hydroset.buildpreviewer.hologram.HologramRenderer.isHologramsEnabled() ? "◆" : "◇"),
                        (button) -> {
                            net.hydroset.buildpreviewer.hologram.HologramRenderer.setHologramsEnabled(
                                    !net.hydroset.buildpreviewer.hologram.HologramRenderer.isHologramsEnabled());
                        })
                .bounds(startX - 20 - 3 - 10, buttonY, 20, 20)
                .tooltip(Tooltip.create(Component.literal("Toggle holograms")))
                .build();
        this.addRenderableWidget(this.hologramButton);

        this.clearButton = Button.builder(
                        Component.literal("✕"),
                        (button) -> {
                            long now = System.currentTimeMillis();
                            if (now - clearBtnFirstClickTime < CLEAR_CONFIRM_TIMEOUT_MS) {
                                net.hydroset.buildpreviewer.networking.ModMessages.sendToServer(
                                        new net.hydroset.buildpreviewer.networking.ClearAllBlocksPacket(pos));
                                clearBtnFirstClickTime = 0L;
                                button.setMessage(Component.literal("✕"));
                            } else {
                                clearBtnFirstClickTime = now;
                                button.setMessage(Component.literal("§c✕"));
                            }
                        })
                .bounds(startX - 20 - 3 - 20 - 10 - 3, buttonY, 20, 20)
                .tooltip(Tooltip.create(Component.literal("Clear all changes")))
                .build();
        this.addRenderableWidget(this.clearButton);

        // Position the counter directly above the hologram/clear mini-buttons
        counterBarX = startX + finalizeWidth + spacing + toggleWidth + 11;
        counterBarW = 55;
        counterBarH = 20;
        counterBarY = buttonY;


    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (this.minecraft == null || this.minecraft.player == null || this.toggleButton == null) return;

        boolean inPreview = net.hydroset.buildpreviewer.PreviewManager.isInPreview(this.minecraft.player.getUUID());

        this.toggleButton.setMessage(
                inPreview ? Component.literal("⛏ Exit Builder") : Component.literal("⛏ Enter Builder")
        );
        this.toggleButton.setTooltip(inPreview
                ? Tooltip.create(Component.literal("Exit and return world back to normal."))
                : Tooltip.create(Component.literal("Go into a creative builder mode to build a blueprint."))
        );

        // Sync hologram button label
        if (this.hologramButton != null) {
            boolean hologramsOn = net.hydroset.buildpreviewer.hologram.HologramRenderer.isHologramsEnabled();
            this.hologramButton.setMessage(Component.literal(hologramsOn ? "◆" : "◇"));
            this.hologramButton.setTooltip(Tooltip.create(
                    Component.literal(hologramsOn ? "Hide holograms" : "Show holograms")));
        }

// Reset clear button label if confirmation timed out
        if (this.clearButton != null) {
            boolean clearPending = (System.currentTimeMillis() - clearBtnFirstClickTime) < CLEAR_CONFIRM_TIMEOUT_MS;
            if (!clearPending) {
                this.clearButton.setMessage(Component.literal("✕"));
                this.clearButton.setTooltip(Tooltip.create(Component.literal("Clear all changes")));
            } else {
                this.clearButton.setTooltip(Tooltip.create(Component.literal("§cAre you sure?")));
            }
        }

        // Disable finalize if in preview, or if there's nothing to finalize
        Map<Item, Integer> required = this.menu.getBlockEntity().getRequiredItems();
        boolean hasContent = required != null && !required.isEmpty();
        this.finalizeButton.active = !inPreview && this.menu.getBlockEntity().hasPendingChanges();    }
}