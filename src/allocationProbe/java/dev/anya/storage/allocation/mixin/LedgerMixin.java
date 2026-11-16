package dev.anya.storage.allocation.mixin;
import dev.anya.storage.allocation.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=dev.anya.storage.pocket.PocketLedger.class,remap=false)
public abstract class LedgerMixin {
 @Inject(method="commit",at=@At("HEAD")) private void a(CallbackInfo c){Probe.begin(8);}
 @Inject(method="commit",at=@At("RETURN")) private void b(CallbackInfo c){Probe.end(8);}
}
