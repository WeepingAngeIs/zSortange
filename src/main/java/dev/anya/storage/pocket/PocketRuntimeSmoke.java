package dev.anya.storage.pocket;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid=AnyaStorage.ID)
public final class PocketRuntimeSmoke {
    private static final NavigableMap<Long,Runnable> steps=new TreeMap<>();
    private static net.minecraft.server.MinecraftServer server;
    private static long tick;
    private static boolean running;
    private record Fixture(net.minecraft.server.level.ServerLevel getLevel) {
        BlockPos absolutePos(BlockPos local){return local.offset(0,80,0);}
        void setBlock(int x,int y,int z,net.minecraft.world.level.block.Block block){getLevel.setBlockAndUpdate(absolutePos(new BlockPos(x,y,z)),block.defaultBlockState());}
        void runAfterDelay(int delay,Runnable work){steps.put((long)delay,work);}
        void succeed(){running=false;getLevel.getServer().saveEverything(true,true,true);getLevel.getServer().halt(false);}
    }
    private static void check(Fixture h,boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static ServerPlayer player(Fixture h,String name){
        var profile=new GameProfile(UUID.randomUUID(),name);
        var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),profile,net.minecraft.server.level.ClientInformation.createDefault()){
            @Override public boolean hasPermissions(int ignored){return false;}
        };
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND){
            @Override public boolean isMemoryConnection(){return true;}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener callback,boolean flush){} // CI transport sink, no real client.
        };
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,p,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false));
        return p;
    }
    @net.neoforged.bus.api.SubscribeEvent public static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event)throws Exception {
        if(!Boolean.getBoolean("anyastorage.pocketTests"))return;
        server=event.getServer();
        if(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat").toFile().exists())throw new IllegalStateException("Pocket runtime requires a fresh disposable world");
        check(null,PocketService.dimension(server)!=null,"Dedicated server must load the shipped pocket dimension");
        var h=new Fixture(server.overworld());h.getLevel().getChunk(0,0);
        for(int x=0;x<10;x++)for(int z=0;z<10;z++)h.setBlock(x,0,z,Blocks.STONE);
        tick=0;running=true;exercise(h);
    }
    @net.neoforged.bus.api.SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST) public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        if(!running||event.getServer()!=server)return;
        try{tick++;while(!steps.isEmpty()&&steps.firstKey()<=tick)steps.pollFirstEntry().getValue().run();if(tick>1400)throw new IllegalStateException("Pocket runtime probe timed out");}
        catch(Throwable error){running=false;AnyaStorage.LOG.error("ANYA_POCKET_RUNTIME_FAILED",error);server.halt(false);}
    }
    private static void exercise(Fixture h)throws Exception {
        ExpansionRules.get().pocketStorage=true;
        var server=h.getLevel().getServer();ServerPlayer owner=player(h,"PocketOwner"),visitor=player(h,"PocketGuest");
        BlockPos hp=h.absolutePos(new BlockPos(1,1,1)),ep=h.absolutePos(new BlockPos(3,1,1));
        h.setBlock(1,1,1,Content.HUB.get());h.setBlock(3,1,1,PocketContent.ENTRANCE.get());h.setBlock(3,0,3,Blocks.STONE);h.setBlock(4,0,3,Blocks.STONE);
        StorageEntity hub=(StorageEntity)h.getLevel().getBlockEntity(hp);hub.owner=owner.getUUID();
        var entrance=(PocketContent.EntranceEntity)h.getLevel().getBlockEntity(ep);entrance.owner=owner.getUUID();
        owner.setPos(ep.getX()+.5,ep.getY(),ep.getZ()+2.5);visitor.setPos(ep.getX()+1.5,ep.getY(),ep.getZ()+2.5);
        ItemStack remote=new ItemStack(Content.REMOTE.get());var data=BagInventory.data(remote);data.putString("HubDimension",h.getLevel().dimension().location().toString());data.putLong("HubPos",hp.asLong());BagInventory.data(remote,data);owner.getInventory().setItem(0,remote);
        ItemStack named=new ItemStack(Items.DIAMOND,17);named.set(DataComponents.CUSTOM_NAME,Component.literal("Conserved pocket diamonds"));owner.getInventory().setItem(5,named.copy());
        PocketService.testPlayer(owner);PocketService.testPlayer(visitor);PocketService.link(owner,entrance,remote);check(h,entrance.pocket!=null,"Persistent entrance linked");
        UUID id=entrance.pocket;var r=PocketService.ledger(server).get(id);hub.setMember(visitor.getUUID(),BasePermissions.VIEW);
        check(h,!PocketService.authorize(visitor,r,PocketLeases.Kind.VISIT,true,0).isEmpty(),"Storage viewer cannot enter without Visit");
        PocketService.enter(owner,entrance);
        h.runAfterDelay(150,()->{
            try{
                check(h,PocketService.inPocket(owner.level()),"Owner actually crossed into registered pocket dimension");
                var record=PocketService.ledger(server).get(id);check(h,record.stage()==PocketLedger.Stage.READY,"Preparation reached READY");
                check(h,PocketService.ledger(server).returning(owner.getUUID())!=null,"Return marker exists during visit");
                check(h,Transactions.same(owner.getInventory().getItem(5),named),"Teleport did not mutate item components or count");
                var view=new PocketView(owner,record,PocketLeases.Kind.VISIT);check(h,view.containers().size()==4,"Four real physical containers");
                StorageEntity chest=(StorageEntity)view.containers().getFirst();var main=new MainInventory(owner);
                var deposit=new Transactions.Plan(List.of(main,chest),view.guard(BasePermissions.DEPOSIT));check(h,deposit.move(main,5,List.of(chest),17)==17,"Deposit fit");
                check(h,deposit.commit(server).isEmpty()&&owner.getInventory().getItem(5).isEmpty()&&Transactions.same(chest.getItem(0),named),"Real inventory conservation");
                // Menu factory uses the existing accurate-capacity UI without an invented virtual store.
                StoreMenu.pocket(owner,view);check(h,owner.containerMenu instanceof StoreMenu m&&m.capacity()==216,"Real 4x54 physical capacity reported");owner.closeContainer();
                hub.setMember(visitor.getUUID(),BasePermissions.VISIT);PocketService.enter(visitor,entrance);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(175,()->{
            try{
                check(h,PocketService.inPocket(visitor.level()),"Second identity entered independently");var record=PocketService.ledger(server).get(id);
                var visitorView=new PocketView(visitor,record,PocketLeases.Kind.VISIT);check(h,!visitorView.permits(BasePermissions.VIEW)&&!visitorView.permits(BasePermissions.WITHDRAW),"Visit-only cannot inspect or withdraw");
                StoreMenu.pocket(visitor,visitorView);check(h,visitor.containerMenu instanceof StoreMenu m&&m.privateView()&&m.resultCount()==0,"Private physical chest data not sent to Visit-only menu");visitor.closeContainer();
                PocketService.recover(owner);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(200,()->{
            try{
                check(h,!PocketService.inPocket(owner.level()),"Owner returned without stranding");check(h,PocketService.ledger(server).returning(owner.getUUID())==null,"Completed return cleared only after exit");
                check(h,PocketService.lease(visitor.getUUID())!=null&&PocketService.ownedTicketRequests()>0,"Second visitor retains pocket requests");
                // Revocation must invalidate an already constructed view before any transaction commits.
                hub.setMember(visitor.getUUID(),BasePermissions.VISIT|BasePermissions.LEGACY);var record=PocketService.ledger(server).get(id);var v=new PocketView(visitor,record,PocketLeases.Kind.VISIT);StorageEntity chest=(StorageEntity)v.containers().getFirst();
                var destination=new SimpleContainer(1);var take=new Transactions.Plan(List.of(chest,destination),v.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));take.move(chest,0,List.of(destination),17);
                hub.removeMember(visitor.getUUID());check(h,!take.commit(server).isEmpty()&&destination.isEmpty()&&Transactions.same(chest.getItem(0),named),"Revoked queued transfer changes no inventory");
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(230,()->{
            try{
                check(h,!PocketService.inPocket(visitor.level()),"Revoked visitor recovered automatically");
                check(h,PocketService.ownedTicketRequests()==0,"Last visitor released every Anya-owned ticket request");
                var record=PocketService.ledger(server).get(id);var pocket=PocketService.dimension(server);var chest=(StorageEntity)pocket.getBlockEntity(PocketService.chestPositions(record).getFirst());
                check(h,chest!=null&&Transactions.same(chest.getItem(0),named),"Chest contents stayed physical and unchanged");
                PocketService.remote(owner,remote,hub);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(260,()->{
            try{
                check(h,owner.containerMenu instanceof StoreMenu m&&m.capacity()==216,"Home-radius remote opens pocket's physical chests");
                var record=PocketService.ledger(server).get(id);var remoteView=new PocketView(owner,record,PocketLeases.Kind.REMOTE);StorageEntity chest=(StorageEntity)remoteView.containers().getFirst();
                SimpleContainer destination=new SimpleContainer(1);var plan=new Transactions.Plan(List.of(chest,destination),remoteView.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));plan.move(chest,0,List.of(destination),17);
                h.getLevel().removeBlock(ep,false);check(h,!plan.commit(server).isEmpty()&&destination.isEmpty()&&Transactions.same(chest.getItem(0),named),"Destroyed entrance invalidates remote transfer without inventory loss");
                owner.closeContainer();
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(280,()->{
            try{
                check(h,PocketService.ownedTicketRequests()==0,"Remote closure releases owned requests");
                var before=PocketService.ledger(server).get(id);BlockPos replacement=h.absolutePos(new BlockPos(5,1,1));
                h.setBlock(5,1,1,PocketContent.ENTRANCE.get());var restored=(PocketContent.EntranceEntity)h.getLevel().getBlockEntity(replacement);restored.owner=owner.getUUID();
                PocketService.reanchor(owner,id,restored,remote);var after=PocketService.ledger(server).get(id);
                check(h,after.cell()==before.cell()&&after.epoch()>before.epoch()&&id.equals(restored.pocket),"Reanchor retained the original physical cell");
                PocketService.enter(owner,restored);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(310,()->{
            try{
                check(h,PocketService.inPocket(owner.level()),"Reanchored entrance actually works");
                PocketService.logout(owner);check(h,PocketService.lease(owner.getUUID())==null,"Logout releases lease immediately");
                check(h,PocketService.ledger(server).returning(owner.getUUID())!=null,"Logout retains unfinished return record");
                PocketService.testPlayer(owner);PocketService.login(owner);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(340,()->{
            try{
                check(h,!PocketService.inPocket(owner.level())&&PocketService.ledger(server).returning(owner.getUUID())==null,"Login hook recovers an unfinished visit without a new lease");
                check(h,PocketService.ownedTicketRequests()==0,"Recovery tickets also expired");
                var record=PocketService.ledger(server).get(id);var level=PocketService.dimension(server);var chest=(StorageEntity)level.getBlockEntity(PocketService.chestPositions(record).getFirst());
                check(h,Transactions.same(chest.getItem(0),named),"Reanchor/logout/recovery never copied or removed chest contents");
                AnyaStorage.LOG.info("ANYA_POCKET_RUNTIME_OK: actual dimension crossings; two embedded-channel ServerPlayer identities; four physical chests; components; Visit; revocation; home remote; anchor destruction/reanchor; logout/login hooks; zero final Anya-owned requests. Normal player-file save verification ran; not real-client multiplayer or forced socket disconnect testing.");
                PocketService.logout(owner);PocketService.logout(visitor);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(940,()->{
            try{
                var record=PocketService.ledger(server).get(id);var level=PocketService.dimension(server);int remaining=0;
                for(int x=(record.x()-1)>>4;x<=(record.x()+32)>>4;x++)for(int z=(record.z()-1)>>4;z<=(record.z()+32)>>4;z++)
                    if(level.getChunkSource().getChunkNow(x,z)!=null)remaining++;
                check(h,PocketService.ownedTicketRequests()==0&&remaining==0,"Engine retained destination chunks after idle cleanup window");
                AnyaStorage.LOG.info("ANYA_POCKET_UNLOAD_OK: zero Anya-owned tickets and zero of the 16 destination chunk positions loaded after 600 additional idle ticks; isolated server only, not full-pack retention proof");
                h.succeed();
            }catch(Exception e){throw new AssertionError(e);}
        });
    }
    private PocketRuntimeSmoke(){}
}
