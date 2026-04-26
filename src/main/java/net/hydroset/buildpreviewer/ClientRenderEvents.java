package net.hydroset.buildpreviewer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.hydroset.buildpreviewer.block.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.joml.Matrix4f;

@Mod.EventBusSubscriber(modid = BuildPreviewer.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientRenderEvents {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BUILDACCESS_BLOCK.get(),
                renderType -> renderType == RenderType.solid() || renderType == RenderType.translucent());
    }

    @SubscribeEvent
    public static void onRenderOutline(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        if (PreviewManager.isInPreview(player.getUUID())) {
            BlockPos anchorPos = PreviewManager.getAnchorPos(player.getUUID());
            if (anchorPos == null) return;

            // 1. Calculate distance for scaling
            double distSq = player.distanceToSqr(anchorPos.getX(), anchorPos.getY(), anchorPos.getZ());
            double dist = Math.sqrt(distSq);

            // 2. Dynamic thickness:
            // Base thickness (0.02) multiplied by a factor of the distance.
            // We use Math.max to ensure it never disappears when you're standing on it.
            float thickness = (float) Math.max(0.02f, (dist * 0.005f));

            PoseStack poseStack = event.getPoseStack();
            double camX = event.getCamera().getPosition().x;
            double camY = event.getCamera().getPosition().y;
            double camZ = event.getCamera().getPosition().z;

            poseStack.pushPose();
            poseStack.translate(anchorPos.getX() - camX, anchorPos.getY() - camY, anchorPos.getZ() - camZ);

            var outlineBuffers = Minecraft.getInstance().renderBuffers().outlineBufferSource();
            outlineBuffers.setColor(165, 246, 59, 255);

            VertexConsumer buffer = outlineBuffers.getBuffer(RenderType.outline(
                    new net.minecraft.resources.ResourceLocation("textures/misc/white.png")));

            // 3. Pass the new dynamic thickness
            drawSpectralLines(poseStack, buffer, 1.0F, 0.6F, 0.0F, 1.0F, thickness);

            poseStack.popPose();
            outlineBuffers.endOutlineBatch();
        }
    }

    private static void drawSpectralLines(PoseStack poseStack, VertexConsumer buffer, float r, float g, float b, float a, float t) {
        Matrix4f matrix = poseStack.last().pose();

        // Bottom 4 edges
        drawBox(matrix, buffer, 0, 0, 0, 1, t, t, r, g, b, a);
        drawBox(matrix, buffer, 0, 0, 1-t, 1, t, t, r, g, b, a);
        drawBox(matrix, buffer, 0, 0, 0, t, t, 1, r, g, b, a);
        drawBox(matrix, buffer, 1-t, 0, 0, t, t, 1, r, g, b, a);

        // Top 4 edges
        drawBox(matrix, buffer, 0, 1-t, 0, 1, t, t, r, g, b, a);
        drawBox(matrix, buffer, 0, 1-t, 1-t, 1, t, t, r, g, b, a);
        drawBox(matrix, buffer, 0, 1-t, 0, t, t, 1, r, g, b, a);
        drawBox(matrix, buffer, 1-t, 1-t, 0, t, t, 1, r, g, b, a);

        // 4 Vertical Pillars
        drawBox(matrix, buffer, 0, 0, 0, t, 1, t, r, g, b, a);
        drawBox(matrix, buffer, 1-t, 0, 0, t, 1, t, r, g, b, a);
        drawBox(matrix, buffer, 0, 0, 1-t, t, 1, t, r, g, b, a);
        drawBox(matrix, buffer, 1-t, 0, 1-t, t, 1, t, r, g, b, a);
    }

    private static void drawBox(Matrix4f matrix, VertexConsumer buffer, float x, float y, float z, float width, float height, float depth, float r, float g, float b, float a) {
        float x2 = x + width;
        float y2 = y + height;
        float z2 = z + depth;

        // We draw the 6 faces of a tiny stick. The Outline shader uses these
        // surfaces to determine the "glow" area.

        // Bottom
        buffer.vertex(matrix, x, y, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y, z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x, y, z2).color(r, g, b, a).endVertex();
        // Top
        buffer.vertex(matrix, x, y2, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x, y2, z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y2, z).color(r, g, b, a).endVertex();
        // North
        buffer.vertex(matrix, x, y, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x, y2, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y2, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y, z).color(r, g, b, a).endVertex();
        // South
        buffer.vertex(matrix, x, y, z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y, z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x, y2, z2).color(r, g, b, a).endVertex();
        // West
        buffer.vertex(matrix, x, y, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x, y, z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x, y2, z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x, y2, z).color(r, g, b, a).endVertex();
        // East
        buffer.vertex(matrix, x2, y, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y2, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y, z2).color(r, g, b, a).endVertex();
    }
}

