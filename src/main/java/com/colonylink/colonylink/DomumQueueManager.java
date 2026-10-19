package com.colonylink.colonylink;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * DomumQueueManager — v1.6.1
 *
 * Domum queue SHARED per AE2 network (grid): one list per IGrid, server-side. Every
 * Warehouse Link Terminal on the same grid reads and writes this single list, so a
 * queued item shows on ALL terminals of the network (shared cable, Quantum Ring, etc.),
 * not just the one that was clicked.
 *
 * Persistence: each terminal keeps an NBT copy of the list (field domumQueue), used as a
 * NET to reseed the manager if the grid reforms (reconnection, world reload, Quantum Ring
 * (re)connection) — an IGrid identity is not stable. WeakHashMap: when a grid dies (no
 * strong reference left, e.g. after a world unload) its entry is evicted by the GC.
 *
 * Server-thread only; methods synchronized defensively (several terminals tick).
 * The seed is a Supplier: evaluated ONLY on first access to a grid (computeIfAbsent),
 * so there is no cost when the shared list already exists.
 */
public final class DomumQueueManager
{
    private static final Map<IGrid, List<ItemStack>> QUEUES = new WeakHashMap<>();

    // A3 — hard cap on the shared queue size. Legitimate use stays well under a few
    // dozen entries (pruneCraftable drains it continuously as patterns become
    // craftable). The bound protects the NBT copy persisted on every terminal of the
    // grid (save bloat) and the size of the full-list DomumQueueSyncPacket broadcast.
    public static final int MAX_QUEUE_SIZE = 128;

    private DomumQueueManager() {}

    private static List<ItemStack> queueFor(IGrid grid, Supplier<List<ItemStack>> seed)
    {
        return QUEUES.computeIfAbsent(grid, g -> {
            List<ItemStack> l = new ArrayList<>();
            if (seed != null)
            {
                List<ItemStack> s = seed.get();
                if (s != null)
                    for (ItemStack it : s)
                        if (!it.isEmpty()) l.add(it.copyWithCount(1));
            }
            // A3 — truncate a reseeded queue to the cap. A world saved before this guard
            // existed can carry a bloated NBT queue; keep the first MAX_QUEUE_SIZE entries
            // so the fix also repairs existing saves. Debug-log the dropped count.
            if (l.size() > MAX_QUEUE_SIZE)
            {
                int dropped = l.size() - MAX_QUEUE_SIZE;
                l.subList(MAX_QUEUE_SIZE, l.size()).clear();
                ColonyLink.LOGGER.debug("[DomumQueue] Reseeded queue exceeded cap: dropped {} entries (kept {}).",
                        dropped, MAX_QUEUE_SIZE);
            }
            return l;
        });
    }

    /** Copy of the shared queue (display / sync / NBT persistence). */
    public static synchronized List<ItemStack> snapshot(IGrid grid, Supplier<List<ItemStack>> seed)
    {
        if (grid == null) return new ArrayList<>();
        return new ArrayList<>(queueFor(grid, seed));
    }

    /** True if the grid has no list yet or it is empty. Does not seed. */
    public static synchronized boolean isEmpty(IGrid grid)
    {
        if (grid == null) return true;
        List<ItemStack> q = QUEUES.get(grid);
        return q == null || q.isEmpty();
    }

    public static synchronized boolean isQueued(IGrid grid, Supplier<List<ItemStack>> seed, ItemStack stack)
    {
        if (grid == null || stack.isEmpty()) return false;
        for (ItemStack s : queueFor(grid, seed))
            if (ItemStack.isSameItemSameComponents(s, stack)) return true;
        return false;
    }

    /** True if this grid's queue has hit the cap. Does not seed. */
    public static synchronized boolean isFull(IGrid grid)
    {
        if (grid == null) return false;
        List<ItemStack> q = QUEUES.get(grid);
        return q != null && q.size() >= MAX_QUEUE_SIZE;
    }

    /** Adds if absent. Returns true if added (false = already queued OR queue full). */
    public static synchronized boolean add(IGrid grid, Supplier<List<ItemStack>> seed, ItemStack stack)
    {
        if (grid == null || stack.isEmpty()) return false;
        List<ItemStack> q = queueFor(grid, seed);
        for (ItemStack s : q)
            if (ItemStack.isSameItemSameComponents(s, stack)) return false;
        // A3 — authoritative cap: refuse once the queue is full. Placed after the dedup
        // scan so an already-queued item keeps returning false for the same reason as
        // before (dedup behaviour unchanged). Callers distinguish full via isFull(grid).
        if (q.size() >= MAX_QUEUE_SIZE) return false;
        q.add(stack.copyWithCount(1));
        return true;
    }

    /** Removes by index (pattern encode / manual removal). */
    public static synchronized void removeAt(IGrid grid, Supplier<List<ItemStack>> seed, int index)
    {
        if (grid == null) return;
        List<ItemStack> q = queueFor(grid, seed);
        if (index >= 0 && index < q.size()) q.remove(index);
    }

    /** Removes all items that became craftable on the grid. Returns true if changed. */
    public static synchronized boolean pruneCraftable(IGrid grid, Supplier<List<ItemStack>> seed, ICraftingService crafting)
    {
        if (grid == null || crafting == null) return false;
        List<ItemStack> q = queueFor(grid, seed);
        boolean changed = false;
        for (int i = q.size() - 1; i >= 0; i--)
        {
            AEItemKey key = AEItemKey.of(q.get(i));
            if (key != null && crafting.isCraftable(key))
            {
                q.remove(i);
                changed = true;
            }
        }
        return changed;
    }
}