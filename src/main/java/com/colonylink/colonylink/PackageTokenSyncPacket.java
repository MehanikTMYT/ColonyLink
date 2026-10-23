package com.colonylink.colonylink;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Packet S→C: syncs the count of ColonyLink Packages stored in the wand.
 * Sent after each PackageTokenPacket action to refresh the slot display, and by
 * the ticker when the Citizens tab opens.
 */
public record PackageTokenSyncPacket(int count) implements CustomPacketPayload
{
    public static final Identifier ID = Identifier.fromNamespaceAndPath(
            ColonyLink.MODID, "package_token_sync");
    public static final CustomPacketPayload.Type<PackageTokenSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, PackageTokenSyncPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeInt(p.count()),
            buf -> new PackageTokenSyncPacket(buf.readInt())
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}