package dev.anya.storage.minimal;
import dev.anya.storage.durable.*;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.*;
import jdk.jfr.*;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
/** Per-tick scalars only in lean mode. No per-callback observer, reference trace, ownership graph or readiness scan. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class LeanMetrics {
 public static final boolean CANDIDATE=Boolean.getBoolean("anya.minimalOwnership");
 public static final boolean DIAGNOSTIC=Boolean.getBoolean("anya.drainDiagnostic");
 public static long retired;
 private static MinecraftServer server;
 private static BufferedWriter rows;
 private static long start,cpuStart,allocStart,epoch;
 private static final long[] began=new long[7],nanos=new long[7],max=new long[7],calls=new long[7];
 private static final int[] depth=new int[7];
 private static final com.sun.management.ThreadMXBean THREAD=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
 private static final com.sun.management.OperatingSystemMXBean OS=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
 private static Recording recording;
 private static TickSpan span;
 @Name("anya.MinimalDrainTick") @Label("Pocket drainage tick") @Category("Anya isolated profiling") @StackTrace(false)
 public static final class TickSpan extends Event {public long tick;public String phase;public long unloadNs;public long saveNs;public long serializeNs;public long distanceNs;public long cacheTickNs;public long levelUnloadNs;}
 public static void begin(int i){if(server==null||!server.isSameThread())return;if(depth[i]++==0)began[i]=System.nanoTime();}
 public static void end(int i){if(server==null||!server.isSameThread())return;if(--depth[i]==0){long n=System.nanoTime()-began[i];nanos[i]+=n;max[i]=Math.max(max[i],n);calls[i]++;}}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void pre(ServerTickEvent.Pre e)throws Exception{
  server=e.getServer();if(rows==null){
   Files.createDirectories(Trace.output());rows=Files.newBufferedWriter(Trace.output().resolve("minimal-ticks.csv"));
   rows.write("tick,epoch_ms,phase,event_ns,thread_cpu_ns,thread_allocated_bytes,process_cpu_ns,heap_used,heap_committed,cache,requests,retired,unload_ns,save_ns,save_all_ns,distance_ns,cache_tick_ns,serialize_ns,level_unload_ns,save_calls,save_max_ns,serialize_calls,pending,engine_callbacks\n");
   Trace.marker("minimal-mode","candidate="+CANDIDATE+" diagnostic="+DIAGNOSTIC+" old_trace=false defensive_queue=false\n");
  }
  if(DIAGNOSTIC&&recording==null&&Fixture.phase.equals("drain")){
   recording=new Recording(Configuration.getConfiguration("profile"));recording.setName("bounded-pocket-drain");recording.setMaxSize(96L*1024*1024);recording.setMaxAge(Duration.ofSeconds(90));
   recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(20));
   recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(10)).withStackTrace();recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(10)).withStackTrace();
   recording.enable("jdk.FileWrite").withThreshold(Duration.ofMillis(10)).withStackTrace();recording.enable("jdk.FileRead").withThreshold(Duration.ofMillis(10)).withStackTrace();
   recording.enable(TickSpan.class).withoutStackTrace();recording.start();Trace.note("DRAIN_RECORDING_STARTED",null,"separate diagnostic case; not lean timing");
  }
  Arrays.fill(nanos,0);Arrays.fill(max,0);Arrays.fill(calls,0);Arrays.fill(depth,0);
  epoch=System.currentTimeMillis();start=System.nanoTime();cpuStart=THREAD.getCurrentThreadCpuTime();allocStart=THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId());
  if(recording!=null){span=new TickSpan();span.tick=server.getTickCount();span.phase=Fixture.phase;span.begin();}
 }
 @SubscribeEvent(priority=EventPriority.LOW) public static void post(ServerTickEvent.Post e)throws IOException{
  if(rows==null)return;long end=System.nanoTime(),cpu=THREAD.getCurrentThreadCpuTime(),alloc=THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId());
  var h=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();var l=PocketService.dimension(e.getServer());int cache=l==null?0:l.getChunkSource().getLoadedChunksCount();
  OwnershipView v=l==null?null:(OwnershipView)(Object)l.getChunkSource().chunkMap;
  rows.write(server.getTickCount()+","+epoch+","+Fixture.phase+","+(end-start)+","+(cpu-cpuStart)+","+(alloc-allocStart)+","+OS.getProcessCpuTime()+","+h.getUsed()+","+h.getCommitted()+","+cache+","+PocketService.ownedTicketRequests()+","+retired);
  for(long n:nanos)rows.write(","+n);rows.write(","+calls[1]+","+max[1]+","+calls[5]+","+(v==null?0:v.anyaPendingCount())+","+(v==null?0:v.anyaCallbackCount())+"\n");
  if(span!=null){span.unloadNs=nanos[0];span.saveNs=nanos[1];span.serializeNs=nanos[5];span.distanceNs=nanos[3];span.cacheTickNs=nanos[4];span.levelUnloadNs=nanos[6];span.end();if(end-start>=20_000_000)span.commit();span=null;}
  if(server.getTickCount()%20==0)rows.flush();
 }
 public static void closed(OwnershipView v){
  Trace.marker("minimal-close-audit","candidate="+CANDIDATE+" retired="+retired+" pending="+v.anyaPendingCount()+" callbacks="+v.anyaCallbackCount()+" defensive_queue=absent\n");
  if(v.anyaPendingCount()!=0||v.anyaCallbackCount()!=0)throw new IllegalStateException("Engine-owned unload work remains at close");
 }
 @SubscribeEvent public static void stopped(ServerStoppedEvent e)throws Exception{
  if(rows!=null){rows.close();rows=null;}
  if(recording!=null){recording.stop();recording.dump(Trace.output().resolve("drainage.jfr"));recording.close();recording=null;}
 }
}
