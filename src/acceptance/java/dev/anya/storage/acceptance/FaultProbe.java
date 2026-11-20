package dev.anya.storage.acceptance;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Test-only forced-process cut points, never part of the playable JAR. */
@EventBusSubscriber(modid=Acceptance.ID)
public final class FaultProbe {
    private static final UUID OWNER=UUID.nameUUIDFromBytes("OfflinePlayer:AnyaQAFault".getBytes(StandardCharsets.UTF_8));
    private static final BlockPos HUB=new BlockPos(1,81,1),ENTRANCE=new BlockPos(3,81,1);
    private static MinecraftServer server;
    private static ServerPlayer player;
    private static PocketContent.EntranceEntity entrance;
    private static UUID id;
    private static String mode;
    private static int ticks,step;
    private static boolean running;
    static boolean active(){return !System.getProperty("anya.fault","").isBlank();}
    private static void check(boolean value,String message){Acceptance.require(value,message);}
    private static ServerPlayer player(MinecraftServer s){
        var profile=new GameProfile(OWNER,"AnyaQAFault");
        var p=new ServerPlayer(s,s.overworld(),profile,ClientInformation.createDefault());
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND){
            @Override public boolean isMemoryConnection(){return true;}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener callback,boolean flush){}
        };
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        s.getPlayerList().placeNewPlayer(connection,p,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false));return p;
    }
    @SubscribeEvent public static void start(ServerStartedEvent e)throws Exception{
        if(!active())return;server=e.getServer();mode=System.getProperty("anya.fault");
        check(Files.isRegularFile(Path.of(".anya-disposable-fixture")),"Refusing fault probe without disposable-directory marker");
        Acceptance.verifyJar("fault-"+mode);ExpansionRules.get().pocketStorage=true;
        boolean seed=mode.endsWith("seed");
        if(seed){
            check(!Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Seed requires a new world");
            for(int x=-3;x<12;x++)for(int z=-3;z<12;z++)server.overworld().setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());
            server.overworld().setDefaultSpawnPos(new BlockPos(4,81,4),180);
            server.overworld().setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());server.overworld().setBlockAndUpdate(ENTRANCE,PocketContent.ENTRANCE.get().defaultBlockState());
        }else{check(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Verification requires the interrupted fixture world");server.overworld().getChunk(0,0);}
        player=player(server);entrance=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(ENTRANCE);
        check(entrance!=null,"Saved entrance missing");
        if(seed){
            var hub=(StorageEntity)server.overworld().getBlockEntity(HUB);hub.owner=OWNER;entrance.owner=OWNER;
            player.teleportTo(server.overworld(),3.5,81,3.5,180,0);player.getInventory().clearContent();
            ItemStack remote=new ItemStack(Content.REMOTE.get());var tag=BagInventory.data(remote);tag.putString("HubDimension","minecraft:overworld");tag.putLong("HubPos",HUB.asLong());BagInventory.data(remote,tag);player.getInventory().setItem(0,remote);
            PocketService.link(player,entrance,remote);
        }
        id=entrance.pocket;check(id!=null,"Missing persistent pocket ID");running=true;
    }
    private static PocketLedger.Pocket record()throws Exception{return PocketService.ledger(server).get(id);}
    private static StorageEntity chest()throws Exception{var p=PocketService.chestPositions(record()).getFirst();var l=PocketService.dimension(server);l.getChunk(p.getX()>>4,p.getZ()>>4);return (StorageEntity)l.getBlockEntity(p);}
    private static ItemStack sample(){ItemStack s=new ItemStack(Items.EMERALD,13);s.set(DataComponents.CUSTOM_NAME,Component.literal("Crash-cut conserved sample"));return s;}
    private static int count(){int n=0;for(int i=0;i<player.getInventory().getContainerSize();i++)if(player.getInventory().getItem(i).is(Items.EMERALD))n+=player.getInventory().getItem(i).getCount();return n;}
    private static void cut(String marker){Acceptance.marker(marker,"mode="+mode+"; Runtime.halt(77), no shutdown hook");Runtime.getRuntime().halt(77);}
    private static void pass(String marker)throws Exception{running=false;Acceptance.marker(marker,"Verified "+mode+" with packaged JAR; server-side fixture identity, not a network client");AnyaStorage.LOG.info("{}",marker);PocketService.logout(player);server.saveEverything(true,true,true);server.halt(false);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
        if(!running||e.getServer()!=server)return;ticks++;
        try{
            if(ticks>900)throw new IllegalStateException("Fault probe timeout: "+mode+" "+PocketService.status());
            if(mode.equals("prepare-seed")){
                if(step==0&&ticks>15){PocketService.enter(player,entrance);step=1;}
                if(step==1&&record().stage()==PocketLedger.Stage.BUILDING){
                    var r=record();var l=PocketService.dimension(server);BlockPos first=new BlockPos(r.x()-1,64,r.z()-1);
                    check(!l.isEmptyBlock(first),"No partial shell exists at the cut point");
                    server.saveEverything(true,true,true);cut("CUT_PREPARATION");
                }
            }else if(mode.equals("prepare-verify")&&ticks==25){
                var r=record();check(r.stage()==PocketLedger.Stage.BUILDING,"Interrupted stage was silently promoted/reset");
                var l=PocketService.dimension(server);BlockPos first=new BlockPos(r.x()-1,64,r.z()-1);l.getChunk(first.getX()>>4,first.getZ()>>4);
                var before=l.getBlockState(first);check(!before.isAir(),"Saved shell prefix lost");
                player.teleportTo(server.overworld(),3.5,81,3.5,180,0);PocketService.enter(player,entrance);
                check(PocketService.lease(OWNER)==null&&PocketService.ownedTicketRequests()==0,"Interrupted BUILDING unexpectedly acquired a lease");
                check(record().stage()==PocketLedger.Stage.BUILDING&&l.getBlockState(first)==before,"Interrupted cell was regenerated");
                pass("ANYA_INTERRUPTED_PREPARATION_OK");
            }else if(mode.equals("transfer-seed")){
                if(step==0&&ticks>15){PocketService.enter(player,entrance);step=1;}
                if(step==1&&PocketService.inPocket(player.level())&&record().stage()==PocketLedger.Stage.READY){
                    var c=chest();c.setItem(0,sample());server.saveEverything(true,true,true);
                    var main=new MainInventory(player);var view=new PocketView(player,record(),PocketLeases.Kind.VISIT);
                    var plan=new Transactions.Plan(List.of(c,main),view.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));
                    check(plan.move(c,0,List.of(main),13)==13,"Prepared transfer did not fit");check(Transactions.same(c.getItem(0),sample())&&count()==0,"Preparation mutated actual containers");
                    cut("CUT_BEFORE_TRANSFER_COMMIT");
                }
            }else if(mode.equals("transfer-commit")){
                if(step==0&&ticks>30&&!PocketService.inPocket(player.level())){
                    check(Transactions.same(chest().getItem(0),sample())&&count()==0,"Uncommitted cut lost or duplicated items");
                    Acceptance.marker("ANYA_UNCOMMITTED_TRANSFER_RESTART_OK","prepared plan was not replayed");
                    player.teleportTo(server.overworld(),3.5,81,3.5,180,0);PocketService.enter(player,entrance);step=1;
                }
                if(step==1&&PocketService.inPocket(player.level())){
                    var c=chest();var main=new MainInventory(player);var view=new PocketView(player,record(),PocketLeases.Kind.VISIT);
                    var plan=new Transactions.Plan(List.of(c,main),view.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));check(plan.move(c,0,List.of(main),13)==13&&plan.commit(server).isEmpty(),"Committed transfer failed");
                    check(c.isEmpty()&&count()==13,"Committed conservation failed");server.saveEverything(true,true,true);cut("CUT_AFTER_TRANSFER_SAVE");
                }
            }else if(mode.equals("transfer-verify")&&ticks>30&&!PocketService.inPocket(player.level())){
                check(chest().isEmpty()&&count()==13,"Saved transfer cut lost or duplicated items");
                boolean named=false;for(int i=0;i<player.getInventory().getContainerSize();i++)if(Transactions.same(player.getInventory().getItem(i),sample()))named=true;check(named,"Transfer restart lost components");
                pass("ANYA_SAVED_TRANSFER_RESTART_OK");
            }
        }catch(Throwable ex){running=false;Acceptance.marker("FAILED-fault-"+mode,ex.toString());AnyaStorage.LOG.error("ANYA_FAULT_PROBE_FAILED "+mode,ex);server.halt(false);}
    }
}
