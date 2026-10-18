package com.colonylink.colonylink;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;

public class ColonyLinkRedirectorMenu extends AbstractContainerMenu
{
    private final ColonyLinkRedirectorBlockEntity blockEntity;
    private final ServerPlayer serverPlayer;

    public static final int BUFFER_COLS = ColonyLinkRedirectorBlockEntity.BUFFER_COLS;
    public static final int BUFFER_ROWS = ColonyLinkRedirectorBlockEntity.BUFFER_ROWS;

    /**
     * Index du slot Warehouse Link Card dans la liste des slots du menu.
     * Il est placé en premier pour simplifier les indices.
     */
    public static final int WAREHOUSE_CARD_SLOT_INDEX = 0;

    /**
     * Extra reach passed to Player.canInteractWithBlock by the ColonyLink menus
     * (Redirector and Warehouse Link Terminal). Vanilla reach is 4.5 blocks from
     * the eyes to the block's box, so 5.0 gives 9.5. That covers every position
     * the pre-1.7.0 check accepted (feet to block centre <= 8, worst case straight
     * above: 8 + 1.62 eye height - 0.5 half block = 9.12).
     */
    public static final double MENU_REACH_BUFFER = 5.0;

    // Client side constructor
    public ColonyLinkRedirectorMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf)
    {
        // 1.7.0-beta.4 — the block entity may be missing on the client (chunk not there
        // yet, e.g. a ship's plot): pass null instead of a ClassCastException / NPE, the
        // menu then runs on placeholder handlers like the Terminal does with part == null.
        this(containerId, playerInventory,
                playerInventory.player.level().getBlockEntity(buf.readBlockPos())
                        instanceof ColonyLinkRedirectorBlockEntity be ? be : null);
    }

    // Server side constructor
    public ColonyLinkRedirectorMenu(int containerId, Inventory playerInventory, ColonyLinkRedirectorBlockEntity blockEntity)
    {
        super(ColonyLinkRegistry.REDIRECTOR_MENU_TYPE.get(), containerId);
        this.blockEntity = blockEntity;
        // 1.7.0-beta.2 — the player who opened the menu, server side only (null on the
        // client): the card slot links a blank card for the player who inserts it.
        this.serverPlayer = playerInventory.player instanceof ServerPlayer sp ? sp : null;
        // A pre-beta.2 card already in the slot is linked on open to the Builder's
        // colony, when the opening player has hut access there.
        if (serverPlayer != null) CardLinks.migrateRedirectorCard(blockEntity, serverPlayer);

        // Placeholder handlers when the block entity is missing (client only, see above).
        net.neoforged.neoforge.items.IItemHandler cardHandler = blockEntity != null
                ? blockEntity.warehouseCardSlot : new net.neoforged.neoforge.items.ItemStackHandler(1);
        net.neoforged.neoforge.items.IItemHandler bufferHandler = blockEntity != null
                ? blockEntity.buffer : new net.neoforged.neoforge.items.ItemStackHandler(BUFFER_ROWS * BUFFER_COLS);

        // ── Slot Warehouse Link Card ─────────────────────────────────────────
        // Item area of the AE2 upgrade-cell box overflowing the top-right edge. The
        // Screen DERIVES the box origin from this slot (box = slot - cell item offset
        // (1,6)), so moving the slot moves the whole cell + fallback + JEI zone + tooltip.
        // Nudged 2px left (177→175) so the cell's open left flank tucks under the panel's
        // right frame → a continuous border all the way around (Fab's on-capture call).
        this.addSlot(new SlotItemHandler(cardHandler, 0, 175, 12)
        {
            @Override
            public boolean mayPlace(ItemStack stack)
            {
                return stack.getItem() instanceof WarehouseLinkCard;
            }

            // 1.7.0-beta.2 — every player insertion (click, drag, shift-click via
            // moveItemStackTo, hotbar swap) goes through setByPlayer. A blank card is
            // linked here, for the inserting player: the Builder's colony first when
            // the player has hut access there, Warehouse closest to the PLAYER (1.7.0-beta.4: the
            // block can sit at a far-away Sable ship plot position; the player stands near it).
            @Override
            public void setByPlayer(ItemStack newStack, ItemStack oldStack)
            {
                if (serverPlayer != null && blockEntity != null && CardLinks.isUnlinkedCard(newStack))
                    CardLinks.autoLink(newStack, serverPlayer, serverPlayer.blockPosition(),
                            ColonyResolver.forRedirector(serverPlayer.level(), blockEntity));
                super.setByPlayer(newStack, oldStack);
            }
        });

        // ── Buffer slots — 9 columns x 3 rows (UI passe 1: grid top at y=38) ─
        for (int row = 0; row < BUFFER_ROWS; row++)
        {
            for (int col = 0; col < BUFFER_COLS; col++)
            {
                this.addSlot(new SlotItemHandler(
                        bufferHandler,
                        row * BUFFER_COLS + col,
                        8 + col * 18,
                        38 + row * 18
                ));
            }
        }

        // ── Inventaire joueur ────────────────────────────────────────────────
        for (int row = 0; row < 3; row++)
        {
            for (int col = 0; col < 9; col++)
            {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 107 + row * 18));
            }
        }

        // ── Hotbar joueur ────────────────────────────────────────────────────
        for (int col = 0; col < 9; col++)
        {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 165));
        }
    }

    @Override
    public boolean stillValid(Player player)
    {
        // v1.6.2 — range check (like a chest, 8 blocks) instead of always-true: the
        // buffer GUI closes if the player walks away or the redirector is removed.
        if (blockEntity == null) return stillValidFail(player, "block entity null");
        if (blockEntity.isRemoved()) return stillValidFail(player, "block entity removed");
        // 1.7.0 — vanilla reach test instead of a hand-rolled distance, so mods that
        // patch Player.canInteractWithBlock (e.g. Sable ships) are honoured.
        if (!player.canInteractWithBlock(blockEntity.getBlockPos(), MENU_REACH_BUFFER))
            return stillValidFail(player, "out of reach (block " + blockEntity.getBlockPos().toShortString()
                    + ", player eyes " + player.getEyePosition() + ")");
        return true;
    }

    /** 1.7.0-beta.4 — logs once per menu (DEBUG) why stillValid closed it; returns false. */
    private boolean loggedStillValidFail = false;

    private boolean stillValidFail(Player player, String reason)
    {
        if (!loggedStillValidFail)
        {
            loggedStillValidFail = true;
            ColonyLink.LOGGER.debug("[ColonyLink] Redirector menu closed for {}: {}",
                    player.getName().getString(), reason);
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index)
    {
        ItemStack returnStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (slot.hasItem())
        {
            ItemStack slotStack = slot.getItem();
            returnStack = slotStack.copy();

            int bufferStart = 1; // après le slot warehouse card
            int bufferEnd = bufferStart + BUFFER_ROWS * BUFFER_COLS;

            if (index == WAREHOUSE_CARD_SLOT_INDEX)
            {
                // Carte → inventaire joueur
                if (!this.moveItemStackTo(slotStack, bufferEnd, this.slots.size(), true))
                    return ItemStack.EMPTY;
            }
            else if (index >= bufferStart && index < bufferEnd)
            {
                // Buffer → inventaire joueur
                if (!this.moveItemStackTo(slotStack, bufferEnd, this.slots.size(), true))
                    return ItemStack.EMPTY;
            }
            else
            {
                // Inventaire joueur
                if (slotStack.getItem() instanceof WarehouseLinkCard)
                {
                    // Carte → slot dédié
                    if (!this.moveItemStackTo(slotStack, WAREHOUSE_CARD_SLOT_INDEX, bufferStart, false))
                        return ItemStack.EMPTY;
                }
                else
                {
                    // Autre item → buffer
                    if (!this.moveItemStackTo(slotStack, bufferStart, bufferEnd, false))
                        return ItemStack.EMPTY;
                }
            }

            if (slotStack.isEmpty())
                slot.set(ItemStack.EMPTY);
            else
                slot.setChanged();
        }

        return returnStack;
    }

    public ColonyLinkRedirectorBlockEntity getBlockEntity()
    {
        return blockEntity;
    }
}