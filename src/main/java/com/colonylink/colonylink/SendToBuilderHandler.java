package com.colonylink.colonylink;

import appeng.api.config.Actionable;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * Handles "Send to Builder": moves items to the MineColonies Builder's Hut.
 *
 * v1.4.9 — unified send & zero voiding:
 *   - Extraction honors the Redirector priority for ALL items (Domum included):
 *       • warehouse card + priority ON  → warehouse first, then ME
 *       • warehouse card + priority OFF → ME first, then warehouse (fallback)
 *       • no warehouse card             → ME only
 *     A FINISHED Domum block in the warehouse is thus delivered straight to the
 *     builder, bypassing AE2 and the Redirector buffer.
 *   - Both sources go through void-safe primitives (pullFromMe / pullFromWarehouse):
 *     any surplus the builder cannot hold returns to its source (ME or racks), then
 *     to the player inventory as a last resort. No item can be destroyed.
 *   - The old "DO buffer step" was removed: the Redirector buffer holds only
 *     DomumPatternItem, never finished blocks — that step did nothing.
 */
public class SendToBuilderHandler
{
    /** v1.6.0 — upper bound for the client-supplied count (anti Integer.MAX_VALUE). */
    private static final int MAX_SEND_COUNT = 10_000;

    public static void handleSendToBuilder(ServerPlayer player, ItemStack stack,
                                           BlockPos builderPos, int realCount)
    {
        // ── v1.6.0 — input hardening (before any RF charge) ──────────────────
        // A non-positive count can only come from a modified client — reject,
        // don't clamp up. The upper clamp bounds ME extraction loops.
        if (realCount <= 0 || stack.isEmpty())
        {
            player.sendSystemMessage(Component.translatable("colonylink.stw.invalid_request"));
            return;
        }
        realCount = Math.min(realCount, MAX_SEND_COUNT);

        // ── v1.6.0 — delivery mode guard (before any RF charge) ──────────────
        // Server-decreed: when the server is configured for WAREHOUSE delivery,
        // direct-to-builder sends are rejected server-side. Hiding the button
        // client-side is not a guard — a modified client sends anyway.
        if (ColonyLinkConfig.SEND_TARGET.get() == ColonyLinkConfig.SendTarget.WAREHOUSE)
        {
            player.sendSystemMessage(Component.translatable("colonylink.delivery.blocked_builder"));
            return;
        }

        // v1.4.9 — cross-dimension: refuse cleanly BEFORE consuming RF
        {
            ItemStack wandForDim = findWandInInventory(player);
            if (wandForDim != null)
            {
                ResourceKey<Level> builderDim =
                        ColonyLinkWandLinkableHandler.getBuilderDimension(wandForDim, builderPos);
                if (builderDim != null && !player.level().dimension().equals(builderDim))
                {
                    player.sendSystemMessage(Component.translatable("colonylink.handler.cross_dimension", builderDim.identifier().getPath()));
                    return;
                }
            }
        }

        // ── v1.6.0 — colony permission check, server-side, before any RF charge.
        // getClosestColony can resolve a colony the player has zero rights in;
        // ACCESS_HUTS is the same action the ticker and the wand already require.
        // 1.7.0 — an unresolvable colony is refused (was silently permissive).
        {
            IColony permColony = ColonyResolver.forBuilder(player.level(), builderPos);
            if (permColony == null)
            {
                player.sendSystemMessage(Component.translatable("colonylink.colony.not_found"));
                return;
            }
            if (!permColony.getPermissions().hasPermission(player, Action.ACCESS_HUTS))
            {
                player.sendSystemMessage(Component.translatable("colonylink.wand.msg.no_permission"));
                return;
            }
        }

        ItemStack wandStack = findWandInInventory(player);
        if (wandStack == null || !ColonyLinkWandLinkableHandler.isLinked(wandStack))
        {
            player.sendSystemMessage(Component.translatable("colonylink.handler.clipboard_not_linked"));
            return;
        }

        ServerLevel level = player.level();

        // v1.6.2 — the item is not authoritative. Validate it is something this builder
        // legitimately needs right now: a missing build material, a live open request,
        // or a valid tool/armor substitute for one of those — exactly what the GUI
        // offers (mirrors fetchBuilderRequest, so tool substitution keeps working).
        // Fail-safe: if the ME grid or the builder cannot be resolved at this instant,
        // we do NOT reject — the downstream redirector/target/grid guards refuse
        // cleanly, and a transient miss must never reject a legitimate send.
        {
            IWirelessAccessPoint vWap = getWap(wandStack, level);
            IColony vColony = IColonyManager.getInstance().getClosestColony(level, builderPos);
            AbstractBuildingStructureBuilder vBb = null;
            if (vColony != null)
                for (IBuilding b : vColony.getServerBuildingManager().getBuildings().values())
                    if (b.getPosition().equals(builderPos)
                            && b instanceof AbstractBuildingStructureBuilder sb) { vBb = sb; break; }
            if (vWap != null && vWap.getGrid() != null && vBb != null)
            {
                appeng.api.stacks.KeyCounter vInv = vWap.getGrid().getStorageService().getCachedInventory();
                appeng.api.networking.crafting.ICraftingService vCs = vWap.getGrid().getCraftingService();
                boolean toolUpgrade = ColonyLinkConfig.ENABLE_TOOL_UPGRADE.get();
                if (!isLegitimateBuilderSend(vBb, stack, vBb.getBuildingLevel(), vInv, vCs, toolUpgrade))
                {
                    player.sendSystemMessage(Component.translatable("colonylink.stw.not_needed", stack.getDisplayName()));
                    return;
                }
            }
        }

        // RF cost (after validation, like SendToWarehousePacket)
        long sendCost = ColonyLinkConfig.SEND_COST_RF.get();
        if (sendCost > 0 && !ColonyLinkServerTicker.tryConsumeRF(player, sendCost))
        {
            player.sendSystemMessage(Component.translatable("colonylink.stb.not_enough_power", sendCost));
            return;
        }

        // Tool substitution
        // v1.6.4 — send-time substitution is gated by tool_substitution_in_list
        // (default false). Priority-line sends already carry the substituted stack
        // (fetchBuilderRequest bakes it into the displayed item), so findBestTool
        // returns NONE for them → gating here only affects list-originated sends,
        // keeping the list's display and send consistent when the toggle is off.
        if (ColonyLinkConfig.TOOL_SUBSTITUTION_IN_LIST.get() && BuilderToolHelper.isTool(stack))
        {
            IWirelessAccessPoint wap = getWap(wandStack, level);
            if (wap != null && wap.getGrid() != null)
            {
                IGrid grid = wap.getGrid();
                appeng.api.stacks.KeyCounter inv = grid.getStorageService().getCachedInventory();
                appeng.api.networking.crafting.ICraftingService cs = grid.getCraftingService();

                int buildingLevel = 0;
                com.minecolonies.api.colony.IColony colony =
                        com.minecolonies.api.colony.IColonyManager.getInstance()
                                .getClosestColony(level, builderPos);
                if (colony != null)
                {
                    for (com.minecolonies.api.colony.buildings.IBuilding b :
                            colony.getServerBuildingManager().getBuildings().values())
                    {
                        if (b.getPosition().equals(builderPos))
                        {
                            buildingLevel = b.getBuildingLevel();
                            break;
                        }
                    }
                }

                BuilderToolHelper.SubstituteResult sub =
                        BuilderToolHelper.findBestTool(stack, buildingLevel, inv, cs);

                if (sub.action() == BuilderToolHelper.SubstituteAction.SEND)
                {
                    stack = sub.displayStack().copyWithCount(stack.getCount());
                    player.sendSystemMessage(Component.translatable("colonylink.stb.tool_upgraded", stack.getDisplayName()));
                }
                else if (sub.action() == BuilderToolHelper.SubstituteAction.CRAFT)
                {
                    CraftHandler.handleCraftRequest(player, sub.displayStack(), 1);
                    player.sendSystemMessage(Component.translatable("colonylink.stb.crafting_best_tool", sub.displayStack().getDisplayName()));
                    return;
                }
            }
        }

        BlockPos redirectorPos = ColonyLinkWandLinkableHandler.getActiveRedirectorPos(wandStack);
        if (redirectorPos == null)
        {
            player.sendSystemMessage(Component.translatable("colonylink.handler.no_redirector"));
            player.sendSystemMessage(Component.translatable("colonylink.stb.sneak_redirector"));
            return;
        }

        ColonyLinkRedirectorBlockEntity redirector = null;
        // 1.7.0-beta.4 — active entry's Redirector, by UUID first (follows a moved Redirector).
        var be = RedirectorRegistry.forEntry(level, ColonyLinkWandLinkableHandler.getActiveEntry(wandStack));
        if (be instanceof ColonyLinkRedirectorBlockEntity r) redirector = r;

        if (redirector == null)
        {
            player.sendSystemMessage(Component.translatable("colonylink.stb.redirector_not_found"));
            return;
        }

        var node = redirector.getManagedGridNode().getNode();
        if (node == null || !node.isActive())
        {
            player.sendSystemMessage(Component.translatable("colonylink.stb.redirector_not_connected"));
            return;
        }

        BlockPos targetPos = redirector.getTargetInventoryPos();
        if (targetPos == null)
        {
            player.sendSystemMessage(Component.translatable("colonylink.stb.redirector_no_target"));
            return;
        }

        // v1.4.9 — strict fail-off on the MineColonies side: everything must be loaded.
        // The builder (insertion target) is mandatory; the warehouse is too as soon as a
        // card is present (the send can draw from it). Otherwise cancel cleanly.
        IColony sendColony = IColonyManager.getInstance().getClosestColony(level, targetPos);
        if (!ColonyLinkChunkUtil.buildingFullyLoaded(level, sendColony, targetPos))
        {
            player.sendSystemMessage(Component.translatable("colonylink.handler.hut_unloaded"));
            return;
        }
        // 1.7.0-beta.2 — the Warehouse is the one written on the card, and the card must
        // belong to the Builder's colony. A card that does not resolve (not linked,
        // other colony, colony or Warehouse lost) is reported and the send goes on
        // ME-only, exactly as without a card.
        BuildingWareHouse cardWarehouse = null;
        if (redirector.hasWarehouseCard())
        {
            CardLinks.Resolved card = CardLinks.resolveForRedirector(level, redirector, player);
            if (card.ok()) cardWarehouse = card.warehouse();
            else player.sendSystemMessage(card.message());
        }
        if (cardWarehouse != null && !ColonyLinkChunkUtil.warehouseFullyLoaded(level, cardWarehouse))
        {
            player.sendSystemMessage(Component.translatable("colonylink.handler.warehouse_unloaded"));
            return;
        }

        // Fix #3: use getBuildingHandlers() instead of a direct getCapability.
        // The MineColonies Builder's Hut exposes no IItemHandler capability at its
        // block position; getBuildingHandlers() queries IBuilding.getContainers()
        // to find the real racks.
        List<IItemHandler> buildingHandlers = redirector.getBuildingHandlers();
        if (buildingHandlers.isEmpty())
        {
            // Dernier fallback : capability directe (non-MineColonies)
            IItemHandler direct = CapCompat.itemHandler(level, targetPos, null);
            if (direct == null)
            {
                player.sendSystemMessage(Component.translatable("colonylink.stb.no_inventory"));
                return;
            }
            buildingHandlers = List.of(direct);
        }

        if (redirector.getState() == ColonyLinkRedirectorBlockEntity.RedirectorState.STANDBY)
        {
            player.sendSystemMessage(Component.translatable("colonylink.handler.builder_inv_full"));
            return;
        }

        IWirelessAccessPoint wap = getWap(wandStack, level);
        if (wap == null) { player.sendSystemMessage(Component.translatable("colonylink.handler.no_wap")); return; }

        IGrid grid = wap.getGrid();
        if (grid == null) { player.sendSystemMessage(Component.translatable("colonylink.handler.network_offline")); return; }

        IStorageService storageService = grid.getStorageService();
        MEStorage inventory = storageService.getInventory();
        IActionSource actionSource = IActionSource.ofPlayer(player,
                (appeng.api.networking.security.IActionHost) wap);

        AEItemKey aeKey = AEItemKey.of(stack);
        int remaining = realCount;

        // Extraction by Redirector priority (v1.4.9).
        // Applies to ALL items, Domum included: a finished Domum block in the warehouse
        // is delivered directly (no AE2) via pullFromWarehouse.
        boolean warehouseEnabled  = cardWarehouse != null; // 1.7.0-beta.2 — a card that resolves
        boolean warehousePriority = warehouseEnabled && redirector.isWarehousePriority();

        if (warehouseEnabled && warehousePriority)
        {
            // Warehouse priority: racks first, then ME.
            remaining = pullFromWarehouse(player, level, stack, remaining, cardWarehouse, redirector, buildingHandlers);
            remaining = pullFromMe(player, aeKey, remaining, inventory, actionSource, redirector, buildingHandlers);
        }
        else if (warehouseEnabled)
        {
            // Card present without priority: ME first, then warehouse as fallback.
            remaining = pullFromMe(player, aeKey, remaining, inventory, actionSource, redirector, buildingHandlers);
            remaining = pullFromWarehouse(player, level, stack, remaining, cardWarehouse, redirector, buildingHandlers);
        }
        else
        {
            // No warehouse card: ME only.
            remaining = pullFromMe(player, aeKey, remaining, inventory, actionSource, redirector, buildingHandlers);
        }

        long totalInserted = realCount - remaining;

        // Feedback
        if (totalInserted > 0)
        {
            // Notify MineColonies that the builder hut inventory changed so the
            // builder re-evaluates its needed resources immediately — without this
            // the citizen stays blocked until a manual restart or item re-placement.
            try
            {
                IColony colony = IColonyManager.getInstance().getClosestColony(level, builderPos);
                if (colony != null)
                {
                    for (IBuilding b : colony.getServerBuildingManager().getBuildings().values())
                    {
                        if (!b.getPosition().equals(builderPos)) continue;
                        b.markDirty();
                        // v1.4.11 — credit the delivered resource into the build tally
                        // (getNeededResources) so the line clears immediately, even when
                        // the builder is far away and does not recompute. See the helper.
                        creditDeliveredResource(b, stack, (int) totalInserted);
                        // v1.6.1 — open-request lines (Priority Request) are not covered
                        // by the credit above: guard them against the cascade.
                        markOpenRequestSent(b, stack, wandStack, builderPos);
                        break;
                    }
                }
            }
            catch (Exception e)
            {
                ColonyLink.LOGGER.debug("[ColonyLink] markDirty after send failed: {}", e.getMessage());
            }

            player.sendSystemMessage(Component.translatable("colonylink.stb.sent", totalInserted, stack.getDisplayName()));
            if (remaining > 0)
                player.sendSystemMessage(Component.translatable("colonylink.handler.builder_inv_full"));
        }
        else
        {
            // If the redirector just entered STANDBY, the orange message is enough — no duplicate red one.
            if (redirector.getState() == ColonyLinkRedirectorBlockEntity.RedirectorState.STANDBY)
                player.sendSystemMessage(Component.translatable("colonylink.handler.builder_inv_full"));
            else
                player.sendSystemMessage(Component.translatable("colonylink.stb.could_not_send", stack.getDisplayName()));
        }
    }

