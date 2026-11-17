package dev.anya.storage.equalwork.mixin;
import dev.anya.storage.equalwork.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=EqualWork.class,remap=false) public abstract class FixtureTimeMixin {
 @Inject(method="tick",at=@At("HEAD")) private static void a(CallbackInfo c){Metrics.fixtureBegin();}
 @Inject(method="tick",at=@At("RETURN")) private static void b(CallbackInfo c){Metrics.fixtureEnd();}
}
