package com.colonylink.colonylink;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * IEnergyStorage implementation for the ColonyLink Wand.
 *
 * Stores RF as NBT in the item's CustomData (key "wand_rf"), exposing the forge:energy
 * capability for third-party charging mods (Powah, Mekanism, IE, Mining Gadgets...).
 *
 * The "wand_rf" key is deliberately SEPARATE from the other wand data: ClearNbtRecipe
 * rebuilds a fresh ItemStack without copying CustomData, so to preserve RF
 * ClearNbtRecipe.assemble() explicitly copies the "wand_rf" key from input to output.
 *
 * Capacity and transfer rate are read from ColonyLinkConfig on every call (hot-reloadable).
 */
public class WandEnergyStorage implements IEnergyStorage
{
    /** NBT key in the item's CustomData. */
    public static final String NBT_KEY = "wand_rf";

    private final ItemStack stack;

    public WandEnergyStorage(ItemStack stack)
    {
        this.stack = stack;
    }

    // NBT read / write

    public static long getStoredRF(ItemStack stack)
    {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0L;
        var tag = data.copyTag();
        return tag.contains(NBT_KEY) ? tag.getLongOr(NBT_KEY, 0L) : 0L;
    }

    public static void setStoredRF(ItemStack stack, long rf)
    {
        long clamped = Math.max(0, Math.min(rf, ColonyLinkConfig.safeGet(ColonyLinkConfig.WAND_RF_CAPACITY, 160_000L)));
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data -> {
            var tag = data.copyTag();
            tag.putLong(NBT_KEY, clamped);
            return CustomData.of(tag);
        });
    }

    /**
     * Tries to consume {@code amount} RF from the wand's buffer.
     * @return true if consumption succeeded (or if blocking is disabled in config)
     */
    public static boolean tryConsume(ItemStack stack, long amount)
    {
        if (amount <= 0) return true;
        long stored = getStoredRF(stack);
        boolean blockIfNoPower = ColonyLinkConfig.BLOCK_ACTIONS_IF_NO_POWER.get();

        if (stored < amount)
        {
            if (blockIfNoPower) return false;
            // blocking disabled: drain what remains
            setStoredRF(stack, 0);
            return true;
        }

        setStoredRF(stack, stored - amount);
        return true;
    }

    // IEnergyStorage

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate)
    {
        long stored   = getStoredRF(stack);
        long capacity = ColonyLinkConfig.safeGet(ColonyLinkConfig.WAND_RF_CAPACITY, 160_000L);
        long rate     = ColonyLinkConfig.safeGet(ColonyLinkConfig.WAND_RF_TRANSFER_RATE, 2_500L);
        long toInsert = Math.min(maxReceive, Math.min(rate, capacity - stored));
        if (toInsert <= 0) return 0;
        if (!simulate)
            setStoredRF(stack, stored + toInsert);
        return (int) Math.min(toInsert, Integer.MAX_VALUE);
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate)
    {
        // The wand never outputs energy
        return 0;
    }

    @Override
    public int getEnergyStored()
    {
        return (int) Math.min(getStoredRF(stack), Integer.MAX_VALUE);
    }

    @Override
    public int getMaxEnergyStored()
    {
        return (int) Math.min(ColonyLinkConfig.safeGet(ColonyLinkConfig.WAND_RF_CAPACITY, 160_000L), Integer.MAX_VALUE);
    }

    @Override
    public boolean canExtract() { return false; }

    @Override
    public boolean canReceive() { return true; }
}