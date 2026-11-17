package dev.anya.storage.equalwork.mixin;
import dev.anya.storage.equalwork.Metrics;
import dev.anya.storage.budget.BudgetState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=BudgetState.class,remap=false) public abstract class BudgetObserveMixin {
 @Inject(method="run",at=@At("HEAD")) private void run(Runnable r,CallbackInfo c){Metrics.run(r);}
}
