package dev.anya.storage.minimal.mixin;
import dev.anya.storage.minimal.LeanMetrics;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ChunkMap.class)
public abstract class DrainMapMixin {
 @Shadow @Final private ServerLevel level;
 @Inject(method="processUnloads",at=@At("HEAD")) private void a(CallbackInfo c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(level))LeanMetrics.begin(0);}
 @Inject(method="processUnloads",at=@At("RETURN")) private void b(CallbackInfo c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(level))LeanMetrics.end(0);}
 @Inject(method="save",at=@At("HEAD")) private void d(CallbackInfoReturnable<Boolean> c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(level))LeanMetrics.begin(1);}
 @Inject(method="save",at=@At("RETURN")) private void e(CallbackInfoReturnable<Boolean> c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(level))LeanMetrics.end(1);}
 @Inject(method="saveAllChunks",at=@At("HEAD")) private void f(CallbackInfo c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(level))LeanMetrics.begin(2);}
 @Inject(method="saveAllChunks",at=@At("RETURN")) private void g(CallbackInfo c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(level))LeanMetrics.end(2);}
}
