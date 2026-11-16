package dev.anya.storage.ticketexperiment.mixin;
import dev.anya.storage.ticketexperiment.*;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Select only the new fixture. Does not intercept any production packet or gameplay code. */
@Pseudo
@Mixin(targets="dev.anya.storage.acceptance.ServerProbe",remap=false)
public abstract class ColdRouterMixin {
    @Inject(method="start",at=@At("HEAD"),cancellable=true)
    private static void start(ServerStartedEvent e,CallbackInfo ci){
        if(ColdRecoveryAudit.active()){RemoteTickets.marker("COLD_ROUTER_ACTIVE","Generic fixture disabled for cold recovery scenario\n",false);ci.cancel();}
    }
}
