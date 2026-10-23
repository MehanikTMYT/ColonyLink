package com.colonylink.colonylink;

import appeng.api.features.IGridLinkableHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.core.component.DataComponents;

import java.util.ArrayList;
import java.util.List;

/**
 * AE2 GridLinkable handler for the wand. Manages the WAP (AE2) link and adds the
 * NBT helpers for the BuilderEntry list (multi-builder, v1.1.2).
 *
 * NBT keys:
 *   linked_x/y/z/dim  → AE2 WAP link
 *   builder_entries   → ListTag of BuilderEntry
 *   active_tab        → int, index of the active tab
 *
 * v1.1.1 compat: a legacy "last_builder_x" is read and converted into a single
 * builder_entries entry on first access.
 */
public class ColonyLinkWandLinkableHandler implements IGridLinkableHandler
{
    // WAP NBT keys
    private static final String NBT_LINKED_X   = "linked_x";
    private static final String NBT_LINKED_Y   = "linked_y";
    private static final String NBT_LINKED_Z   = "linked_z";
    private static final String NBT_LINKED_DIM = "linked_dim";

    // Multi-builder NBT keys
    public static final String NBT_BUILDER_ENTRIES = "builder_entries";
    public static final String NBT_ACTIVE_TAB      = "active_tab";

    // Legacy v1.1.1 keys (migration)
    private static final String LEGACY_BUILDER_X   = "last_builder_x";
    private static final String LEGACY_BUILDER_Y   = "last_builder_y";
    private static final String LEGACY_BUILDER_Z   = "last_builder_z";
    private static final String LEGACY_REDIRECTOR_X = "redirector_x";
    private static final String LEGACY_REDIRECTOR_Y = "redirector_y";
    private static final String LEGACY_REDIRECTOR_Z = "redirector_z";

    /** Current limit from config (hot-reloadable). */
    public static int getMaxBuilders()
    {
        // v1.6.0 — SERVER config: guarded read, this is called from client
        // rendering paths (ColonyLinkScreen) as well as server code.
        return ColonyLinkConfig.safeGet(ColonyLinkConfig.MAX_BUILDERS_PER_WAND, 5);
    }

    // IGridLinkableHandler (WAP AE2)

    @Override
    public boolean canLink(ItemStack stack)
    {
        return stack.getItem() instanceof ColonyLinkWand;
    }

