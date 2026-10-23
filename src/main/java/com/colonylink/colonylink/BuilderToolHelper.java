package com.colonylink.colonylink;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.core.Holder;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool substitution logic for ColonyLink — AE2/RS2 neutral.
 *
 * v1.1.4: refactored to support AE2 and RS2 without a direct dependency on the
 * AE2 types (KeyCounter, ICraftingService, AEItemKey) in findBestTool()'s public
 * signature.
 *
 * Two neutral interfaces replace the AE2 types:
 *   ToolInventoryView   → abstracts KeyCounter (AE2) and StorageNetworkComponent (RS2)
 *   ToolCraftingView    → abstracts ICraftingService (AE2) and AutocraftingNetworkComponent (RS2)
 *
 * SubstituteResult now returns an ItemStack (displayStack) rather than an
 * AEItemKey — enough for every caller.
 *
 * Static wrappers build these interfaces from AE2 or RS2 so callers do not have to.
 *
 * Table (max allowed individual enchant level):
 * Tool Tier  │ Hut 0 │ Hut 1 │ Hut 2 │ Hut 3 │ Hut 4 │ Hut 5
 * Wood/Gold  │   0   │   1   │   2   │   3   │   4   │  any
 * Stone      │   -   │   0   │   1   │   2   │   3   │  any
 * Iron       │   -   │   -   │   0   │   1   │   2   │  any
 * Diamond    │   -   │   -   │   -   │   0   │   1   │  any
 * Netherite+ │   -   │   -   │   -   │   -   │   0   │  any
 */
public class BuilderToolHelper
{
    // ── PORT(26.1) ────────────────────────────────────────────────────────────
    // Vanilla dropped Tiers/TieredItem and the per-type tool and armour classes: a tool is any item with a
    // Tool component (or a sword), its kind comes from the vanilla item tags, and its tier is only visible
    // through its durability. The old getUses() estimate below therefore becomes the only tier source, and
    // the names keep the 1.21.1 enum so the tables and decision logic stay untouched.

    /** Vanilla tool tiers, as in 1.21.1's {@code Tiers}. */
    enum Tiers { WOOD, STONE, IRON, DIAMOND, GOLD, NETHERITE }

    /** Tool kinds ColonyLink substitutes, keyed by their vanilla item tag. */
    enum ToolKind
    {
        PICKAXE("pickaxe", ItemTags.PICKAXES),
        AXE("axe", ItemTags.AXES),
        SHOVEL("shovel", ItemTags.SHOVELS),
        HOE("hoe", ItemTags.HOES),
        SWORD("sword", ItemTags.SWORDS);

        final String id;
        final TagKey<Item> tag;

        ToolKind(final String id, final TagKey<Item> tag)
        {
            this.id = id;
            this.tag = tag;
        }

        static ToolKind of(final ItemStack stack)
        {
            for (final ToolKind kind : values())
                if (stack.is(kind.tag)) return kind;
            return null;
        }
    }

    /** Armour slots, as in 1.21.1's {@code ArmorItem.Type}. */
    enum ArmorPiece
    {
        HELMET(ItemTags.HEAD_ARMOR), CHESTPLATE(ItemTags.CHEST_ARMOR), LEGGINGS(ItemTags.LEG_ARMOR), BOOTS(ItemTags.FOOT_ARMOR);

        final TagKey<Item> tag;

        ArmorPiece(final TagKey<Item> tag) { this.tag = tag; }

        static ArmorPiece of(final ItemStack stack)
        {
            for (final ArmorPiece piece : values())
                if (stack.is(piece.tag)) return piece;
            return null;
        }
    }

    /** Replaces {@code instanceof TieredItem}: a damageable item that mines (Tool component) or is a sword. */
    static boolean isTieredTool(final ItemStack stack)
    {
        return !stack.isEmpty() && stack.isDamageableItem()
                && (stack.has(DataComponents.TOOL) || stack.is(ItemTags.SWORDS));
    }

    // ── Interfaces neutres ────────────────────────────────────────────────────

    /**
     * Abstract view of a network's item stock (AE2 or RS2).
     * Replaces KeyCounter (AE2) in findBestTool()'s signature.
     */
    public interface ToolInventoryView
    {
        /** Returns how much of this item is available in the network. */
        long get(ItemStack stack);