    /**
     * v1.4.11 — Immediately credits the delivered resource into the build tally.
     *
     * The Clipboard's "Priority Request" list comes from getNeededResources()
     * (miss = amount - available), where `available` is what the builder holds in its hut.
     * MineColonies only recomputes `available` on the builder's work TICK — frozen when the
     * player is far away. So the item does land in the rack, but `available` stays stale →
     * the line does not clear (and sends can cascade) until the player returns near the colony.
     *
     * Here, on deposit, we credit `available` by the amount actually inserted (capped at the
     * miss). getNeededResources() then reflects the deposit → the ticker (which runs even
     * remotely, ungated) recomputes the list → the line clears immediately and uniformly.
     *
     * Safe: the builder's native recompute (on return) does setAvailable(0) then re-adds the
     * REAL inventory contents (rack included) → our early credit is OVERWRITTEN by the exact
     * value, never double-counted. The map is unmodifiable but its values are mutable.
     * 100% server-side, a one-off cost on click (outside the tick).
     */
    private static void creditDeliveredResource(IBuilding building, ItemStack delivered, int insertedCount)
    {
        if (insertedCount <= 0 || delivered.isEmpty()) return;
        if (!(building instanceof AbstractBuildingStructureBuilder bb)) return;

        try
        {
            var needed = bb.getNeededResources();   // live map: mutable values
            if (needed == null) return;

            for (BuildingBuilderResource res : needed.values())
            {
                if (!ItemStack.isSameItemSameComponents(res.getItemStack(), delivered)) continue;

                int missing = res.getAmount() - res.getAvailable();
                if (missing <= 0) return;            // already covered, nothing to credit
                res.addAvailable(Math.min(missing, insertedCount));
                bb.markDirty();
                return;                              // only one resource matches per item
            }
        }
        catch (Exception e)
        {
            ColonyLink.LOGGER.debug("[ColonyLink] needed-resource credit skipped: {}", e.getMessage());
        }
    }

