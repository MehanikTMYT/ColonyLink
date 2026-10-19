package com.colonylink.colonylink;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
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

/**
 * C→S — cancels the Priority Request shown for the targeted builder.
 *
 * Security model: same as {@link WarehouseCraftHandler} (NOT
 * {@link RestartBuilderPacket}, which only does ACCESS_HUTS). The client is never
 * authoritative: {@code redirectorPos} MUST match a redirector actually linked to
 * THIS player's wand. The validated entry supplies the {@code builderPos} — we do
 * not trust a raw position from the client.
 *
 * The cancelled request is re-derived server-side (first open request not
 * CANCELLED/OVERRULED of type IDeliverable), exactly like pass 1 of
 * {@code ColonyLinkServerTicker.fetchBuilderRequest}. We NEVER match by the
 * displayed item: with tool substitution active, the displayed item is the
 * substitute, not the request's item. The button is on the priority line = the
 * first open request, so the target stays consistent.
 *
 * The cancel call mirrors the native builder GUI message 1:1
 * (UpdateRequestStateMessage → updateRequestState(token, CANCELLED)). The internal
 * IRequestManager handles the cascade (child-requests, resolver).
 */
public record CancelRequestPacket(BlockPos redirectorPos) implements CustomPacketPayload
{
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            ColonyLink.MODID, "cancel_request");
    public static final CustomPacketPayload.Type<CancelRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, CancelRequestPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeBlockPos(packet.redirectorPos()),
            buf -> new CancelRequestPacket(buf.readBlockPos())
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public static void handle(CancelRequestPacket packet, IPayloadContext context)
    {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;

            ServerLevel level = serverPlayer.serverLevel();
            BlockPos redirectorPos = packet.redirectorPos();

            // Wand detection (includes Curios)
            ItemStack wandStack = ColonyLinkServerTicker.findWandInInventory(serverPlayer);
            if (wandStack == null || !ColonyLinkWandLinkableHandler.isLinked(wandStack))
            {
                serverPlayer.sendSystemMessage(
                        Component.translatable("colonylink.whc.clipboard_not_linked"));
                return;
            }

            // Validation redirector↔wand (client NOT authoritative)
            // redirectorPos must be a redirector actually linked to THIS wand.
            // The validated entry gives us the builderPos — we never trust a
            // builder position sent by the client.
            BlockPos builderPos = null;
            for (BuilderEntry e : ColonyLinkWandLinkableHandler.getBuilderEntries(wandStack))
            {
                if (e.hasRedirector() && e.redirectorPos().equals(redirectorPos))
                {
                    // Dimension guard: refuse a mis-resolved cross-dimension cancel.
                    if (e.dimension() != null && !e.dimension().equals(level.dimension()))
                        break;
                    builderPos = e.builderPos();
                    break;
                }
            }
            if (builderPos == null)
            {
                serverPlayer.sendSystemMessage(
                        Component.translatable("colonylink.stw.invalid_request"));
                return;
            }

            // Colony (resolved via the wand-validated builderPos)
            IColony colony = IColonyManager.getInstance().getClosestColony(level, builderPos);
            if (colony == null)
            {
                serverPlayer.sendSystemMessage(
                        Component.translatable("colonylink.whs.no_colony"));
                return;
            }

            // Colony permission, server-side
            if (!colony.getPermissions().hasPermission(serverPlayer, Action.ACCESS_HUTS))
            {
                serverPlayer.sendSystemMessage(
                        Component.translatable("colonylink.wand.msg.no_permission"));
                return;
            }

            // Builder's Hut resolution
            IBuilding building = null;
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values())
            {
                if (b.getPosition().equals(builderPos))
                {
                    building = b;
                    break;
                }
            }
            if (!(building instanceof AbstractBuildingStructureBuilder builderBuilding))
            {
                serverPlayer.sendSystemMessage(
                        Component.translatable("colonylink.handler.hut_not_found"));
                return;
            }

            try
            {
                // Re-derive the Priority Request = first open request not
                // CANCELLED/OVERRULED of type IDeliverable (mirror fetchBuilderRequest
                // pass 1). NO item match (avoids the substitution mismatch).
                IRequest<?> target = null;
                if (!builderBuilding.getAllAssignedCitizen().isEmpty())
                {
                    var citizen = builderBuilding.getAllAssignedCitizen().iterator().next();
                    var reqs = builderBuilding.getOpenRequests(citizen.getId());
                    if (reqs != null)
                    {
                        for (IRequest<?> req : reqs)
                        {
                            if (req.getState() == RequestState.CANCELLED
                                    || req.getState() == RequestState.OVERRULED) continue;
                            if (!(req.getRequest() instanceof IDeliverable)) continue;
                            target = req;
                            break;
                        }
                    }
                }

                if (target == null)
                {
                    // v1.6.6 — resync a stale GUI that may still show a priority line
                    // which no longer exists server-side (root cause of the repeated
                    // "No request to cancel" after an untracked refresh): force an
                    // immediate resend so the client stops showing the dead line.
                    ColonyLinkServerTicker.invalidateSignature(serverPlayer);
                    serverPlayer.sendSystemMessage(
                            Component.translatable("colonylink.priority.cancel_none"));
                    return;
                }

                // Native 1:1 cancel (UpdateRequestStateMessage → CANCELLED).
                // The IRequestManager handles the cascade (child-requests, resolver).
                colony.getRequestManager().updateRequestState(target.getId(), RequestState.CANCELLED);
                building.markDirty();

                // v1.6.6 — force an immediate GUI resend. building.markDirty() only
                // dirties MineColonies state; it does not invalidate ColonyLink's own
                // content signature, so without this the throttled ticker would keep
                // showing the just-cancelled priority line until the next natural change.
                ColonyLinkServerTicker.invalidateSignature(serverPlayer);

                serverPlayer.sendSystemMessage(
                        Component.translatable("colonylink.priority.cancelled"));
            }
            catch (Exception e)
            {
                ColonyLink.LOGGER.error("[ColonyLink] Error cancelling builder request: {}", e.getMessage());
                serverPlayer.sendSystemMessage(
                        Component.translatable("colonylink.priority.cancel_none"));
            }
        });
    }
}
