package dev.anya.storage;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
public final class CubeRecipe extends CustomRecipe {
    public CubeRecipe(CraftingBookCategory category){super(category);}
    @Override public boolean matches(CraftingInput input,Level level){
        Config.Data d=Config.get();if(d.cubeRecipe==0||!Boolean.TRUE.equals(d.recipes.get("endgame")))return false;
        int core=0,stars=0,dragon=0;
        for(int i=0;i<input.size();i++){ItemStack s=input.getItem(i);if(s.isEmpty())continue;if(s.is(Content.CORE.get()))core++;else if(s.is(Items.NETHER_STAR))stars++;else if(s.is(d.cubeRecipe==2?Content.DRAGON_TOKEN.get():Items.DRAGON_BREATH))dragon++;else return false;}
        return core==1&&stars==4&&dragon==4;
    }
    @Override public ItemStack assemble(CraftingInput input,HolderLookup.Provider lookup){return new ItemStack(Content.CUBE.get());}
    @Override public boolean canCraftInDimensions(int w,int h){return w*h>=9;}
    @Override public RecipeSerializer<?> getSerializer(){return Content.CUBE_RECIPE.get();}
}
