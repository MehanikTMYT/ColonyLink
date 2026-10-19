package com.colonylink.colonylink;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * ColonyLink Package — consumable token for the Citizens tab.
 *
 * Stored in the wand (NBT "citizen_packages", int). Each Send or Craft action in
 * the Citizens tab consumes 1 package server-side; when the stock is empty the
 * action is blocked with an error message.
 *
 * Recipe: 4 Oak Slab (tag #c:slabs/wooden) + 4 Paper arranged in a ring.
 * Modest, intentional cost.
 */
public class ColonyLinkPackage extends Item
{
    public ColonyLinkPackage()
    {
        super(new Item.Properties().stacksTo(64));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag)
    {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.literal("§8──────────────────"));
        tooltip.add(Component.translatable("colonylink.pkg.line1"));
        tooltip.add(Component.literal("§8──────────────────"));
        tooltip.add(Component.translatable("colonylink.pkg.usage"));
        tooltip.add(Component.translatable("colonylink.pkg.load"));
        tooltip.add(Component.translatable("colonylink.pkg.each"));
        tooltip.add(Component.translatable("colonylink.pkg.tab"));
        tooltip.add(Component.literal("§8──────────────────"));
        tooltip.add(Component.translatable("colonylink.pkg.crafted"));
    }
}