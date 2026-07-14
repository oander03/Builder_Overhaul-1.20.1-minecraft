package net.hydroset.buildpreviewer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.hydroset.buildpreviewer.block.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

@Mod.EventBusSubscriber(modid = BuildPreviewer.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientRenderEvents {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BUILDACCESS_BLOCK.get(),
                renderType -> renderType == RenderType.solid());
    }

    @SubscribeEvent
    public static void onRenderOutline(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (!PreviewManager.isInPreview(player.getUUID())) return;

        BlockPos anchorPos = PreviewManager.getAnchorPos(player.getUUID());
        if (anchorPos == null) return;

        double camX = event.getCamera().getPosition().x;
        double camY = event.getCamera().getPosition().y;
        double camZ = event.getCamera().getPosition().z;

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(anchorPos.getX() - camX, anchorPos.getY() - camY, anchorPos.getZ() - camZ);

        // True, unexpanded cube matrix — captured BEFORE the expand scale.
        Matrix4f trueMatrix = new Matrix4f(poseStack.last().pose());

        // Expand outward for the shell. Captured now so both the pre-pass
        // and the final shell draw use the exact same expanded matrix.
        float expand = 0.06f;
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.scale(1.0f + expand, 1.0f + expand, 1.0f + expand);
        poseStack.translate(-0.5, -0.5, -0.5);
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableCull();
        GL11.glCullFace(GL11.GL_FRONT); // layer 1 must match the shell: far faces only

        RenderSystem.depthMask(true);
        RenderSystem.colorMask(false, false, false, false);

        // --- Depth pre-pass, layer 1 ---
        // Force-write the EXPANDED cube's depth everywhere, ignoring what's
        // already in the depth buffer. This guarantees the rim area always
        // beats neighboring blocks, regardless of which is physically closer.
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        Tesselator tess1 = Tesselator.getInstance();
        BufferBuilder buf1 = tess1.getBuilder();
        buf1.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        drawFaceDown(matrix, buf1, 1, 1, 1, 1);
        drawFaceUp(matrix, buf1, 1, 1, 1, 1);
        drawFaceNorth(matrix, buf1, 1, 1, 1, 1);
        drawFaceSouth(matrix, buf1, 1, 1, 1, 1);
        drawFaceWest(matrix, buf1, 1, 1, 1, 1);
        drawFaceEast(matrix, buf1, 1, 1, 1, 1);
        tess1.end();
        RenderSystem.disableCull(); // layer 2 needs all faces for a correct nearest-depth test


        // --- Depth pre-pass, layer 2 ---
        // Write the TRUE (unexpanded) cube's depth back on top, but only
        // where it's actually closer than what layer 1 just wrote. This
        // carves out the block's own footprint, so the shell's far faces
        // get correctly hidden over the real block's own near faces —
        // leaving only the thin rim sliver from layer 1 exposed.
        RenderSystem.depthFunc(GL11.GL_LESS);
        Tesselator tess2 = Tesselator.getInstance();
        BufferBuilder buf2 = tess2.getBuilder();
        buf2.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        drawFaceDown(trueMatrix, buf2, 1, 1, 1, 1);
        drawFaceUp(trueMatrix, buf2, 1, 1, 1, 1);
        drawFaceNorth(trueMatrix, buf2, 1, 1, 1, 1);
        drawFaceSouth(trueMatrix, buf2, 1, 1, 1, 1);
        drawFaceWest(trueMatrix, buf2, 1, 1, 1, 1);
        drawFaceEast(trueMatrix, buf2, 1, 1, 1, 1);
        tess2.end();

        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.colorMask(true, true, true, true);

        // --- Draw the shell ---
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);

        float r = 0.65f, g = 0.96f, b = 0.23f, a = 1f;

        RenderSystem.polygonOffset(-1.0f, -1.0f);
        RenderSystem.enablePolygonOffset();

        RenderSystem.enableCull();
        GL11.glCullFace(GL11.GL_FRONT); // keep only far-side faces

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        drawFaceDown(matrix, buffer, r, g, b, a);
        drawFaceUp(matrix, buffer, r, g, b, a);
        drawFaceNorth(matrix, buffer, r, g, b, a);
        drawFaceSouth(matrix, buffer, r, g, b, a);
        drawFaceWest(matrix, buffer, r, g, b, a);
        drawFaceEast(matrix, buffer, r, g, b, a);

        tesselator.end();
        RenderSystem.disablePolygonOffset();

        GL11.glCullFace(GL11.GL_BACK); // restore default
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        poseStack.popPose();
    }

    private static void drawFaceDown(Matrix4f matrix, VertexConsumer buffer, float r, float g, float b, float a) {
        buffer.vertex(matrix, 0, 0, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 1, 0, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 1, 0, 1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 0, 0, 1).color(r, g, b, a).endVertex();
    }

    private static void drawFaceUp(Matrix4f matrix, VertexConsumer buffer, float r, float g, float b, float a) {
        buffer.vertex(matrix, 0, 1, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 0, 1, 1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 1, 1, 1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 1, 1, 0).color(r, g, b, a).endVertex();
    }

    private static void drawFaceNorth(Matrix4f matrix, VertexConsumer buffer, float r, float g, float b, float a) {
        buffer.vertex(matrix, 0, 0, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 0, 1, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 1, 1, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 1, 0, 0).color(r, g, b, a).endVertex();
    }

    private static void drawFaceSouth(Matrix4f matrix, VertexConsumer buffer, float r, float g, float b, float a) {
        buffer.vertex(matrix, 0, 0, 1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 1, 0, 1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 1, 1, 1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 0, 1, 1).color(r, g, b, a).endVertex();
    }

    private static void drawFaceWest(Matrix4f matrix, VertexConsumer buffer, float r, float g, float b, float a) {
        buffer.vertex(matrix, 0, 0, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 0, 0, 1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 0, 1, 1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 0, 1, 0).color(r, g, b, a).endVertex();
    }

    private static void drawFaceEast(Matrix4f matrix, VertexConsumer buffer, float r, float g, float b, float a) {
        buffer.vertex(matrix, 1, 0, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 1, 1, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 1, 1, 1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, 1, 0, 1).color(r, g, b, a).endVertex();
    }
}