package dev.anya.storage.equalwork.mixin;
import dev.anya.storage.equalwork.Metrics;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MinecraftServer.class) public abstract class FullTickMixin {
 @Inject(method="tickServer",at=@At("HEAD")) private void a(CallbackInfo c){Metrics.pre((MinecraftServer)(Object)this);}
 @Inject(method="tickServer",at=@At("RETURN")) private void b(CallbackInfo c){Metrics.post((MinecraftServer)(Object)this);}
 @Inject(method="stopServer",at=@At("HEAD")) private void s(CallbackInfo c){Metrics.stopBegin();}
 @Inject(method="stopServer",at=@At("RETURN")) private void e(CallbackInfo c){Metrics.stopEnd();}
}
