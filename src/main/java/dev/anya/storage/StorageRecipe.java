package dev.anya.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import java.util.*;

/** Live server-authoritative crafting rules. No ingot-manufacturing or third-party recipe replacement. */
public final class StorageRecipe extends CustomRecipe {
    public record Definition(String id,String name,String group,String output,String tier,String predecessor,List<String> ingredients,String blocked) {
        public ItemStack outputStack(){
            Item item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(output));ItemStack s=new ItemStack(item);
            if(!tier.isEmpty()){CompoundTag tag=StorageData.metadata(s);tag.putString("AnyaTier",tier);StorageData.metadata(s,tag);}return s;
        }
        public List<ItemStack> preview(){
            List<ItemStack> out=new ArrayList<>();for(String id:ingredients)out.add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))));
            if(!predecessor.isEmpty())for(ItemStack s:out)if(s.getItem() instanceof BackpackItem){CompoundTag tag=StorageData.metadata(s);tag.putString("AnyaTier",predecessor);StorageData.metadata(s,tag);}
            while(out.size()<9)out.add(ItemStack.EMPTY);return out;
        }
    }
    public StorageRecipe(CraftingBookCategory category){super(category);}
    private static Definition def(Rules.Config c,String id,String name,String group,String output,String center,String material,int count){
        List<String> ingredients=new ArrayList<>();ingredients.add(center);for(int i=0;i<count;i++)ingredients.add(material);
        String blocked=!Boolean.TRUE.equals(c.recipes.get(group))?"Recipe group disabled: "+group:"";
        if((group.equals("hubs")||group.equals("terminals"))&&!c.network)blocked="Networks disabled";
        if(group.equals("upgrades")&&(!c.upgrades||!Boolean.TRUE.equals(c.upgradeTypes.get(id.replace("_upgrade","")))))blocked="Upgrade disabled";
        return new Definition(id,name,group,output,"","",ingredients,blocked);
    }
    public static List<Definition> definitions(Rules.Config c){
        List<Definition> out=new ArrayList<>();
        for(Rules.Tier t:c.tiers){List<String> ingredients=new ArrayList<>();ingredients.add(t.parent.isEmpty()?"minecraft:chest":"anyastorage:backpack");for(int i=0;i<t.cost;i++)ingredients.add(t.material);out.add(new Definition("tier_"+t.id,t.name+" Backpack","backpacks","anyastorage:backpack",t.id,t.parent,ingredients,Rules.unavailable(c,t)));}
        out.add(def(c,"storage_hub","Storage Hub","hubs","anyastorage:storage_hub","minecraft:lodestone","minecraft:redstone",8));
        out.add(def(c,"storage_chest","Storage Chest","containers","anyastorage:storage_chest","minecraft:chest","minecraft:iron_ingot",8));
        out.add(def(c,"storage_button","Storage Button","terminals","anyastorage:storage_button","minecraft:stone_button","minecraft:quartz",4));
        out.add(def(c,"storage_core","Storage Core","upgrades","anyastorage:storage_core","minecraft:diamond","minecraft:ender_pearl",8));
        out.add(def(c,"pickup_upgrade","Pickup / Auto-stow Upgrade","upgrades","anyastorage:pickup_upgrade","anyastorage:storage_core","minecraft:iron_ingot",8));
        out.add(def(c,"feeding_upgrade","Feeding Upgrade","upgrades","anyastorage:feeding_upgrade","anyastorage:storage_core","minecraft:golden_carrot",8));
        out.add(def(c,"refill_upgrade","Refill Upgrade","upgrades","anyastorage:refill_upgrade","anyastorage:storage_core","minecraft:redstone",8));
        out.add(def(c,"compacting_upgrade","Compacting Upgrade","upgrades","anyastorage:compacting_upgrade","anyastorage:storage_core","minecraft:quartz",8));
        List<String> cube=new ArrayList<>();cube.add("anyastorage:storage_core");for(int i=0;i<4;i++)cube.add("minecraft:nether_star");for(int i=0;i<4;i++)cube.add(c.endgameMode==2?"anyastorage:dragon_victory_token":"minecraft:dragon_breath");
        out.add(new Definition("uwu_cube","UwU Endgame Cube","endgame","anyastorage:uwu_cube","","",cube,c.endgameMode==0?"Choose an endgame progression route first":!Boolean.TRUE.equals(c.recipes.get("endgame"))?"Endgame recipe gate is OFF":""));
        return out;
    }
    private static boolean matches(Definition d,CraftingInput input){
        if(!d.blocked.isEmpty())return false;
        List<String> remaining=new ArrayList<>(d.ingredients);int bags=0;
        for(int i=0;i<input.size();i++){
            ItemStack s=input.getItem(i);if(s.isEmpty())continue;
            if(s.has(DataComponents.CONTAINER)||s.has(DataComponents.BUNDLE_CONTENTS)||s.has(DataComponents.BLOCK_ENTITY_DATA))return false;
            if(s.getItem() instanceof BackpackItem){bags++;if(d.predecessor.isEmpty()||!StorageData.itemTier(s).equals(d.predecessor))return false;}
            if(!remaining.remove(StorageOps.itemId(s)))return false;
        }
        return remaining.isEmpty()&&(d.predecessor.isEmpty()?bags==0:bags==1);
    }
    private static Definition resolve(CraftingInput input){for(Definition d:definitions(Rules.current))if(matches(d,input))return d;return null;}
    @Override public boolean matches(CraftingInput input,Level level){return resolve(input)!=null;}
    @Override public ItemStack assemble(CraftingInput input,HolderLookup.Provider lookup){
        Definition d=resolve(input);if(d==null)return ItemStack.EMPTY;
        if(!d.predecessor.isEmpty())for(int i=0;i<input.size();i++)if(input.getItem(i).getItem() instanceof BackpackItem){
            ItemStack upgraded=input.getItem(i).copyWithCount(1);CompoundTag tag=StorageData.metadata(upgraded);tag.putString("AnyaTier",d.tier);StorageData.metadata(upgraded,tag);return upgraded;
        }
        return d.outputStack();
    }
    @Override public boolean canCraftInDimensions(int width,int height){return width*height>=2;}
    @Override public RecipeSerializer<?> getSerializer(){return AnyaStorage.STORAGE_RECIPE.get();}
    public static List<String> conflicts(ServerPlayer p,Definition definition){
        if(definition.ingredients.stream().anyMatch(id->!Rules.itemExists(id)))return List.of("Missing registered ingredient; preview only");
        List<String> result=new ArrayList<>();CraftingInput input=CraftingInput.of(3,3,definition.preview());
        for(var holder:p.serverLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)){
            if(holder.value() instanceof StorageRecipe)continue;
            try{if(holder.value().matches(input,p.serverLevel()))result.add(holder.id().toString());}catch(RuntimeException error){result.add("Could not inspect "+holder.id());}
            if(result.size()>=8)break;
        }
        return result;
    }
}
