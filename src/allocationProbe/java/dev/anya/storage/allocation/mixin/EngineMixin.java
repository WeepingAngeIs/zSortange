package dev.anya.storage.allocation.mixin;
import dev.anya.storage.allocation.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(net.minecraft.server.level.ServerChunkCache.class)
public abstract class EngineMixin {
 @Inject(method="getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",at=@At("HEAD")) private void a(CallbackInfoReturnable<net.minecraft.world.level.chunk.ChunkAccess> c){Probe.begin(6);}
 @Inject(method="getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",at=@At("RETURN")) private void b(CallbackInfoReturnable<net.minecraft.world.level.chunk.ChunkAccess> c){Probe.end(6);}
 @Inject(method="save",at=@At("HEAD")) private void c(CallbackInfo i){Probe.begin(7);}
 @Inject(method="save",at=@At("RETURN")) private void d(CallbackInfo i){Probe.end(7);}
 @Inject(method="tick",at=@At("HEAD")) private void e(CallbackInfo i){Probe.begin(9);}
 @Inject(method="tick",at=@At("RETURN")) private void f(CallbackInfo i){Probe.end(9);}
}
