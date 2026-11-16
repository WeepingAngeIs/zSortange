package dev.anya.storage.pressure;
import dev.anya.storage.*;
import dev.anya.storage.durable.*;
import dev.anya.storage.minimal.OwnershipView;
import dev.anya.storage.pocket.*;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
public final class Pressure {
 public static final boolean ENABLED=Boolean.getBoolean("anya.backpressure");
 public static boolean workload=Boolean.getBoolean("anya.pressureClient");
 public static long accepted,denied,shared,maxWait;
 private static AdmissionWindow gate;
 public static AdmissionWindow gate(){if(gate==null)gate=new AdmissionWindow();return gate;}
 public static AdmissionWindow.Signal signal(ServerPlayer p){
  var l=PocketService.dimension(p.getServer());var v=(OwnershipView)(Object)l.getChunkSource().chunkMap;
  var h=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
  return new AdmissionWindow.Signal(Math.max(v.anyaPendingCount(),v.anyaCallbackCount()),l.getChunkSource().getLoadedChunksCount(),Receipts.oldest(p.getServer().getTickCount()),h.getUsed(),h.getMax(),Receipts.failed);
 }
 public static void check(ServerPlayer p,PocketLedger.Pocket r)throws IOException{
  if(!ENABLED||!workload)return;
  if(!p.getServer().isSameThread())throw new IOException("Admission must run on server thread");
  var leases=(PocketLeases)Preparation.field("LEASES");boolean active=leases.active().contains(r.id());long now=p.getServer().getTickCount();
  gate().update(now,signal(p));
  if(!gate().admit(p.getUUID(),r.id(),active,now)){denied++;throw new IOException("Experimental save backpressure: new pocket admission deferred; existing views and return recovery unchanged");}
  accepted++;if(active)shared++;
 }
 private Pressure(){}
}
