package dev.anya.storage.healthy.mixin;
import dev.anya.storage.healthy.HealthyAudit;
import dev.anya.storage.repair.SnapshotJournal;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=SnapshotJournal.class,remap=false) public abstract class HealthyResetMixin {
 @Inject(method="reset",at=@At("HEAD")) private void before(long key,UUID t,CallbackInfoReturnable<SnapshotJournal.Record> c){HealthyAudit.reset((SnapshotJournal)(Object)this,key,t,false);}
 @Inject(method="reset",at=@At("RETURN")) private void after(long key,UUID t,CallbackInfoReturnable<SnapshotJournal.Record> c){HealthyAudit.reset((SnapshotJournal)(Object)this,key,t,true);}
}
