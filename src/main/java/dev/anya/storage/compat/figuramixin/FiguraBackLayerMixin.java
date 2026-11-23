package dev.anya.storage.compat.figuramixin;

import dev.anya.storage.appearance.FiguraBackFit;
import org.figuramc.figura.model.FiguraModelPart;
import org.figuramc.figura.model.PartCustomization;
import org.figuramc.figura.model.rendering.ImmediateAvatarRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "org.figuramc.figura.model.rendering.ImmediateAvatarRenderer", remap = false)
abstract class FiguraBackLayerMixin {
    @Shadow @Final protected PartCustomization.PartCustomizationStack customizationStack;

    @Inject(method = "renderPart", at = @At(value = "INVOKE", target =
            "Lorg/figuramc/figura/model/FiguraModelPart;pushVerticesImmediate(Lorg/figuramc/figura/model/rendering/ImmediateAvatarRenderer;[I)Z"), require = 0)
    private void anyastorage$onVisiblePart(FiguraModelPart part, int[] remaining, boolean parentVisible,
                                           CallbackInfoReturnable<Boolean> result) {
        FiguraBackFit.observe((ImmediateAvatarRenderer)(Object)this, part, customizationStack.peek());
    }
}