    @Override
    public void link(ItemStack stack, GlobalPos pos)
    {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data -> {
            var tag = data.copyTag();
            tag.putInt(NBT_LINKED_X, pos.pos().getX());
            tag.putInt(NBT_LINKED_Y, pos.pos().getY());
            tag.putInt(NBT_LINKED_Z, pos.pos().getZ());
            tag.putString(NBT_LINKED_DIM, pos.dimension().identifier().toString());
            return CustomData.of(tag);
        });
    }

    @Override
    public void unlink(ItemStack stack)
    {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data -> {
            var tag = data.copyTag();
            tag.remove(NBT_LINKED_X);
            tag.remove(NBT_LINKED_Y);
            tag.remove(NBT_LINKED_Z);
            tag.remove(NBT_LINKED_DIM);
            return CustomData.of(tag);
        });
    }

    // WAP helpers

    public static boolean isLinked(ItemStack stack)
    {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyTag().contains(NBT_LINKED_DIM);
    }

    public static GlobalPos getLinkedPos(ItemStack stack)
    {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        var tag = data.copyTag();
        if (!tag.contains(NBT_LINKED_DIM)) return null;

        int x = tag.getIntOr(NBT_LINKED_X, 0);
        int y = tag.getIntOr(NBT_LINKED_Y, 0);
        int z = tag.getIntOr(NBT_LINKED_Z, 0);
        String dimStr = tag.getStringOr(NBT_LINKED_DIM, "");

        ResourceKey<Level> dimKey = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.parse(dimStr)
        );
        return GlobalPos.of(dimKey, new BlockPos(x, y, z));
    }

    // Multi-builder helpers

    /** All BuilderEntry stored in the wand, migrating from the v1.1.1 format if needed. */
    public static List<BuilderEntry> getBuilderEntries(ItemStack stack)
    {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return new ArrayList<>();
        var tag = data.copyTag();

        // Legacy → new format migration
        if (!tag.contains(NBT_BUILDER_ENTRIES) && tag.contains(LEGACY_BUILDER_X))
        {
            List<BuilderEntry> migrated = new ArrayList<>();
            BlockPos bPos = new BlockPos(
                    tag.getIntOr(LEGACY_BUILDER_X, 0),
                    tag.getIntOr(LEGACY_BUILDER_Y, 0),
                    tag.getIntOr(LEGACY_BUILDER_Z, 0));
            BlockPos rPos = tag.contains(LEGACY_REDIRECTOR_X)
                    ? new BlockPos(
                    tag.getIntOr(LEGACY_REDIRECTOR_X, 0),
                    tag.getIntOr(LEGACY_REDIRECTOR_Y, 0),
                    tag.getIntOr(LEGACY_REDIRECTOR_Z, 0))
                    : BlockPos.ZERO;
            migrated.add(new BuilderEntry(bPos, rPos, "Builder", "N/A", null));
            return migrated;
        }

        if (!tag.contains(NBT_BUILDER_ENTRIES)) return new ArrayList<>();
        return BuilderEntry.listFromNbt(tag.getListOrEmpty(NBT_BUILDER_ENTRIES));
    }

    /** Writes the BuilderEntry list to the wand NBT. */
    public static void setBuilderEntries(ItemStack stack, List<BuilderEntry> entries)
    {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data -> {
            var tag = data.copyTag();
            tag.put(NBT_BUILDER_ENTRIES, BuilderEntry.listToNbt(entries));
            // Clean up legacy keys if present
            tag.remove(LEGACY_BUILDER_X);
            tag.remove(LEGACY_BUILDER_Y);
            tag.remove(LEGACY_BUILDER_Z);
            tag.remove(LEGACY_REDIRECTOR_X);
            tag.remove(LEGACY_REDIRECTOR_Y);
            tag.remove(LEGACY_REDIRECTOR_Z);
            return CustomData.of(tag);
        });
    }

    /**
     * Adds a BuilderEntry, overwriting any existing entry with the same builderPos.
     * Limited to getMaxBuilders() entries; returns false if the limit is reached and
     * the entry is not already present.
     */
    public static boolean addOrUpdateEntry(ItemStack stack, BuilderEntry entry)
    {
        List<BuilderEntry> entries = getBuilderEntries(stack);

        for (int i = 0; i < entries.size(); i++)
        {
            if (entries.get(i).builderPos().equals(entry.builderPos()))
            {
                entries.set(i, entry);
                setBuilderEntries(stack, entries);
                return true;
            }
        }

        if (entries.size() >= getMaxBuilders()) return false;

        entries.add(entry);
        setBuilderEntries(stack, entries);
        return true;
    }

    /** Updates the redirectorPos of the entry identified by builderPos; false if none. */
    public static boolean updateRedirectorForBuilder(ItemStack stack, BlockPos builderPos, BlockPos redirectorPos)
    {
        List<BuilderEntry> entries = getBuilderEntries(stack);
        for (int i = 0; i < entries.size(); i++)
        {
            if (entries.get(i).builderPos().equals(builderPos))
            {
                entries.set(i, entries.get(i).withRedirector(redirectorPos));
                setBuilderEntries(stack, entries);
                return true;
            }
        }
        return false;
    }

    /** Removes the entry at the given index, adjusting activeTab if needed. */
    public static void removeEntryAt(ItemStack stack, int index)
    {
        List<BuilderEntry> entries = getBuilderEntries(stack);
        if (index < 0 || index >= entries.size()) return;
        entries.remove(index);
        setBuilderEntries(stack, entries);

        int activeTab = getActiveTab(stack);
        if (entries.isEmpty())
            setActiveTab(stack, 0);
        else if (activeTab >= entries.size())
            setActiveTab(stack, entries.size() - 1);
    }

    // Active tab

    public static int getActiveTab(ItemStack stack)
    {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        var tag = data.copyTag();
        if (!tag.contains(NBT_ACTIVE_TAB)) return 0;
        int tab = tag.getIntOr(NBT_ACTIVE_TAB, 0);
        List<BuilderEntry> entries = getBuilderEntries(stack);
        // v1.6.2 — clamp low bound too: a wand whose NBT was poisoned with a
        // negative active_tab (modified client) self-heals instead of crashing on
        // every read. Integer.MAX_VALUE (Citizens sentinel) still collapses to size-1.
        return entries.isEmpty() ? 0 : Math.max(0, Math.min(tab, entries.size() - 1));
    }

    public static void setActiveTab(ItemStack stack, int tabIndex)
    {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data -> {
            var tag = data.copyTag();
            tag.putInt(NBT_ACTIVE_TAB, tabIndex);
            return CustomData.of(tag);
        });
    }

    /** The active tab's entry, or null if the list is empty. */
    public static BuilderEntry getActiveEntry(ItemStack stack)
    {
        List<BuilderEntry> entries = getBuilderEntries(stack);
        if (entries.isEmpty()) return null;
        int tab = getActiveTab(stack);
        return entries.get(Math.min(tab, entries.size() - 1));
    }

    /**
     * v1.4.9 — Dimension frozen when the builder at {@code builderPos} was paired,
     * or null if unknown (legacy entry from before dimension tracking).
     */
    public static ResourceKey<Level> getBuilderDimension(ItemStack stack, BlockPos builderPos)
    {
        for (BuilderEntry e : getBuilderEntries(stack))
            if (e.builderPos().equals(builderPos))
                return e.dimension();
        return null;
    }

    /**
     * 1.7.0-beta.4 — fills the Redirector UUID of an entry from the Redirector at its
     * stored position: entries paired before beta.4 (no UUID), and entries whose linked
     * Redirector is gone (UUID not loaded anywhere) while a Redirector stands at the same
     * spot (broken and placed again: new UUID). Only loaded positions are read (never
     * loads a chunk); the Clipboard is rewritten only if something changed. SERVER-side.
     */
    public static void upgradeRedirectorIds(ItemStack stack, net.minecraft.world.level.Level level)
    {
        List<BuilderEntry> entries = getBuilderEntries(stack);
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++)
        {
            BuilderEntry e = entries.get(i);
            if (!e.hasRedirector() || !level.isLoaded(e.redirectorPos())) continue;
            if (e.redirectorId() != null && RedirectorRegistry.resolve(e.redirectorId(), level) != null) continue;
            if (level.getBlockEntity(e.redirectorPos()) instanceof ColonyLinkRedirectorBlockEntity r
                    && !r.getRedirectorId().equals(e.redirectorId()))
            {
                entries.set(i, e.withRedirector(e.redirectorPos(), r.getRedirectorId()));
                changed = true;
            }
        }
        if (changed) setBuilderEntries(stack, entries);
    }

    /**
     * 1.7.0-beta.4 — the entry whose Redirector the client designates by
     * {@code redirectorPos} (the key it got from the Clipboard), or null. Resolve the
     * block entity with {@link RedirectorRegistry#forEntry}: by UUID it follows a moved
     * Redirector, while the client keeps sending the original position.
     */
    public static BuilderEntry findEntryByRedirectorPos(ItemStack stack, BlockPos redirectorPos)
    {
        if (redirectorPos == null) return null;
        for (BuilderEntry e : getBuilderEntries(stack))
            if (e.hasRedirector() && e.redirectorPos().equals(redirectorPos)) return e;
        return null;
    }

    public static BlockPos getActiveRedirectorPos(ItemStack stack)
    {
        BuilderEntry entry = getActiveEntry(stack);
        if (entry == null) return null;
        return entry.hasRedirector() ? entry.redirectorPos() : null;
    }

    /** The active entry's builderPos, or null. Replaces getLastBuilderPos() in legacy calls. */
    public static BlockPos getActiveBuilderPos(ItemStack stack)
    {
        BuilderEntry entry = getActiveEntry(stack);
        return entry != null ? entry.builderPos() : null;
    }

    // ColonyLink Packages (citizen token)

    private static final String NBT_CITIZEN_PACKAGES = "citizen_packages";
    public static final int MAX_PACKAGES = 64;

    /** Number of packages stored in the wand (0 if absent). */
    public static int getCitizenPackages(ItemStack stack)
    {
        net.minecraft.world.item.component.CustomData data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        var tag = data.copyTag();
        return tag.contains(NBT_CITIZEN_PACKAGES) ? tag.getIntOr(NBT_CITIZEN_PACKAGES, 0) : 0;
    }

    /** Sets the package count (clamped 0..MAX_PACKAGES). */
    public static void setCitizenPackages(ItemStack stack, int count)
    {
        int clamped = Math.max(0, Math.min(count, MAX_PACKAGES));
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY, data -> {
                    var tag = data.copyTag();
                    tag.putInt(NBT_CITIZEN_PACKAGES, clamped);
                    return net.minecraft.world.item.component.CustomData.of(tag);
                });
    }

    /** Adds packages (clamped). */
    public static void addCitizenPackages(ItemStack stack, int amount)
    {
        setCitizenPackages(stack, getCitizenPackages(stack) + amount);
    }

    /**
     * Consumes 1 package.
     * @return true if a package was consumed (stock > 0)
     */
    public static boolean consumeCitizenPackage(ItemStack stack)
    {
        int current = getCitizenPackages(stack);
        if (current <= 0) return false;
        setCitizenPackages(stack, current - 1);
        return true;
    }

    // ── Sent request keys (server-written reconciliation memory) ──────────────
    //
    // v1.6.0 — this NBT list is now written EXCLUSIVELY on the server (packet
    // handlers add keys, the server ticker / citizens scan prune them); the
    // client only reads it to paint button states. The stack's CUSTOM_DATA is
    // synced to the client automatically, and survives relogs — the old
    // client-side writes did not.
    //
    // Two key families share the list, distinguished by prefix:
    //   Builder  : "b|<x>,<y>,<z>|<itemId>|<availableAtSend>"
    //              The trailing baseline is the builder's 'available' count at
    //              send time; pruning drops the key when the item leaves
    //              getNeededResources() OR when 'available' changes (a courier
    //              delivered), so partially-stocked sends can be topped up.
    //   Citizen  : "c|<citizenName>|<itemId>" (presence-only, the Package
    //              token is the real consumable guard on that path)
    // Legacy unprefixed keys ("<citizenName>|<itemId>", pre-v1.6.0) are
    // dropped by any prune call.

    private static final String NBT_SENT_REQUESTS = "citizen_sent_keys";

    public static final String SENT_PREFIX_BUILDER = "b|";
    public static final String SENT_PREFIX_CITIZEN = "c|";

    /** Citizen key: {@code c|<citizenName>|<itemId>}. */
    public static String citizenSentKey(String citizenName, net.minecraft.world.item.Item item)
    {
        return SENT_PREFIX_CITIZEN + citizenName + "|"
                + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
    }

    /**
     * Identity prefix of a builder key: {@code b|<x>,<y>,<z>|<itemId>|}.
     * Two keys with the same identity prefix refer to the same (builder, item)
     * pair regardless of their baseline.
     */
    public static String builderSentKeyPrefix(BlockPos builderPos, net.minecraft.world.item.Item item)
    {
        return SENT_PREFIX_BUILDER
                + builderPos.getX() + "," + builderPos.getY() + "," + builderPos.getZ()
                + "|" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)
                + "|";
    }

    /** Full builder key: identity prefix + the builder's 'available' count at send time. */
    public static String builderSentKey(BlockPos builderPos, net.minecraft.world.item.Item item, int availableAtSend)
    {
        return builderSentKeyPrefix(builderPos, item) + availableAtSend;
    }

    /** True if a builder key for this (builder, item) pair is stored, whatever its baseline. */
    public static boolean hasBuilderSentKey(java.util.Set<String> keys, BlockPos builderPos, net.minecraft.world.item.Item item)
    {
        String prefix = builderSentKeyPrefix(builderPos, item);
        for (String k : keys)
            if (k.startsWith(prefix)) return true;
        return false;
    }

    /** The builder position encoded in a builder key, or null if malformed. */
    public static BlockPos parseBuilderKeyPos(String key)
    {
        if (!key.startsWith(SENT_PREFIX_BUILDER)) return null;
        int end = key.indexOf('|', SENT_PREFIX_BUILDER.length());
        if (end < 0) return null;
        String[] parts = key.substring(SENT_PREFIX_BUILDER.length(), end).split(",");
        if (parts.length != 3) return null;
        try
        {
            return new BlockPos(Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /** The item registry id encoded in a builder key, or null if malformed. */
    public static String parseBuilderKeyItemId(String key)
    {
        if (!key.startsWith(SENT_PREFIX_BUILDER)) return null;
        int posEnd = key.indexOf('|', SENT_PREFIX_BUILDER.length());
        if (posEnd < 0) return null;
        int itemEnd = key.indexOf('|', posEnd + 1);
        if (itemEnd < 0) return null;
        return key.substring(posEnd + 1, itemEnd);
    }

    /** The stored 'available' baseline of a builder key, or -1 if malformed. */
    public static int parseBuilderKeyBaseline(String key)
    {
        int idx = key.lastIndexOf('|');
        if (idx < 0 || idx == key.length() - 1) return -1;
        try
        {
            return Integer.parseInt(key.substring(idx + 1));
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
    }

    /** Keys of requests already sent (both families, prefixed). */
    public static java.util.Set<String> getSentRequestKeys(ItemStack stack)
    {
        net.minecraft.world.item.component.CustomData data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return new java.util.HashSet<>();
        var tag = data.copyTag();
        if (!tag.contains(NBT_SENT_REQUESTS)) return new java.util.HashSet<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        var list = tag.getListOrEmpty(NBT_SENT_REQUESTS); // 8 = TAG_String
        for (int i = 0; i < list.size(); i++)
            keys.add(list.getStringOr(i, ""));
        return keys;
    }

    /**
     * Adds a sent-request key. SERVER-side only (packet handlers).
     * Exact duplicates are ignored; for builder keys, an existing key with the
     * same identity prefix but a different baseline is replaced.
     */
    public static void addSentRequestKey(ItemStack stack, String key)
    {
        // For builder keys, the identity is everything up to (and including)
        // the last '|'; a re-send must replace the old baseline, not stack up.
        final String identityPrefix = key.startsWith(SENT_PREFIX_BUILDER)
                ? key.substring(0, key.lastIndexOf('|') + 1)
                : null;

        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY, data -> {
                    var tag = data.copyTag();
                    net.minecraft.nbt.ListTag old = tag.contains(NBT_SENT_REQUESTS)
                            ? tag.getListOrEmpty(NBT_SENT_REQUESTS)
                            : new net.minecraft.nbt.ListTag();
                    net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
                    for (int i = 0; i < old.size(); i++)
                    {
                        String k = old.getStringOr(i, "");
                        if (k.equals(key)) return data; // exact duplicate, nothing to do
                        if (identityPrefix != null && k.startsWith(identityPrefix))
                            continue; // same (builder, item) with stale baseline — replace
                        list.add(net.minecraft.nbt.StringTag.valueOf(k));
                    }
                    list.add(net.minecraft.nbt.StringTag.valueOf(key));
                    tag.put(NBT_SENT_REQUESTS, list);
                    return net.minecraft.world.item.component.CustomData.of(tag);
                });
    }

    /**
     * Reconciles ONE key family against the server truth. SERVER-side only.
     *
     * Keys starting with {@code familyPrefix} are kept only if present in
     * {@code keysToKeep}; keys of the other recognized family are left
     * untouched; keys with no recognized prefix (legacy pre-v1.6.0 format)
     * are always dropped.
     *
     * @param familyPrefix SENT_PREFIX_BUILDER or SENT_PREFIX_CITIZEN
     * @param keysToKeep   full keys of this family that must survive
     */
    public static void pruneSentRequestKeys(ItemStack stack, java.util.Set<String> keysToKeep, String familyPrefix)
    {
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY, data -> {
                    var tag = data.copyTag();
                    if (!tag.contains(NBT_SENT_REQUESTS)) return data;
                    net.minecraft.nbt.ListTag old = tag.getListOrEmpty(NBT_SENT_REQUESTS);
                    net.minecraft.nbt.ListTag pruned = new net.minecraft.nbt.ListTag();
                    for (int i = 0; i < old.size(); i++)
                    {
                        String k = old.getStringOr(i, "");
                        boolean recognized = k.startsWith(SENT_PREFIX_BUILDER)
                                || k.startsWith(SENT_PREFIX_CITIZEN);
                        if (!recognized) continue; // legacy unprefixed key — drop
                        if (k.startsWith(familyPrefix) && !keysToKeep.contains(k))
                            continue; // resolved / delivered — drop
                        pruned.add(net.minecraft.nbt.StringTag.valueOf(k));
                    }
                    if (pruned.size() == old.size())
                        return data; // nothing changed — avoid a dirty component
                    tag.put(NBT_SENT_REQUESTS, pruned);
                    return net.minecraft.world.item.component.CustomData.of(tag);
                });
    }
}