package com.colonylink.colonylink;

import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import appeng.api.util.AEColor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side registration for ColonyLink visuals and input.
 *
 * Currently handles:
 *   - ItemColor handler for the Warehouse Link Terminal item.
 *   - Explicit model registration for the Warehouse Link Terminal part models
 *     via ModelEvent.RegisterAdditional (variant must be "standalone").
 *   - Keybinding to open the Clipboard GUI from anywhere in the inventory
 *     (unbound by default — player assigns in Controls menu).
 *
 * Server safety:
 *   This class is . The dispatcher in ColonyLink.java
 *   MUST wrap all listener registrations for this class in dist.isClient().
 *
 * Keybinding notes (v1.4.0):
 *   - Registered via RegisterKeyMappingsEvent on the mod event bus.
 *   - Consumed via InputEvent.Key on the NeoForge event bus (client-side).
 *   - When pressed, sends OpenWandGuiPacket C→S. The server locates the
 *     Clipboard anywhere in the player's inventory (not just held item).
 *   - Default: UNKNOWN (unbound). Player assigns in Options → Controls.
 *   - Category: "key.categories.colonylink" (separate group in Controls menu).
 *   - Guard: key is consumed only when no screen is open (getScreen() == null)
 *     to avoid conflicts with chest/crafting GUIs.
 */
public final class ColonyLinkClient
{
    // ── Keybinding ────────────────────────────────────────────────────────────

    /** Category shown in the Controls menu. */
    // PORT(26.1): key categories are registered ids; the label key is key.category.colonylink.colonylink.
    public static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("colonylink", "colonylink"));

    /**
     * The keybinding for opening the Clipboard GUI.
     * Default: UNKNOWN (unbound) — the player assigns it manually.
     */
    public static final KeyMapping KEY_OPEN_CLIPBOARD = new KeyMapping(
            "key.colonylink.open_clipboard",     // translation key
            InputConstants.UNKNOWN.getValue(),   // default: unbound
            KEY_CATEGORY                         // Controls menu category
    );

    private ColonyLinkClient() {}

    // ── Block entity renderer registration ────────────────────────────────────

    /**
     * Registers the Redirector's BlockEntityRenderer (3D craft item + slide/bob).
     * Called via modEventBus.addListener(ColonyLinkClient::onRegisterBlockEntityRenderers)
     * from the ColonyLink constructor (client-only, guarded by dist.isClient()).
     */
    public static void onRegisterBlockEntityRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerBlockEntityRenderer(
                ColonyLinkRegistry.REDIRECTOR_BLOCK_ENTITY.get(),
                RedirectorBlockEntityRenderer::new);
    }

    // Item color registration

    /**
     * Registers ItemColor handlers for ColonyLink items.
     */
    public static void onRegisterItemDecorations(
            net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent event)
    {
        event.register(ColonyLinkRegistry.DOMUM_PATTERN_ITEM.get(),
                DomumPatternDecorator.INSTANCE);
    }

    // ── Keybinding registration ───────────────────────────────────────────────

    /**
     * Registers the ColonyLink keybinding with NeoForge.
     * Called via modEventBus.addListener(ColonyLinkClient::onRegisterKeyMappings)
     * from ColonyLink constructor (client-only, guarded by dist.isClient()).
     */
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)
    {
        event.register(KEY_OPEN_CLIPBOARD);
    }

    // Keybinding input handler

    /**
     * Fires on every key press/release. Checked on the NeoForge event bus
     * (client-side only — registered via NeoForge.EVENT_BUS.addListener
     * inside a dist.isClient() guard in ColonyLink constructor).
     *
     * Sends OpenWandGuiPacket C→S when:
     *   - The key is pressed (action == GLFW_PRESS)
     *   - No screen is currently open (avoids conflicts with chest/crafting GUIs)
     *   - The keybinding is actually bound by the player
     */
    public static void onKeyInput(InputEvent.Key event)
    {
        if (event.getAction() != org.lwjgl.glfw.GLFW.GLFW_PRESS) return;

        if (Minecraft.getInstance().screen != null) return;

        if (!KEY_OPEN_CLIPBOARD.isDown()) return;

        KEY_OPEN_CLIPBOARD.consumeClick();
        ClientPacketDistributor.sendToServer(new OpenWandGuiPacket());
    }

    /**
     * Upstream 1.7.0: drop the Domum cutter-recipe cache when the client receives new recipes.
     * PORT(26.1): RecipesUpdatedEvent -> RecipesReceivedEvent.
     */
    public static void onRecipesUpdated(net.neoforged.neoforge.client.event.RecipesReceivedEvent event)
    {
        DomumRecipeHelper.invalidate();
    }
}