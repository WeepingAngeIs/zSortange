package dev.anya.storage.repair.mixin;
import dev.anya.storage.repair.RepairFixture;import dev.anya.storage.pocket.PocketService;import net.minecraft.server.level.ServerPlayer;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(PocketService.class) public abstract class FixtureRecoveryMixin {
 @Inject(method="performRecovery",at=@At("HEAD")) private static void observe(ServerPlayer p,CallbackInfo c){RepairFixture.recovery(p);}
}