        /**
         * Iterates the stock and returns every ItemStack whose item is an instance
         * of toolClass, at the given estimated tier. Used to find modded items
         * (Apotheosis, etc.) that carry NBT. Default: empty list (NBT-free fallback).
         */
        default List<ItemStack> findByToolClass(ToolKind toolClass, Tiers estimatedTier)
        {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Abstract view of a network's crafting service (AE2 or RS2).
     * Replaces ICraftingService (AE2) in findBestTool()'s signature.
     */
    public interface ToolCraftingView
    {
        /** Returns true if a pattern exists to craft this item. */
        boolean isCraftable(ItemStack stack);
    }

    // AE2 wrappers

    /**
     * Builds a ToolInventoryView from an AE2 KeyCounter.
     * Called by ColonyLinkServerTicker and SendToBuilderHandler (AE2).
     */
    public static ToolInventoryView fromAE2Inventory(appeng.api.stacks.KeyCounter counter)
    {
        return new ToolInventoryView()
        {
            @Override
            public long get(ItemStack stack)
            {
                appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(stack);
                return key != null ? counter.get(key) : 0L;
            }

            @Override
            public List<ItemStack> findByToolClass(ToolKind toolClass, Tiers estimatedTier)
            {
                List<ItemStack> result = new ArrayList<>();
                for (var entry : counter)
                {
                    if (!(entry.getKey() instanceof appeng.api.stacks.AEItemKey aeKey)) continue;
                    ItemStack found = aeKey.toStack(1);
                    if (toolClass != null && !found.is(toolClass.tag)) continue;
                    if (!isTieredTool(found)) continue;
                    if (estimateVanillaTier(found) != estimatedTier) continue;
                    result.add(found);
                }
                return result;
            }
        };
    }

    /**
     * Builds a ToolCraftingView from an AE2 ICraftingService.
     * Called by ColonyLinkServerTicker and SendToBuilderHandler (AE2).
     */
    public static ToolCraftingView fromAE2CraftingService(
            appeng.api.networking.crafting.ICraftingService cs)
    {
        return stack -> {
            appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(stack);
            return key != null && cs.isCraftable(key);
        };
    }

    // Ordered tiers

    private static final Tiers[] TIER_ORDER = {
            Tiers.NETHERITE, Tiers.DIAMOND, Tiers.IRON, Tiers.STONE, Tiers.WOOD
    };

    // Armor: vanilla materials ordered best to worst
    // Used for armor substitution (guards, knights, etc.)
    // Reference durabilities (base multiplier 13 * duraMult):
    //   Leather=5, Gold=7, Chainmail=15, Iron=15, Diamond=33, Netherite=37
    // Ordered by the chestplate's defense value
    private static final int ARMOR_TIER_NETHERITE = 4;
    private static final int ARMOR_TIER_DIAMOND    = 3;
    private static final int ARMOR_TIER_IRON       = 2;
    private static final int ARMOR_TIER_GOLD       = 1;
    private static final int ARMOR_TIER_LEATHER    = 0;

    /** Max armor tier per building level (0-5), same as tools. */
    private static final int[] MAX_ARMOR_TIER_FOR_LEVEL = {
            ARMOR_TIER_LEATHER,   // level 0 → leather max
            ARMOR_TIER_GOLD,      // level 1 → gold/chain max
            ARMOR_TIER_IRON,      // level 2 → iron max
            ARMOR_TIER_DIAMOND,   // level 3 → diamond max
            ARMOR_TIER_NETHERITE, // level 4 → netherite max
            ARMOR_TIER_NETHERITE  // level 5 → netherite max
    };

    private static final Tiers[] MAX_TIER_FOR_LEVEL = {
            Tiers.WOOD,      // level 0
            Tiers.STONE,     // level 1
            Tiers.IRON,      // level 2
            Tiers.DIAMOND,   // level 3
            Tiers.NETHERITE, // level 4
            Tiers.NETHERITE  // level 5
    };

    private static final int[][] MAX_ENCHANT_LEVEL = {
            // Hut0  Hut1  Hut2  Hut3  Hut4  Hut5
            {  0,    1,    2,    3,    4,    Integer.MAX_VALUE }, // Wood/Gold
            { -1,    0,    1,    2,    3,    Integer.MAX_VALUE }, // Stone
            { -1,   -1,    0,    1,    2,    Integer.MAX_VALUE }, // Iron
            { -1,   -1,   -1,    0,    1,    Integer.MAX_VALUE }, // Diamond
            { -1,   -1,   -1,   -1,    0,    Integer.MAX_VALUE }, // Netherite
    };

    // Result

    public enum SubstituteAction { SEND, CRAFT, NONE }

    /**
     * Substitution result.
     *
     * displayStack: the item to send or craft (count = 1).
     *   AE2 side: the caller extracts via AEItemKey.of(displayStack).
     *   RS2 side: the caller extracts via ItemResource.ofItemStack(displayStack).
     *   Both can rebuild their network key from an ItemStack.
     */
    public record SubstituteResult(
            SubstituteAction action,
            ItemStack displayStack
    )
    {
        public static final SubstituteResult NONE =
                new SubstituteResult(SubstituteAction.NONE, ItemStack.EMPTY);
    }

    // Main API

    /**
     * Tries to find a substitute for the requested tool.
     * AE2/RS2 neutral — uses the ToolInventoryView and ToolCraftingView interfaces.
     *
     * @param requested     item requested by the builder
     * @param buildingLevel Work Hut level (0-5)
     * @param inventory     network stock view (AE2 or RS2)
     * @param crafting      network crafting view (AE2 or RS2)
     */
    public static SubstituteResult findBestTool(
            ItemStack requested,
            int buildingLevel,
            ToolInventoryView inventory,
            ToolCraftingView crafting)
    {
        if (!isTool(requested)) return SubstituteResult.NONE;

        int level = Math.max(0, Math.min(5, buildingLevel));
        Tiers maxTier = MAX_TIER_FOR_LEVEL[level];
        int maxTierIndex = getTierIndex(maxTier);

        // Pass 1: best tier in stock
        ItemStack bestInStock = null;
        int bestInStockTierIndex = -1;

        for (Tiers tier : TIER_ORDER)
        {
            int tierIndex = getTierIndex(tier);
            if (tierIndex < 0 || tierIndex > maxTierIndex) continue;
            int maxEnchant = MAX_ENCHANT_LEVEL[tierIndex][level];
            if (maxEnchant < 0) continue;

            ItemStack found = findInInventory(requested, tier, maxEnchant, inventory);
            if (found != null)
            {
                bestInStock = found;
                bestInStockTierIndex = tierIndex;
                break;
            }
        }

        // Pass 2: best craftable tier
        ItemStack bestCraftable = null;
        int bestCraftableTierIndex = -1;

        for (Tiers tier : TIER_ORDER)
        {
            int tierIndex = getTierIndex(tier);
            if (tierIndex < 0 || tierIndex > maxTierIndex) continue;
            if (MAX_ENCHANT_LEVEL[tierIndex][level] < 0) continue;

            List<Item> candidates = getCandidateItems(requested, tier);
            for (Item candidate : candidates)
            {
                ItemStack candidateStack = new ItemStack(candidate);
                if (crafting.isCraftable(candidateStack))
                {
                    bestCraftable = candidateStack;
                    bestCraftableTierIndex = tierIndex;
                    break;
                }
            }
            if (bestCraftable != null) break;
        }

        // Decision — stock always wins over crafting.
        // Only offer to craft when nothing is available in the network.
        // Why: custom tiers (Apotheosis, etc.) are poorly estimated via getUses(),
        // and offering to craft when an acceptable tool is already in stock is a
        // bad experience.
        if (bestInStock != null)
            return new SubstituteResult(SubstituteAction.SEND, bestInStock);
        else if (bestCraftable != null)
            return new SubstituteResult(SubstituteAction.CRAFT, bestCraftable);

        return SubstituteResult.NONE;
    }

    // AE2 compatibility overloads (non-breaking for existing code)

    /**
     * Legacy AE2 overload — converts KeyCounter + ICraftingService into the neutral
     * interfaces. Lets existing AE2 code (ServerTicker, SendToBuilderHandler) keep
     * calling findBestTool unchanged.
     *
     * @deprecated Prefer the neutral version with ToolInventoryView/ToolCraftingView.
     */
    public static SubstituteResult findBestTool(
            ItemStack requested,
            int buildingLevel,
            appeng.api.stacks.KeyCounter inventory,
            appeng.api.networking.crafting.ICraftingService craftingService)
    {
        return findBestTool(
                requested,
                buildingLevel,
                fromAE2Inventory(inventory),
                fromAE2CraftingService(craftingService));
    }

    // Inventory search

    /**
     * Looks in the network inventory for an item of the requested type, the given
     * tier, with valid enchants. Returns the first ItemStack found (count=1), or null.
     */
    private static ItemStack findInInventory(ItemStack requested, Tiers tier,
                                             int maxEnchantLevel, ToolInventoryView inventory)
    {
        List<Item> candidates = getCandidateItems(requested, tier);
        if (candidates.isEmpty()) return null;

        // Pass A: vanilla / NBT-free items — direct key lookup
        for (Item candidate : candidates)
        {
            ItemStack probe = new ItemStack(candidate);
            long qty = inventory.get(probe);
            if (qty <= 0) continue;
            if (!isEnchantValid(probe, maxEnchantLevel)) continue;
            return probe;
        }

        // Passe B : items moddés avec NBT (Apotheosis, etc.)
        // On itère sur le stock réel pour trouver des items de la même classe/tier
        // qui ne matchent pas en lookup direct (NBT différent de l'item vide).
        // null kind = any tiered tool (the old TieredItem.class fallback).
        final ToolKind toolClass = ToolKind.of(requested);

        List<ItemStack> modded = inventory.findByToolClass(toolClass, tier);
        for (ItemStack found : modded)
        {
            if (!isEnchantValid(found, maxEnchantLevel)) continue;
            return found;
        }

        return null;
    }

    // Enchant validation

    public static boolean isEnchantValid(ItemStack stack, int maxLevel)
    {
        if (!ColonyLinkConfig.RESPECT_ENCHANT_LEVEL_CAP.get()) return true;
        var enchants = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        if (enchants.isEmpty()) return true;
        if (maxLevel <= 0) return false;
        for (var entry : enchants.entrySet())
            if (entry.getValue() > maxLevel) return false;
        return true;
    }

    public static int getMaxEnchantLevel(Tiers tier, int buildingLevel)
    {
        int level = Math.max(0, Math.min(5, buildingLevel));
        int tierIndex = getTierIndex(tier);
        if (tierIndex < 0) return -1;
        return MAX_ENCHANT_LEVEL[tierIndex][level];
    }

    // Tool detection

    // Tool type tags (c:tools/*) — NeoForge convention
    // Used to detect modded tools for GUI display (not substitution).
    // MineColonies only accepts TieredItem subclasses for actual tool requests.
    private static final net.minecraft.tags.TagKey<Item> TAG_PICKAXES =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                    Identifier.fromNamespaceAndPath("c", "tools/pickaxe"));
    private static final net.minecraft.tags.TagKey<Item> TAG_AXES =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                    Identifier.fromNamespaceAndPath("c", "tools/axe"));
    private static final net.minecraft.tags.TagKey<Item> TAG_SHOVELS =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                    Identifier.fromNamespaceAndPath("c", "tools/shovel"));
    private static final net.minecraft.tags.TagKey<Item> TAG_HOES =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                    Identifier.fromNamespaceAndPath("c", "tools/hoe"));
    private static final net.minecraft.tags.TagKey<Item> TAG_SWORDS =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                    Identifier.fromNamespaceAndPath("c", "tools/sword"));

    public static boolean isTool(ItemStack stack)
    {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        // Vanilla tool tags / tiered tools (fast path)
        if (ToolKind.of(stack) != null || isTieredTool(stack))
            return true;
        // Tag-based check for modded tools — GUI display only.
        // Substitution stays restricted to TieredItem (what MineColonies accepts).
        return stack.is(TAG_PICKAXES) || stack.is(TAG_AXES)
                || stack.is(TAG_SHOVELS) || stack.is(TAG_HOES)
                || stack.is(TAG_SWORDS);
    }

    // Armor detection and substitution

    public static boolean isArmor(ItemStack stack)
    {
        return !stack.isEmpty() && ArmorPiece.of(stack) != null;
    }

    /**
     * Finds the best vanilla substitute for a requested armor piece.
     * Respects the building level (same table as tools) and enchants.
     * Priority: stock > craftable, vanilla over modded.
     *
     * @param requested    requested armor piece (may be Mekanism, Apotheosis, etc.)
     * @param buildingLevel building level (0-5)
     * @param inventory    network stock view
     * @param crafting     network crafting view
     */
    public static SubstituteResult findBestArmor(
            ItemStack requested,
            int buildingLevel,
            ToolInventoryView inventory,
            ToolCraftingView crafting)
    {
        if (!isArmor(requested)) return SubstituteResult.NONE;
        ArmorPiece armorType = ArmorPiece.of(requested);
        if (armorType == null) return SubstituteResult.NONE;
        int level     = Math.max(0, Math.min(5, buildingLevel));
        int maxTier   = MAX_ARMOR_TIER_FOR_LEVEL[level];
        int maxEnchant = MAX_ENCHANT_LEVEL[Math.min(maxTier, 4)][level];

        // Order: Netherite → Diamond → Iron → Gold → Leather (best first)
        int[] tierOrder = { ARMOR_TIER_NETHERITE, ARMOR_TIER_DIAMOND, ARMOR_TIER_IRON,
                ARMOR_TIER_GOLD, ARMOR_TIER_LEATHER };

        // Pass 1: best in stock
        for (int tier : tierOrder)
        {
            if (tier > maxTier) continue;
            Item vanilla = getVanillaArmorPiece(armorType, tier);
            if (vanilla == null) continue;
            ItemStack probe = new ItemStack(vanilla);
            long qty = inventory.get(probe);
            if (qty > 0 && isEnchantValid(probe, maxEnchant))
                return new SubstituteResult(SubstituteAction.SEND, probe);
        }

        // Pass 2: best craftable
        for (int tier : tierOrder)
        {
            if (tier > maxTier) continue;
            Item vanilla = getVanillaArmorPiece(armorType, tier);
            if (vanilla == null) continue;
            ItemStack probe = new ItemStack(vanilla);
            if (crafting.isCraftable(probe))
                return new SubstituteResult(SubstituteAction.CRAFT, probe);
        }

        return SubstituteResult.NONE;
    }

    /** Retourne la pièce d'armure vanilla correspondant au type et au tier donné. */
    private static Item getVanillaArmorPiece(ArmorPiece type, int tier)
    {
        String mat = switch (tier) {
            case ARMOR_TIER_NETHERITE -> "netherite";
            case ARMOR_TIER_DIAMOND   -> "diamond";
            case ARMOR_TIER_IRON      -> "iron";
            case ARMOR_TIER_GOLD      -> "golden";
            default                   -> "leather";
        };
        String piece = switch (type) {
            case HELMET     -> "helmet";
            case CHESTPLATE -> "chestplate";
            case LEGGINGS   -> "leggings";
            case BOOTS      -> "boots";
        };
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", mat + "_" + piece);
        if (!BuiltInRegistries.ITEM.containsKey(id)) return null;
        Item result = BuiltInRegistries.ITEM.getValue(id);
        return result == net.minecraft.world.item.Items.AIR ? null : result;
    }

    // Tier mapping

    private static int getTierIndex(Tiers tier)
    {
        return switch (tier) {
            case WOOD      -> 0;
            case GOLD      -> 0;
            case STONE     -> 1;
            case IRON      -> 2;
            case DIAMOND   -> 3;
            case NETHERITE -> 4;
        };
    }

    private static Item getEquivalentItem(ItemStack requested, Tiers tier)
    {
        ToolKind kind = ToolKind.of(requested);
        if (kind == null) return null;
        String typeId = kind.id;

        String materialName = switch (tier) {
            case WOOD      -> "wooden";
            case GOLD      -> "golden";
            case STONE     -> "stone";
            case IRON      -> "iron";
            case DIAMOND   -> "diamond";
            case NETHERITE -> "netherite";
        };

        Identifier id = Identifier.fromNamespaceAndPath(
                "minecraft", materialName + "_" + typeId);
        if (!BuiltInRegistries.ITEM.containsKey(id)) return null;
        Item result = BuiltInRegistries.ITEM.getValue(id);
        if (result == net.minecraft.world.item.Items.AIR) return null;
        return result;
    }

    // Candidate cache

    private static final java.util.concurrent.ConcurrentHashMap<String, List<Item>> CANDIDATE_CACHE
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static String cacheKey(Object toolClass, Tiers tier)
    { return toolClass + "_" + tier.name(); }

    private static List<Item> getCandidateItems(ItemStack requested, Tiers tier)
    {
        Item requestedItem = requested.getItem();
        final ToolKind toolClass = ToolKind.of(requested);
        // Unknown kinds only match their own item (the old getClass() fallback).
        final Object cacheOwner = toolClass != null ? toolClass : BuiltInRegistries.ITEM.getKey(requestedItem);

        final Item vanillaItem = getEquivalentItem(requested, tier);
        String key = cacheKey(cacheOwner, tier);

        return CANDIDATE_CACHE.computeIfAbsent(key, k -> {
            List<Item> candidates = new ArrayList<>();
            if (vanillaItem != null) candidates.add(vanillaItem);
            for (Item item : BuiltInRegistries.ITEM)
            {
                if (candidates.contains(item)) continue;
                ItemStack probe = new ItemStack(item);
                if (toolClass != null ? !probe.is(toolClass.tag) : item != requestedItem) continue;
                if (!isTieredTool(probe)) continue;

                // #11 : supporte les tiers vanilla ET les tiers custom (Apotheosis, mods...)
                // On mappe le Tier custom sur un Tiers vanilla équivalent via la durabilité
                Tiers equivalent = estimateVanillaTier(probe);
                if (equivalent != tier) continue;

                candidates.add(item);
            }
            return java.util.Collections.unmodifiableList(candidates);
        });
    }

    /**
     * #11 — Maps any Tier (vanilla or custom) onto the closest vanilla Tiers,
     * based on getUses() (durability).
     *
     * Reference vanilla levels:
     *   WOOD/GOLD = 0, STONE = 1, IRON = 2, DIAMOND = 3, NETHERITE = 4
     *
     * Custom mod tiers (Apotheosis, etc.) are estimated via getUses() and mapped
     * onto the closest step.
     */
    static Tiers estimateVanillaTier(ItemStack stack)
    {
        // PORT(26.1): vanilla tools carry no tier object any more, so the tier is read from durability.
        // Valeurs vanilla : Wood=59, Gold=32, Stone=131, Copper=190, Iron=250, Diamond=1561, Netherite=2031.
        int uses = stack.getMaxDamage();
        // Vanilla materials (any tool kind, swords included) share their material's durability exactly,
        // which is what the 1.21.1 `tier instanceof Tiers` shortcut gave us.
        if (uses == net.minecraft.world.item.ToolMaterial.WOOD.durability())      return Tiers.WOOD;
        if (uses == net.minecraft.world.item.ToolMaterial.GOLD.durability())      return Tiers.GOLD;
        if (uses == net.minecraft.world.item.ToolMaterial.STONE.durability())     return Tiers.STONE;
        if (uses == net.minecraft.world.item.ToolMaterial.COPPER.durability())    return Tiers.STONE; // new in 26.1, below iron
        if (uses == net.minecraft.world.item.ToolMaterial.IRON.durability())      return Tiers.IRON;
        if (uses == net.minecraft.world.item.ToolMaterial.DIAMOND.durability())   return Tiers.DIAMOND;
        if (uses == net.minecraft.world.item.ToolMaterial.NETHERITE.durability()) return Tiers.NETHERITE;
        // Tier custom (Apotheosis, etc.) — estimation par paliers comme en 1.21.1
        if (uses <= 100)  return Tiers.WOOD;
        if (uses <= 300)  return Tiers.STONE;
        if (uses <= 800)  return Tiers.IRON;
        if (uses <= 1800) return Tiers.DIAMOND;
        return Tiers.NETHERITE;
    }
}