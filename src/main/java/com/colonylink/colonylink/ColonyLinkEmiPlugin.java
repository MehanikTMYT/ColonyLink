package com.colonylink.colonylink;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.api.widget.Bounds;

/**
 * Native EMI integration (replaces the former JEI plugin, same scope): hovering an item in the
 * Warehouse/ME panels of the terminal lets EMI show its recipes (R) and uses (U) like a real slot,
 * and the Redirector's upgrade-cell box is declared as an exclusion area so the EMI overlay never
 * covers it. No categories, transfer or drag-and-drop.
 *
 * Loaded by EMI only (client side, optional dependency), so the mod boots normally without EMI.
 */
@EmiEntrypoint
public class ColonyLinkEmiPlugin implements EmiPlugin
{
    @Override
    public void register(EmiRegistry registry)
    {
        registry.addStackProvider(WarehouseLinkTerminalScreen.class, (screen, mouseX, mouseY) -> {
            var stack = screen.getPanelStackAt(mouseX, mouseY);
            return stack.isEmpty() ? EmiStackInteraction.EMPTY : new EmiStackInteraction(EmiStack.of(stack));
        });
        registry.addExclusionArea(ColonyLinkRedirectorScreen.class, (screen, consumer) -> {
            for (var area : screen.getViewerExclusionAreas())
            {
                consumer.accept(new Bounds(area.getX(), area.getY(), area.getWidth(), area.getHeight()));
            }
        });
    }
}
