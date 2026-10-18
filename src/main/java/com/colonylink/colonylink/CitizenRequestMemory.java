package com.colonylink.colonylink;

import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.player.IPlayerRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.retrying.IRetryingRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractWarehouseRequestResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1.7.0-beta.3 — server-side memory of the Citizens tab "Sent" / "Crafting…" marks.
 *
 * Shared per colony (a Send by one player greys the row for every viewer), keyed by
 * the MineColonies request UUID, which stays the same for the whole life of a
 * request (reassignment to the Warehouse keeps it). RAM only: losing it on a
 * restart costs nothing, rows just show "Send" again.
 *
 * An entry is dropped only when MineColonies is done with the request (gone from
 * the request manager, or in a terminal state) — never because one scan did not
 * list it. That was the pre-beta.3 bug: the Clipboard's "c|name|item" keys were
 * pruned whenever a single scan missed the row.
 *
 * Server thread only (packet handlers run through enqueueWork, the ticker on the
 * server tick), so plain maps are enough.
 */
public final class CitizenRequestMemory
{
    private CitizenRequestMemory() {}

    /** Colony identity: MineColonies reuses colony IDs, so the centre is the fingerprint. */
    public record ColonyKey(ResourceKey<Level> dim, int colonyId, BlockPos center)
    {
        public static ColonyKey of(IColony colony)
        {
            return new ColonyKey(colony.getDimension(), colony.getID(), colony.getCenter());
        }
    }

    public enum Mark
    {
        SENT, CRAFTING;

        /** CitizensPacket.MARK_* code. */
        public byte code() { return this == SENT ? CitizensPacket.MARK_SENT : CitizensPacket.MARK_CRAFTING; }
    }

    /**
     * @param wapPos 1.7.0-beta.3 — the Clipboard's Wireless Access Point at mark time
     *               (CRAFTING: the AE2 network the craft was started on; null if unknown)
     * @param stack  1.7.0-beta.3 — exact stack (with components) for the AE2 craft check
     */
    public record Entry(Mark mark, UUID playerId, String playerName, long gameTime, Item item, int count,
                        GlobalPos wapPos, ItemStack stack) {}

    public static final int MAX_PER_COLONY = 1000;

    /** A CRAFTING mark older than this (10 min) is dropped: finished, cancelled or WAP offline. */
    public static final long CRAFTING_MAX_AGE_TICKS = 20L * 60 * 10;

    /** No AE2 check during the first 10 s of a CRAFTING mark (craft still being planned). */
    public static final long CRAFTING_PLAN_GRACE_TICKS = 20L * 10;

    /** States after which MineColonies no longer needs anything for the request. */
    private static final Set<RequestState> TERMINAL = EnumSet.of(
            RequestState.COMPLETED, RequestState.RECEIVED, RequestState.CANCELLED,
            RequestState.OVERRULED, RequestState.FAILED);

    private static final Map<ColonyKey, LinkedHashMap<UUID, Entry>> MEMORY = new HashMap<>();

    // ── Memory ────────────────────────────────────────────────────────────────

