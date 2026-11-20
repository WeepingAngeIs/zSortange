package dev.anya.storage.equalwork;

import dev.anya.storage.durable.*;
import dev.anya.storage.minimal.*;
import dev.anya.storage.pocket.PocketService;
import dev.anya.storage.pressure.*;
import dev.anya.storage.repair.*;
import net.minecraft.server.MinecraftServer;
import java.io.*;
import java.lang.management.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import jdk.jfr.*;

/** Same bounded observer in both policies. Full tickServer bracket includes fixture work and legacy CSVs. */
public final class Metrics {
 public static MinecraftServer server;public static boolean measure;
 private static long start,cpu,alloc,wall,stopAt;private static String beginPhase;private static boolean beginMeasure;
 private static final long[] spans=field("nanos"),calls=field("calls");
 private static final com.sun.management.ThreadMXBean THREAD=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
 private static final com.sun.management.OperatingSystemMXBean OS=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
 private static final List<String> rows=new ArrayList<>();
 private record Seen(long tick,long nano){}
 private static final IdentityHashMap<Runnable,Seen> seen=new IdentityHashMap<>();
 private static final List<String> residence=new ArrayList<>();
 private static long scanNs,observedRun,unobservedRun;
 public static final AtomicLong journalAtomicNs=new AtomicLong(),journalAtomicCalls=new AtomicLong(),journalAtomicBytes=new AtomicLong();
 public static final AtomicLong regionWriteNs=new AtomicLong(),regionWriteCalls=new AtomicLong(),regionFlushNs=new AtomicLong(),regionFlushCalls=new AtomicLong();
 private static final ThreadLocal<long[]> io=ThreadLocal.withInitial(()->new long[3]);
 private static Recording recording;private static Tick span;private static boolean finished;
 @Name("anya.EqualWorkTick") @StackTrace(false) public static class Tick extends jdk.jfr.Event {public long tick;public String phase;public long fullNs,serializeNs,saveNs,unloadNs,fixtureNs;}
 public static long fixtureNs;private static long fixtureStart;
 private static long[] field(String name){try{Field f=LeanMetrics.class.getDeclaredField(name);f.setAccessible(true);return (long[])f.get(null);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 public static void start(MinecraftServer s)throws Exception{
  server=s;recording=new Recording();recording.setName("equal-work-identical-observer");recording.setMaxSize(64L*1024*1024);recording.setMaxAge(Duration.ofSeconds(360));
  recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20));recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(20));
  recording.enable("jdk.GarbageCollection");recording.enable("jdk.GCPhasePause");recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(5)).withStackTrace();
  recording.enable("jdk.FileWrite").withThreshold(Duration.ofMillis(5)).withStackTrace();recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(5)).withStackTrace();recording.enable(Tick.class).withoutStackTrace();recording.start();
 }
 public static void pre(MinecraftServer s){if(server==null)return;start=System.nanoTime();wall=System.currentTimeMillis();cpu=THREAD.getCurrentThreadCpuTime();alloc=THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId());scanNs=fixtureNs=0;beginPhase=EqualWork.phase;beginMeasure=measure;span=new Tick();span.begin();}
 public static void post(MinecraftServer s){if(server==null||finished)return;long end=System.nanoTime(),cp=THREAD.getCurrentThreadCpuTime(),al=THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId());
  var l=PocketService.dimension(s);var v=(OwnershipView)(Object)l.getChunkSource().chunkMap;var h=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
  if(rows.size()>=7200)throw new IllegalStateException("Bounded metric rows exceeded");
  StringBuilder b=new StringBuilder().append(s.getTickCount()).append(',').append(wall).append(',').append(beginPhase).append(',').append(EqualWork.phase).append(',').append(beginMeasure).append(',').append(end-start).append(',').append(cp-cpu).append(',').append(al-alloc).append(',').append(OS.getProcessCpuTime()).append(',').append(h.getUsed()).append(',').append(h.getCommitted());
  b.append(',').append(v.anyaCallbackCount()).append(',').append(v.anyaPendingCount()).append(',').append(l.getChunkSource().getLoadedChunksCount()).append(',').append(PocketService.ownedTicketRequests());
  for(long n:spans)b.append(',').append(n);b.append(',').append(calls[5]).append(',').append(scanNs).append(',').append(fixtureNs).append(',').append(EqualWork.completed).append(',').append(Receipts.confirmedCount).append(',').append(Pressure.denied).append(',').append(Pressure.accepted).append(',').append(Pressure.gate().waiters()).append(',').append(Receipts.oldest(s.getTickCount()));
  b.append(',').append(journalAtomicNs.get()).append(',').append(journalAtomicCalls.get()).append(',').append(journalAtomicBytes.get()).append(',').append(regionWriteNs.get()).append(',').append(regionWriteCalls.get()).append(',').append(regionFlushNs.get()).append(',').append(regionFlushCalls.get()).append(',').append(RepairService.journal==null?-1:RepairService.journal.bytes());rows.add(b.toString());
  if(span!=null){span.tick=s.getTickCount();span.phase=beginPhase;span.fullNs=end-start;span.serializeNs=spans[5];span.saveNs=spans[1];span.unloadNs=spans[0];span.fixtureNs=fixtureNs;span.end();if(end-start>=10_000_000)span.commit();span=null;}
 }
 public static void fixtureBegin(){fixtureStart=System.nanoTime();}
 public static void fixtureEnd(){fixtureNs+=System.nanoTime()-fixtureStart;}
 public static void scan(Queue<Runnable> q){if(server==null)return;long n=System.nanoTime();for(var r:q)if(!seen.containsKey(r)){if(seen.size()>=20000)throw new IllegalStateException("Callback observer bound");seen.put(r,new Seen(server.getTickCount(),n));}scanNs+=System.nanoTime()-n;}
 public static void run(Runnable r){if(server==null)return;var s=seen.remove(r);if(s==null){unobservedRun++;return;}observedRun++;if(residence.size()>=100000)throw new IllegalStateException("Residence row bound");residence.add(server.getTickCount()+","+EqualWork.phase+","+(server.getTickCount()-s.tick)+","+(System.nanoTime()-s.nano));}
 public static void ioBegin(int i){io.get()[i]=System.nanoTime();}
 public static void ioEnd(int i,int bytes){long d=System.nanoTime()-io.get()[i];if(i==0){journalAtomicNs.addAndGet(d);journalAtomicCalls.incrementAndGet();journalAtomicBytes.addAndGet(bytes);}else if(i==1){regionWriteNs.addAndGet(d);regionWriteCalls.incrementAndGet();}else{regionFlushNs.addAndGet(d);regionFlushCalls.incrementAndGet();}}
 public static void stopBegin(){if(server!=null){stopAt=System.nanoTime();Trace.marker("EQUAL_STOP_BEGIN",""+System.currentTimeMillis());}}
 public static void stopEnd(){if(server!=null&&stopAt>0)Trace.marker("EQUAL_STOP_NS",""+(System.nanoTime()-stopAt));}
 public static synchronized void finish()throws Exception{
  if(finished||server==null)return;finished=true;
  try(var w=Files.newBufferedWriter(Trace.output().resolve("equal-ticks.csv"))){w.write("tick,epoch_ms,phase_begin,phase_end,work,full_tick_ns,thread_cpu_ns,thread_allocated_bytes,process_cpu_ns,heap_used,heap_committed,queue,pending,cache,requests,unload_ns,save_ns,save_all_ns,distance_ns,cache_tick_ns,serialize_ns,level_unload_ns,serialize_calls,observer_scan_ns,fixture_ns,updates,confirmed,denied,accepted,waiters,oldest_dirty_ticks,journal_atomic_ns,journal_atomic_calls,journal_atomic_bytes,region_write_ns,region_write_calls,region_flush_ns,region_flush_calls,journal_bytes\n");for(var line:rows){w.write(line);w.newLine();}}
  try(var w=Files.newBufferedWriter(Trace.output().resolve("queue-residence.csv"))){w.write("tick,phase,first_observed_wait_ticks,first_observed_wait_ns\n");for(var line:residence){w.write(line);w.newLine();}}
  Trace.marker("EQUAL_OBSERVER","observed_callbacks="+observedRun+" unobserved_callbacks="+unobservedRun+" retained_observer_entries="+seen.size()+" first-observed residence is lower bound, not actual enqueue timestamp; no callback wrapping/removal");
  if(recording!=null){recording.stop();recording.dump(Trace.output().resolve("equal-work.jfr"));recording.close();recording=null;}
 }
 private Metrics(){}
}
