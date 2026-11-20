package dev.anya.storage.liveview.mixin;
import dev.anya.storage.liveview.LiveViews;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import qouteall.imm_ptl.core.portal.Portal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=Portal.class,remap=false)
public abstract class ScopedPortal {
 @Inject(method="broadcastToPlayer",at=@At("HEAD"),cancellable=true,remap=true)
 private void recipient(ServerPlayer p,CallbackInfoReturnable<Boolean> ci){var portal=(Portal)(Object)this;if(LiveViews.ENABLED&&LiveViews.protectedDimension(portal.getDestDim()))ci.setReturnValue(LiveViews.portalAllowed(portal,p));}
 @Inject(method="canTeleportEntity",at=@At("HEAD"),cancellable=true)
 private void noTravel(Entity entity,CallbackInfoReturnable<Boolean> ci){if(LiveViews.ENABLED&&LiveViews.protectedDimension(((Portal)(Object)this).getDestDim()))ci.setReturnValue(false);}
 @Inject(method="isInteractableBy",at=@At("HEAD"),cancellable=true)
 private void noReach(Player p,CallbackInfoReturnable<Boolean> ci){if(LiveViews.ENABLED&&LiveViews.protectedDimension(((Portal)(Object)this).getDestDim()))ci.setReturnValue(false);}
 @Inject(method="canCollideWithEntity",at=@At("HEAD"),cancellable=true)
 private void noCollision(Entity p,CallbackInfoReturnable<Boolean> ci){if(LiveViews.ENABLED&&LiveViews.protectedDimension(((Portal)(Object)this).getDestDim()))ci.setReturnValue(false);}
}
