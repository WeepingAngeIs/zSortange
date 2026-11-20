package dev.anya.storage.liveview.mixin;

import dev.anya.storage.AnyaStorage;
import dev.anya.storage.liveview.LiveViews;
import dev.anya.storage.liveview.PacketFence;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPPerServerInfo;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import java.util.*;

/**
 * The pinned CE conventional-travel hook resets delivery only when its optional
 * portal-generation manager exists. With generation disabled, vanilla respawn
 * clears client worlds but CE still considers their chunks delivered. Restore
 * that per-player reset only for Anya's existing authorized travel path. This
 * does not perform travel, create a lease, or remove any chunk/save ticket.
 */
@Mixin(value=PocketService.class,remap=false)
public abstract class ConventionalPocketSync {
 private static Map<Object,String> otherWatches(ServerPlayer moving){
  Map<Object,String> out=new HashMap<>();
  for(var dimension:LiveViews.watches().values())for(var row:dimension.values())for(var rec:row.values())if(rec.player!=moving)
   out.put(rec,rec.isValid+"/"+rec.isLoadedToPlayer+"/"+rec.lastWatchGeneration+"/"+rec.distanceToSource+"/"+rec.isBoundary);
  return out;
 }
 @Inject(method="teleport",at=@At("HEAD"))
 private static void before(ServerPlayer player,ServerLevel target,Vec3 pos,float yaw,float pitch,CallbackInfo ci){
  if(!LiveViews.ENABLED||player.server!=LiveViews.server||player.level()==target)return;
  if(!LiveViews.protectedDimension(player.level().dimension())&&!LiveViews.protectedDimension(target.dimension()))return;
  // When present, CE's own hook performs this reset inside ServerPlayer.teleportTo.
  if(IPPerServerInfo.of(player.server).customPortalGenManager!=null)return;
  LiveViews.owner();
  var other=otherWatches(player);var handles=new LinkedHashMap<>(LiveViews.HANDLES);
  var leases=new HashMap<UUID,Object>();for(var p:player.server.getPlayerList().getPlayers())leases.put(p.getUUID(),PocketService.lease(p.getUUID()));
  int tickets=PocketService.ownedTicketRequests();var targetPos=new ChunkPos(BlockPos.containing(pos));
  var row=ImmPtlChunkTracking.getWatchRecordForChunk(target.dimension(),targetPos.x,targetPos.z);
  var previous=row==null?null:row.get(player);boolean previouslyDelivered=previous!=null&&previous.isValid&&previous.isLoadedToPlayer;
  // Retain non-pocket extra-loader identities. The adapter already refuses extras
  // for the protected dimension; its normal authorized union is rebuilt next tick.
  var extras=new ArrayList<>(ImmPtlChunkTracking.getPlayerInfo(player).additionalChunkLoaders);
  PacketFence.beforeClientWorldReset(player);
  ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player);
  for(var loader:extras)if(!LiveViews.protectedDimension(loader.dimension()))ImmPtlChunkTracking.addPerPlayerAdditionalChunkLoader(player,loader);
  ImmPtlChunkTracking.getPlayerInfo(player).shouldUpdateImmediately=true;
  if(!other.equals(otherWatches(player))||!handles.equals(LiveViews.HANDLES)||tickets!=PocketService.ownedTicketRequests())throw new IllegalStateException("Per-player delivery reset changed unrelated ownership");
  for(var p:player.server.getPlayerList().getPlayers())if(!Objects.equals(leases.get(p.getUUID()),PocketService.lease(p.getUUID())))throw new IllegalStateException("Delivery reset changed a Visit/remote lease");
  AnyaStorage.LOG.info("LIVE_CONVENTIONAL_RESYNC player={} from={} to={} target={} previously_delivered={} optional_generation_manager=false other_watch_records_preserved={} handles_preserved={} anya_requests_preserved={}",player.getGameProfile().getName(),player.level().dimension().location(),target.dimension().location(),targetPos,previouslyDelivered,other.size(),handles.size(),tickets);
 }
}
