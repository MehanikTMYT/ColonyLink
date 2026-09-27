package com.colonylink.colonylink;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * Facade for Curios API integration.
 *
 * Every public method guards its body with ModList.isLoaded("curios")
 * before delegating to ColonyLinkCuriosHelper.
 *
 * ColonyLinkCuriosHelper (which imports Curios types) is NEVER
 * class-loaded when Curios is absent — no NoClassDefFoundError.
 *
 * All other classes call only this class, never ColonyLinkCuriosHelper directly.
 *
 * CurseForge issue #3 — takes any Player (not only ServerPlayer): the client screens
 * also need to see a wand equipped in a curio slot. Curios syncs the
 * local player's curio stacks to their own client, so the client copy is valid.
 */
public final class ColonyLinkCuriosCompat
{
    private ColonyLinkCuriosCompat() {}

    /** True if Curios API is present in the current mod list. */
    public static boolean isLoaded()
    {
        return ModList.get().isLoaded("curios");
    }

    /**
     * Searches the player's curio slots for a ColonyLink Wand.
     * Returns ItemStack.EMPTY if Curios is not installed or no wand found.
     */
    public static ItemStack findWandInCurioSlots(Player player)
    {
        if (!isLoaded()) return ItemStack.EMPTY;
        return ColonyLinkCuriosHelper.findWandInCurioSlots(player);
    }

    /**
     * Every ColonyLink Wand in the player's curio slots, in Curios order.
     * Returns an empty list if Curios is not installed or none found.
     */
    public static List<ItemStack> findAllWandsInCurioSlots(Player player)
    {
        if (!isLoaded()) return List.of();
        return ColonyLinkCuriosHelper.findAllWandsInCurioSlots(player);
    }
}
