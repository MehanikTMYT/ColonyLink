package com.colonylink.colonylink;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Renders the Redirector's current craft item inside the glass cage, sliding
 * along the saw blade plane (local Z=8) on a short loop at a perfectly fixed
 * height — as if being fed through the spinning blade.
 *
 * <p>Animation clock: driven by the client's {@code gameTime + partialTick}, NOT by
 * {@link ColonyLinkRedirectorBlockEntity#getShuffleProgress()}. The server only
 * syncs the block entity to clients when the render stack is (re)picked — roughly
 * every 5 ticks — so {@code shuffleProgress} on the client is effectively pinned
 * near 0 and cannot drive a smooth slide. A client-side game-time loop is smooth
 * (interpolated by {@code partialTick}), pauses with the game, and needs no extra
 * per-tick network sync. {@code getRenderStack()} still supplies WHICH item shows;
 * a new pick simply swaps the sprite mid-conveyor.
 *
 * <p>Server safety: {@code } — this class is only referenced
 * from {@link ColonyLinkClient} (also client-only) and registered client-side.
 */
public class RedirectorBlockEntityRenderer
        implements BlockEntityRenderer<ColonyLinkRedirectorBlockEntity, RedirectorBlockEntityRenderer.State>
{
    /** Item ride height in blocks — CONSTANT (bobbing removed in pass 4): the deck
     *  (cage floor) sits at Y=11px, the cage interior spans Y=11..15.5px; 12px keeps
     *  the item skimming the deck through the blade. */
    private static final double BASE_Y = 12.0 / 16.0;
    /** Horizontal slide half-range in pixels (item travels -3px .. +3px). */
    private static final float SLIDE_HALF_PX = 3.0f;
    /** Slide loop length in ticks (matches the server shuffle period). */
    private static final float SLIDE_TICKS = 5.0f;
    /** Uniform scale applied to the displayed item (1.0 = GROUND default). Tune to taste. */
    private static final float ITEM_SCALE = 0.75f;

    /**
     * PORT(26.1): block entity rendering is split into a client-thread extract step and a submit step that only
     * sees this state. Everything the old render() derived from the block entity is captured here.
     */
    public static class State extends BlockEntityRenderState
    {
        final ItemStackRenderState item = new ItemStackRenderState();
        Direction facing = Direction.NORTH;
        float time;
    }

    private final ItemModelResolver itemModelResolver;

    public RedirectorBlockEntityRenderer(BlockEntityRendererProvider.Context context)
    {
        this.itemModelResolver = context.itemModelResolver();
    }

    @Override
    public State createRenderState()
    {
        return new State();
    }

    @Override
    public void extractRenderState(ColonyLinkRedirectorBlockEntity blockEntity, State state, float partialTick,
                                   Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress)
    {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTick, cameraPosition, breakProgress);
        Level level = blockEntity.getLevel();
        // Client animation clock: smooth, pauses with the game, no extra sync needed.
        state.time = (level != null ? (float) (level.getGameTime() % 100000L) : 0.0f) + partialTick;
        state.facing = blockEntity.getBlockState().getValue(ColonyLinkRedirectorBlock.FACING);
        ItemStack stack = blockEntity.getRenderStack();
        this.itemModelResolver.updateForTopItem(state.item, stack, ItemDisplayContext.GROUND, level, null,
                blockEntity.getBlockPos().hashCode());
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera)
    {
        if (state.item.isEmpty()) return;

        // Horizontal slide: 0..1 looping every SLIDE_TICKS, mapped to -3px .. +3px.
        float slide = (state.time % SLIDE_TICKS) / SLIDE_TICKS;
        float xOffsetPx = -SLIDE_HALF_PX + slide * (SLIDE_HALF_PX * 2.0f);

        poseStack.pushPose();
        // Rotate around the block centre so the item slides along the model's local
        // X axis — i.e. inside the saw blade plane (local Z=8) — for every FACING.
        // 180 - toYRot() maps NORTH->0, EAST->-90, SOUTH->180, WEST->90, matching
        // the blockstate y-rotations (north=0, east=90, south=180, west=270).
        poseStack.translate(0.5, 0.0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - state.facing.toYRot()));
        // Local offsets after rotation; pixel offsets converted to block units (/16).
        // Y is constant — only the X slide animates.
        poseStack.translate(xOffsetPx / 16.0, BASE_Y, 0.0);
        // Uniform shrink around the display point set by the translate above.
        poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
        state.item.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    @Override
    public int getViewDistance()
    {
        return 64;
    }
}
