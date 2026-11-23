package dev.anya.storage.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.anya.storage.BackpackHistory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
abstract class BelowWorldBackpackMixin {
    @WrapOperation(method = "onBelowWorld()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;discard()V"), require = 1, allow = 1)
    private void anyastorage$belowWorld(Entity entity, Operation<Void> discard) {
        if (entity instanceof ItemEntity drop) BackpackHistory.onLoss(drop, () -> discard.call(drop));
        else discard.call(entity);
    }
}
