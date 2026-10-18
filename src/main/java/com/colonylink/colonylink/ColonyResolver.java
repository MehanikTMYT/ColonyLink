package com.colonylink.colonylink;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Resolves "the colony" a Redirector action belongs to.
 *
 * The Redirector's own position is NOT used: getClosestColony has no maximum radius,
 * so a Redirector outside the claims (or on a Sable ship, ~20M blocks away) would
 * resolve an arbitrary colony. A Builder hut always sits inside its colony's claim,
 * so its position resolves the owning colony reliably.
 *
 * Server-side only (IColonyManager server lookups).
 */
public final class ColonyResolver
{
    private ColonyResolver() {}

    /**
     * Colony of the Builder hut at {@code builderPos}, or null if the position is
     * unknown or no colony is found.
     */
    public static IColony forBuilder(Level level, BlockPos builderPos)
    {
        if (level == null || builderPos == null) return null;
        return IColonyManager.getInstance().getClosestColony(level, builderPos);
    }

    /**
     * Colony of the Builder linked to this Redirector (linkedBuilderPos, else
     * targetInventoryPos — both are written together by the Clipboard), or null
     * if the Redirector is not linked or no colony is found.
     */
    public static IColony forRedirector(Level level, ColonyLinkRedirectorBlockEntity redirector)
    {
        if (redirector == null) return null;
        BlockPos builderPos = redirector.getLinkedBuilderPos() != null
                ? redirector.getLinkedBuilderPos()
                : redirector.getTargetInventoryPos();
        return forBuilder(level, builderPos);
    }
}
