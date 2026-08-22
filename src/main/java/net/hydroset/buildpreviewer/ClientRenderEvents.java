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

        double camX = event.getCamera().getPosition().x;
        double camY = event.getCamera().getPosition().y;
        double camZ = event.getCamera().getPosition().z;

        PoseStack poseStack = event.getPoseStack();

        boolean inPreview = PreviewManager.isInPreview(player.getUUID());
        BlockPos anchorPos = inPreview ? PreviewManager.getAnchorPos(player.getUUID()) : null;

        boolean hologramsOn = net.hydroset.buildpreviewer.hologram.HologramRenderer.isHologramsEnabled();
        java.util.Map<BlockPos, net.hydroset.buildpreviewer.hologram.HologramSyncPacket.HologramEntry> holograms =
                hologramsOn ? net.hydroset.buildpreviewer.hologram.HologramRenderer.getHolograms() : java.util.Collections.emptyMap();

        // Every position getting an outline this frame — used so two touching
        // outlined blocks don't each draw a rim face along their shared internal
        // boundary, which is what caused the z-fighting/flicker artifact.
        java.util.Set<BlockPos> outlinedPositions = new java.util.HashSet<>(holograms.keySet());
        if (anchorPos != null) outlinedPositions.add(anchorPos);

        if (anchorPos != null) {
            renderBlockRim(poseStack, camX, camY, camZ, anchorPos, outlinedPositions, 0.65f, 0.96f, 0.23f, 1f);
        }

        for (var entry : holograms.entrySet()) {
            BlockPos pos = entry.getKey();
            boolean placed = entry.getValue().isPlaced();
            if (placed) {
                renderBlockRim(poseStack, camX, camY, camZ, pos, outlinedPositions, 0.75f, 1.00f, 1.00f, 1f);
            } else {
                renderBlockRim(poseStack, camX, camY, camZ, pos, outlinedPositions, 1.00f, 0.50f, 0.50f, 1f);
            }
        }
    }

    private static void renderBlockRim(PoseStack poseStack, double camX, double camY, double camZ,
                                       BlockPos anchorPos, java.util.Set<BlockPos> outlinedPositions,
                                       float r, float g, float b, float a) {
        poseStack.pushPose();
        poseStack.translate(anchorPos.getX() - camX, anchorPos.getY() - camY, anchorPos.getZ() - camZ);

        // True, unexpanded cube matrix — captured BEFORE the expand scale.
        Matrix4f trueMatrix = new Matrix4f(poseStack.last().pose());

        float expand = 0.07f;
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.scale(1.0f + expand, 1.0f + expand, 1.0f + expand);
        poseStack.translate(-0.5, -0.5, -0.5);
        Matrix4f matrix = poseStack.last().pose();

        boolean skipDown  = outlinedPositions.contains(anchorPos.below());
        boolean skipUp    = outlinedPositions.contains(anchorPos.above());
        boolean skipNorth = outlinedPositions.contains(anchorPos.north());
        boolean skipSouth = outlinedPositions.contains(anchorPos.south());
        boolean skipWest  = outlinedPositions.contains(anchorPos.west());
        boolean skipEast  = outlinedPositions.contains(anchorPos.east());

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableCull();
        GL11.glCullFace(GL11.GL_FRONT);

        RenderSystem.depthMask(true);
        RenderSystem.colorMask(false, false, false, false);

        // --- Depth pre-pass, layer 1 --- force-write the expanded cube's depth,
        // ignoring whatever's already there (terrain, walls) — this is what
        // makes the rim visible through the ground/walls.
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        Tesselator tess1 = Tesselator.getInstance();
        BufferBuilder buf1 = tess1.getBuilder();
        buf1.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        if (!skipDown)  drawFaceDown(matrix, buf1, 1, 1, 1, 1);
        if (!skipUp)    drawFaceUp(matrix, buf1, 1, 1, 1, 1);
        if (!skipNorth) drawFaceNorth(matrix, buf1, 1, 1, 1, 1);
        if (!skipSouth) drawFaceSouth(matrix, buf1, 1, 1, 1, 1);
        if (!skipWest)  drawFaceWest(matrix, buf1, 1, 1, 1, 1);
        if (!skipEast)  drawFaceEast(matrix, buf1, 1, 1, 1, 1);
        tess1.end();
        RenderSystem.disableCull();

        // --- Depth pre-pass, layer 2 --- carve out the block's own true footprint
        // by writing the TRUE (unexpanded) cube's depth back on top wherever it's
        // nearer, leaving only the thin rim sliver from layer 1.
        RenderSystem.depthFunc(GL11.GL_LESS);
        Tesselator tess2 = Tesselator.getInstance();
        BufferBuilder buf2 = tess2.getBuilder();
        buf2.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        if (!skipDown)  drawFaceDown(trueMatrix, buf2, 1, 1, 1, 1);
        if (!skipUp)    drawFaceUp(trueMatrix, buf2, 1, 1, 1, 1);
        if (!skipNorth) drawFaceNorth(trueMatrix, buf2, 1, 1, 1, 1);
        if (!skipSouth) drawFaceSouth(trueMatrix, buf2, 1, 1, 1, 1);
        if (!skipWest)  drawFaceWest(trueMatrix, buf2, 1, 1, 1, 1);
        if (!skipEast)  drawFaceEast(trueMatrix, buf2, 1, 1, 1, 1);
        tess2.end();

        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.colorMask(true, true, true, true);

        // --- Draw the shell ---
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);

        RenderSystem.polygonOffset(-1.0f, -1.0f);
        RenderSystem.enablePolygonOffset();

        RenderSystem.enableCull();
        GL11.glCullFace(GL11.GL_FRONT);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        if (!skipDown)  drawFaceDown(matrix, buffer, r, g, b, a);
        if (!skipUp)    drawFaceUp(matrix, buffer, r, g, b, a);
        if (!skipNorth) drawFaceNorth(matrix, buffer, r, g, b, a);
        if (!skipSouth) drawFaceSouth(matrix, buffer, r, g, b, a);
        if (!skipWest)  drawFaceWest(matrix, buffer, r, g, b, a);
        if (!skipEast)  drawFaceEast(matrix, buffer, r, g, b, a);

        tesselator.end();
        RenderSystem.disablePolygonOffset();

        GL11.glCullFace(GL11.GL_BACK);
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