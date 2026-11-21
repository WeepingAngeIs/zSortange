package dev.anya.feedingreview;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.AdvancedFeeding;
import dev.anya.storage.BagInventory;
import dev.anya.storage.Config;
import dev.anya.storage.Content;
import dev.anya.storage.Transactions;
import dev.anya.storage.Upgrades;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@Mod("anyafeedingreview")
@EventBusSubscriber(modid="anyafeedingreview")
public final class FeedingReview {
    private enum Stage { PARITY, THRESHOLD, PRIORITY, UNSAFE, DEFAULT_UNSAFE, OVERFILL, DUPLICATE, MENU, DISABLED, HELD, REPEAT, DONE }
    private static final String BASIC="feeding", ADVANCED="advanced_feeding";
    private static final List<String> notes=new ArrayList<>();
    private static final Map<UUID,Actor> actors=new HashMap<>();
    private static MinecraftServer server;
    private static OnionHistory onion;
    private static boolean provider;
    private static Actor manual,basic,advanced;
    private static Stage stage;
    private static int elapsed,activeHandPasses,earlierProviderCredits;
    private static ItemStack unchanged,hand,offhand;
    private static OnionHistory.Snapshot guarded;
    private static boolean originalGate;
    private static final class Actor{
        final ServerPlayer player;final EmbeddedChannel channel;
        final List<String> starts=new ArrayList<>(),finishes=new ArrayList<>();
        final OnionHistory.Snapshot before;
        Actor(ServerPlayer player,EmbeddedChannel channel)throws Exception{
            this.player=player;this.channel=channel;this.before=onion.read(player);
        }
    }
    static void check(boolean ok,String why){if(!ok)throw new IllegalStateException("Feeding "+stage+": "+why);}
    private static void next(Stage value){stage=value;elapsed=0;}
    private static Path artifact(String id){
        return ModList.get().getModContainerById(id).orElseThrow().getModInfo().getOwningFile().getFile().getFilePath();
    }
    private static String hash(Path path)throws Exception{
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void start(ServerStartedEvent event)throws Exception{
        check("true".equals(System.getenv("GITHUB_ACTIONS")),"Hosted runner required");
        server=event.getServer();provider=Boolean.getBoolean("anya.feedingProvider");
        var playable=artifact("anyastorage");
        check(Files.isSameFile(playable,Path.of(System.getProperty("anya.feedingPlayable"))),"Expected frozen uploaded JAR");
        check(ModList.get().isLoaded("solonion")==provider && ModList.get().isLoaded("creativecore")==provider,"Unexpected provider runtime");
        notes.add("Playable SHA-256 "+hash(playable));notes.add("Runtime "+(provider?"provider":"plain"));
        if(provider){
            notes.add("Spice of Life Onion 1.5.6 SHA-256 "+hash(artifact("solonion")));
            notes.add("CreativeCore 2.13.49 SHA-256 "+hash(artifact("creativecore")));
        }
        onion=new OnionHistory(provider);
        manual=join("HandMeal",3);basic=join("BasicMeal",7);advanced=join("AdvancedMeal",11);
        recipe();
        install(basic.player,BASIC);install(advanced.player,ADVANCED);
        check(AdvancedFeeding.minimumMissing(bag(advanced))==4 && AdvancedFeeding.priority(bag(advanced)).isEmpty(),"New bag default feeding preferences");
        bag(basic).setItem(0,new ItemStack(Items.APPLE,2));bag(advanced).setItem(0,new ItemStack(Items.APPLE,2));
        for(Actor a:List.of(manual,basic,advanced))food(a.player,16,0);
        manual.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE,2));
        hand=advanced.player.getMainHandItem().copy();offhand=advanced.player.getOffhandItem().copy();
        manual.player.gameMode.useItem(manual.player,manual.player.serverLevel(),manual.player.getMainHandItem(),InteractionHand.MAIN_HAND);
        check(manual.player.isUsingItem() && manual.player.getUseItem().is(Items.APPLE),"Normal game-mode hand use did not begin");
        next(Stage.PARITY);
    }
    private static Actor join(String name,int x)throws Exception{
        var level=server.overworld();var profile=new GameProfile(UUID.randomUUID(),name);
        var p=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
        var connection=new Connection(PacketFlow.SERVERBOUND){
            @Override public boolean isMemoryConnection(){return true;}
            @Override public void send(Packet<?> packet,PacketSendListener callback,boolean flush){}
        };
        var channel=new EmbeddedChannel(connection);NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection,p,CommonListenerCookie.createInitial(profile,false));
        p.setGameMode(GameType.SURVIVAL);
        var floor=new BlockPos(x,80,3);level.setBlockAndUpdate(floor,Blocks.SMOOTH_STONE.defaultBlockState());
        p.teleportTo(level,x+.5,81,3.5,0,0);p.getInventory().clearContent();p.getInventory().selected=1;
        p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STICK));
        p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.FEATHER));
        var actor=new Actor(p,channel);actors.put(p.getUUID(),actor);return actor;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void startedUsing(LivingEntityUseItemEvent.Start event){
        var a=actors.get(event.getEntity().getUUID());
        if(a!=null)a.starts.add(BuiltInRegistries.ITEM.getKey(event.getItem().getItem()).toString());
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void finishedUsing(LivingEntityUseItemEvent.Finish event){
        var a=actors.get(event.getEntity().getUUID());
        if(a!=null)a.finishes.add(BuiltInRegistries.ITEM.getKey(event.getItem().getItem()).toString());
    }
    @SubscribeEvent
    public static void recordEarlierAddonCredit(VanillaGameEvent event) throws Exception {
        if (!provider || stage != Stage.REPEAT || event.getCause() != advanced.player || !event.getVanillaEvent().equals(GameEvent.EAT)) return;
        // Simulate a second add-on that credits the real provider during the same bread's natural EAT event.
        var events = Class.forName("team.creative.solonion.common.SOLOnion").getField("EVENT").get(null);
        events.getClass().getMethod("eat", ItemStack.class, net.minecraft.world.entity.player.Player.class)
                .invoke(events, new ItemStack(Items.BREAD), advanced.player);
        earlierProviderCredits++;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void usingAtCarriedPass(PlayerTickEvent.Post event){
        if(stage==Stage.HELD && event.getEntity()==advanced.player && advanced.player.tickCount%20==0 && advanced.player.isUsingItem())
            activeHandPasses++;
    }
    private static ItemStack disc(String id){return new ItemStack(Content.UPGRADES.get(id).get());}
    private static ItemStack named(ItemStack stack,String name){stack.set(DataComponents.CUSTOM_NAME,Component.literal(name));return stack;}
    private static void install(ServerPlayer p,String id){
        var inv=p.getInventory();inv.setItem(0,new ItemStack(Content.BACKPACK.get()));
        inv.setItem(9,named(disc(id),"Lunchbox "+id));
        check(Upgrades.change(new BagInventory(p,0),id,false) && inv.getItem(9).isEmpty(),"Exact installation "+id);
    }
    private static void recipe(){
        var level=server.overworld();
        var items=List.of(new ItemStack(Items.BOWL),disc(BASIC),new ItemStack(Items.CLOCK));
        var input=CraftingInput.of(3,1,items);
        var recipe=level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,level).orElseThrow().value();
        check(Transactions.same(recipe.assemble(input,level.registryAccess()),disc(ADVANCED)) &&
                recipe.getRemainingItems(input).stream().allMatch(ItemStack::isEmpty),"Advanced disc recipe output or cost");
        for(int i=0;i<items.size();i++){
            var missing=new ArrayList<>(items);missing.set(i,ItemStack.EMPTY);
            check(!recipe.matches(CraftingInput.of(3,1,missing),level),"Advanced disc recipe accepted missing ingredient "+i);
        }
        notes.add("PASS Real shapeless recipe consumed one basic feeder, clock and bowl for one advanced disc; each ingredient is required.");
    }
    private static BagInventory bag(Actor a){return new BagInventory(a.player,0);}
    private static void food(ServerPlayer p,int hunger,float saturation){
        p.getFoodData().setFoodLevel(hunger);p.getFoodData().setSaturation(saturation);p.getFoodData().setExhaustion(0);
    }
    private static void close(float actual,float expect,String msg){check(Math.abs(actual-expect)<.0001f,msg+" "+actual+" / "+expect);}
    private static void expectHistory(Actor a,OnionHistory.Snapshot previous,String food,boolean credit)throws Exception{
        var current=onion.read(a.player);
        if(!provider || !credit){check(current.equals(previous),"Unexpected food record or benefit for "+a.player.getScoreboardName());return;}
        var expected=new ArrayList<>(previous.newestFirst());expected.addFirst(food);
        check(current.newestFirst().equals(expected),"Expected exactly one "+food+" for "+a.player.getScoreboardName()+"; got "+current.newestFirst());
    }
    private static void parity()throws Exception{
        var handData=manual.player.getFoodData();var apple="minecraft:apple";
        check(!manual.player.isUsingItem() && manual.starts.equals(List.of(apple)) && manual.finishes.equals(List.of(apple)),
                "Hand eating must emit exactly one real apple Start and Finish, observed "+manual.starts+" / "+manual.finishes);
        check(manual.player.getMainHandItem().is(Items.APPLE) && manual.player.getMainHandItem().getCount()==1 &&
                handData.getFoodLevel()==20 && handData.getSaturationLevel()>0,"Hand control did not eat exactly one apple");
        for(Actor a:List.of(basic,advanced)){
            check(bag(a).countItem(Items.APPLE)==1 && a.starts.isEmpty() && a.finishes.isEmpty() &&
                    a.player.getFoodData().getFoodLevel()==handData.getFoodLevel(),"Automatic meal count, hunger or fake hand event");
            close(a.player.getFoodData().getSaturationLevel(),handData.getSaturationLevel(),"Hand vs backpack saturation");
            check(Transactions.same(a.player.getMainHandItem(),hand)&&Transactions.same(a.player.getOffhandItem(),offhand),
                    "Automatic feeding displaced a hand");
        }
        expectHistory(manual,manual.before,apple,true);expectHistory(basic,basic.before,apple,false);
        expectHistory(advanced,advanced.before,apple,true);
        if(provider){
            var reference=onion.read(manual.player);var actual=onion.read(advanced.player);
            check(Math.abs(reference.diversity()-actual.diversity())<.00001 &&
                    reference.maxHealth()-manual.before.maxHealth()==actual.maxHealth()-advanced.before.maxHealth(),
                    "Advanced max-health or diversity change differed from manual eating");
        }
        notes.add("PASS One apple per actor; food level 16 to 20 and saturation 0 to "+handData.getSaturationLevel()
                +". Native hand use emitted one apple Start/Finish; Basic and Advanced emitted none and preserved both hands.");
        if(provider)notes.add("PASS Native hand and Advanced each added exactly one minecraft:apple to their real Onion history with matching diversity and max-health change. Basic produced zero provider entries.");
    }
    private static void savePreferences()throws Exception{
        var p=advanced.player;var bag=bag(advanced);
        var spare=named(disc(BASIC),"Spare basic feeder");p.getInventory().setItem(9,spare.copy());
        check(!Upgrades.change(bag,BASIC,false) && Transactions.same(p.getInventory().getItem(9),spare)
                && Upgrades.installed(bag,ADVANCED) && !Upgrades.installed(bag,BASIC),"Conflicting feeder tier installed or consumed the spare disc");
        p.getInventory().setItem(9,ItemStack.EMPTY);
        check(AdvancedFeeding.setMinimumMissing(bag,8).isEmpty(),"Valid threshold rejected");
        check(AdvancedFeeding.toggleFood(bag,"minecraft:bread").isEmpty() && AdvancedFeeding.toggleFood(bag,"minecraft:carrot").isEmpty(),"Valid priority inputs rejected");
        var order=List.of("minecraft:bread","minecraft:carrot");
        AdvancedFeeding.toggleFood(bag,"minecraft:poisonous_potato");
        AdvancedFeeding.setMinimumMissing(bag,11);
        check(AdvancedFeeding.minimumMissing(bag)==8 && AdvancedFeeding.priority(bag).equals(order),"Invalid input changed preferences");
        var other=new ItemStack(Content.BACKPACK.get());p.getInventory().setItem(15,other);
        BagInventory separate=new BagInventory(p,15);
        check(AdvancedFeeding.priority(separate).isEmpty() && AdvancedFeeding.minimumMissing(separate)==4,"Preferences leaked to another bag");
        p.getInventory().setItem(15,ItemStack.EMPTY);
        var original=p.getInventory().removeItemNoUpdate(0);
        var path=Path.of("fixture-advanced-bag.nbt");
        NbtIo.writeCompressed((CompoundTag)original.save(p.registryAccess()),path);
        var restored=ItemStack.parseOptional(p.registryAccess(),NbtIo.readCompressed(path,NbtAccounter.create(2_097_152)));
        check(Transactions.same(original,restored),"Registry-aware bag disk round trip changed contents or identity");
        p.getInventory().setItem(0,restored);bag=bag(advanced);
        check(AdvancedFeeding.minimumMissing(bag)==8 && AdvancedFeeding.priority(bag).equals(order),"Saved order or threshold changed");
        var expected=named(disc(ADVANCED),"Lunchbox "+ADVANCED);
        check(Upgrades.change(bag,ADVANCED,true) && p.getInventory().countItem(expected.getItem())==1,"Removal did not return one disc");
        boolean exact=false;
        for(int i=0;i<36;i++)if(Transactions.same(p.getInventory().getItem(i),expected))exact=true;
        check(exact && Upgrades.change(bag(advanced),ADVANCED,false) && p.getInventory().countItem(expected.getItem())==0,
                "Named disc recovery/reinstall");
        check(AdvancedFeeding.minimumMissing(bag(advanced))==8 && AdvancedFeeding.priority(bag(advanced)).equals(order),"Reinstall erased bag preferences");
        notes.add("PASS The bag's identity, cargo, named disc, threshold and ordered foods survived a real NBT disk read. A second backpack kept its own defaults. Removing and reinstalling returned the exact named disc.");
    }
    private static void guard(Stage to)throws Exception{
        unchanged=advanced.player.getInventory().getItem(0).copy();guarded=onion.read(advanced.player);next(to);
    }
    private static void noMeal()throws Exception{
        check(Transactions.same(unchanged,advanced.player.getInventory().getItem(0)) && advanced.finishes.isEmpty(),"A refused meal changed bag or emitted Finish");
        check(onion.read(advanced.player).equals(guarded),"Refused meal changed provider history/benefit");
    }
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event)throws Exception{
        if(stage==null||stage==Stage.DONE)return;
        // These loopback transports were never attached to the network acceptor. Run the normal connection tick
        // once per world tick so the registered player runs its real use duration and LivingEntity completion.
        for(Actor a:List.of(manual,basic,advanced))a.player.connection.tick();
        elapsed++;
        var p=advanced.player;
        switch(stage){
            case PARITY->{
                if(elapsed<60)return;
                parity();savePreferences();
                var bag=bag(advanced);bag.clearContent();bag.setItem(0,new ItemStack(Items.CARROT,2));bag.setItem(1,new ItemStack(Items.BREAD,2));
                food(p,13,0);guard(Stage.THRESHOLD);
            }
            case THRESHOLD->{
                if(elapsed<30)return;
                noMeal();check(p.getFoodData().getFoodLevel()==13,"A deficit one below configured eight fed a meal");
                food(p,12,0);next(Stage.PRIORITY);
            }
            case PRIORITY->{
                if(elapsed<30)return;
                var bag=bag(advanced);check(bag.countItem(Items.CARROT)==2 && bag.countItem(Items.BREAD)==1 &&
                        p.getFoodData().getFoodLevel()==17 && advanced.finishes.isEmpty(),"Priority did not consume the listed bread before slot-zero carrot");
                expectHistory(advanced,guarded,"minecraft:bread",true);
                bag.clearContent();bag.setItem(0,named(new ItemStack(Items.BREAD,2),"Keepsake lunch"));
                bag.setItem(1,new ItemStack(Items.POISONOUS_POTATO));bag.setItem(2,new ItemStack(Items.APPLE));
                food(p,12,0);guard(Stage.UNSAFE);
            }
            case UNSAFE->{
                if(elapsed<30)return;
                noMeal();var bag=bag(advanced);
                AdvancedFeeding.clearPriority(bag);bag.setItem(2,new ItemStack(Items.ROTTEN_FLESH));guard(Stage.DEFAULT_UNSAFE);
            }
            case DEFAULT_UNSAFE->{
                if(elapsed<30)return;
                noMeal();var bag=bag(advanced);
                AdvancedFeeding.toggleFood(bag,"minecraft:cooked_beef");AdvancedFeeding.setMinimumMissing(bag,2);
                bag.clearContent();bag.setItem(0,new ItemStack(Items.COOKED_BEEF,2));food(p,16,0);guard(Stage.OVERFILL);
            }
            case OVERFILL->{
                if(elapsed<30)return;
                noMeal();food(p,10,0);p.getInventory().setItem(7,bag(advanced).stack.copy());guard(Stage.DUPLICATE);
            }
            case DUPLICATE->{
                if(elapsed<30)return;
                noMeal();check(Transactions.same(unchanged,p.getInventory().getItem(7)),"Duplicate identity copy changed");
                p.getInventory().setItem(7,ItemStack.EMPTY);dev.anya.storage.StoreMenu.bag(p,bag(advanced),true);
                check(p.containerMenu instanceof dev.anya.storage.StoreMenu,"Real upgrades menu did not open");guard(Stage.MENU);
            }
            case MENU->{
                if(elapsed<30)return;
                noMeal();p.closeContainer();originalGate=Config.get().upgrades.put(BASIC,false);guard(Stage.DISABLED);
            }
            case DISABLED->{
                if(elapsed<30)return;
                noMeal();Config.get().upgrades.put(BASIC,originalGate);
                p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE,2));
                p.gameMode.useItem(p,p.serverLevel(),p.getMainHandItem(),InteractionHand.MAIN_HAND);
                check(p.isUsingItem(),"Native hand meal did not start for active-use guard");guard(Stage.HELD);
            }
            case HELD->{
                if(elapsed<23)return;
                check(p.isUsingItem() && activeHandPasses>=1 && p.getMainHandItem().getCount()==2,
                        "A real carried pass did not occur during the live hand-use window: "+activeHandPasses);
                noMeal();p.stopUsingItem();food(p,20,0);
                notes.add("PASS Threshold boundary, allowlist order, unsafe and named foods, overfill, duplicated identity, open menu, disabled family and an ongoing hand meal. Refusals preserved bag contents and provider records.");
                var bag=bag(advanced);AdvancedFeeding.clearPriority(bag);bag.clearContent();bag.setItem(0,new ItemStack(Items.BREAD,2));
                food(p,15,0);guarded=onion.read(p);next(Stage.REPEAT);
            }
            case REPEAT->{
                if(elapsed<30)return;
                check(bag(advanced).countItem(Items.BREAD)==1 && p.getFoodData().getFoodLevel()==20 && advanced.finishes.isEmpty(),
                        "Repeat meal did not consume one bread with no held Finish");
                if(provider){
                    check(!guarded.newestFirst().isEmpty() && guarded.newestFirst().getFirst().equals("minecraft:bread") && earlierProviderCredits==1,
                            "Expected one competing add-on credit while repeating the exact preceding bread");
                    expectHistory(advanced,guarded,"minecraft:bread",true);
                    notes.add("PASS A fixture add-on credited a repeated bread during its real EAT event. Advanced left exactly one new provider entry for that meal.");
                }
                server.saveEverything(true,true,true);
                Files.write(Path.of("feeding-checks.txt"),notes,StandardOpenOption.CREATE_NEW);
                next(Stage.DONE);
                for(Actor a:List.of(manual,basic,advanced)){server.getPlayerList().remove(a.player);a.channel.finishAndReleaseAll();}
                server.halt(false);
            }
            case DONE->{}
        }
    }
}
