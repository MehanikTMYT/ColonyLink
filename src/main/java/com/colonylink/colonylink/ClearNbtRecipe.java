package com.colonylink.colonylink;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public class ClearNbtRecipe extends CustomRecipe
{
    public final Item targetItem;
    private final CraftingBookCategory category;

    public ClearNbtRecipe(CraftingBookCategory category, Item targetItem)
    {
        // PORT(26.1): CustomRecipe no longer stores the category; it is kept here for the codec.
        this.category = category;
        this.targetItem = targetItem;
    }

    @Override
    public CraftingBookCategory category() { return category; }

    @Override
    public boolean matches(CraftingInput input, Level level)
    {
        int found = 0;
        for (int i = 0; i < input.size(); i++)
        {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == targetItem) found++;
            else return false;
        }
        return found == 1;
    }

    /**
     * v1.1.3: the RF stored in the wand (NBT key "wand_rf") is preserved on reset;
     * all other NBT (AE2 link, builder entries, active tab) is cleared.
     */
    @Override
    public ItemStack assemble(CraftingInput input)
    {
        for (int i = 0; i < input.size(); i++)
        {
            ItemStack stack = input.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem)
            {
                long storedRf = WandEnergyStorage.getStoredRF(stack);

                ItemStack result = new ItemStack(targetItem, 1);

                if (storedRf > 0)
                    WandEnergyStorage.setStoredRF(result, storedRf);

                return result;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public RecipeSerializer<ClearNbtRecipe> getSerializer()
    {
        return ColonyLinkRecipes.CLEAR_NBT_SERIALIZER.get();
    }
}