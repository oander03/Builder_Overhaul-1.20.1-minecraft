package net.hydroset.buildpreviewer.hologram;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;

import java.util.*;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = net.hydroset.buildpreviewer.BuildPreviewer.MOD_ID)
public class HologramRenderer {

    private static final float PLACED_R = 0.35f, PLACED_G = 0.55f, PLACED_B = 1.00f, PLACED_A = 0.55f;
    private static final float BROKEN_R = 1.00f, BROKEN_G = 0.20f, BROKEN_B = 0.20f, BROKEN_A = 0.55f;
    private static final float SCALE = 1.003f;

    private static volatile Map<BlockPos, HologramSyncPacket.HologramEntry> holograms = Collections.emptyMap();

    public static void updateHolograms(Map<BlockPos, HologramSyncPacket.HologramEntry> newData) {
        holograms = new HashMap<>(newData);
    }

    public static void clear() {
        holograms = Collections.emptyMap();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Map<BlockPos, HologramSyncPacket.HologramEntry> snapshot = holograms;
        if (snapshot.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        PoseStack poseStack = event.getPoseStack();
        BlockRenderDispatcher brd = mc.getBlockRenderer();

        double camX = mc.gameRenderer.getMainCamera().getPosition().x;
        double camY = mc.gameRenderer.getMainCamera().getPosition().y;
        double camZ = mc.gameRenderer.getMainCamera().getPosition().z;

        // Add these two variables BEFORE the loop, after camX/Y/Z
        double playerX = mc.player.getX();
        double playerY = mc.player.getEyeY();
        double playerZ = mc.player.getZ();

// Sort entries back-to-front so far holograms render first
        List<Map.Entry<BlockPos, HologramSyncPacket.HologramEntry>> entries =
                new ArrayList<>(snapshot.entrySet());
        entries.sort((a2, b2) -> {
            double dA = a2.getKey().distToCenterSqr(playerX, playerY, playerZ);
            double dB = b2.getKey().distToCenterSqr(playerX, playerY, playerZ);
            return Double.compare(dB, dA); // far first
        });

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);

        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-2f, -2f);

        MultiBufferSource.BufferSource realSource = mc.renderBuffers().bufferSource();

