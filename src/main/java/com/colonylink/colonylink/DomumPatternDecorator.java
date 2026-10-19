package com.colonylink.colonylink;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * DomumPatternDecorator — ColonyLink v1.4.2
 *
 * IItemDecorator for DomumPatternItem. When Shift is held, renders the target
 * Domum item over the pattern in the slot at the exact same (x, y).
 *
 * Registered via RegisterItemDecorationsEvent in ColonyLinkClient.
 * Works in all slots: inventory, hotbar, AE2, WH, etc.
 */
@OnlyIn(Dist.CLIENT)
public class DomumPatternDecorator implements IItemDecorator
{
    public static final DomumPatternDecorator INSTANCE = new DomumPatternDecorator();
    private DomumPatternDecorator() {}

    @Override
    public boolean render(GuiGraphics graphics, Font font,
                          ItemStack stack, int x, int y)
    {
        if (!(stack.getItem() instanceof DomumPatternItem)) return false;
        if (!Screen.hasShiftDown()) return false;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return false;

        ItemStack target = DomumPatternItem.decodeTarget(
                stack, mc.level.registryAccess());
        if (target == null || target.isEmpty()) return false;

        // Push z very high to draw above the pattern render
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);

        // Vanilla slot background (#8B8B8B) to mask the pattern
        graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);

        graphics.renderItem(target, x, y);
        graphics.renderItemDecorations(font, target, x, y, null);

        graphics.pose().popPose();

        return false;
    }
}