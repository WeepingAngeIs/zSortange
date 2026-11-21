package dev.anya.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Each installed cooker uses its own recipe type and saved heat, but shares the bag's actual item slots. */
final class BackpackSmelter {
    private static final String LEGACY="smelting";

    private enum Family {
        FURNACE("smelting","Smelt","furnace",RecipeType.SMELTING,1600),
        SMOKER("smoking","Smoke","smoker",RecipeType.SMOKING,800),
        BLAST_FURNACE("blasting","Blast","blast furnace",RecipeType.BLASTING,800);

        final String id, manual, automatic, machine;
        final String filter, mode, slot, input, recipe, definition, ticks, fuel, xp;
        final RecipeType<? extends AbstractCookingRecipe> type;
        final int coalTicks;
        final List<String> jobKeys, savedKeys;
        Family(String id,String prefix,String machine,RecipeType<? extends AbstractCookingRecipe> type,int coalTicks){
            this.id=id;this.manual="manual_"+id;this.automatic="auto_"+id;
            this.machine=machine;this.type=type;this.coalTicks=coalTicks;
            filter=prefix+"Filter";mode=prefix+"Mode";slot=prefix+"Slot";input=prefix+"Input";
            recipe=prefix+"Recipe";definition=prefix+"Definition";ticks=prefix+"Ticks";xp=prefix+"XP";
            fuel=id.equals(LEGACY)?"FuelTicks":prefix+"FuelTicks";
            jobKeys=List.of(mode,slot,input,recipe,definition,ticks);
            savedKeys=List.of(mode,slot,input,recipe,definition,ticks,fuel,xp);
        }
        private static final List<Family> ALL=List.of(values());
        static Family of(String id){
            for(Family family:ALL)if(family.id.equals(id))return family;
            return null;
        }
    }

    private record Cook(int slot,ItemStack input,RecipeHolder<? extends AbstractCookingRecipe> recipe,int time,float xp,
                        List<ItemStack> products,CompoundTag definition) {}

