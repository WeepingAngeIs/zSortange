package dev.anya.smelterreview;

import dev.anya.storage.BagInventory;
import dev.anya.storage.Content;
import dev.anya.storage.Config;
import dev.anya.storage.Packets;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.Transactions;
import dev.anya.storage.Upgrades;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.fml.loading.FMLPaths;
import static dev.anya.smelterreview.SmelterGameTests.*;

/** Exercises registered recipes and normal carried-backpack passes on the hosted server. */
final class SpecialistChecks {
    private record Family(String id,String prefix,Item appliance,Item part,Item input,Item output,
                          Item otherInput,Item otherOutput,Item bucketOutput,float xp){
        String tier(boolean manual){return (manual?"manual_":"auto_")+id;}
        void allow(BagInventory bag,Item item){Upgrades.filter(bag,prefix+"Filter",BuiltInRegistries.ITEM.getKey(item).toString());}
        boolean pending(ServerPlayer p){return Upgrades.cookPending(bag(p),id);}
        int heat(ServerPlayer p){return Upgrades.fuelTicks(bag(p),id);}
        int ticks(ServerPlayer p){return data(p).getInt(prefix+"Ticks");}
        float credit(ServerPlayer p){return data(p).getFloat(prefix+"XP");}
        void startOne(ServerPlayer p){
            Upgrades.startCook(bag(p),id);
            check(pending(p),"Start did not arm "+id);
        }
        boolean matches(ServerPlayer p,Item item){
            var input=new SingleRecipeInput(new ItemStack(item));var level=p.serverLevel();
            return id.equals("smoking")?level.getRecipeManager().getRecipeFor(RecipeType.SMOKING,input,level).isPresent()
                    :level.getRecipeManager().getRecipeFor(RecipeType.BLASTING,input,level).isPresent();
        }
    }
    private static final Family SMOKE=new Family("smoking","Smoke",Items.SMOKER,Items.COPPER_GRATE,Items.BEEF,
            Items.COOKED_BEEF,Items.PORKCHOP,Items.COOKED_PORKCHOP,Items.CLAY_BALL,.35f);
    private static final Family BLAST=new Family("blasting","Blast",Items.BLAST_FURNACE,Items.OBSIDIAN,Items.RAW_IRON,
            Items.IRON_INGOT,Items.RAW_COPPER,Items.COPPER_INGOT,Items.BRICK,.7f);
    private static final List<Family> FAMILIES=List.of(SMOKE,BLAST);
    private static String key(int variant){return "combined_"+variant;}

    /** Exercise the installed server's loader against old-format and partially updated disposable files. */
    static void configCompatibility()throws Exception{
        Path file=FMLPaths.CONFIGDIR.get().resolve("anya-storage-plus.json");
        byte[] original=Files.readAllBytes(file);
        try{
            var settings=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var gates=settings.getAsJsonObject("upgrades");
            for(boolean enabled:List.of(false,true)){
                gates.addProperty("smelting",enabled);gates.remove("smoking");gates.remove("blasting");
                String legacy=settings.toString();Files.writeString(file,legacy);Config.init();
                check(Config.get().upgrades.get("smoking")==enabled && Config.get().upgrades.get("blasting")==enabled &&
                        Files.readString(file).equals(legacy),"Loading the old furnace gate must inherit its value without rewriting the file");
            }
            gates.addProperty("smelting",false);gates.addProperty("smoking",true);gates.remove("blasting");
            String partial=settings.toString();Files.writeString(file,partial);Config.init();
            check(Config.get().upgrades.get("smoking") && !Config.get().upgrades.get("blasting")
                    && !Config.get().upgrades.get("smelting") && Files.readString(file).equals(partial),"Explicit independent gate did not override inheritance");
        }finally{Files.write(file,original);Config.init();}
    }

