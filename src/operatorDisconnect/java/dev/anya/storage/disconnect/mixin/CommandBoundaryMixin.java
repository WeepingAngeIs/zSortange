package dev.anya.storage.disconnect.mixin;
import dev.anya.storage.disconnect.InterruptAudit;
import dev.anya.storage.operator.OperatorResolution;
import net.minecraft.commands.CommandSourceStack;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=OperatorResolution.class,remap=false)
public abstract class CommandBoundaryMixin {
 @Inject(method="inspectInternal",at=@At("HEAD")) private static void consumed(CommandSourceStack s,long key,boolean issue,CallbackInfoReturnable<?> ci){InterruptAudit.receiptConsumed(key,issue);}
 @Inject(method="resolve",at=@At("RETURN")) private static void result(CommandSourceStack s,UUID world,long key,UUID token,String receipt,CallbackInfoReturnable<CompletableFuture<Void>> ci){InterruptAudit.result(key,ci.getReturnValue());}
}
