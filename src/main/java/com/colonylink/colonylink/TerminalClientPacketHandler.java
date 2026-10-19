package com.colonylink.colonylink;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * CLIENT-side handlers for the Warehouse Link Terminal packets.
 *
 * Kept in this @OnlyIn(CLIENT) class so RuntimeDistCleaner does not crash the
 * dedicated server when it scans the bytecode of the S→C packets.
 *
 * The packets' handle() methods (WarehouseTerminalSyncPacket, TerminalMeSyncPacket)
 * must NOT reference Screen or its subclasses directly; they delegate here via
 * registerPayloads() on the client only.
 */
@OnlyIn(Dist.CLIENT)
public class TerminalClientPacketHandler
{
    public static void handleWarehouseSync(WarehouseTerminalSyncPacket packet, IPayloadContext ctx)
    {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof WarehouseLinkTerminalScreen screen)
                screen.updateWarehouseSnapshot(packet);
        });
    }

    public static void handleMeSync(TerminalMeSyncPacket packet, IPayloadContext ctx)
    {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof WarehouseLinkTerminalScreen screen)
                screen.updateMeSnapshot(packet);
        });
    }

    public static void onDomumQueueSync(java.util.List<net.minecraft.world.item.ItemStack> queue)
    {
        // Called from DomumQueueSyncPacket.handle() (already on the client thread via enqueueWork)
        if (Minecraft.getInstance().screen instanceof WarehouseLinkTerminalScreen screen)
            screen.updateDomumQueue(queue);
    }

    public static void handleDomumQueueSync(DomumQueueSyncPacket packet, net.neoforged.neoforge.network.handling.IPayloadContext ctx)
    {
        ctx.enqueueWork(() -> onDomumQueueSync(packet.queue()));
    }
}