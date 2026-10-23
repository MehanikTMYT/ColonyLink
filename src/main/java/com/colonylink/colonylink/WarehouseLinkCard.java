package com.colonylink.colonylink;

import com.minecolonies.core.blocks.huts.BlockHutWareHouse;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

public class WarehouseLinkCard extends Item
{
    public WarehouseLinkCard(Item.Properties properties)
    {
        super(properties.stacksTo(1));
    }

    /**
     * 1.7.0-beta.2 — right-click on a MineColonies Warehouse hut links the card to that
     * Warehouse (and its colony). onItemUseFirst runs before the block's own use, so the
     * hut GUI does not open. Both sides return SUCCESS on a Warehouse hut (the client
     * checks only the block type); the link itself, with its ACCESS_HUTS check, is
     * server-side. Any other block: PASS, vanilla behaviour unchanged.
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context)
    {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockState(pos).getBlock() instanceof BlockHutWareHouse)) return InteractionResult.PASS;
        if (!level.isClientSide() && context.getPlayer() instanceof ServerPlayer player)
            CardLinks.linkToWarehouseHut(stack, player, level, pos);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<Component> tooltipAdder, TooltipFlag flag)
    {
        final List<Component> tooltip = new TooltipLines(tooltipAdder); // PORT(26.1)

        tooltip.add(Component.translatable("colonylink.card.line1"));

        // 1.7.0-beta.2 — the linked colony is read from the card itself (name copied
        // at link time), so the tooltip works without any client colony view.
        CardLink link = stack.get(ColonyLinkRegistry.CARD_LINK.get());
        if (link != null)
        {
            tooltip.add(Component.translatable("colonylink.card.linked", link.colonyName()));
            if (!Level.OVERWORLD.equals(link.dimension()))
                tooltip.add(Component.translatable("colonylink.card.dimension",
                        link.dimension().identifier().toString()));
            tooltip.add(Component.translatable("colonylink.card.warehouse",
                    link.warehousePos().toShortString()));
        }
        else
        {
            tooltip.add(Component.translatable("colonylink.card.not_linked"));
        }

        if (net.minecraft.client.Minecraft.getInstance().hasShiftDown())
        {
            tooltip.add(Component.translatable("colonylink.card.insert"));
            tooltip.add(Component.translatable("colonylink.card.enables"));
            tooltip.add(Component.translatable("colonylink.card.send"));
            tooltip.add(Component.translatable("colonylink.card.relink"));
        }
        else
        {
            tooltip.add(Component.translatable("colonylink.tip.hold_shift_details"));
        }
    }
}
