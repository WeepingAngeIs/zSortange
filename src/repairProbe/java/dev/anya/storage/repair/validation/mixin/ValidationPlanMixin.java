package dev.anya.storage.repair.validation.mixin;
import dev.anya.storage.*;import dev.anya.storage.repair.validation.ValidationAudit;import net.minecraft.world.Container;import net.minecraft.world.item.ItemStack;import net.minecraft.server.MinecraftServer;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;import java.util.*;
@Mixin(value=Transactions.Plan.class,priority=1100) public abstract class ValidationPlanMixin {
 @Shadow @Final private LinkedHashMap<Container,ItemStack[]> before;
 @Inject(method="commit",at=@At("HEAD")) private void observe(MinecraftServer s,CallbackInfoReturnable<String> c){ValidationAudit.plan(before.keySet());}
}
