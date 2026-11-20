package dev.anya.storage.healthy.mixin;
import dev.anya.storage.healthy.HealthyAudit;
import dev.anya.storage.repair.RepairService;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=RepairService.class,remap=false) public abstract class HealthyBoundaryMixin {
 @Inject(method="boundary",at=@At("HEAD")) private static void boundary(String p,ChunkPos c,CallbackInfo info){HealthyAudit.boundary(p,c);}
}