    static void run(ServerPlayer p){
        var sand=new SingleRecipeInput(new ItemStack(Items.SAND));
        var furnace=p.serverLevel().getRecipeManager().getRecipeFor(RecipeType.SMELTING,sand,p.level()).orElseThrow();
        check(Transactions.same(furnace.value().assemble(sand,p.registryAccess()),new ItemStack(Items.GLASS)),
                "Sand must match the running furnace registry and produce exactly one glass");
        for(Family f:FAMILIES){
            var peer=f==SMOKE?BLAST:SMOKE;
            check(f.matches(p,f.input) && f.matches(p,f.otherInput) && !f.matches(p,peer.input) && !f.matches(p,Items.SAND),
                    "Unexpected native recipe eligibility in "+f.id);
            checkRecipe(p,f.tier(true),3,2,List.of(Items.COPPER_INGOT,f.appliance,Items.COPPER_INGOT,Items.AIR,Items.REDSTONE_TORCH,Items.AIR));
            checkRecipe(p,f.tier(false),3,1,List.of(f.part,disc(f.tier(true)).getItem(),Items.CLOCK));
            check(!autoRecipeMatches(p,f,disc(peer.tier(true)).getItem(),f.part) &&
                    !autoRecipeMatches(p,f,disc(f.tier(true)).getItem(),peer.part),"Mixed specialist progression crafted "+f.id);
            for(boolean manual:List.of(true,false)){
                orderedCooking(p,f,peer,manual);
                pairConflict(p,f,manual);
            }
            bucketAndStall(p,f);
            menuAndGate(p,f,peer);
        }
        sharedSupplies(p);
    }