    /**
     * v1.6.1 — Anti-cascade for the Clipboard's « Priority Request » line.
     *
     * That line is fed by getOpenRequests() (tools, armor, food...), NOT by
     * getNeededResources(). creditDeliveredResource only credits the latter, and a
     * direct BUILDER-mode fulfill bypasses the courier — so nothing tells the request
     * system the delivery happened. MineColonies closes the open request on its own
     * work tick, which is why the line sometimes clears at once and sometimes not at
     * all. Meanwhile the button stays active: re-clicks cascade and drain the ME.
     *
     * We record the same sent-key the WAREHOUSE path uses: the ticker then renders the
     * line grey SENT_PENDING (Send disabled) and pruneBuilderSentKeys drops the key as
     * soon as the open request is gone. Reconciliation is MineColonies' own truth —
     * never a timer — so a slow reconcile only means a longer grey, never a cascade.
     *
     * Baseline 0: open requests carry no 'available' counter (same convention as
     * SendToWarehousePacket). Build materials are skipped on purpose — they already
     * vanish via creditDeliveredResource, so BUILDER-mode material lines are untouched.
     */
    private static void markOpenRequestSent(IBuilding building, ItemStack delivered,
                                            ItemStack wandStack, BlockPos builderPos)
    {
        if (delivered.isEmpty() || wandStack == null) return;
        if (!(building instanceof AbstractBuildingStructureBuilder bb)) return;

        try
        {
            // Build material → creditDeliveredResource owns it, no key.
            var needed = bb.getNeededResources();
            if (needed != null)
                for (BuildingBuilderResource res : needed.values())
                    if (ItemStack.isSameItemSameComponents(res.getItemStack(), delivered)) return;

            // Open request only → guard the line until MineColonies reconciles.
            if (!matchesOpenRequest(bb, delivered)) return;

            ColonyLinkWandLinkableHandler.addSentRequestKey(wandStack,
                    ColonyLinkWandLinkableHandler.builderSentKey(builderPos, delivered.getItem(), 0));
        }
        catch (Exception e)
        {
            ColonyLink.LOGGER.debug("[ColonyLink] open-request sent-key skipped: {}", e.getMessage());
        }
    }

