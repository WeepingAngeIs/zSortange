package dev.anya.storage.liveview;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import dev.anya.storage.repair.RepairService;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.*;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import qouteall.imm_ptl.core.chunk_loading.*;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.portal.Portal;
import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.Consumer;

/** Separate default-off experiment. No inventory, return record or persistent ticket belongs to this class. */
@Mod(LiveViews.ID) @EventBusSubscriber(modid=LiveViews.ID)
public final class LiveViews {
 public static final String ID="anyaliveview", PREFIX="anya_ephemeral_view:";
 public static final boolean ENABLED=Boolean.getBoolean("anyastorage.liveViewPrototype");
 public static final int MAX_POCKETS=2,MAX_HANDLES=8,MAX_PER_PLAYER=2,DATA_RADIUS=2,MAX_CACHE=4096;
 public static final long LIFETIME=1200;
 public static final int MAX_SUSPENDED_TICKS=100;
 public static MinecraftServer server;
 public static boolean stopping,failed,pressure;
 public static long deniedOpens,closed,autoLoadersSuppressed,nestedQueriesSuppressed;
 public static final Map<UUID,Handle> HANDLES=new LinkedHashMap<>();
 public static final class Handle {
  public final UUID id,player,pocket;public final long epoch,deadline;public final LivePortal portal;
  private String lastValidity="";private int validityTransitions,suspendedTicks;
  Handle(UUID id,ServerPlayer p,PocketLedger.Pocket r,LivePortal portal){this.id=id;player=p.getUUID();pocket=r.id();epoch=r.epoch();deadline=now()+LIFETIME;this.portal=portal;}
 }
 /** Ordinary non-global portal; it never becomes a persistent source-world entity. */
 public static final class LivePortal extends Portal {
  LivePortal(ServerLevel l){super(Portal.ENTITY_TYPE,l);}
  @Override public boolean save(CompoundTag ignored){return false;}
  @Override public boolean saveAsPassenger(CompoundTag ignored){return false;}
  @Override public boolean canTeleportEntity(Entity e){return false;}
  @Override public boolean canCollideWithEntity(Entity e){return false;}
  @Override public boolean broadcastToPlayer(ServerPlayer p){return portalAllowed(this,p);}
 }
 public LiveViews(){}
 public static long now(){return server==null?0:server.getTickCount();}
 public static boolean protectedDimension(ResourceKey<Level> d){return PocketService.DIMENSION.equals(d);}
 public static void owner(){if(server==null||!server.isSameThread())throw new IllegalStateException("Live view state must stay on the server thread");}
 public static PocketLedger.Pocket pocket(UUID id){try{return PocketService.ledger(server).get(id);}catch(Exception e){return null;}}
 public static String viewReason(ServerPlayer p,PocketLedger.Pocket r){return viewReason(p,r,true);}
 private static String viewReason(ServerPlayer p,PocketLedger.Pocket r,boolean persistence){
  if(!ENABLED||failed||stopping||server==null||p==null||p.isRemoved()||server.getPlayerList().getPlayer(p.getUUID())!=p)return "Preview unavailable";
  if(r==null||r.stage()!=PocketLedger.Stage.READY)return "Only an existing READY pocket can be previewed";
  String reason=PocketService.authorize(p,r,PocketLeases.Kind.REMOTE,false,BasePermissions.VIEW);if(!reason.isEmpty())return reason;
  if(p.distanceToSqr(Vec3.atCenterOf(BlockPos.of(r.anchor().entrance())))>24*24)return "Preview distance exceeded";
  if(persistence&&RepairService.roomBlocked(r.id()))return "Persistence quarantine";
  return "";
 }
 public static boolean valid(Handle h){
  if(server==null||!server.isSameThread())return false;
  String reason;
  if(now()>=h.deadline)reason="deadline";
  else if(h.portal.isRemoved())reason="portal removed: "+h.portal.getRemovalReason();
  else {var r=pocket(h.pocket);reason=r==null?"pocket absent":r.epoch()!=h.epoch?"epoch changed":viewReason(server.getPlayerList().getPlayer(h.player),r);}
  // Bounded diagnostics only: never cache authorization or change its outcome.
  // Observe transitions even between pre-tick cleanup and the final packet gate.
  if(!reason.equals(h.lastValidity)){
   if(h.validityTransitions++<32)AnyaStorage.LOG.info("LIVE_VIEW_VALIDITY tick={} handle={} pocket={} reason={} previous={}",now(),h.id,h.pocket,reason.isEmpty()?"valid":reason,h.lastValidity.isEmpty()?"valid":h.lastValidity);
   h.lastValidity=reason;
  }
  return reason.isEmpty();
 }
 /** Retain only the bounded load intent while a save receipt is pending.
  * This never authorizes a packet, entity pairing, Visit, or inventory access.
  * Keeping the same footprint avoids unload/save/reload feedback; a persistent
  * block still expires after 100 cumulative blocked pre-ticks. Blocks between
  * samples remain packet-gated and cannot extend the absolute handle deadline.
  */
 public static boolean loaderRetained(Handle h){
  if(server==null||!server.isSameThread()||now()>=h.deadline||h.portal.isRemoved())return false;
  var r=pocket(h.pocket);
  if(r==null||r.epoch()!=h.epoch||!viewReason(server.getPlayerList().getPlayer(h.player),r,false).isEmpty())return false;
  return !RepairService.roomBlocked(r.id())||h.suspendedTicks<MAX_SUSPENDED_TICKS;
 }
 public static Handle open(ServerPlayer p,UUID id)throws Exception{
  owner();var r=pocket(id);String reason=viewReason(p,r);if(!reason.isEmpty()||pressure){deniedOpens++;throw new IllegalStateException("Live view denied");}
  for(var h:HANDLES.values())if(h.player.equals(p.getUUID())&&h.pocket.equals(id)&&valid(h))return h; // never extend an existing deadline
  if(HANDLES.size()>=MAX_HANDLES||HANDLES.values().stream().filter(h->h.player.equals(p.getUUID())).count()>=MAX_PER_PLAYER)throw new IllegalStateException("View handle budget");
  var distinct=new HashSet<UUID>();HANDLES.values().forEach(h->distinct.add(h.pocket));
  for(var q:server.getPlayerList().getPlayers()){var l=PocketService.lease(q.getUUID());if(l!=null)distinct.add(l.pocket());}
  if(!distinct.contains(id)&&distinct.size()>=MAX_POCKETS)throw new IllegalStateException("Active preview/pocket budget");
  var portal=new LivePortal(p.serverLevel());var pos=Vec3.atCenterOf(BlockPos.of(r.anchor().entrance())).add(0,1.4,0);
  portal.setOriginPos(pos);portal.setOrientationAndSize(new Vec3(-1,0,0),new Vec3(0,1,0),3,3);
  portal.setDestinationDimension(PocketService.DIMENSION);portal.setDestination(new Vec3(r.x()+12.5,67,r.z()+3.5));
  portal.setTeleportable(false);portal.setInteractable(false);var f=Portal.class.getDeclaredField("crossPortalCollisionEnabled");f.setAccessible(true);f.setBoolean(portal,false);
  UUID handle=UUID.randomUUID();portal.portalTag=PREFIX+handle;portal.specificPlayerId=p.getUUID();
  var h=new Handle(handle,p,r,portal);HANDLES.put(handle,h);
  if(!p.serverLevel().addFreshEntity(portal)){HANDLES.remove(handle);throw new IllegalStateException("Could not publish ephemeral portal");}
  ImmPtlChunkTracking.getPlayerInfo(p).shouldUpdateImmediately=true;return h;
 }
 public static boolean close(ServerPlayer p,UUID id){owner();var h=HANDLES.get(id);if(h==null||!h.player.equals(p.getUUID()))return false;retire(id);reconcile();return true;}
 private static void retire(UUID id){var h=HANDLES.remove(id);if(h!=null){h.portal.discard();closed++;var p=server.getPlayerList().getPlayer(h.player);if(p!=null)ImmPtlChunkTracking.getPlayerInfo(p).shouldUpdateImmediately=true;}}
 public static void closePlayer(ServerPlayer p){owner();for(var h:new ArrayList<>(HANDLES.values()))if(h.player.equals(p.getUUID()))retire(h.id);reconcile();}
 public static boolean portalAllowed(Portal portal,ServerPlayer p){
  if(!ENABLED)return true;
  if(!protectedDimension(portal.getDestDim()))return true;
  if(!(portal instanceof LivePortal)||p==null)return false;
  for(var h:HANDLES.values())if(h.portal==portal&&h.player.equals(p.getUUID()))return valid(h);return false;
 }
 public static Handle portalHandle(UUID uuid){for(var h:HANDLES.values())if(h.portal.getUUID().equals(uuid))return h;return null;}
 public static PocketLedger.Pocket visiting(ServerPlayer p){
  if(p==null||server==null||!server.isSameThread()||!PocketService.inPocket(p.level()))return null;
  var l=PocketService.lease(p.getUUID());if(l==null||l.kind()!=PocketLeases.Kind.VISIT)return null;
  var r=pocket(l.pocket());if(r==null||r.epoch()!=l.epoch()||!PocketService.authorize(p,r,l.kind(),false,0).isEmpty())return null;return r;
 }
 public static boolean covers(PocketLedger.Pocket r,long key){int x=ChunkPos.getX(key),z=ChunkPos.getZ(key);return Math.abs(x-(r.x()>>4))<=DATA_RADIUS&&Math.abs(z-(r.z()>>4))<=DATA_RADIUS;}
 public static boolean allowedColumn(ServerPlayer p,long key){
  if(server==null||!server.isSameThread())return false;
  var visit=visiting(p);if(visit!=null&&covers(visit,key))return true;
  for(var h:HANDLES.values())if(h.player.equals(p.getUUID())&&valid(h)&&covers(pocket(h.pocket),key))return true;return false;
 }
 public static boolean anyAccess(ServerPlayer p){if(visiting(p)!=null)return true;return HANDLES.values().stream().anyMatch(h->h.player.equals(p.getUUID())&&valid(h));}
 public static Set<Long> requested(ServerPlayer p){
  Set<Long> result=new HashSet<>();var ids=new HashSet<UUID>();var visit=visiting(p);if(visit!=null)ids.add(visit.id());
  for(var h:HANDLES.values())if(h.player.equals(p.getUUID())&&loaderRetained(h))ids.add(h.pocket);
  for(UUID id:ids){var r=pocket(id);for(int dx=-DATA_RADIUS;dx<=DATA_RADIUS;dx++)for(int dz=-DATA_RADIUS;dz<=DATA_RADIUS;dz++)result.add(ChunkPos.asLong((r.x()>>4)+dx,(r.z()>>4)+dz));}return result;
 }
 /** Original source-world loaders remain. All protected-destination loaders, direct or nested, are replaced by the authorized union. */
 public static Consumer<ChunkLoader> filterLoader(Consumer<ChunkLoader> original){return l->{if(ENABLED&&protectedDimension(l.dimension()))autoLoadersSuppressed++;else original.accept(l);};}
 public static void appendLoaders(ServerPlayer p,Consumer<ChunkLoader> sink){
  if(!ENABLED||server==null||stopping||failed)return;var ids=new HashSet<UUID>();var visit=visiting(p);if(visit!=null)ids.add(visit.id());
  for(var h:HANDLES.values())if(h.player.equals(p.getUUID())&&loaderRetained(h))ids.add(h.pocket);
  for(UUID id:ids){var r=pocket(id);sink.accept(new ChunkLoader(new DimensionalChunkPos(PocketService.DIMENSION,new ChunkPos(r.x()>>4,r.z()>>4)),DATA_RADIUS));}
 }
 private static Object field(Class<?> c,Object object,String name){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(Exception e){throw new IllegalStateException("Pinned CE field unavailable: "+name,e);}}
 @SuppressWarnings("unchecked") public static Map<ResourceKey<Level>,? extends Map<Long,? extends Map<ServerPlayer,ImmPtlChunkTracking.PlayerWatchRecord>>> watches(){return (Map)field(ImmPtlChunkTracking.class,null,"chunkWatchRecords");}
 /** Revocation removes only invalid player/column records. Shared viewers and visit/recovery tickets are never blanket-cleared. */
 public static void reconcile(){
  if(!ENABLED||server==null)return;owner();
  var map=watches().get(PocketService.DIMENSION);
  if(map!=null){var chunks=map.entrySet().iterator();while(chunks.hasNext()){var row=chunks.next();long chunkKey=row.getKey();var players=row.getValue().entrySet().iterator();while(players.hasNext()){var en=players.next();var recipient=en.getKey();if(!allowedColumn(recipient,chunkKey)){
   // fastutil invalidates its MapEntry when iterator.remove() runs. Keep stable
   // recipient/column values before removal; never read the removed entry again.
   var rec=en.getValue();rec.isValid=false;players.remove();if(rec.isLoadedToPlayer&&recipient.connection!=null)PacketFence.forget(recipient,chunkKey);
  }}if(row.getValue().isEmpty())chunks.remove();}}
  for(var p:server.getPlayerList().getPlayers()){
   var info=ImmPtlChunkTracking.getPlayerInfo(p);info.additionalChunkLoaders.removeIf(l->protectedDimension(l.dimension()));
   for(var row:info.distanceToPendingChunks)if(row!=null)row.removeIf(rec->protectedDimension(rec.dimension)&&(!rec.isValid||!allowedColumn(p,rec.chunkPos)));
   if(!anyAccess(p))info.visibleDimensions.remove(PocketService.DIMENSION);
  }
  var l=PocketService.dimension(server);if(l!=null){Set<Long> union=new HashSet<>();for(var p:server.getPlayerList().getPlayers())union.addAll(requested(p));ImmPtlChunkTickets.get(l).purge(l,union::contains);}
 }
 @SubscribeEvent public static void commands(RegisterCommandsEvent e){e.getDispatcher().register(Commands.literal("anyaliveview").then(Commands.literal("open").then(Commands.argument("pocket",StringArgumentType.word()).executes(c->{try{var p=c.getSource().getPlayerOrException();var h=open(p,UUID.fromString(StringArgumentType.getString(c,"pocket")));c.getSource().sendSuccess(()->Component.literal("View handle "+h.id+" deadline "+h.deadline),false);return 1;}catch(Exception ex){c.getSource().sendFailure(Component.literal("Live view denied"));return 0;}}))).then(Commands.literal("close").executes(c->{closePlayer(c.getSource().getPlayerOrException());return 1;})));}
 // Logout can occur after ServerTickEvent.Pre. Retire this player's view scopes
 // immediately rather than leaving them until the next tick's validity sweep.
 // closePlayer reconciles the authorized union; other viewers, visitors and
 // save/recovery obligations retain their existing owners.
 @SubscribeEvent(priority=EventPriority.HIGHEST)
 public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e){
  if(!ENABLED||server==null||!(e.getEntity() instanceof ServerPlayer p)||p.getServer()!=server)return;
  closePlayer(p);
 }
 @SubscribeEvent public static void started(ServerStartedEvent e){server=e.getServer();stopping=failed=pressure=false;HANDLES.clear();PacketFence.clear();if(ENABLED){owner();watches();}}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ServerTickEvent.Pre e){if(!ENABLED||server!=e.getServer())return;try{
  for(var h:new ArrayList<>(HANDLES.values())){
   if(!valid(h)){
    if(!loaderRetained(h))retire(h.id);
    else if(++h.suspendedTicks>=MAX_SUSPENDED_TICKS)retire(h.id);
   }
  }
  var l=PocketService.dimension(server);var mem=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();pressure=l!=null&&l.getChunkSource().getLoadedChunksCount()>MAX_CACHE||mem.getUsed()>mem.getMax()*.80;
  if(pressure)for(var id:new ArrayList<>(HANDLES.keySet()))retire(id);reconcile();
 }catch(Throwable ex){failed=true;AnyaStorage.LOG.error("LIVE_VIEW_FAIL_CLOSED",ex);for(var id:new ArrayList<>(HANDLES.keySet()))retire(id);}}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void stop(ServerStoppingEvent e){if(!ENABLED)return;stopping=true;for(var id:new ArrayList<>(HANDLES.keySet()))retire(id);reconcile();}
}
