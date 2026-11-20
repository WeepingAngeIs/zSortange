package dev.anya.storage.minimal.mixin;
import dev.anya.storage.minimal.LeanMetrics;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ServerChunkCache.class)
public abstract class DrainCacheMixin {
 @Shadow @Final public ServerLevel level;
 @Inject(method="runDistanceManagerUpdates",at=@At("HEAD")) private void a(CallbackInfoReturnable<Boolean> c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(level))LeanMetrics.begin(3);}
 @Inject(method="runDistanceManagerUpdates",at=@At("RETURN")) private void b(CallbackInfoReturnable<Boolean> c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(level))LeanMetrics.end(3);}
 @Inject(method="tick",at=@At("HEAD")) private void d(CallbackInfo c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(level))LeanMetrics.begin(4);}
 @Inject(method="tick",at=@At("RETURN")) private void e(CallbackInfo c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(level))LeanMetrics.end(4);}
}
