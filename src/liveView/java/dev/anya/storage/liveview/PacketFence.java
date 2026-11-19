package dev.anya.storage.liveview;

import dev.anya.storage.pocket.PocketLedger;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.network.*;
import java.util.*;

/** Last server-listener boundary, after CE redirection/bundling. Unknown protected preview packets fail closed. */
public final class PacketFence {
 public static long accepted,denied,forgotten,privacyChecks;
 public static final Map<UUID,Map<String,Long>> COUNTS=new HashMap<>();
 public static final Map<UUID,Set<Long>> SENT=new HashMap<>();
 private static final Map<UUID,Set<Integer>> SENT_ENTITIES=new HashMap<>();
 private static final Map<UUID,ServerGamePacketListenerImpl> connections=new HashMap<>();
 private record ForgetRequest(ServerGamePacketListenerImpl connection, long column) {}
 private static final ThreadLocal<ForgetRequest> CLEANUP = new ThreadLocal<>();
 public static void clear(){accepted=denied=forgotten=privacyChecks=0;COUNTS.clear();SENT.clear();SENT_ENTITIES.clear();connections.clear();}
 public static synchronized void record(ServerPlayer p,String kind,boolean allow){if(allow)accepted++;else denied++;COUNTS.computeIfAbsent(p.getUUID(),k->new HashMap<>()).merge((allow?"allow:":"deny:")+kind,1L,Long::sum);}
 private static void clearDelivered(UUID player) {
  SENT.remove(player);
  SENT_ENTITIES.remove(player);
 }
 /** A reconnect may use the same player UUID. Only its current socket may access the delivery ledger. */
 private static boolean bindCurrentConnection(ServerPlayer player) {
  LiveViews.owner();
  var connection = player.connection;
  UUID id = player.getUUID();
  if (connection == null || connection.player != player || !connection.getConnection().isConnected()
      || LiveViews.server.getPlayerList().getPlayer(id) != player) return false;
  if (connections.put(id, connection) != connection) clearDelivered(id);
  return true;
 }
 public static void disconnected(ServerPlayer player) {
  LiveViews.owner();
  if (connections.remove(player.getUUID(), player.connection)) clearDelivered(player.getUUID());
 }
 private static boolean isCleanupForget(ServerPlayer player, ClientboundForgetLevelChunkPacket packet) {
  var request = CLEANUP.get();
  return request != null && request.connection() == player.connection && request.column() == packet.pos().toLong();
 }
 public static void forget(ServerPlayer p,long key){
  if (!bindCurrentConnection(p)) return;
  var sent=SENT.get(p.getUUID());if(sent==null||!sent.contains(key))return;
  var previous = CLEANUP.get();
  CLEANUP.set(new ForgetRequest(p.connection, key));
  try {
   PacketRedirection.sendRedirectedMessage(p,PocketService.DIMENSION,new ClientboundForgetLevelChunkPacket(new ChunkPos(key)));
   sent.remove(key);
   forgotten++;
  } finally {
   if (previous == null) CLEANUP.remove(); else CLEANUP.set(previous);
  }
 }
 /** CE may already have dropped its watch record while the client still holds the delivered column. */
 public static void reconcileDelivered(ServerPlayer player) {
  if (!bindCurrentConnection(player)) return;
  var id = player.getUUID();
  var sent = SENT.get(id);
  var entities = SENT_ENTITIES.get(id);
  if ((sent == null || sent.isEmpty()) && (entities == null || entities.isEmpty())) return;
  var visit = LiveViews.visiting(player);
  if (sent != null && !sent.isEmpty()) {
   for (long key : new HashSet<>(sent)) {
    if (visit == null || !LiveViews.covers(visit, key)) forget(player, key);
   }
  }
  if (visit == null && entities != null && !entities.isEmpty()) {
   // The removal filter consumes only IDs previously sent on this connection.
   int[] removed = entities.stream().mapToInt(Integer::intValue).toArray();
   PacketRedirection.sendRedirectedMessage(player, PocketService.DIMENSION, new ClientboundRemoveEntitiesPacket(removed));
  }
 }
 /** Retire only this connection's projected data before vanilla destroys its client worlds. */
 public static void beforeClientWorldReset(ServerPlayer p){
  if (!bindCurrentConnection(p)) return;
  for(long key:new HashSet<>(SENT.getOrDefault(p.getUUID(),Set.of())))forget(p,key);
  if (connections.get(p.getUUID()) == p.connection) clearDelivered(p.getUUID());
 }
 private static boolean visitColumn(PocketLedger.Pocket visit, BlockPos pos) {
  return LiveViews.covers(visit, ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
 }
 @SuppressWarnings({"unchecked","rawtypes"}) public static Packet<?> filter(ServerPlayer p,Packet<?> packet){return filter(p,packet,PacketRedirection.getForceRedirectDimension(),0);}
 @SuppressWarnings({"unchecked","rawtypes"}) private static Packet<?> filter(ServerPlayer p,Packet<?> packet,ResourceKey<Level> dimension,int depth){
  if(!LiveViews.ENABLED)return packet;if(depth>8){record(p,"nesting",false);return null;}
  if(packet instanceof ClientboundBundlePacket bundle){List<Packet<? super ClientGamePacketListener>> out=new ArrayList<>();for(var part:bundle.subPackets()){var kept=filter(p,part,dimension,depth+1);if(kept!=null)out.add((Packet)kept);}return out.isEmpty()?null:new ClientboundBundlePacket(out);}
  if(packet instanceof ClientboundCustomPayloadPacket custom){
   if(custom.payload() instanceof PacketRedirection.Payload redirected){
    if(LiveViews.server==null||!LiveViews.server.isSameThread()){record(p,"off-thread-redirection",false);return null;}
    ResourceKey<Level> dim=PortalAPI.serverIntToDimKey(p.server,redirected.dimensionIntId());var kept=filter(p,redirected.packet(),dim,depth+1);
    return kept==null?null:kept==redirected.packet()?packet:PacketRedirection.createRedirectedMessage(p.server,dim,(Packet)kept);
   }
   if(custom.payload() instanceof ImmPtlNetworking.PortalSyncPacket sync){
    var h=LiveViews.portalHandle(sync.uuid());boolean protectedPortal=h!=null||sync.extraData().getString("dimensionTo").equals(PocketService.DIMENSION.location().toString());
    if(protectedPortal){boolean yes=h!=null&&h.player.equals(p.getUUID())&&LiveViews.valid(h)&&!sync.extraData().getBoolean("teleportable")&&!sync.extraData().getBoolean("interactable");record(p,"portal",yes);return yes?packet:null;}
   }
   if(custom.payload() instanceof ImmPtlNetworking.GlobalPortalSyncPacket sync&&sync.data().toString().contains(PocketService.DIMENSION.location().toString())){record(p,"global-protected-portal",false);return null;}
  }
  // A protected portal is spawned in its SOURCE dimension. Its ordinary entity
  // shell must be authorized too, before the protected-destination early return.
  // Filtering only PortalSyncPacket leaves an uninitialized shell at denied clients.
  if(packet instanceof ClientboundAddEntityPacket spawn&&spawn.getType()==qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE){
   if(LiveViews.server==null||!LiveViews.server.isSameThread()){record(p,"off-thread-portal-spawn",false);return null;}
   var h=LiveViews.portalHandle(spawn.getUUID());var world=dimension==null?p.serverLevel():p.server.getLevel(dimension);
   var entity=world==null?null:world.getEntity(spawn.getUUID());
   if(h!=null||entity instanceof qouteall.imm_ptl.core.portal.Portal portal&&LiveViews.protectedDimension(portal.getDestDim())||entity==null){
    boolean yes=h!=null&&h.player.equals(p.getUUID())&&LiveViews.valid(h);record(p,"portal-spawn",yes);return yes?packet:null;
   }
  }
  if(!LiveViews.protectedDimension(dimension))return packet;
  if(LiveViews.server==null||!LiveViews.server.isSameThread()){record(p,"off-thread",false);return null;}
  if (!bindCurrentConnection(p)) return null;
  if (packet instanceof ClientboundForgetLevelChunkPacket forgottenChunk) {
   boolean yes = isCleanupForget(p, forgottenChunk);
   record(p, "forget", yes);
   return yes ? packet : null;
  }
  if(packet instanceof ClientboundRemoveEntitiesPacket removed){var sent=SENT_ENTITIES.get(p.getUUID());if(sent==null)return null;var ids=new ArrayList<Integer>();for(int id:removed.getEntityIds())if(sent.remove(id))ids.add(id);record(p,"entity-forget",!ids.isEmpty());return ids.isEmpty()?null:new ClientboundRemoveEntitiesPacket(ids.stream().mapToInt(Integer::intValue).toArray());}
  // Cleanup above remains possible after a visit ends. Every positive data send
  // below requires a physical visit at delivery time, including already queued packets.
  var visit = LiveViews.visiting(p);
  if (visit == null) {
   record(p, packet.getClass().getSimpleName(), false);
   return null;
  }
  boolean yes=false;
  if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
   long key = ChunkPos.asLong(chunk.getX(), chunk.getZ());
   ServerLevel room = PocketService.dimension(LiveViews.server);
   yes = LiveViews.covers(visit, key) && PocketChestPrivacy.publicChunk(room, chunk, LiveViews::pocket);
   if (yes) {
    privacyChecks += room.getChunkSource().getChunkNow(chunk.getX(), chunk.getZ()).getBlockEntities().size();
    SENT.computeIfAbsent(p.getUUID(), k -> new HashSet<>()).add(key);
   }
  }
  else if (packet instanceof ClientboundLightUpdatePacket light) {
   yes = LiveViews.covers(visit, ChunkPos.asLong(light.getX(), light.getZ()));
  } else if (packet instanceof ClientboundBlockUpdatePacket block) {
   yes = visitColumn(visit, block.getPos());
  } else if (packet instanceof ClientboundSectionBlocksUpdatePacket blocks) {
   boolean[] observed = {false};
   boolean[] outside = {false};
   blocks.runUpdates((pos, state) -> {
    observed[0] = true;
    if (!visitColumn(visit, pos)) outside[0] = true;
   });
   yes = observed[0] && !outside[0];
  }
  else if (packet instanceof ClientboundBlockEntityDataPacket update) {
   yes = visitColumn(visit, update.getPos())
       && PocketChestPrivacy.publicUpdate(PocketService.dimension(LiveViews.server), update, LiveViews::pocket);
  } else if (packet instanceof ClientboundBlockEventPacket event) {
   BlockPos pos = event.getPos();
   yes = PocketChestPrivacy.visitorAt(visit, pos);
  }
  else if(packet instanceof ClientboundSetTimePacket||packet instanceof ClientboundGameEventPacket)yes=true;
  else if (packet instanceof ClientboundAddEntityPacket spawn) {
   var entity = p.serverLevel().getEntity(spawn.getId());
   yes = entity != null && entity.getUUID().equals(spawn.getUUID()) && entity.getType() == spawn.getType()
       && PocketChestPrivacy.visitorAt(visit, entity.blockPosition())
       && PocketChestPrivacy.visitorAt(visit, BlockPos.containing(spawn.getX(), spawn.getY(), spawn.getZ()));
   if (yes) SENT_ENTITIES.computeIfAbsent(p.getUUID(), k -> new HashSet<>()).add(spawn.getId());
  }
  else if(packet.getClass().getSimpleName().contains("Entity")){
   try{Integer id=null;for(String name:List.of("getId","getEntityId","id"))try{Object v=packet.getClass().getMethod(name).invoke(packet);if(v instanceof Integer i){id=i;break;}}catch(NoSuchMethodException ignored){}
    if (id != null && SENT_ENTITIES.getOrDefault(p.getUUID(), Set.of()).contains(id)) {
     var entity = p.serverLevel().getEntity(id);
     yes = entity != null && PocketChestPrivacy.visitorAt(visit, entity.blockPosition());
     if (packet instanceof ClientboundTeleportEntityPacket teleport) {
      yes &= PocketChestPrivacy.visitorAt(visit,
          BlockPos.containing(teleport.getX(), teleport.getY(), teleport.getZ()));
     }
    }
   }catch(Exception ignored){yes=false;}
  }
  record(p,packet.getClass().getSimpleName(),yes);return yes?packet:null;
 }
 private PacketFence(){}
}
