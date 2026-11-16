package dev.anya.storage.pressure.mixin;
import dev.anya.storage.pressure.Pressure;
import dev.anya.storage.pocket.*;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.IOException;
@Mixin(PocketService.class)
public abstract class AdmissionMixin {
 /** After the original authorization and stage checks; before menu closure, lease or tickets. */
 @Inject(method="start",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;closeContainer()V"))
 private static void admission(ServerPlayer p,PocketLedger.Pocket r,PocketLeases.Kind kind,CallbackInfo ci)throws IOException{Pressure.check(p,r);}
}
