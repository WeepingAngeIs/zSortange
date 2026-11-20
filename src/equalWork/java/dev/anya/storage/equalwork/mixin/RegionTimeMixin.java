package dev.anya.storage.equalwork.mixin;
import dev.anya.storage.equalwork.Metrics;
import dev.anya.storage.repair.RepairService;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(RegionFileStorage.class) public abstract class RegionTimeMixin {
 private boolean relevant(){return RepairService.target(((RegionFileStorage)(Object)this).info());}
 @Inject(method="write",at=@At("HEAD")) private void a(CallbackInfo c){if(relevant())Metrics.ioBegin(1);}
 @Inject(method="write",at=@At("RETURN")) private void b(CallbackInfo c){if(relevant())Metrics.ioEnd(1,0);}
 @Inject(method="flush",at=@At("HEAD")) private void f(CallbackInfo c){if(relevant())Metrics.ioBegin(2);}
 @Inject(method="flush",at=@At("RETURN")) private void g(CallbackInfo c){if(relevant())Metrics.ioEnd(2,0);}
}
