package dev.anya.storage.resetgap.mixin;
import dev.anya.storage.resetgap.GapHooks;
import dev.anya.storage.repair.SnapshotJournal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.io.IOException;
import java.util.UUID;
@Mixin(value=SnapshotJournal.class,remap=false)
public abstract class GapJournalMixin {
 @Inject(method="reset",at=@At("RETURN")) private void reset(long key,UUID token,CallbackInfoReturnable<SnapshotJournal.Record> ci){GapHooks.afterReset(key,token);}
 @Inject(method="lock",at=@At("HEAD")) private void lock(long key,UUID token,CallbackInfoReturnable<SnapshotJournal.Record> ci)throws IOException{GapHooks.beforeLock(key,token);}
 @Inject(method="failClosed",at=@At("HEAD")) private void marker(String reason,CallbackInfo ci)throws IOException{GapHooks.beforeMarker();}
}
