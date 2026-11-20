package dev.anya.storage.durable.unload;

import dev.anya.storage.durable.*;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Bounded lifecycle evidence. Weak keys and scalar-only values do not keep chunk holders alive. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class ReferenceTrace {
    public static final boolean ACTIVE=Boolean.getBoolean("anya.unloadExperiment");
    public static final boolean DEFER=Boolean.getBoolean("anya.deferUnload");
    public static volatile long tick;
    public static long callbacks,blockedOwned,blockedStale,ready,accepted,staleRetired,overflow;
    private static long ids,acquired,released,resurrections,begin,cpuBegin,allocatedBegin;
    private static final Map<GenerationChunkHolder,Life> lives=new WeakHashMap<>();
    private static final LongOpenHashSet sampled=new LongOpenHashSet();
    private static final AtomicInteger stacks=new AtomicInteger();
    private static BufferedWriter rows,events;
    private static final com.sun.management.ThreadMXBean THREAD=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    private static final class Life {
        long id,first,acquired,released,lastAcquire,lastRelease,scheduled=-1,resurrected=-1;
        boolean observed,reacquireLogged;
        Life(){id=++ids;first=tick;}
    }
    private static Life life(GenerationChunkHolder h){
        Life l=lives.get(h);if(l==null){if(lives.size()>=20000)return null;l=new Life();lives.put(h,l);}return l;
    }
    private static void log(String action,GenerationChunkHolder h,Life l,String detail){
        if(events==null||l==null)return;
        try{events.write(tick+"\t"+action+"\t"+l.id+"\t"+h.getPos()+"\t"+h.getGenerationRefCount()+"\t"+l.acquired+"\t"+l.released+"\t"+l.scheduled+"\t"+l.resurrected+"\t"+Thread.currentThread().getName()+"\t"+detail+"\n");events.flush();}catch(IOException e){throw new IllegalStateException(e);}
    }
    public static synchronized void acquire(GenerationChunkHolder h){
        acquired++;Life l=life(h);if(l==null)return;l.acquired++;l.lastAcquire=tick;
        if(l.resurrected>=0&&!l.reacquireLogged&&stacks.get()<16){
            l.reacquireLogged=true;stacks.incrementAndGet();
            String stack=Arrays.toString(Thread.currentThread().getStackTrace());log("REACQUIRE_AFTER_RESURRECTION",h,l,stack);
        }
    }
    public static synchronized void release(GenerationChunkHolder h){
        released++;Life l=life(h);if(l==null)return;l.released++;l.lastRelease=tick;
        if(l.observed&&h.getGenerationRefCount()==0)log("REFERENCES_RETURNED_TO_ZERO",h,l,"last_acquire_tick="+l.lastAcquire);
    }
    public static synchronized void scheduled(ChunkHolder h){Life l=life(h);if(l!=null){l.scheduled=tick;if(l.observed)log("PENDING_UNLOAD_AGAIN",h,l,"future_done="+h.getSaveSyncFuture().isDone());}}
    public static synchronized void resurrect(ChunkHolder h){resurrections++;Life l=life(h);if(l!=null){l.resurrected=tick;if(l.observed)log("RESURRECTED_TO_ACTIVE",h,l,"future_done="+h.getSaveSyncFuture().isDone());}}
    public static void callback(ChunkHolder h,boolean owns,boolean updating){
        callbacks++;boolean done=h.getSaveSyncFuture().isDone();int refs=h.getGenerationRefCount();
        if(refs>0&&done){
            if(owns)blockedOwned++;else blockedStale++;
            if(sampled.size()<64&&sampled.add(h.getPos().toLong()))synchronized(ReferenceTrace.class){Life l=life(h);if(l!=null){l.observed=true;log("BLOCKED_COMPLETED_FUTURE",h,l,"pending_owns="+owns+" updating_owns="+updating+" future_identity="+System.identityHashCode(h.getSaveSyncFuture())+" last_acquire_tick="+l.lastAcquire);}}
        }else if(h.isReadyForSaving())ready++;
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void pre(ServerTickEvent.Pre e)throws IOException{
        if(!ACTIVE)return;tick=e.getServer().getTickCount();
        if(rows==null){Files.createDirectories(Trace.output());rows=Files.newBufferedWriter(Trace.output().resolve("unload-retry-ticks.csv"));events=Files.newBufferedWriter(Trace.output().resolve("reference-lifetime.tsv"));
            rows.write("tick,phase,event_ns,thread_cpu_ns,thread_allocated_bytes,process_cpu_ns,heap_used,heap_committed,callbacks,blocked_owned,blocked_stale,ready,accepted,staleRetired,overflow,waiting,retired,rearmed,replaced,polls,queue_peak,acquired,released,resurrections,cache,requests\n");
            events.write("tick\taction\tholder_id\tchunk\trefs\tacquires\treleases\tpending_tick\tresurrection_tick\tthread\tdetail\n");
        }
        begin=System.nanoTime();cpuBegin=THREAD.getCurrentThreadCpuTime();allocatedBegin=THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
    @SubscribeEvent(priority=EventPriority.LOW) public static void post(ServerTickEvent.Post e)throws IOException{
        if(!ACTIVE||rows==null)return;var level=PocketService.dimension(e.getServer());if(level==null)return;
        long end=System.nanoTime(),cpu=THREAD.getCurrentThreadCpuTime(),alloc=THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId());
        var control=(UnloadControl)(Object)level.getChunkSource().chunkMap;var s=control.anyaRetryStats();var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long a,r,u;synchronized(ReferenceTrace.class){a=acquired;r=released;u=resurrections;}
        rows.write(tick+","+Fixture.phase+","+(end-begin)+","+(cpu-cpuBegin)+","+(alloc-allocatedBegin)+","+OS.getProcessCpuTime()+","+heap.getUsed()+","+heap.getCommitted()+","+callbacks+","+blockedOwned+","+blockedStale+","+ready+","+accepted+","+staleRetired+","+overflow+","+control.anyaRetryCount()+","+s[0]+","+s[1]+","+s[2]+","+s[3]+","+s[4]+","+a+","+r+","+u+","+level.getChunkSource().getLoadedChunksCount()+","+PocketService.ownedTicketRequests()+"\n");
        if(tick%20==0){rows.flush();if(overflow!=0){Trace.marker("FAILED","Deferred queue capacity reached; no callbacks evicted");e.getServer().halt(false);}}
    }
    public static synchronized void closed(ServerLevel level,UnloadControl control){
        if(!ACTIVE)return;if(control.anyaRetryCount()!=0)throw new IllegalStateException("Deferred work remains at ChunkMap.close");
        long[] s=control.anyaRetryStats();Trace.marker("unload-close-audit","deferred=0 acquired="+acquired+" released="+released+" resurrections="+resurrections+" callbacks="+callbacks+" blocked_owned="+blockedOwned+" blocked_stale="+blockedStale+" stale_retired="+staleRetired+" retired="+s[0]+" rearmed="+s[1]+" peak="+s[4]+" overflow="+overflow+"\n");
    }
    @SubscribeEvent public static synchronized void stopped(ServerStoppedEvent e)throws IOException{if(rows!=null){rows.close();events.close();rows=events=null;}lives.clear();sampled.clear();}
}
