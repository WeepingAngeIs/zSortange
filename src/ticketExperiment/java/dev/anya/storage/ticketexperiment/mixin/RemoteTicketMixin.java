package dev.anya.storage.ticketexperiment.mixin;
import dev.anya.storage.pocket.*;
import java.util.*;
import dev.anya.storage.ticketexperiment.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=PocketService.class,remap=false)
public abstract class RemoteTicketMixin {
    @Shadow @Final private static PocketLeases LEASES;
    @Shadow private static PocketLedger ledger;
    @Shadow @Final private static Map<UUID,?> PREPARING;
    @Shadow @Final private static Map<UUID,?> PENDING;
    @Shadow @Final private static Map<?,Long> RECOVERY_TICKETS;
    @Shadow @Final private static Set<?> TICKETS;
    @Shadow @Final private static TicketType<UUID> TICKET;
    @Shadow private static long clock;
    @Inject(method="syncTickets",at=@At("HEAD")) private static void syncStart(MinecraftServer s,CallbackInfo c){RemoteTickets.beforeSync(s,LEASES,ledger,PREPARING.keySet(),PENDING.keySet(),RECOVERY_TICKETS,clock,TICKET);}
    @Inject(method="syncTickets",at=@At("RETURN")) private static void syncEnd(MinecraftServer s,CallbackInfo c){RemoteTickets.afterSync(TICKETS);}
    @Redirect(method="syncTickets",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerChunkCache;addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V"))
    private static <T> void add(ServerChunkCache c,TicketType<T> t,ChunkPos p,int r,T key){RemoteTickets.add(c,t,p,r,key);}
    @Redirect(method={"syncTickets","stop"},require=2,at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerChunkCache;removeRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V"))
    private static <T> void remove(ServerChunkCache c,TicketType<T> t,ChunkPos p,int r,T key){RemoteTickets.remove(c,t,p,r,key);}
    @Inject(method="stop",at=@At("HEAD")) private static void stopStart(MinecraftServer s,CallbackInfo c){RemoteTickets.beforeStop(s);}
    @Inject(method="stop",at=@At("RETURN")) private static void stopEnd(MinecraftServer s,CallbackInfo c){RemoteTickets.afterStop();}
    @Inject(method="performRecovery",at=@At("HEAD")) private static void recoveryAttempt(ServerPlayer p,CallbackInfo c){ChurnProbe.attempt(p);}
    @Inject(method="performRecovery",at=@At(value="INVOKE",target="Ldev/anya/storage/pocket/PocketService;savePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private static void cut(ServerPlayer p,CallbackInfo c){RemoteTickets.beforeRecoverySave(p);}
    @Inject(method="tick",at=@At("HEAD")) private static void tickStart(MinecraftServer s,CallbackInfo c){RemoteMetrics.serviceStart();}
    @Inject(method="tick",at=@At("RETURN")) private static void tickEnd(MinecraftServer s,CallbackInfo c){RemoteMetrics.serviceEnd();}
}
