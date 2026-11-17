package dev.anya.storage.liveview.mixin;

import dev.anya.storage.liveview.LiveViews;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.portal.Portal;

/** The pinned CE tracker replaces vanilla and omits broadcastToPlayer. Gate before seenBy/addPairing. */
@Mixin(targets="net.minecraft.server.level.ChunkMap$TrackedEntity",priority=900)
public abstract class ScopedEntityTracking {
 @Inject(method="recWatches",at=@At("HEAD"),cancellable=true,remap=false,require=1)
 private static void recipient(Entity entity,int range,ImmPtlChunkTracking.PlayerWatchRecord record,ServerPlayer player,CallbackInfoReturnable<Boolean> ci){
  if(LiveViews.ENABLED&&entity instanceof Portal portal&&LiveViews.protectedDimension(portal.getDestDim())&&!LiveViews.portalAllowed(portal,player))ci.setReturnValue(false);
 }
}
