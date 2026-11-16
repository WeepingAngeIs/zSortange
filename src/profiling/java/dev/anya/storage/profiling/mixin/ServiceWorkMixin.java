package dev.anya.storage.profiling.mixin;
import dev.anya.storage.pocket.PocketService;
import dev.anya.storage.profiling.ProfileProbe;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Timings and an explicitly test-only A/B intervention. Original playable JAR stays identical. */
@Mixin(value=PocketService.class,remap=false)
public abstract class ServiceWorkMixin {
    @Inject(method="tick",at=@At("HEAD"))
    private static void begin(MinecraftServer server,CallbackInfo ci){ProfileProbe.serviceBegin();}
    @Inject(method="tick",at=@At("RETURN"))
    private static void end(MinecraftServer server,CallbackInfo ci){ProfileProbe.serviceEnd();}
    @ModifyArg(method="syncTickets",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerChunkCache;addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V"),index=2)
    private static int addDistance(int original){return ProfileProbe.ticketDistance(original);}
    @ModifyArg(method="syncTickets",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerChunkCache;removeRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V"),index=2)
    private static int removeDistance(int original){return ProfileProbe.ticketDistance(original);}
}
