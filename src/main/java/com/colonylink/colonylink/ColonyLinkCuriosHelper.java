package com.colonylink.colonylink;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.util.List;

/**
 * Curios API integration for the ColonyLink Clipboard (wand).
 *
 * This class MUST NOT be referenced directly from any other class.
 * All calls go through {@link ColonyLinkCuriosCompat}, which guards
 * every call with ModList.isLoaded("curios").
 *
 * v1.6.2 — no explicit CuriosApi.registerCurio() is needed: in Curios 9.x the
 * wand is made equippable through data-driven slot tags (verified in game). Only
 * the read path remains.
 *
 * CurseForge issue #3 — accepts any Player so the client screens can read it too
 * (CuriosApi.getCuriosInventory takes a LivingEntity and works on both sides).
 */
public final class ColonyLinkCuriosHelper
{
    private ColonyLinkCuriosHelper() {}

    /**
     * Searches Curios slots on the given player for a ColonyLink Wand.
     * Returns ItemStack.EMPTY if none found.
     */
    public static ItemStack findWandInCurioSlots(Player player)
    {
        return CuriosApi.getCuriosInventory(player)
                .map(handler ->
                {
                    var result = handler.findFirstCurio(ColonyLink.COLONY_LINK_WAND.get());
                    return result.map(r -> r.stack()).orElse(ItemStack.EMPTY);
                })
                .orElse(ItemStack.EMPTY);
    }

    /** Every ColonyLink Wand in the player's curio slots, in Curios order. */
    public static List<ItemStack> findAllWandsInCurioSlots(Player player)
    {
        return CuriosApi.getCuriosInventory(player)
                .map(handler -> handler.findCurios(ColonyLink.COLONY_LINK_WAND.get())
                        .stream().map(SlotResult::stack).toList())
                .orElse(List.of());
    }
}
