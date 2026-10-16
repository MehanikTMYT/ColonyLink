package com.colonylink.colonylink;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * DomumPatternDecorator — ColonyLink v1.4.2
 *
 * IItemDecorator pour DomumPatternItem.
 * Quand Shift est enfoncé : rend l'item Domum cible PAR-DESSUS le pattern
 * dans le slot, à la position exacte (x, y identiques = pas de décalage).
 *
 * Enregistré via RegisterItemDecorationsEvent dans ColonyLinkClient.
 * Fonctionne dans TOUS les slots : inventaire, hotbar, AE2, WH, etc.
 */
public class DomumPatternDecorator implements IItemDecorator
{
    public static final DomumPatternDecorator INSTANCE = new DomumPatternDecorator();
    private DomumPatternDecorator() {}

    @Override
    public boolean render(GuiGraphicsExtractor graphics, Font font,
                          ItemStack stack, int x, int y)
    {
        if (!(stack.getItem() instanceof DomumPatternItem)) return false;
        if (!net.minecraft.client.Minecraft.getInstance().hasShiftDown()) return false;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return false;

        ItemStack target = DomumPatternItem.decodeTarget(
                stack, mc.level.registryAccess());
        if (target == null || target.isEmpty()) return false;

        // PORT(26.1): GUI layering follows submission order (no Z); the decorator runs after the
        // pattern item, so drawing here already covers it.

        // Fond couleur slot vanilla (#8B8B8B) pour masquer le pattern
        graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);

        // Rend l'item cible au même z-level élevé
        graphics.item(target, x, y);
        graphics.itemDecorations(font, target, x, y, null);


        return false;
    }
}