    static boolean pending(BagInventory bag,String id){
        Family family=Family.of(id);
        return family!=null && pending(BagInventory.data(bag.stack),family);
    }
    private static boolean pending(CompoundTag tag,Family family){
        return family.manual.equals(tag.getString(family.mode)) && tag.contains(family.slot,Tag.TAG_INT);
    }
    static int fuelTicks(BagInventory bag,String id){
        Family family=Family.of(id);
        return family==null?0:Math.max(0,BagInventory.data(bag.stack).getInt(family.fuel));
    }
    static void removedDisc(CompoundTag tag,String id){
        for(Family family:Family.ALL)
            if(family.manual.equals(id) && family.manual.equals(tag.getString(family.mode)))clearJob(tag,family);
    }
    private static void clearJob(CompoundTag tag,Family family){
        family.jobKeys.forEach(tag::remove);
    }
    static void removedRule(CompoundTag tag,String filterKey,String item){
        for(Family family:Family.ALL)
            if(family.filter.equals(filterKey) && pending(tag,family)
                    && item.equals(tag.getCompound(family.input).getString("id")))clearJob(tag,family);
    }
    static void stop(BagInventory bag,String id){
        Family family=Family.of(id);
        if(family==null || !bag.player.serverLevel().getServer().isSameThread() || !bag.valid())return;
        CompoundTag tag=BagInventory.data(bag.stack);
        tag.remove(family.filter);clearJob(tag,family);
        BagInventory.data(bag.stack,tag);bag.hostChanged();
    }
    private static Map<String,Integer> filters(CompoundTag data,Family family){
        Map<String,Integer> priority=new LinkedHashMap<>();
        for(Tag rule:data.getList(family.filter,Tag.TAG_STRING))priority.putIfAbsent(rule.getAsString(),priority.size());
        return priority;
    }
    static String start(BagInventory bag,String id){
        Family family=Family.of(id);
        if(family==null)return "Unknown backpack cooker.";
        if(!bag.player.serverLevel().getServer().isSameThread() || !bag.valid() || !family.manual.equals(tier(bag,family)))
            return "Install and enable the manual "+family.machine+" upgrade in this backpack first.";
        CustomData previous=bag.stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag state=BagInventory.data(bag.stack);
        if(pending(state,family))return "A manual cook is already queued for this "+family.machine+".";
        var allow=filters(state,family);
        if(allow.isEmpty())return "Add an ingredient to the "+family.machine+" allowlist first.";
        Cook cook=choose(bag,family,allow,false);
        if(cook==null)return "Put an allowlisted "+family.machine+" ingredient in this backpack first.";
        pin(bag,state,family,family.manual,cook,0);
        if(!publish(bag,previous,state,family,family.manual,cook,null))return "The backpack or recipe changed. Start the cook again.";
        return "Queued one "+cook.input().getHoverName().getString()+". Carry the backpack and close the menu to cook.";
    }
    private static Cook at(BagInventory bag,Family family,int slot){
        ItemStack input=bag.getItem(slot).copyWithCount(1);
        var query=new SingleRecipeInput(input);
        var level=bag.player.serverLevel();
        var match=level.getRecipeManager().getRecipeFor(family.type,query,level);
        if(match.isEmpty())return null;
        var holder=match.get();
        AbstractCookingRecipe recipe=holder.value();
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
    private static Cook choose(BagInventory bag,Family family,Map<String,Integer> allow,boolean slotOrder){
        Cook best=null;int bestRank=Integer.MAX_VALUE;
        for(int i=0;i<bag.getContainerSize();i++){
            ItemStack stack=bag.getItem(i);
            if(stack.isEmpty())continue;
            Integer rank=allow.get(Categories.id(stack));
            if(rank==null || rank>=bestRank)continue;
            Cook cook=at(bag,family,i);
            if(cook==null)continue;
            if(slotOrder || rank==0)return cook;
            best=cook;bestRank=rank;
        }
        return best;
    }
    private static String tier(BagInventory bag,Family family){
        return tier(Upgrades.installedItems(bag.stack),family);
    }
    private static String tier(ListTag installed,Family family){
        if(!Config.get().backpacks || !Boolean.TRUE.equals(Config.get().upgrades.get(family.id)))return null;
        String found=null;
        for(Tag entry:installed){
            String id=((CompoundTag)entry).getString("Id");
            if(!family.manual.equals(id) && !family.automatic.equals(id) && !(family==Family.FURNACE && LEGACY.equals(id)))continue;
            if(found!=null)return null;
            found=id;
        }
        return found;
    }
    private static boolean matches(BagInventory bag,CompoundTag data,Family family,Cook cook,String mode){
        String previous=data.getString(family.mode);
        if(!(previous.equals(mode) || previous.isEmpty() && LEGACY.equals(mode)))return false;
        var stored=ItemStack.parseOptional(bag.player.registryAccess(),data.getCompound(family.input));
        if(!cook.recipe().id().toString().equals(data.getString(family.recipe))
                || !ItemStack.isSameItemSameComponents(stored,cook.input()))return false;
        if(data.contains(family.definition))return cook.definition().equals(data.getCompound(family.definition));
        return LEGACY.equals(mode); // Old paid progress recorded recipe ID and exact input only.
    }
    private static void pin(BagInventory bag,CompoundTag data,Family family,String mode,Cook cook,int progress){
        data.putString(family.mode,mode);data.putInt(family.ticks,progress);
        if(family.manual.equals(mode))data.putInt(family.slot,cook.slot());else data.remove(family.slot);
        data.putString(family.recipe,cook.recipe().id().toString());
        data.put(family.input,cook.input().save(bag.player.registryAccess()));
        data.put(family.definition,cook.definition());
    }
    private static boolean mayRun(BagInventory bag){
        return bag.playerSlot!=BackpackAccess.GROUND_SLOT && bag.player.containerMenu==bag.player.inventoryMenu
                && bag.player.serverLevel().getServer().isSameThread();
    }
    static void stepFurnace(BagInventory bag){
        if(mayRun(bag))step(bag,Family.FURNACE);
    }
    static void stepAll(BagInventory bag){
        if(!mayRun(bag))return;
        ListTag installed=Upgrades.installedItems(bag.stack);
        for(Family family:Family.ALL){
            if(tier(installed,family)!=null)step(bag,family);
        }
    }
    private static void step(BagInventory bag,Family family){
        // The previous cooker may have consumed the final ingredient or piece of coal.
        // Read every job and its shared inventory again before deciding this family's step.
        CustomData previous=bag.stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag state=BagInventory.data(bag.stack);
        String mode=tier(state.getList("InstalledUpgrades",Tag.TAG_COMPOUND),family);
        if(mode==null || !mayRun(bag) || !bag.valid())return;
        boolean manual=family.manual.equals(mode);
        if(manual && !pending(state,family))return;
        Map<String,Integer> allow=filters(state,family);
        Cook cook;
        if(manual){
            int slot=state.getInt(family.slot);
            cook=slot>=0 && slot<bag.getContainerSize() && !bag.getItem(slot).isEmpty()
                    && allow.containsKey(Categories.id(bag.getItem(slot)))?at(bag,family,slot):null;
            if(cook==null || !matches(bag,state,family,cook,mode)){
                clearJob(state,family);publish(bag,previous,state,family,mode,null,null);return;
            }
        }else{
            if(allow.isEmpty())return;
            cook=choose(bag,family,allow,LEGACY.equals(mode));
            if(cook==null)return;
        }
        advance(bag,previous,state,family,mode,cook);
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
    private static void advance(BagInventory bag,CustomData previous,CompoundTag data,Family family,String mode,Cook cook){
        int progress=matches(bag,data,family,cook,mode)?Math.clamp(data.getInt(family.ticks),0,cook.time()):0;
        int fuel=Math.max(0,data.getInt(family.fuel));
        int wanted=Math.min(20,cook.time()-progress);
        int coal=fuel<wanted?fuelSlot(bag,cook):-1;
        if(coal>=0)fuel+=family.coalTicks; // Only added when the existing balance is below 20.
        int paid=Math.min(wanted,fuel);
        if(wanted>0 && paid==0)return;
        boolean complete=progress+paid==cook.time();
        Transactions.Plan plan=new Transactions.Plan(List.of(bag),()->mayRun(bag) && bag.valid() && mode.equals(tier(bag,family))
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
            float earned=data.getFloat(family.xp)+cook.xp();
            if(!Float.isFinite(earned) || earned<0 || earned>=Integer.MAX_VALUE)return;
            award=(int)earned;data.putFloat(family.xp,earned-award);
            if(family.manual.equals(mode))clearJob(data,family);else pin(bag,data,family,mode,cook,0);
        }else{
            pin(bag,data,family,mode,cook,progress+paid);
            if(coal<0)plan=null; // Only the already-paid balance and clock change.
            else{
                for(int i=0;i<slots.length;i++)slots[i]=plan.original(bag)[i].copy();
                slots[coal].shrink(1);plan.authorizeRecipe(List.of(piece),List.of());
            }
        }
        data.putInt(family.fuel,fuel-paid);
        if(mayRun(bag) && publish(bag,previous,data,family,mode,cook,plan) && award>0)bag.player.giveExperiencePoints(award);
    }
    private static boolean currentRecipe(BagInventory bag,Cook cook){
        return cook==null || (bag.player.serverLevel().getRecipeManager().byKey(cook.recipe().id()).orElse(null)==cook.recipe()
                && ItemStack.isSameItemSameComponents(bag.getItem(cook.slot()),cook.input()));
    }
    private static boolean publish(BagInventory bag,CustomData previous,CompoundTag state,Family family,String mode,Cook cook,Transactions.Plan plan){
        if(!bag.valid() || !mode.equals(tier(bag,family)) || !currentRecipe(bag,cook)
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
                // Inventory rollback is owned by the plan. Restore only this cooker's staged fields.
                CompoundTag old=previous.copyTag(), live=BagInventory.data(bag.stack);
                for(String key:family.savedKeys)if(Objects.equals(live.get(key),state.get(key))){
                    if(old.contains(key))live.put(key,old.get(key).copy());else live.remove(key);
                }
                BagInventory.data(bag.stack,live);bag.hostChanged();
            }
        }
    }
    private BackpackSmelter(){}
}
