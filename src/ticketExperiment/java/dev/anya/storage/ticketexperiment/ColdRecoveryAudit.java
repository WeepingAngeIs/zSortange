package dev.anya.storage.ticketexperiment;

import dev.anya.storage.pocket.PocketService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** Read-only instrumentation at the real production recovery load call, not a replacement recovery. */
public final class ColdRecoveryAudit {
    public record Observation(String phase,long tick,String dimension,long chunk,UUID player,boolean missingBefore,int rank,long nanos){}
    private static final List<Observation> OBS=new ArrayList<>();
    private static final Set<RemoteTickets.Key> COLD=new HashSet<>();
    private static boolean missing;
    private static long began;
    public static String phase="startup";
    public static boolean active(){return Boolean.getBoolean("anya.coldRecoveryAcceptance");}
    public static void expectCold(ServerLevel level,BlockPos pos,UUID player){
        check(level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null,"Cold arm attempted on loaded chunk");
        COLD.add(new RemoteTickets.Key(level.dimension(),new ChunkPos(pos),player));
    }
    private static void check(boolean value,String text){if(!value){RemoteTickets.marker("FAILED-cold-audit",text,false);throw new IllegalStateException(text);}}
    public static void before(ServerLevel level,BlockPos pos,UUID player){
        if(!active())return;
        began=System.nanoTime();missing=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null;
        var key=new RemoteTickets.Key(level.dimension(),new ChunkPos(pos),player);
        if(COLD.contains(key))check(missing,"Target was loaded before production recovery: "+key);
    }
    public static void after(ServerLevel level,BlockPos pos,UUID player){
        if(!active())return;
        var key=new RemoteTickets.Key(level.dimension(),new ChunkPos(pos),player);
        Integer rank=RemoteTickets.applied().get(key);
        check(rank!=null&&rank==2,"Recovery did not retain original rank 2: "+key+" rank="+rank);
        check(level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)!=null,"Production recovery did not load target");
        RemoteTickets.auditEngine();COLD.remove(key);
        var row=new Observation(phase,(long)RemoteTickets.field("clock"),level.dimension().location().toString(),key.chunk().toLong(),player,missing,rank,System.nanoTime()-began);
        OBS.add(row);RemoteTickets.marker("cold-recovery-calls.csv",row.phase()+","+row.tick()+","+row.dimension()+","+row.chunk()+","+row.player()+","+row.missingBefore()+","+row.rank()+","+row.nanos()+"\n",true);
    }
    public static List<Observation> snapshot(){return List.copyOf(OBS);}
    public static int outstanding(){return COLD.size();}
    private ColdRecoveryAudit(){}
}
