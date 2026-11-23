package dev.anya.storage.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.anya.storage.BackpackHistory;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(ItemEntity.class)
abstract class BackpackLossMixin {
    @WrapOperation(method = "tick()V",
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/event/EventHooks;onItemExpire(Lnet/minecraft/world/entity/item/ItemEntity;)I",
                    remap = false)),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V", ordinal = 0),
            require = 1, allow = 1)
    private void anyastorage$finalExpiry(ItemEntity drop, Operation<Void> discard) {
        BackpackHistory.onLoss(drop, () -> discard.call(drop));
    }

    @WrapOperation(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V"),
            require = 1, allow = 1)
    private void anyastorage$fatalDamage(ItemEntity drop, Operation<Void> discard) {
        BackpackHistory.onLoss(drop, () -> discard.call(drop));
    }
}
