package dev.anya.storage.allocation.mixin;
import dev.anya.storage.allocation.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=dev.anya.storage.pocket.PocketService.class,remap=false)
public abstract class PocketMixin {
 @Inject(method="tick",at=@At("HEAD")) private static void a0(CallbackInfo c){Probe.begin(0);}
 @Inject(method="tick",at=@At("RETURN")) private static void b0(CallbackInfo c){Probe.end(0);}
 @Inject(method="prepare",at=@At("HEAD")) private static void a1(CallbackInfoReturnable<Boolean> c){Probe.begin(1);}
 @Inject(method="prepare",at=@At("RETURN")) private static void b1(CallbackInfoReturnable<Boolean> c){Probe.end(1);}
 @Inject(method="syncTickets",at=@At("HEAD")) private static void a2(CallbackInfo c){Probe.begin(2);}
 @Inject(method="syncTickets",at=@At("RETURN")) private static void b2(CallbackInfo c){Probe.end(2);}
 @Inject(method="performRecovery",at=@At("HEAD")) private static void a3(CallbackInfo c){Probe.begin(3);}
 @Inject(method="performRecovery",at=@At("RETURN")) private static void b3(CallbackInfo c){Probe.end(3);}
 @Inject(method="savePlayer",at=@At("HEAD")) private static void a4(CallbackInfo c){Probe.begin(4);}
 @Inject(method="savePlayer",at=@At("RETURN")) private static void b4(CallbackInfo c){Probe.end(4);}
 @Inject(method="arrive",at=@At("HEAD")) private static void a5(CallbackInfo c){Probe.begin(5);}
 @Inject(method="arrive",at=@At("RETURN")) private static void b5(CallbackInfo c){Probe.end(5);}
}
