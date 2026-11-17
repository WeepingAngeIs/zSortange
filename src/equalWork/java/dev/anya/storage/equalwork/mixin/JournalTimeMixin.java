package dev.anya.storage.equalwork.mixin;
import dev.anya.storage.equalwork.Metrics;
import dev.anya.storage.repair.SnapshotJournal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.file.Path;
@Mixin(value=SnapshotJournal.class,remap=false) public abstract class JournalTimeMixin {
 @Inject(method="atomic",at=@At("HEAD")) private void a(Path p,byte[] b,String phase,CallbackInfo c){Metrics.ioBegin(0);}
 @Inject(method="atomic",at=@At("RETURN")) private void b(Path p,byte[] b,String phase,CallbackInfo c){Metrics.ioEnd(0,b.length);}
}
