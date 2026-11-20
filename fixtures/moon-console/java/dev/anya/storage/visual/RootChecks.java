package dev.anya.storage.visual;

import dev.anya.storage.*;
import dev.anya.storage.appearance.Looks;
import dev.anya.storage.pocket.PocketContent;
import dev.anya.storage.pocket.PocketService;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

/** Runner-only acceptance of public gameplay routes, using the finished mod JAR. */
@EventBusSubscriber(modid="anyamoonreview",value=Dist.CLIENT)
public final class RootChecks {
    private static final BlockPos ENTRANCE=new BlockPos(-3,80,0);
    private static final List<String> notices=new ArrayList<>();
    private static int bindings, opens, ticks;
    private static UUID pocketId;
    private enum Step { START, ARRIVING, VISIT, RETURNING, USE, MENU, CLOSE, ICONS, DONE }
    private static Step step=Step.START;
    private static CompletableFuture<Void> operation;

    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static void next(Step value){step=value;ticks=0;System.out.println("Pocket camera "+value);}
    private static void server(Minecraft mc, Consumer<ServerPlayer> work){
        var server=mc.getSingleplayerServer();
        UUID id=mc.player.getUUID();
        operation=server.submit(()->work.accept(server.getPlayerList().getPlayer(id)));
    }

    static void setup(ServerPlayer p){
        testBagNames(p);
        testDiscs(p);
        GlobeCheck.run(p);
        var level=p.serverLevel();
        require(!PocketService.enabled(),"Expected default travel gate during crafting");
        var key=ResourceLocation.fromNamespaceAndPath("anyastorage","home_void_cube");
        var holder=level.getRecipeManager().byKey(key).orElseThrow();
        require(holder.value() instanceof ShapelessRecipe && !holder.value().isSpecial(),"Standard discoverable recipe");
        p.setGameMode(GameType.SURVIVAL);
        require(!p.getRecipeBook().contains(holder),"Fresh player should discover the recipe");
        var diamonds=new ItemStack(Items.DIAMOND,2);
        p.getInventory().setItem(12,diamonds);
        p.inventoryMenu.broadcastChanges();
        require(p.getRecipeBook().contains(holder),"Diamond pickup unlocks the recipe");
        var grid=p.inventoryMenu.getCraftSlots();
        grid.setItem(0,diamonds.split(1));
        grid.setItem(3,diamonds.split(1));
        require(!p.inventoryMenu.getSlot(0).getItem().is(PocketContent.ITEM.get()),"Must require Ancient Debris");
        grid.setItem(1,new ItemStack(Items.ANCIENT_DEBRIS));
        var cube=p.inventoryMenu.getSlot(0).safeTake(1,1,p);
        require(cube.is(PocketContent.ITEM.get()) && cube.getCount()==1 && grid.isEmpty(),"Survival crafting result or ingredient consumption");
        var larger=new ArrayList<>(Collections.nCopies(9,ItemStack.EMPTY));
        larger.set(2,new ItemStack(Items.ANCIENT_DEBRIS));larger.set(5,new ItemStack(Items.DIAMOND));larger.set(8,new ItemStack(Items.DIAMOND));
        require(((ShapelessRecipe)holder.value()).matches(CraftingInput.of(3,3,larger),level),"Offset crafting table pattern");
        var state=PocketContent.ENTRANCE.get().defaultBlockState();
        level.setBlockAndUpdate(ENTRANCE,state);
        state.getBlock().setPlacedBy(level,ENTRANCE,state,p,cube);
        p.setGameMode(GameType.CREATIVE);
        // The guidebook grant on first join can occupy the selected slot.
        if(!p.getMainHandItem().isEmpty()){
            var given=p.getMainHandItem();
            p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            require(p.getInventory().getItem(27).isEmpty(),"Free slot for first-join item");
            p.getInventory().setItem(27,given);
        }
        GroundBagCheck.run(p);
        System.out.println("PASS Survival craft while pockets off, book unlock, exact consumption, table arrangement, entrance placement.");
    }

