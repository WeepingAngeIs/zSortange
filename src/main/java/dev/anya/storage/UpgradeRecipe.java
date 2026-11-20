package dev.anya.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;

/** Dynamic special recipe: one predecessor backpack plus 1..8 occupied ingredient cells. */
public final class UpgradeRecipe extends CustomRecipe {
    public UpgradeRecipe(CraftingBookCategory category){super(category);}
    private record Match(ItemStack backpack,Config.Tier tier){}
    private Match match(CraftingInput input){
        ItemStack backpack=ItemStack.EMPTY;String material=null;int ingredients=0;
        for(int i=0;i<input.size();i++){ItemStack item=input.getItem(i);if(item.isEmpty())continue;
            if(item.is(Content.BACKPACK.get())){if(!backpack.isEmpty())return null;backpack=item;}
            else {String id=Categories.id(item);if(material!=null&&!material.equals(id))return null;material=id;ingredients++;}
        }
        if(backpack.isEmpty()||ingredients==0)return null;
        String tier=BagInventory.data(backpack).getString("Tier");if(tier.isBlank())tier="starter";
        Match found=null;
        for(Config.Tier candidate:Config.get().tiers)if(!candidate.id.equals("starter")&&Config.unavailable(Config.get(),candidate).isEmpty()&&candidate.material.equals(material)&&candidate.cost==ingredients&&Config.predecessor(Config.get(),candidate).equals(tier)){
            if(found!=null)return null;found=new Match(backpack,candidate);
        }
        return found;
    }
    @Override public boolean matches(CraftingInput input,Level level){return match(input)!=null;}
    @Override public ItemStack assemble(CraftingInput input,HolderLookup.Provider lookup){
        Match match=match(input);if(match==null)return ItemStack.EMPTY;ItemStack result=match.backpack.copyWithCount(1);BagInventory.initialize(result);CompoundTag tag=BagInventory.data(result);
        tag.putString("Tier",match.tier.id);tag.putString("TierName",match.tier.name);
        tag.putInt("Capacity",Math.max(tag.getInt("Capacity"),match.tier.slots));tag.putInt("UpgradeSlots",Math.max(tag.getInt("UpgradeSlots"),match.tier.upgrades));BagInventory.data(result,tag);return result;
    }
    @Override public boolean canCraftInDimensions(int width,int height){return width*height>=2;}
    @Override public RecipeSerializer<?> getSerializer(){return Content.UPGRADE_RECIPE.get();}
}
