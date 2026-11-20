package dev.anya.storage.equalwork.mixin;
import dev.anya.storage.equalwork.Metrics;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Queue;
@Mixin(ChunkMap.class) public abstract class CallbackObserveMixin {
 @Shadow @Final private ServerLevel level;
 @Shadow @Final private Queue<Runnable> unloadQueue;
 @Inject(method="processUnloads",at=@At("HEAD")) private void scan(CallbackInfo c){if(PocketService.inPocket(level))Metrics.scan(unloadQueue);}
}
