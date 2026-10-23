package com.colonylink.colonylink;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight packet S→C: request count per tab (for badge #5).
 *
 * Sent each ticker cycle alongside sendFullUpdate. Carries only the counts of
 * inactive tabs — the active tab is handled directly by ColonyLinkPacket.
 *
 * The client calls ColonyLinkScreen.markTabUnread() when a count > 0 on a tab
 * that is not active.
 */
public record TabCountsPacket(
        List<Integer> counts,   // counts[i] = entry count for tab i (-1 = not computed)
        int activeTabIndex
) implements CustomPacketPayload
{
    public static final Identifier ID = Identifier.fromNamespaceAndPath(
            ColonyLink.MODID, "tab_counts");
    public static final CustomPacketPayload.Type<TabCountsPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, TabCountsPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeInt(packet.counts().size());
                for (int c : packet.counts()) buf.writeInt(c);
                buf.writeInt(packet.activeTabIndex());
            },
            buf -> {
                int size = buf.readInt();
                List<Integer> counts = new ArrayList<>();
                for (int i = 0; i < size; i++) counts.add(buf.readInt());
                int active = buf.readInt();
                return new TabCountsPacket(counts, active);
            }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}