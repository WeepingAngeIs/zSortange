package dev.anya.storage.durable.mixin;
import dev.anya.storage.durable.Trace;
import net.minecraft.server.level.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ChunkMap.class)
public abstract class UnloadAuditMixin {
 @Shadow @Final private ServerLevel level;
 @Inject(method="lambda$scheduleUnload$12",at=@At("HEAD"))
 private void callback(ChunkHolder holder,long key,CallbackInfo c){Trace.unload(level,holder,key);}
}
