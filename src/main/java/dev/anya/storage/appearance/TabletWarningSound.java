package dev.anya.storage.appearance;

import dev.anya.storage.AnyaStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

/** A quiet note from the player's own tablet, centered in the listener's headphones. */
final class TabletWarningSound {
    private static final ResourceLocation CUE = ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, "tablet_warning");

    static void play() {
        var note = new SimpleSoundInstance(CUE, SoundSource.PLAYERS, .10f, 1f,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
        Minecraft.getInstance().getSoundManager().play(note);
    }

    private TabletWarningSound() {}
}
