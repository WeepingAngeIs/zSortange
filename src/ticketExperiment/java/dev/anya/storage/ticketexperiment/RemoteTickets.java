package dev.anya.storage.ticketexperiment;

import dev.anya.storage.pocket.*;
import dev.anya.storage.ticketexperiment.mixin.TicketKeyAccess;
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
    private static final Map<ServerChunkCache,ResourceKey<Level>> DIMENSIONS=new IdentityHashMap<>();
    private static final Set<Key> RECOVERY_KEYS=new HashSet<>();
    private static EligibilitySnapshot.Decision decision=new EligibilitySnapshot.Decision(Set.of(),Set.of(),0,0,0);
    private static TicketType<UUID> ticketType;
    private static long syncs,selectionNanos,leaseScans,pocketScans,occupantTests,lookups;
    public static void startTick(){syncs=selectionNanos=leaseScans=pocketScans=occupantTests=lookups=0;}
    public static long[] selectionMetrics(){return new long[]{syncs,selectionNanos,leaseScans,pocketScans,occupantTests,lookups};}
    private static Key key(Object ticket){var t=(TicketKeyAccess)ticket;return new Key(t.anya$dimension(),t.anya$chunk(),t.anya$pocket());}
    private static Key key(ServerChunkCache cache,ChunkPos p,Object owner){
        var dimension=DIMENSIONS.get(cache);if(dimension==null)throw new IllegalStateException("Unknown ticket dimension");return new Key(dimension,p,(UUID)owner);
    }
    private static TicketType<UUID> type(){return ticketType==null?(TicketType<UUID>)field("TICKET"):ticketType;}
    private static PocketLedger ledger(){return (PocketLedger)field("ledger");}
    private static boolean desired(Key k){return RECOVERY_KEYS.contains(k)||decision.active().contains(k.owner());}
    private static int policy(Key k,int original){
        lookups++;
        return candidate()&&original==2&&k.dimension().equals(PocketService.DIMENSION)
                &&!RECOVERY_KEYS.contains(k)&&decision.remoteOnly().contains(k.owner())?0:original;
    }
    public static void beforeSync(MinecraftServer s,PocketLeases leases,PocketLedger ledger,Set<UUID> preparing,Set<UUID> pendingPlayers,
                                  Map<?,Long> recovery,long clock,TicketType<UUID> type){
        if(server!=s){if(!APPLIED.isEmpty())throw new IllegalStateException("New server with old tickets");DIMENSIONS.clear();for(var level:s.getAllLevels())DIMENSIONS.put(level.getChunkSource(),level.dimension());}
        server=s;ticketType=type;syncs++;
        if(!candidate())return;
        long started=System.nanoTime();RECOVERY_KEYS.clear();
        recovery.forEach((t,end)->{if(clock<end)RECOVERY_KEYS.add(key(t));});
        var occupants=new ArrayList<EligibilitySnapshot.Occupant>();
        for(var player:s.getPlayerList().getPlayers())if(PocketService.inPocket(player.level())){
            var pos=player.chunkPosition();occupants.add(new EligibilitySnapshot.Occupant(pos.x,pos.z));
        }
        decision=EligibilitySnapshot.compute(leases.snapshot(),id->ledger==null?null:ledger.get(id),preparing,pendingPlayers,occupants,clock);
        selectionNanos+=System.nanoTime()-started;leaseScans+=decision.leaseVisits();pocketScans+=decision.pocketVisits();occupantTests+=decision.occupantComparisons();
        // Recompute each synchronization, not each tick: requests/recovery can change between calls.
        for(var entry:APPLIED.entrySet()){
            Key k=entry.getKey();if(!desired(k))continue;
            int next=policy(k,2);if(next==entry.getValue())continue;
            var level=s.getLevel(k.dimension());if(level==null)throw new IllegalStateException("Live request lost dimension");
            level.getChunkSource().addRegionTicket(type(),k.chunk(),next,k.owner());
            level.getChunkSource().removeRegionTicket(type(),k.chunk(),entry.getValue(),k.owner());
            entry.setValue(next);transitions++;
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
    public static void afterSync(Set<?> tickets){
        Set<Key> expected=new HashSet<>();for(Object t:tickets)expected.add(key(t));
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
