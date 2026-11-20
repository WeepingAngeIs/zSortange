package dev.anya.storage.durable.mixin;
import dev.anya.storage.pocket.*;
import dev.anya.storage.durable.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.io.IOException;
@Mixin(value=PocketService.class,remap=false)
public abstract class PreparationMixin {
 @Inject(method="prepare",at=@At("HEAD"),cancellable=true)
 private static void head(MinecraftServer s,PocketLedger.Pocket p,CallbackInfoReturnable<Boolean> c)throws IOException{
  Trace.prepareBegin();if(Preparation.enabled()&&Preparation.has(p.id())){boolean done=Preparation.poll(s,p);Trace.prepareEnd();c.setReturnValue(done);}
 }
 @Redirect(method="prepare",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
 private static LevelChunk load(ServerLevel l,int x,int z){
  Preparation.main(l.getServer());if(Preparation.enabled()){Trace.nonblockingMisses++;return l.getChunkSource().getChunkNow(x,z);}return l.getChunk(x,z);
 }
 @Inject(method="prepare",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerChunkCache;save(Z)V"),cancellable=true)
 private static void beforeSave(MinecraftServer s,PocketLedger.Pocket p,CallbackInfoReturnable<Boolean> c)throws IOException{
  if(Preparation.enabled()){Preparation.begin(s,p);Trace.prepareEnd();c.setReturnValue(false);}
 }
 @Inject(method="prepare",at=@At("RETURN")) private static void end(MinecraftServer s,PocketLedger.Pocket p,CallbackInfoReturnable<Boolean> c){Trace.prepareEnd();}
 @Inject(method="tick",at=@At("HEAD")) private static void beginTick(MinecraftServer s,CallbackInfo c){Trace.serviceBegin(s);}
 @Inject(method="tick",at=@At("RETURN")) private static void endTick(MinecraftServer s,CallbackInfo c){if(Preparation.enabled())Preparation.sweep(s);Trace.serviceEnd();}
 @Inject(method="stop",at=@At("HEAD")) private static void stop(MinecraftServer s,CallbackInfo c){Preparation.stop(s);}
 @Inject(method="arrive",at=@At("HEAD")) private static void beforeArrive(@Coerce Object pending,PocketLedger.Pocket p,CallbackInfo c){Fixture.beforeArrival(p.id());}
}
