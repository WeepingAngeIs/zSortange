package dev.anya.storage.allocation.mixin;
import dev.anya.storage.allocation.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=dev.anya.storage.ticketexperiment.RemoteTickets.class,remap=false)
public abstract class AuditMixin {
 @Inject(method="auditEngine",at=@At("HEAD")) private static void a(CallbackInfo c){Probe.begin(11);}
 @Inject(method="auditEngine",at=@At("RETURN")) private static void b(CallbackInfo c){Probe.end(11);}
}
