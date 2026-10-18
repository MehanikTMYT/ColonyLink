package com.colonylink.colonylink;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 1.7.0-beta.2 — server-side helpers for the Warehouse Link Card's {@link CardLink}:
 * writing a link (auto on insert, manual on a Warehouse hut, migration of old cards).
 *
 * The card never carries a permission. Linking checks ACCESS_HUTS on the player who
 * links; every later action checks it again on the player who acts.
 */
public final class CardLinks
{
    private CardLinks() {}

    public static CardLink get(ItemStack card)
    {
        if (card == null || card.isEmpty() || !(card.getItem() instanceof WarehouseLinkCard)) return null;
        return card.get(ColonyLinkRegistry.CARD_LINK.get());
    }

    /** True for a Warehouse Link Card that carries no link yet (blank or pre-beta.2). */
    public static boolean isUnlinkedCard(ItemStack stack)
    {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof WarehouseLinkCard
                && !stack.has(ColonyLinkRegistry.CARD_LINK.get());
    }

    public static CardLink linkFor(IColony colony, BuildingWareHouse warehouse)
    {
        return new CardLink(colony.getID(), colony.getDimension(), colony.getCenter(),
                colony.getName(), warehouse.getPosition());
    }

    public static boolean hasHutAccess(IColony colony, ServerPlayer player)
    {
        return colony != null && player != null
                && colony.getPermissions().hasPermission(player, Action.ACCESS_HUTS);
    }

