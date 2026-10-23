package com.colonylink.colonylink;

import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import appeng.api.config.Actionable;
import appeng.api.networking.IManagedGridNode;
import appeng.menu.ISubMenu;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.parts.IPartItem;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.parts.reporting.AbstractTerminalPart;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Warehouse Link Terminal — Part v1.3.9 (hybrid meta/racks).
 *
 * v1.3.9:
 *  - Double-count fix: warehouse reads go through the meta-handler exposed by
 *    MineColonies (an aggregate of all racks, identified by getSlots() > 54).
 *    MineColonies handles dedup of double/single/mixed racks for us.
 *  - Insert/extract logic unchanged, but now explicitly skip the meta-handler
 *    (getSlots() > 54). The meta-handler is read-only, never written.
 *  - Fallback: with no meta-handler (degenerate case, mod conflict), reads fall
 *    back to the old per-rack scan capped at RACK_HALF_SLOTS (v1.3.0 behavior).
 *
 * Extends AbstractTerminalPart for:
 *  - Native AE2 rendering (cable arm, channel, ModelData SPIN)
 *  - NBT view cells
 *  - AE2 network integration (1 channel, named in Network Status)
 *
 * Features:
 *  - Warehouse Link Card slot (NBT-persisted)
 *  - MineColonies warehouse scan → client snapshot (via meta-handler)
 *  - ME storage scan → client snapshot
 *  - Transfers: WH↔ME, WH↔Player, ME↔Player (via individual racks)
 *  - Pick/deposit: via vanilla carried (setCarried/getCarried)
 *  - Permissions: owner or officer of the colony linked to the card
 *  - Active viewer management (periodic sync)
 */
public class WarehouseLinkTerminalPart extends AbstractTerminalPart
{
    // ── Modèles ───────────────────────────────────────────────────────────────
    // PORT(26.1): AE2 part models are data-driven — see assets/colonylink/ae2/parts/warehouse_link_terminal.json
    // (status_indicator picks the off/on/has-channel layers the old getStaticModels() selected).

    // Constants

    /**
     * Local slot count of one MineColonies rack half.
     * - Single rack: 27 slots
     * - Double rack: 54 slots (27 local + 27 mirror of the other half)
     * When scanning individual racks (insert/extract), stop at 27 to avoid
     * double-counting the mirror slots.
     */
    private static final int RACK_HALF_SLOTS = 27;

    // State

    /** NBT-persisted slot for the Warehouse Link Card. */
    private final ItemStackHandler warehouseCardSlot = new ItemStackHandler(1)
    {
        @Override
        public boolean isItemValid(int slot, ItemStack stack)
        { return stack.getItem() instanceof WarehouseLinkCard; }

        @Override
        protected void onContentsChanged(int slot)
        { getHost().markForSave(); }
    };

    /** NBT-persisted slot for the target Domum item (Cutter tab). Capped at 1 item. */
    final ItemStackHandler domumTargetSlot = new ItemStackHandler(1)
    {
        @Override
        public int getSlotLimit(int slot) { return 1; }

        @Override
        public boolean isItemValid(int slot, ItemStack stack)
        { return stack.isEmpty() || DomumCraftHandler.isDomumItem(stack); }

        @Override
        protected void onContentsChanged(int slot)
        { getHost().markForSave(); }
    };

    /** NBT-persisted slot for the AE2 Blank Pattern (Cutter tab). */
    final ItemStackHandler blankPatternSlot = new ItemStackHandler(1)
    {
        @Override
        public boolean isItemValid(int slot, ItemStack stack)
        {
            if (stack.isEmpty()) return true;
            net.minecraft.resources.Identifier id =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id != null && id.getPath().equals("blank_pattern");
        }

        @Override
        protected void onContentsChanged(int slot)
        { getHost().markForSave(); }
    };

    /** Output preview slot (not persisted, recomputed on open). */
    final ItemStackHandler domumOutputSlot = new ItemStackHandler(1)
    {
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    };

    /** Persisted 3×3 crafting grid (9 slots). */
    final ItemStackHandler craftingGridSlot = new ItemStackHandler(9)
    {
        @Override
        protected void onContentsChanged(int slot)
        { getHost().markForSave(); }
    };

    /** Players with the GUI open — receive periodic syncs. */
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    /**
     * Domum items awaiting encoding — shared per colony.
     * NBT-persisted, synced to all viewers on every change. An entry is removed
     * automatically once AE2 considers it craftable.
     */
    private final List<ItemStack> domumQueue = new java.util.ArrayList<>();

    // Diff/throttle v1.4.6
    // Signature of the last snapshot actually sent. If the signature recomputed
    // on tick is identical, nothing is sent (zero alloc at rest).
    private long lastWarehouseSig = Long.MIN_VALUE;
    private long lastMeSig        = Long.MIN_VALUE;

    // Constructor

    public WarehouseLinkTerminalPart(IPartItem<?> partItem)
    {
        super(partItem);
    }

    // Model

    /**
     * Drops the Warehouse Link Card when the terminal is removed (wrench or break).
     * addAdditionalDrops is AE2's IPart hook for a Part's extra drops.
     * @MustBeInvokedByOverriders → super call required.
     */
    /**
     * 1.7.0-beta.4 — AE2's CableBusBlockEntity is Clearable and clears every part. Block
     * movers (Sable ship assembly, Create) save the NBT, call Clearable.tryClear, then
     * remove the block, and AEBaseEntityBlock.onRemove calls addAdditionalDrops without
     * an isMoving check: our slots must be empty by then, or their contents duplicate.
     */
    @Override
    public void clearContent()
    {
        super.clearContent();
        warehouseCardSlot.setStackInSlot(0, ItemStack.EMPTY);
        domumTargetSlot.setStackInSlot(0, ItemStack.EMPTY);
        blankPatternSlot.setStackInSlot(0, ItemStack.EMPTY);
        domumOutputSlot.setStackInSlot(0, ItemStack.EMPTY);
        for (int i = 0; i < craftingGridSlot.getSlots(); i++)
            craftingGridSlot.setStackInSlot(i, ItemStack.EMPTY);
    }

