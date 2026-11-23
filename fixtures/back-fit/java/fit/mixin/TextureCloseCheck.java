package fit.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the real client manager after the walking fixture has finished. */
@Mixin(TextureManager.class)
abstract class TextureCloseCheck {
    @Shadow @Final private Map<ResourceLocation, AbstractTexture> byPath;
    @Unique private List<AbstractTexture> backfit$avatarTextures;
    @Unique private DynamicTexture backfit$ordinary;
    @Unique private int backfit$registered;
    @Unique private Path backfit$output;

    @Inject(method = "close", at = @At("HEAD"))
    private void backfit$beginClose(CallbackInfo callback) throws IOException {
        String mode = System.getProperty("anyastorage.fitMode", "");
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))
                || !(mode.equals("absent") || mode.equals("synthetic"))) return;
        Path root = Path.of(System.getProperty("anyastorage.fitRoot")).toRealPath();
        if (!Files.isRegularFile(root.resolve("hosted-check.marker"))
                || !Files.isRegularFile(root.resolve("evidence/PASS.txt"))) return;
        backfit$output = root.resolve("evidence/texture-close.txt");
        backfit$ordinary = new DynamicTexture(1, 1, true);
        ((TextureManager)(Object)this).register(ResourceLocation.fromNamespaceAndPath("backfitcheck", "cleanup"),
                backfit$ordinary);
        backfit$registered = byPath.size();
        backfit$avatarTextures = byPath.values().stream().filter(texture -> texture.getClass().getName()
                .equals("org.figuramc.figura.model.rendering.texture.FiguraTexture")).distinct().toList();
        boolean hasAvatarTextures = !backfit$avatarTextures.isEmpty();
        if (backfit$registered <= 1 || hasAvatarTextures != mode.equals("synthetic"))
            throw new IllegalStateException("Unexpected textures at the start of client shutdown");
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void backfit$finishClose(CallbackInfo callback) throws ReflectiveOperationException, IOException {
        if (backfit$output == null) return;
        int closed = 0;
        for (AbstractTexture texture : backfit$avatarTextures) {
            var field = texture.getClass().getDeclaredField("isClosed");
            field.setAccessible(true);
            if (field.getBoolean(texture)) closed++;
        }
        if (closed != backfit$avatarTextures.size() || !byPath.isEmpty() || backfit$ordinary.getPixels() != null)
            throw new IllegalStateException("Client shutdown left open textures or registrations");
        Files.writeString(backfit$output, "TextureManager.close returned. Entries at start=" + backfit$registered
                + ", at return=" + byPath.size() + ". Figura textures at start=" + backfit$avatarTextures.size()
                + ", closed=" + closed + ". Ordinary texture closed.\n", StandardOpenOption.CREATE_NEW);
        backfit$avatarTextures = null;
        backfit$ordinary = null;
        backfit$output = null;
    }
}
