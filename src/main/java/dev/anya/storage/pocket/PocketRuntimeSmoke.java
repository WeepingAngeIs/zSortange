package dev.anya.storage.pocket;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
    private static ServerPlayer player(Fixture h,String name,boolean operator){
        var profile=new GameProfile(UUID.randomUUID(),name);
        var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),profile,net.minecraft.server.level.ClientInformation.createDefault()){
            @Override public boolean hasPermissions(int level){return operator&&level<=2;}
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
    private static int pocketCommand(ServerPlayer actor,int permission,String action)throws CommandSyntaxException {
        return actor.getServer().getCommands().getDispatcher().execute("anya pocket "+action,
                actor.createCommandSourceStack().withPermission(permission));
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
        var server=h.getLevel().getServer();ServerPlayer owner=player(h,"PocketOwner",true),visitor=player(h,"PocketGuest",false);
        check(h,System.getProperty("anyastorage.pocketPrototype")==null,"Pocket smoke must omit the old JVM gate");
        check(h,!PocketService.enabled(),"Fresh disposable config starts with pockets off");
        check(h,pocketCommand(owner,2,"enable")==1&&PocketService.enabled(),"Operator enabled pockets through the registered command without a JVM gate");
        BlockPos hp=h.absolutePos(new BlockPos(1,1,1)),ep=h.absolutePos(new BlockPos(3,1,1));
        h.setBlock(1,1,1,Content.HUB.get());h.setBlock(3,1,1,PocketContent.ENTRANCE.get());h.setBlock(3,0,3,Blocks.STONE);h.setBlock(4,0,3,Blocks.STONE);
        StorageEntity hub=(StorageEntity)h.getLevel().getBlockEntity(hp);hub.owner=owner.getUUID();
        var entrance=(PocketContent.EntranceEntity)h.getLevel().getBlockEntity(ep);entrance.owner=owner.getUUID();
        owner.setPos(ep.getX()+.5,ep.getY(),ep.getZ()+2.5);visitor.setPos(ep.getX()+1.5,ep.getY(),ep.getZ()+2.5);
        ItemStack legacy=new ItemStack(Content.REMOTE.get());var data=BagInventory.data(legacy);data.putString("HubDimension",h.getLevel().dimension().location().toString());data.putLong("HubPos",hp.asLong());BagInventory.data(legacy,data);owner.getInventory().setItem(0,legacy);
        ItemStack remote=StorageItem.tabletInHand(owner,net.minecraft.world.InteractionHand.MAIN_HAND);
        check(h,remote.is(Content.CUPID_TABLET.get())&&BagInventory.data(remote).equals(data),"Saved Remote migrated in place with the original hub binding");
        ItemStack named=new ItemStack(Items.DIAMOND,17);named.set(DataComponents.CUSTOM_NAME,Component.literal("Conserved pocket diamonds"));owner.getInventory().setItem(5,named.copy());
        PocketService.testPlayer(owner);PocketService.testPlayer(visitor);PocketService.link(owner,entrance,remote);check(h,entrance.pocket!=null,"Persistent entrance linked");
        UUID id=entrance.pocket;var r=PocketService.ledger(server).get(id);hub.setMember(visitor.getUUID(),BasePermissions.VIEW);
        check(h,!PocketService.authorize(visitor,r,PocketLeases.Kind.VISIT,true,0).isEmpty(),"Storage viewer cannot enter without Visit");
        PocketService.enter(owner,entrance);
        h.runAfterDelay(1,()->checkDisableDuringPreparation(h,owner,id));
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
                verifyAdminTransitions(h,owner,visitor,hub,remote,record,chest,named);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(1060,()->{
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

    private static void checkDisableDuringPreparation(Fixture h,ServerPlayer owner,UUID pocketId) {
        try {
            var record=PocketService.ledger(server).get(pocketId);
            if (record.stage()==PocketLedger.Stage.RESERVED&&tick<100) {
                h.runAfterDelay((int)tick+1,()->checkDisableDuringPreparation(h,owner,pocketId));
                return;
            }
            check(h,record.stage()==PocketLedger.Stage.BUILDING,"First visit must enter durable BUILDING before the operator disable attempt");
            long revision=Config.get().revision;
            check(h,pocketCommand(owner,2,"disable")==0,"Operator disable must refuse an active room preparation");
            check(h,PocketService.enabled()&&Config.get().revision==revision&&PocketService.lease(owner.getUUID())!=null
                    &&PocketService.ledger(server).get(pocketId).stage()==PocketLedger.Stage.BUILDING,
                    "Rejected disable must retain live policy, in-flight room and lease");
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static void verifyAdminTransitions(Fixture h,ServerPlayer owner,ServerPlayer guest,StorageEntity hub,
            ItemStack remote,PocketLedger.Pocket original,StorageEntity chest,ItemStack named)throws Exception {
        UUID id=original.id();
        UUID chestIdentity=chest.existingStorageIdentity();
        check(h,chestIdentity!=null,"Original chest has a durable identity before the operator test");
        var entrance=(PocketContent.EntranceEntity)h.getLevel().getBlockEntity(BlockPos.of(original.anchor().entrance()));
        var homePosition=owner.position();
        var guestRemote=remote.copy();
        guest.getInventory().setItem(0,guestRemote);
        hub.setMember(guest.getUUID(),BasePermissions.VIEW);
        check(h,Config.setServerSetting(owner,Config.get().revision,"expansion.pocketEditing","true")&&RoomEditor.enabled(),
                "Enable editing for the atomic disable check");
        PocketService.enter(owner,entrance);
        check(h,PocketService.remote(guest,guestRemote,hub),"Bound guest Remote routes to the same pocket");

        h.runAfterDelay(370,()->{
            try {
                check(h,PocketService.inPocket(owner.level())&&guest.level()==h.getLevel(),"One player visits; the second views from home");
                var ownerView=new PocketView(owner,original,PocketLeases.Kind.VISIT);
                StoreMenu.pocket(owner,ownerView);
                check(h,owner.containerMenu instanceof StoreMenu menu&&menu.hasPocket(id),"Visitor has an open pocket menu");
                check(h,guest.containerMenu instanceof StoreMenu menu&&menu.hasPocket(id)&&menu.capacity()==216&&!menu.privateView(),
                        "Authorized guest can see the linked pocket's real capacity");
                var ownerLease=PocketService.lease(owner.getUUID());
                var guestLease=PocketService.lease(guest.getUUID());
                check(h,ownerLease!=null&&ownerLease.kind()==PocketLeases.Kind.VISIT
                        &&guestLease!=null&&guestLease.kind()==PocketLeases.Kind.REMOTE,"Both independent leases are active");
                try {
                    pocketCommand(guest,0,"disable");
                    throw new AssertionError("A nonoperator reached the pocket disable command");
                } catch (CommandSyntaxException expected) {}
                check(h,PocketService.enabled()&&PocketService.lease(guest.getUUID())!=null,"Nonoperator attempt did not change pocket access");

                check(h,pocketCommand(owner,2,"disable")==1&&!PocketService.enabled()&&!ExpansionRules.get().pocketEditing,
                        "The registered admin command disabled storage and editing together");
                check(h,owner.containerMenu==owner.inventoryMenu&&guest.containerMenu==guest.inventoryMenu,
                        "Admin disable immediately closed both viewers' menus");
                check(h,PocketService.lease(owner.getUUID())==null&&PocketService.lease(guest.getUUID())==null,
                        "Admin disable immediately released both pocket leases");
                check(h,PocketService.recovering(owner.getUUID())&&!PocketService.recovering(guest.getUUID())
                        &&PocketService.ledger(server).returning(owner.getUUID())!=null,
                        "Only the physical visitor is returned, with the durable record still present before save");
            } catch (Exception e) { throw new AssertionError(e); }
        });
        h.runAfterDelay(400,()->{
            try {
                check(h,owner.level()==h.getLevel()&&owner.position().distanceToSqr(homePosition)<0.01
                        &&PocketService.ledger(server).returning(owner.getUUID())==null,
                        "Disable returned the occupant home and cleared the record after saving");
                check(h,guest.level()==h.getLevel()&&guest.containerMenu==guest.inventoryMenu
                        &&PocketService.ownedTicketRequests()==0,"Remote guest stayed home and temporary tickets expired");
                PocketService.enter(owner,entrance);
                PocketService.remote(guest,guestRemote,hub);
                check(h,PocketService.lease(owner.getUUID())==null&&PocketService.lease(guest.getUUID())==null,
                        "Both entry and Remote storage remain closed while disabled");

                check(h,pocketCommand(owner,2,"enable")==1&&PocketService.enabled()&&!ExpansionRules.get().pocketEditing,
                        "Re-enable restored storage without independently restoring edit access");
                PocketService.enter(owner,entrance);
                PocketService.remote(guest,guestRemote,hub);
            } catch (Exception e) { throw new AssertionError(e); }
        });
        h.runAfterDelay(430,()->{
            try {
                check(h,PocketService.inPocket(owner.level())&&guest.containerMenu instanceof StoreMenu menu
                        &&menu.hasPocket(id)&&menu.capacity()==216,"The same entrance and linked Remote work again after re-enable");
                var reopened=PocketService.ledger(server).get(id);
                check(h,original.equals(reopened)&&id.equals(entrance.pocket)&&id.equals(BagInventory.data(guestRemote).getUUID("PocketId")),
                        "Disable and re-enable preserve the linked room identity, cell, anchor and epoch");
                var view=new PocketView(owner,reopened,PocketLeases.Kind.VISIT);
                StorageEntity keptChest=(StorageEntity)view.containers().getFirst();
                check(h,chestIdentity.equals(keptChest.existingStorageIdentity())&&Transactions.same(keptChest.getItem(0),named),
                        "Existing physical chest, item count and components survived both policy transitions");
                PocketService.recover(owner);
                guest.closeContainer();
            } catch (Exception e) { throw new AssertionError(e); }
        });
        h.runAfterDelay(460,()->{
            check(h,owner.level()==h.getLevel()&&PocketService.lease(owner.getUUID())==null
                    &&PocketService.lease(guest.getUUID())==null&&PocketService.ownedTicketRequests()==0,
                    "The reopened pocket released both leases and all owned ticket requests");
            PocketService.logout(owner);
            PocketService.logout(guest);
            AnyaStorage.LOG.info("ANYA_POCKET_ADMIN_TRANSITIONS_OK: operator commands; rejected disable during BUILDING; active visit and remote leases closed; safe return; re-enable kept pocket, chest and item identity");
        });
    }
    private PocketRuntimeSmoke(){}
}
