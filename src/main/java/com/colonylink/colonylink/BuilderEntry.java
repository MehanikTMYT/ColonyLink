package com.colonylink.colonylink;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A Builder's Hut + Redirector pair linked to the wand.
 * Stored in NBT in the wand's "builder_entries" list.
 *
 * builderPos     : Builder's Hut position (snapshot at pairing)
 * redirectorPos  : linked Redirector position (BlockPos.ZERO if not yet linked)
 * builderName    : builder NPC name (snapshot at pairing, "N/A" if empty)
 * buildingLabel  : MineColonies building name (snapshot at pairing)
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

    // NBT serialization

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
        // v1.4.9 — builder dimension (frozen at pairing). Absent = legacy/unknown.
        if (dimension != null) tag.putString("dim", dimension.identifier().toString());
        // 1.7.0-beta.4 — absent = entry paired before beta.4 (position fallback).
        if (redirectorId != null) tag.store("redirector_id", net.minecraft.core.UUIDUtil.CODEC, redirectorId);
        return tag;
    }

    public static BuilderEntry fromNbt(CompoundTag tag)
    {
        BlockPos bPos = new BlockPos(tag.getIntOr("bx", 0), tag.getIntOr("by", 0), tag.getIntOr("bz", 0));
        BlockPos rPos = new BlockPos(tag.getIntOr("rx", 0), tag.getIntOr("ry", 0), tag.getIntOr("rz", 0));
        String bName = tag.contains("builder_name") ? tag.getStringOr("builder_name", "") : "N/A";
        String label = tag.contains("building_label") ? tag.getStringOr("building_label", "") : "N/A";
        ResourceKey<Level> dim = null;
        if (tag.contains("dim"))
        {
            Identifier rl = Identifier.tryParse(tag.getStringOr("dim", ""));
            if (rl != null) dim = ResourceKey.create(Registries.DIMENSION, rl);
        }
        UUID rId = tag.read("redirector_id", UUIDUtil.CODEC).isPresent() ? tag.read("redirector_id", UUIDUtil.CODEC).orElseThrow() : null;
        return new BuilderEntry(bPos, rPos, bName, label, dim, rId);
    }

    /**
     * Returns a copy with redirectorPos updated. The Redirector UUID is
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

    /** Returns a copy with updated labels (snapshot at pairing). */
    public BuilderEntry withLabels(String newBuilderName, String newBuildingLabel)
    {
        return new BuilderEntry(builderPos, redirectorPos, newBuilderName, newBuildingLabel, dimension, redirectorId);
    }

    /** True if this entry has a linked redirector (not ZERO). */
    public boolean hasRedirector()
    {
        return !redirectorPos.equals(BlockPos.ZERO);
    }

    // NBT list serialization

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
