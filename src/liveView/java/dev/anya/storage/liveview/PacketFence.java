package dev.anya.storage.liveview;

import dev.anya.storage.StorageEntity;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
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
 private static final ThreadLocal<Boolean> CLEANUP=ThreadLocal.withInitial(()->false);
 public static void clear(){accepted=denied=forgotten=privacyChecks=0;COUNTS.clear();SENT.clear();SENT_ENTITIES.clear();}
 public static synchronized void record(ServerPlayer p,String kind,boolean allow){if(allow)accepted++;else denied++;COUNTS.computeIfAbsent(p.getUUID(),k->new HashMap<>()).merge((allow?"allow:":"deny:")+kind,1L,Long::sum);}
 public static void forget(ServerPlayer p,long key){
  var sent=SENT.get(p.getUUID());if(sent==null||!sent.remove(key))return;
  CLEANUP.set(true);try{PacketRedirection.sendRedirectedMessage(p,PocketService.DIMENSION,new ClientboundForgetLevelChunkPacket(new ChunkPos(key)));forgotten++;}finally{CLEANUP.set(false);}
 }
 /** Retire only this connection's projected data before vanilla destroys its client worlds. */
 public static void beforeClientWorldReset(ServerPlayer p){
  LiveViews.owner();
  for(long key:new HashSet<>(SENT.getOrDefault(p.getUUID(),Set.of())))forget(p,key);
  SENT.remove(p.getUUID());SENT_ENTITIES.remove(p.getUUID());
 }
 private static boolean column(ServerPlayer p,BlockPos pos){return LiveViews.allowedColumn(p,new ChunkPos(pos).toLong());}
 private static boolean safeTag(CompoundTag t){return t==null||Set.of("Symbol","ShowLabels").containsAll(t.getAllKeys());}
 private static boolean safeChunk(long key){
  var l=PocketService.dimension(LiveViews.server);var chunk=l==null?null:l.getChunkSource().getChunkNow(ChunkPos.getX(key),ChunkPos.getZ(key));if(chunk==null)return false;
  for(var be:chunk.getBlockEntities().values()){
   if(!(be instanceof StorageEntity s)||s.pocketId==null||!safeTag(s.getUpdateTag(l.registryAccess())))return false;privacyChecks++;
  }return true;
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
  if(packet instanceof ClientboundForgetLevelChunkPacket){boolean yes=CLEANUP.get();record(p,"forget",yes);return yes?packet:null;}
  if(packet instanceof ClientboundRemoveEntitiesPacket removed){var sent=SENT_ENTITIES.get(p.getUUID());if(sent==null)return null;var ids=new ArrayList<Integer>();for(int id:removed.getEntityIds())if(sent.remove(id))ids.add(id);record(p,"entity-forget",!ids.isEmpty());return ids.isEmpty()?null:new ClientboundRemoveEntitiesPacket(ids.stream().mapToInt(Integer::intValue).toArray());}
  boolean yes=false;
  if(packet instanceof ClientboundLevelChunkWithLightPacket chunk){long key=ChunkPos.asLong(chunk.getX(),chunk.getZ());yes=LiveViews.allowedColumn(p,key)&&safeChunk(key);if(yes)SENT.computeIfAbsent(p.getUUID(),k->new HashSet<>()).add(key);}
  else if(packet instanceof ClientboundLightUpdatePacket light)yes=LiveViews.allowedColumn(p,ChunkPos.asLong(light.getX(),light.getZ()));
  else if(packet instanceof ClientboundBlockUpdatePacket block)yes=column(p,block.getPos());
  else if(packet instanceof ClientboundSectionBlocksUpdatePacket blocks){boolean[] ok={true};blocks.runUpdates((pos,state)->{if(!column(p,pos))ok[0]=false;});yes=ok[0]&&LiveViews.anyAccess(p);}
  else if(packet instanceof ClientboundBlockEntityDataPacket be)yes=column(p,be.getPos())&&safeTag(be.getTag());
  else if(packet instanceof ClientboundBlockEventPacket be)yes=column(p,be.getPos());
  else if(packet instanceof ClientboundSetTimePacket||packet instanceof ClientboundGameEventPacket)yes=LiveViews.anyAccess(p);
  // Remote entity previews are intentionally outside the supported first subset.
  else if(LiveViews.visiting(p)!=null&&packet instanceof ClientboundAddEntityPacket entity){yes=column(p,BlockPos.containing(entity.getX(),entity.getY(),entity.getZ()));if(yes)SENT_ENTITIES.computeIfAbsent(p.getUUID(),k->new HashSet<>()).add(entity.getId());}
  else if(LiveViews.visiting(p)!=null&&packet.getClass().getSimpleName().contains("Entity")){
   try{Integer id=null;for(String name:List.of("getId","getEntityId","id"))try{Object v=packet.getClass().getMethod(name).invoke(packet);if(v instanceof Integer i){id=i;break;}}catch(NoSuchMethodException ignored){}
    if(id!=null&&SENT_ENTITIES.getOrDefault(p.getUUID(),Set.of()).contains(id)){var entity=PocketService.dimension(p.server).getEntity(id);yes=entity!=null&&column(p,entity.blockPosition());}
   }catch(Exception ignored){yes=false;}
  }
  record(p,packet.getClass().getSimpleName(),yes);return yes?packet:null;
 }
 private PacketFence(){}
}