    /** Adds or replaces the mark of a request; drops the oldest entry past MAX_PER_COLONY. */
    public static void mark(ColonyKey colony, UUID requestId, Entry entry)
    {
        LinkedHashMap<UUID, Entry> table = MEMORY.computeIfAbsent(colony, k -> new LinkedHashMap<>());
        table.remove(requestId); // re-insert at the end: a re-mark is the newest entry
        table.put(requestId, entry);
        while (table.size() > MAX_PER_COLONY)
        {
            Iterator<UUID> it = table.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    public static Entry get(ColonyKey colony, UUID requestId)
    {
        LinkedHashMap<UUID, Entry> table = MEMORY.get(colony);
        return table == null ? null : table.get(requestId);
    }

    /**
     * Drops the entries whose request is gone or finished in MineColonies, and the
     * CRAFTING marks whose AE2 craft is over (see {@link #craftOver}).
     */
    public static void cleanup(ColonyKey colony, IRequestManager manager, MinecraftServer server)
    {
        LinkedHashMap<UUID, Entry> table = MEMORY.get(colony);
        if (table == null) return;
        table.entrySet().removeIf(e -> shouldDrop(manager, server, e.getKey(), e.getValue()));
        if (table.isEmpty()) MEMORY.remove(colony);
    }

    /**
     * Periodic pass over every colony in memory (also while no one has the Citizens
     * tab open). A colony that cannot be found, or whose centre differs (ID handed to
     * a new colony), loses its whole table.
     */
    public static void cleanupAll(MinecraftServer server)
    {
        if (MEMORY.isEmpty()) return;
        Iterator<Map.Entry<ColonyKey, LinkedHashMap<UUID, Entry>>> it = MEMORY.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<ColonyKey, LinkedHashMap<UUID, Entry>> slot = it.next();
            ColonyKey key = slot.getKey();
            IColony colony = server.getLevel(key.dim()) == null ? null
                    : IColonyManager.getInstance().getColonyByDimension(key.colonyId(), key.dim());
            if (colony == null || !key.center().equals(colony.getCenter()))
            {
                it.remove();
                continue;
            }
            IRequestManager manager = colony.getRequestManager();
            slot.getValue().entrySet().removeIf(e -> shouldDrop(manager, server, e.getKey(), e.getValue()));
            if (slot.getValue().isEmpty()) it.remove();
        }
    }

    /** Empties everything (integrated server: statics outlive the world). */
    public static void clear()
    {
        MEMORY.clear();
    }

    // ── MineColonies request helpers ──────────────────────────────────────────

    public static IToken<?> token(UUID requestId)
    {
        return new StandardToken(requestId);
    }

    /** UUID of a request (StandardToken implements IToken<UUID>), or null if another token type. */
    public static UUID requestUuid(IRequest<?> request)
    {
        IToken<?> id = request.getId();
        return id != null && id.getIdentifier() instanceof UUID uuid ? uuid : null;
    }

    private static boolean shouldDrop(IRequestManager manager, MinecraftServer server, UUID requestId, Entry entry)
    {
        IRequest<?> request;
        try
        {
            request = manager.getRequestForToken(token(requestId));
        }
        catch (Exception e)
        {
            return true;
        }
        if (request == null || TERMINAL.contains(request.getState())) return true;
        return entry.mark() == Mark.CRAFTING && craftOver(manager, server, request, entry);
    }

    /**
     * CRAFTING mark end. Older than CRAFTING_MAX_AGE_TICKS: over (time safety net).
     * While the request is still the player's to provide, asks the AE2 network of the
     * WAP recorded at craft time: no longer requesting that item = the craft finished
     * (or was cancelled), so the row can offer Send again. WAP unloaded / offline /
     * unknown: keep the mark (the time net ends it). Once MineColonies has the request
     * (Warehouse, courier, crafter) the mark is kept: the row shows that state anyway.
     */
    private static boolean craftOver(IRequestManager manager, MinecraftServer server, IRequest<?> request, Entry entry)
    {
        ServerLevel overworld = server.overworld();
        long age = overworld != null ? overworld.getGameTime() - entry.gameTime() : 0L;
        if (age > CRAFTING_MAX_AGE_TICKS) return true;
        // AE2 plans the craft asynchronously (CraftHandler: beginCraftingCalculation, then
        // submitJob): isRequesting is still false right after the click. Give it a grace.
        if (age < CRAFTING_PLAN_GRACE_TICKS) return false;
        byte state = classifyRequest(manager, request);
        if (state != STATE_PLAYER && state != STATE_RETRYING) return false;
        if (entry.wapPos() == null || entry.stack() == null || entry.stack().isEmpty()) return false;
        ServerLevel wapLevel = server.getLevel(entry.wapPos().dimension());
        if (wapLevel == null || !wapLevel.isLoaded(entry.wapPos().pos())) return false;
        if (!(wapLevel.getBlockEntity(entry.wapPos().pos()) instanceof IWirelessAccessPoint wap)) return false;
        IGrid grid = wap.getGrid();
        if (grid == null) return false;
        AEItemKey key = AEItemKey.of(entry.stack());
        return key != null && !grid.getCraftingService().isRequesting(key);
    }

    // ── Classification of who handles a request ───────────────────────────────

    // Codes shared with the client through CitizensPacket (no MineColonies class there).
    private static final byte STATE_PLAYER       = CitizensPacket.STATE_PLAYER;
    private static final byte STATE_RETRYING     = CitizensPacket.STATE_RETRYING;
    private static final byte STATE_WAREHOUSE    = CitizensPacket.STATE_WAREHOUSE;
    private static final byte STATE_DELIVERING   = CitizensPacket.STATE_DELIVERING;
    private static final byte STATE_COLONY_CRAFT = CitizensPacket.STATE_COLONY_CRAFT;

    /**
     * Who handles the request, from its state AND its resolver: IN_PROGRESS alone is
     * ambiguous, MineColonies puts every assigned request in IN_PROGRESS, including
     * the ones waiting for the player (RequestHandler.resolveRequest).
     */
    public static byte classifyRequest(IRequestManager manager, IRequest<?> request)
    {
        if (request.getState() == RequestState.FOLLOWUP_IN_PROGRESS) return STATE_DELIVERING;
        IRequestResolver<?> resolver;
        try
        {
            resolver = manager.getResolverForRequest(request.getId());
        }
        catch (Exception e)
        {
            return STATE_PLAYER; // not assigned yet (CREATED / REPORTED)
        }
        if (resolver == null || resolver instanceof IPlayerRequestResolver) return STATE_PLAYER;
        if (resolver instanceof IRetryingRequestResolver) return STATE_RETRYING;
        // Warehouse: normally IN_PROGRESS or RESOLVED here (FOLLOWUP handled above). A
        // transient ASSIGNED is still the Warehouse's, never the player's: offering Send
        // for it would deposit items MineColonies already counts on.
        if (resolver instanceof AbstractWarehouseRequestResolver) return STATE_WAREHOUSE;
        return STATE_COLONY_CRAFT;
    }

    /** Class name of the request's resolver, for the debug log ("none" if unassigned). */
    public static String resolverName(IRequestManager manager, IRequest<?> request)
    {
        try
        {
            IRequestResolver<?> r = manager.getResolverForRequest(request.getId());
            return r == null ? "none" : r.getClass().getSimpleName();
        }
        catch (Exception e)
        {
            return "none";
        }
    }
}
