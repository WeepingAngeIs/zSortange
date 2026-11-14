package dev.anya.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import java.util.*;

public final class StorageUpgrades {
    private StorageUpgrades(){}
    private static final Set<Item> SAFE_FOODS=new HashSet<>();
    private static Set<Item> foods(){if(SAFE_FOODS.isEmpty())SAFE_FOODS.addAll(List.of(Items.BREAD,Items.BAKED_POTATO,Items.COOKED_BEEF,Items.COOKED_PORKCHOP,Items.COOKED_CHICKEN,Items.COOKED_MUTTON,Items.COOKED_RABBIT,Items.COOKED_SALMON,Items.COOKED_COD,Items.CARROT,Items.GOLDEN_CARROT));return SAFE_FOODS;}
    public static String upgrade(ItemStack s){for(var e:AnyaStorage.UPGRADES.entrySet())if(s.is(e.getValue().get()))return e.getKey();return null;}
    public static void tick(ServerPlayer p){
        if(!Rules.current.upgrades||!Rules.current.backpacks||p.isSpectator()||!p.isAlive()||p.containerMenu!=p.inventoryMenu)return;
        StorageData data=StorageData.get(p.getServer());StorageData.Prefs prefs=data.prefs(p.getUUID());Set<UUID> handled=new HashSet<>();
        // Each identity is processed once, even when a creative clone exists in another slot.
        for(int bagSlot=0;bagSlot<36;bagSlot++){
            ItemStack item=p.getInventory().getItem(bagSlot);if(!(item.getItem() instanceof BackpackItem))continue;
            StorageData.Bag bag=data.bag(item);UUID id=StorageData.bagId(item);if(!handled.add(id))continue;
            if(prefs.autoStow&&bag.hasUpgrade("pickup")){
                StorageOps.Plan plan=new StorageOps.Plan();int moved=0;
                for(int slot=0;slot<36;slot++){ItemStack s=p.getInventory().getItem(slot);if(slot==bagSlot||s.isEmpty()||prefs.locked.get(slot)||!StorageInventory.safeInBackpack(s))continue;
                    if(!bag.filters.isEmpty()?bag.filters.contains(StorageOps.itemId(s)):plan.originallyContains(bag.inventory,s))moved+=plan.move(p.getInventory(),slot,bag.inventory,s.getCount());
                }if(moved>0)plan.commit();
            }
            if(prefs.autoRefill&&bag.hasUpgrade("refill")){
                StorageOps.Plan plan=new StorageOps.Plan();int moved=0;
                for(int target=0;target<9;target++){
                    ItemStack wanted=p.getInventory().getItem(target);if(wanted.isEmpty()||wanted.getCount()>=wanted.getMaxStackSize()||wanted.getItem() instanceof BackpackItem)continue;
                    if(!bag.filters.isEmpty()&&!bag.filters.contains(StorageOps.itemId(wanted)))continue;
                    // Refilling targets only an already-occupied hotbar stack; it never replaces a tool or empties a protected slot.
                    for(int source=0;source<bag.inventory.getContainerSize();source++)if(StorageOps.identity(bag.inventory.getItem(source),wanted)){
                        int need=wanted.getMaxStackSize()-wanted.getCount();
                        ItemStack from=bag.inventory.getItem(source);int n=Math.min(need,from.getCount());
                        if(n>0){ItemStack oldTarget=wanted.copy(),oldSource=from.copy();try{wanted.grow(n);from.shrink(n);p.getInventory().setItem(target,wanted);bag.inventory.setItem(source,from);moved+=n;}catch(RuntimeException ex){p.getInventory().setItem(target,oldTarget);bag.inventory.setItem(source,oldSource);throw ex;}}
                        if(wanted.getCount()>=wanted.getMaxStackSize())break;
                    }
                }if(moved>0)p.getInventory().setChanged();
            }
            if(prefs.autoFeed&&bag.hasUpgrade("feeding")&&p.getFoodData().getFoodLevel()<=16){
                for(int slot=0;slot<bag.inventory.getContainerSize();slot++){
                    ItemStack food=bag.inventory.getItem(slot);if(food.isEmpty()||!foods().contains(food.getItem())||!food.getComponentsPatch().isEmpty()||!bag.filters.isEmpty()&&!bag.filters.contains(StorageOps.itemId(food)))continue;
                    ItemStack one=food.copyWithCount(1);food.shrink(1);bag.inventory.setChanged();
                    ItemStack remainder=p.eat(p.serverLevel(),one);
                    if(!remainder.isEmpty()&&!p.getInventory().add(remainder))p.drop(remainder,false);break;
                }
            }
            if(prefs.autoCompact&&bag.hasUpgrade("compacting"))compact(bag);
        }
    }
    private static void compact(StorageData.Bag bag){
        Map<Item,Item> pairs=Map.of(Items.IRON_NUGGET,Items.IRON_INGOT,Items.GOLD_NUGGET,Items.GOLD_INGOT,Items.IRON_INGOT,Items.IRON_BLOCK,Items.GOLD_INGOT,Items.GOLD_BLOCK,Items.DIAMOND,Items.DIAMOND_BLOCK,Items.EMERALD,Items.EMERALD_BLOCK,Items.COAL,Items.COAL_BLOCK,Items.REDSTONE,Items.REDSTONE_BLOCK,Items.LAPIS_LAZULI,Items.LAPIS_BLOCK);
        // Only explicit vanilla 9-to-1 materials, no mod crafting recursion or custom-component inputs.
        for(int slot=0;slot<bag.inventory.getContainerSize();slot++){
            ItemStack in=bag.inventory.getItem(slot);Item result=pairs.get(in.getItem());
            if(result==null||in.getCount()<9||!in.getComponentsPatch().isEmpty()||!bag.filters.isEmpty()&&!bag.filters.contains(StorageOps.itemId(in)))continue;
            ItemStack out=new ItemStack(result);int destination=-1;
            for(int i=0;i<bag.inventory.getContainerSize();i++){
                ItemStack old=bag.inventory.getItem(i);if(i==slot&&in.getCount()==9||old.isEmpty()||StorageOps.identity(old,out)&&old.getCount()<old.getMaxStackSize()){destination=i;break;}
            }
            if(destination<0)return;
            ItemStack before=in.copy(),old=bag.inventory.getItem(destination).copy();
            try{
                in.shrink(9);bag.inventory.setItem(slot,in);
                if(destination==slot)bag.inventory.setItem(slot,out);else{ItemStack next=old.copy();if(next.isEmpty())next=out;else next.grow(1);bag.inventory.setItem(destination,next);}
            }catch(RuntimeException ex){bag.inventory.setItem(slot,before);if(destination!=slot)bag.inventory.setItem(destination,old);throw ex;}
            return; // Bounded to one compression per backpack per second.
        }
    }
    public static void dragonDeath(LivingDeathEvent event){
        if(event.getEntity() instanceof EnderDragon dragon&&!dragon.level().isClientSide&&dragon.level().dimension()==Level.END&&dragon.getDragonFight()!=null){
            StorageData data=StorageData.get(dragon.getServer());if(data.rewardedDragons.add(dragon.getUUID())){data.setDirty();dragon.spawnAtLocation(AnyaStorage.TOKEN.get());}
        }
    }
}
