package net.hydroset.buildpreviewer.hologram;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.hydroset.buildpreviewer.hologram.HologramSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders semi-transparent hologram blocks on the client.
 *
 *  • Blue tint  — blocks the player PLACED in preview  (isPlaced = true)
 *  • Red  tint  — blocks the player BROKE  in preview  (isPlaced = false)
 *
 * The data is pushed from the server via {@link HologramSyncPacket}.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = net.hydroset.buildpreviewer.BuildPreviewer.MOD_ID)
public class HologramRenderer {

    // ── Tint colours ─────────────────────────────────────────────────────────
    // RGBA floats used as a GL blend colour (alpha drives transparency)
    private static final float PLACED_R = 0.3f, PLACED_G = 0.5f, PLACED_B = 1.0f, PLACED_A = 0.45f;
    private static final float BROKEN_R = 1.0f, BROKEN_G = 0.2f, BROKEN_B = 0.2f, BROKEN_A = 0.45f;

    // ── Hologram data (updated by the network packet) ─────────────────────────
    private static volatile Map<BlockPos, HologramSyncPacket.HologramEntry> holograms = Collections.emptyMap();

    /** Called from the network packet handler (client main thread). */
    public static void updateHolograms(Map<BlockPos, HologramSyncPacket.HologramEntry> newData) {
        holograms = new HashMap<>(newData);
    }

    /** Called from the local session cleanup so ghosts disappear when you exit preview. */
    public static void clear() {
        holograms = Collections.emptyMap();
    }

    // ── Render hook ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // AFTER_TRANSLUCENT_BLOCKS is a good stage — it draws on top of solid geometry
        // but before particles/weather, so the ghosts look "in the world".
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Map<BlockPos, HologramSyncPacket.HologramEntry> snapshot = holograms;
        if (snapshot.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        PoseStack poseStack = event.getPoseStack();
        BlockRenderDispatcher brd = mc.getBlockRenderer();

        // Camera offset — everything must be drawn relative to the camera position
        double camX = mc.gameRenderer.getMainCamera().getPosition().x;
        double camY = mc.gameRenderer.getMainCamera().getPosition().y;
        double camZ = mc.gameRenderer.getMainCamera().getPosition().z;

        // ── OpenGL state ─────────────────────────────────────────────────────
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);             // don't write to depth — stays see-through
        RenderSystem.disableCull();                // render back faces so hollow blocks look solid
        // Slight depth offset so the hologram sits just in front of any real block at same pos
        RenderSystem.enableDepthTest();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        for (Map.Entry<BlockPos, HologramSyncPacket.HologramEntry> entry : snapshot.entrySet()) {
            BlockPos pos = entry.getKey();
            HologramSyncPacket.HologramEntry hologram = entry.getValue();

            BlockState stateToRender;
            float r, g, b, a;

            if (hologram.isPlaced()) {
                // Blue ghost — show the placed block
                stateToRender = hologram.state();
                r = PLACED_R; g = PLACED_G; b = PLACED_B; a = PLACED_A;
            } else {
                // Red ghost — show what was there before (originalState stored in hologram.state())
                stateToRender = hologram.state();
                r = BROKEN_R; g = BROKEN_G; b = BROKEN_B; a = BROKEN_A;
            }

            // Skip air — nothing to render
            if (stateToRender == null || stateToRender.isAir()) continue;

            poseStack.pushPose();
            poseStack.translate(
                    pos.getX() - camX,
                    pos.getY() - camY,
                    pos.getZ() - camZ
            );

            // Apply tint via the overlay texture trick:
            //   We render to the TRANSLUCENT render type and supply a custom packed overlay
            //   that forces the desired colour. The easiest compatible approach is to use
            //   a custom RenderType with our colour, but for simplicity we use the
            //   existing translucent pass and control colour via RenderSystem.setShaderColor.
            RenderSystem.setShaderColor(r, g, b, a);

            try {
                brd.renderSingleBlock(
                        stateToRender,
                        poseStack,
                        bufferSource,
                        LightTexture.FULL_BRIGHT,   // always full-bright so ghosts are visible
                        OverlayTexture.NO_OVERLAY
                );
            } catch (Exception ignored) {
                // Safety: some modded block models crash if their level context is unusual
            }

            poseStack.popPose();
        }

        // Flush all queued geometry and restore state
        bufferSource.endBatch();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }
}