package com.colonylink.colonylink;

import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.recipe.ModRecipeTypes;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipe;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DomumRecipeHelper — output count (yield) of Domum Ornamentum's Architect's Cutter.
 *
 * 1.7.0 — the yield comes from the Cutter recipes loaded in the RecipeManager, so
 * Domum updates, new block types and modpack changes (datapack, KubeJS) are honoured.
 * It mirrors ArchitectsCutterRecipe#assemble exactly:
 *     count = max(number of texture components of the block, recipe count)
 * (getResultItem is NOT used: it always reports 1).
 *
 * Fallback: when no Cutter recipe exists for the block, or no Level is available,
 * the pre-1.7.0 hard-coded table below is used unchanged (logged once per block,
 * DEBUG level).
 *
 * Cache: one entry per block, tied to the RecipeManager instance it was built from.
 * A new instance means new recipes, which covers the server (every resource load,
 * /reload included, builds a new RecipeManager), a new world, and joining a server
 * (the client builds a new RecipeManager per connection). The client also refreshes
 * its RecipeManager in place on a recipe sync, so ColonyLinkClient clears the cache
 * on RecipesUpdatedEvent.
 *
 * Common code: no client class. Today the only caller is server-side
 * (DomumEncodePatternPacket), with the player's ServerLevel.
 */
public class DomumRecipeHelper
{
    /** Cached "no Cutter recipe for this block" marker. */
    private static final int NO_RECIPE = -1;

    private static final Map<Block, Integer> CACHE = new ConcurrentHashMap<>();
    private static final Set<Block> FALLBACK_LOGGED = ConcurrentHashMap.newKeySet();
    /** RecipeManager the cache was built from (weak: never keeps old recipes alive). */
    private static WeakReference<RecipeManager> cacheOwner = new WeakReference<>(null);

    public static int getOutputCount(ItemStack domumStack, Level level)
    {
        if (domumStack.isEmpty()) return 1;
        if (!(domumStack.getItem() instanceof BlockItem bi)) return 1;

        Block block = bi.getBlock();

        // PORT(26.1): recipes live on the server only (ServerLevel#recipeAccess); the client uses the yield table.
        int fromRecipe = level instanceof net.minecraft.server.level.ServerLevel sl ? recipeYield(block, sl.recipeAccess()) : NO_RECIPE;
        if (fromRecipe > 0) return fromRecipe;

        if (FALLBACK_LOGGED.add(block))
            ColonyLink.LOGGER.debug("[ColonyLink] No Architect's Cutter recipe found for {} — using the built-in yield table",
                    block);
        return legacyOutputCount(block);
    }

    /** Clears the per-block cache (called on client recipe sync). */
    public static void invalidate()
    {
        synchronized (DomumRecipeHelper.class)
        {
            CACHE.clear();
            FALLBACK_LOGGED.clear();
            cacheOwner = new WeakReference<>(null);
        }
    }

    private static int recipeYield(Block block, RecipeManager recipes)
    {
        if (recipes == null) return NO_RECIPE;
        synchronized (DomumRecipeHelper.class)
        {
            if (cacheOwner.get() != recipes)
            {
                CACHE.clear();
                FALLBACK_LOGGED.clear();
                cacheOwner = new WeakReference<>(recipes);
            }
        }
        return CACHE.computeIfAbsent(block, b -> scanRecipes(b, recipes));
    }

    /**
     * Largest Cutter yield among the recipes producing this block (Domum registers one
     * recipe per block-state variant for some blocks — panels, posts, doors — all with
     * the same count), or NO_RECIPE.
     */
    private static int scanRecipes(Block block, RecipeManager recipes)
    {
        try
        {
            int best = NO_RECIPE;
            for (RecipeHolder<ArchitectsCutterRecipe> holder :
                    recipes.recipeMap().byType(ModRecipeTypes.ARCHITECTS_CUTTER.get()))
            {
                ArchitectsCutterRecipe recipe = holder.value();
                if (recipe.getBlock() != block) continue;
                int components = block instanceof IMateriallyTexturedBlock mtb ? mtb.getComponents().size() : 0;
                best = Math.max(best, Math.max(components, recipe.getCount()));
            }
            return best;
        }
        catch (RuntimeException | LinkageError e)
        {
            // Defensive: a Domum API change must degrade to the table, never crash.
            ColonyLink.LOGGER.debug("[ColonyLink] Architect's Cutter recipe lookup failed for {}: {}", block, e.toString());
            return NO_RECIPE;
        }
    }

    /**
     * Pre-1.7.0 table, kept unchanged as the fallback. Keyed by the block's class name:
     *
     *   shingleblock       → 4   (shingles)
     *   panelblock         → 4   (panels)
     *   timberframeblock   → 4   (timber frames)
     *   framedlightblock   → 2   (lights)
     *   fancydoorblock     → 2   (fancy doors, 2-material)
     *   fancytrapdoorblock → 2   (fancy trapdoors, 2-material)
     *   paperwallblock     → 6   (framed panes / paper walls)
     *   everything else    → 1
     */
    private static int legacyOutputCount(Block block)
    {
        String cls = block.getClass().getSimpleName().toLowerCase();

        return switch (cls)
        {
            case "shingleblock"       -> 4;
            case "panelblock"         -> 4;
            case "timberframeblock"   -> 4;
            case "framedlightblock"   -> 2;
            case "fancydoorblock"     -> 2;
            case "fancytrapdoorblock" -> 2;
            case "paperwallblock"     -> 6;
            default                   -> 1;
        };
    }
}
