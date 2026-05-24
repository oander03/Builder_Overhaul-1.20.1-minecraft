package net.hydroset.buildpreviewer.hologram;

import net.hydroset.buildpreviewer.hologram.HologramRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sent server → client to update the hologram overlay.
 *
 * Each entry carries:
 *   - the BlockPos
 *   - the BlockState to render (AIR means "this block was broken")
 *   - a boolean: true = placed (blue tint), false = broken (red tint)
 */
public class HologramSyncPacket {

    /** pos → (state, isPlaced).  isPlaced=false means a break hologram. */
    private final Map<BlockPos, HologramEntry> entries;

    public record HologramEntry(BlockState state, boolean isPlaced) {}

    // ── Constructors ────────────────────────────────────────────────────────

    /** Called on the server side to build the packet. */
    public HologramSyncPacket(Map<BlockPos, HologramEntry> entries) {
        this.entries = entries;
    }
    

    // ── Codec helpers ────────────────────────────────────────────────────────

    public static HologramSyncPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<BlockPos, HologramEntry> map = new HashMap<>(size);

        for (int i = 0; i < size; i++) {
            BlockPos pos = buf.readBlockPos();
            // BlockState is stored as its raw state-id integer
            int stateId = buf.readVarInt();
            BlockState state = net.minecraft.world.level.block.Block.stateById(stateId);
            boolean placed = buf.readBoolean();
            map.put(pos, new HologramEntry(state, placed));
        }

        return new HologramSyncPacket(map);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Map.Entry<BlockPos, HologramEntry> e : entries.entrySet()) {
            buf.writeBlockPos(e.getKey());
            buf.writeVarInt(net.minecraft.world.level.block.Block.getId(e.getValue().state()));
            buf.writeBoolean(e.getValue().isPlaced());
        }
    }

    // ── Handler (runs on client) ─────────────────────────────────────────────

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Runs on the client main thread — safe to touch client-only classes
            HologramRenderer.updateHolograms(this.entries);
        });
        ctx.get().setPacketHandled(true);
    }

    public Map<BlockPos, HologramEntry> getEntries() {
        return entries;
    }
}