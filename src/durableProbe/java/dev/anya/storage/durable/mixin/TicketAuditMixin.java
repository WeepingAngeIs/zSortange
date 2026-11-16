package dev.anya.storage.durable.mixin;
import dev.anya.storage.durable.Trace;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ServerChunkCache.class)
public abstract class TicketAuditMixin {
 @Inject(method="addRegionTicket",at=@At("HEAD")) private <T> void add(TicketType<T> t,ChunkPos p,int r,T k,CallbackInfo c){Trace.ticket((ServerChunkCache)(Object)this,t,p,r,k,true);}
 @Inject(method="removeRegionTicket",at=@At("HEAD")) private <T> void remove(TicketType<T> t,ChunkPos p,int r,T k,CallbackInfo c){Trace.ticket((ServerChunkCache)(Object)this,t,p,r,k,false);}
}
