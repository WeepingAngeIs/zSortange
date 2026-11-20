package dev.anya.storage.disconnect.mixin;
import dev.anya.storage.disconnect.InterruptAudit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(CommandSourceStack.class)
public abstract class AcknowledgementMixin {
 @Inject(method="sendSuccess",at=@At("HEAD"),cancellable=true) private void response(Supplier<Component> message,boolean broadcast,CallbackInfo ci){if(InterruptAudit.acknowledgement((CommandSourceStack)(Object)this,message))ci.cancel();}
}