    private static boolean autoRecipeMatches(ServerPlayer p,Family f,Item module,Item part){
        var grid=CraftingInput.of(3,1,List.of(new ItemStack(module),new ItemStack(Items.CLOCK),new ItemStack(part)));
        var level=p.serverLevel();
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,grid,level)
                .map(found->found.value().assemble(grid,p.registryAccess()).is(disc(f.tier(false)).getItem())).orElse(false);
    }
    private static ItemStack nextBag(ServerPlayer p,ItemStack bag,Item material){
        List<ItemStack> cells=new ArrayList<>();
        for(int i=0;i<9;i++)cells.add(i==4?bag:new ItemStack(material));
        var grid=CraftingInput.of(3,3,cells);var level=p.serverLevel();
        var found=level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,grid,level).orElseThrow();
        var result=found.value().assemble(grid,p.registryAccess());
        check(result.is(Content.BACKPACK.get()),"Expected backpack progression");return result;
    }
    private static ItemStack roomy(ServerPlayer p){
        return nextBag(p,nextBag(p,new ItemStack(Content.BACKPACK.get()),Items.IRON_INGOT),Items.GOLD_INGOT);
    }
    private static void attach(ServerPlayer p,String id){
        p.getInventory().setItem(9,named(disc(id),"Workshop "+id));
        check(Upgrades.change(bag(p),id,false) && p.getInventory().getItem(9).isEmpty(),"Exact disc not installed: "+id);
    }
    private static void pairConflict(ServerPlayer p,Family f,boolean manual){
        var bag=install(p,f.tier(manual),roomy(p));
        String other=f.tier(!manual);
        var spare=named(disc(other),"Do not consume");
        p.getInventory().setItem(12,spare.copy());
        check(!Upgrades.change(bag,other,false) && Transactions.same(p.getInventory().getItem(12),spare)
                && Upgrades.installedItems(bag.stack).size()==1,"Two tiers of "+f.id+" fit in the same roomy bag");
        check(Upgrades.change(bag,f.tier(manual),true) && returned(p,named(disc(f.tier(manual)),"Workshop "+f.tier(manual))),
                "Removing "+f.tier(manual)+" did not return the exact installed disc");
    }
    private static void orderedCooking(ServerPlayer p,Family f,Family peer,boolean manual){
        var bag=install(p,f.tier(manual));int xp=p.totalExperience;
        f.allow(bag,Items.SAND);f.allow(bag,peer.input);
        bag.setItem(0,new ItemStack(Items.SAND,2));bag.setItem(1,new ItemStack(peer.input,2));
        bag.setItem(2,new ItemStack(f.otherInput,2));bag.setItem(3,new ItemStack(f.input,2));
        bag.setItem(4,new ItemStack(Items.COAL,2));
        Upgrades.startCook(bag,f.id);carry(p,10);
        check(!f.pending(p) && bag(p).countItem(Items.COAL)==2 && f.heat(p)==0 && f.ticks(p)==0,
                f.id+" accepted an ordinary furnace or another specialist's recipe");
        f.allow(bag(p),f.input);f.allow(bag(p),f.otherInput);
        if(manual){
            carry(p,6);check(bag(p).countItem(Items.COAL)==2,"Unarmed manual "+f.id+" ran");
            f.startOne(p);
        }
        carry(p,4);
        check(f.ticks(p)==80 && f.heat(p)==720 && bag(p).countItem(f.output)==0 && p.totalExperience==xp,
                f.id+" must use four twenty-tick passes and no XP before completion");
        carry(p,1);
        check(f.heat(p)==700 && bag(p).countItem(f.output)==1 && bag(p).countItem(f.otherOutput)==0,
                f.id+" did not prioritize the first eligible rule over the earlier bag slot");
        near(f.credit(p),f.xp,"Fractional credit "+f.id);
        if(manual){
            var completed=bag(p).stack.copy();carry(p,7);
            check(!f.pending(p)&&Transactions.same(completed,bag(p).stack),f.id+" repeated without Start");f.startOne(p);
        }
        carry(p,5);if(manual)f.startOne(p);carry(p,5);
        check(bag(p).countItem(f.output)==2 && bag(p).countItem(f.otherOutput)==1 && bag(p).countItem(Items.SAND)==2 &&
                bag(p).countItem(peer.input)==2 && bag(p).countItem(Items.COAL)==1 && f.heat(p)==500,
                f.id+" failed to move to the second matching rule, or consumed an ineligible item");
        int paid=(int)(3*f.xp);
        check(p.totalExperience==xp+paid,"Wrong whole "+f.id+" XP");
        near(f.credit(p),3*f.xp-paid,"Remaining "+f.id+" XP");
    }
    private static void bucketAndStall(ServerPlayer p,Family f){
        var bag=install(p,f.tier(true));f.allow(bag,Items.WATER_BUCKET);
        bag.setItem(0,new ItemStack(Items.WATER_BUCKET));bag.setItem(1,named(new ItemStack(Items.CHARCOAL,2),"Spare fuel"));
        int xp=p.totalExperience;f.startOne(p);carry(p,6);
        check(f.pending(p)&&f.heat(p)==0 && f.ticks(p)==0 && bag(p).countItem(Items.WATER_BUCKET)==1,
                f.id+" used named charcoal or cooked without fuel");
        bag=bag(p);bag.setItem(1,new ItemStack(Items.CHARCOAL,2));
        for(int i=2;i<bag.getContainerSize();i++)bag.setItem(i,new ItemStack(Items.COBBLESTONE,64));
        var full=bag.stack.copy();carry(p,10);
        check(Transactions.same(full,bag(p).stack) && p.totalExperience==xp,f.id+" lost fuel/input/return when only one output fit");
        bag(p).setItem(2,ItemStack.EMPTY);carry(p,6);
        check(!f.pending(p) && bag(p).countItem(f.bucketOutput)==1 && bag(p).countItem(Items.BUCKET)==1 &&
                bag(p).countItem(Items.WATER_BUCKET)==0 && bag(p).countItem(Items.SNOWBALL)==0 &&
                bag(p).countItem(Items.CHARCOAL)==1 && f.heat(p)==760 && p.totalExperience==xp+1,
                "Incorrect specialist recipe, missing remainder or incorrect fuel/XP in "+f.id);
    }

    private static StoreMenu upgrades(ServerPlayer p){
        if(p.containerMenu!=p.inventoryMenu)p.closeContainer();
        StoreMenu.bag(p,bag(p),true);
        return (StoreMenu)p.containerMenu;
    }
    private static void sharedSupplies(ServerPlayer p){
        install(p,SMOKE.tier(false),roomy(p));attach(p,BLAST.tier(false));
        var bag=bag(p);SMOKE.allow(bag,Items.WATER_BUCKET);BLAST.allow(bag,Items.WATER_BUCKET);
        bag.setItem(0,new ItemStack(Items.WATER_BUCKET));bag.setItem(1,new ItemStack(Items.CHARCOAL));
        int xp=p.totalExperience;carry(p,5);
        check(bag(p).countItem(Items.CHARCOAL)==0 && bag(p).countItem(Items.WATER_BUCKET)==0 &&
                bag(p).countItem(Items.CLAY_BALL)==1 && bag(p).countItem(Items.BRICK)==0 && bag(p).countItem(Items.BUCKET)==1 &&
                SMOKE.heat(p)==760 && BLAST.heat(p)==0 && p.totalExperience==xp+1,
                "Two eligible machines sharing the last fuel and input failed to conserve both items, output, remainder or XP");

        install(p,SMOKE.tier(false),roomy(p));attach(p,BLAST.tier(false));bag=bag(p);
        SMOKE.allow(bag,Items.WATER_BUCKET);BLAST.allow(bag,Items.WATER_BUCKET);
        bag.setItem(0,new ItemStack(Items.WATER_BUCKET));bag.setItem(1,new ItemStack(Items.COAL,2));
        xp=p.totalExperience;carry(p,1);
        check(SMOKE.ticks(p)==20 && BLAST.ticks(p)==20 && SMOKE.heat(p)==780 && BLAST.heat(p)==780 &&
                bag(p).countItem(Items.COAL)==0 && bag(p).countItem(Items.WATER_BUCKET)==1 && p.totalExperience==xp,
                "Both 40-tick recipes must independently pay for their first 20 ticks on the same one remaining input");
        carry(p,1);
        check(bag(p).countItem(Items.WATER_BUCKET)==0 && bag(p).countItem(Items.CLAY_BALL)==1 &&
                bag(p).countItem(Items.BUCKET)==1 && bag(p).countItem(Items.BRICK)==0 && bag(p).countItem(Items.SNOWBALL)==0 &&
                SMOKE.heat(p)==760 && BLAST.heat(p)==780 && p.totalExperience==xp+1 &&
                SMOKE.credit(p)==0 && BLAST.credit(p)==0,
                "Competing completions must pay exactly one output, one bucket and one XP; the losing blaster keeps all its remaining heat");
    }
    private static void menuAndGate(ServerPlayer host,Family f,Family peer){
        var p=menuTester(host.serverLevel(),"SpecialistMenu");
        var bag=install(p,f.tier(true),roomy(p));attach(p,peer.tier(false));bag=bag(p);
        f.allow(bag,f.input);peer.allow(bag,peer.input);
        bag.setItem(0,new ItemStack(f.input,2));bag.setItem(1,new ItemStack(peer.input,2));bag.setItem(2,new ItemStack(Items.COAL,4));
        int startSlot=f==SMOKE?34:43,stopSlot=startSlot+1;
        var retired=upgrades(p);var current=upgrades(p);
        check(current.containerId==retired.containerId && current.session()!=retired.session(),"Expected a fresh specialist menu session");
        current.action(new Packets.Action(current.containerId,retired.session(),current.revision(),"slot","",startSlot));
        check(!f.pending(p),"Stale menu queued "+f.id);
        current.action(new Packets.Action(current.containerId,current.session(),current.revision(),"slot","",startSlot));
        String queued=f==SMOKE?"One smoker cook queued":"One blast furnace cook queued";
        check(f.pending(p)&&current.getSlot(startSlot).getItem().getHoverName().getString().equals(queued),"Valid specialist Start control");
        p.closeContainer();
        boolean previous=Config.get().upgrades.put(f.id,false);
        try{
            carry(p,5);
            check(f.pending(p) && f.ticks(p)==0 && f.heat(p)==0 && bag(p).countItem(f.output)==0 &&
                    bag(p).countItem(peer.output)==1 && peer.heat(p)==700,"Disabling "+f.id+" affected the other family or advanced disabled work");
        }finally{Config.get().upgrades.put(f.id,previous);}
        carry(p,1);check(f.ticks(p)==20 && f.heat(p)==780,"Re-enabled "+f.id+" failed to resume");
        var before=data(p);current=upgrades(p);
        current.action(new Packets.Action(current.containerId,current.session(),current.revision(),"slot","",stopSlot));
        check(!f.pending(p) && data(p).getList(f.prefix+"Filter",Tag.TAG_STRING).isEmpty() &&
                f.heat(p)==780 && before.get(peer.prefix+"Filter").equals(data(p).get(peer.prefix+"Filter")) &&
                before.getInt(peer.prefix+"FuelTicks")==peer.heat(p),"Stop control changed independent family state");
        p.closeContainer();
        f.allow(bag(p),f.input);f.startOne(p);carry(p,1);
        int paidHeat=f.heat(p);
        check(Upgrades.change(bag(p),f.tier(true),true) && returned(p,named(disc(f.tier(true)),"Workshop "+f.tier(true))),
                "Active "+f.id+" did not return its original disc");
        check(Upgrades.change(bag(p),f.tier(true),false),"Failed to reinstall "+f.id);
        carry(p,6);
        check(!f.pending(p) && f.heat(p)==paidHeat && bag(p).countItem(f.output)==0,"Removing and reinstalling revived an old specialist job");
    }

    static void seed(ServerPlayer p,ChestBlockEntity chest,CompoundTag oracle,int variant){
        boolean manualSmoke=variant==0;
        install(p,AUTO,roomy(p));attach(p,SMOKE.tier(manualSmoke));attach(p,BLAST.tier(!manualSmoke));
        var bag=bag(p);
        check(Upgrades.installedItems(bag.stack).size()==3,"All three families must coexist");
        bag.setItem(0,new ItemStack(Items.SAND,4));bag.setItem(1,new ItemStack(Items.BEEF,5));
        bag.setItem(2,new ItemStack(Items.RAW_IRON,5));bag.setItem(3,new ItemStack(Items.COAL,6));
        filter(bag,"sand");carry(p,3);
        SMOKE.allow(bag(p),Items.BEEF);if(manualSmoke)SMOKE.startOne(p);carry(p,2);
        BLAST.allow(bag(p),Items.RAW_IRON);if(!manualSmoke)BLAST.startOne(p);carry(p,3);
        if(manualSmoke)SMOKE.startOne(p);carry(p,1);
        check(data(p).getInt("SmeltTicks")==180 && data(p).getInt("FuelTicks")==1420 &&
                SMOKE.ticks(p)==20 && SMOKE.heat(p)==680 && BLAST.ticks(p)==80 && BLAST.heat(p)==720 &&
                bag(p).countItem(Items.COOKED_BEEF)==1 && bag(p).countItem(Items.COAL)==3,"Incorrect staggered checkpoint for bag "+variant);
        near(SMOKE.credit(p),.35f,"One completed smoker job before restart");
        check(data(p).getFloat("SmeltXP")==0 && BLAST.credit(p)==0 && SMOKE.pending(p)==manualSmoke && BLAST.pending(p)!=manualSmoke,
                "Cook state leaked between families before restart");
        var live=p.getInventory().removeItemNoUpdate(0);
        oracle.put(key(variant),live.save(p.registryAccess()));chest.setItem(TIERS.size()+variant,live);
    }

    static void resume(ServerPlayer p,ChestBlockEntity chest,CompoundTag oracle,int variant){
        p.getInventory().clearContent();p.getInventory().selected=0;
        boolean manualSmoke=variant==0;
        var live=chest.removeItemNoUpdate(TIERS.size()+variant);
        check(Transactions.same(live,ItemStack.parseOptional(p.registryAccess(),oracle.getCompound(key(variant)))),
                "Real three-family saved stack differs from its pre-restart component snapshot");
        p.getInventory().setItem(0,live);
        int xp=p.totalExperience;carry(p,1);
        check(bag(p).countItem(Items.GLASS)==1 && bag(p).countItem(Items.IRON_INGOT)==1 && bag(p).countItem(Items.COOKED_BEEF)==1 &&
                data(p).getInt("FuelTicks")==1400 && SMOKE.heat(p)==660 && BLAST.heat(p)==700 && p.totalExperience==xp,
                "The first restarted pass did not finish the exact furnace and blaster jobs independently");
        near(data(p).getFloat("SmeltXP"),.1f,"Furnace XP");near(SMOKE.credit(p),.35f,"Smoker XP");near(BLAST.credit(p),.7f,"Blaster XP");
        carry(p,3);
        check(bag(p).countItem(Items.COOKED_BEEF)==2 && !SMOKE.pending(p) && !BLAST.pending(p) && p.totalExperience==xp,
                "Mid-cook smoky progress did not resume, or XP banks pooled between families");
        carry(p,5);
        check(bag(p).countItem(Items.COOKED_BEEF)==(manualSmoke?2:3) && bag(p).countItem(Items.IRON_INGOT)==(manualSmoke?2:1) &&
                bag(p).countItem(Items.COAL)==3 && p.totalExperience==xp+1,"Restarted specialists repeated a manual job or stopped an automatic one");
        Family manual=manualSmoke?SMOKE:BLAST;
        manual.startOne(p);carry(p,1);
        int heat=manual.heat(p);float bank=manual.credit(p);
        var otherState=data(p);Upgrades.stopCook(bag(p),manual.id);
        var stopped=data(p);
        for(String key:otherState.getAllKeys())if(!key.startsWith(manual.prefix))
            check(otherState.get(key).equals(stopped.get(key)),"Stopping "+manual.id+" changed "+key);
        check(!manual.pending(p) && stopped.getList(manual.prefix+"Filter",Tag.TAG_STRING).isEmpty() &&
                manual.heat(p)==heat && manual.credit(p)==bank,"Stop did not preserve its bank and heat or clear only its scheduled job/filters");
        chest.setItem(TIERS.size()+variant,p.getInventory().removeItemNoUpdate(0));chest.setChanged();
    }
    private SpecialistChecks(){}
}