    private static void testBagNames(ServerPlayer p){
        var level=p.serverLevel();
        var oak=level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("anyastorage","leaf_backpack_oak")).orElseThrow();
        var bag=oak.value().getResultItem(level.registryAccess()).copy();
        require(bag.getHoverName().getString().equals("Leaf Backpack · Starter"),"Fresh leaf recipe result: "+bag.getHoverName().getString());
        BagInventory.initialize(bag);
        var id=BagInventory.data(bag).getUUID("BagId");
        var save=ItemContainerContents.fromItems(List.of(new ItemStack(Items.EMERALD,7)));
        bag.set(DataComponents.CONTAINER,save);
        var recipe=new UpgradeRecipe(CraftingBookCategory.EQUIPMENT);
        var names=new HashSet<String>();
        for(var tier:Config.get().tiers){
            if(!tier.id.equals("starter") && tier.enabled){
                var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse(tier.material));
                var grid=new ArrayList<>(Collections.nCopies(9,ItemStack.EMPTY));grid.set(0,bag);
                for(int i=1;i<=tier.cost;i++)grid.set(i,new ItemStack(item));
                var input=CraftingInput.of(3,3,grid);
                require(recipe.matches(input,level),"Tier recipe "+tier.name);
                bag=recipe.assemble(input,level.registryAccess());
            }else if(!tier.id.equals("starter"))continue;
            require(bag.getHoverName().getString().equals("Leaf Backpack · "+tier.name),"Tier name "+tier.name);
            require(id.equals(BagInventory.data(bag).getUUID("BagId")) && save.equals(bag.get(DataComponents.CONTAINER)),"Stored identity and contents");
            for(String element:Looks.ELEMENTS){
                var styled=Looks.styledCopy(bag,element,true);
                require(names.add(styled.getHoverName().getString()),"Duplicate element/tier label");
            }
            for(String skin:Looks.SKINS){
                if(skin.equals("elemental"))continue;
                var styled=Looks.styledCopy(bag,skin,false);
                require(names.add(styled.getHoverName().getString()),"Duplicate skin/tier label");
            }
        }
        var cherry=level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("anyastorage","leaf_backpack_cherry")).orElseThrow().value().getResultItem(level.registryAccess()).copy();
        require(cherry.getHoverName().getString().equals("Cherry Leaf Backpack · Starter"),"Species recipe name");
        var old=BagInventory.data(cherry);old.remove("TierName");old.putString("Tier","gold");BagInventory.data(cherry,old);
        require(cherry.getHoverName().getString().equals("Cherry Leaf Backpack · Gold"),"Legacy tier without TierName");
        old.putString("TierName","Rose Gold");BagInventory.data(cherry,old);
        require(cherry.getHoverName().getString().endsWith(" · Rose Gold"),"Custom server tier");
        cherry.set(DataComponents.CUSTOM_NAME,Component.literal("For luck"));
        require(Looks.styledCopy(cherry,"galaxy",false).getHoverName().getString().equals("For luck"),"Anvil name");
        System.out.println("PASS "+names.size()+" unique backpack style/tier labels, leaf crafting, real tier upgrades, custom names, bag ID and cargo.");
    }

    private static void testDiscs(ServerPlayer player){
        var bag=new ItemStack(Content.BACKPACK.get());BagInventory.initialize(bag);
        var data=BagInventory.data(bag);data.putInt("UpgradeSlots",7);BagInventory.data(bag,data);
        for(String id:Config.UPGRADE_IDS){
            var disc=new ItemStack(Content.UPGRADES.get(id).get(),3);
            disc.set(DataComponents.CUSTOM_NAME,Component.literal("My "+id));
            var change=BackpackAttachment.preview(player,bag,disc);
            require(change.recognized() && change.cost()==1 && change.output().is(Content.BACKPACK.get()),"Direct "+id);
            require(disc.getCount()==3 && BackpackAttachment.preview(player,change.output(),disc).cost()==0,"No precharge or duplicate disc");
            var installed=Upgrades.installedItems(change.output());
            var saved=ItemStack.parseOptional(player.registryAccess(),installed.getCompound(installed.size()-1).getCompound("Stack"));
            require(saved.getCount()==1 && ItemStack.isSameItemSameComponents(saved,disc),"Exact installed disc");
            bag=change.output();
        }
        require(Upgrades.installedItems(bag).size()==7,"Distinct discs installed");
        require(!BackpackAttachment.preview(player,bag,new ItemStack(Items.STICK)).recognized(),"Unrelated item should pass");
        System.out.println("PASS all seven direct upgrade discs retained exact components and rejected duplicate installs.");
    }

    private static void verifyTooltipPreference(ServerPlayer p){
        require(Profiles.of(p).hideStorageTooltips,"Owner setting persisted after close");
        var other=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(p.serverLevel());
        require(!Profiles.of(other).hideStorageTooltips,"Another player's default was changed");
        var lookup=p.registryAccess();
        var saved=Profiles.get(p.getServer()).save(new net.minecraft.nbt.CompoundTag(),lookup);
        var roundTrip=Profiles.load(saved,lookup).save(new net.minecraft.nbt.CompoundTag(),lookup);
        var rows=roundTrip.getList("Players",net.minecraft.nbt.Tag.TAG_COMPOUND);
        boolean found=false;
        for(int i=0;i<rows.size();i++){
            var profile=rows.getCompound(i);
            if(profile.getUUID("Id").equals(p.getUUID()))found=profile.getBoolean("HideStorageTooltips");
            else require(!profile.getBoolean("HideStorageTooltips"),"Another player's saved preference changed");
        }
        require(found,"Persisted tooltip preference did not survive a profile save/load");
    }

    @SubscribeEvent public static void chat(ClientChatReceivedEvent.System event){
        String msg=event.getMessage().getString();
        if(!msg.startsWith("[Anya Storage]"))return;
        if(msg.contains("Linked to pocket "))bindings++;
        if(step==Step.USE || step==Step.MENU || step==Step.CLOSE)notices.add(msg);
    }

    static boolean run(Minecraft mc) throws Exception {
        if(operation!=null){
            if(!operation.isDone())return false;
            operation.join();operation=null;
        }
        if(mc.player==null || mc.level==null)return false;
        require(++ticks<800,"Timeout at "+step+", notices="+notices);
        switch(step){
            case START -> {
                server(mc,p->{
                    verifyTooltipPreference(p);
                    require(Config.setServerSetting(p,Config.get().revision,"expansion.pocketStorage","true"),"Enable visit");
                    var be=(PocketContent.EntranceEntity)p.serverLevel().getBlockEntity(ENTRANCE);
                    var tablet=p.getMainHandItem();
                    require(tablet.is(Content.CUPID_TABLET.get()) && StorageItem.bound(p,tablet)!=null,"Original bound tablet");
                    PocketService.link(p,be,tablet);
                    PocketService.link(p,be,tablet);
                    require(be.pocket!=null && be.pocket.equals(BagInventory.data(tablet).getUUID("PocketId")),"Actual linked entrance");
                    pocketId=be.pocket;
                    PocketService.enter(p,be);
                });
                next(Step.ARRIVING);
            }
            case ARRIVING -> {if(PocketService.inPocket(mc.level) && mc.player.level()==mc.level)next(Step.VISIT);}
            case VISIT -> {
                if(RoomCamera.run(mc)){server(mc,PocketService::recover);next(Step.RETURNING);}
            }
            case RETURNING -> {
                if(ticks>=10 && !PocketService.inPocket(mc.level) && mc.screen==null)next(Step.USE);
            }
            case USE -> {
                if(ticks<5)return false;
                mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);
                next(Step.MENU);
            }
            case MENU -> {
                if(!(mc.player.containerMenu instanceof StoreMenu menu) || menu.capacity()==0 || mc.screen==null)return false;
                require(menu.remoteOpening() && menu.capacity()==216 && !menu.tooltipsEnabled(),"Valid linked pocket and retained personal tooltip choice");
                if(ticks<12)return false;
                server(mc,p->{require(p.containerMenu instanceof StoreMenu m && m.hasPocket(pocketId),"Server opened correct pocket");p.closeContainer();});
                opens++;next(Step.CLOSE);
            }
            case CLOSE -> {
                if(ticks<12 || mc.screen!=null)return false;
                if(opens<2){next(Step.USE);return false;}
                require(notices.isEmpty() && bindings==1,"Expected one real binding and quiet routine use: "+bindings+", "+notices);
                server(mc,p->{
                    var inv=p.getInventory();
                    for(int i=0;i<Config.UPGRADE_IDS.size();i++)inv.setItem(i,new ItemStack(Content.UPGRADES.get(Config.UPGRADE_IDS.get(i)).get()));
                    inv.setItem(7,new ItemStack(Content.CORE.get()));inv.setItem(8,new ItemStack(Content.DRAGON_TOKEN.get()));
                    p.inventoryMenu.broadcastChanges();
                });
                next(Step.ICONS);
            }
            case ICONS -> {
                if(ticks<18 || !mc.player.getInventory().getItem(8).is(Content.DRAGON_TOKEN.get()))return false;
                mc.gui.getChat().clearMessages(true);
                Screenshot.grab(mc.gameDirectory,"discs-hotbar.png",mc.getMainRenderTarget(),message->{});
                Files.writeString(mc.gameDirectory.toPath().resolve("storage-checks.txt"),
                    "Survival entrance crafted while pockets off. Actual linked visit and return. Two 216-slot tablet opens, one link confirmation, zero routine Anya messages. Backpack names and retained cargo checked across tier recipes. Nine distinct items in hotbar.\n");
                next(Step.DONE);
            }
            case DONE -> {return Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots/discs-hotbar.png"));}
        }
        return false;
    }
    private RootChecks(){}
}
