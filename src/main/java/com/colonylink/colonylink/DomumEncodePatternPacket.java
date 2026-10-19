package com.colonylink.colonylink;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * DomumEncodePatternPacket — ColonyLink v1.4.2
 *
 * Sent by the client when the player clicks "Encode" in the Cutter tab.
 * Uses RegistryFriendlyByteBuf + ItemStack.STREAM_CODEC (NeoForge 1.21.1).
 *
 * Server result:
 *   - Verifies targetStack is a valid Domum item
 *   - Consumes 1× Blank Pattern from the player's inventory
 *   - Produces 1× encoded DomumPatternItem → given to the player
 *   - targetStack is NOT consumed (like the AE2 Pattern Encoding Terminal)
 */
public record DomumEncodePatternPacket(
        BlockPos  hostPos,
        int       hostSide,
        ItemStack targetStack
) implements CustomPacketPayload
{
    public static final CustomPacketPayload.Type<DomumEncodePatternPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ColonyLink.MODID, "domum_encode_pattern"));

    // RegistryFriendlyByteBuf required for ItemStack.STREAM_CODEC (encodes DataComponents with registries)
    public static final StreamCodec<RegistryFriendlyByteBuf, DomumEncodePatternPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeBlockPos(packet.hostPos);
                        buf.writeByte(packet.hostSide);
                        ItemStack.STREAM_CODEC.encode(buf, packet.targetStack);
                    },
                    buf -> new DomumEncodePatternPacket(
                            buf.readBlockPos(),
                            buf.readByte() & 0xFF,
                            ItemStack.STREAM_CODEC.decode(buf)
                    )
            );

    @Override
    public CustomPacketPayload.Type<DomumEncodePatternPacket> type() { return TYPE; }

    // Server handler

    public static void handle(DomumEncodePatternPacket packet, IPayloadContext ctx)
    {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) handleServer(sp, packet);
        });
    }

    private static void handleServer(ServerPlayer player, DomumEncodePatternPacket packet)
    {
        if (!DomumCraftHandler.isDomumItem(packet.targetStack))
        {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "colonylink.encode.invalid"));
            return;
        }

        HolderLookup.Provider provider = player.serverLevel().registryAccess();

        // The blank pattern is in menu slot 48 (WarehouseLinkTerminalMenu).
        // The Domum slots are at x=-2000 but their ItemStackHandler is reachable via the menu.
        ItemStack blank = findBlankInMenu(player);
        if (blank.isEmpty())
        {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "colonylink.encode.no_blank"));
            return;
        }

        // Fix 3: block encoding if the output slot already holds a pattern
        if (player.containerMenu instanceof WarehouseLinkTerminalMenu tm
                && !tm.domumOutputSlot.getStackInSlot(0).isEmpty())
        {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "colonylink.encode.take_first"));
            return;
        }

        // Output count from the Domum recipe via RecipeManager
        int outputCount = DomumRecipeHelper.getOutputCount(packet.targetStack, player.serverLevel());

        ItemStack encoded = DomumPatternItem.encode(packet.targetStack, provider, outputCount);
        if (encoded.isEmpty())
        {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "colonylink.encode.failed"));
            return;
        }

        // Consume the Blank Pattern (slot 48)
        blank.shrink(1);

        // Inject the encoded pattern into the menu's output slot (49);
        // it stays there until the player takes it manually.
        if (player.containerMenu instanceof WarehouseLinkTerminalMenu terminalMenu)
        {
            // Output slot = the Part's domumOutputSlot (via the menu).
            // Read-only for deposits, but can be written to directly.
            terminalMenu.domumOutputSlot.setStackInSlot(0, encoded);
        }
        else
        {
            // Fallback: give directly if the menu isn't open
            if (!player.getInventory().add(encoded.copy()))
                player.drop(encoded, false);
        }

        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "colonylink.encode.success"));
    }

    /**
     * Finds the blank pattern in slot 48 of the player's open menu.
     * The Domum slots are at x=-2000 but their ItemStackHandlers live in the menu.
     * Returns the ItemStack (direct reference) or EMPTY if absent / wrong type.
     */
    private static ItemStack findBlankInMenu(ServerPlayer player)
    {
        if (!(player.containerMenu instanceof WarehouseLinkTerminalMenu menu)) return ItemStack.EMPTY;
        // Slot 48 = BLANK_PATTERN_SLOT in the menu
        ItemStack s = menu.blankPatternSlot.getStackInSlot(0);
        if (s.isEmpty()) return ItemStack.EMPTY;
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
        if (id != null && id.getPath().equals("blank_pattern")) return s;
        return ItemStack.EMPTY;
    }
}