    @Override
    public void addAdditionalDrops(java.util.List<ItemStack> drops, boolean wrenched)
    {
        super.addAdditionalDrops(drops, wrenched);
        ItemStack card = warehouseCardSlot.getStackInSlot(0);
        if (!card.isEmpty()) drops.add(card.copy());
        ItemStack target = domumTargetSlot.getStackInSlot(0);
        if (!target.isEmpty()) drops.add(target.copy());
        ItemStack blank = blankPatternSlot.getStackInSlot(0);
        if (!blank.isEmpty()) drops.add(blank.copy());
        for (int i = 0; i < craftingGridSlot.getSlots(); i++)
        {
            ItemStack cg = craftingGridSlot.getStackInSlot(i);
            if (!cg.isEmpty()) drops.add(cg.copy());
        }
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    // PORT(26.1): parts persist through ValueOutput/ValueInput; keys are unchanged from 1.21.1.
    @Override
    public void writeToNBT(ValueOutput data)
    {
        super.writeToNBT(data);
        warehouseCardSlot.serialize(data.child("WarehouseCard"));
        domumTargetSlot.serialize(data.child("DomumTarget"));
        blankPatternSlot.serialize(data.child("BlankPattern"));
        // domumOutputSlot intentionnellement NON persisté — slot preview temporaire
        craftingGridSlot.serialize(data.child("CraftingGrid"));

        // Queue Domum — liste d'ItemStack sérialisés
        ValueOutput.TypedOutputList<ItemStack> queueTag = data.list("DomumQueue", ItemStack.CODEC);
        for (ItemStack stack : domumQueue)
            if (!stack.isEmpty()) queueTag.add(stack);
    }

    @Override
    public void readFromNBT(ValueInput data)
    {
        super.readFromNBT(data);
        data.child("WarehouseCard").ifPresent(warehouseCardSlot::deserialize);
        data.child("DomumTarget").ifPresent(domumTargetSlot::deserialize);
        data.child("BlankPattern").ifPresent(blankPatternSlot::deserialize);
        // domumOutputSlot toujours vide à l'ouverture — pas de désérialisation
        data.child("CraftingGrid").ifPresent(craftingGridSlot::deserialize);

        // Domum queue
        domumQueue.clear();
        for (ItemStack stack : data.listOrEmpty("DomumQueue", ItemStack.CODEC))
            domumQueue.add(stack);
    }

    // Activation (right-click)

    @Override
    public boolean onUseWithoutItem(Player player, Vec3 pos)
    {
        if (this.getLevel() == null || this.getLevel().isClientSide()) return true;
        if (!(player instanceof ServerPlayer sp)) return true;

        // 1.7.0-beta.2 — a pre-beta.2 card in this Terminal is linked on open, only when
        // the Terminal is inside a colony claim (before the permission check, which
        // then reads that colony from the card, as it read the closest colony before).
        migrateOldCard();

        if (!checkPermission(sp))
        {
            sp.sendSystemMessage(Component.translatable("colonylink.terminal.no_permission"));
            return true;
        }

        // 1.7.0-beta.2 — a card that does not resolve (not linked, colony lost or
        // reassigned, Warehouse gone…) is reported; the terminal still opens (ME side).
        if (hasWarehouseCard())
        {
            CardLinks.Resolved card = resolveCard(sp.level());
            if (!card.ok()) msg(sp, card.message());
        }

        BlockEntity hostBe = this.getBlockEntity();
        if (hostBe == null) return true;

        BlockPos hostPos = hostBe.getBlockPos();
        int sideOrd = this.getSide() != null ? this.getSide().ordinal() : 0;

        sp.openMenu(
                new net.minecraft.world.MenuProvider()
                {
                    @Override
                    public Component getDisplayName()
                    { return Component.literal("Warehouse Link Terminal"); }

                    @Override
                    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                            int id, net.minecraft.world.entity.player.Inventory inv, Player p)
                    { return new WarehouseLinkTerminalMenu(id, inv, WarehouseLinkTerminalPart.this); }
                },
                buf -> { buf.writeBlockPos(hostPos); buf.writeByte(sideOrd); }
        );
        return true;
    }

    // Tick

    @Override
    public void addToWorld()
    {
        super.addToWorld();
        ColonyLinkServerTicker.registerTerminalPartGlobal(this);
    }

    @Override
    public void removeFromWorld()
    {
        super.removeFromWorld();
        ColonyLinkServerTicker.unregisterTerminalPartGlobal(this);
        ColonyLinkServerTicker.unregisterTerminalPart(this);
    }

    /**
     * Called by ColonyLinkServerTicker on every global ticker fire.
     * Sends the warehouse + ME snapshot to all active viewers.
     */
    public void serverTick()
    {
        if (viewers.isEmpty()) return;
        if (!(this.getLevel() instanceof ServerLevel level)) return;

        long whSig = computeWarehouseSig(level);
        if (whSig != lastWarehouseSig)
        {
            WarehouseTerminalSyncPacket whPacket = buildWarehouseSnapshot(level);
            for (UUID uid : viewers)
            {
                ServerPlayer sp = level.getServer().getPlayerList().getPlayer(uid);
                if (sp != null)
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, whPacket);
            }
            lastWarehouseSig = whSig;
        }