    /** True when the builder has a live open request matching this stack.
     *  Mirrors the ticker's fetchBuilderRequest pass 1 (IDeliverable.matches). */
    private static boolean matchesOpenRequest(AbstractBuildingStructureBuilder bb, ItemStack stack)
    {
        try
        {
            if (bb.getAllAssignedCitizen().isEmpty()) return false;
            var citizen = bb.getAllAssignedCitizen().iterator().next();
            var reqs = bb.getOpenRequests(citizen.getId());
            if (reqs == null) return false;
            for (com.minecolonies.api.colony.requestsystem.request.IRequest<?> req : reqs)
            {
                if (req.getState() == com.minecolonies.api.colony.requestsystem.request.RequestState.CANCELLED
                        || req.getState() == com.minecolonies.api.colony.requestsystem.request.RequestState.OVERRULED)
                    continue;
                if (!(req.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.IDeliverable del))
                    continue;
                if (del.matches(stack)) return true;
                // v1.6.3 — parity with fetchBuilderRequest, which derives the Priority
                // Request line from getDisplayStacks(), NOT del.matches(). Some deliverables
                // (tools, food...) do not match their own display example, so the line was
                // shown but the send rejected as "not needed". Accept the exact display
                // stack we actually offered (components matched, count ignored).
                var ds = req.getDisplayStacks();
                if (ds != null)
                    for (ItemStack disp : ds)
                        if (!disp.isEmpty() && ItemStack.isSameItemSameComponents(disp, stack))
                            return true;
            }
        }
        catch (Exception e)
        {
            ColonyLink.LOGGER.debug("[ColonyLink] open-request match failed: {}", e.getMessage());
        }
        return false;
    }

