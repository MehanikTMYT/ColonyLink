package com.colonylink.colonylink;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jetbrains.annotations.Nullable;

/**
 * PORT(26.1): the block item capability is now a {@code ResourceHandler<ItemResource>}. ColonyLink's rack and hut
 * logic is written against slot-based {@link IItemHandler}, so lookups go through NeoForge's legacy adapter.
 */
public final class CapCompat
{
    private CapCompat() {}

    @Nullable
    public static IItemHandler itemHandler(final Level level, final BlockPos pos, @Nullable final Direction side)
    {
        final ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, side);
        return handler == null ? null : IItemHandler.of(handler);
    }
}
