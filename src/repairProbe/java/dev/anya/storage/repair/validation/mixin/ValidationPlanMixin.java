package dev.anya.storage.repair.validation.mixin;
import dev.anya.storage.*;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.repair.validation.ValidationAudit;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
/** Observe the actual returned outcome, including an earlier cancellable quarantine HEAD injection. */
@Mixin(StoreMenu.class)
public abstract class ValidationPlanMixin {
 @Redirect(method={"take","inventoryClick"},at=@At(value="INVOKE",target="Ldev/anya/storage/Transactions$Plan;commit(Lnet/minecraft/server/MinecraftServer;)Ljava/lang/String;"))
 private String result(Transactions.Plan plan,MinecraftServer server){
  String outcome=plan.commit(server); // Exactly one original call, no mutation retries or replacement result.
  if(ValidationAudit.ACTIVE){
   boolean quarantined=outcome.equals("Persistence quarantine changed; no item mutation. Verify repair/reset before retrying.");
   if(quarantined)ValidationAudit.blockedPlans++;
   Trace.note("NETWORK_PLAN_RESULT",null,"quarantine="+quarantined+" blocked="+ValidationAudit.blockedPlans+" outcome="+outcome);
  }
  return outcome;
 }
}
