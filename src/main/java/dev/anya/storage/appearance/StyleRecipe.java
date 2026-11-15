package dev.anya.storage.appearance;

import dev.anya.storage.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;

/** One existing backpack + one crafted binding/skin. Never reconstructs the backpack's contents. */
public final class StyleRecipe extends CustomRecipe {
    public StyleRecipe(CraftingBookCategory category){super(category);}
    private record Match(ItemStack bag,String key,boolean element){}
    private Match match(CraftingInput input){
        if(!Config.get().backpacks||!Config.get().recipes.get("backpacks"))return null;
        ItemStack bag=ItemStack.EMPTY;String key=null;boolean element=false;
        for(int i=0;i<input.size();i++){
            ItemStack item=input.getItem(i);if(item.isEmpty())continue;
            if(item.is(Content.BACKPACK.get())){if(!bag.isEmpty()||item.getCount()!=1)return null;bag=item;}
            else {
                if(key!=null)return null;
                for(var e:Content.STYLES.entrySet())if(item.is(e.getValue().get())){element=e.getKey().startsWith("element_");key=e.getKey().substring(element?8:5);break;}
                if(key==null)return null;
            }
        }
        return bag.isEmpty()||key==null?null:new Match(bag,key,element);
    }
    @Override public boolean matches(CraftingInput input,Level level){return match(input)!=null;}
    @Override public ItemStack assemble(CraftingInput input,HolderLookup.Provider lookup){Match m=match(input);return m==null?ItemStack.EMPTY:Looks.styledCopy(m.bag,m.key,m.element);}
    @Override public boolean canCraftInDimensions(int w,int h){return w*h>=2;}
    @Override public RecipeSerializer<?> getSerializer(){return Content.STYLE_RECIPE.get();}
}
