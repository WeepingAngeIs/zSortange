package dev.anya.storage.ticketexperiment.mixin;
import dev.anya.storage.pocket.PocketService;
import dev.anya.storage.ticketexperiment.ColdRecoveryAudit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=PocketService.class,remap=false)
public abstract class ColdRecoveryMixin {
    @Inject(method="loadForRecovery",at=@At("HEAD")) private static void before(ServerLevel l,BlockPos p,UUID id,CallbackInfo ci){ColdRecoveryAudit.before(l,p,id);}
    @Inject(method="loadForRecovery",at=@At("RETURN")) private static void after(ServerLevel l,BlockPos p,UUID id,CallbackInfo ci){ColdRecoveryAudit.after(l,p,id);}
}
