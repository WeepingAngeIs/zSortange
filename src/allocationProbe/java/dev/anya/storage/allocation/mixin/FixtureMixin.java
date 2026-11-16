package dev.anya.storage.allocation.mixin;
import dev.anya.storage.allocation.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=dev.anya.storage.ticketexperiment.ChurnProbe.class,remap=false)
public abstract class FixtureMixin {
 @Inject(method="post",at=@At("HEAD"),cancellable=true) private static void a(CallbackInfo c){if(Probe.holdFixture())c.cancel();else Probe.begin(10);}
 @Inject(method="post",at=@At("RETURN")) private static void b(CallbackInfo c){if(!Probe.holdFixture())Probe.end(10);}
}
