package com.colonylink.colonylink;

import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ColonyLinkRedirectorBlock extends Block implements EntityBlock
{
    public static final EnumProperty<net.minecraft.core.Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** true while a craft is in progress (or outputs await injection): drives _off/_on models. */
    public static final BooleanProperty WORKING = BooleanProperty.create("working");

    public ColonyLinkRedirectorBlock(BlockBehaviour.Properties properties)
    {
        // PORT(26.1): the registry hands in Properties already carrying the block id.
        super(properties
                .strength(0.5f)
                .destroyTime(0.5f)
                // The upper half is a glass cage: without noOcclusion() the block is
                // treated as a full opaque cube, so neighbor/ground faces are culled
                // against it (x-ray through the windows) and light cannot enter the
                // cage. Same technique as AE2's Molecular Assembler
                // (AEBlocks: metalProps().noOcclusion()).
                .noOcclusion());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(WORKING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(FACING, WORKING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context)
    {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new ColonyLinkRedirectorBlockEntity(pos, state);
    }

    /**
     * getTicker: used for the first setup tick after loading.
     * The BE notifies its neighbors once the level is fully loaded.
     */
    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> serverType)
    {
        // Pass 2 (Fix 3) — no early client return anymore: createTicker() branches
        // internally (client = sawdust particles only, server = craft pipeline +
        // display sync). Without this delegation the client ticker would be dead code.
        return ColonyLinkRedirectorBlockEntity.createTicker(level, serverType);
    }

    /**
     * neighborChanged: called by Minecraft when an adjacent block changes.
     *
     * In AE2 the CABLE initiates the connection: when a cable is placed it calls
     * neighborChanged on its neighbors, then getGridNode(direction) on the adjacent
     * IInWorldGridNodeHost. Our BE implements IInWorldGridNodeHost and returns its
     * node → AE2 creates the connection automatically.
     *
     * Here we just update the redirector's state in response to the neighbor change.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block neighborBlock, @org.jetbrains.annotations.Nullable Orientation orientation, boolean movedByPiston)
    {
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
        if (level.isClientSide()) return;

        var be = level.getBlockEntity(pos);
        if (be instanceof ColonyLinkRedirectorBlockEntity redirector)
            redirector.updateState();
    }

    /**
     * onPlace: when the redirector is placed, notify neighboring blocks so that
     * existing cables detect the new node and form a connection by calling
     * getGridNode() on this BE.
     */
    /**
     * 1.7.0-beta.4 — a Redirector placed by a player gets its own UUID, even if the item
     * carried block-entity data (creative copy): two Redirectors must never share one.
     * Block movers (Sable, Create) do not call setPlacedBy, so a moved Redirector keeps
     * its UUID.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer, ItemStack stack)
    {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof ColonyLinkRedirectorBlockEntity be)
            be.regenerateRedirectorId();
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos,
                        BlockState oldState, boolean movedByPiston)
    {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide()) return;
        level.updateNeighborsAt(pos, this);
    }

    /**
     * PORT(26.1): onRemove is gone. The buffer/card drops moved to
     * {@link ColonyLinkRedirectorBlockEntity#preRemoveSideEffects}, which vanilla only calls when the block is
     * really replaced; the neighbour update stays here so adjacent AE2 cables drop the node.
     */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston)
    {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        level.updateNeighborsAt(pos, this);
    }

    /**
     * Self-drop defined in code, independent of any JSON loot table.
     * This was the bug: with no loot table (missing/incomplete), the vanilla path
     * playerDestroy() → dropResources() → getDrops() returned nothing, so the block
     * item never dropped. We force the Redirector drop here. Covers ALL destruction
     * cases (pickaxe, explosion, wrench destroyBlock…). Buffer contents + the
     * Warehouse Link Card stay handled by onRemove().
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params)
    {
        return List.of(new ItemStack(ColonyLinkRegistry.REDIRECTOR_BLOCK_ITEM.get()));
    }


    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit)
    {
        ItemStack held = player.getMainHandItem();

        // Wand → PASS on client and server; the wand's useOn() already handles everything
        if (held.getItem() instanceof ColonyLinkWand)
            return InteractionResult.PASS;

        // Wrench (c:tools/wrench) → block GUI opening on both sides.
        // The check must be BEFORE isClientSide() so the client doesn't try to open
        // the GUI on its side.
        var wrenchTag = net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.ITEM,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("c", "tools/wrench"));
        if (held.is(wrenchTag))
        {
            // Sneak + wrench → remove the Redirector cleanly.
            // destroyBlock(pos, true): true → dropResources → getDrops() drops the block
            // item, then onRemove() drops the buffer contents + the Warehouse Link Card.
            // Client side (isClientSide) does nothing: the block removal is replicated by
            // the usual server update packet.
            if (!level.isClientSide() && player.isShiftKeyDown())
                level.destroyBlock(pos, true);
            return InteractionResult.SUCCESS;
        }

        if (level.isClientSide()) return InteractionResult.PASS;

        var be = level.getBlockEntity(pos);
        if (!(be instanceof ColonyLinkRedirectorBlockEntity redirector)) return InteractionResult.PASS;

        // Any other case → open the buffer GUI
        if (!player.isShiftKeyDown())
        {
            player.openMenu(redirector, buf -> buf.writeBlockPos(pos));
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }


}