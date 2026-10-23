package com.colonylink.colonylink;

import net.minecraft.world.item.ItemStack;

/**
 * Client-only RF cache.
 *
 * Problem: when the server edits an ItemStack's NBT in the player's inventory
 * (stack.update(DataComponents.CUSTOM_DATA, ...)), Minecraft sends a
 * ClientboundContainerSetSlotPacket that forces the client to visually replace the
 * whole item — causing the hotbar "pop/flash" every 2s.
 *
 * Solution: this cache hides the BAR render — ColonyLinkWand.getBarWidth/getBarColor
 * read the value pushed by ColonyLinkPacket (rfStored/rfMax) instead of re-reading NBT
 * each frame, so the bar no longer flickers.
 *
 * Note (v1.6.2): the server DOES keep draining the wand's RF in NBT at rest (passive
 * drain, GUI open: see ColonyLinkServerTicker.onServerTick + WandEnergyStorage.setStoredRF).
 * Since RF is stored in NBT, decrementing it necessarily means an NBT write. This cache
 * only suppresses the VISUAL bar flicker; the NBT write and slot resync remain (minimal
 * network cost, v1.6.2 decision = keep passive drain as is).
 *
 * Usage:
 *   ColonyLinkPacket.handle() → ClientRFCache.update(rfStored, rfMax)
 *   ColonyLinkWand.getBarWidth() → ClientRFCache.getStoredRF()
 *   ColonyLinkWand.getBarColor() → ClientRFCache.getStoredRF()
 */
public class ClientRFCache
{
    // RF from the last server packet
    private static long cachedRF  = 0L;
    private static long cachedMax = 1_600_000L;

    // Last update timestamp (ms) — for TTL invalidation
    private static long lastUpdateMs = 0L;
    private static final long CACHE_TTL_MS = 10_000L; // 10s without a packet → fall back to NBT

    /**
     * Called by ColonyLinkPacket.handle() on each packet received from the server.
     */
    public static void update(long rfStored, long rfMax)
    {
        cachedRF      = rfStored;
        cachedMax     = rfMax > 0 ? rfMax : 1_600_000L;
        lastUpdateMs  = System.currentTimeMillis();
    }

    /**
     * Invalidates the cache (GUI closed, player disconnected, etc.).
     */
    public static void invalidate()
    {
        lastUpdateMs = 0L;
    }

    /**
     * Returns true if the cache is valid (GUI opened recently).
     */
    public static boolean isValid()
    {
        return lastUpdateMs > 0L
                && (System.currentTimeMillis() - lastUpdateMs) < CACHE_TTL_MS;
    }

    /**
     * RF currently stored per the server; returns -1 when the cache is invalid
     * (signal to read NBT instead).
     */
    public static long getStoredRF()
    {
        return isValid() ? cachedRF : -1L;
    }

    public static long getMaxRF()
    {
        return cachedMax;
    }

    /**
     * Charge percent (0–100); computed from the passed stack's NBT when the cache
     * is invalid.
     */
    public static int getPercent(ItemStack stack)
    {
        long stored, max;
        if (isValid())
        {
            stored = cachedRF;
            max    = cachedMax;
        }
        else
        {
            stored = WandEnergyStorage.getStoredRF(stack);
            max    = ColonyLinkConfig.safeGet(ColonyLinkConfig.WAND_RF_CAPACITY, 160_000L);
        }
        // v1.6.1 — clamp [0..100]: if the cap drops below stored, cap the display
        // instead of an absurd percentage (e.g. 625%).
        return max > 0 ? (int) Math.max(0L, Math.min(100L, stored * 100L / max)) : 0;
    }

    /**
     * Durability bar width (0..13) from the cache or NBT.
     */
    public static int getBarWidth(ItemStack stack)
    {
        long stored, max;
        if (isValid())
        {
            stored = cachedRF;
            max    = cachedMax;
        }
        else
        {
            stored = WandEnergyStorage.getStoredRF(stack);
            max    = ColonyLinkConfig.safeGet(ColonyLinkConfig.WAND_RF_CAPACITY, 160_000L);
        }
        if (max <= 0) return 0;
        // v1.6.1 — clamp [0..13]: keeps the bar from overflowing the slot (green bar
        // across the screen) when stored > max after a capacity drop.
        return (int) Math.max(0L, Math.min(13L, stored * 13L / max));
    }

    /**
     * Durability bar color from the cache or NBT.
     */
    public static int getBarColor(ItemStack stack)
    {
        int pct = getPercent(stack);
        int threshold = ColonyLinkConfig.safeGet(ColonyLinkConfig.LOW_POWER_THRESHOLD_PERCENT, 10);
        if (pct <= threshold) return 0xFF2222; // red
        if (pct <= 30)        return 0xFFAA00; // yellow
        return 0x22CC22;                        // green
    }
}