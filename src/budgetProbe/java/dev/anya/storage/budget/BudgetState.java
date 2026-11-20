package dev.anya.storage.budget;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
/** No retained callbacks, world snapshots, save receipts, or futures are stored here. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class BudgetState {
 public static final boolean ENABLED=Boolean.getBoolean("anya.serializationBudget");
 public static final int SNAPSHOTS=Integer.getInteger("anya.budgetSnapshots",32);
 private static final long NANOS=8_000_000L;
 private static final int CALLBACKS=256;
 private static boolean stopping;
 private static final List<BudgetState> STATES=new ArrayList<>();
 private static BudgetState active;
 private static BufferedWriter csv;
 public final ServerLevel level;
 private final TickBudget window=new TickBudget(CALLBACKS,SNAPSHOTS,NANOS);
 private long tick=Long.MIN_VALUE;
 public int forceDepth;
 private long elapsed;
 public long yielded,forced,polled,executed,serialized,forcedSerialized,incrementalDeferred;
 public long totalYields,totalForce,totalSerialized;
 public BudgetState(ServerLevel level){this.level=level;STATES.add(this);}
 public void reset(){long now=level.getServer().getTickCount();window.tick(now);if(tick!=now){tick=now;yielded=forced=polled=executed=serialized=forcedSerialized=incrementalDeferred=elapsed=0;}}
 public boolean bounded(){return ENABLED&&!stopping&&forceDepth==0&&level.getServer().isRunning();}
 public boolean timeAllowed(){reset();return !bounded()||window.admit();}
 public Runnable poll(Queue<Runnable> queue){
  reset();if(bounded()&&!window.admit()){yielded++;totalYields++;return null;}
  Runnable work=queue.poll();if(work!=null){polled++;if(!bounded()){forced++;totalForce++;}}
  return work;
 }
 public void run(Runnable work){
  reset();BudgetState prior=active;active=this;boolean limited=bounded();long before=System.nanoTime();
  if(limited)window.callback();executed++;
  try{work.run();}finally{long duration=System.nanoTime()-before;elapsed+=duration;if(limited)window.elapsed(duration);active=prior;}
 }
 public boolean allowIncremental(){reset();if(bounded()&&!window.admit()){incrementalDeferred++;return false;}return true;}
 public void incremental(Runnable work){
  reset();BudgetState prior=active;active=this;boolean limited=bounded();long before=System.nanoTime();
  try{work.run();}finally{long duration=System.nanoTime()-before;elapsed+=duration;if(limited)window.elapsed(duration);active=prior;}
 }
 public static void snapshot(ServerLevel level){
  BudgetState s=active;if(s==null||s.level!=level)return;
  if(!level.getServer().isSameThread())throw new IllegalStateException("World serialization left server thread");
  s.serialized++;s.totalSerialized++;if(s.bounded())s.window.snapshot();else s.forcedSerialized++;
 }
 public static BudgetState pocket(){return STATES.stream().filter(s->PocketService.inPocket(s.level)).findFirst().orElseThrow();}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void stopping(ServerStoppingEvent e){stopping=true;Trace.note("BUDGET_STOP_BYPASS",null,"retained engine callbacks; original save/flush path unmetered");}
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void post(ServerTickEvent.Post e)throws IOException{
  if(csv==null){Files.createDirectories(Trace.output());csv=Files.newBufferedWriter(Trace.output().resolve("serialization-budget.csv"));csv.write("tick,enabled,snapshot_limit,serializations,forced_serializations,callbacks_polled,callbacks_executed,forced_callbacks,yields,incremental_deferred,charged_ns,work_ns,force_depth\n");}
  for(BudgetState s:STATES){if(!PocketService.inPocket(s.level))continue;s.reset();
   if(s.polled!=s.executed)throw new IllegalStateException("Polled callback did not execute exactly once");
   if(ENABLED&&(s.window.snapshots()>SNAPSHOTS||s.window.callbacks()>CALLBACKS))throw new IllegalStateException("Budget count exceeded in isolated workload");
   csv.write(s.tick+","+ENABLED+","+SNAPSHOTS+","+s.serialized+","+s.forcedSerialized+","+s.polled+","+s.executed+","+s.forced+","+s.yielded+","+s.incrementalDeferred+","+s.window.nanos()+","+s.elapsed+","+s.forceDepth+"\n");
   if(s.tick%20==0)csv.flush();
  }
 }
 @SubscribeEvent public static void stopped(ServerStoppedEvent e)throws IOException{for(var s:STATES)if(PocketService.inPocket(s.level))Trace.marker("budget-final","enabled="+ENABLED+" yields="+s.totalYields+" force_callbacks="+s.totalForce+" serialized="+s.totalSerialized+" own_queue=none\n");if(csv!=null){csv.close();csv=null;}STATES.clear();active=null;}
}
