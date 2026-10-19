package com.colonylink.colonylink;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * C→S: the client requests opening the Clipboard GUI via keybind.
 *
 * The server locates the wand in the player's inventory and runs the same
 * logic as ColonyLinkWand.use() — without requiring the player to hold the
 * wand in hand.
 *
 * Payload-less packet (nothing to transmit).
 */
public record OpenWandGuiPacket() implements CustomPacketPayload
{
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            ColonyLink.MODID, "open_wand_gui");
    public static final CustomPacketPayload.Type<OpenWandGuiPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenWandGuiPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, packet) -> { /* nothing to write */ },
                    buf -> new OpenWandGuiPacket()
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public static void handle(OpenWandGuiPacket packet, IPayloadContext context)
    {
        context.enqueueWork(() ->
        {
            if (!(context.player() instanceof ServerPlayer sp)) return;

            // Find the wand in the inventory (same logic as ColonyLinkServerTicker)
            ItemStack wand = ColonyLinkServerTicker.findWandInInventory(sp);
            if (wand == null)
            {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "colonylink.open_gui.no_clipboard"));
                return;
            }

            // Same checks as ColonyLinkWand.use()
            if (WandEnergyStorage.getStoredRF(wand) <= 0)
            {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "colonylink.wand.msg.out_of_power"));
                return;
            }

            if (!ColonyLinkWandLinkableHandler.isLinked(wand))
            {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "colonylink.wand.msg.not_linked"));
                return;
            }

            List<BuilderEntry> entries = ColonyLinkWandLinkableHandler.getBuilderEntries(wand);
            if (entries.isEmpty())
            {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "colonylink.wand.msg.no_builder_yet"));
                return;
            }

            int activeTab = ColonyLinkWandLinkableHandler.getActiveTab(wand);
            BuilderEntry active = entries.get(Math.min(activeTab, entries.size() - 1));

            ServerLevel sl = sp.serverLevel();

            // Delegates to ColonyLinkWand.openGUIStatic() — extracted so it can be
            // called without an instance (see ColonyLinkWand).
            ColonyLinkWand.openGUIStatic(wand, sp, sl, active.builderPos(), activeTab);
        });
    }
}