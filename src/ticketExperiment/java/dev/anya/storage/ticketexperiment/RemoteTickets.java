package dev.anya.storage.ticketexperiment;

import dev.anya.storage.pocket.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.neoforged.fml.common.Mod;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Isolated A/B intervention. It does not change desired ticket keys, leases or world/player data.
 * Only READY destination keys with remote-only leases and no occupant may use FULL radius 0.
 * Visitor, pending visit, preparation, home and recovery keys retain original radius 2.
 */
@Mod(RemoteTickets.ID)
public final class RemoteTickets {
    public static final String ID="anyaremotetest";
    public record Key(ResourceKey<Level> dimension,ChunkPos chunk,UUID owner){}
    private static final Map<Key,Integer> APPLIED=new HashMap<>();
    private static final Map<String,Field> FIELDS=new HashMap<>();
    private static MinecraftServer server;
    private static long transitions,adds,removes;
    private static int shutdownBefore;
    public RemoteTickets(){
        if(!"true".equals(System.getenv("GITHUB_ACTIONS")))throw new IllegalStateException("Isolated CI fixture, not a playable mod");
    }
    public static boolean candidate(){return Boolean.getBoolean("anya.remoteTickets");}
    public static Object field(String name){try{
        Field f=FIELDS.computeIfAbsent(name,n->{try{var x=PocketService.class.getDeclaredField(n);x.setAccessible(true);return x;}catch(Exception e){throw new IllegalStateException(e);}});
        return f.get(null);
    }catch(Exception e){throw new IllegalStateException(e);}}
    public static PocketLeases leases(){return (PocketLeases)field("LEASES");}
    private static Object call(Object o,String name){try{var m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}catch(Exception e){throw new IllegalStateException(e);}}
    private static Key key(Object ticket){return new Key((ResourceKey<Level>)call(ticket,"dimension"),(ChunkPos)call(ticket,"chunk"),(UUID)call(ticket,"pocket"));}
    private static Key key(ServerChunkCache cache,ChunkPos p,Object owner){
        for(ServerLevel l:server.getAllLevels())if(l.getChunkSource()==cache)return new Key(l.dimension(),p,(UUID)owner);
        throw new IllegalStateException("Unknown ticket dimension");
    }
    private static TicketType<UUID> type(){return (TicketType<UUID>)field("TICKET");}
    private static PocketLedger ledger(){return (PocketLedger)field("ledger");}
    private static long clock(){return (long)field("clock");}
    private static Set<Key> recovery(){
        Set<Key> result=new HashSet<>();
        ((Map<?,?>)field("RECOVERY_TICKETS")).forEach((t,end)->{if(clock()<(long)end)result.add(key(t));});return result;
    }
    private static boolean desired(Key k){return recovery().contains(k)||leases().active().contains(k.owner());}
    private static int policy(Key k,int original){
        if(!candidate()||original!=2||!k.dimension().equals(PocketService.DIMENSION)||recovery().contains(k))return original;
        PocketLedger l=ledger();var r=l==null?null:l.get(k.owner());
        if(r==null||r.stage()!=PocketLedger.Stage.READY||((Map<?,?>)field("PREPARING")).containsKey(r.id()))return original;
        var owned=leases().snapshot().stream().filter(v->v.pocket().equals(r.id())).toList();
        if(owned.isEmpty()||owned.stream().anyMatch(v->v.kind()!=PocketLeases.Kind.REMOTE))return original;
        // A released visitor may still be waiting for safe recovery. Never downgrade around them.
        for(var p:server.getPlayerList().getPlayers())if(PocketService.inPocket(p.level())){
            var c=p.chunkPosition();if(c.x>=(r.x()-1)>>4&&c.x<=(r.x()+32)>>4&&c.z>=(r.z()-1)>>4&&c.z<=(r.z()+32)>>4)return original;
        }
        return 0;
    }
    public static void beforeSync(MinecraftServer s){
        server=s;
        if(!candidate())return;
        // Existing shared remote leases can upgrade/downgrade without changing their keys/deadlines.
        for(var entry:new ArrayList<>(APPLIED.entrySet())){
            Key k=entry.getKey();if(!desired(k))continue;
            int next=policy(k,2);if(next==entry.getValue())continue;
            var level=s.getLevel(k.dimension());if(level==null)throw new IllegalStateException("Live request lost dimension");
            // Add stronger/weaker replacement before removing the exact previous rank: no ticket gap.
            level.getChunkSource().addRegionTicket(type(),k.chunk(),next,k.owner());
            level.getChunkSource().removeRegionTicket(type(),k.chunk(),entry.getValue(),k.owner());
            APPLIED.put(k,next);transitions++;
        }
    }
    public static <T> void add(ServerChunkCache cache,TicketType<T> type,ChunkPos pos,int original,T owner){
        if(type!=type())throw new IllegalStateException("Wrong redirected ticket type");Key k=key(cache,pos,owner);int rank=policy(k,original);
        Integer old=APPLIED.get(k);if(old!=null&&old!=rank)throw new IllegalStateException("Unreconciled ticket rank");
        cache.addRegionTicket(type,pos,rank,owner);APPLIED.put(k,rank);adds++;
    }
    public static <T> void remove(ServerChunkCache cache,TicketType<T> type,ChunkPos pos,int original,T owner){
        if(type!=type())throw new IllegalStateException("Wrong removal ticket type");Key k=key(cache,pos,owner);
        Integer applied=APPLIED.remove(k);cache.removeRegionTicket(type,pos,applied==null?original:applied,owner);removes++;
    }
    public static void afterSync(){
        Set<Key> expected=new HashSet<>();for(Object t:(Set<?>)field("TICKETS"))expected.add(key(t));
        if(!expected.equals(APPLIED.keySet()))throw new IllegalStateException("Applied ticket keys differ from original service");
        for(var e:APPLIED.entrySet())if(e.getValue()!=policy(e.getKey(),2))throw new IllegalStateException("Incorrect remote/home/visitor/recovery rank");
    }
    public static Map<Key,Integer> applied(){return Map.copyOf(APPLIED);}
    public static int destination(UUID id,int rank){return (int)APPLIED.entrySet().stream().filter(e->e.getKey().dimension().equals(PocketService.DIMENSION)&&e.getKey().owner().equals(id)&&e.getValue()==rank).count();}
    public static long transitions(){return transitions;}
    public static void beforeStop(MinecraftServer s){server=s;shutdownBefore=APPLIED.size();auditEngine();}
    public static void afterStop(){
        if(!APPLIED.isEmpty())throw new IllegalStateException("Stop retained applied ticket ranks");auditEngine();
        marker("ticket-stop-audit.txt","before="+shutdownBefore+" after=0 transitions="+transitions+" addCalls="+adds+" removeCalls="+removes+" candidate="+candidate()+"\n",true);
    }
    private static Field named(Class<?> c,String name)throws Exception{for(Class<?> x=c;x!=null;x=x.getSuperclass())try{var f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    /** Inspect actual DistanceManager ticket sets, not just the experiment's bookkeeping. */
    public static void auditEngine(){try{
        Set<String> actual=new HashSet<>(),expected=new HashSet<>();
        for(var e:APPLIED.entrySet()){Key k=e.getKey();expected.add(k.dimension().location()+"/"+k.chunk().toLong()+"/"+k.owner()+"/"+(33-e.getValue()));}
        for(ServerLevel l:server.getAllLevels()){
            DistanceManager manager=null;
            for(Class<?> c=l.getChunkSource().getClass();c!=null&&manager==null;c=c.getSuperclass())for(var f:c.getDeclaredFields())if(DistanceManager.class.isAssignableFrom(f.getType())){f.setAccessible(true);manager=(DistanceManager)f.get(l.getChunkSource());break;}
            if(manager==null)throw new IllegalStateException("DistanceManager contract not found");
            Map<?,?> tickets=(Map<?,?>)named(manager.getClass(),"tickets").get(manager);
            for(var entry:tickets.entrySet())for(Object raw:(Iterable<?>)entry.getValue()){
                Ticket<?> t=(Ticket<?>)raw;if(t.getType()!=type())continue;
                Object owner=named(t.getClass(),"key").get(t);
                actual.add(l.dimension().location()+"/"+entry.getKey()+"/"+owner+"/"+t.getTicketLevel());
            }
        }
        if(!actual.equals(expected))throw new IllegalStateException("Actual engine ticket set differs: actual="+actual+" expected="+expected);
    }catch(Exception e){throw new IllegalStateException("Ticket engine audit failed",e);}}
    public static void marker(String name,String value,boolean append){try{
        Path dir=Path.of(System.getProperty("anya.control"));Files.createDirectories(dir);
        Files.writeString(dir.resolve(name),value,StandardOpenOption.CREATE,append?StandardOpenOption.APPEND:StandardOpenOption.TRUNCATE_EXISTING);
    }catch(Exception e){throw new IllegalStateException(e);}}
    /** Deliberately interrupt recovery after teleport and before its player-save confirmation. */
    public static void beforeRecoverySave(net.minecraft.server.level.ServerPlayer p){
        if(!Boolean.getBoolean("anya.remoteCut")||!Files.exists(Path.of(System.getProperty("anya.control"),"ARM_RECOVERY_CUT")))return;
        auditEngine();
        if(PocketService.inPocket(p.level())||ledger().returning(p.getUUID())==null)throw new IllegalStateException("Wrong recovery cut boundary");
        marker("RECOVERY_CUT","Player="+p.getUUID()+"; outside=true; returnRecord=true; engineTicketsAudited=true; Runtime.halt(77) before savePlayer\n",false);
        Runtime.getRuntime().halt(77);
    }
}
