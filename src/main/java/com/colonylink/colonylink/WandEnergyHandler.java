package com.colonylink.colonylink;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * PORT(26.1): the item energy capability is an {@link EnergyHandler} over an {@link ItemAccess}. The charge stays
 * in the wand's custom data ({@link WandEnergyStorage#NBT_KEY}); a charge is applied by exchanging the accessed
 * stack for a copy carrying the new value, inside the caller's transaction. Receive-only, rate-limited, as before.
 */
public final class WandEnergyHandler implements EnergyHandler
{
    private final ItemAccess access;

    public WandEnergyHandler(final ItemAccess access)
    {
        this.access = access;
    }

    private static long capacity()
    {
        return ColonyLinkConfig.safeGet(ColonyLinkConfig.WAND_RF_CAPACITY, 160_000L);
    }

    @Override
    public long getAmountAsLong()
    {
        return WandEnergyStorage.getStoredRF(access.getResource().toStack());
    }

    @Override
    public long getCapacityAsLong()
    {
        return capacity();
    }

    @Override
    public int insert(final int amount, final TransactionContext transaction)
    {
        if (amount <= 0 || access.getAmount() != 1) return 0;
        final ItemResource resource = access.getResource();
        final ItemStack stack = resource.toStack();
        final long stored = WandEnergyStorage.getStoredRF(stack);
        final long rate = ColonyLinkConfig.safeGet(ColonyLinkConfig.WAND_RF_TRANSFER_RATE, 2_500L);
        final long toInsert = Math.min(amount, Math.min(rate, capacity() - stored));
        if (toInsert <= 0) return 0;
        WandEnergyStorage.setStoredRF(stack, stored + toInsert);
        return access.exchange(ItemResource.of(stack), 1, transaction) == 1 ? (int) toInsert : 0;
    }

    @Override
    public int extract(final int amount, final TransactionContext transaction)
    {
        // La wand ne distribue pas d'énergie vers l'extérieur
        return 0;
    }
}
