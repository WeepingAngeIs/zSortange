package dev.anya.storage.acceptance;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;

/** Observe the spectator's client-level sound dispatch, before the audio device or mixer. */
@EventBusSubscriber(modid = Acceptance.ID, value = Dist.CLIENT)
public final class ArtSoundObserver {
    private static final String OPEN = "anyastorage:chest_open";
    private static final String CLOSE = "anyastorage:chest_close";
    private static final List<String> heard = new ArrayList<>();
    private static Vec3 chest;

    static void arm(BlockPos pos) {
        Acceptance.require("Alpha".equals(System.getProperty("anya.role")), "Expected the spectator client");
        heard.clear();
        chest = Vec3.atCenterOf(pos);
    }

    @SubscribeEvent
    public static void hear(PlayLevelSoundEvent.AtPosition event) {
        if (chest == null || !event.getLevel().isClientSide() || !chest.equals(event.getPosition())) return;
        var sound = event.getSound();
        if (sound == null) return;
        String id = sound.value().getLocation().toString();
        if (OPEN.equals(id) || CLOSE.equals(id)) {
            heard.add(event.getSource() == SoundSource.BLOCKS ? id : id + " on " + event.getSource());
        }
    }

    static void checkCapture(String action) {
        switch (action) {
            case "art-closed" -> expect(List.of(), action);
            case "art-open" -> expect(List.of(OPEN), action);
            case "art-veil" -> {
                // Beta has closed the chest, the server saw it fully shut, and Alpha has acknowledged the next pose.
                expect(List.of(OPEN, CLOSE), action);
                Acceptance.marker("art-alpha-chimes.txt", "AnyaQAAlpha heard " + heard + " at " + chest + " on BLOCKS.");
                chest = null;
            }
        }
    }

    private static void expect(List<String> expected, String action) {
        Acceptance.require(chest != null && heard.equals(expected),
                "Spectator chest sounds at " + action + ": expected " + expected + ", received " + heard);
    }
}
