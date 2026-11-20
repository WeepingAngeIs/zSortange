package dev.anya.storage.allocation;

import dev.anya.storage.pocket.PocketService;
import dev.anya.storage.ticketexperiment.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import jdk.jfr.*;

/** Separate CI observer. No production/candidate class file or decision is rewritten. */
@Mod("anyaallocation")
public final class Probe {
    public static final String[] NAMES={"service","prepare","sync","recovery","savePlayer","arrive","getChunk","chunkSave","ledgerCommit","chunkTick","fixture","audit"};
    private static final int N=NAMES.length;
    private static final long[] BEGIN=new long[N],TOTAL=new long[N],CALLS=new long[N];
    private static final int[] DEPTH=new int[N];
    private static final com.sun.management.ThreadMXBean THREAD=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    private static final MemoryMXBean MEMORY=ManagementFactory.getMemoryMXBean();
    private static final List<GarbageCollectorMXBean> GC=ManagementFactory.getGarbageCollectorMXBeans();
    private static final boolean DIAG=Boolean.getBoolean("anya.allocationDiagnostic");
    private static final boolean OLD_OBSERVER=Boolean.getBoolean("anya.originalObserver");
    private static final Path OUT=Path.of(System.getProperty("anya.control"));
    private static BufferedWriter csv,slow;
    private static MinecraftServer server;
    private static Thread serverThread;
    private static long serial,start,cpuStart,allocStart,wallPrevious,processPrevious,wallBase,epochBase,phaseAge,holdStart;
    private static String phase="startup",lastPhase="";
    private static boolean holding;
    private static String snapshot="";
    private static final Set<String> TAKEN=new HashSet<>();
    public Probe(){if(!"true".equals(System.getenv("GITHUB_ACTIONS")))throw new IllegalStateException("CI observation only");}
    public static boolean suppressOldObserver(){return !OLD_OBSERVER;}
    public static boolean serverThread(){return Thread.currentThread()==serverThread;}
    public static void begin(int n){if(serverThread()&&csv!=null&&DEPTH[n]++==0)BEGIN[n]=System.nanoTime();}
    public static void end(int n){if(!serverThread()||csv==null)return;if(--DEPTH[n]!=0)return;long duration=System.nanoTime()-BEGIN[n];TOTAL[n]+=duration;CALLS[n]++;if(DIAG&&duration>=5_000_000){Span e=new Span();e.kind=NAMES[n];e.phase=RemoteMetrics.phase;e.tick=serial;e.wallStart=epochBase+BEGIN[n]-wallBase;e.nanos=duration;e.commit();}}
    private static void open(MinecraftServer s)throws IOException{
        if(!Files.isRegularFile(Path.of(".anya-churn-fixture")))throw new IllegalStateException("Disposable fixture marker missing");
        server=s;serverThread=Thread.currentThread();Files.createDirectories(OUT);
        csv=Files.newBufferedWriter(OUT.resolve("light-ticks.csv"));slow=Files.newBufferedWriter(OUT.resolve("slow-ticks.csv"));
        String head="tick,phase,diagnostic,epoch_ns,tick_ns,cpu_ns,allocated_bytes,period_ns,process_cpu_ns,heap_used,heap_committed,nonheap_used,gc_count,gc_ms,cache,requests,writes";
        for(String n:NAMES)head+=","+n+"_ns,"+n+"_calls";head+=",observer_ns\n";csv.write(head);slow.write(head);
        if(THREAD.isThreadCpuTimeSupported()&&!THREAD.isThreadCpuTimeEnabled())THREAD.setThreadCpuTimeEnabled(true);
        if(THREAD.isThreadAllocatedMemorySupported()&&!THREAD.isThreadAllocatedMemoryEnabled())THREAD.setThreadAllocatedMemoryEnabled(true);
        wallBase=System.nanoTime();epochBase=System.currentTimeMillis()*1_000_000;wallPrevious=wallBase;processPrevious=OS.getProcessCpuTime();
    }
    public static void tickBegin(MinecraftServer s){try{
        if(csv==null)open(s);start=System.nanoTime();serial++;Arrays.fill(TOTAL,0);Arrays.fill(CALLS,0);Arrays.fill(DEPTH,0);
        cpuStart=THREAD.getCurrentThreadCpuTime();allocStart=THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId());
        if(!OLD_OBSERVER)RemoteTickets.startTick();
    }catch(Exception e){throw new IllegalStateException(e);}}
    public static void tickEnd(MinecraftServer s){if(csv==null)return;try{
        long end=System.nanoTime(),elapsed=end-start,cpu=THREAD.getCurrentThreadCpuTime()-cpuStart,allocated=THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocStart;
        long process=OS.getProcessCpuTime(),period=end-wallPrevious,processDelta=process-processPrevious;wallPrevious=end;processPrevious=process;
        phase=RemoteMetrics.phase;if(!phase.equals(lastPhase)){lastPhase=phase;phaseAge=0;Phase p=new Phase();p.phase=phase;p.tick=serial;p.commit();}else if(!holding)phaseAge++;
        long used=-1,committed=-1,nonheap=-1,gc=-1,gcms=-1;int cache=-1,requests=-1,writes=-1;
        if(serial%20==0){var heap=MEMORY.getHeapMemoryUsage();used=heap.getUsed();committed=heap.getCommitted();nonheap=MEMORY.getNonHeapMemoryUsage().getUsed();gc=gcms=0;
            for(var b:GC){gc+=Math.max(0,b.getCollectionCount());gcms+=Math.max(0,b.getCollectionTime());}
            cache=PocketService.dimension(s).getChunkSource().getLoadedChunksCount();requests=PocketService.ownedTicketRequests();writes=(int)RemoteTickets.field("lastPreparedBlocks");
            if(cache>15000||used>heap.getMax()*.92)throw new IllegalStateException("Protective heap/cache stop: "+heap+" cache="+cache);
            Path temp=OUT.resolve("light-heartbeat.pending");Files.writeString(temp,serial+","+phase+","+holding);Files.move(temp,OUT.resolve("light-heartbeat"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        StringBuilder row=new StringBuilder(500).append(serial).append(',').append(phase).append(',').append(holding).append(',').append(epochBase+start-wallBase).append(',').append(elapsed).append(',').append(cpu).append(',').append(allocated).append(',').append(period).append(',').append(processDelta).append(',').append(used).append(',').append(committed).append(',').append(nonheap).append(',').append(gc).append(',').append(gcms).append(',').append(cache).append(',').append(requests).append(',').append(writes);
        for(int n=0;n<N;n++)row.append(',').append(TOTAL[n]).append(',').append(CALLS[n]);row.append(',').append(System.nanoTime()-end).append('\n');csv.write(row.toString());if(elapsed>=10_000_000)slow.write(row.toString());
        if(DIAG&&elapsed>=10_000_000){Span e=new Span();e.kind="tick";e.phase=phase;e.tick=serial;e.wallStart=epochBase+start-wallBase;e.nanos=elapsed;e.commit();}
        if(serial%20==0){csv.flush();slow.flush();}
        if(holding){if(Files.exists(OUT.resolve("snapshot-"+snapshot+".done"))){holding=false;}else if(System.nanoTime()-holdStart>60_000_000_000L)throw new IllegalStateException("Diagnostic checkpoint exceeded 60 seconds");}
        else if(DIAG){String target=null;
            if(phase.equals("empty-baseline")&&phaseAge==100)target="empty";
            if(phase.equals("remote-steady-3")&&phaseAge==350)target="loaded";
            if(phase.equals("final-idle")&&phaseAge>=600&&PocketService.dimension(s).getChunkSource().getLoadedChunksCount()==0)target="idle";
            if(target!=null&&TAKEN.add(target)){snapshot=target;holding=true;holdStart=System.nanoTime();Files.writeString(OUT.resolve("snapshot-request"),target);}
        }
    }catch(Exception e){try{Files.writeString(OUT.resolve("FAILED-allocation"),e.toString());}catch(IOException ignored){}s.halt(false);throw new IllegalStateException(e);}}
    /** Pauses only fixture progression at clearly excluded diagnostic checkpoints. Engine/service still tick. */
    public static boolean holdFixture(){return holding;}
    public static void close(){try{if(csv!=null){csv.close();slow.close();csv=null;}}catch(IOException e){throw new IllegalStateException(e);}}
    @Name("anya.Span") @Label("Anya measured method span") @StackTrace(false) public static final class Span extends Event {public String kind,phase;public long tick;public long wallStart,nanos;}
    @Name("anya.Phase") @Label("Anya workload phase") @StackTrace(false) public static final class Phase extends Event {public String phase;public long tick;}
}
