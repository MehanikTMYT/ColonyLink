package com.colonylink.colonylink;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;

/**
 * DomumQueuePacket — v1.4.8
 *
 * Sent C→S when the player clicks "No Pattern" on a Domum item in the Clipboard.
 * The server adds the item to the queue of the WarehouseLinkTerminalPart linked to
 * the Redirector.
 *
 * redirectorPos: position of the Redirector the Domum request came from.
 * domumStack:    the exact Domum item (block + full DataComponents).
 */
public record DomumQueuePacket(
        BlockPos  redirectorPos,
        ItemStack domumStack
) implements CustomPacketPayload
{
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(ColonyLink.MODID, "domum_queue");
    public static final CustomPacketPayload.Type<DomumQueuePacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, DomumQueuePacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeBlockPos(packet.redirectorPos());
                        ItemStack.STREAM_CODEC.encode(buf, packet.domumStack());
                    },
                    buf -> new DomumQueuePacket(
                            buf.readBlockPos(),
                            ItemStack.STREAM_CODEC.decode(buf)
                    )
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(DomumQueuePacket packet, IPayloadContext ctx)
    {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) handleServer(sp, packet);
        });
    }

    private static void handleServer(ServerPlayer player, DomumQueuePacket packet)
    {
        ColonyLink.LOGGER.debug("[DomumQueue] Received packet from {} — item: {}",
                player.getName().getString(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(packet.domumStack().getItem()));

        if (!DomumCraftHandler.isDomumItem(packet.domumStack()))
        {
            player.sendSystemMessage(Component.translatable("colonylink.domum_queue.invalid",
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(packet.domumStack().getItem()).toString()));
            return;
        }

        ServerLevel level = player.serverLevel();

        // A3 — redirector↔wand validation (client is NOT authoritative on redirectorPos).
        // Only accept a redirectorPos actually linked to THIS player's wand. Same predicate
        // and dimension guard as CancelRequestPacket. Both known senders are in the Clipboard
        // screen, so the wand is in the player's inventory or Curios (findWandInInventory
        // covers both).
        ItemStack wand = ColonyLinkServerTicker.findWandInInventory(player);
        if (wand == null)
        {
            player.sendSystemMessage(Component.translatable("colonylink.domum_queue.invalid",
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(packet.domumStack().getItem()).toString()));
            return;
        }
        boolean redirectorLinked = false;
        BuilderEntry linkedEntry = null; // 1.7.0-beta.4 — resolves the Redirector by UUID
        for (BuilderEntry e : ColonyLinkWandLinkableHandler.getBuilderEntries(wand))
        {
            if (e.hasRedirector() && e.redirectorPos().equals(packet.redirectorPos()))
            {
                // Dimension guard: refuse a cross-dimension redirector. Legacy entries
                // (dimension == null) are not filtered — historical behaviour preserved.
                if (e.dimension() != null && !e.dimension().equals(level.dimension()))
                    break;
                redirectorLinked = true;
                linkedEntry = e;
                break;
            }
        }
        if (!redirectorLinked)
        {
            player.sendSystemMessage(Component.translatable("colonylink.domum_queue.invalid",
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(packet.domumStack().getItem()).toString()));
            return;
        }

        // v1.6.1 — Resolve the Redirector's ME GRID (via redirectorPos) and add the item to
        // that grid's SHARED Domum queue (DomumQueueManager). The item then shows on ALL
        // terminals of the network, not just one. The shared queue is the source of truth;
        // each terminal keeps an NBT copy as a persistence net.
        IGrid redirectorGrid = null;
        if (RedirectorRegistry.forEntry(level, linkedEntry) instanceof ColonyLinkRedirectorBlockEntity redirector)
        {
            IGridNode rnode = redirector.getActionableNode();
            if (rnode != null) redirectorGrid = rnode.getGrid();
        }

        if (redirectorGrid == null
                || ColonyLinkServerTicker.findLiveTerminalsForGrid(level, redirectorGrid).isEmpty())
        {
            player.sendSystemMessage(Component.translatable("colonylink.domum_queue.no_terminal",
                    packet.domumStack().getDisplayName()));
            return;
        }

        boolean added = WarehouseLinkTerminalPart.queueDomumOnGrid(
                level, redirectorGrid, packet.domumStack());

        // A3 — three outcomes now. queueDomumOnGrid already seeded the grid via add(), so
        // isFull reflects the true post-seed size: a refused add on a full queue means the
        // cap was hit (distinct from a plain duplicate). Never reuse already_queued/invalid
        // for the full case — they would mislead the player.
        if (added)
            player.sendSystemMessage(Component.translatable("colonylink.domum_queue.sent"));
        else if (DomumQueueManager.isFull(redirectorGrid))
            player.sendSystemMessage(Component.translatable("colonylink.domum_queue.full",
                    DomumQueueManager.MAX_QUEUE_SIZE));
        else
            player.sendSystemMessage(Component.translatable("colonylink.domum_queue.already_queued"));
    }
}