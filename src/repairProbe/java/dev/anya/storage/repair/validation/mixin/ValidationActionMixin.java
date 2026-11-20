package dev.anya.storage.repair.validation.mixin;
import dev.anya.storage.*;import dev.anya.storage.repair.validation.ValidationAudit;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(StoreMenu.class) public abstract class ValidationActionMixin {
 @Inject(method="action",at=@At("HEAD")) private void observe(Packets.Action p,CallbackInfo c){ValidationAudit.action((StoreMenu)(Object)this,p);}
}
