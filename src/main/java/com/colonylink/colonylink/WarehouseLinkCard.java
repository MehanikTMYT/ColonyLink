package com.colonylink.colonylink;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

public class WarehouseLinkCard extends Item
{
    public WarehouseLinkCard(Item.Properties properties)
    {
        super(properties.stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<Component> tooltipAdder, TooltipFlag flag)
    {
        final List<Component> tooltip = new TooltipLines(tooltipAdder); // PORT(26.1)

        tooltip.add(Component.translatable("colonylink.card.line1"));
        if (net.minecraft.client.Minecraft.getInstance().hasShiftDown())
        {
            tooltip.add(Component.translatable("colonylink.card.insert"));
            tooltip.add(Component.translatable("colonylink.card.enables"));
            tooltip.add(Component.translatable("colonylink.card.send"));
        }
        else
        {
            tooltip.add(Component.translatable("colonylink.tip.hold_shift_details"));
        }
    }
}