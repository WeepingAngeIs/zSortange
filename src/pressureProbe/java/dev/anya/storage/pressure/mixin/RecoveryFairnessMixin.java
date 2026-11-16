package dev.anya.storage.pressure.mixin;
import dev.anya.storage.pressure.PressureFixture;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(PocketService.class)
public abstract class RecoveryFairnessMixin {
 @Inject(method="performRecovery",at=@At("HEAD")) private static void attempt(ServerPlayer p,CallbackInfo ci){PressureFixture.recoveryAttempt(p);}
}
