package com.colonylink.colonylink;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Représente un couple Builder's Hut + Redirector lié à la wand.
 * Stocké en NBT dans la liste "builder_entries" de la wand.
 *
 * builderPos     : position du Builder's Hut (snapshot à l'appairage)
 * redirectorPos  : position du Redirector lié (BlockPos.ZERO si pas encore lié)
 * builderName    : nom du builder PNJ (snapshot à l'appairage, "N/A" si vide)
 * buildingLabel  : nom du bâtiment MineColonies (snapshot à l'appairage)
 * redirectorId   : 1.7.0-beta.4 — stable UUID of the linked Redirector (null for
 *                  entries paired before beta.4). The Redirector keeps it in its block
 *                  entity NBT, which block movers copy, so it survives a Create:
 *                  Aeronautics ship reassembly that gives the block a new position.
 *                  redirectorPos stays the key the client sends and the fallback.
 */
public record BuilderEntry(
        BlockPos builderPos,
        BlockPos redirectorPos,
        String builderName,
        String buildingLabel,
        ResourceKey<Level> dimension,
        UUID redirectorId
)
{
    public static final BuilderEntry EMPTY = new BuilderEntry(
            BlockPos.ZERO, BlockPos.ZERO, "N/A", "N/A", null, null);

    /** Pre-beta.4 shape (no Redirector UUID). */
    public BuilderEntry(BlockPos builderPos, BlockPos redirectorPos, String builderName,
                        String buildingLabel, ResourceKey<Level> dimension)
    {
        this(builderPos, redirectorPos, builderName, buildingLabel, dimension, null);
    }

    // ── Sérialisation NBT ────────────────────────────────────────────────

    public CompoundTag toNbt()
    {
        CompoundTag tag = new CompoundTag();
        tag.putInt("bx", builderPos.getX());
        tag.putInt("by", builderPos.getY());
        tag.putInt("bz", builderPos.getZ());
        tag.putInt("rx", redirectorPos.getX());
        tag.putInt("ry", redirectorPos.getY());
        tag.putInt("rz", redirectorPos.getZ());
        tag.putString("builder_name", builderName);
        tag.putString("building_label", buildingLabel);
        // v1.4.9 — dimension du builder (figée à l'appairage). Absent = legacy/inconnu.
        if (dimension != null) tag.putString("dim", dimension.location().toString());
        // 1.7.0-beta.4 — absent = entry paired before beta.4 (position fallback).
        if (redirectorId != null) tag.putUUID("redirector_id", redirectorId);
        return tag;
    }

    public static BuilderEntry fromNbt(CompoundTag tag)
    {
        BlockPos bPos = new BlockPos(tag.getInt("bx"), tag.getInt("by"), tag.getInt("bz"));
        BlockPos rPos = new BlockPos(tag.getInt("rx"), tag.getInt("ry"), tag.getInt("rz"));
        String bName = tag.contains("builder_name") ? tag.getString("builder_name") : "N/A";
        String label = tag.contains("building_label") ? tag.getString("building_label") : "N/A";
        ResourceKey<Level> dim = null;
        if (tag.contains("dim"))
        {
            ResourceLocation rl = ResourceLocation.tryParse(tag.getString("dim"));
            if (rl != null) dim = ResourceKey.create(Registries.DIMENSION, rl);
        }
        UUID rId = tag.hasUUID("redirector_id") ? tag.getUUID("redirector_id") : null;
        return new BuilderEntry(bPos, rPos, bName, label, dim, rId);
    }

    /**
     * Retourne une copie avec le redirectorPos mis à jour. The Redirector UUID is
     * dropped (another Redirector, or unknown): use {@link #withRedirector(BlockPos, UUID)}.
     */
    public BuilderEntry withRedirector(BlockPos newRedirectorPos)
    {
        return new BuilderEntry(builderPos, newRedirectorPos, builderName, buildingLabel, dimension, null);
    }

    /** 1.7.0-beta.4 — copy linked to a Redirector, with its stable UUID. */
    public BuilderEntry withRedirector(BlockPos newRedirectorPos, UUID newRedirectorId)
    {
        return new BuilderEntry(builderPos, newRedirectorPos, builderName, buildingLabel, dimension, newRedirectorId);
    }

    /** Retourne une copie avec les labels mis à jour (snapshot à l'appairage). */
    public BuilderEntry withLabels(String newBuilderName, String newBuildingLabel)
    {
        return new BuilderEntry(builderPos, redirectorPos, newBuilderName, newBuildingLabel, dimension, redirectorId);
    }

    /** true si cet entry a un redirector lié (pas ZERO). */
    public boolean hasRedirector()
    {
        return !redirectorPos.equals(BlockPos.ZERO);
    }

    // ── Sérialisation liste en NBT ───────────────────────────────────────

    public static ListTag listToNbt(List<BuilderEntry> entries)
    {
        ListTag list = new ListTag();
        for (BuilderEntry e : entries)
            list.add(e.toNbt());
        return list;
    }

    public static List<BuilderEntry> listFromNbt(ListTag list)
    {
        List<BuilderEntry> entries = new ArrayList<>();
        for (Tag t : list)
        {
            if (t instanceof CompoundTag ct)
                entries.add(fromNbt(ct));
        }
        return entries;
    }
}
