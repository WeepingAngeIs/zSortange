package dev.anya.storage.minimal.mixin;
import dev.anya.storage.minimal.LeanMetrics;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ServerLevel.class)
public abstract class DrainLevelMixin {
 @Inject(method="unload",at=@At("HEAD")) private void a(CallbackInfo c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket((ServerLevel)(Object)this))LeanMetrics.begin(6);}
 @Inject(method="unload",at=@At("RETURN")) private void b(CallbackInfo c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket((ServerLevel)(Object)this))LeanMetrics.end(6);}
}
