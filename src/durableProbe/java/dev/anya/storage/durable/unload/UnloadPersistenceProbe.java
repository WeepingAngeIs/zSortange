package dev.anya.storage.durable.unload;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import dev.anya.storage.durable.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Explicit synthetic generation dependency and dirty-unload persistence fixture. Never enabled in measurement/client cases. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class UnloadPersistenceProbe {
    private static final String MODE=System.getProperty("anya.unloadPersistence","");
    private static final TicketType<UUID> TEST=TicketType.create("anya_unload_fixture_reactivation",UUID::compareTo,60);
    private static final UUID KEY=UUID.fromString("f0c314d0-346c-4328-848f-1ea7289aa860");
    private static boolean seeded,injected,released,reactivated,testTicket,inspected,finished;
    private static ChunkHolder held;
    private static long key,heldAt,drainAt=-1;
    private static CompletableFuture<Void> flush;
    private static int verified;
    private static final Properties identities=new Properties();
    private static void check(boolean v,String text){if(!v)throw new IllegalStateException(text);}
    private static ItemStack sample(UUID id){var stack=new ItemStack(Items.DIAMOND,13);stack.set(DataComponents.CUSTOM_NAME,Component.literal("Dirty unload "+id));return stack;}
    private static boolean hasSample(LevelChunk chunk){for(var e:chunk.getBlockEntities().values())if(e instanceof StorageEntity c&&c.pocketId!=null&&Transactions.same(c.getItem(0),sample(c.pocketId)))return true;return false;}
    public static void beforeCallback(ServerLevel level,ChunkHolder h,long pos,boolean owns){
        if(MODE.isEmpty()||MODE.startsWith("verify")||injected||!Fixture.phase.equals("drain")||!owns||!h.isReadyForSaving()||h.getSaveSyncFuture().isCompletedExceptionally())return;
        if(!(h.getLatestChunk() instanceof LevelChunk c)||!hasSample(c))return;
        check(level.getServer().isSameThread(),"Synthetic dependency must be owner-thread");
        // Exactly one balanced synthetic reference tests the defensive owned-wait path, not its natural prevalence.
        h.increaseGenerationRefCount();held=h;key=pos;heldAt=level.getServer().getTickCount();injected=true;
        Trace.note("SYNTHETIC_OWNED_WAIT_BEGIN",null,"key="+key+" refs="+h.getGenerationRefCount()+" mode="+MODE);
    }
    @SuppressWarnings("unchecked") private static Map<Long,ChunkHolder> map(Object object,String name)throws Exception{
        var field=ChunkMap.class.getDeclaredField(name);field.setAccessible(true);return (Map<Long,ChunkHolder>)field.get(object);
    }
    private static boolean drained(ChunkMap map,UnloadControl control)throws Exception{
        var f=ChunkMap.class.getDeclaredField("unloadQueue");f.setAccessible(true);
        return control.anyaRetryCount()==0&&map(map,"pendingUnloads").isEmpty()&&((java.util.Queue<?>)f.get(map)).isEmpty();
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Pre event)throws Exception{
        if(MODE.isEmpty()||finished)return;
        var server=event.getServer();var level=PocketService.dimension(server);if(level==null)return;
        try {
            check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"Not a disposable fixture");
            long now=server.getTickCount();check(now<1900,"Unload persistence deadline exceeded");
            var control=(UnloadControl)(Object)level.getChunkSource().chunkMap;
            if(MODE.startsWith("verify")){
                if(!inspected){
                    if(now<20)return;
                    try(var in=Files.newInputStream(Path.of("unload-identities.properties"))){identities.load(in);}
                    var rooms=PocketService.ledger(server).pockets();check(rooms.size()==8,"Restart duplicated/lost pocket allocation");
                    for(var room:rooms){
                        check(room.stage()==PocketLedger.Stage.READY&&identities.getProperty(room.id().toString()).equals(room.cell()+"/"+room.owner()),"Restart identity changed");
                        var positions=PocketService.chestPositions(room);
                        for(int i=0;i<positions.size();i++){
                            var p=positions.get(i);level.getChunk(p.getX()>>4,p.getZ()>>4); // Intentional restart inspection, not a loaded-only claim.
                            check(level.getBlockEntity(p) instanceof StorageEntity,"Saved physical chest absent");var c=(StorageEntity)level.getBlockEntity(p);
                            check(room.id().equals(c.pocketId)&&room.owner().equals(c.owner),"Saved chest identity changed");
                            check(i==0?Transactions.same(c.getItem(0),sample(room.id())):c.isEmpty(),"Dirty unload lost or duplicated sample");
                            if(i==0){for(int slot=1;slot<c.getContainerSize();slot++)check(c.getItem(slot).isEmpty(),"Duplicate sample slot");verified+=c.getItem(0).getCount();}
                        }
                    }
                    check(verified==104,"Expected exactly 8x13 items");inspected=true;drainAt=now;
                    Trace.marker("RAW_UNLOAD_RESTART_VERIFIED","104 named diamonds across original eight physical chest identities; no reseed or clean-stop save before forced cut");
                }else if(now-drainAt>=650){
                    check(PocketService.ownedTicketRequests()==0&&control.anyaRetryCount()==0&&level.getChunkSource().getLoadedChunksCount()==0,"Restart inspection leaked destination");
                    Trace.audit(server);Trace.marker("PASSED","Dirty unload persistence and restart cleanup; synthetic dependency mode="+MODE);finished=true;server.halt(false);
                }
                return;
            }
            if(!seeded&&Fixture.phase.equals("visit-steady")){
                var rooms=PocketService.ledger(server).pockets();check(rooms.size()==8,"Expected eight prepared rooms");
                for(var room:rooms){var p=PocketService.chestPositions(room).getFirst();check(level.hasChunkAt(p),"Seed must not load chunks");var c=(StorageEntity)level.getBlockEntity(p);check(c.isEmpty(),"Never replace saved inventory");c.setItem(0,sample(room.id()));check(level.getChunkAt(p).isUnsaved(),"Sample did not dirty its chunk");identities.setProperty(room.id().toString(),room.cell()+"/"+room.owner());}
                try(var out=Files.newOutputStream(Path.of("unload-identities.properties"))){identities.store(out,"Disposable post-READY dirty sample IDs");}
                seeded=true;Trace.note("POST_READY_DIRTY_SAMPLES",null,"104 named diamonds seeded; no saveEverything call after this point");
            }
            if(injected&&!released){
                check(held.getGenerationRefCount()>0&&held.getLatestChunk() instanceof LevelChunk chunk&&hasSample(chunk),"Owned dependency lost its live sample");
                if(MODE.equals("resurrect")&&now-heldAt>=5&&!testTicket){level.getChunkSource().addRegionTicket(TEST,new ChunkPos(key),2,KEY);testTicket=true;Trace.note("SYNTHETIC_RESURRECTION_REQUEST",null,"key="+key);}
                if(testTicket&&map(level.getChunkSource().chunkMap,"updatingChunkMap").get(key)==held)reactivated=true;
                if(now-heldAt>=20){
                    if(MODE.equals("hold")){check(control.anyaRetryCount()>0&&map(level.getChunkSource().chunkMap,"pendingUnloads").get(key)==held,"Owned deferred entry disappeared before dependency release");}
                    else check(reactivated,"Fixture did not actually resurrect held pending holder");
                    held.decreaseGenerationRefCount();released=true;held=null;
                    if(testTicket){level.getChunkSource().removeRegionTicket(TEST,new ChunkPos(key),2,KEY);testTicket=false;}
                    Trace.note("SYNTHETIC_DEPENDENCY_RELEASED",null,"balanced reference; real reactivation="+reactivated);
                }
            }
            if(Fixture.phase.equals("drain")&&seeded&&released&&PocketService.ownedTicketRequests()==0&&level.getChunkSource().getLoadedChunksCount()==0&&drained(level.getChunkSource().chunkMap,control)){
                check(control.anyaRetryCount()==0&&!testTicket,"Unresolved deferred/fixture request");
                long[] stats=control.anyaRetryStats();check(ReferenceTrace.accepted>0,"Owned retry path never executed");
                check(MODE.equals("hold")?stats[1]>0:stats[0]>0,"Expected owned rearm or resurrected retirement missing");
                if(flush==null){check(level.getChunkSource().chunkMap.chunkScanner() instanceof IOWorker,"Pinned worker missing");flush=((IOWorker)level.getChunkSource().chunkMap.chunkScanner()).synchronize(true);return;}
                if(!flush.isDone())return;flush.join(); // Already complete, no blocking wait. Fresh-process inspection verifies actual saved contents.
                Trace.audit(server);Trace.marker("CUT_AFTER_DIRTY_UNLOAD","items=104 original rooms=8 synthetic="+MODE+" accepted="+ReferenceTrace.accepted+" rearmed="+stats[1]+" retired="+stats[0]+" exact_owned_requests=0 deferred=0 pending_unloads=0 engine_unload_queue=0; no clean shutdown");
                Runtime.getRuntime().halt(77);
            }
        }catch(Throwable failure){finished=true;Trace.marker("FAILED","Unload persistence fixture: "+failure);AnyaStorage.LOG.error("ANYA_UNLOAD_PERSISTENCE_FAILED",failure);server.halt(false);}
    }
}
