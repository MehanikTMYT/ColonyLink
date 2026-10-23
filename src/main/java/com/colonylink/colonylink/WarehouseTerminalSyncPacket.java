package com.colonylink.colonylink;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * S->C: full Warehouse snapshot to the client, with an optional error message
 * (e.g. "No colony found").
 *
 * The client handler lives in TerminalClientPacketHandler (@OnlyIn(CLIENT)) to keep
 * RuntimeDistCleaner from crashing the dedicated server.
 */
public record WarehouseTerminalSyncPacket(
        List<WarehouseItemEntry> entries,
        boolean hasWarehouseCard,
        BlockPos terminalPos,
        String errorMessage
) implements CustomPacketPayload
{
    public record WarehouseItemEntry(ItemStack stack, long count) {}

    public static final Identifier ID = Identifier.fromNamespaceAndPath(
            ColonyLink.MODID, "warehouse_terminal_sync");
    public static final CustomPacketPayload.Type<WarehouseTerminalSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, WarehouseTerminalSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeInt(p.entries().size());
                        for (var e : p.entries())
                        {
                            ItemStack.STREAM_CODEC.encode(buf, e.stack());
                            buf.writeLong(e.count());
                        }
                        buf.writeBoolean(p.hasWarehouseCard());
                        buf.writeBlockPos(p.terminalPos());
                        buf.writeUtf(p.errorMessage());
                    },
                    buf -> {
                        int size = buf.readInt();
                        List<WarehouseItemEntry> entries = new ArrayList<>();
                        for (int i = 0; i < size; i++)
                            entries.add(new WarehouseItemEntry(
                                    ItemStack.STREAM_CODEC.decode(buf), buf.readLong()));
                        boolean hasCard = buf.readBoolean();
                        BlockPos pos    = buf.readBlockPos();
                        String err      = buf.readUtf();
                        return new WarehouseTerminalSyncPacket(entries, hasCard, pos, err);
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public boolean hasError() { return errorMessage != null && !errorMessage.isEmpty(); }
}