package dev.anya.storage.allocation.mixin;
import dev.anya.storage.allocation.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(net.minecraft.server.MinecraftServer.class)
public abstract class ServerMixin {
 @Inject(method="tickServer",at=@At("HEAD")) private void before(CallbackInfo c){Probe.tickBegin((net.minecraft.server.MinecraftServer)(Object)this);}
 @Inject(method="tickServer",at=@At("RETURN")) private void after(CallbackInfo c){Probe.tickEnd((net.minecraft.server.MinecraftServer)(Object)this);}
 @Inject(method="stopServer",at=@At("RETURN")) private void close(CallbackInfo c){Probe.close();}
}
