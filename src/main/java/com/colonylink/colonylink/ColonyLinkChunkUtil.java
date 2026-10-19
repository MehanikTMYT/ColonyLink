package com.colonylink.colonylink;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * v1.4.11 — Chunk-loading checks (MineColonies side).
 *
 * All-or-nothing policy: no ColonyLink operation on the colony, warehouse or builder is
 * attempted if any of their chunks is not TRULY alive. The player is warned and the
 * action is cancelled rather than operating on un-simulated inventories.
 *
 * IMPORTANT — "present" chunk ≠ "alive" chunk:
 *   MineColonies keeps a colony "loaded" via a NON-TICKING ticket when no player is
 *   nearby. The chunks and their data stay in memory:
 *     - {@link Level#hasChunkAt(BlockPos)} returns {@code true} (READS work: the
 *       Clipboard sees items, the colony is found);
 *     - BUT the world does not SIMULATE those chunks. A WRITE (inserting into a rack)
 *       hits a frozen BlockEntity and does not propagate/persist → we show "sent to
 *       builder" while nothing actually arrives (even voiding, depending on timing).
 *   So we require {@link ServerLevel#isPositionEntityTicking(BlockPos)} (alive chunk:
 *   player within simulation range OR a ticking ticket), not mere presence.
 *
 * Applied Energistics side: NO manual check here, deliberately.
 *   - AE2 itself removes unloaded nodes from the grid (network split): we CANNOT
 *     enumerate "the unloaded part" of the network, it is no longer in the grid.
 *   - If unloading cuts the Redirector from the controller/power, node.isActive()
 *     goes false → our existing guards already cancel.
 *   - If only storage unloads while the Redirector stays powered, extract/insert only
 *     operate on loaded storage, never voiding (remainder handled).
 *   So node.isActive() + remainder handling suffice: no manual AE2 scan.
 */
public final class ColonyLinkChunkUtil
{
    private ColonyLinkChunkUtil() {}

    // Presence (read-only)

    /** True if the chunk containing {@code pos} is present in memory. Does not force loading. */
    public static boolean isLoaded(Level level, BlockPos pos)
    {
        return level != null && pos != null && level.hasChunkAt(pos);
    }

    /** True if ALL positions are in present chunks (empty/null → true). */
    public static boolean allLoaded(Level level, Iterable<BlockPos> positions)
    {
        if (level == null) return false;
        if (positions == null) return true;
        for (BlockPos p : positions)
            if (p != null && !level.hasChunkAt(p)) return false;
        return true;
    }

    // Alive (simulated/ticking) — required for any write

    /**
     * True if {@code pos}'s chunk is TRULY alive (entity-ticking), not just present.
     * This guarantees a write (rack insertion) will be simulated and persisted. A
     * "frozen" (non-ticking) MineColonies load returns false here. On the client
     * (undeterminable) we fall back to presence.
     */
    public static boolean isLive(Level level, BlockPos pos)
    {
        if (level == null || pos == null) return false;
        if (!level.hasChunkAt(pos)) return false;
        if (level instanceof ServerLevel sl) return sl.isPositionEntityTicking(pos);
        return true;
    }

    /** True if ALL positions are in alive chunks (empty/null → true). */
    public static boolean allLive(Level level, Iterable<BlockPos> positions)
    {
        if (level == null) return false;
        if (positions == null) return true;
        for (BlockPos p : positions)
            if (p != null && !isLive(level, p)) return false;
        return true;
    }

    // High-level guards (require an ALIVE chunk)

    /**
     * True if the warehouse (hut block + all its containers/racks) is fully ALIVE.
     * {@code null} → false (fail safe).
     */
    public static boolean warehouseFullyLoaded(Level level, BuildingWareHouse warehouse)
    {
        if (warehouse == null) return false;
        if (!isLive(level, warehouse.getPosition())) return false;
        return allLive(level, warehouse.getContainers());
    }

    /**
     * True if ALL of the colony's warehouses are fully ALIVE.
     * A colony with no warehouse → true (the caller handles absence separately).
     */
    public static boolean colonyWarehousesFullyLoaded(Level level, IColony colony)
    {
        if (colony == null) return false;
        for (IBuilding b : colony.getServerBuildingManager().getBuildings().values())
        {
            if (!(b instanceof BuildingWareHouse warehouse)) continue;
            if (!isLive(level, warehouse.getPosition())) return false;
            if (!allLive(level, warehouse.getContainers())) return false;
        }
        return true;
    }

    /**
     * True if the building at {@code buildingPos} (block + containers) is fully ALIVE.
     * If no MineColonies building exists at that position (direct, non-MineColonies
     * inventory), only the block itself is checked for liveness.
     */
    public static boolean buildingFullyLoaded(Level level, IColony colony, BlockPos buildingPos)
    {
        if (buildingPos == null) return false;
        if (colony == null) return isLive(level, buildingPos);
        for (IBuilding b : colony.getServerBuildingManager().getBuildings().values())
        {
            if (!b.getPosition().equals(buildingPos)) continue;
            if (!isLive(level, b.getPosition())) return false;
            return allLive(level, b.getContainers());
        }
        return isLive(level, buildingPos);
    }
}