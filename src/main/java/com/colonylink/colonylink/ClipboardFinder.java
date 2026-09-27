package com.colonylink.colonylink;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for "which ColonyLink Clipboard (wand) does this player carry?".
 *
 * Common code: safe on both logical sides and on a dedicated server (no client
 * classes, no Curios types — Curios is reached only through ColonyLinkCuriosCompat,
 * which is guarded by ModList.isLoaded("curios")).
 *
 * Search order (explicit, unchanged for the locations covered before the Curios fix):
 *   1. player.getInventory().items, in index order (hotbar 0-8, then main 9-35).
 *      The main hand is part of this list (selected hotbar slot), so it is NOT
 *      given a separate, higher priority — that would change which wand wins when
 *      several are carried.
 *   2. Curios slots (any slot type: bracelet, charm, hands, ...), in Curios order.
 * The offhand and armour slots are not searched (same as before).
 *
 * Several Clipboards: the FIRST match in this order is "the" Clipboard. Every
 * caller (server handlers, Redirector screen, Clipboard screen) uses the same
 * order, so they all agree on the same stack and never mix two wands' links.
 */
public final class ClipboardFinder
{
    private ClipboardFinder() {}

    /** The player's Clipboard (first match in search order), or ItemStack.EMPTY. */
    public static ItemStack find(Player player)
    {
        if (player == null) return ItemStack.EMPTY;

        for (ItemStack stack : player.getInventory().items)
            if (stack.getItem() instanceof ColonyLinkWand) return stack;

        return ColonyLinkCuriosCompat.findWandInCurioSlots(player);
    }

    /** Every Clipboard the player carries, in search order (for fallback scans). */
    public static List<ItemStack> findAll(Player player)
    {
        List<ItemStack> result = new ArrayList<>();
        if (player == null) return result;

        for (ItemStack stack : player.getInventory().items)
            if (stack.getItem() instanceof ColonyLinkWand) result.add(stack);

        result.addAll(ColonyLinkCuriosCompat.findAllWandsInCurioSlots(player));
        return result;
    }
}
