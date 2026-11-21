package dev.anya.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Furnace jobs run against the carried bag's actual inventory and the server's current recipe manager. */
final class BackpackSmelter {
    private static final String MANUAL="manual_smelting", AUTO="auto_smelting", LEGACY="smelting";
    private static final int COAL_TICKS=1600;
    private static final List<String> COOK_STATE=List.of("SmeltMode","SmeltSlot","SmeltInput","SmeltRecipe",
            "SmeltDefinition","SmeltTicks","FuelTicks","SmeltXP");

    private record Cook(int slot,ItemStack input,RecipeHolder<SmeltingRecipe> recipe,int time,float xp,
                        List<ItemStack> products,CompoundTag definition) {}

    static boolean pending(CompoundTag tag){
        return MANUAL.equals(tag.getString("SmeltMode")) && tag.contains("SmeltSlot",Tag.TAG_INT);
    }
    static void clearManual(CompoundTag tag){
        if(!MANUAL.equals(tag.getString("SmeltMode")))return;
        clearJob(tag);
    }
    private static void clearJob(CompoundTag tag){
        tag.remove("SmeltMode");tag.remove("SmeltSlot");tag.remove("SmeltDefinition");
        tag.remove("SmeltInput");tag.remove("SmeltRecipe");tag.remove("SmeltTicks");
    }
    static void removedRule(CompoundTag tag,String item){
        if(pending(tag) && item.equals(tag.getCompound("SmeltInput").getString("id")))clearManual(tag);
    }
    static void stop(BagInventory bag){
        if(!bag.valid())return;
        CompoundTag tag=BagInventory.data(bag.stack);
        tag.remove("SmeltFilter");clearJob(tag);
        BagInventory.data(bag.stack,tag);bag.hostChanged();
    }
    private static Map<String,Integer> filters(CompoundTag data){
        Map<String,Integer> priority=new LinkedHashMap<>();
        for(Tag rule:data.getList("SmeltFilter",Tag.TAG_STRING))priority.putIfAbsent(rule.getAsString(),priority.size());
        return priority;
    }
    static String start(BagInventory bag){
        if(!bag.player.serverLevel().getServer().isSameThread() || !bag.valid() || !MANUAL.equals(tier(bag)))
            return "Install and enable Manual Smelting in this backpack first.";
        CustomData previous=bag.stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag state=BagInventory.data(bag.stack);
        if(pending(state))return "A manual cook is already queued.";
        var allow=filters(state);
        if(allow.isEmpty())return "Add a furnace input to the smelting allowlist first.";
        Cook cook=choose(bag,allow,false);
        if(cook==null)return "Put an allowlisted furnace ingredient in this backpack first.";
        pin(bag,state,MANUAL,cook,0);
        if(!publish(bag,previous,state,MANUAL,cook,null))return "The backpack or recipe changed. Start the cook again.";
        return "Queued one "+cook.input().getHoverName().getString()+". Carry the backpack and close the menu to cook.";
    }
    private static Cook at(BagInventory bag,int slot){
        ItemStack input=bag.getItem(slot).copyWithCount(1);
        var query=new SingleRecipeInput(input);
        var level=bag.player.serverLevel();
        var match=level.getRecipeManager().getRecipeFor(RecipeType.SMELTING,query,level);
        if(match.isEmpty())return null;
        RecipeHolder<SmeltingRecipe> holder=match.get();
        SmeltingRecipe recipe=holder.value();
        var registries=bag.player.registryAccess();
        ItemStack result=recipe.assemble(query,registries);
        int ticks=recipe.getCookingTime();float xp=recipe.getExperience();
        if(result.isEmpty() || ticks<=0 || !Float.isFinite(xp) || xp<0)return null;
        List<ItemStack> products=new ArrayList<>();products.add(result.copy());
        for(ItemStack remainder:recipe.getRemainingItems(query))if(!remainder.isEmpty())products.add(remainder.copy());
        CompoundTag definition=new CompoundTag();definition.putInt("Time",ticks);definition.putFloat("XP",xp);
        ListTag outputs=new ListTag();for(ItemStack stack:products)outputs.add(stack.save(registries));
        definition.put("Products",outputs);
        return new Cook(slot,input,holder,ticks,xp,products,definition);
    }
    private static Cook choose(BagInventory bag,Map<String,Integer> allow,boolean slotOrder){
        Cook best=null;int bestRank=Integer.MAX_VALUE;
        for(int i=0;i<bag.getContainerSize();i++){
            ItemStack stack=bag.getItem(i);
            if(stack.isEmpty())continue;
            Integer rank=allow.get(Categories.id(stack));
            if(rank==null || rank>=bestRank)continue;
            Cook cook=at(bag,i);
            if(cook==null)continue;
            if(slotOrder || rank==0)return cook;
            best=cook;bestRank=rank;
        }
        return best;
    }
    private static String tier(BagInventory bag){
        if(!Config.get().backpacks || !Boolean.TRUE.equals(Config.get().upgrades.get(LEGACY)))return null;
        String found=null;
        for(Tag entry:Upgrades.installedItems(bag.stack)){
            String id=((CompoundTag)entry).getString("Id");
            if(!MANUAL.equals(id) && !AUTO.equals(id) && !LEGACY.equals(id))continue;
            if(found!=null)return null;
            found=id;
        }
        return found;
    }
    private static boolean matches(BagInventory bag,CompoundTag data,Cook cook,String mode){
        String previous=data.getString("SmeltMode");
        if(!(previous.equals(mode) || previous.isEmpty() && LEGACY.equals(mode)))return false;
        var stored=ItemStack.parseOptional(bag.player.registryAccess(),data.getCompound("SmeltInput"));
        if(!cook.recipe().id().toString().equals(data.getString("SmeltRecipe"))
                || !ItemStack.isSameItemSameComponents(stored,cook.input()))return false;
        if(data.contains("SmeltDefinition"))return cook.definition().equals(data.getCompound("SmeltDefinition"));
        return LEGACY.equals(mode); // Old paid progress recorded recipe ID and exact input only.
    }
    private static void pin(BagInventory bag,CompoundTag data,String mode,Cook cook,int progress){
        data.putString("SmeltMode",mode);data.putInt("SmeltTicks",progress);
        if(MANUAL.equals(mode))data.putInt("SmeltSlot",cook.slot());else data.remove("SmeltSlot");
        data.putString("SmeltRecipe",cook.recipe().id().toString());
        data.put("SmeltInput",cook.input().save(bag.player.registryAccess()));
        data.put("SmeltDefinition",cook.definition());
    }
    static void step(BagInventory bag){
        if(bag.playerSlot==BackpackAccess.GROUND_SLOT || bag.player.containerMenu!=bag.player.inventoryMenu
                || !bag.player.serverLevel().getServer().isSameThread())return;
        String mode=tier(bag);if(mode==null || !bag.valid())return;
        CustomData previous=bag.stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag state=BagInventory.data(bag.stack);
        if(MANUAL.equals(mode) && !pending(state))return;
        Map<String,Integer> allow=filters(state);
        Cook cook;
        if(MANUAL.equals(mode)){
            int slot=state.getInt("SmeltSlot");
            cook=slot>=0 && slot<bag.getContainerSize() && !bag.getItem(slot).isEmpty()
                    && allow.containsKey(Categories.id(bag.getItem(slot)))?at(bag,slot):null;
            if(cook==null || !matches(bag,state,cook,mode)){
                clearManual(state);publish(bag,previous,state,mode,null,null);return;
            }
        }else{
            if(allow.isEmpty())return;
            cook=choose(bag,allow,LEGACY.equals(mode));
            if(cook==null)return;
        }
        advance(bag,previous,state,mode,cook);
    }
    private static int fuelSlot(BagInventory bag,Cook cook){
        for(int i=0;i<bag.getContainerSize();i++){
            ItemStack fuel=bag.getItem(i);
            if(!fuel.is(Items.COAL)&&!fuel.is(Items.CHARCOAL))continue;
            if(i==cook.slot() && fuel.getCount()<2)continue;
            if(ItemStack.isSameItemSameComponents(fuel,new ItemStack(fuel.getItem())))return i;
        }
        return -1;
    }
    private static void advance(BagInventory bag,CustomData previous,CompoundTag data,String mode,Cook cook){
        int progress=matches(bag,data,cook,mode)?Math.clamp(data.getInt("SmeltTicks"),0,cook.time()):0;
        int fuel=Math.max(0,data.getInt("FuelTicks"));
        int wanted=Math.min(20,cook.time()-progress);
        int coal=fuel<wanted?fuelSlot(bag,cook):-1;
        if(coal>=0)fuel+=COAL_TICKS; // Only added when the existing balance is below 20.
        int paid=Math.min(wanted,fuel);
        if(wanted>0 && paid==0)return;
        boolean complete=progress+paid==cook.time();
        Transactions.Plan plan=new Transactions.Plan(List.of(bag),()->bag.valid() && mode.equals(tier(bag))
                && currentRecipe(bag,cook));
        ItemStack[] slots=plan.work(bag);
        if(!ItemStack.isSameItemSameComponents(slots[cook.slot()],cook.input()))return;
        ItemStack piece=coal>=0?slots[coal].copyWithCount(1):ItemStack.EMPTY;
        if(coal>=0)slots[coal].shrink(1);
        slots[cook.slot()].shrink(1);
        // Model the actual post-consumption slots, including a coal slot freed by this purchase.
        for(ItemStack product:cook.products()){
            ItemStack remaining=product.copy();plan.insert(bag,remaining);
            if(!remaining.isEmpty())return;
        }
        int award=0;
        if(complete){
            plan.authorizeRecipe(List.of(cook.input(),piece),cook.products());
            float earned=data.getFloat("SmeltXP")+cook.xp();
            if(!Float.isFinite(earned) || earned<0 || earned>=Integer.MAX_VALUE)return;
            award=(int)earned;data.putFloat("SmeltXP",earned-award);
            if(MANUAL.equals(mode))clearJob(data);else pin(bag,data,mode,cook,0);
        }else{
            pin(bag,data,mode,cook,progress+paid);
            if(coal<0)plan=null; // Only the already-paid balance and clock change.
            else{
                for(int i=0;i<slots.length;i++)slots[i]=plan.original(bag)[i].copy();
                slots[coal].shrink(1);plan.authorizeRecipe(List.of(piece),List.of());
            }
        }
        data.putInt("FuelTicks",fuel-paid);
        if(publish(bag,previous,data,mode,cook,plan) && award>0)bag.player.giveExperiencePoints(award);
    }
    private static boolean currentRecipe(BagInventory bag,Cook cook){
        return cook==null || (bag.player.serverLevel().getRecipeManager().byKey(cook.recipe().id()).orElse(null)==cook.recipe()
                && ItemStack.isSameItemSameComponents(bag.getItem(cook.slot()),cook.input()));
    }
    private static boolean publish(BagInventory bag,CustomData previous,CompoundTag state,String mode,Cook cook,Transactions.Plan plan){
        if(!bag.valid() || !mode.equals(tier(bag)) || !currentRecipe(bag,cook)
                || bag.stack.get(DataComponents.CUSTOM_DATA)!=previous)return false;
        CustomData staged=CustomData.of(state);
        boolean saved=false;
        bag.stack.set(DataComponents.CUSTOM_DATA,staged);
        try{
            if(plan!=null){
                if(!plan.commit(bag.player.getServer()).isEmpty())return false;
            }else bag.hostChanged();
            saved=true;
            return true;
        }finally{
            if(!saved){
                // Inventory rollback is owned by the plan. Restore only the furnace fields we published.
                CompoundTag old=previous.copyTag(), live=BagInventory.data(bag.stack);
                for(String key:COOK_STATE)if(Objects.equals(live.get(key),state.get(key))){
                    if(old.contains(key))live.put(key,old.get(key).copy());else live.remove(key);
                }
                BagInventory.data(bag.stack,live);bag.hostChanged();
            }
        }
    }
    private BackpackSmelter(){}
}
