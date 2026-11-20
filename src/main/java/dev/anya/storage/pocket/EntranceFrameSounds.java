package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Local sound for the surface the entrance renderer drew, independent of its artwork or contents. */
@EventBusSubscriber(modid = AnyaStorage.ID, value = Dist.CLIENT)
public final class EntranceFrameSounds {
    private static final ResourceLocation ON = ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, "frame_on");
    private static final ResourceLocation OFF = ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, "frame_off");
    private static final FrameSoundEdges edges = new FrameSoundEdges();
    private static ClientLevel level;
    private static LocalPlayer viewer;
    private static boolean inMainPass, submitted, completedPass, visible;
    private static SimpleSoundInstance playing;

    @SubscribeEvent
    public static void beginFrame(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level || mc.player != viewer) {
            forget();
            level = mc.level;
            viewer = mc.player;
        }
        inMainPass = submitted = completedPass = visible = false;
    }

    @SubscribeEvent
    public static void renderStage(RenderLevelStageEvent event) {
        var stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            // Count submissions only during the main camera's block-entity pass.
            Minecraft mc = Minecraft.getInstance();
            inMainPass = viewer != null && level != null && mc.level == level && event.getLevelRenderer() == mc.levelRenderer
                    && event.getCamera() == mc.gameRenderer.getMainCamera() && event.getCamera().getEntity() == viewer;
            submitted = false;
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES && inMainPass) {
            if (event.getCamera() == Minecraft.getInstance().gameRenderer.getMainCamera()) {
                completedPass = true;
                visible |= submitted;
            }
            inMainPass = false;
        }
    }

    static void drewFace(PocketContent.EntranceEntity entrance) {
        if (!inMainPass || entrance.getLevel() != level || entrance.isRemoved()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getBlockEntityRenderDispatcher().camera == mc.gameRenderer.getMainCamera()) submitted = true;
    }

    @SubscribeEvent
    public static void endFrame(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!completedPass || level == null || mc.level != level || mc.player != viewer || mc.getCameraEntity() != viewer
                || viewer == null || !viewer.isAlive() || mc.screen != null || mc.getOverlay() != null || !mc.isWindowActive()) {
            silence();
            return;
        }
        var cue = edges.sample(visible, Util.getMillis());
        if (cue == FrameSoundEdges.Cue.NONE) return;
        var sounds = mc.getSoundManager();
        if (playing != null) sounds.stop(playing);
        playing = new SimpleSoundInstance(cue == FrameSoundEdges.Cue.ON ? ON : OFF, SoundSource.BLOCKS,
                0.45F, 1.0F, SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
                0, 0, 0, true);
        sounds.play(playing);
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() == level) forget();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { forget(); }

    private static void silence() {
        edges.reset();
        if (playing != null) {
            Minecraft.getInstance().getSoundManager().stop(playing);
            playing = null;
        }
    }

    private static void forget() {
        silence();
        level = null;
        viewer = null;
        inMainPass = submitted = completedPass = visible = false;
    }

    private EntranceFrameSounds() {}
}