    /**
     * v1.6.2 — True if {@code stack} is something this builder legitimately needs
     * right now, so a direct ME→hut send is authorised. Mirrors what the GUI /
     * fetchBuilderRequest would emit, so the intentional tool substitution
     * (v1.6.0) keeps working: the sent item is often the substituted tool, not the
     * raw needed-resource item.
     *
     * Accepts when {@code stack} is:
     *   1. a missing build material (getNeededResources, miss > 0), directly or as
     *      a valid tool substitute of one;
     *   2. a live open request of the assigned citizen (IDeliverable.matches),
     *      directly or as a valid tool/armor substitute of one.
     *
     * Fail-safe: any unexpected error returns true (never block a legit send on a
     * bug); the caller already skips this check entirely when the grid/builder
     * cannot be resolved.
     */
    private static boolean isLegitimateBuilderSend(
            AbstractBuildingStructureBuilder bb, ItemStack stack, int buildingLevel,
            appeng.api.stacks.KeyCounter inv,
            appeng.api.networking.crafting.ICraftingService cs, boolean toolUpgrade)
    {
        if (stack.isEmpty()) return false;
        try
        {
            BuilderToolHelper.ToolInventoryView invView = BuilderToolHelper.fromAE2Inventory(inv);
            BuilderToolHelper.ToolCraftingView csView  = BuilderToolHelper.fromAE2CraftingService(cs);

            // 1) Missing build material — direct, or via tool substitution.
            var needed = bb.getNeededResources();
            if (needed != null)
            {
                for (BuildingBuilderResource res : needed.values())
                {
                    if (res.getAmount() - res.getAvailable() <= 0) continue;
                    ItemStack req = res.getItemStack();
                    if (ItemStack.isSameItemSameComponents(req, stack)) return true;
                    if (toolUpgrade && BuilderToolHelper.isTool(req)
                            && toolSubstituteMatches(req, stack, buildingLevel, invView, csView))
                        return true;
                }
            }

            // 2) Live open request — direct match first (reuses matchesOpenRequest),
            //    then tool/armor substitution against each request's display stack.
            if (matchesOpenRequest(bb, stack)) return true;
            if (toolUpgrade && !bb.getAllAssignedCitizen().isEmpty())
            {
                var citizen = bb.getAllAssignedCitizen().iterator().next();
                var reqs = bb.getOpenRequests(citizen.getId());
                if (reqs != null)
                {
                    for (com.minecolonies.api.colony.requestsystem.request.IRequest<?> req : reqs)
                    {
                        if (req.getState() == com.minecolonies.api.colony.requestsystem.request.RequestState.CANCELLED
                                || req.getState() == com.minecolonies.api.colony.requestsystem.request.RequestState.OVERRULED)
                            continue;
                        if (!(req.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.IDeliverable))
                            continue;
                        var ds = req.getDisplayStacks();
                        if (ds == null) continue;
                        for (ItemStack disp : ds)
                        {
                            if (disp.isEmpty()) continue;
                            if (BuilderToolHelper.isTool(disp)
                                    && toolSubstituteMatches(disp, stack, buildingLevel, invView, csView))
                                return true;
                            if (BuilderToolHelper.isArmor(disp)
                                    && armorSubstituteMatches(disp, stack, buildingLevel, invView, csView))
                                return true;
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            ColonyLink.LOGGER.debug("[ColonyLink] isLegitimateBuilderSend failed: {}", e.getMessage());
            return true; // fail-safe: never block a potentially legitimate send on a bug
        }
        return false;
    }

    /** True if the best tool substitute for {@code requested} equals {@code candidate}. */
    private static boolean toolSubstituteMatches(ItemStack requested, ItemStack candidate,
                                                 int level, BuilderToolHelper.ToolInventoryView inv, BuilderToolHelper.ToolCraftingView cs)
    {
        BuilderToolHelper.SubstituteResult sub = BuilderToolHelper.findBestTool(requested, level, inv, cs);
        return sub.action() != BuilderToolHelper.SubstituteAction.NONE
                && ItemStack.isSameItemSameComponents(sub.displayStack(), candidate);
    }

    /** True if the best armor substitute for {@code requested} equals {@code candidate}. */
    private static boolean armorSubstituteMatches(ItemStack requested, ItemStack candidate,
                                                  int level, BuilderToolHelper.ToolInventoryView inv, BuilderToolHelper.ToolCraftingView cs)
    {
        BuilderToolHelper.SubstituteResult sub = BuilderToolHelper.findBestArmor(requested, level, inv, cs);
        return sub.action() != BuilderToolHelper.SubstituteAction.NONE
                && ItemStack.isSameItemSameComponents(sub.displayStack(), candidate);
    }

    // Extraction primitives (void-safe)

    /**
     * v1.4.9 — Drains up to {@code remaining} items from the ME network into the
     * builder handlers. Any surplus that cannot be stored returns to the ME (never voided).
     *
     * @return the new remaining
     */
    private static int pullFromMe(
            ServerPlayer player,
            AEItemKey aeKey, int remaining, MEStorage inventory, IActionSource actionSource,
            ColonyLinkRedirectorBlockEntity redirector, List<IItemHandler> buildingHandlers)
    {
        if (aeKey == null) return remaining;

        while (remaining > 0)
        {
            int batchSize = Math.min(remaining, 64);
            long extracted = inventory.extract(aeKey, batchSize, Actionable.MODULATE, actionSource);
            if (extracted <= 0) break;

            ItemStack toInsert = aeKey.toStack((int) extracted);
            ItemStack leftOver = insertIntoHandlers(buildingHandlers, toInsert);
            int sent = (int) extracted - leftOver.getCount();
            remaining -= sent;

            if (!leftOver.isEmpty())
            {
                // Builder full → return the surplus to the ME (never voided).
                // v1.6.0 — VERIFY the re-insert (an overflow/void cell or a full
                // network can silently discard); player inventory is the
                // last-resort sink so nothing is ever voided.
                long reinserted = inventory.insert(aeKey, leftOver.getCount(),
                        Actionable.MODULATE, actionSource);
                int lost = leftOver.getCount() - (int) reinserted;
                if (lost > 0)
                    player.getInventory().placeItemBackInInventory(aeKey.toStack(lost));
                redirector.setState(ColonyLinkRedirectorBlockEntity.RedirectorState.STANDBY);
                break;
            }
        }
        return remaining;
    }

    /**
     * v1.4.9 — Drains up to {@code remaining} items (exact component match) from the
     * Warehouse racks into the builder handlers. Any surplus that cannot be stored returns
     * to the racks, then to the player inventory as a last resort. Never voided.
     *
     * @return the new remaining
     */
    private static int pullFromWarehouse(
            ServerPlayer player, ServerLevel level, ItemStack stack, int remaining,
            BuildingWareHouse warehouse,
            ColonyLinkRedirectorBlockEntity redirector, List<IItemHandler> buildingHandlers)
    {
        if (remaining <= 0) return remaining;

        // 1.7.0-beta.2 — the card's Warehouse, resolved by the caller (card colony ==
        // the linked Builder's colony), instead of the first Warehouse of the building
        // map of ColonyResolver.forRedirector's colony.
        if (warehouse == null) return remaining;

        {
            try
            {
                var containerList = warehouse.getContainers();
                if (containerList == null || containerList.isEmpty()) return remaining;

                for (BlockPos rackPos : containerList)
                {
                    if (remaining <= 0) break;
                    IItemHandler rackHandler = CapCompat.itemHandler(level, rackPos, null);
                    if (rackHandler == null) continue;

                    for (int slot = 0; slot < rackHandler.getSlots() && remaining > 0; slot++)
                    {
                        ItemStack inSlot = rackHandler.getStackInSlot(slot);
                        if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, stack)) continue;

                        int toExtract = Math.min(inSlot.getCount(), remaining);
                        ItemStack took = rackHandler.extractItem(slot, toExtract, false);
                        if (took.isEmpty()) continue;

                        ItemStack leftOver = insertIntoHandlers(buildingHandlers, took);
                        int sent = took.getCount() - leftOver.getCount();
                        remaining -= sent;

                        if (!leftOver.isEmpty())
                        {
                            // Builder full → surplus returned to the rack, then to the player.
                            ItemStack back = leftOver.copy();
                            for (int s2 = 0; s2 < rackHandler.getSlots() && !back.isEmpty(); s2++)
                                back = rackHandler.insertItem(s2, back, false);
                            if (!back.isEmpty())
                                player.getInventory().placeItemBackInInventory(back);
                            redirector.setState(ColonyLinkRedirectorBlockEntity.RedirectorState.STANDBY);
                            return remaining;
                        }
                    }
                }
            }
            catch (Exception e)
            {
                ColonyLink.LOGGER.debug("[ColonyLink] Warehouse extraction error: {}", e.getMessage());
            }
        }
        return remaining;
    }

    // Helpers

    /**
     * Inserts an ItemStack into a list of handlers (MineColonies racks).
     * Returns the surplus that was not inserted.
     */
    static ItemStack insertIntoHandlers(List<IItemHandler> handlers, ItemStack stack)
    {
        ItemStack remainder = stack.copy();
        for (IItemHandler handler : handlers)
        {
            for (int slot = 0; slot < handler.getSlots() && !remainder.isEmpty(); slot++)
                remainder = handler.insertItem(slot, remainder, false);
            if (remainder.isEmpty()) return ItemStack.EMPTY;
        }
        return remainder;
    }

    static ItemStack findWandInInventory(ServerPlayer player)
    {
        // Delegate to the shared implementation that also checks Curios slots.
        return ColonyLinkServerTicker.findWandInInventory(player);
    }

    private static IWirelessAccessPoint getWap(ItemStack wandStack, ServerLevel level)
    {
        net.minecraft.core.GlobalPos linkedPos = ColonyLinkWandLinkableHandler.getLinkedPos(wandStack);
        if (linkedPos == null) return null;
        ServerLevel targetLevel = level.getServer().getLevel(linkedPos.dimension());
        if (targetLevel == null) return null;
        var be = targetLevel.getBlockEntity(linkedPos.pos());
        if (be instanceof IWirelessAccessPoint wap) return wap;
        return null;
    }
}