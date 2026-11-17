package dev.anya.storage.repair.mixin;
import dev.anya.storage.repair.RepairService;import dev.anya.storage.pocket.*;import net.minecraft.server.level.ServerPlayer;import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(RoomEditor.class) public abstract class BuildGuardMixin {
 @Inject(method="permission",at=@At("HEAD"),cancellable=true) private static void protect(ServerPlayer p,PocketLedger.Pocket r,BlockPos pos,BlockState state,boolean removal,CallbackInfoReturnable<String> c){if(RepairService.roomBlocked(r.id()))c.setReturnValue("Persistence quarantine: construction refused without changing items or blocks");}
}