    /**
     * Warehouse of {@code colony} closest to {@code near}. Built Warehouses (level > 0)
     * win over unbuilt ones; null if the colony has none.
     */
    public static BuildingWareHouse nearestWarehouse(IColony colony, BlockPos near)
    {
        BuildingWareHouse best = null;
        boolean bestBuilt = false;
        double bestDist = Double.MAX_VALUE;
        for (IBuilding b : colony.getServerBuildingManager().getBuildings().values())
        {
            if (!(b instanceof BuildingWareHouse wh)) continue;
            boolean built = wh.getBuildingLevel() > 0;
            double dist = wh.getPosition().distSqr(near);
            if (best == null || (built && !bestBuilt) || (built == bestBuilt && dist < bestDist))
            {
                best = wh;
                bestBuilt = built;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * Colony of the player's current dimension where the player has ACCESS_HUTS, the
     * one whose centre is closest to the player if there are several; null if none.
     */
    public static IColony colonyForPlayer(ServerPlayer player)
    {
        IColony best = null;
        double bestDist = Double.MAX_VALUE;
        for (IColony colony : IColonyManager.getInstance().getColonies(player.level()))
        {
            if (!hasHutAccess(colony, player)) continue;
            double dist = colony.getCenter().distToCenterSqr(player.position());
            if (dist < bestDist)
            {
                best = colony;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * Auto-link on insert: writes the link onto {@code card} (in place) for the colony
     * the inserting player has ACCESS_HUTS in, and that colony's Warehouse closest to
     * {@code blockPos}. {@code preferred} (the Redirector's Builder colony) is used
     * first when the player has hut access there. Tells the player the outcome.
     */
    public static void autoLink(ItemStack card, ServerPlayer player, BlockPos blockPos, IColony preferred)
    {
        IColony colony = hasHutAccess(preferred, player) ? preferred : colonyForPlayer(player);
        if (colony == null)
        {
            player.sendSystemMessage(Component.translatable("colonylink.card.msg.no_colony_access"));
            return;
        }
        BuildingWareHouse warehouse = nearestWarehouse(colony, blockPos);
        if (warehouse == null)
        {
            player.sendSystemMessage(Component.translatable("colonylink.card.msg.no_warehouse", colony.getName()));
            return;
        }
        writeLink(card, player, colony, warehouse);
    }

    /**
     * Manual link: right-click of the card on a Warehouse hut. Re-links an already
     * linked card too (this is how a card moves to another Warehouse or colony).
     * Requires ACCESS_HUTS in the Warehouse's colony, checked on this player.
     */
    public static void linkToWarehouseHut(ItemStack card, ServerPlayer player, Level level, BlockPos hutPos)
    {
        IBuilding building = IColonyManager.getInstance().getBuilding(level, hutPos);
        if (!(building instanceof BuildingWareHouse warehouse) || warehouse.getColony() == null)
        {
            player.sendSystemMessage(Component.translatable("colonylink.terminal.no_colony"));
            return;
        }
        IColony colony = warehouse.getColony();
        if (!hasHutAccess(colony, player))
        {
            player.sendSystemMessage(Component.translatable("colonylink.wand.msg.no_permission_colony"));
            return;
        }
        writeLink(card, player, colony, warehouse);
    }

    /** Writes the link onto {@code card} and confirms it to the player. */
    public static void writeLink(ItemStack card, ServerPlayer player, IColony colony, BuildingWareHouse warehouse)
    {
        card.set(ColonyLinkRegistry.CARD_LINK.get(), linkFor(colony, warehouse));
        if (player != null)
            player.sendSystemMessage(Component.translatable("colonylink.card.msg.linked",
                    colony.getName(), warehouse.getPosition().toShortString()));
    }

    // ── Resolution: card → colony + Warehouse ─────────────────────────────────

    public enum Status
    {
        OK,
        /** No card in the slot. */
        NO_CARD,
        /** Card without a link (blank, or pre-beta.2 not migrated yet). */
        NOT_LINKED,
        /** Card linked to a colony of another dimension than the block using it. */
        OTHER_DIMENSION,
        /** Colony deleted, or its ID now belongs to another colony (centre differs). */
        COLONY_LOST,
        /** Redirector only: the card's colony is not the colony of the linked Builder. */
        OTHER_COLONY,
        /** Redirector only: the linked Builder's colony cannot be found. */
        NO_BUILDER_COLONY,
        /** Colony found, but no Warehouse at the card's Warehouse position any more. */
        WAREHOUSE_LOST
    }

    /**
     * Outcome of a resolution. {@code colony} is set for OK and WAREHOUSE_LOST,
     * {@code warehouse} only for OK.
     */
    public record Resolved(Status status, CardLink link, IColony colony, BuildingWareHouse warehouse)
    {
        public boolean ok() { return status == Status.OK; }

        /** Player-facing explanation of a failed resolution (null for OK / NO_CARD). */
        public Component message()
        {
            return switch (status)
            {
                case OK, NO_CARD -> null;
                case NOT_LINKED -> Component.translatable("colonylink.card.msg.not_linked");
                case OTHER_DIMENSION -> Component.translatable("colonylink.card.msg.other_dimension",
                        link.dimension().location().toString());
                case COLONY_LOST -> Component.translatable("colonylink.card.msg.colony_lost");
                case OTHER_COLONY -> Component.translatable("colonylink.card.msg.other_colony", link.colonyName());
                case NO_BUILDER_COLONY -> Component.translatable("colonylink.colony.not_found");
                case WAREHOUSE_LOST -> Component.translatable("colonylink.card.msg.warehouse_lost",
                        link.warehousePos().toShortString());
            };
        }

        /** Short translation key for the Terminal's Warehouse panel (no arguments). */
        public String panelKey()
        {
            return switch (status)
            {
                case OK, NO_CARD -> "";
                case NOT_LINKED -> "colonylink.term.err.not_linked";
                case OTHER_DIMENSION -> "colonylink.term.err.other_dimension";
                case COLONY_LOST, NO_BUILDER_COLONY, OTHER_COLONY -> "colonylink.term.err.colony_lost";
                case WAREHOUSE_LOST -> "colonylink.term.err.warehouse_lost";
            };
        }
    }

    /**
     * Colony and Warehouse written on {@code card}, looked up by ID + dimension and
     * checked against the centre fingerprint. Never falls back to a position search.
     * {@code level} is the level of the block using the card (Terminal / Redirector):
     * Warehouse racks are read through that level, so a card of another dimension is
     * refused.
     */
    public static Resolved resolve(ItemStack card, Level level)
    {
        Resolved colonyPart = resolveColony(card, level);
        if (colonyPart.status() != Status.OK) return colonyPart;
        return resolveWarehouse(colonyPart.link(), colonyPart.colony());
    }

    /**
     * Redirector: its colony is decided by its Builder (ColonyResolver.forRedirector);
     * the card must point at that same colony, and its Warehouse is then used.
     */
    public static Resolved resolveForRedirector(Level level, ColonyLinkRedirectorBlockEntity redirector)
    {
        Resolved colonyPart = resolveColony(redirector.warehouseCardSlot.getStackInSlot(0), level);
        if (colonyPart.status() != Status.OK) return colonyPart;
        IColony builderColony = ColonyResolver.forRedirector(level, redirector);
        if (builderColony == null)
            return new Resolved(Status.NO_BUILDER_COLONY, colonyPart.link(), null, null);
        if (builderColony.getID() != colonyPart.colony().getID()
                || !builderColony.getDimension().equals(colonyPart.colony().getDimension()))
            return new Resolved(Status.OTHER_COLONY, colonyPart.link(), null, null);
        return resolveWarehouse(colonyPart.link(), colonyPart.colony());
    }

    /**
     * Same as {@link #resolveForRedirector(Level, ColonyLinkRedirectorBlockEntity)}, after
     * migrating a pre-beta.2 card in the slot for the acting player.
     */
    public static Resolved resolveForRedirector(Level level, ColonyLinkRedirectorBlockEntity redirector,
                                                ServerPlayer player)
    {
        migrateRedirectorCard(redirector, player);
        return resolveForRedirector(level, redirector);
    }

    /**
     * Migration of a pre-beta.2 (blank) card already in a Redirector: linked to the
     * colony of the Redirector's Builder, only when {@code player} (the player acting
     * or opening the menu) has ACCESS_HUTS there; Warehouse closest to the Redirector.
     * Otherwise nothing happens and the card stays unlinked. Returns true if linked.
     */
    public static boolean migrateRedirectorCard(ColonyLinkRedirectorBlockEntity redirector, ServerPlayer player)
    {
        if (redirector == null || player == null) return false;
        ItemStack card = redirector.warehouseCardSlot.getStackInSlot(0);
        if (!isUnlinkedCard(card)) return false;
        IColony colony = ColonyResolver.forRedirector(redirector.getLevel(), redirector);
        if (!hasHutAccess(colony, player)) return false;
        BuildingWareHouse warehouse = nearestWarehouse(colony, redirector.getBlockPos());
        if (warehouse == null) return false;
        ItemStack linked = card.copy();
        writeLink(linked, player, colony, warehouse);
        redirector.warehouseCardSlot.setStackInSlot(0, linked);
        return true;
    }

    private static Resolved resolveColony(ItemStack card, Level level)
    {
        if (card == null || card.isEmpty()) return new Resolved(Status.NO_CARD, null, null, null);
        CardLink link = get(card);
        if (link == null) return new Resolved(Status.NOT_LINKED, null, null, null);
        if (level != null && !link.dimension().equals(level.dimension()))
            return new Resolved(Status.OTHER_DIMENSION, link, null, null);
        IColony colony = IColonyManager.getInstance().getColonyByDimension(link.colonyId(), link.dimension());
        if (colony == null || !link.center().equals(colony.getCenter()))
            return new Resolved(Status.COLONY_LOST, link, null, null);
        return new Resolved(Status.OK, link, colony, null);
    }

    private static Resolved resolveWarehouse(CardLink link, IColony colony)
    {
        IBuilding building = colony.getServerBuildingManager().getBuildings().get(link.warehousePos());
        if (!(building instanceof BuildingWareHouse warehouse))
            return new Resolved(Status.WAREHOUSE_LOST, link, colony, null);
        return new Resolved(Status.OK, link, colony, warehouse);
    }

    /**
     * Migration of a pre-beta.2 card already sitting in a Terminal: links it only when
     * the Terminal stands inside a colony's claim (chunk owner, never "closest colony"),
     * to that colony's Warehouse closest to the Terminal. Returns true if linked.
     */
    public static boolean migrateInTerritory(ItemStack card, Level level, BlockPos terminalPos)
    {
        if (!isUnlinkedCard(card) || level == null || terminalPos == null) return false;
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, terminalPos);
        if (colony == null) return false;
        BuildingWareHouse warehouse = nearestWarehouse(colony, terminalPos);
        if (warehouse == null) return false;
        card.set(ColonyLinkRegistry.CARD_LINK.get(), linkFor(colony, warehouse));
        return true;
    }
}
