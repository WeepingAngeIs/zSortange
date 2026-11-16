package dev.anya.storage.profiling;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.*;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Disposable packaged-server engine profile. Fixture identities have no actual client JVMs/sockets. */
@Mod(ProfileProbe.ID)
@EventBusSubscriber(modid=ProfileProbe.ID)
public final class ProfileProbe {
    public static final String ID="anyaprofile";
    private static MinecraftServer server;
    private static boolean running,stopping;
    private static Path out;
    private static BufferedWriter ticksFile,holdersFile,eventsFile;
    private static final ThreadMXBean THREAD=ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    private static long begin,cpuBegin,processLast,wallLast,serviceStart,serviceTime,tick;
    private static int chunkTicks,peakCache,peakRequests,peakLeases,peakActive,peakWrites,ceiling,hotHeap;
    private static long peakHeap;
    private static final Map<String,Field> FIELDS=new HashMap<>();
    private static final List<ServerPlayer> owners=new ArrayList<>(),guests=new ArrayList<>();
    private static final List<StorageEntity> hubs=new ArrayList<>();
    private static final List<PocketContent.EntranceEntity> entrances=new ArrayList<>();
    private static final List<ItemStack> remotes=new ArrayList<>();
    private static final List<UUID> ids=new ArrayList<>();
    private static final Map<UUID,Long> waiting=new LinkedHashMap<>();
    private static final Set<UUID> seeded=new HashSet<>();
    private static int step=-1;
    private static long since;
    private static String phase="startup";
    @FunctionalInterface private interface Work {void run()throws Exception;}
    @FunctionalInterface private interface Ready {boolean get()throws Exception;}
    private record Stage(String name,Work start,Ready ready,Work verify){}
    private static final List<Stage> stages=new ArrayList<>();
    public ProfileProbe(){
        if(!Boolean.getBoolean("anya.profile")||!"true".equals(System.getenv("GITHUB_ACTIONS"))||!Files.isRegularFile(Path.of(".anya-profile-fixture")))throw new IllegalStateException("Profiler refuses non-disposable/profile launch");
    }
    public static int ticketDistance(int old){return Boolean.getBoolean("anya.profileFullOnly")?0:old;}
    public static void chunkTick(ServerLevel l){if(running&&PocketService.inPocket(l))chunkTicks++;}
    public static void serviceBegin(){if(running)serviceStart=System.nanoTime();}
    public static void serviceEnd(){if(running)serviceTime+=System.nanoTime()-serviceStart;}
    private static void check(boolean test,String message){if(!test)throw new IllegalStateException(message);}
    private static Object field(String name)throws Exception{
        Field f=FIELDS.get(name);if(f==null){f=PocketService.class.getDeclaredField(name);f.setAccessible(true);FIELDS.put(name,f);}return f.get(null);
    }
    private static PocketLeases leases()throws Exception{return (PocketLeases)field("LEASES");}
    private static PocketLedger.Pocket room(int i)throws Exception{return PocketService.ledger(server).get(ids.get(i));}
    private static boolean inside(ServerPlayer p,int i)throws Exception{return PocketService.inPocket(p.level())&&room(i).contains(p.blockPosition().getX(),p.blockPosition().getY(),p.blockPosition().getZ());}
    private static void event(String text)throws Exception{eventsFile.write(tick+","+phase+",\""+text.replace("\"","'")+"\"\n");eventsFile.flush();AnyaStorage.LOG.info("ANYA_PROFILE {} {} {}",tick,phase,text);}
    private static Method method(Class<?> c,String n)throws Exception{for(Class<?> k=c;k!=null;k=k.getSuperclass())try{Method m=k.getDeclaredMethod(n);m.setAccessible(true);return m;}catch(NoSuchMethodException ignored){}throw new NoSuchMethodException(c.getName()+"."+n);}
    private static Method chunks,fullFuture,tickFuture,entityFuture;
    private static boolean ready(Object holder,Method m)throws Exception{
        Object value=((CompletableFuture<?>)m.invoke(holder)).getNow(null);
        return value instanceof ChunkResult<?> result&&result.orElse(null)!=null;
    }
    private static void holderSample()throws Exception{
        long start=System.nanoTime();var cache=PocketService.dimension(server).getChunkSource();Object map=cache.chunkMap;
        if(chunks==null){chunks=method(map.getClass(),"getChunks");
            fullFuture=method(ChunkHolder.class,"getFullChunkFuture");tickFuture=method(ChunkHolder.class,"getTickingChunkFuture");entityFuture=method(ChunkHolder.class,"getEntityTickingChunkFuture");
            Files.writeString(out.resolve("api-contract.txt"),chunks+"\n"+fullFuture+"\n"+tickFuture+"\n"+entityFuture+"\nActual work counter: required mixin at ServerLevel.tickChunk HEAD. Readiness is not work.\n");
        }
        int holders=0,full=0,block=0,entity=0;
        for(Object h:(Iterable<?>)chunks.invoke(map)){holders++;if(ready(h,fullFuture))full++;if(ready(h,tickFuture))block++;if(ready(h,entityFuture))entity++;}
        holdersFile.write(tick+","+phase+","+cache.getLoadedChunksCount()+","+holders+","+full+","+block+","+entity+","+(System.nanoTime()-start)+"\n");
    }
    private static int ringLoaded(int i)throws Exception{var r=room(i);var c=PocketService.dimension(server).getChunkSource();int n=0;for(int x=(r.x()-1)>>4;x<=(r.x()+32)>>4;x++)for(int z=(r.z()-1)>>4;z<=(r.z()+32)>>4;z++)if(c.getChunkNow(x,z)!=null)n++;return n;}
    private static long gcCount(){long n=0;for(var b:ManagementFactory.getGarbageCollectorMXBeans())n+=Math.max(0,b.getCollectionCount());return n;}
    private static long gcTime(){long n=0;for(var b:ManagementFactory.getGarbageCollectorMXBeans())n+=Math.max(0,b.getCollectionTime());return n;}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void pre(ServerTickEvent.Pre e){if(!running||e.getServer()!=server)return;begin=System.nanoTime();cpuBegin=THREAD.getCurrentThreadCpuTime();serviceTime=0;chunkTicks=0;}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void post(ServerTickEvent.Post e){
        if(!running||e.getServer()!=server)return;
        long end=System.nanoTime(),tickTime=end-begin,cpu=THREAD.getCurrentThreadCpuTime()-cpuBegin,process=OS.getProcessCpuTime(),period=end-wallLast,processDelta=process-processLast;
        wallLast=end;processLast=process;tick++;
        try{
            long instrumentation=System.nanoTime();var l=leases();int active=l.active().size(),users=l.snapshot().size(),requests=PocketService.ownedTicketRequests();
            int writes=(int)field("lastPreparedBlocks"),pending=((Map<?,?>)field("PENDING")).size(),jobs=((Map<?,?>)field("PREPARING")).size(),cache=PocketService.dimension(server).getChunkSource().getLoadedChunksCount();
            var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();long used=heap.getUsed(),committed=heap.getCommitted();
            peakCache=Math.max(peakCache,cache);peakRequests=Math.max(peakRequests,requests);peakLeases=Math.max(peakLeases,users);peakActive=Math.max(peakActive,active);peakWrites=Math.max(peakWrites,writes);peakHeap=Math.max(peakHeap,used);
            check(active<=8&&users<=16&&writes<=256&&jobs<=1,"Production work/user/preparation bound exceeded");check(requests<=154,"Logical ticket requests exceeded eight pockets plus bounded recovery");
            if(tick%20==0)holderSample();
            long overhead=System.nanoTime()-instrumentation;
            ticksFile.write(tick+","+phase+","+period+","+tickTime+","+cpu+","+processDelta+","+serviceTime+","+overhead+","+used+","+committed+","+gcCount()+","+gcTime()+","+active+","+users+","+requests+","+cache+","+chunkTicks+","+writes+","+pending+","+jobs+"\n");
            if(tick%100==0){ticksFile.flush();holdersFile.flush();}
            if(tick%20==0)Files.writeString(out.resolve("heartbeat"),tick+","+phase+","+System.currentTimeMillis());
            if(used>heap.getMax()*.85)hotHeap++;else hotHeap=0;
            if(!stopping&&(hotHeap>=100||cache>15000||Files.exists(out.resolve("STOP_REQUESTED")))){stopping=true;phase="safety-drain";since=tick;event("SAFE STOP requested: heap="+used+" cache="+cache+" ceiling="+ceiling);drain();}
            if(stopping){if((users==0&&!recovering())||tick-since>200)finish("LIMITED","Resource guard stopped ramp; last settled ceiling="+ceiling);return;}
            observeArrivals();
            if(step<0)advance();else {Stage s=stages.get(step);if(tick-since>2400)throw new IllegalStateException("Phase exceeded bounded 2400 ticks: "+s.name());if(s.ready().get()){s.verify().run();event("PASS");advance();}}
        }catch(Throwable failure){try{event("FAILED "+failure);finish("FAILED",failure.toString());}catch(Exception nested){AnyaStorage.LOG.error("PROFILE failed",nested);running=false;server.halt(false);}}
    }
    private static void finish(String status,String detail)throws Exception{
        Files.writeString(out.resolve(status),detail+"\nsettledCeiling="+ceiling+"\npeakActive="+peakActive+"\npeakLeases="+peakLeases+"\npeakRequests="+peakRequests+"\npeakCache="+peakCache+"\npeakShellWrites="+peakWrites+"\npeakHeapBytes="+peakHeap+"\nfixtureIdentities="+(owners.size()+guests.size())+"\nactualClientJVMs=0\n");
        running=false;ticksFile.close();holdersFile.close();eventsFile.close();server.saveEverything(true,true,true);server.halt(false);
    }
    private static void advance()throws Exception{step++;if(step>=stages.size()){finish("PASSED","Sustained maximum-cap profile and cleanup passed");return;}Stage s=stages.get(step);phase=s.name();since=tick;event("START");s.start().run();}
    private static void add(String name,Work start,Ready ready,Work verify){stages.add(new Stage(name,start,ready,verify));}
    private static void hold(String name,int duration,Work verify){add(name,()->{},()->tick-since>=duration,verify);}
    private static ServerPlayer player(int i,boolean guest){
        String name=(guest?"LoadGuest":"LoadOwner")+i;var profile=new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8)),name);
        var p=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault()){@Override public boolean hasPermissions(int ignored){return false;}};
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND){@Override public boolean isMemoryConnection(){return true;}@Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener listener,boolean flush){}};
        new io.netty.channel.embedded.EmbeddedChannel(connection);net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection,p,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false));
        p.teleportTo(server.overworld(),8.5,81,8.5,0,0);p.getInventory().clearContent();return p;
    }
    private static void setup()throws Exception{
        var world=server.overworld();for(int x=0;x<16;x++)for(int z=0;z<16;z++)world.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());world.setDefaultSpawnPos(new BlockPos(8,81,8),0);
        for(int i=0;i<9;i++){
            var p=player(i,false);owners.add(p);BlockPos h=new BlockPos(1+(i%3)*4,81,1+(i/3)*4),ep=h.east();
            world.setBlockAndUpdate(h,Content.HUB.get().defaultBlockState());world.setBlockAndUpdate(ep,PocketContent.ENTRANCE.get().defaultBlockState());
            var hub=(StorageEntity)world.getBlockEntity(h);hub.owner=p.getUUID();var en=(PocketContent.EntranceEntity)world.getBlockEntity(ep);en.owner=p.getUUID();hubs.add(hub);entrances.add(en);
            p.teleportTo(world,ep.getX()+.5,81,ep.getZ()+2.5,0,0);ItemStack r=new ItemStack(Content.REMOTE.get());var d=BagInventory.data(r);d.putString("HubDimension","minecraft:overworld");d.putLong("HubPos",h.asLong());BagInventory.data(r,d);p.getInventory().setItem(0,r);PocketService.link(p,en,r);check(en.pocket!=null,"Fixture link failed");remotes.add(r);ids.add(en.pocket);
            if(i<8){var g=player(i,true);guests.add(g);home(g,i);hub.setMember(g.getUUID(),BasePermissions.VISIT|BasePermissions.LEGACY);}
        }
        check(leases().snapshot().isEmpty(),"Setup accidentally acquired leases");
    }
    private static void home(ServerPlayer p,int i){var e=entrances.get(i).getBlockPos();p.teleportTo(server.overworld(),e.getX()+.5,81,e.getZ()+2.5,0,0);}
    private static void enter(ServerPlayer p,int i)throws Exception{home(p,i);PocketService.enter(p,entrances.get(i));check(PocketService.lease(p.getUUID())!=null,"Admission failed for "+i);waiting.put(p.getUUID(),tick);}
    private static void remote(ServerPlayer p,int i)throws Exception{home(p,i);p.getInventory().setItem(0,remotes.get(i).copy());PocketService.remote(p,p.getInventory().getItem(0),hubs.get(i));check(PocketService.lease(p.getUUID())!=null,"Remote admission failed");waiting.put(p.getUUID(),tick);}
    private static boolean arrived(ServerPlayer p,int i)throws Exception{var l=PocketService.lease(p.getUUID());return l!=null&&l.pocket().equals(ids.get(i))&&(l.kind()==PocketLeases.Kind.VISIT?inside(p,i):p.containerMenu instanceof StoreMenu m&&m.hasPocket(ids.get(i)));}
    private static void observeArrivals()throws Exception{for(var entry:new ArrayList<>(waiting.entrySet())){var p=server.getPlayerList().getPlayer(entry.getKey());var l=PocketService.lease(entry.getKey());if(l!=null){int i=ids.indexOf(l.pocket());if(arrived(p,i)){event("ADMITTED "+p.getGameProfile().getName()+" pocket="+i+" waitTicks="+(tick-entry.getValue()));waiting.remove(entry.getKey());}}}}
    private static boolean all(int n)throws Exception{for(int i=0;i<n;i++)if(!arrived(owners.get(i),i)||!arrived(guests.get(i),i))return false;return true;}
    private static ItemStack sample(int i){var s=new ItemStack(Items.EMERALD,i+1);s.set(DataComponents.CUSTOM_NAME,Component.literal("Profile sample "+i));return s;}
    private static StorageEntity chest(int i)throws Exception{var pos=PocketService.chestPositions(room(i)).getFirst();var l=PocketService.dimension(server);check(l.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)!=null,"Inspection must not load absent room");return (StorageEntity)l.getBlockEntity(pos);}
    private static void verifyRooms(int n)throws Exception{for(int i=0;i<n;i++){
        check(room(i).stage()==PocketLedger.Stage.READY&&ringLoaded(i)==16,"Room not fully prepared");var c=chest(i);check(c!=null&&c.pocketId.equals(ids.get(i)),"Wrong physical chest identity");
        if(seeded.add(ids.get(i)))c.setItem(0,sample(i));check(Transactions.same(c.getItem(0),sample(i)),"Conservation failed in pocket "+i);
    }}
    private static void denied(int i)throws Exception{var p=owners.get(i);home(p,i);PocketService.enter(p,entrances.get(i));check(PocketService.lease(p.getUUID())==null,"Over-cap room admitted");event("OVER_CAP_REFUSED pocket="+i);}
    private static boolean recovering(){for(ServerPlayer p:server.getPlayerList().getPlayers())if(PocketService.inPocket(p.level())||PocketService.recovering(p.getUUID()))return true;return false;}
    private static void drain(){for(ServerPlayer p:server.getPlayerList().getPlayers()){if(PocketService.inPocket(p.level()))PocketService.recover(p);else p.closeContainer();}}
    private static boolean drained()throws Exception{return leases().snapshot().isEmpty()&&!recovering()&&PocketService.ownedTicketRequests()==0;}
    private static void clean()throws Exception{check(drained(),"Residual user/recovery lease");for(int i=0;i<ids.size();i++)check(ringLoaded(i)==0,"Destination ring still loaded "+i);check(PocketService.dimension(server).getChunkSource().getLoadedChunksCount()==0,"Destination cache retained after cleanup");event("CLEAN_ZERO all destination cache/rings/Anya requests");}
    @SubscribeEvent public static void start(ServerStartedEvent e)throws Exception{
        server=e.getServer();out=Path.of(System.getProperty("anya.profileOut")).toAbsolutePath();Files.createDirectories(out);
        check(!Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Refusing nonfresh profiling world");
        Path jar=ModList.get().getModFileById("anyastorage").getFile().getFilePath();check(Files.isRegularFile(jar)&&jar.toString().endsWith(".jar"),"Not a packaged production JAR");String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));check(hash.equals(System.getProperty("anya.expectedHash")),"Playable JAR differs from beta.6");
        Files.writeString(out.resolve("runtime.txt"),"sha256="+hash+"\njava="+System.getProperty("java.runtime.version")+"\nvmArgs="+ManagementFactory.getRuntimeMXBean().getInputArguments()+"\nprocessors="+OS.getAvailableProcessors()+"\nphysicalMemory="+OS.getTotalMemorySize()+"\nheapMax="+Runtime.getRuntime().maxMemory()+"\nvariant="+(Boolean.getBoolean("anya.profileFullOnly")?"test-only full tickets":"unmodified beta.6 tickets")+"\nclientJVMs=0\ntransport=embedded sinks; ordinary ServerPlayer engine identities\n");
        var o=ExpansionRules.get();o.pocketStorage=true;o.pocketEditing=false;o.pocketLimit=9;o.activePocketLimit=1;o.pocketSessionTicks=12000;
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);server.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,server);
        ticksFile=Files.newBufferedWriter(out.resolve("ticks.csv"));ticksFile.write("tick,phase,period_ns,event_tick_ns,thread_cpu_ns,process_cpu_ns,service_ns,measurement_ns,heap_used,heap_committed,gc_count,gc_ms,active,leases,requests,cache,actual_tickChunk_calls,shell_writes,pending,preparing\n");
        holdersFile=Files.newBufferedWriter(out.resolve("holders.csv"));holdersFile.write("tick,phase,cache,visible_holders,full_ready,block_ticking_ready,entity_ticking_ready,sample_ns\n");eventsFile=Files.newBufferedWriter(out.resolve("events.csv"));eventsFile.write("tick,phase,event\n");
        if(THREAD.isThreadCpuTimeSupported()&&!THREAD.isThreadCpuTimeEnabled())THREAD.setThreadCpuTimeEnabled(true);
        scenarios();running=true;wallLast=System.nanoTime();processLast=OS.getProcessCpuTime();event("PACKAGED_PROFILE_READY "+hash);
    }
    private static void scenarios(){
        hold("empty-baseline",400,()->{check(PocketService.dimension(server).getChunkSource().getLoadedChunksCount()==0,"Empty destination baseline not zero");});
        add("setup-17-fixture-identities",ProfileProbe::setup,()->true,()->{});
        hold("home-baseline",400,()->{});
        int previous=0;
        for(int target:new int[]{1,2,4,8}){final int from=previous,n=target;previous=target;
            add("cold-ramp-"+n,()->{ExpansionRules.get().activePocketLimit=n;for(int i=from;i<n;i++){enter(owners.get(i),i);enter(guests.get(i),i);}},()->all(n),()->{verifyRooms(n);ceiling=n;denied(n);});
            hold("visit-steady-"+n,600,()->{check(all(n),"A finite visit expired during steady sample");verifyRooms(n);});
        }
        add("guests-return-for-mixed",()->{for(var g:guests)PocketService.recover(g);},()->{for(var g:guests)if(PocketService.inPocket(g.level())||PocketService.recovering(g.getUUID()))return false;return true;},()->{for(int i=0;i<8;i++)check(arrived(owners.get(i),i),"Guest return broke owner");});
        add("mixed-admit-8",()->{for(int i=0;i<8;i++)remote(guests.get(i),i);},()->all(8),()->verifyRooms(8));
        hold("mixed-steady-8",600,()->verifyRooms(8));
        add("owners-return-remotes-retained",()->{for(int i=0;i<8;i++)PocketService.recover(owners.get(i));},()->{for(int i=0;i<8;i++)if(PocketService.inPocket(owners.get(i).level())||PocketService.recovering(owners.get(i).getUUID()))return false;return true;},()->{for(int i=0;i<8;i++)check(arrived(guests.get(i),i),"Owner return broke remote");});
        hold("remote-steady-8",600,()->verifyRooms(8));
        add("capacity-released-contender-admission",()->{guests.get(0).closeContainer();enter(owners.get(8),8);},()->arrived(owners.get(8),8),()->{check(room(8).stage()==PocketLedger.Stage.READY,"Contender did not finish");denied(0);for(int i=1;i<8;i++)check(arrived(guests.get(i),i),"Contender interrupted unrelated remote");});
        hold("contender-plus-seven-remotes",200,()->{});
        add("drain-first-cycle",ProfileProbe::drain,ProfileProbe::drained,()->{});
        hold("idle-after-cold",650,ProfileProbe::clean);
        for(int cycle=1;cycle<=2;cycle++){final int c=cycle;
            add("warm-reopen-"+c,()->{ExpansionRules.get().activePocketLimit=8;for(int i=0;i<8;i++){remote(owners.get(i),i);remote(guests.get(i),i);}},()->all(8),()->verifyRooms(8));
            hold("warm-remote-steady-"+c,600,()->{verifyRooms(8);check((int)field("lastPreparedBlocks")==0,"Warm reopen regenerated shell");});
            add("lower-cap-share-"+c,()->{ExpansionRules.get().activePocketLimit=1;owners.get(0).closeContainer();},()->PocketService.lease(owners.get(0).getUUID())==null,()->{check(arrived(guests.get(0),0),"First viewer close invalidated shared pocket");remote(owners.get(0),0);denied(8);});
            add("drain-warm-"+c,ProfileProbe::drain,ProfileProbe::drained,()->{});
            hold("idle-warm-"+c,650,ProfileProbe::clean);
        }
    }
}
