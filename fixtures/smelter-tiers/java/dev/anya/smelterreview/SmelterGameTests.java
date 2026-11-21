package dev.anya.smelterreview;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.BagInventory;
import dev.anya.storage.Config;
import dev.anya.storage.Content;
import dev.anya.storage.Packets;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.Transactions;
import dev.anya.storage.Upgrades;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("anyasmelterreview")
@PrefixGameTestTemplate(false)
public final class SmelterGameTests {
    static final String MANUAL="manual_smelting", AUTO="auto_smelting", LEGACY="smelting";
    static final List<String> TIERS=List.of(MANUAL,AUTO,LEGACY);

    static void check(boolean ok,String description) {
        if(!ok)throw new AssertionError(description);
    }

    @GameTest(template="empty",templateNamespace="anyastorage")
    public static void smeltingTransactions(GameTestHelper helper) {
        run(new FakePlayer(helper.getLevel(),new GameProfile(UUID.randomUUID(),"SmelterTester")));
        helper.succeed();
    }

    static void run(ServerPlayer p) {
        recipes(p);
        gatesAndOwnership(p);
        for(String tier:TIERS)orderAndTiming(p,tier);
        manualCancellation(p);
        stalls(p);
        menuStart(p);
        fixtureRecipes(p);
    }

    static BagInventory bag(ServerPlayer p){return new BagInventory(p,0);}
    static CompoundTag data(ServerPlayer p){return BagInventory.data(p.getInventory().getItem(0));}
    static ItemStack disc(String id){return new ItemStack(Content.UPGRADES.get(id).get());}
    static ItemStack named(ItemStack stack,String name){
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(name));return stack;
    }
    static BagInventory install(ServerPlayer p,String tier) {
        return install(p,tier,new ItemStack(Content.BACKPACK.get()));
    }
    private static BagInventory install(ServerPlayer p,String tier,ItemStack host) {
        var inventory=p.getInventory();
        inventory.clearContent();inventory.selected=0;
        inventory.setItem(0,host);
        inventory.setItem(9,named(disc(tier),"Workshop "+tier));
        check(Upgrades.change(bag(p),tier,false),"Could not install "+tier);
        check(inventory.getItem(9).isEmpty() && Upgrades.installed(bag(p),tier),"Installation consumed exactly one disc");
        return bag(p); // Transactions replace the held item. Never keep the old bag reference.
    }
    static void filter(BagInventory bag,String id){Upgrades.filter(bag,"SmeltFilter","minecraft:"+id);}
    static void carry(ServerPlayer p,int seconds) {
        for(int i=0;i<seconds;i++){
            p.tickCount=(p.tickCount/20+1)*20;
            Upgrades.tick(new PlayerTickEvent.Post(p));
        }
    }
    static void start(ServerPlayer p){
        String result=Upgrades.startSmelt(bag(p));
        check(Upgrades.manualSmeltPending(bag(p)),"Manual start did not arm: "+result);
    }
    static boolean returned(ServerPlayer p,ItemStack exact) {
        for(int i=0;i<36;i++)if(Transactions.same(p.getInventory().getItem(i),exact))return true;
        return false;
    }
    static void near(float actual,float expected,String message) {
        check(Math.abs(actual-expected)<.0001f,message+" expected "+expected+" got "+actual);
    }

    private static void gatesAndOwnership(ServerPlayer p){
        for(String tier:TIERS){
            List<ItemStack> grid=new ArrayList<>();
            for(int slot=0;slot<9;slot++)grid.add(slot==4?new ItemStack(Content.BACKPACK.get()):new ItemStack(Items.IRON_INGOT));
            var input=CraftingInput.of(3,3,grid);
            var recipe=p.serverLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,p.level()).orElseThrow();
            var bag=install(p,tier,recipe.value().assemble(input,p.registryAccess()));
            check(BagInventory.data(bag.stack).getInt("UpgradeSlots")>1,"Family conflict test needs spare upgrade capacity");
            for(String other:TIERS){
                p.getInventory().setItem(12,disc(other));
                check(!Upgrades.change(bag,other,false) && Transactions.same(p.getInventory().getItem(12),disc(other)),
                        "Installing "+other+" over "+tier+" must preserve both discs");
            }
            p.getInventory().setItem(12,ItemStack.EMPTY);
            check(Upgrades.change(bag,tier,true) && returned(p,named(disc(tier),"Workshop "+tier)),
                    "Removing "+tier+" must return the exact named disc");
        }
        var bag=install(p,MANUAL);
        filter(bag,"raw_iron");bag.setItem(0,new ItemStack(Items.RAW_IRON,2));bag.setItem(1,new ItemStack(Items.COAL,2));
        boolean previous=Config.get().upgrades.put(LEGACY,false);
        try {
            check(!Upgrades.active(bag,MANUAL),"Smelting family gate must disable the installed Manual module");
            Upgrades.startSmelt(bag);carry(p,12);
            check(!Upgrades.manualSmeltPending(bag) && bag(p).countItem(Items.COAL)==2,"Disabled job consumed fuel or armed");
            check(Upgrades.change(bag,MANUAL,true) && returned(p,named(disc(MANUAL),"Workshop "+MANUAL)),
                    "Disabled modules remain recoverable");
            for(String tier:TIERS){
                p.getInventory().setItem(12,disc(tier));
                check(!Upgrades.change(bag(p),tier,false) && Transactions.same(p.getInventory().getItem(12),disc(tier)),
                        "Disabled "+tier+" installed or consumed its disc");
            }
        } finally {Config.get().upgrades.put(LEGACY,previous);}
        bag=install(p,MANUAL);filter(bag,"raw_iron");
        bag.setItem(0,new ItemStack(Items.RAW_IRON));bag.setItem(1,new ItemStack(Items.COAL));
        var original=bag.stack;
        p.getInventory().setItem(0,ItemStack.EMPTY);
        Upgrades.startSmelt(bag);Upgrades.smeltingStep(bag);
        check(!BagInventory.data(original).contains("SmeltInput"),"Detached backpack acquired a job");
        p.getInventory().setItem(0,original);p.getInventory().setItem(20,original.copy());
        var snapshot=original.copy();
        Upgrades.startSmelt(bag);carry(p,12);
        check(Transactions.same(original,snapshot)&&Transactions.same(p.getInventory().getItem(20),snapshot),
                "Duplicate identities changed inputs, fuel or job state");
        p.getInventory().setItem(20,ItemStack.EMPTY);
    }

    private static void orderAndTiming(ServerPlayer p,String tier) {
        var bag=install(p,tier);
        bag.setItem(0,new ItemStack(Items.RAW_COPPER,2));
        bag.setItem(1,new ItemStack(Items.RAW_IRON,2));
        bag.setItem(2,new ItemStack(Items.RAW_GOLD,2)); // Never allowlisted.
        bag.setItem(3,new ItemStack(Items.COAL,2));
        carry(p,12);
        check(bag(p).countItem(Items.COAL)==2,"No rule must mean no smelting");
        filter(bag(p),"raw_iron");filter(bag(p),"raw_copper");
        boolean manual=tier.equals(MANUAL);
        if(manual){
            carry(p,12);check(bag(p).countItem(Items.COAL)==2,"Manual tier ran without Start");start(p);
        }
        p.containerMenu=ChestMenu.threeRows(1,p.getInventory());
        try {carry(p,12);check(bag(p).countItem(Items.COAL)==2,"Carried cooking must pause in an open menu");}
        finally {p.containerMenu=p.inventoryMenu;}
        int xp=p.totalExperience;
        carry(p,9);
        check(data(p).getInt("SmeltTicks")==180 && bag(p).countItem(Items.COAL)==1 && p.totalExperience==xp,
                "After 180 ticks "+tier+" must have one coal paid and no XP");
        carry(p,1);
        Item preferred=tier.equals(LEGACY)?Items.COPPER_INGOT:Items.IRON_INGOT;
        check(bag(p).countItem(preferred)==1 && bag(p).countItem(Items.RAW_GOLD)==2,
                tier+" selected the wrong first recipe or consumed unlisted gold");
        near(data(p).getFloat("SmeltXP"),.7f,"First ore fractional XP");
        if(manual) {
            var completed=p.getInventory().getItem(0).copy();
            carry(p,30);
            check(!Upgrades.manualSmeltPending(bag(p)) && Transactions.same(bag(p).stack,completed),
                    "Manual repeated or spent fuel without a fresh Start");
            start(p);
        }
        carry(p,10);
        check(bag(p).countItem(preferred)==2 && bag(p).countItem(Items.COAL)==1 && p.totalExperience==xp+1,
                tier+" must cook the second ore only once using stored heat and pay one XP");
        near(data(p).getFloat("SmeltXP"),.4f,"After two ore recipes");
        if(manual)start(p);
        carry(p,10);
        Item next=tier.equals(LEGACY)?Items.IRON_INGOT:Items.COPPER_INGOT;
        check(bag(p).countItem(next)==1 && bag(p).countItem(Items.RAW_GOLD)==2,
                tier+" did not move to the next eligible ore after the preferred input ran out");
    }

    private static void manualCancellation(ServerPlayer p) {
        var bag=install(p,MANUAL);filter(bag,"raw_iron");
        var ore=named(new ItemStack(Items.RAW_IRON,2),"Selected vein");
        bag.setItem(0,ore.copy());bag.setItem(1,new ItemStack(Items.COAL,2));bag.setItem(2,ore.copy());
        start(p);carry(p,3);int heat=data(p).getInt("FuelTicks");
        check(data(p).getInt("SmeltTicks")==60,"Manual partial job");
        Upgrades.startSmelt(bag(p));
        check(data(p).getInt("SmeltTicks")==60 && data(p).getInt("FuelTicks")==heat,"Second Start reset an armed job");
        bag(p).setItem(0,named(ore.copy(),"Other vein"));
        carry(p,12);
        check(!Upgrades.manualSmeltPending(bag(p)) && bag(p).countItem(Items.IRON_INGOT)==0 &&
                data(p).getInt("FuelTicks")==heat,"Replaced components reused heat/progress or borrowed the other slot");
        bag(p).setItem(0,ore.copy());carry(p,12);
        check(bag(p).countItem(Items.RAW_IRON)==4 && bag(p).countItem(Items.IRON_INGOT)==0,"Restoring ore silently rearmed the job");
        start(p);carry(p,2);
        float earned=data(p).getFloat("SmeltXP");
        check(Upgrades.change(bag(p),MANUAL,true),"Remove active manual disc");
        check(returned(p,named(disc(MANUAL),"Workshop "+MANUAL)),"Exact disc not recovered");
        check(Upgrades.change(bag(p),MANUAL,false),"Reinstall named manual disc");
        carry(p,12);
        check(!Upgrades.manualSmeltPending(bag(p)) && bag(p).countItem(Items.RAW_IRON)==4 && bag(p).countItem(Items.IRON_INGOT)==0,
                "Removing/reinstalling restored the old job");
        near(data(p).getFloat("SmeltXP"),earned,"Uncompleted jobs awarded XP");
        start(p);carry(p,1);filter(bag(p),"raw_iron");carry(p,12);
        check(!Upgrades.manualSmeltPending(bag(p)) && bag(p).countItem(Items.RAW_IRON)==4,"Removed allowlist rule left a cook queued");
        filter(bag(p),"raw_iron");start(p);Upgrades.stopSmelt(bag(p));carry(p,12);
        check(!Upgrades.manualSmeltPending(bag(p)) && bag(p).countItem(Items.RAW_IRON)==4
                && data(p).getList("SmeltFilter",net.minecraft.nbt.Tag.TAG_STRING).isEmpty(),"Stop must clear input rules and the queued cook");
    }

    private static void stalls(ServerPlayer p){
        var bag=install(p,MANUAL);filter(bag,"raw_iron");
        bag.setItem(0,new ItemStack(Items.RAW_IRON,2));
        var keepsake=named(new ItemStack(Items.COAL,2),"Keepsake");
        bag.setItem(1,keepsake.copy());int xp=p.totalExperience;
        start(p);carry(p,15);
        check(Upgrades.manualSmeltPending(bag(p)) && Transactions.same(bag(p).getItem(1),keepsake)
                && data(p).getInt("SmeltTicks")==0 && bag(p).countItem(Items.RAW_IRON)==2,"No valid fuel did not stall safely");
        bag=bag(p);bag.setItem(2,new ItemStack(Items.CHARCOAL,2));carry(p,4);
        check(data(p).getInt("SmeltTicks")==80,"Charcoal did not fuel the queued operation");
        bag=bag(p);
        for(int slot=3;slot<bag.getContainerSize();slot++)bag.setItem(slot,new ItemStack(Items.COBBLESTONE,64));
        carry(p,25);
        var stalled=bag(p).stack.copy();
        check(bag(p).countItem(Items.RAW_IRON)==2 && bag(p).countItem(Items.IRON_INGOT)==0 &&
                bag(p).countItem(Items.CHARCOAL)==1 && p.totalExperience==xp,"Blocked output consumed or created items/XP");
        carry(p,100);
        check(Transactions.same(stalled,bag(p).stack),"A full output destination drained fuel or changed stalled state");
        bag(p).setItem(3,ItemStack.EMPTY);
        carry(p,15);
        check(bag(p).countItem(Items.RAW_IRON)==1 && bag(p).countItem(Items.IRON_INGOT)==1
                && !Upgrades.manualSmeltPending(bag(p)) && Transactions.same(bag(p).getItem(1),keepsake),"Freed output did not resume once");
        near(data(p).getFloat("SmeltXP"),.7f,"Stalled recipe XP");
    }

    private static void recipes(ServerPlayer p){
        checkRecipe(p,MANUAL,3,2,List.of(Items.COPPER_INGOT,Items.FURNACE,Items.COPPER_INGOT,
                Items.AIR,Items.REDSTONE_TORCH,Items.AIR));
        checkRecipe(p,AUTO,3,1,List.of(Items.POWERED_RAIL,Items.CLOCK,disc(MANUAL).getItem()));
        checkRecipe(p,LEGACY,3,3,List.of(Items.PAPER,Items.FURNACE,Items.PAPER,Items.FURNACE,
                Items.REDSTONE,Items.FURNACE,Items.PAPER,Items.FURNACE,Items.PAPER));
    }

    static FakePlayer menuTester(ServerLevel level,String name){
        return new FakePlayer(level,new GameProfile(UUID.randomUUID(),name)){
            @Override public OptionalInt openMenu(MenuProvider provider,Consumer<RegistryFriendlyByteBuf> writer){
                // FakePlayer normally discards this. Use the real server factory and deliberately reuse the menu ID.
                containerMenu=provider.createMenu(37,getInventory(),this);
                return OptionalInt.of(containerMenu.containerId);
            }
        };
    }
    private static void menuStart(ServerPlayer host){
        var p=menuTester(host.serverLevel(),"SmelterMenu");
        var bag=install(p,MANUAL);filter(bag,"raw_iron");
        bag.setItem(0,new ItemStack(Items.RAW_IRON,2));bag.setItem(1,new ItemStack(Items.COAL,2));
        StoreMenu.bag(p,bag,true);
        var old=(StoreMenu)p.containerMenu;
        p.closeContainer();
        StoreMenu.bag(p,bag(p),true);
        var current=(StoreMenu)p.containerMenu;
        check(current.containerId==old.containerId && current.session()!=old.session(),"Expected distinct sessions with the same numeric menu ID");
        check(current.getSlot(24).getItem().getHoverName().getString().equals("Start one furnace cook"),"Manual Start missing on the upgrades page");
        var snapshot=bag(p).stack.copy();
        current.action(new Packets.Action(current.containerId,old.session(),current.revision(),"slot","",24));
        check(Transactions.same(snapshot,bag(p).stack),"Retired menu session started a manual job");
        current.action(new Packets.Action(current.containerId,current.session(),current.revision()-1,"slot","",24));
        check(Transactions.same(snapshot,bag(p).stack),"Stale displayed controls started a manual job");
        current.action(new Packets.Action(current.containerId,current.session(),current.revision(),"slot","",24));
        check(Upgrades.manualSmeltPending(bag(p)) && current.getSlot(24).getItem().getHoverName().getString().equals("One furnace cook queued"),
                "Valid server-menu slot 24 failed to queue exactly one cook");
        carry(p,12);
        check(bag(p).countItem(Items.COAL)==2 && bag(p).countItem(Items.IRON_INGOT)==0,"Manual queue ran with its real menu still open");
        p.closeContainer();carry(p,25);
        check(!Upgrades.manualSmeltPending(bag(p)) && bag(p).countItem(Items.RAW_IRON)==1 && bag(p).countItem(Items.IRON_INGOT)==1,
                "Closing the upgrades menu did not complete exactly the requested cook");
    }

    private static void fixtureRecipes(ServerPlayer p){
        var bucket=new SingleRecipeInput(new ItemStack(Items.WATER_BUCKET));
        var recipe=p.serverLevel().getRecipeManager().getRecipeFor(RecipeType.SMELTING,bucket,p.level()).orElseThrow();
        check(recipe.id().toString().equals("anyasmelterreview:bucket_remainder") &&
                recipe.value().getRemainingItems(bucket).stream().filter(s->s.is(Items.BUCKET)).mapToInt(ItemStack::getCount).sum()==1,
                "Expected the test datapack's vanilla smelting recipe and registered empty-bucket remainder");
        var bag=install(p,MANUAL);filter(bag,"water_bucket");
        bag.setItem(0,bucket.getItem(0).copy());bag.setItem(1,new ItemStack(Items.COAL,2));
        for(int i=2;i<bag.getContainerSize();i++)bag.setItem(i,new ItemStack(Items.COBBLESTONE,64));
        start(p);var full=bag(p).stack.copy();int xp=p.totalExperience;
        carry(p,10);
        check(Transactions.same(full,bag(p).stack) && p.totalExperience==xp,"Only the output fitted; input, fuel or bucket remainder was lost");
        bag(p).setItem(2,ItemStack.EMPTY);carry(p,5);
        check(!Upgrades.manualSmeltPending(bag(p)) && bag(p).countItem(Items.WATER_BUCKET)==0 && bag(p).countItem(Items.BUCKET)==1
                && bag(p).countItem(Items.SNOWBALL)==1 && bag(p).countItem(Items.COAL)==1 && p.totalExperience==xp+1,
                "After freeing one slot the physical bag must contain the result and its one bucket remainder");

        var forbidden=new SingleRecipeInput(new ItemStack(Items.TOTEM_OF_UNDYING));
        var blocked=p.serverLevel().getRecipeManager().getRecipeFor(RecipeType.SMELTING,forbidden,p.level()).orElseThrow();
        check(blocked.id().toString().equals("anyasmelterreview:forbidden_output")
                && blocked.value().assemble(forbidden,p.registryAccess()).is(Content.BACKPACK.get()),"Missing prohibited-output datapack recipe");
        bag=install(p,AUTO);filter(bag,"totem_of_undying");
        bag.setItem(0,forbidden.getItem(0).copy());bag.setItem(1,new ItemStack(Items.CHARCOAL,2));
        var unchanged=bag.stack.copy();xp=p.totalExperience;carry(p,10);
        check(Transactions.same(unchanged,bag(p).stack) && p.totalExperience==xp,"Forbidden nested backpack output consumed fuel, input or paid XP");
    }
    private static void checkRecipe(ServerPlayer p,String tier,int w,int h,List<Item> ingredients){
        List<ItemStack> slots=new ArrayList<>();
        for(Item item:ingredients)slots.add(item==Items.AIR?ItemStack.EMPTY:new ItemStack(item));
        var grid=CraftingInput.of(w,h,slots);
        var found=p.serverLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING,grid,p.level()).orElseThrow();
        check(Transactions.same(found.value().assemble(grid,p.registryAccess()),disc(tier))
                && found.value().getRemainingItems(grid).stream().allMatch(ItemStack::isEmpty),"Wrong recipe output or container remainder for "+tier);
        for(int slot=0;slot<slots.size();slot++){
            if(slots.get(slot).isEmpty())continue;
            var incomplete=new ArrayList<>(slots);incomplete.set(slot,ItemStack.EMPTY);
            check(!found.value().matches(CraftingInput.of(w,h,incomplete),p.level()),"Recipe "+tier+" accepted missing slot "+slot);
        }
    }
    private SmelterGameTests(){}
}
