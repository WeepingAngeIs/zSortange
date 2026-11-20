package dev.anya.storage.repair.mixin;
import dev.anya.storage.repair.RepairService;import dev.anya.storage.pocket.*;import net.minecraft.server.level.ServerPlayer;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;import java.io.IOException;
@Mixin(PocketService.class) public abstract class RepairAdmissionMixin {
 @Inject(method="start",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;closeContainer()V")) private static void admission(ServerPlayer p,PocketLedger.Pocket r,PocketLeases.Kind k,CallbackInfo c)throws IOException{if(RepairService.roomBlocked(r.id()))throw new IOException("Pocket persistence quarantined; existing return recovery remains available");}
}
