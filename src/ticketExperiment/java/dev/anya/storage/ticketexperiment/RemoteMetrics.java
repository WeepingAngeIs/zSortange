package dev.anya.storage.ticketexperiment;

import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Read-only engine/CPU telemetry. No absent chunks are loaded by observation. */
@EventBusSubscriber(modid=RemoteTickets.ID)
public final class RemoteMetrics {
    public static String phase="startup";
    private static MinecraftServer server;
    private static BufferedWriter csv;
    private static final ThreadMXBean THREAD=ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    private static long tick,start,cpuStart,wallLast,processLast,serviceBegin,serviceNs;
    private static int chunkCalls;
    private static boolean running;
    private static Path dir;
    public static void serviceStart(){if(running)serviceBegin=System.nanoTime();}
    public static void serviceEnd(){if(running)serviceNs+=System.nanoTime()-serviceBegin;}
    public static void chunk(ServerLevel l){if(running&&PocketService.inPocket(l))chunkCalls++;}
    private static Method method(Class<?> c,String n)throws Exception{for(Class<?> t=c;t!=null;t=t.getSuperclass())try{var m=t.getDeclaredMethod(n);m.setAccessible(true);return m;}catch(NoSuchMethodException ignored){}throw new NoSuchMethodException(n);}
    private static boolean ready(Object h,String name)throws Exception{Object v=((CompletableFuture<?>)method(h.getClass(),name).invoke(h)).getNow(null);return v instanceof ChunkResult<?> r&&r.orElse(null)!=null;}
    @SubscribeEvent public static void start(ServerStartedEvent e)throws Exception{
        server=e.getServer();dir=Path.of(System.getProperty("anya.control"));Files.createDirectories(dir);
        csv=Files.newBufferedWriter(dir.resolve("remote-ticks.csv"));
        csv.write("tick,phase,period_ns,event_ns,thread_cpu_ns,process_cpu_ns,service_ns,measurement_ns,heap_used,gc_count,gc_ms,requests,weak_destination,strong_destination,home_recovery,cache,full_ready,block_ready,entity_ready,actual_chunk_ticks,transitions,shell_writes\n");
        if(THREAD.isThreadCpuTimeSupported()&&!THREAD.isThreadCpuTimeEnabled())THREAD.setThreadCpuTimeEnabled(true);
        wallLast=System.nanoTime();processLast=OS.getProcessCpuTime();running=true;
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void pre(ServerTickEvent.Pre e){if(!running)return;start=System.nanoTime();cpuStart=THREAD.getCurrentThreadCpuTime();serviceNs=0;chunkCalls=0;}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void post(ServerTickEvent.Post e)throws Exception{
        if(!running||e.getServer()!=server)return;
        long now=System.nanoTime(),duration=now-start,cpu=THREAD.getCurrentThreadCpuTime()-cpuStart,process=OS.getProcessCpuTime(),period=now-wallLast,delta=process-processLast;
        processLast=process;wallLast=now;tick++;
        var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var cache=PocketService.dimension(server).getChunkSource();int raw=cache.getLoadedChunksCount();
        if(raw>6000||heap.getUsed()>heap.getMax()*.92){RemoteTickets.marker("FAILED-resource","Protective profile stop: cache="+raw+" heap="+heap+"\n",false);server.halt(false);return;}
        int full=-1,block=-1,entity=-1;
        if(tick%20==0){
            RemoteTickets.auditEngine();full=block=entity=0;
            for(Object h:(Iterable<?>)method(cache.chunkMap.getClass(),"getChunks").invoke(cache.chunkMap)){
                if(ready(h,"getFullChunkFuture"))full++;if(ready(h,"getTickingChunkFuture"))block++;if(ready(h,"getEntityTickingChunkFuture"))entity++;
            }
            Path temp=dir.resolve("heartbeat.pending");Files.writeString(temp,tick+","+phase);Files.move(temp,dir.resolve("heartbeat"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        long gc=0,gcMs=0;for(var g:ManagementFactory.getGarbageCollectorMXBeans()){gc+=Math.max(0,g.getCollectionCount());gcMs+=Math.max(0,g.getCollectionTime());}
        int weak=0,strong=0,home=0;for(var item:RemoteTickets.applied().entrySet()){
            if(item.getKey().dimension().equals(PocketService.DIMENSION)){if(item.getValue()==0)weak++;else strong++;}else{home++;if(item.getValue()!=2)throw new IllegalStateException("Home/recovery altered");}
        }
        csv.write(tick+","+phase+","+period+","+duration+","+cpu+","+delta+","+serviceNs+","+(System.nanoTime()-now)+","+heap.getUsed()+","+gc+","+gcMs+","+PocketService.ownedTicketRequests()+","+weak+","+strong+","+home+","+raw+","+full+","+block+","+entity+","+chunkCalls+","+RemoteTickets.transitions()+","+RemoteTickets.field("lastPreparedBlocks")+"\n");
        if(tick%20==0)csv.flush();
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e)throws Exception{running=false;if(csv!=null){csv.close();csv=null;}}
}
