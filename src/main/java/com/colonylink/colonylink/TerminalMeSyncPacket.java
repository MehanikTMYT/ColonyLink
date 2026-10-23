package com.colonylink.colonylink;

import appeng.api.stacks.AEItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * S→C: snapshot of the ME storage to the client (items + craftables).
 *
 * The client handler lives in TerminalClientPacketHandler (@OnlyIn(CLIENT))
 * so RuntimeDistCleaner does not crash the dedicated server.
 */
public record TerminalMeSyncPacket(
        List<MeEntry> entries,
        BlockPos terminalPos
) implements CustomPacketPayload
{
    public record MeEntry(ItemStack stack, long count, boolean craftable) {}

    public static final Identifier ID = Identifier.fromNamespaceAndPath(
            ColonyLink.MODID, "terminal_me_sync");
    public static final CustomPacketPayload.Type<TerminalMeSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalMeSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.terminalPos());
                        buf.writeInt(p.entries().size());
                        for (var e : p.entries())
                        {
                            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, e.stack());
                            buf.writeLong(e.count());
                            buf.writeBoolean(e.craftable());
                        }
                    },
                    buf -> {
                        BlockPos pos = buf.readBlockPos();
                        int size = buf.readInt();
                        List<MeEntry> entries = new ArrayList<>();
                        for (int i = 0; i < size; i++)
                            entries.add(new MeEntry(
                                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                                    buf.readLong(),
                                    buf.readBoolean()));
                        return new TerminalMeSyncPacket(entries, pos);
                    }
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * Builds the ME snapshot from the AE2 grid.
     *
     * Uses getCachedInventory() (KeyCounter) to iterate stocked items, and
     * isCraftable() to flag autocraftable ones. For craftable-but-not-stocked
     * items: getCraftables(AEKeyFilter) with a pass-through filter (accepts all).
     */
    public static TerminalMeSyncPacket fromGrid(appeng.api.networking.IGrid grid, BlockPos pos)
    {
        appeng.api.stacks.KeyCounter inv = grid.getStorageService().getCachedInventory();
        appeng.api.networking.crafting.ICraftingService crafting = grid.getCraftingService();
        List<MeEntry> entries = new ArrayList<>();

        // Stocked items — KeyCounter is Iterable<Map.Entry<AEKey, Long>>
        for (var entry : inv)
        {
            if (!(entry.getKey() instanceof AEItemKey aeKey)) continue;
            boolean craftable = crafting.isCraftable(aeKey);
            entries.add(new MeEntry(aeKey.toStack(1), entry.getLongValue(), craftable));
        }

        // O(1) dedup via a HashSet of already-seen AE2 keys
        Set<AEItemKey> seen = new java.util.HashSet<>();
        for (MeEntry e : entries)
        {
            AEItemKey k = AEItemKey.of(e.stack());
            if (k != null) seen.add(k);
        }

        // Craftable but not stocked items — getCraftables(AEKeyFilter) in AE2 19.x
        try
        {
            var craftables = crafting.getCraftables(k -> true);
            for (var key : craftables)
            {
                if (!(key instanceof AEItemKey aeKey)) continue;
                if (seen.add(aeKey))   // add() returns false if already present
                    entries.add(new MeEntry(aeKey.toStack(1), 0, true));
            }
        }
        catch (Exception ignored) {} // safety if the API changes

        entries.sort((a, b) -> Long.compare(b.count(), a.count()));
        return new TerminalMeSyncPacket(entries, pos);
    }
}