package dev.anya.storage.compat.figuramixin;

import com.llamalad7.mixinextras.injector.ModifyReceiver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TextureManager.class)
abstract class TextureManagerCloseMixin {
    @ModifyReceiver(method = "close", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V", remap = false))
    private Map<ResourceLocation, AbstractTexture> anyastorage$shutdownTextures(
            Map<ResourceLocation, AbstractTexture> registered, BiConsumer<?, ?> closeTexture) {
        // Figura avatar textures unregister themselves during close. Give vanilla a stable
        // worklist in its original order; unregister and the final clear still use the live map.
        return ModList.get().isLoaded("figura") ? new LinkedHashMap<>(registered) : registered;
    }
}
