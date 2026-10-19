package com.colonylink.colonylink;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * ColonyLinkJeiPlugin — v1.6.6
 *
 * Minimal, non-intrusive JEI integration: hovering an item in the terminal's
 * Warehouse/ME panels → R shows recipes, U shows uses (like a real slot). Nothing
 * else: no recipe category, no transfer, no ghost, no drag-and-drop.
 *
 * {@code @JeiPlugin} is auto-discovered by JEI; this class loads only when JEI is
 * present (CLIENT {@code optional} + {@code compileOnly} dependency), so the mod
 * boots normally without JEI.
 *
 * The hovered ingredient and its highlight area come from a single hit-test source
 * in {@link WarehouseLinkTerminalScreen} ({@code panelHitAt}), in SCREEN
 * coordinates: {@link WarehouseLinkTerminalScreen#getPanelStackAt} for the stack,
 * {@link WarehouseLinkTerminalScreen#getPanelSlotArea} for the 16×16 cell.
 */
@JeiPlugin
public class ColonyLinkJeiPlugin implements IModPlugin
{
    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(ColonyLink.MODID, "jei");

    @Override
    public ResourceLocation getPluginUid()
    {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration reg)
    {
        reg.addGuiContainerHandler(WarehouseLinkTerminalScreen.class,
                new IGuiContainerHandler<WarehouseLinkTerminalScreen>()
                {
                    @Override
                    public Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(
                            IClickableIngredientFactory factory,
                            WarehouseLinkTerminalScreen screen,
                            double mouseX, double mouseY)
                    {
                        ItemStack stack = screen.getPanelStackAt(mouseX, mouseY);
                        if (stack.isEmpty()) return Optional.empty();

                        Rect2i area = screen.getPanelSlotArea(mouseX, mouseY);
                        if (area == null) return Optional.empty();

                        return factory.createBuilder(stack).buildWithArea(area);
                    }
                });

        // UI pass 1 — the Redirector's upgrade-cell box overflows the panel's
        // right edge (AE2 UpgradesPanel style): declare it as an extra area so the
        // JEI overlay never overlaps it (mirror of AE2's addExclusionZones).
        reg.addGuiContainerHandler(ColonyLinkRedirectorScreen.class,
                new IGuiContainerHandler<ColonyLinkRedirectorScreen>()
                {
                    @Override
                    public List<Rect2i> getGuiExtraAreas(ColonyLinkRedirectorScreen screen)
                    {
                        return screen.getJeiExclusionAreas();
                    }
                });
    }
}
