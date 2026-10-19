package com.colonylink.colonylink;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsTooltip;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent;
import com.ldtteam.domumornamentum.client.model.data.MaterialTextureData;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DomumPatternDetails — ColonyLink v1.4.2
 *
 * Implements IPatternDetails to expose a Domum Ornamentum recipe to the AE2
 * crafting system.
 *
 * Actual AE2 API (from decompiled AECraftingPattern):
 *   - getDefinition()          → AEItemKey (pattern item identifier)
 *   - getInputs()              → IInput[] (array)
 *   - IInput.getPossibleInputs() → GenericStack[] (array, not List)
 *   - IInput.getRemainingKey() → @Nullable AEKey (remainder after craft)
 *   - getOutputs()             → List<GenericStack>
 *   - getTooltip()             → PatternDetailsTooltip(OUTPUT_TEXT_CRAFTS or OUTPUT_TEXT_PROCESSING)
 */
public class DomumPatternDetails implements IPatternDetails
{
    private final AEItemKey          definition;    // AEItemKey of the DomumPatternItem (AE2 identifier)
    private final ItemStack          patternStack;  // The DomumPatternItem (to read NBT_COUNT)
    private final ItemStack          targetStack;   // The target Domum item
    private final IInput[]           inputs;        // Materials → AE2 inputs
    private final List<GenericStack> outputs;       // Domum block → AE2 output

    // Constructor

    public DomumPatternDetails(ItemStack patternStack, HolderLookup.Provider provider)
    {
        this.definition    = AEItemKey.of(patternStack);
        this.patternStack  = patternStack.copy();

        ItemStack decoded = DomumPatternItem.decodeTarget(patternStack, provider);
        this.targetStack = (decoded != null && !decoded.isEmpty()) ? decoded : ItemStack.EMPTY;

        this.outputs = buildOutputs();
        this.inputs  = buildInputs(provider);
    }

    // IPatternDetails

    /**
     * The pattern's AE2 identifier — AEItemKey of the DomumPatternItem.
     * AE2 uses this for uniqueness and caching.
     */
    @Override
    public AEItemKey getDefinition()
    {
        return definition;
    }

    /**
     * Inputs: the raw materials (1 IInput per Domum component).
     */
    @Override
    public IInput[] getInputs()
    {
        return inputs;
    }

    /**
     * Outputs: the target Domum block (1 item).
     */
    @Override
    public List<GenericStack> getOutputs()
    {
        return outputs;
    }

    /**
     * Primary output — used by AE2 for the search bar and JEI/REI.
     */
    @Override
    public GenericStack getPrimaryOutput()
    {
        return outputs.isEmpty() ? new GenericStack(definition, 1L) : outputs.get(0);
    }

    // AE2 tooltip

    @Override
    public PatternDetailsTooltip getTooltip(Level level, TooltipFlag flags)
    {
        // OUTPUT_TEXT_CRAFTS is a static Component constant in PatternDetailsTooltip
        // (as in AECraftingPattern: new PatternDetailsTooltip(OUTPUT_TEXT_CRAFTS))
        PatternDetailsTooltip tooltip = new PatternDetailsTooltip(
                PatternDetailsTooltip.OUTPUT_TEXT_CRAFTS);

        for (GenericStack output : outputs)
            tooltip.addOutput(output.what(), output.amount());

        for (IInput input : inputs)
        {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible.length > 0 && possible[0] != null)
                tooltip.addInput(possible[0].what(), possible[0].amount());
        }

        return tooltip;
    }

    // Validity

    public boolean isValid() { return !targetStack.isEmpty() && definition != null; }

    public ItemStack getTargetStack() { return targetStack.copy(); }

    // Internal builders

    private List<GenericStack> buildOutputs()
    {
        if (targetStack.isEmpty()) return List.of();
        AEItemKey key = AEItemKey.of(targetStack);
        if (key == null) return List.of();
        long count = DomumPatternItem.getOutputCount(patternStack);
        return List.of(new GenericStack(key, count));
    }

    private IInput[] buildInputs(HolderLookup.Provider provider)
    {
        if (targetStack.isEmpty()) return new IInput[0];
        if (!(targetStack.getItem() instanceof BlockItem bi)) return new IInput[0];

        Block block = bi.getBlock();
        if (!(block instanceof IMateriallyTexturedBlock texturedBlock)) return new IInput[0];

        MaterialTextureData textureData = MaterialTextureData.readFromItemStack(targetStack);
        Map<ResourceLocation, Block> components = textureData.getTexturedComponents();

        List<IInput> result = new ArrayList<>();
        for (IMateriallyTexturedBlockComponent component : texturedBlock.getComponents())
        {
            Block materialBlock = components.get(component.getId());
            if (materialBlock == null)
            {
                if (!component.isOptional())
                    ColonyLink.LOGGER.warn("[DomumPattern] Missing required component: {}", component.getId());
                continue;
            }

            AEItemKey key = AEItemKey.of(new ItemStack(materialBlock, 1));
            if (key == null) continue;

            final GenericStack stack = new GenericStack(key, 1L);

            result.add(new IInput()
            {
                @Override
                public GenericStack[] getPossibleInputs()
                {
                    return new GenericStack[]{ stack };
                }

                @Override
                public long getMultiplier()
                {
                    return 1L;
                }

                // A Domum material is valid only if the key matches exactly
                @Override
                public boolean isValid(AEKey input, Level level)
                {
                    return input.matches(stack);
                }

                @Override
                @Nullable
                public AEKey getRemainingKey(AEKey template)
                {
                    return null;
                }
            });
        }

        return result.toArray(new IInput[0]);
    }

    // Equals / hashCode

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof DomumPatternDetails other)) return false;
        return definition.equals(other.definition);
    }

    @Override
    public int hashCode()
    {
        return definition.hashCode();
    }
}