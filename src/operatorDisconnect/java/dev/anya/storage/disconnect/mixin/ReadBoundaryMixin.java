package dev.anya.storage.disconnect.mixin;
import dev.anya.storage.disconnect.InterruptAudit;
import dev.anya.storage.operator.ReadOnlyRegion;
import java.nio.file.Path;
import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=ReadOnlyRegion.class,remap=false)
public abstract class ReadBoundaryMixin {
 @Inject(method="inspect",at=@At("HEAD")) private static void read(Path path,long key,CallbackInfoReturnable<?> ci)throws IOException{InterruptAudit.read(key);}
}
