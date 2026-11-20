package dev.anya.storage.allocation.mixin;
import dev.anya.storage.allocation.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=dev.anya.storage.ticketexperiment.RemoteMetrics.class,remap=false)
public abstract class ObserverMixin {
 @Inject(method={"start","pre","post","stopped"},at=@At("HEAD"),cancellable=true)
 private static void skip(CallbackInfo c){if(Probe.suppressOldObserver())c.cancel();}
}
