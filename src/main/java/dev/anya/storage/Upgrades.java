package dev.anya.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import java.util.*;

/** Explicitly installed upgrades. Automation never reads armor/offhand or a backpack currently open in a menu. */
public final class Upgrades {
    static final Set<Item> SAFE_FOOD=Set.of(Items.COOKED_BEEF,Items.COOKED_PORKCHOP,Items.COOKED_CHICKEN,Items.COOKED_MUTTON,Items.COOKED_RABBIT,Items.COOKED_COD,Items.COOKED_SALMON,Items.BREAD,Items.BAKED_POTATO,Items.CARROT,Items.APPLE,Items.BEETROOT);
    private static final Map<Item,Item> COMPACT=Map.ofEntries(Map.entry(Items.IRON_NUGGET,Items.IRON_INGOT),Map.entry(Items.GOLD_NUGGET,Items.GOLD_INGOT),Map.entry(Items.IRON_INGOT,Items.IRON_BLOCK),Map.entry(Items.GOLD_INGOT,Items.GOLD_BLOCK),Map.entry(Items.DIAMOND,Items.DIAMOND_BLOCK),Map.entry(Items.EMERALD,Items.EMERALD_BLOCK),Map.entry(Items.COAL,Items.COAL_BLOCK),Map.entry(Items.LAPIS_LAZULI,Items.LAPIS_BLOCK),Map.entry(Items.REDSTONE,Items.REDSTONE_BLOCK),Map.entry(Items.NETHERITE_INGOT,Items.NETHERITE_BLOCK));
    public static ListTag installedItems(ItemStack bag){return BagInventory.data(bag).getList("InstalledUpgrades",Tag.TAG_COMPOUND);}
    public static boolean installed(BagInventory bag,String id){for(Tag entry:installedItems(bag.stack))if(((CompoundTag)entry).getString("Id").equals(id))return true;return false;}
    public static boolean active(BagInventory bag,String id){return Config.get().backpacks&&Boolean.TRUE.equals(Config.get().upgrades.get(Config.upgradeFamily(id)))&&installed(bag,id);}
    static String installProblem(CompoundTag data,ListTag upgrades,String id){
        if(!Config.get().backpacks || !Boolean.TRUE.equals(Config.get().upgrades.get(Config.upgradeFamily(id))))return "This upgrade is disabled by the server.";
        for(Tag entry:upgrades){
            String other=((CompoundTag)entry).getString("Id");
            if(id.equals(other))return "This upgrade is already installed.";
            if(Config.upgradeFamily(id).equals(Config.upgradeFamily(other)))return "Remove the installed tier of this upgrade first.";
        }
        return upgrades.size()>=data.getInt("UpgradeSlots")?"No free upgrade slots.":"";
    }
    static void addDisc(ListTag upgrades,String id,ItemStack disc,ServerPlayer player){
        CompoundTag entry=new CompoundTag();entry.putString("Id",id);entry.put("Stack",disc.save(player.registryAccess()));upgrades.add(entry);
    }
    public static boolean change(BagInventory bag,String id,boolean remove){
        ServerPlayer p=bag.player;if(!bag.valid()||bag.playerSlot<0||bag.playerSlot>=36){AnyaStorage.tell(p,"Put the backpack in your main inventory to edit upgrades.");return false;}
        boolean present=installed(bag,id);if(remove!=present)return false;
        CompoundTag tag=BagInventory.data(bag.stack);ListTag upgrades=tag.getList("InstalledUpgrades",Tag.TAG_COMPOUND).copy();
        if(!remove){String reason=installProblem(tag,upgrades,id);if(!reason.isEmpty()){AnyaStorage.tell(p,reason);return false;}}
        MainInventory main=new MainInventory(p);Transactions.Plan plan=new Transactions.Plan(List.of(main),bag::valid);
        ItemStack old=bag.stack.copy(),next=old.copy(),upgrade=ItemStack.EMPTY;
        List<ItemStack> consumed=new ArrayList<>(),produced=new ArrayList<>();consumed.add(old);
        if(remove){
            for(int i=0;i<upgrades.size();i++)if(upgrades.getCompound(i).getString("Id").equals(id)){upgrade=ItemStack.parseOptional(p.registryAccess(),upgrades.getCompound(i).getCompound("Stack"));upgrades.remove(i);break;}
            if(upgrade.isEmpty()){AnyaStorage.tell(p,"Stored upgrade is invalid; left unchanged for recovery.");return false;}
            ItemStack remaining=upgrade.copy();plan.insert(main,remaining);if(!remaining.isEmpty()){AnyaStorage.tell(p,"Make room in your inventory before removing this upgrade.");return false;}produced.add(upgrade.copy());
        }else{
            for(int i=0;i<36;i++)if(i!=bag.playerSlot&&plan.work(main)[i].is(Content.UPGRADES.get(id).get())){upgrade=plan.work(main)[i].copyWithCount(1);plan.work(main)[i].shrink(1);break;}
            if(upgrade.isEmpty()){AnyaStorage.tell(p,"Craft and carry a "+id+" upgrade first.");return false;}
            addDisc(upgrades,id,upgrade,p);consumed.add(upgrade);
        }
        if(remove)BackpackSmelter.removedDisc(tag,id);
        tag.put("InstalledUpgrades",upgrades);BagInventory.data(next,tag);plan.work(main)[bag.playerSlot]=next;produced.add(next.copy());
        plan.authorizeRecipe(consumed,produced);String error=plan.commit(p.getServer());if(!error.isEmpty()){AnyaStorage.tell(p,error);return false;}return true;
    }
    public static void filter(BagInventory bag,String key,String item){
        if(!bag.valid())return;
        CompoundTag tag=BagInventory.data(bag.stack);ListTag list=tag.getList(key,Tag.TAG_STRING).copy();
        if(list.remove(StringTag.valueOf(item)))BackpackSmelter.removedRule(tag,key,item);
        else{
            boolean valid;
            if(item.startsWith("#")){
                String name=item.substring(1);
                valid=key.equals("PickupFilter")&&active(bag,"advanced_pickup")&&Config.validId(name)
                        &&BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM,ResourceLocation.parse(name))).isPresent();
            }else valid=Config.installed(item);
            if(!valid){AnyaStorage.tell(bag.player,item.startsWith("#")?"Use an existing item tag with Advanced Pickup.":"Enter an installed item ID in the input bar first.");return;}
            if(list.size()>=64){AnyaStorage.tell(bag.player,"Filter limit reached.");return;}
            list.add(StringTag.valueOf(item));
        }
        tag.put(key,list);BagInventory.data(bag.stack,tag);bag.hostChanged();
    }
    public static void craft(BagInventory bag){if(!bag.valid()||!active(bag,"crafting"))return;bag.player.openMenu(new SimpleMenuProvider((id,inv,p)->new CraftingMenu(id,inv,ContainerLevelAccess.NULL),Component.literal("Backpack Crafting")));}
    public static String compact(BagInventory bag){
        boolean advanced=active(bag,"advanced_compacting");
        String tier=advanced?"advanced_compacting":"compacting";
        if(!bag.valid()||!active(bag,tier))return "Install and enable the compacting upgrade first";
        Set<Item> checked=new HashSet<>();
        for(int slot=0;slot<bag.getContainerSize();slot++){
            ItemStack first=bag.getItem(slot);if(first.isEmpty()||!COMPACT.containsKey(first.getItem())||!ItemStack.isSameItemSameComponents(first,new ItemStack(first.getItem()))||!checked.add(first.getItem()))continue;
            List<ItemStack> grid=new ArrayList<>();for(int i=0;i<9;i++)grid.add(new ItemStack(first.getItem()));CraftingInput input=CraftingInput.of(3,3,grid);
            var recipe=bag.player.serverLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,bag.player.level());if(recipe.isEmpty())continue;
            ItemStack output=recipe.get().value().assemble(input,bag.player.registryAccess());if(!output.is(COMPACT.get(first.getItem()))||output.getCount()!=1||(advanced&&!ItemStack.isSameItemSameComponents(output,new ItemStack(output.getItem()))))continue;
            if(recipe.get().value().getRemainingItems(input).stream().anyMatch(s->!s.isEmpty()))continue;
            int available=0;
            for(int i=0;i<bag.getContainerSize();i++){
                ItemStack stack=bag.getItem(i);
                if(ItemStack.isSameItemSameComponents(first,stack))available+=stack.getCount();
            }
            int maxBatches=Math.min(advanced?8:1,available/9);
            for(int batches=maxBatches;batches>0;batches--){
                Transactions.Plan plan=new Transactions.Plan(List.of(bag),()->bag.valid()&&active(bag,tier));
                int need=batches*9;
                for(ItemStack stack:plan.work(bag)){
                    if(!ItemStack.isSameItemSameComponents(stack,first))continue;
                    int taken=Math.min(need,stack.getCount());
                    stack.shrink(taken);need-=taken;
                    if(need==0)break;
                }
                ItemStack result=output.copyWithCount(batches),remaining=result.copy();
                plan.insert(bag,remaining);
                if(!remaining.isEmpty())continue;
                plan.authorizeRecipe(List.of(first.copyWithCount(batches*9)),List.of(result));
                String error=plan.commit(bag.player.getServer());
                return error.isEmpty()?"Compacted "+(batches*9)+" "+first.getHoverName().getString()+" into "+batches+" "+output.getHoverName().getString():error;
            }
            if(maxBatches>0)return "Backpack has no room for the compacted result";
        }
        return "No safe vanilla 3x3 compression recipe is currently possible. Named/modified materials are never compacted.";
    }
    public static void tick(PlayerTickEvent.Post event){
        if(!(event.getEntity() instanceof ServerPlayer p)||p.tickCount%20!=0||!p.isAlive()||p.isSpectator()||p.containerMenu!=p.inventoryMenu||!Config.get().backpacks)return;
        for(var source:BackpackAccess.priority(p))if(!installedItems(source.stack()).isEmpty()&&BackpackAccess.unique(p,source.stack())){
            BagInventory bag=source.open(p);
            try {
                pickup(bag);
                if(active(bag,"refill"))refill(bag);
                if(active(bag,"feeding"))feed(bag);
                else if(active(bag,"advanced_feeding"))AdvancedFeeding.feed(bag);
                BackpackSmelter.stepAll(bag);
            }
            catch(Exception e){AnyaStorage.LOG.error("Backpack automation stopped for {}",p.getUUID(),e);}
            break; // One active backpack per player per second bounds the automation cost.
        }
    }
    static void pickup(BagInventory bag){
        boolean advanced=active(bag,"advanced_pickup");
        String tier=advanced?"advanced_pickup":"pickup";
        if(!active(bag,tier)||!bag.valid())return;
        MainInventory main=new MainInventory(bag.player,bag.playerSlot);Transactions.Plan plan=new Transactions.Plan(List.of(main,bag),()->bag.valid()&&active(bag,tier));
        ListTag filter=BagInventory.data(bag.stack).getList("PickupFilter",Tag.TAG_STRING);Set<String> ids=new HashSet<>();Set<String> tags=new HashSet<>();
        for(Tag entry:filter){
            String rule=entry.getAsString();
            if(rule.startsWith("#")){if(advanced)tags.add(rule.substring(1));}
            else ids.add(rule);
        }
        int moved=0;
        for(int i=0;i<36;i++){
            ItemStack item=plan.work(main)[i];if(item.isEmpty()||Profiles.of(bag.player).protectedSlot(i))continue;
            if(filter.isEmpty()){
                if(!plan.contains(bag,item))continue;
            }else if(!ids.contains(Categories.id(item))&&(tags.isEmpty()||item.getTags().noneMatch(tag->tags.contains(tag.location().toString()))))continue;
            moved+=plan.move(main,i,List.of(bag),item.getCount());
        }
        if(moved>0){String error=plan.commit(bag.player.getServer());if(!error.isEmpty())AnyaStorage.tell(bag.player,error);}
    }
    private static void refill(BagInventory bag){
        MainInventory main=new MainInventory(bag.player,bag.playerSlot);Transactions.Plan plan=new Transactions.Plan(List.of(main,bag),bag::valid);boolean changed=false;
        for(int hotbar=0;hotbar<9;hotbar++){
            ItemStack target=plan.work(main)[hotbar];if(target.isEmpty()||target.getMaxStackSize()<=1)continue;
            for(ItemStack source:plan.work(bag))if(ItemStack.isSameItemSameComponents(source,target)){
                int n=Math.min(source.getCount(),target.getMaxStackSize()-target.getCount());if(n>0){source.shrink(n);target.grow(n);changed=true;}if(target.getCount()>=target.getMaxStackSize())break;
            }
        }
        if(changed){String error=plan.commit(bag.player.getServer());if(!error.isEmpty())AnyaStorage.tell(bag.player,error);}
    }
    private static void feed(BagInventory bag){
        int missing=20-bag.player.getFoodData().getFoodLevel();if(missing<1)return;
        for(int i=0;i<bag.getContainerSize();i++){
            ItemStack food=bag.getItem(i);var props=food.get(DataComponents.FOOD);
            if(!SAFE_FOOD.contains(food.getItem())||props==null||props.nutrition()>missing||!ItemStack.isSameItemSameComponents(food,new ItemStack(food.getItem())))continue;
            ItemStack one=bag.removeItem(i,1);one.finishUsingItem(bag.player.serverLevel(),bag.player);return;
        }
    }
    public static String startCook(BagInventory bag,String family){return BackpackSmelter.start(bag,family);}
    public static boolean cookPending(BagInventory bag,String family){return BackpackSmelter.pending(bag,family);}
    public static void stopCook(BagInventory bag,String family){BackpackSmelter.stop(bag,family);}
    public static int fuelTicks(BagInventory bag,String family){return BackpackSmelter.fuelTicks(bag,family);}
    public static String startSmelt(BagInventory bag){return startCook(bag,"smelting");}
    public static boolean manualSmeltPending(BagInventory bag){return cookPending(bag,"smelting");}
    public static void stopSmelt(BagInventory bag){stopCook(bag,"smelting");}
    public static void smeltingStep(BagInventory bag){BackpackSmelter.stepFurnace(bag);}
    public static void resupply(BagInventory bag){
        if(!bag.valid()||!active(bag,"resupply"))return;ServerPlayer p=bag.player;int selected=p.getInventory().selected;
        if(!p.getMainHandItem().isEmpty()){AnyaStorage.tell(p,"Select an empty hotbar slot first, then use the resupply button.");return;}
        MainInventory main=new MainInventory(p,bag.playerSlot);Transactions.Plan plan=new Transactions.Plan(List.of(main,bag),bag::valid);
        for(int i=0;i<bag.getContainerSize();i++){ItemStack s=plan.work(bag)[i];if(s.getItem() instanceof DiggerItem||s.getItem() instanceof SwordItem){plan.work(main)[selected]=s.copy();plan.work(bag)[i]=ItemStack.EMPTY;String error=plan.commit(p.getServer());AnyaStorage.tell(p,error.isEmpty()?"Tool supplied to the selected hotbar slot.":error);return;}}
        AnyaStorage.tell(p,"No tool or sword in this backpack.");
    }
    public static void dragonDeath(LivingDeathEvent event){
        if(!(event.getEntity() instanceof EnderDragon dragon)||!(event.getSource().getEntity() instanceof ServerPlayer p)||p.isCreative()||dragon.getDragonFight()==null)return;
        if(!Objects.equals(dragon.getDragonFight().getDragonUUID(),dragon.getUUID()))return;
        Profiles data=Profiles.get(p.getServer());if(!data.rewardedDragons.add(dragon.getUUID()))return;data.setDirty();
        ItemStack reward=new ItemStack(Content.DRAGON_TOKEN.get());if(!p.getInventory().add(reward))p.drop(reward,false);
        AnyaStorage.tell(p,"Dragon victory token earned for defeating the tracked End fight dragon.");
    }
}
