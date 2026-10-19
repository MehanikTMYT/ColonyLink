package com.colonylink.colonylink;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

/**
 * Jade provider for the Colony Link Redirector.
 *
 * HUD display:
 * - block name with a state-colored icon
 * - AE2 state (connected / disconnected)
 * - linked builder (name + coordinates)
 * - Warehouse Link Card presence
 * - WH/AE2 priority when a card is present
 * - buffer item count
 * - setup guide when the redirector is not configured
 */
public class RedirectorJadeProvider implements
        IBlockComponentProvider,
        IServerDataProvider<BlockAccessor>
{
    public static final RedirectorJadeProvider INSTANCE = new RedirectorJadeProvider();

    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
            ColonyLink.MODID, "redirector");

    // NBT keys for server → client transfer
    private static final String KEY_STATE        = "cl_state";
    private static final String KEY_AE2_ACTIVE   = "cl_ae2";
    private static final String KEY_HAS_CARD     = "cl_card";
    private static final String KEY_WH_PRIORITY  = "cl_whprio";
    private static final String KEY_BUILDER_X    = "cl_bx";
    private static final String KEY_BUILDER_Y    = "cl_by";
    private static final String KEY_BUILDER_Z    = "cl_bz";
    private static final String KEY_BUFFER_USED    = "cl_buf_used";
    private static final String KEY_BUFFER_MAX     = "cl_buf_max";
    private static final String KEY_DOMUM_PATTERNS = "cl_domum";
    private static final String KEY_DOMUM_INFO     = "cl_domum_info"; // "name|variant;name|variant;..."

    // Server → client data

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor)
    {
        if (!(accessor.getBlockEntity() instanceof ColonyLinkRedirectorBlockEntity be)) return;

        // Recompute state on the fly for consistency with isAe2Active(): be.getState()
        // can be stale (old NBT value) when updateState() has not run since chunk load.
        be.updateState();
        data.putString(KEY_STATE, be.getState().name());
        data.putBoolean(KEY_AE2_ACTIVE, be.isAe2Active());
        data.putBoolean(KEY_HAS_CARD, be.hasWarehouseCard());
        data.putBoolean(KEY_WH_PRIORITY, be.isWarehousePriority());

        if (be.getLinkedBuilderPos() != null)
        {
            data.putInt(KEY_BUILDER_X, be.getLinkedBuilderPos().getX());
            data.putInt(KEY_BUILDER_Y, be.getLinkedBuilderPos().getY());
            data.putInt(KEY_BUILDER_Z, be.getLinkedBuilderPos().getZ());
        }

        int used = 0;
        for (int slot = 0; slot < be.buffer.getSlots(); slot++)
            if (!be.buffer.getStackInSlot(slot).isEmpty()) used++;

        int domumCount = 0;
        StringBuilder domumInfo = new StringBuilder();
        for (int slot = 0; slot < be.buffer.getSlots(); slot++)
        {
            net.minecraft.world.item.ItemStack s = be.buffer.getStackInSlot(slot);
            if (!(s.getItem() instanceof DomumPatternItem)) continue;
            domumCount++;

            net.minecraft.world.item.ItemStack target =
                    DomumPatternItem.decodeTarget(s, accessor.getLevel().registryAccess());
            String name = target != null ? target.getDisplayName().getString() : "?";

            net.minecraft.world.item.component.BlockItemStateProperties bs =
                    s.get(net.minecraft.core.component.DataComponents.BLOCK_STATE);
            String variant = "";
            if (bs != null && !bs.properties().isEmpty())
                variant = bs.properties().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining(","));

            if (domumInfo.length() > 0) domumInfo.append(";");
            domumInfo.append(name).append("|").append(variant);
        }

        data.putInt(KEY_BUFFER_USED, used);
        data.putInt(KEY_BUFFER_MAX, be.buffer.getSlots());
        data.putInt(KEY_DOMUM_PATTERNS, domumCount);
        data.putString(KEY_DOMUM_INFO, domumInfo.toString());


    }

    // Client HUD display

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config)
    {
        CompoundTag data = accessor.getServerData();
        if (data.isEmpty()) return;

        IElementHelper helper = IElementHelper.get();

        // AE2 state
        boolean ae2Active = data.getBoolean(KEY_AE2_ACTIVE);
        String ae2Text = (ae2Active ? Component.translatable("colonylink.jade.ae2_connected").getString() : Component.translatable("colonylink.jade.ae2_disconnected").getString());
        tooltip.add(Component.literal(ae2Text));

        if (!ae2Active)
        {
            tooltip.add(Component.translatable("colonylink.jade.place_adjacent"));
            return;
        }

        // Redirector state
        String stateName = data.getString(KEY_STATE);
        ColonyLinkRedirectorBlockEntity.RedirectorState state;
        try { state = ColonyLinkRedirectorBlockEntity.RedirectorState.valueOf(stateName); }
        catch (Exception e) { state = ColonyLinkRedirectorBlockEntity.RedirectorState.NO_CONTROLLER; }

        switch (state)
        {
            case NOT_LINKED ->
            {
                tooltip.add(Component.translatable("colonylink.jade.status_not_linked"));
                tooltip.add(Component.literal("§8──────────────────"));
                tooltip.add(Component.translatable("colonylink.jade.setup_required"));
                tooltip.add(Component.translatable("colonylink.jade.setup1"));
                tooltip.add(Component.translatable("colonylink.jade.setup2"));
                tooltip.add(Component.translatable("colonylink.jade.setup3"));
            }
            case STANDBY ->
            {
                tooltip.add(Component.translatable("colonylink.jade.status_standby"));
                tooltip.add(Component.translatable("colonylink.jade.standby_full"));
                tooltip.add(Component.translatable("colonylink.jade.standby_resume"));
                appendLinkedInfo(tooltip, data);
            }
            case LINKED ->
            {
                tooltip.add(Component.translatable("colonylink.jade.status_linked"));
                appendLinkedInfo(tooltip, data);
            }
            case NO_CONTROLLER ->
                    tooltip.add(Component.translatable("colonylink.jade.status_no_ae2"));
        }

        // Warehouse Link Card
        tooltip.add(Component.literal("§8──────────────────"));
        boolean hasCard = data.getBoolean(KEY_HAS_CARD);
        if (hasCard)
        {
            tooltip.add(Component.translatable("colonylink.jade.card_inserted"));
            boolean whPriority = data.getBoolean(KEY_WH_PRIORITY);
            tooltip.add(Component.translatable(whPriority ? "colonylink.jade.priority_wh" : "colonylink.jade.priority_ae2"));
        }
        else
        {
            tooltip.add(Component.translatable("colonylink.jade.card_empty"));
            tooltip.add(Component.translatable("colonylink.jade.card_insert1"));
            tooltip.add(Component.translatable("colonylink.jade.card_insert2"));
        }

        // Buffer
        int bufUsed     = data.getInt(KEY_BUFFER_USED);
        int bufMax      = data.getInt(KEY_BUFFER_MAX);
        int domumCount  = data.getInt(KEY_DOMUM_PATTERNS);
        String domumInfo = data.getString(KEY_DOMUM_INFO);
        tooltip.add(Component.literal("§8──────────────────"));
        if (bufUsed > 0)
        {
            tooltip.add(Component.translatable("colonylink.jade.buffer", bufUsed, bufMax, (domumCount > 0 ? Component.translatable("colonylink.jade.buffer_domum", domumCount).getString() : "")));

            // DomumPattern detail (name + variant) — shift only
            if (domumCount > 0 && !domumInfo.isEmpty()
                    && net.minecraft.client.gui.screens.Screen.hasShiftDown())
            {
                for (String entry : domumInfo.split(";"))
                {
                    String[] parts = entry.split("\\|", 2);
                    String name    = parts[0];
                    String variant = parts.length > 1 && !parts[1].isEmpty() ? " §8[" + parts[1] + "]" : "";
                    tooltip.add(Component.literal("§7  • §f" + name + variant));
                }
            }
            else if (domumCount > 0)
            {
                tooltip.add(Component.translatable("colonylink.jade.hold_shift_domum"));
            }
        }
        else
        {
            tooltip.add(Component.translatable("colonylink.jade.buffer_empty"));
        }
    }

    private void appendLinkedInfo(ITooltip tooltip, CompoundTag data)
    {
        if (data.contains(KEY_BUILDER_X))
        {
            int bx = data.getInt(KEY_BUILDER_X);
            int by = data.getInt(KEY_BUILDER_Y);
            int bz = data.getInt(KEY_BUILDER_Z);
            tooltip.add(Component.translatable("colonylink.jade.builder_pos", bx, by, bz));
        }
    }

    @Override
    public ResourceLocation getUid() { return UID; }
}