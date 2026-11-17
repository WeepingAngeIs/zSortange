package dev.anya.storage.repair.mixin;
import dev.anya.storage.Transactions;import dev.anya.storage.repair.RepairService;import net.minecraft.world.Container;import net.minecraft.world.item.ItemStack;import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;import java.util.*;
@Mixin(Transactions.Plan.class) public abstract class WriterGuardMixin {
 @Shadow @Final private LinkedHashMap<Container,ItemStack[]> before;
 @Unique private final Map<Container,Long> repairEpochs=new IdentityHashMap<>();
 @Inject(method="<init>",at=@At("RETURN")) private void capture(CallbackInfo c){if(RepairService.ENABLED)for(Container x:before.keySet())repairEpochs.put(x,RepairService.epoch(x));}
 @Inject(method="commit",at=@At("HEAD"),cancellable=true) private void guarded(MinecraftServer s,CallbackInfoReturnable<String> c){
  if(!RepairService.ENABLED)return;for(Container x:before.keySet())if(RepairService.containerBlocked(x)||repairEpochs.getOrDefault(x,0L)!=RepairService.epoch(x)){c.setReturnValue("Persistence quarantine changed; no item mutation. Verify repair/reset before retrying.");return;}
 }
}
