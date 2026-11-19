package dev.anya.storage.liveview;

import dev.anya.storage.pocket.PocketService;
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
 private static final PositionDeliveryLedger<ServerGamePacketListenerImpl> delivered = new PositionDeliveryLedger<>();
 public static final Map<UUID,Set<Long>> SENT = delivered.columns;
 private record ForgetRequest(ServerGamePacketListenerImpl connection, long column) {}
 private static final ThreadLocal<ForgetRequest> CLEANUP = new ThreadLocal<>();
 public static void clear(){accepted=denied=forgotten=privacyChecks=0;COUNTS.clear();delivered.clear();}
 public static synchronized void record(ServerPlayer p,String kind,boolean allow){if(allow)accepted++;else denied++;COUNTS.computeIfAbsent(p.getUUID(),k->new HashMap<>()).merge((allow?"allow:":"deny:")+kind,1L,Long::sum);}
 /** A reconnect may use the same player UUID. Only its current socket may access the delivery ledger. */
 private static boolean bindCurrentConnection(ServerPlayer player) {
  LiveViews.owner();
  var connection = player.connection;
  UUID id = player.getUUID();
  if (connection == null || connection.player != player || !connection.getConnection().isConnected()
      || LiveViews.server.getPlayerList().getPlayer(id) != player) return false;
  delivered.bind(id, connection);
  return true;
 }
 public static void disconnected(ServerPlayer player) {
  LiveViews.owner();
  delivered.disconnected(player.getUUID(), player.connection);
 }
 private static boolean isCleanupForget(ServerPlayer player, ClientboundForgetLevelChunkPacket packet) {
  var request = CLEANUP.get();
  return request != null && request.connection() == player.connection && request.column() == packet.pos().toLong();
 }
 public static void forget(ServerPlayer p,long key){
  if (!bindCurrentConnection(p)) return;
  delivered.forget(p.getUUID(), p.connection, key, column -> sendForget(p, column));
 }
 private static void sendForget(ServerPlayer p, long key) {
  var previous = CLEANUP.get();
  CLEANUP.set(new ForgetRequest(p.connection, key));
  try {
   PacketRedirection.sendRedirectedMessage(p,PocketService.DIMENSION,new ClientboundForgetLevelChunkPacket(new ChunkPos(key)));
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
  var entities = delivered.entities.get(id);
  if ((sent == null || sent.isEmpty()) && (entities == null || entities.isEmpty())) return;
  var visit = LiveViews.visiting(player);
  delivered.reconcileColumns(id, player.connection, key -> visit != null && LiveViews.covers(visit, key),
      key -> sendForget(player, key));
  if (visit == null && entities != null && !entities.isEmpty()) {
   // The removal filter consumes only IDs previously sent on this connection.
   int[] removed = entities.stream().mapToInt(Integer::intValue).toArray();
   PacketRedirection.sendRedirectedMessage(player, PocketService.DIMENSION, new ClientboundRemoveEntitiesPacket(removed));
  }
 }
 /** Retire only this connection's projected data before vanilla destroys its client worlds. */
 public static void beforeClientWorldReset(ServerPlayer p){
  if (!bindCurrentConnection(p)) return;
  var connection = p.connection;
  delivered.reconcileColumns(p.getUUID(), connection, key -> false, key -> sendForget(p, key));
  delivered.clearDelivered(p.getUUID(), connection);
 }
 @SuppressWarnings({"unchecked","rawtypes"}) public static Packet<?> filter(ServerPlayer p,Packet<?> packet){return filter(p,packet,PacketRedirection.getForceRedirectDimension(),0);}
 @SuppressWarnings({"unchecked","rawtypes"}) private static Packet<?> filter(ServerPlayer p,Packet<?> packet,ResourceKey<Level> dimension,int depth){
  if(!LiveViews.ENABLED)return packet;if(depth>8){record(p,"nesting",false);return null;}
  if (packet instanceof ClientboundBundlePacket bundle) {
   return PositionTerrainPolicy.filterBundle(bundle, part -> filter(p, part, dimension, depth + 1));
  }
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
  if (packet instanceof ClientboundRemoveEntitiesPacket removal) {
   var kept = PositionTerrainPolicy.removeDelivered(removal, delivered.entities.get(p.getUUID()));
   record(p, "entity-forget", kept != null);
   return kept;
  }
  // The physical visit is resolved at this final send, independently for every recipient.
  // The removal branch above remains available after its visit has ended.
  var visit = LiveViews.visiting(p);
  ServerLevel room = visit == null ? null : PocketService.dimension(LiveViews.server);
  Set<Integer> sent = visit != null && packet instanceof ClientboundAddEntityPacket
      ? delivered.entities.computeIfAbsent(p.getUUID(), id -> new HashSet<>())
      : delivered.entities.getOrDefault(p.getUUID(), Set.of());
  var kept = PositionTerrainPolicy.filterData(room, visit, packet, sent, LiveViews::pocket);
  if (kept instanceof ClientboundLevelChunkWithLightPacket chunk) {
   privacyChecks += room.getChunkSource().getChunkNow(chunk.getX(), chunk.getZ()).getBlockEntities().size();
   SENT.computeIfAbsent(p.getUUID(), id -> new HashSet<>()).add(ChunkPos.asLong(chunk.getX(), chunk.getZ()));
  }
  record(p, packet.getClass().getSimpleName(), kept != null);
  return kept;
 }
 private PacketFence(){}
}