        long meSig = computeMeSig();
        if (meSig != lastMeSig)
        {
            TerminalMeSyncPacket mePacket = buildMeSnapshot(level);
            for (UUID uid : viewers)
            {
                ServerPlayer sp = level.getServer().getPlayerList().getPlayer(uid);
                if (sp != null)
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, mePacket);
            }
            lastMeSig = meSig;
        }

        // Auto-remove Domum queue entries once craftable in AE2
        tickDomumQueueCraftableCheck();
    }

    // GUI open / close

    public void onGuiOpened(ServerPlayer player)
    {
        viewers.add(player.getUUID());
        ColonyLinkServerTicker.registerTerminalPart(this);
        if (this.getLevel() instanceof ServerLevel level)
        {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, buildWarehouseSnapshot(level));
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, buildMeSnapshot(level));
            // v1.6.1 — SHARED Domum queue: seeds/fetches the grid's common list, aligns
            // the NBT of all terminals and broadcasts to their viewers (including this player).
            IGrid grid = currentGrid();
            if (grid != null)
                syncGrid(level, grid);
            else
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new DomumQueueSyncPacket(new java.util.ArrayList<>()));
            // Snapshot just sent: align the signature to avoid a double send next tick.
            lastWarehouseSig = computeWarehouseSig(level);
            lastMeSig        = computeMeSig();
        }
    }

    public void onGuiClosed() {}

    public void onGuiClosed(ServerPlayer player)
    {
        viewers.remove(player.getUUID());
        if (viewers.isEmpty())
            ColonyLinkServerTicker.unregisterTerminalPart(this);
    }

    public void requestImmediateSync()
    {
        // Force the next serverTick() to resend everything (content just changed).
        lastWarehouseSig = Long.MIN_VALUE;
        lastMeSig        = Long.MIN_VALUE;
        if (viewers.isEmpty()) return;
        if (!(this.getLevel() instanceof ServerLevel level)) return;

        WarehouseTerminalSyncPacket whPacket = buildWarehouseSnapshot(level);
        TerminalMeSyncPacket mePacket = buildMeSnapshot(level);

        for (UUID uid : viewers)
        {
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(uid);
            if (sp == null) continue;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, whPacket);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, mePacket);
        }
    }

    // Content signatures (diff/throttle v1.4.6)

    /**
     * Lightweight ItemStack signature: combines the Item identity (singleton) and
     * its DataComponents (Domum, NBT…) with the count. Allocates no String.
     */
    private static long stackSig(ItemStack stack, long count)
    {
        int h = Item.getId(stack.getItem());          // stable int, no alloc
        h = 31 * h + stack.getComponents().hashCode();
        return ((long) h << 20) ^ count;
    }

    /**
     * Warehouse content signature. Mirrors buildWarehouseSnapshot's traversal
     * (meta-handler or rack fallback) but folds a long (FNV-1a) with no map/list.
     */
    private long computeWarehouseSig(ServerLevel level)
    {
        if (!hasWarehouseCard()) return 0L;

        // 1.7.0-beta.2 — colony + Warehouse come from the card. One distinct signature
        // per failure status, so the panel re-syncs when the status changes.
        CardLinks.Resolved card = resolveCard(level);
        if (!card.ok()) return 1L + card.status().ordinal();
        BuildingWareHouse warehouse = card.warehouse();

        long sig = 0xcbf29ce484222325L; // FNV offset basis
        final long FNV_PRIME = 0x100000001b3L;

        IItemHandler metaHandler = findMetaHandler(level, warehouse);
        if (metaHandler != null)
        {
            for (int s = 0; s < metaHandler.getSlots(); s++)
            {
                ItemStack inSlot = metaHandler.getStackInSlot(s);
                if (inSlot.isEmpty()) continue;
                sig = (sig ^ stackSig(inSlot, inSlot.getCount())) * FNV_PRIME;
            }
        }
        else
        {
            java.util.Set<BlockPos> visitedPos = new java.util.HashSet<>();
            for (BlockPos rackPos : warehouse.getContainers())
            {
                if (!visitedPos.add(rackPos)) continue;
                IItemHandler rack = CapCompat.itemHandler(level, rackPos, null);
                if (rack == null) continue;
                int slots = Math.min(rack.getSlots(), RACK_HALF_SLOTS);
                for (int s = 0; s < slots; s++)
                {
                    ItemStack inSlot = rack.getStackInSlot(s);
                    if (inSlot.isEmpty()) continue;
                    sig = (sig ^ stackSig(inSlot, inSlot.getCount())) * FNV_PRIME;
                }
            }
        }
        return sig;
    }

    /**
     * ME content signature. Same network access as buildMeSnapshot: folds the
     * stock (key + count) and the craftable count.
     */
    private long computeMeSig()
    {
        if (!isActive()) return -1L;
        var node = this.getMainNode().getNode();
        if (node == null) return -1L;
        var grid = node.getGrid();

        long sig = 0xcbf29ce484222325L;
        final long FNV_PRIME = 0x100000001b3L;

        // Stock
        appeng.api.stacks.KeyCounter inv = grid.getStorageService().getCachedInventory();
        for (var entry : inv)
        {
            if (!(entry.getKey() instanceof AEItemKey k)) continue;
            sig = (sig ^ ((long) k.hashCode() << 20 ^ entry.getLongValue())) * FNV_PRIME;
        }

        // Craftables (set size is enough to detect a change)
        try
        {
            ICraftingService crafting = grid.getCraftingService();
            long craftCount = crafting.getCraftables(k -> true).size();
            sig = (sig ^ craftCount) * FNV_PRIME;
        }
        catch (Exception ignored) {}

        return sig;
    }

    // Snapshots

    /**
     * Builds the warehouse snapshot for the client.
     *
     * v1.3.9 — hybrid strategy:
     *  1. Scan getContainers() for the meta-handler (the building's aggregate
     *     handler, identified by getSlots() >= 55).
     *  2. If found: read ONLY the meta-handler. MineColonies handles double-rack
     *     dedup and aggregation of identical stacks across racks — no double-count.
     *  3. If not found (degenerate case: atypical warehouse, mod conflict): fall
     *     back to the old per-rack scan capped at RACK_HALF_SLOTS=27 (v1.3.0).
     */
    private WarehouseTerminalSyncPacket buildWarehouseSnapshot(ServerLevel level)
    {
        BlockPos hostPos = this.getBlockEntity() != null ? this.getBlockEntity().getBlockPos() : BlockPos.ZERO;
        boolean hasCard = hasWarehouseCard();

        if (!hasCard)
            return new WarehouseTerminalSyncPacket(List.of(), false, hostPos, "");

        // 1.7.0-beta.2 — the error is now a translation key (the Screen translates it);
        // it replaces the hard-coded "No colony found" / "No warehouse nearby".
        CardLinks.Resolved card = resolveCard(level);
        if (!card.ok())
            return new WarehouseTerminalSyncPacket(List.of(), true, hostPos, card.panelKey());
        BuildingWareHouse warehouse = card.warehouse();

        java.util.Map<String, WarehouseTerminalSyncPacket.WarehouseItemEntry> aggregated = new java.util.LinkedHashMap<>();

        IItemHandler metaHandler = findMetaHandler(level, warehouse);

        if (metaHandler != null)
        {
            // Meta read: single scan of all slots, no dedup.
            int slots = metaHandler.getSlots();
            for (int s = 0; s < slots; s++)
            {
                ItemStack inSlot = metaHandler.getStackInSlot(s);
                if (inSlot.isEmpty()) continue;
                aggregateItem(aggregated, inSlot);
            }
        }
        else
        {
            // Fallback: per-rack scan (v1.3.0 behavior). Dedup by position
            // (double rack = 2 BlockPos) and RACK_HALF_SLOTS cap to skip mirror slots.
            java.util.Set<BlockPos> visitedPos = new java.util.HashSet<>();
            for (BlockPos rackPos : warehouse.getContainers())
            {
                if (!visitedPos.add(rackPos)) continue;
                IItemHandler rack = CapCompat.itemHandler(level, rackPos, null);
                if (rack == null) continue;
                // Defensive: the RACK_HALF_SLOTS cap keeps a stray meta-handler from
                // skewing the total. Only reached when findMetaHandler() returned null.
                int slots = Math.min(rack.getSlots(), RACK_HALF_SLOTS);
                for (int s = 0; s < slots; s++)
                {
                    ItemStack inSlot = rack.getStackInSlot(s);
                    if (inSlot.isEmpty()) continue;
                    aggregateItem(aggregated, inSlot);
                }
            }
        }

        List<WarehouseTerminalSyncPacket.WarehouseItemEntry> entries = new ArrayList<>(aggregated.values());
        entries.sort((a, b) -> Long.compare(b.count(), a.count()));

        return new WarehouseTerminalSyncPacket(entries, true, hostPos, "");
    }

    /**
     * Merges an ItemStack into the aggregation map keyed by item identity
     * (item + components). Used by both branches of buildWarehouseSnapshot.
     */
    private static void aggregateItem(
            java.util.Map<String, WarehouseTerminalSyncPacket.WarehouseItemEntry> aggregated,
            ItemStack inSlot)
    {
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(inSlot.getItem()).toString()
                + inSlot.getComponents().toString();
        aggregated.merge(key,
                new WarehouseTerminalSyncPacket.WarehouseItemEntry(inSlot.copyWithCount(1), inSlot.getCount()),
                (a, b) -> new WarehouseTerminalSyncPacket.WarehouseItemEntry(a.stack(), a.count() + b.count()));
    }

    /**
     * Finds the MineColonies meta-handler by the Warehouse Hut block position.
     * The meta-handler is exposed at warehouse.getPosition() (the building block),
     * not at a rack position; this works regardless of warehouse size.
     *
     * The Warehouse Hut's internal inventory (9x7 = 63 slots) is handled
     * separately in buildWarehouseSnapshot/pickupFromWarehouse.
     */
    private static IItemHandler findMetaHandler(ServerLevel level, BuildingWareHouse warehouse)
    {
        BlockPos hutPos = warehouse.getPosition();
        IItemHandler h = CapCompat.itemHandler(level, hutPos, null);
        // Verifier que ce handler a plus de slots qu'un rack simple (27 ou 54 slots)
        // pour s'assurer que c'est bien le meta-handler et non un rack place sur le hut
        if (h != null && h.getSlots() > RACK_HALF_SLOTS)
            return h;
        return null;
    }

    private TerminalMeSyncPacket buildMeSnapshot(ServerLevel level)
    {
        BlockPos hostPos = this.getBlockEntity() != null ? this.getBlockEntity().getBlockPos() : BlockPos.ZERO;
        if (!isActive()) return new TerminalMeSyncPacket(List.of(), hostPos);
        var node = this.getMainNode().getNode();
        if (node == null) return new TerminalMeSyncPacket(List.of(), hostPos);
        var grid = node.getGrid();
        return TerminalMeSyncPacket.fromGrid(grid, hostPos);
    }

    // PICK / DEPOSIT (via vanilla carried)


    /**
     * Deposits from the carried stack into the WH.
     * count < carried.count: deposit only count items (right-click).
     * count >= carried.count or count == -1: deposit all.
     */
    public void depositToWarehouse(ServerPlayer player, int count)
    {
        if (!checkWarehouseAccess(player)) return;

        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) return;

        ServerLevel level = player.level();
        CardLinks.Resolved card = resolveCard(level);
        if (!card.ok()) { msg(player, card.message()); return; }
        BuildingWareHouse wh = card.warehouse();
        // v1.4.9 — strict fail-off: warehouse must be fully loaded.
        if (!ColonyLinkChunkUtil.warehouseFullyLoaded(level, wh))
        {
            msg(player, Component.translatable("colonylink.terminal.warehouse_unloaded"));
            return;
        }

        int toDeposit = (count <= 0 || count >= carried.getCount()) ? carried.getCount() : count;
        ItemStack toInsert = carried.copyWithCount(toDeposit);

        int inserted = 0;
        java.util.Set<BlockPos> visitedPos = new java.util.HashSet<>();
        java.util.Set<IItemHandler> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (BlockPos rackPos : wh.getContainers())
        {
            if (toInsert.isEmpty()) break;
            if (!visitedPos.add(rackPos)) continue;
            IItemHandler rack = CapCompat.itemHandler(level, rackPos, null);
            if (rack == null || !visited.add(rack)) continue;
            // v1.3.9 — skip meta-handler: never write via the aggregate.
            ItemStack before = toInsert.copy();
            toInsert = insertIntoHandler(rack, toInsert);
            inserted += before.getCount() - toInsert.getCount();
        }

        if (inserted > 0)
        {
            carried.shrink(inserted);
            player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
            player.containerMenu.broadcastChanges();
        }
        else
            msg(player, Component.translatable("colonylink.terminal.warehouse_full"));
    }

    /**
     * Deposits from the carried stack into the ME.
     */
    public void depositToMe(ServerPlayer player, int count)
    {
        if (!checkPermission(player)) return;

        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) return;

        var node = this.getMainNode().getNode();
        if (node == null) { msg(player, Component.translatable("colonylink.terminal.network_unavailable")); return; }
        var storage = node.getGrid().getStorageService().getInventory();
        IActionSource src = IActionSource.ofPlayer(player, this);
        AEItemKey key = AEItemKey.of(carried);

        int toDeposit = (count <= 0 || count >= carried.getCount()) ? carried.getCount() : count;
        long inserted = storage.insert(key, toDeposit, Actionable.MODULATE, src);

        if (inserted > 0)
        {
            carried.shrink((int) inserted);
            player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
            player.containerMenu.broadcastChanges();
        }
        else
            msg(player, Component.translatable("colonylink.terminal.me_full"));
    }

    // Selection transfers (center arrows)

    // PICKUP/PUT (AE2 PICKUP_OR_SET_DOWN style)

    public void pickupFromWarehouse(ServerPlayer player, ItemStack template, int requestCount)
    {
        if (!checkWarehouseAccess(player)) return;
        ServerLevel level = player.level();
        CardLinks.Resolved card = resolveCard(level);
        if (!card.ok()) { msg(player, card.message()); return; }
        BuildingWareHouse wh = card.warehouse();
        // v1.4.9 — strict fail-off: warehouse must be fully loaded.
        if (!ColonyLinkChunkUtil.warehouseFullyLoaded(level, wh))
        {
            msg(player, Component.translatable("colonylink.terminal.warehouse_unloaded"));
            return;
        }

        ItemStack carried = player.containerMenu.getCarried();

        // Different item on the cursor → deposit it into the WH first
        if (!carried.isEmpty() && !ItemStack.isSameItemSameComponents(carried, template))
        {
            int deposited = transferPlayerToWarehouse(player, carried.copy());
            if (deposited > 0) carried.shrink(deposited);
            player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
            syncCarried(player);
            return;
        }

        int maxCarried = template.getMaxStackSize();
        int alreadyCarried = carried.isEmpty() ? 0 : carried.getCount();
        int canPick = Math.min(requestCount, maxCarried - alreadyCarried);
        if (canPick <= 0) { syncCarried(player); return; }

        int remaining = canPick;
        int totalExtracted = 0;
        java.util.Set<BlockPos> visitedPos = new java.util.HashSet<>();
        java.util.Set<IItemHandler> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        BlockPos hutPos = wh.getPosition();
        for (BlockPos rackPos : wh.getContainers())
        {
            if (remaining <= 0) break;
            if (!visitedPos.add(rackPos)) continue;
            IItemHandler rack = CapCompat.itemHandler(level, rackPos, null);
            if (rack == null || !visited.add(rack)) continue;
            // Warehouse Hut block (building position): scan all slots.
            // Normal racks: cap at RACK_HALF_SLOTS (exposed half).
            int slots = rackPos.equals(hutPos) ? rack.getSlots()
                    : Math.min(rack.getSlots(), RACK_HALF_SLOTS);
            for (int s = 0; s < slots && remaining > 0; s++)
            {
                ItemStack inSlot = rack.getStackInSlot(s);
                if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, template)) continue;
                int toGet = Math.min(remaining, inSlot.getCount());
                ItemStack got = rack.extractItem(s, toGet, false);
                if (!got.isEmpty()) { totalExtracted += got.getCount(); remaining -= got.getCount(); }
            }
        }

        if (totalExtracted == 0) { syncCarried(player); return; }

        if (carried.isEmpty())
            player.containerMenu.setCarried(template.copyWithCount(totalExtracted));
        else { carried.grow(totalExtracted); player.containerMenu.setCarried(carried); }

        syncCarried(player);
    }

    public void pickupFromMe(ServerPlayer player, ItemStack template, int requestCount)
    {
        if (!checkPermission(player)) return;
        var node = this.getMainNode().getNode();
        if (node == null) { msg(player, Component.translatable("colonylink.terminal.network_unavailable")); return; }
        var storage = node.getGrid().getStorageService().getInventory();
        IActionSource src = IActionSource.ofPlayer(player, this);
        AEItemKey key = AEItemKey.of(template);
        if (key == null) return;

        ItemStack carried = player.containerMenu.getCarried();

        // Different item on the cursor → deposit it into the ME first
        if (!carried.isEmpty() && !ItemStack.isSameItemSameComponents(carried, template))
        {
            AEItemKey carriedKey = AEItemKey.of(carried);
            if (carriedKey != null)
            {
                long ins = storage.insert(carriedKey, carried.getCount(), Actionable.MODULATE, src);
                carried.shrink((int) ins);
            }
            player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
            syncCarried(player);
            return;
        }

        int maxCarried = template.getMaxStackSize();
        int alreadyCarried = carried.isEmpty() ? 0 : carried.getCount();
        int canPick = Math.min(requestCount, maxCarried - alreadyCarried);
        if (canPick <= 0) { syncCarried(player); return; }

        long extracted = storage.extract(key, canPick, Actionable.MODULATE, src);
        if (extracted <= 0) { syncCarried(player); return; }

        if (carried.isEmpty())
            player.containerMenu.setCarried(key.toStack((int) extracted));
        else { carried.grow((int) extracted); player.containerMenu.setCarried(carried); }

        syncCarried(player);
    }

    public void putCarriedIntoWarehouse(ServerPlayer player)
    {
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) return;
        if (!checkWarehouseAccess(player)) return;
        int inserted = transferPlayerToWarehouse(player, carried.copy());
        carried.shrink(inserted);
        player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        syncCarried(player);
    }

    public void putCarriedIntoMe(ServerPlayer player)
    {
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) return;
        var node = this.getMainNode().getNode();
        if (node == null) return;
        var storage = node.getGrid().getStorageService().getInventory();
        IActionSource src = IActionSource.ofPlayer(player, this);
        AEItemKey key = AEItemKey.of(carried);
        if (key == null) return;
        long inserted = storage.insert(key, carried.getCount(), Actionable.MODULATE, src);
        carried.shrink((int) inserted);
        player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        syncCarried(player);
    }


    /**
     * WH → ME (center arrows / selection).
     */
    public int transferWarehouseToMe(ServerPlayer player, ItemStack template, int count)
    {
        if (!checkWarehouseAccess(player)) return 0;
        ServerLevel level = player.level();
        CardLinks.Resolved card = resolveCard(level);
        if (!card.ok()) { msg(player, card.message()); return 0; }
        BuildingWareHouse wh = card.warehouse();
        // v1.4.9 — strict fail-off: warehouse must be fully loaded.
        if (!ColonyLinkChunkUtil.warehouseFullyLoaded(level, wh))
        {
            msg(player, Component.translatable("colonylink.terminal.warehouse_unloaded"));
            return 0;
        }

        var node = this.getMainNode().getNode();
        if (node == null) { msg(player, Component.translatable("colonylink.terminal.network_unavailable")); return 0; }
        var storage = node.getGrid().getStorageService().getInventory();
        IActionSource src = IActionSource.ofPlayer(player, this);
        AEItemKey key = AEItemKey.of(template);

        List<ItemStack> extracted = new ArrayList<>();
        int remaining = count;
        java.util.Set<BlockPos> visitedPos = new java.util.HashSet<>();
        java.util.Set<IItemHandler> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (BlockPos rackPos : wh.getContainers())
        {
            if (remaining <= 0) break;
            if (!visitedPos.add(rackPos)) continue;
            IItemHandler rack = CapCompat.itemHandler(level, rackPos, null);
            if (rack == null || !visited.add(rack)) continue;
            // v1.3.9 — skip meta-handler.
            for (int s = 0; s < Math.min(rack.getSlots(), RACK_HALF_SLOTS) && remaining > 0; s++)
            {
                ItemStack inSlot = rack.getStackInSlot(s);
                if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, template)) continue;
                int toGet = Math.min(remaining, inSlot.getCount());
                ItemStack got = rack.extractItem(s, toGet, false);
                if (!got.isEmpty()) { extracted.add(got); remaining -= got.getCount(); }
            }
        }

        if (extracted.isEmpty()) { msg(player, Component.translatable("colonylink.terminal.item_not_in_wh")); return 0; }

        int transferred = 0;
        List<ItemStack> toReturn = new ArrayList<>();
        for (ItemStack stack : extracted)
        {
            long inserted = storage.insert(key, stack.getCount(), Actionable.MODULATE, src);
            transferred += (int) inserted;
            long notInserted = stack.getCount() - inserted;
            if (notInserted > 0) toReturn.add(stack.copyWithCount((int) notInserted));
        }

        java.util.Set<IItemHandler> retVisited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ItemStack ret : toReturn)
        {
            for (BlockPos rackPos : wh.getContainers())
            {
                if (ret.isEmpty()) break;
                IItemHandler rack = CapCompat.itemHandler(level, rackPos, null);
                if (rack == null || !retVisited.add(rack)) continue;
                // v1.3.9 — skip meta-handler.
                ret = insertIntoHandler(rack, ret);
            }
            // 1.7.0-beta.5 — safety net: what the Warehouse refused to take back (not
            // expected: it was just extracted from it) is dropped, never voided.
            dropLeftover(player, ret);
        }

        if (transferred == 0) msg(player, Component.translatable("colonylink.terminal.me_full_or_missing"));
        return transferred;
    }

    /**
     * WH → player inventory (center arrows / selection).
     */
    public int transferWarehouseToInventory(ServerPlayer player, ItemStack template, int count)
    {
        if (!checkWarehouseAccess(player)) return 0;
        ServerLevel level = player.level();
        CardLinks.Resolved card = resolveCard(level);
        if (!card.ok()) { msg(player, card.message()); return 0; }
        BuildingWareHouse wh = card.warehouse();
        // v1.4.9 — strict fail-off: warehouse must be fully loaded.
        if (!ColonyLinkChunkUtil.warehouseFullyLoaded(level, wh))
        {
            msg(player, Component.translatable("colonylink.terminal.warehouse_unloaded"));
            return 0;
        }

        List<ItemStack> extracted = new ArrayList<>();
        int remaining = count;
        java.util.Set<BlockPos> visitedPos = new java.util.HashSet<>();
        java.util.Set<IItemHandler> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (BlockPos rackPos : wh.getContainers())
        {
            if (remaining <= 0) break;
            if (!visitedPos.add(rackPos)) continue;
            IItemHandler rack = CapCompat.itemHandler(level, rackPos, null);
            if (rack == null || !visited.add(rack)) continue;
            // v1.3.9 — skip meta-handler.
            for (int s = 0; s < Math.min(rack.getSlots(), RACK_HALF_SLOTS) && remaining > 0; s++)
            {
                ItemStack inSlot = rack.getStackInSlot(s);
                if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, template)) continue;
                int toGet = Math.min(remaining, inSlot.getCount());
                ItemStack got = rack.extractItem(s, toGet, false);
                if (!got.isEmpty()) { extracted.add(got); remaining -= got.getCount(); }
            }
        }

        if (extracted.isEmpty()) { msg(player, Component.translatable("colonylink.terminal.item_not_in_wh")); return 0; }

        ItemStack toGive = extracted.get(0).copy();
        for (int i = 1; i < extracted.size(); i++) toGive.grow(extracted.get(i).getCount());
        int totalExtracted = toGive.getCount();

        // 1.7.0-beta.5 (audit #2) — Inventory.add() inserts as much as it can, SHRINKS the
        // stack it is given to the remainder and returns false on a partial add. Passing a
        // copy and rolling back the full amount on "false" duplicated the part the player
        // had already received. Pass the stack itself: what is left in it is exactly what
        // the player did NOT get, and only that goes back to the Warehouse.
        player.getInventory().add(toGive);
        int given = totalExtracted - toGive.getCount();
        if (!toGive.isEmpty())
        {
            // Return the leftover (and only it) to the WH
            java.util.Set<IItemHandler> retVis = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (BlockPos rp : wh.getContainers())
            {
                if (toGive.isEmpty()) break;
                IItemHandler rack = CapCompat.itemHandler(level, rp, null);
                if (rack == null || !retVis.add(rack)) continue;
                // v1.3.9 — skip meta-handler.
                toGive = insertIntoHandler(rack, toGive);
            }
            dropLeftover(player, toGive);
            if (given == 0) msg(player, Component.translatable("colonylink.terminal.inventory_full"));
            else msg(player, Component.translatable("colonylink.terminal.inventory_full_partial", given));
        }
        return given;
    }

    /**
     * ME → WH (center arrows / selection).
     */
    public int transferMeToWarehouse(ServerPlayer player, ItemStack template, int count)
    {
        if (!checkWarehouseAccess(player)) return 0;
        ServerLevel level = player.level();
        CardLinks.Resolved card = resolveCard(level);
        if (!card.ok()) { msg(player, card.message()); return 0; }
        BuildingWareHouse wh = card.warehouse();
        // v1.4.9 — strict fail-off: warehouse must be fully loaded.
        if (!ColonyLinkChunkUtil.warehouseFullyLoaded(level, wh))
        {
            msg(player, Component.translatable("colonylink.terminal.warehouse_unloaded"));
            return 0;
        }

        var node = this.getMainNode().getNode();
        if (node == null) { msg(player, Component.translatable("colonylink.terminal.network_unavailable")); return 0; }
        var storage = node.getGrid().getStorageService().getInventory();
        IActionSource src = IActionSource.ofPlayer(player, this);
        AEItemKey key = AEItemKey.of(template);

        long extracted = storage.extract(key, count, Actionable.MODULATE, src);
        if (extracted <= 0) { msg(player, Component.translatable("colonylink.terminal.item_not_in_me")); return 0; }

        ItemStack remaining = key.toStack((int) extracted);
        java.util.Set<BlockPos> visitedPos = new java.util.HashSet<>();
        java.util.Set<IItemHandler> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (BlockPos rackPos : wh.getContainers())
        {
            if (remaining.isEmpty()) break;
            if (!visitedPos.add(rackPos)) continue;
            IItemHandler rack = CapCompat.itemHandler(level, rackPos, null);
            if (rack == null || !visited.add(rack)) continue;
            // v1.3.9 — skip meta-handler.
            remaining = insertIntoHandler(rack, remaining);
        }

        int inserted = (int)(extracted - remaining.getCount());
        if (!remaining.isEmpty())
        {
            long back = storage.insert(key, remaining.getCount(), Actionable.MODULATE, src);
            // 1.7.0-beta.5 — safety net: what the ME refused to take back (not expected:
            // it was just extracted from it) is dropped, never voided.
            if (back < remaining.getCount())
                dropLeftover(player, remaining.copyWithCount((int) (remaining.getCount() - back)));
            if (inserted == 0) msg(player, Component.translatable("colonylink.terminal.warehouse_full"));
            else msg(player, Component.translatable("colonylink.terminal.warehouse_full_partial", inserted));
        }
        return inserted;
    }

    /**
     * ME → player inventory (center arrows / selection).
     */
    public int transferMeToInventory(ServerPlayer player, ItemStack template, int count)
    {
        if (!checkPermission(player)) return 0;

        var node = this.getMainNode().getNode();
        if (node == null) { msg(player, Component.translatable("colonylink.terminal.network_unavailable")); return 0; }
        var storage = node.getGrid().getStorageService().getInventory();
        IActionSource src = IActionSource.ofPlayer(player, this);
        AEItemKey key = AEItemKey.of(template);

        long extracted = storage.extract(key, count, Actionable.MODULATE, src);
        if (extracted <= 0) { msg(player, Component.translatable("colonylink.terminal.item_not_in_me")); return 0; }

        ItemStack toGive = key.toStack((int) extracted);
        // 1.7.0-beta.5 (audit #2/#3) — same fix as transferWarehouseToInventory: let
        // Inventory.add() shrink the stack to the remainder, and re-insert into the ME
        // only that remainder (re-inserting the full 'extracted' duplicated the part the
        // player had already received).
        player.getInventory().add(toGive);
        int given = (int) extracted - toGive.getCount();
        if (!toGive.isEmpty())
        {
            long back = storage.insert(key, toGive.getCount(), Actionable.MODULATE, src);
            if (back < toGive.getCount())
                dropLeftover(player, toGive.copyWithCount((int) (toGive.getCount() - back)));
            if (given == 0) msg(player, Component.translatable("colonylink.terminal.inventory_full"));
            else msg(player, Component.translatable("colonylink.terminal.inventory_full_partial", given));
        }
        return given;
    }

    /**
     * Empties the crafting grid to a destination.
     * @param dest 0=WH, 1=Player, 2=ME
     */
    public int transferCraftGridContents(ServerPlayer player,
                                         net.minecraft.world.inventory.TransientCraftingContainer grid,
                                         int dest)
    {
        int total = 0;
        for (int i = 0; i < grid.getContainerSize(); i++)
        {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty()) continue;
            int sent = switch (dest)
            {
                case 0 -> transferPlayerToWarehouse(player, stack.copy());
                case 1 -> {
                    // 1.7.0-beta.5 (audit #3) — on a partial add the old code yielded 0 and
                    // left the full stack in the grid while the player had received part of
                    // it (duplication). Count what actually went in.
                    ItemStack give = stack.copy();
                    player.getInventory().add(give);
                    yield stack.getCount() - give.getCount();
                }
                case 2 -> transferPlayerToMe(player, stack.copy());
                default -> 0;
            };
            if (sent > 0)
            {
                stack.shrink(sent);
                if (stack.isEmpty()) grid.setItem(i, ItemStack.EMPTY);
                total += sent;
            }
        }
        return total;
    }

    /** Inserts an item from the player inventory into the WH. */
    public int transferPlayerToWarehouse(ServerPlayer player, ItemStack stack)
    {
        if (!checkWarehouseAccess(player)) return 0;
        ServerLevel level = player.level();
        CardLinks.Resolved card = resolveCard(level);
        if (!card.ok()) return 0;
        BuildingWareHouse wh = card.warehouse();
        // v1.4.9 — strict fail-off: warehouse must be fully loaded.
        if (!ColonyLinkChunkUtil.warehouseFullyLoaded(level, wh))
        {
            msg(player, Component.translatable("colonylink.terminal.warehouse_unloaded"));
            return 0;
        }

        int original = stack.getCount();
        java.util.Set<BlockPos> visitedPos = new java.util.HashSet<>();
        java.util.Set<IItemHandler> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (BlockPos rackPos : wh.getContainers())
        {
            if (stack.isEmpty()) break;
            if (!visitedPos.add(rackPos)) continue;
            IItemHandler rack = CapCompat.itemHandler(level, rackPos, null);
            if (rack == null || !visited.add(rack)) continue;
            // v1.3.9 — skip meta-handler.
            stack = insertIntoHandler(rack, stack);
        }
        return original - stack.getCount();
    }

    /** Inserts an item into the ME. */
    public int transferPlayerToMe(ServerPlayer player, ItemStack stack)
    {
        var node = this.getMainNode().getNode();
        if (node == null) return 0;
        var storage = node.getGrid().getStorageService().getInventory();
        IActionSource src = IActionSource.ofPlayer(player, this);
        AEItemKey key = AEItemKey.of(stack);
        long inserted = storage.insert(key, stack.getCount(), Actionable.MODULATE, src);
        if (inserted < stack.getCount())
            msg(player, Component.translatable("colonylink.terminal.me_full_partial", stack.getCount() - inserted));
        return (int) inserted;
    }

    /**
     * Reopens our WarehouseLinkTerminalMenu when the user clicks "Back" from
     * AE2's CraftAmountMenu or CraftConfirmMenu.
     */
    @Override
    public void returnToMainMenu(net.minecraft.world.entity.player.Player player,
                                 ISubMenu subMenu)
    {
        if (this.getLevel() == null || this.getLevel().isClientSide()) return;
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        BlockEntity hostBe = this.getBlockEntity();
        if (hostBe == null) return;
        net.minecraft.core.BlockPos hostPos = hostBe.getBlockPos();
        int sideOrd = this.getSide() != null ? this.getSide().ordinal() : 0;
        sp.openMenu(
                new net.minecraft.world.MenuProvider()
                {
                    @Override public net.minecraft.network.chat.Component getDisplayName()
                    { return net.minecraft.network.chat.Component.literal("Warehouse Link Terminal"); }
                    @Override public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                            int id, net.minecraft.world.entity.player.Inventory inv,
                            net.minecraft.world.entity.player.Player p)
                    { return new WarehouseLinkTerminalMenu(id, inv, WarehouseLinkTerminalPart.this); }
                },
                buf -> { buf.writeBlockPos(hostPos); buf.writeByte(sideOrd); }
        );
    }

    // Domum queue

    // v1.6.1 — SHARED Domum queue over the AE2 network (DomumQueueManager)
    // domumQueue is now only an NBT copy (persistence net); the live shared list is held
    // by DomumQueueManager, indexed by grid. All terminals on the same network read/write
    // the same list → a queued item appears on ALL terminals (shared cable, Quantum Ring…).

    /** Current ME grid of this terminal (null if not connected). */
    private IGrid currentGrid()
    {
        var node = this.getMainNode().getNode();
        return node != null ? node.getGrid() : null;
    }

    /** Union of the NBT copies of all live terminals on the grid — the manager's seed
     *  (also merges divergent queues from old worlds on first read). */
    private static List<ItemStack> collectSeed(ServerLevel level, IGrid grid)
    {
        List<ItemStack> seed = new java.util.ArrayList<>();
        for (WarehouseLinkTerminalPart t : ColonyLinkServerTicker.findLiveTerminalsForGrid(level, grid))
            for (ItemStack cur : t.domumQueue)
            {
                if (cur.isEmpty()) continue;
                boolean dup = false;
                for (ItemStack e : seed)
                    if (ItemStack.isSameItemSameComponents(e, cur)) { dup = true; break; }
                if (!dup) seed.add(cur.copyWithCount(1));
            }
        return seed;
    }

    /** After any mutation: copies the shared queue into each grid terminal's NBT
     *  (persistence) and broadcasts the list to all their viewers. */
    private static void syncGrid(ServerLevel level, IGrid grid)
    {
        if (grid == null) return;
        List<ItemStack> shared = DomumQueueManager.snapshot(grid, () -> collectSeed(level, grid));
        for (WarehouseLinkTerminalPart t : ColonyLinkServerTicker.findLiveTerminalsForGrid(level, grid))
        {
            t.domumQueue.clear();
            for (ItemStack sh : shared) t.domumQueue.add(sh.copyWithCount(1));
            t.getHost().markForSave();
            t.broadcastDomumQueue();
        }
    }

    /** Entry point from DomumQueuePacket: adds to the grid's shared queue and syncs all
     *  terminals. Returns true if added (false = already queued). */
    public static boolean queueDomumOnGrid(ServerLevel level, IGrid grid, ItemStack stack)
    {
        if (grid == null) return false;
        boolean added = DomumQueueManager.add(grid, () -> collectSeed(level, grid), stack);
        if (added) syncGrid(level, grid);
        return added;
    }

    public void addToDomumQueue(ItemStack stack)
    {
        if (!(this.getLevel() instanceof ServerLevel level)) return;
        IGrid grid = currentGrid();
        if (grid == null) return;
        if (DomumQueueManager.add(grid, () -> collectSeed(level, grid), stack))
            syncGrid(level, grid);
    }

    /**
     * Removes a queue item by index.
     * Used by DomumEncodePatternPacket after a successful encode.
     */
    public void removeFromDomumQueue(int index)
    {
        if (!(this.getLevel() instanceof ServerLevel level)) return;
        IGrid grid = currentGrid();
        if (grid == null) return;
        DomumQueueManager.removeAt(grid, () -> collectSeed(level, grid), index);
        syncGrid(level, grid);
    }

    /**
     * Checks whether an exact Domum ItemStack is already queued (dedup).
     * Strict comparison: item + DataComponents (block + MaterialTextureData + BLOCK_STATE).
     */
    public boolean isDomumQueued(ItemStack stack)
    {
        if (!(this.getLevel() instanceof ServerLevel level)) return false;
        IGrid grid = currentGrid();
        return DomumQueueManager.isQueued(grid, () -> collectSeed(level, grid), stack);
    }

    /** Returns an unmodifiable view of the queue (for the menu / screen). */
    public List<ItemStack> getDomumQueue()
    {
        if (!(this.getLevel() instanceof ServerLevel level))
            return java.util.Collections.unmodifiableList(domumQueue);
        IGrid grid = currentGrid();
        return DomumQueueManager.snapshot(grid, () -> collectSeed(level, grid));
    }

    /**
     * On each serverTick, removes queue items that have become craftable in AE2.
     */
    private void tickDomumQueueCraftableCheck()
    {
        if (!isActive()) return;
        if (!(this.getLevel() instanceof ServerLevel level)) return;
        IGrid grid = currentGrid();
        if (grid == null || DomumQueueManager.isEmpty(grid)) return;
        var node = this.getMainNode().getNode();
        if (node == null) return;

        ICraftingService crafting;
        try { crafting = node.getGrid().getCraftingService(); }
        catch (Exception e) { return; }

        if (DomumQueueManager.pruneCraftable(grid, () -> collectSeed(level, grid), crafting))
            syncGrid(level, grid);
    }

    /**
     * Sends the full queue to all active viewers.
     */
    private void broadcastDomumQueue()
    {
        if (viewers.isEmpty()) return;
        if (!(this.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        DomumQueueSyncPacket packet = new DomumQueueSyncPacket(new java.util.ArrayList<>(domumQueue));
        for (UUID uid : viewers)
        {
            net.minecraft.server.level.ServerPlayer sp =
                    level.getServer().getPlayerList().getPlayer(uid);
            if (sp != null)
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, packet);
        }
    }

    // Public getters

    public boolean isAe2Active()     { return this.isActive(); }
    public IManagedGridNode getManagedGridNode() { return this.getMainNode(); }
    public BlockEntity getHostBlockEntity()      { return this.getBlockEntity(); }
    public ItemStackHandler getWarehouseCardSlot() { return warehouseCardSlot; }
    public boolean hasWarehouseCard() { return !warehouseCardSlot.getStackInSlot(0).isEmpty(); }

    // Private helpers


    /**
     * Syncs the carried item (cursor) to the client.
     * Stands in for ServerPlayer.broadcastCarriedItem(), absent in 1.21.1.
     * Sends ClientboundContainerSetSlotPacket(containerId=-1, slot=-1, carried).
     */
    private static void syncCarried(net.minecraft.server.level.ServerPlayer player)
    {
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                -1,
                player.containerMenu.getStateId(),
                -1,
                player.containerMenu.getCarried()));
    }

    private boolean checkPermission(ServerPlayer player)
    {
        // No card: the terminal is a plain ME terminal (it lives on the AE grid,
        // never wireless). ME access is gated by the caller's IActionSource.ofPlayer
        // (AE2 security), by resolvePart requiring the menu to be actually open, and
        // by the menu's range check — no colony binding to enforce here.
        if (!hasWarehouseCard()) return true;
        // 1.7.0-beta.2 — the colony is the one written on the card, checked live on
        // this player. A card that resolves to no colony (not linked, other dimension,
        // colony lost) unlocks nothing: the terminal then behaves exactly like the
        // card-less ME terminal above, and every Warehouse action still fails on the
        // card resolution with its own message (so the player can open the terminal,
        // read the error and take the card out).
        CardLinks.Resolved card = resolveCard(player.level());
        if (card.colony() == null) return true;
        return card.colony().getPermissions().hasPermission(player, Action.ACCESS_HUTS);
    }

    /**
     * 1.7.0 — guard for every Warehouse-touching action. checkPermission alone
     * returns true without a card (so the plain ME actions keep working), which
     * left the Warehouse transfers reachable card-less by a modified client, with
     * no colony permission at all. The GUI never offers them without a card.
     */
    private boolean checkWarehouseAccess(ServerPlayer player)
    {
        return hasWarehouseCard() && checkPermission(player);
    }

    /**
     * 1.7.0-beta.2 — colony + Warehouse written on the card in the slot. Replaces
     * findColony (closest colony to the Terminal, no radius) and findWarehouse (first
     * Warehouse of the building map, unordered).
     */
    private CardLinks.Resolved resolveCard(ServerLevel level)
    {
        return CardLinks.resolve(warehouseCardSlot.getStackInSlot(0), level);
    }

    /**
     * 1.7.0-beta.2 — migration of a pre-beta.2 (blank) card already in this Terminal:
     * linked to the colony whose claim the Terminal stands in, Warehouse closest to
     * the Terminal. Outside any claim nothing happens; a player re-inserting the card
     * links it. Called when a player opens the terminal.
     */
    private void migrateOldCard()
    {
        ItemStack card = warehouseCardSlot.getStackInSlot(0);
        BlockEntity hostBe = this.getBlockEntity();
        if (hostBe == null || !CardLinks.isUnlinkedCard(card)) return;
        ItemStack linked = card.copy();
        if (CardLinks.migrateInTerritory(linked, this.getLevel(), hostBe.getBlockPos()))
            warehouseCardSlot.setStackInSlot(0, linked);
    }

    private static ItemStack insertIntoHandler(IItemHandler handler, ItemStack stack)
    {
        ItemStack remaining = stack.copy();
        for (int s = 0; s < handler.getSlots() && !remaining.isEmpty(); s++)
            remaining = handler.insertItem(s, remaining, false);
        return remaining;
    }

    private static void msg(ServerPlayer player, Component text)
    {
        if (text != null) player.sendSystemMessage(text);
    }

    /**
     * 1.7.0-beta.5 — last-resort safety net of the rollback paths: a remainder the
     * source (Warehouse / ME) refused to take back is dropped at the player's feet
     * instead of being voided. Not expected to trigger (the items were just extracted
     * from that very source), hence the log line.
     */
    private static void dropLeftover(ServerPlayer player, ItemStack leftover)
    {
        if (leftover == null || leftover.isEmpty()) return;
        int n = leftover.getCount();
        ColonyLink.LOGGER.warn("[Terminal] {} x {} could not be returned to its source; dropped at {}'s feet",
                n, leftover.getDisplayName().getString(), player.getGameProfile().name());
        player.drop(leftover, false);
        msg(player, Component.translatable("colonylink.terminal.dropped_on_ground", n));
    }
}