        for (Map.Entry<BlockPos, HologramSyncPacket.HologramEntry> entry : entries) {
            BlockPos pos = entry.getKey();
            HologramSyncPacket.HologramEntry hologram = entry.getValue();

            BlockState stateToRender = hologram.state();
            if (stateToRender == null || stateToRender.isAir()) continue;

            float r, g, b, a;
            if (hologram.isPlaced()) {
                r = PLACED_R; g = PLACED_G; b = PLACED_B; a = PLACED_A;
            } else {
                r = BROKEN_R; g = BROKEN_G; b = BROKEN_B; a = BROKEN_A;
            }

            // ── Determine the 3 camera-visible faces in world space ───────────
            // The camera can only see one face per axis. We pick based on which
            // side of the block centre the camera sits on.



            Set<Direction> allowedFaces = EnumSet.allOf(Direction.class);




            poseStack.pushPose();
            poseStack.translate(pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ);
            poseStack.translate(0.5, 0.5, 0.5);
            poseStack.scale(SCALE, SCALE, SCALE);
            poseStack.translate(-0.5, -0.5, -0.5);

            TintedBufferSource tinted = new TintedBufferSource(realSource, r, g, b, a, allowedFaces);

            try {
                brd.renderSingleBlock(stateToRender, poseStack, tinted,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            } catch (Exception ignored) {}

            realSource.endBatch();
            poseStack.popPose();
        }

        GL11.glPolygonOffset(0f, 0f);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    // ── TintedBufferSource ────────────────────────────────────────────────────

    private static class TintedBufferSource implements MultiBufferSource {
        private final MultiBufferSource inner;
        private final float r, g, b, a;
        private final Set<Direction> allowedFaces;

        TintedBufferSource(MultiBufferSource inner, float r, float g, float b, float a,
                           Set<Direction> allowedFaces) {
            this.inner = inner;
            this.r = r; this.g = g; this.b = b; this.a = a;
            this.allowedFaces = allowedFaces;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            return new TintedVertexConsumer(inner.getBuffer(renderType), r, g, b, a, allowedFaces);
        }
    }

    // ── TintedVertexConsumer ──────────────────────────────────────────────────

    private static class TintedVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float tr, tg, tb, ta;
        private final Set<Direction> allowedFaces;

        private static final int QUAD_SIZE = 4;
        private final float[][] vx  = new float[QUAD_SIZE][3];
        private final float[][] vc  = new float[QUAD_SIZE][4];
        private final float[][] vuv = new float[QUAD_SIZE][2];
        private final int[][]   vOv = new int[QUAD_SIZE][2];
        private final int[][]   vLt = new int[QUAD_SIZE][2];
        private final float[][] vN  = new float[QUAD_SIZE][3];
        private int vi = 0;
        private boolean hasNormal = false;

        TintedVertexConsumer(VertexConsumer delegate, float r, float g, float b, float a,
                             Set<Direction> allowedFaces) {
            this.delegate = delegate;
            this.tr = r; this.tg = g; this.tb = b; this.ta = a;
            this.allowedFaces = allowedFaces;
        }

        @Override public VertexConsumer vertex(double x, double y, double z) {
            vx[vi][0]=(float)x; vx[vi][1]=(float)y; vx[vi][2]=(float)z; return this;
        }
        @Override public VertexConsumer color(int red, int green, int blue, int alpha) {
            vc[vi][0]=clamp((int)(red*tr))/255f;   vc[vi][1]=clamp((int)(green*tg))/255f;
            vc[vi][2]=clamp((int)(blue*tb))/255f;  vc[vi][3]=clamp((int)(alpha*ta))/255f;
            return this;
        }
        @Override public VertexConsumer uv(float u, float v)              { vuv[vi][0]=u; vuv[vi][1]=v; return this; }
        @Override public VertexConsumer overlayCoords(int u, int v)       { vOv[vi][0]=u; vOv[vi][1]=v; return this; }
        @Override public VertexConsumer uv2(int u, int v)                 { vLt[vi][0]=u; vLt[vi][1]=v; return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { vN[vi][0]=x; vN[vi][1]=y; vN[vi][2]=z; hasNormal=true; return this; }
        @Override public void endVertex() { vi++; if (vi==QUAD_SIZE) { flushQuad(); vi=0; hasNormal=false; } }

        private void flushQuad() {
            // Use e2×e1 instead of e1×e2 — Minecraft quads are wound CW from outside,
            // so the standard cross product gives an inward normal. Swap to get outward.
            float e1x=vx[1][0]-vx[0][0], e1y=vx[1][1]-vx[0][1], e1z=vx[1][2]-vx[0][2];
            float e2x=vx[2][0]-vx[0][0], e2y=vx[2][1]-vx[0][1], e2z=vx[2][2]-vx[0][2];
            float nx=e2y*e1z-e2z*e1y, ny=e2z*e1x-e2x*e1z, nz=e2x*e1y-e2y*e1x;
            if (nx==0&&ny==0&&nz==0) return;

            Direction facing = dirFromNormal(nx, ny, nz);
            if (facing == null) return;
            if (!allowedFaces.contains(facing)) return;

            for (int i=0; i<QUAD_SIZE; i++) {
                delegate.vertex(vx[i][0], vx[i][1], vx[i][2]);
                delegate.color((int)(vc[i][0]*255),(int)(vc[i][1]*255),
                        (int)(vc[i][2]*255),(int)(vc[i][3]*255));
                delegate.uv(vuv[i][0], vuv[i][1]);
                delegate.overlayCoords(vOv[i][0], vOv[i][1]);
                delegate.uv2(vLt[i][0], vLt[i][1]);
                if (hasNormal) delegate.normal(vN[i][0], vN[i][1], vN[i][2]);
                delegate.endVertex();
            }
        }

        // Map the dominant axis of a normal to a Direction.
        // This works correctly even after rotation because we only care about
        // which axis is largest — and block faces are always axis-aligned.
        private static Direction dirFromNormal(float nx, float ny, float nz) {
            float ax=Math.abs(nx), ay=Math.abs(ny), az=Math.abs(nz);
            if (ay>=ax&&ay>=az) return ny>0 ? Direction.UP    : Direction.DOWN;
            if (ax>=az)         return nx>0 ? Direction.EAST   : Direction.WEST;
            return                     nz>0 ? Direction.SOUTH  : Direction.NORTH;
        }

        private static int clamp(int v) { return Math.max(0,Math.min(255,v)); }

        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() { delegate.unsetDefaultColor(); }
    }
}