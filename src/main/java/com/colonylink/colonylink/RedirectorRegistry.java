package com.colonylink.colonylink;

import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 1.7.0-beta.4 — server-side index: Redirector UUID → loaded block entity.
 *
 * Lets a Clipboard entry find its Redirector after the block moved: a Create:
 * Aeronautics / Sable ship reassembly puts the block at a new position (new plot)
 * but copies its NBT, UUID included. RAM only, filled by the loaded block entities
 * themselves (onLoad / setRemoved); weak references, so a missed unregister cannot
 * leak. Server thread only.
 */
public final class RedirectorRegistry
{
    private RedirectorRegistry() {}

    private static final Map<UUID, WeakReference<ColonyLinkRedirectorBlockEntity>> REGISTRY = new HashMap<>();

    public static void register(UUID id, ColonyLinkRedirectorBlockEntity be)
    {
        if (id != null && be != null) REGISTRY.put(id, new WeakReference<>(be));
    }

    /**
     * Removes the entry only if it still points at {@code be}. During a move the new
     * block entity registers the same UUID before the old one is removed: the old
     * one's setRemoved must not drop the new mapping.
     */
    public static void unregister(UUID id, ColonyLinkRedirectorBlockEntity be)
    {
        if (id == null) return;
        WeakReference<ColonyLinkRedirectorBlockEntity> ref = REGISTRY.get(id);
        if (ref == null) return;
        ColonyLinkRedirectorBlockEntity current = ref.get();
        if (current == null || current == be) REGISTRY.remove(id);
    }

    /** Loaded, not removed Redirector with this UUID in {@code level}, or null. */
    public static ColonyLinkRedirectorBlockEntity resolve(UUID id, Level level)
    {
        if (id == null) return null;
        WeakReference<ColonyLinkRedirectorBlockEntity> ref = REGISTRY.get(id);
        if (ref == null) return null;
        ColonyLinkRedirectorBlockEntity be = ref.get();
        if (be == null || be.isRemoved())
        {
            REGISTRY.remove(id);
            return null;
        }
        return be.getLevel() == level ? be : null;
    }

    /**
     * The Redirector of a Clipboard entry: by UUID first (follows the block when it
     * moved), else at the stored position, exactly as before beta.4 (entries paired
     * before beta.4, Redirector not loaded/registered, or a Redirector broken and placed
     * again at the same spot, which gets a new UUID). The UUID always wins when the
     * linked Redirector is loaded, so a moved Redirector is never confused with another
     * one at its old position.
     */
    public static ColonyLinkRedirectorBlockEntity forEntry(Level level, BuilderEntry entry)
    {
        if (level == null || entry == null || !entry.hasRedirector()) return null;
        ColonyLinkRedirectorBlockEntity byId = resolve(entry.redirectorId(), level);
        if (byId != null) return byId;
        return level.getBlockEntity(entry.redirectorPos()) instanceof ColonyLinkRedirectorBlockEntity r ? r : null;
    }

    /** Empties the index (integrated server: statics outlive the world). */
    public static void clear()
    {
        REGISTRY.clear();
    }
}
