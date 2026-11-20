package dev.anya.storage.ticketexperiment;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded engine load fixture, NOT real connected clients. No user directory is accepted. */
@EventBusSubscriber(modid=RemoteTickets.ID)
public final class ChurnProbe {
    private interface Work {void run()throws Exception;}
    private interface Ready {boolean get()throws Exception;}
    private record Stage(String name,Work start,Ready ready,Work verify){}
    private static final ArrayDeque<Stage> STAGES=new ArrayDeque<>();
    private static final List<ServerPlayer> OWNERS=new ArrayList<>(),GUESTS=new ArrayList<>();
    private static final List<StorageEntity> HUBS=new ArrayList<>();
    private static final List<PocketContent.EntranceEntity> ENTRANCES=new ArrayList<>();
    private static final List<ItemStack> REMOTES=new ArrayList<>();
    private static final List<UUID> IDS=new ArrayList<>();
    private static final Map<UUID,Long> DEADLINES=new HashMap<>(),WAITING=new LinkedHashMap<>();
    private static final Map<UUID,List<Long>> ATTEMPTS=new LinkedHashMap<>();
    private static final BlockPos SPAWN=new BlockPos(-1024,81,-1024);
    private static MinecraftServer server;
    private static boolean running,contention;
    private static long tick,since,checks,totalWrites;
    private static int attemptsThisTick,peakActive,peakLeases,peakRequests,peakPending,peakCache;
    private static Stage stage;
    public static boolean active(){return Boolean.getBoolean("anya.efficientChurn");}
    private static void check(boolean v,String m){checks++;if(!v)throw new IllegalStateException(m);}
    private static void event(String m){RemoteTickets.marker("churn-events.csv",tick+","+RemoteMetrics.phase+",\""+m.replace('"','\'')+"\"\n",true);AnyaStorage.LOG.info("ANYA_CHURN {} {} {}",tick,RemoteMetrics.phase,m);}
    private static PocketLedger.Pocket room(int i)throws Exception{return PocketService.ledger(server).get(IDS.get(i));}
    private static boolean inside(ServerPlayer p,int i)throws Exception{var r=room(i);return PocketService.inPocket(p.level())&&r.contains(p.blockPosition().getX(),p.blockPosition().getY(),p.blockPosition().getZ());}
    private static boolean outside(ServerPlayer p){return !PocketService.inPocket(p.level())&&!PocketService.recovering(p.getUUID());}
    private static boolean viewed(ServerPlayer p,int i){return p.containerMenu instanceof StoreMenu m&&m.hasPocket(IDS.get(i));}
    private static boolean ready(ServerPlayer p,int i)throws Exception{var l=PocketService.lease(p.getUUID());return l!=null&&l.pocket().equals(IDS.get(i))&&(l.kind()==PocketLeases.Kind.VISIT?inside(p,i):viewed(p,i));}
    private static void home(ServerPlayer p,int i,boolean high){var pos=ENTRANCES.get(i).getBlockPos();p.teleportTo(server.overworld(),pos.getX()+.5,high?88:81,pos.getZ()+.5+(high?0:2),0,0);}
    private static void remote(ServerPlayer p,int i)throws Exception{p.closeContainer();home(p,i,false);p.getInventory().setItem(0,REMOTES.get(i).copy());PocketService.remote(p,p.getInventory().getItem(0),HUBS.get(i));var l=PocketService.lease(p.getUUID());check(l!=null,"Remote admission denied "+i);DEADLINES.put(p.getUUID(),l.deadline());WAITING.put(p.getUUID(),tick);}
    private static void enter(ServerPlayer p,int i)throws Exception{p.closeContainer();check(PocketService.lease(p.getUUID())==null,"Previous view must close before a new visit");DEADLINES.remove(p.getUUID());home(p,i,true);PocketService.enter(p,ENTRANCES.get(i));check(PocketService.lease(p.getUUID())!=null,"Visit admission denied "+i);WAITING.put(p.getUUID(),tick);rank(i,true);}
    private static void rank(int i,boolean strong){int expected=strong||!RemoteTickets.candidate()?2:0;check(RemoteTickets.destination(IDS.get(i),expected)==16&&RemoteTickets.destination(IDS.get(i),expected==0?2:0)==0,"Incorrect destination rank for pocket "+i+" strong="+strong);}
    private static void deadlines(){for(var e:DEADLINES.entrySet()){var l=PocketService.lease(e.getKey());if(l!=null)check(l.deadline()==e.getValue(),"Independent remote deadline reset");}}
    private static ItemStack sample(int i){var s=new ItemStack(Items.EMERALD,i+11);s.set(DataComponents.CUSTOM_NAME,Component.literal("Churn conserved sample "+i));return s;}
    private static StorageEntity chest(int i)throws Exception{var pos=PocketService.chestPositions(room(i)).getFirst();var l=PocketService.dimension(server);check(l.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)!=null,"Inspection would load missing destination");return (StorageEntity)l.getBlockEntity(pos);}
    private static void contents(int i)throws Exception{var c=chest(i);check(c!=null&&IDS.get(i).equals(c.pocketId)&&c.owner.equals(room(i).owner())&&Transactions.same(c.getItem(0),sample(i)),"Physical sample or identity changed "+i);}
    private static void allContents()throws Exception{for(int i=0;i<8;i++)contents(i);}
    private static boolean guestsOutside(){for(var p:GUESTS)if(!outside(p))return false;return true;}
    private static boolean guestsInside()throws Exception{for(int i=0;i<8;i++)if(!inside(GUESTS.get(i),i))return false;return true;}
    private static boolean allRemote()throws Exception{for(int i=0;i<8;i++)if(!ready(OWNERS.get(i),i)||!ready(GUESTS.get(i),i))return false;return true;}
    private static void ownerViews(){for(int i=0;i<8;i++)check(viewed(OWNERS.get(i),i),"Independent owner view closed "+i);deadlines();}
    private static void takePut()throws Exception{for(int i=0;i<8;i++){
        var p=OWNERS.get(i);var c=chest(i);var main=new MainInventory(p);var view=new PocketView(p,room(i),PocketLeases.Kind.REMOTE);
        var take=new Transactions.Plan(List.of(c,main),view.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));
        check(take.move(c,0,List.of(main),1)==1&&take.commit(server).isEmpty(),"Guarded withdrawal failed");
        int slot=-1;for(int j=0;j<36;j++)if(main.getItem(j).is(Items.EMERALD))slot=j;check(slot>=0,"Withdrawn item missing");
        var put=new Transactions.Plan(List.of(c,main),view.guard(BasePermissions.DEPOSIT));check(put.move(main,slot,List.of(c),1)==1&&put.commit(server).isEmpty(),"Guarded redeposit failed");contents(i);
    }event("Eight guarded withdrawal/redeposit pairs conserved named samples; server API fixture, not packets");}
    private static ServerPlayer player(int i,boolean guest){
        var profile=new GameProfile(UUID.nameUUIDFromBytes(((guest?"ChurnGuest":"ChurnOwner")+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)),(guest?"ChurnGuest":"ChurnOwner")+i);
        var p=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault()){@Override public boolean hasPermissions(int ignored){return false;}};
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND){
            @Override public boolean isMemoryConnection(){return true;}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener listener,boolean flush){}
        };
        new io.netty.channel.embedded.EmbeddedChannel(connection);net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection,p,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false));p.getInventory().clearContent();return p;
    }
    private static void setup()throws Exception{
        var w=server.overworld();for(int x=-4;x<21;x++)for(int z=-4;z<21;z++){
            w.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());w.setBlockAndUpdate(new BlockPos(x,87,z),Blocks.STONE.defaultBlockState());
        }
        server.overworld().setDefaultSpawnPos(new BlockPos(8,81,8),0);
        for(int i=0;i<9;i++){
            var p=player(i,false);OWNERS.add(p);var hp=new BlockPos(1+(i%3)*4,81,1+(i/3)*4);var ep=hp.east();
            w.setBlockAndUpdate(hp,Content.HUB.get().defaultBlockState());w.setBlockAndUpdate(ep,PocketContent.ENTRANCE.get().defaultBlockState());
            var h=(StorageEntity)w.getBlockEntity(hp);h.owner=p.getUUID();h.setChanged();var en=(PocketContent.EntranceEntity)w.getBlockEntity(ep);en.owner=p.getUUID();en.setChanged();HUBS.add(h);ENTRANCES.add(en);home(p,i,false);
            var remote=new ItemStack(Content.REMOTE.get());var tag=BagInventory.data(remote);tag.putString("HubDimension","minecraft:overworld");tag.putLong("HubPos",hp.asLong());BagInventory.data(remote,tag);p.getInventory().setItem(0,remote);PocketService.link(p,en,remote);
            check(en.pocket!=null,"Fixture allocation failed");IDS.add(en.pocket);REMOTES.add(remote);
            if(i<8){var g=player(i,true);GUESTS.add(g);home(g,i,false);h.setMember(g.getUUID(),BasePermissions.VISIT|BasePermissions.LEGACY);}
        }
        // Unsafe fallback is separate from all homes and is never used for initial login.
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)w.setBlockAndUpdate(SPAWN.offset(x,-1,z),Blocks.MAGMA_BLOCK.defaultBlockState());
        w.setDefaultSpawnPos(SPAWN,0);check(RemoteTickets.leases().snapshot().isEmpty(),"Setup acquired unintended lease");
    }
    private static void denyContender(){var p=OWNERS.get(8);home(p,8,true);PocketService.enter(p,ENTRANCES.get(8));check(PocketService.lease(p.getUUID())==null,"Over-cap contender admitted");}
    private static void drain(){for(var p:server.getPlayerList().getPlayers())if(PocketService.inPocket(p.level()))PocketService.recover(p);else{p.closeContainer();PocketService.logout(p);}}
    private static boolean drained(){if(!RemoteTickets.leases().snapshot().isEmpty()||PocketService.ownedTicketRequests()!=0)return false;for(var p:server.getPlayerList().getPlayers())if(!outside(p))return false;return true;}
    private static void clean()throws Exception{check(drained()&&RemoteTickets.applied().isEmpty(),"Final lease/ticket/recovery still active");check(PocketService.ledger(server).returnCount()==0,"Unfinished returns remain");RemoteTickets.auditEngine();check(PocketService.dimension(server).getChunkSource().getLoadedChunksCount()==0,"Final engine destination cache nonzero");}
    public static void attempt(ServerPlayer p){if(!running)return;attemptsThisTick++;if(contention){ATTEMPTS.computeIfAbsent(p.getUUID(),k->new ArrayList<>()).add(tick);event("RECOVERY_ATTEMPT "+p.getGameProfile().getName());}}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void pre(ServerTickEvent.Pre e){if(running)attemptsThisTick=0;}
    private static void add(String n,Work s,Ready r,Work v){STAGES.add(new Stage(n,s,r,v));}
    private static void hold(String n,int t,Work v){add(n,()->{},()->tick-since>=t,v);}
    private static void scenarios(){
        hold("empty-baseline",200,()->check(PocketService.dimension(server).getChunkSource().getLoadedChunksCount()==0,"Empty destination not zero"));
        add("setup-seventeen-fixtures",ChurnProbe::setup,()->true,()->{});
        hold("home-baseline",200,()->{});
        add("cold-maxcap-visits",()->{for(int i=0;i<8;i++){enter(OWNERS.get(i),i);enter(GUESTS.get(i),i);}denyContender();},()->{for(int i=0;i<8;i++)if(!ready(OWNERS.get(i),i)||!ready(GUESTS.get(i),i))return false;return true;},()->{for(int i=0;i<8;i++){chest(i).setItem(0,sample(i));rank(i,true);}check(WAITING.isEmpty(),"A queued fixture starved");});
        hold("cold-visit-steady",300,ChurnProbe::allContents);
        add("initial-visitors-return",()->{GUESTS.forEach(PocketService::recover);OWNERS.subList(0,8).forEach(PocketService::recover);},()->{if(!guestsOutside())return false;for(int i=0;i<8;i++)if(!outside(OWNERS.get(i)))return false;return true;},()->{for(int i=0;i<8;i++)remote(OWNERS.get(i),i);});
        add("initial-owner-remotes",()->{},()->{for(int i=0;i<8;i++)if(!ready(OWNERS.get(i),i))return false;return true;},ChurnProbe::ownerViews);
        hold("remote-steady-0",500,()->{ownerViews();allContents();for(int i=0;i<8;i++)rank(i,false);takePut();});
        for(int cycle=1;cycle<=3;cycle++){final int c=cycle;
            add("churn-visits-"+c,()->{for(int i=0;i<8;i++)enter(GUESTS.get(i),i);},ChurnProbe::guestsInside,()->{ownerViews();for(int i=0;i<8;i++)rank(i,true);});
            hold("mixed-eight-steady-"+c,200,ChurnProbe::allContents);
            add("half-visitors-leave-"+c,()->{for(int i=0;i<4;i++)PocketService.recover(GUESTS.get(i));},()->{for(int i=0;i<4;i++)if(!outside(GUESTS.get(i)))return false;return true;},ChurnProbe::ownerViews);
            hold("mixed-four-steady-"+c,200,()->{for(int i=0;i<8;i++)rank(i,i>=4);allContents();});
            add("remaining-visitors-leave-"+c,()->{for(int i=4;i<8;i++)PocketService.recover(GUESTS.get(i));},ChurnProbe::guestsOutside,ChurnProbe::ownerViews);
            hold("remote-steady-"+c,500,()->{for(int i=0;i<8;i++)rank(i,false);ownerViews();takePut();});
        }
        add("shared-sixteen-remotes",()->{for(int i=0;i<8;i++)remote(GUESTS.get(i),i);},ChurnProbe::allRemote,()->{check(RemoteTickets.applied().size()==136,"Shared viewers duplicated requests");ExpansionRules.get().activePocketLimit=1;});
        add("lowered-cap-owner-close",()->OWNERS.subList(0,8).forEach(p->p.closeContainer()),()->{for(int i=0;i<8;i++)if(PocketService.lease(OWNERS.get(i).getUUID())!=null)return false;return true;},()->{deadlines();denyContender();for(int i=0;i<8;i++)remote(OWNERS.get(i),i);});
        add("shared-readmission-under-cap",()->{},ChurnProbe::allRemote,()->{deadlines();allContents();ExpansionRules.get().activePocketLimit=8;});
        add("release-one-pocket",()->{OWNERS.get(0).closeContainer();GUESTS.get(0).closeContainer();},()->PocketService.lease(OWNERS.get(0).getUUID())==null&&PocketService.lease(GUESTS.get(0).getUUID())==null,()->enter(OWNERS.get(8),8));
        add("fair-contender-completes",()->{},()->inside(OWNERS.get(8),8),()->{for(int i=1;i<8;i++){check(viewed(OWNERS.get(i),i)&&viewed(GUESTS.get(i),i),"Contender broke another pocket");contents(i);}check(WAITING.isEmpty(),"Contender still pending");PocketService.recover(OWNERS.get(8));});
        add("return-contender-restore-original",()->{},()->outside(OWNERS.get(8)),()->{remote(OWNERS.get(0),0);remote(GUESTS.get(0),0);});
        add("all-remotes-restored",()->{},ChurnProbe::allRemote,ChurnProbe::allContents);
        add("revoke-shared-view",()->HUBS.get(3).removeMember(GUESTS.get(3).getUUID()),()->PocketService.lease(GUESTS.get(3).getUUID())==null&&PocketService.lease(OWNERS.get(3).getUUID())==null,()->{
            var p=GUESTS.get(3);PocketService.remote(p,p.getInventory().getItem(0),HUBS.get(3));check(PocketService.lease(p.getUUID())==null,"Revoked remote reacquired");HUBS.get(3).setMember(p.getUUID(),BasePermissions.VISIT|BasePermissions.LEGACY);remote(OWNERS.get(3),3);remote(p,3);
        });
        add("post-revocation-restored",()->{},ChurnProbe::allRemote,ChurnProbe::allContents);
        add("prepare-contended-visits",()->{for(int i=0;i<8;i++){GUESTS.get(i).closeContainer();enter(GUESTS.get(i),i);}},ChurnProbe::guestsInside,ChurnProbe::ownerViews);
        add("eight-unsafe-recoveries",()->{
            var w=server.overworld();for(int x=-4;x<21;x++)for(int z=-4;z<21;z++)w.setBlockAndUpdate(new BlockPos(x,87,z),Blocks.MAGMA_BLOCK.defaultBlockState());
            ATTEMPTS.clear();contention=true;GUESTS.forEach(PocketService::recover);
        },()->tick-since>=240,()->{
            check(ATTEMPTS.size()==8,"Recovery contention starved a user");
            long firstMin=Long.MAX_VALUE,firstMax=Long.MIN_VALUE;
            for(var p:GUESTS){var attempts=ATTEMPTS.get(p.getUUID());check(attempts!=null&&attempts.size()>=3,"Too few unsafe attempts");for(int n=1;n<attempts.size();n++)check(attempts.get(n)-attempts.get(n-1)==100,"Unsafe retry cadence changed");firstMin=Math.min(firstMin,attempts.getFirst());firstMax=Math.max(firstMax,attempts.getFirst());check(PocketService.recovering(p.getUUID())&&PocketService.lease(p.getUUID())==null&&PocketService.ledger(server).returning(p.getUUID())!=null,"Unsafe return state lost");}
            check(firstMax-firstMin==7,"Recovery queue failed bounded FIFO opportunity");for(int i=0;i<8;i++)rank(i,true);ownerViews();allContents();event("RECOVERY_FAIR eight users attempted in eight ticks; 100-tick retries, occupants protect stronger tickets");
            for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)server.overworld().setBlockAndUpdate(SPAWN.offset(x,-1,z),Blocks.STONE.defaultBlockState());
        });
        add("repaired-fallback-drains",()->{},ChurnProbe::guestsOutside,()->{contention=false;check(tick-since<=110,"Recovery after repair exceeded retry bound");ownerViews();allContents();check(PocketService.ledger(server).returnCount()==0,"Completed return records retained");});
        hold("after-contention-remote-steady",500,()->{for(int i=0;i<8;i++)rank(i,false);ownerViews();takePut();});
        add("drain-all",ChurnProbe::drain,ChurnProbe::drained,()->{});
        hold("final-idle",650,()->{clean();event("CLEAN_ZERO exact engine tickets and destination cache");RemoteTickets.marker("PASSED","activeCeiling="+peakActive+" leases="+peakLeases+" requests="+peakRequests+" cache="+peakCache+" pending="+peakPending+" shellWrites="+totalWrites+" checks="+checks+"\nserverFixtures=17 actualClientJVMs=0\n",false);running=false;server.saveEverything(true,true,true);server.halt(false);});
    }
    @SubscribeEvent public static void start(ServerStartedEvent e)throws Exception{
        if(!active())return;server=e.getServer();
        check("true".equals(System.getenv("GITHUB_ACTIONS"))&&Files.isRegularFile(Path.of(".anya-churn-fixture")),"Disposable CI fixture required");
        check(!Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Fresh world required");
        var jar=ModList.get().getModFileById("anyastorage").getFile().getFilePath();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));check(hash.equals("5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11"),"Playable JAR changed");
        var rules=ExpansionRules.get();rules.pocketStorage=true;rules.pocketEditing=false;rules.activePocketLimit=8;rules.pocketLimit=9;rules.pocketSessionTicks=12000;
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);server.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,server);
        scenarios();running=true;event("PACKAGED_READY sha256="+hash+" embedded sinks, zero network/client sessions");
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void post(ServerTickEvent.Post e){
        if(!running||e.getServer()!=server)return;tick++;
        try{
            int active=RemoteTickets.leases().active().size(),people=RemoteTickets.leases().snapshot().size(),requests=PocketService.ownedTicketRequests();int pending=((Map<?,?>)RemoteTickets.field("PENDING")).size();int writes=(int)RemoteTickets.field("lastPreparedBlocks");
            peakActive=Math.max(peakActive,active);peakLeases=Math.max(peakLeases,people);peakRequests=Math.max(peakRequests,requests);peakPending=Math.max(peakPending,pending);peakCache=Math.max(peakCache,PocketService.dimension(server).getChunkSource().getLoadedChunksCount());totalWrites+=writes;
            check(active<=8&&people<=16&&requests<=154&&writes<=256&&attemptsThisTick<=1,"Production resource/work budget exceeded");
            var metrics=RemoteTickets.selectionMetrics();check(metrics[2]<=metrics[0]*32&&metrics[3]<=metrics[0]*8,"Eligibility scans exceeded two lease passes/one per-pocket lookup per sync");
            for(var it=WAITING.entrySet().iterator();it.hasNext();){var v=it.next();var p=server.getPlayerList().getPlayer(v.getKey());var l=PocketService.lease(v.getKey());if(l!=null&&ready(p,IDS.indexOf(l.pocket()))){event("ADMITTED "+p.getGameProfile().getName()+" waitTicks="+(tick-v.getValue()));it.remove();}}
            if(stage==null){stage=STAGES.poll();check(stage!=null,"No terminal checkpoint");since=tick;RemoteMetrics.phase=stage.name();event("START");stage.start().run();}
            if(tick-since>1800)throw new IllegalStateException("Stage timeout "+stage.name());
            if(stage.ready().get()){stage.verify().run();RemoteTickets.auditEngine();event("PASS");stage=null;}
        }catch(Throwable ex){running=false;RemoteTickets.marker("FAILED-churn",stage+"\n"+ex,false);AnyaStorage.LOG.error("ANYA_CHURN_FAILED",ex);server.halt(false);}
    }
    private ChurnProbe(){}
}
