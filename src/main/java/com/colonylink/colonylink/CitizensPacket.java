package com.colonylink.colonylink;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S→C packet: open requests of all NON-builder citizens.
 * Sent by the ticker while the Citizens tab is active, or in reply to CitizensRequestPacket.
 */
public record CitizensPacket(List<CitizenRequestEntry> entries) implements CustomPacketPayload
{
    // 1.7.0-beta.3 — who handles the request in MineColonies (colonyState)
    /** The player has to provide it. */
    public static final byte STATE_PLAYER       = 0;
    /** MineColonies retries it periodically; in practice still for the player. */
    public static final byte STATE_RETRYING     = 1;
    /** A Warehouse took it (has the items, delivery being set up). */
    public static final byte STATE_WAREHOUSE    = 2;
    /** Deliveries created: a courier has it planned or on the way. */
    public static final byte STATE_DELIVERING   = 3;
    /** Another resolver (a colony crafter…) works on it. */
    public static final byte STATE_COLONY_CRAFT = 4;

    // 1.7.0-beta.3 — ColonyLink's own mark (CitizenRequestMemory)
    public static final byte MARK_NONE     = 0;
    public static final byte MARK_SENT     = 1;
    public static final byte MARK_CRAFTING = 2;

    /**
     * A request from a non-builder citizen.
     *
     * @param stack          requested item (with count)
     * @param citizenName    citizen name
     * @param jobName        job name (e.g. "Farmer", "Miner"...)
     * @param count          requested quantity
     * @param requestId      1.7.0-beta.3 — UUID of the MineColonies request
     * @param colonyState    1.7.0-beta.3 — STATE_* above
     * @param warehouseStock 1.7.0-beta.3 — matching items in all the colony's Warehouses,
     *                       capped at count; -1 when unknown or not relevant
     * @param mark           1.7.0-beta.3 — MARK_* above
     * @param markedBy       1.7.0-beta.3 — name of the player who set the mark ("" if none)
     */
    public record CitizenRequestEntry(
            ItemStack stack,
            String citizenName,
            String jobName,
            int count,
            boolean availableInME,  // true if the item is in ME stock (Send button active)
            boolean craftableInME,  // true if a pattern exists in the ME (Craft button active)
            UUID requestId,
            byte colonyState,
            int warehouseStock,
            byte mark,
            String markedBy
    ) {}

    public static final Identifier ID = Identifier.fromNamespaceAndPath(ColonyLink.MODID, "citizens_packet");
    public static final CustomPacketPayload.Type<CitizensPacket> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, CitizensPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeInt(packet.entries().size());
                for (CitizenRequestEntry e : packet.entries())
                {
                    ItemStack.STREAM_CODEC.encode(buf, e.stack());
                    buf.writeUtf(e.citizenName());
                    buf.writeUtf(e.jobName());
                    buf.writeInt(e.count());
                    buf.writeBoolean(e.availableInME());
                    buf.writeBoolean(e.craftableInME());
                    buf.writeLong(e.requestId().getMostSignificantBits());
                    buf.writeLong(e.requestId().getLeastSignificantBits());
                    buf.writeByte(e.colonyState());
                    buf.writeInt(e.warehouseStock());
                    buf.writeByte(e.mark());
                    buf.writeUtf(e.markedBy());
                }
            },
            buf -> {
                int size = buf.readInt();
                List<CitizenRequestEntry> list = new ArrayList<>();
                for (int i = 0; i < size; i++)
                {
                    ItemStack stack   = ItemStack.STREAM_CODEC.decode(buf);
                    String citizen    = buf.readUtf();
                    String job        = buf.readUtf();
                    int count         = buf.readInt();
                    boolean avail   = buf.readBoolean();
                    boolean craft   = buf.readBoolean();
                    UUID requestId  = new UUID(buf.readLong(), buf.readLong());
                    byte state      = buf.readByte();
                    int whStock     = buf.readInt();
                    byte mark       = buf.readByte();
                    String markedBy = buf.readUtf();
                    list.add(new CitizenRequestEntry(stack, citizen, job, count, avail, craft,
                            requestId, state, whStock, mark, markedBy));
                }
                return new CitizensPacket(list);
            }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
