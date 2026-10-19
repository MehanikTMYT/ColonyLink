package com.colonylink.colonylink;

import appeng.api.stacks.AEItemKey;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent;
import com.ldtteam.domumornamentum.client.model.data.MaterialTextureData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DomumPatternItem — ColonyLink v1.4.2
 *
 * Custom item storing an encoded Domum Ornamentum recipe.
 * NBT via DataComponents.CUSTOM_DATA (NeoForge 1.21.1 API).
 *
 * Only these items may enter the Redirector buffer; standard AE2
 * Encoded Patterns are rejected by isItemValid().
 */
public class DomumPatternItem extends Item
{
    private static final String NBT_TARGET = "domum_target";
    private static final String NBT_COUNT  = "output_count";

    public DomumPatternItem()
    {
        super(new Item.Properties().stacksTo(1));
    }

    // Encoding / Decoding

    /**
     * Encodes a target Domum ItemStack into a new DomumPatternItem.
     * The source ItemStack is NOT consumed.
     */
    public static ItemStack encode(ItemStack domumStack, HolderLookup.Provider provider)
    {
        return encode(domumStack, provider, 1);
    }

    /**
     * Encodes with the Domum recipe count (items produced per craft).
     * Stored in NBT_COUNT so DomumPatternDetails returns the right output.
     */
    public static ItemStack encode(ItemStack domumStack, HolderLookup.Provider provider, int outputCount)
    {
        if (!DomumCraftHandler.isDomumItem(domumStack)) return ItemStack.EMPTY;

        CompoundTag targetTag = (CompoundTag) ItemStack.CODEC
                .encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), domumStack)
                .resultOrPartial(err -> ColonyLink.LOGGER.error("[DomumPattern] encode failed: {}", err))
                .orElse(new CompoundTag());

        if (targetTag.isEmpty()) return ItemStack.EMPTY;

        ItemStack pattern = new ItemStack(ColonyLinkRegistry.DOMUM_PATTERN_ITEM.get());
        final int count = Math.max(1, outputCount);
        pattern.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> {
            CompoundTag tag = existing.copyTag();
            tag.put(NBT_TARGET, targetTag);
            tag.putInt(NBT_COUNT, count);
            return CustomData.of(tag);
        });
        return pattern;
    }

    /**
     * Reads the recipe count from a DomumPatternItem.
     * Returns 1 if unset (compatibility with old patterns).
     */
    public static int getOutputCount(ItemStack patternStack)
    {
        CustomData data = patternStack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 1;
        CompoundTag tag = data.copyTag();
        return tag.contains(NBT_COUNT) ? Math.max(1, tag.getInt(NBT_COUNT)) : 1;
    }

    /**
     * Decodes the target Domum ItemStack from a DomumPatternItem.
     */
    @Nullable
    public static ItemStack decodeTarget(ItemStack patternStack, HolderLookup.Provider provider)
    {
        if (!(patternStack.getItem() instanceof DomumPatternItem)) return ItemStack.EMPTY;

        // NeoForge 1.21.1 API: stack.get(DataComponents.CUSTOM_DATA)
        CustomData customData = patternStack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return ItemStack.EMPTY;

        CompoundTag root = customData.copyTag();
        if (!root.contains(NBT_TARGET)) return ItemStack.EMPTY;

        CompoundTag targetTag = root.getCompound(NBT_TARGET);
        return ItemStack.CODEC
                .parse(provider.createSerializationContext(NbtOps.INSTANCE), targetTag)
                .resultOrPartial(err -> ColonyLink.LOGGER.error("[DomumPattern] decode failed: {}", err))
                .orElse(ItemStack.EMPTY);
    }

    /**
     * Checks whether a DomumPatternItem is valid.
     */
    public static boolean isValid(ItemStack patternStack, HolderLookup.Provider provider)
    {
        ItemStack target = decodeTarget(patternStack, provider);
        return target != null && !target.isEmpty() && DomumCraftHandler.isDomumItem(target);
    }

    /**
     * Returns the materials needed to craft the encoded target.
     */
    public static List<MaterialEntry> getMaterials(ItemStack patternStack, HolderLookup.Provider provider)
    {
        ItemStack target = decodeTarget(patternStack, provider);
        if (target == null || target.isEmpty()) return List.of();
        if (!(target.getItem() instanceof BlockItem bi)) return List.of();

        Block block = bi.getBlock();
        if (!(block instanceof IMateriallyTexturedBlock texturedBlock)) return List.of();

        MaterialTextureData textureData = MaterialTextureData.readFromItemStack(target);
        Map<ResourceLocation, Block> components = textureData.getTexturedComponents();

        List<MaterialEntry> result = new ArrayList<>();
        for (IMateriallyTexturedBlockComponent component : texturedBlock.getComponents())
        {
            Block materialBlock = components.get(component.getId());
            if (materialBlock == null)
            {
                if (!component.isOptional())
                    result.add(new MaterialEntry(null, component.getId(), 1, false));
                continue;
            }
            result.add(new MaterialEntry(materialBlock, component.getId(), 1, true));
        }
        return result;
    }

    // Tooltip

    @Override
    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag)
    {
        Level level = context.level();
        if (level == null) return;

        HolderLookup.Provider provider = level.registryAccess();
        ItemStack target = decodeTarget(stack, provider);

        if (target == null || target.isEmpty())
        {
            lines.add(Component.translatable("colonylink.domum_item.invalid").withStyle(ChatFormatting.RED));
            return;
        }

        // Main line — always visible
        int outputCount = getOutputCount(stack);
        lines.add(Component.translatable("colonylink.redir.crafts").append(target.getDisplayName())
                .append(Component.literal(outputCount > 1 ? " §7(×" + outputCount + ")" : "")
                        .withStyle(ChatFormatting.GRAY)));

        if (net.minecraft.client.gui.screens.Screen.hasShiftDown())
        {
            // Block variant
            net.minecraft.world.item.component.BlockItemStateProperties blockState =
                    stack.get(net.minecraft.core.component.DataComponents.BLOCK_STATE);
            if (blockState != null && !blockState.properties().isEmpty())
            {
                lines.add(Component.translatable("colonylink.domum_item.variant").withStyle(ChatFormatting.GRAY));
                for (var entry : blockState.properties().entrySet())
                    lines.add(Component.literal("§7  " + entry.getKey() + ": §f" + entry.getValue()));
            }

            // Materials
            List<MaterialEntry> materials = getMaterials(stack, provider);
            if (!materials.isEmpty())
            {
                lines.add(Component.translatable("colonylink.redir.materials").withStyle(ChatFormatting.GRAY));
                for (MaterialEntry mat : materials)
                {
                    if (mat.resolved())
                        lines.add(Component.literal("§7  • §f")
                                .append(new ItemStack(mat.block()).getDisplayName())
                                .append(Component.literal(" ×1").withStyle(ChatFormatting.GRAY)));
                    else
                        lines.add(Component.translatable("colonylink.domum_item.missing", mat.componentId().getPath()));
                }
            }

            // Guide
            lines.add(Component.translatable("colonylink.domum_item.place")
                    .withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("colonylink.domum_item.clear")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        else
        {
            lines.add(Component.translatable("colonylink.tip.hold_shift_details")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @Override
    public Component getName(ItemStack stack)
    {
        return Component.translatable("item.colonylink.domum_pattern");
    }

    /**
     * Shift+right-click → reverts to an AE2 Blank Pattern.
     * Same behaviour as AE2 EncodedPatternItem.clearPattern().
     */
    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(
            Level level, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand)
    {
        if (clearPattern(player.getItemInHand(hand), player))
            return net.minecraft.world.InteractionResultHolder.sidedSuccess(
                    player.getItemInHand(hand), level.isClientSide());
        return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    @Override
    public net.minecraft.world.InteractionResult onItemUseFirst(
            ItemStack stack, net.minecraft.world.item.context.UseOnContext context)
    {
        return clearPattern(stack, context.getPlayer())
                ? net.minecraft.world.InteractionResult.sidedSuccess(context.getLevel().isClientSide())
                : net.minecraft.world.InteractionResult.PASS;
    }

    /**
     * If the player is sneaking (shift), replaces the DomumPatternItem with an
     * AE2 Blank Pattern in the inventory.
     */
    private boolean clearPattern(ItemStack stack, net.minecraft.world.entity.player.Player player)
    {
        if (player == null) return false;
        if (!player.isShiftKeyDown()) return false; // server-safe: no Screen.hasShiftDown()
        if (player.level().isClientSide()) return false;

        net.minecraft.world.item.Item blankItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse("ae2:blank_pattern"));
        if (blankItem == null || blankItem == net.minecraft.world.item.Items.AIR) return false;

        ItemStack blank = new ItemStack(blankItem, stack.getCount());
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++)
        {
            if (inv.getItem(i) == stack)
            {
                inv.setItem(i, blank);
                return true;
            }
        }
        return false;
    }

    /**
     * Static helper: returns the target ItemStack from a DomumPatternItem.
     * Used by the Redirector GUI renderer to show the target item instead of the
     * pattern icon in buffer slots. Client-side only (uses clientLevel).
     */
    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    public static ItemStack getTargetStackClient(ItemStack patternStack)
    {
        if (!(patternStack.getItem() instanceof DomumPatternItem)) return patternStack;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return patternStack;
        ItemStack target = decodeTarget(patternStack, mc.level.registryAccess());
        return (target != null && !target.isEmpty()) ? target : patternStack;
    }

    // Record helper

    public record MaterialEntry(
            @Nullable Block block,
            ResourceLocation componentId,
            int count,
            boolean resolved
    ) {}
}