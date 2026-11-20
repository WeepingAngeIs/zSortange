package dev.anya.storage.repair.validation.mixin;
import dev.anya.storage.repair.validation.ValidationAudit;import dev.anya.storage.pocket.PocketService;import net.minecraft.server.level.ServerPlayer;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(PocketService.class) public abstract class ValidationRecoveryMixin {
 @Inject(method="performRecovery",at=@At("HEAD")) private static void observe(ServerPlayer p,CallbackInfo c){ValidationAudit.recovery(p);}
}
