package com.colonylink.colonylink;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server packet fired when the player clicks "Craft" on an item whose
 * components are available in the Warehouse (active snapshot).
 *
 * Two cases:
 * - isDomum = false: extract warehouse components -> inject into ME -> start AE2 craft
 * - isDomum = true : extract warehouse components -> insert into redirector buffer (virtual DO craft)
 */
public record WarehouseCraftPacket(
        ItemStack stack,
        int realCount,
        boolean isDomum,
        BlockPos redirectorPos
) implements CustomPacketPayload
{
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            ColonyLink.MODID, "warehouse_craft");
    public static final CustomPacketPayload.Type<WarehouseCraftPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, WarehouseCraftPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                ItemStack.STREAM_CODEC.encode(buf, packet.stack());
                buf.writeInt(packet.realCount());
                buf.writeBoolean(packet.isDomum());
                buf.writeBlockPos(packet.redirectorPos());
            },
            buf -> new WarehouseCraftPacket(
                    ItemStack.STREAM_CODEC.decode(buf),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readBlockPos()
            )
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public static void handle(WarehouseCraftPacket packet, IPayloadContext context)
    {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;
            WarehouseCraftHandler.handleWarehouseCraft(
                    serverPlayer,
                    packet.stack(),
                    packet.realCount(),
                    packet.isDomum(),
                    packet.redirectorPos()
            );
        });
    }
}