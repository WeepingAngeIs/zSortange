package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** One short-lived observation, tied to the picked entrance object and the connection that granted it. */
@EventBusSubscriber(modid = AnyaStorage.ID, value = Dist.CLIENT)
public final class EntranceLayoutClient {
    static final boolean CHEST_LAYOUT_STILL = Boolean.getBoolean("anyastorage.chestLayoutStill");
    private static final int RENEW_TICKS = 12;

    private static ClientPacketListener connection;
    private static PocketContent.EntranceEntity target;
    private static final EntranceFocusState focus = new EntranceFocusState();
    private static long tick, nextRequest;
    private static boolean still;
    private static final StillFrameState frame = new StillFrameState();
    static { PocketPadPackets.stillReceiver = EntranceLayoutClient::receiveStill; }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener current = mc.getConnection();
        boolean useMarker = !CHEST_LAYOUT_STILL && PocketPadPackets.STILL_ENABLED && current != null
                && current.hasChannel(PocketPadPackets.StillFocus.TYPE) && current.hasChannel(PocketPadPackets.StillUpdate.TYPE);
        PocketContent.EntranceEntity picked = pickedEntrance(mc, useMarker);
        if (target != null && (connection != current || target != picked || expired())) clear(true);
        if (picked == null || current == null || tick < nextRequest || !current.getConnection().isConnected()) return;
        if (target == null) {
            still = useMarker;
            if (!still && !current.hasChannel(PocketPadPackets.Focus.TYPE)) return;
            if (CHEST_LAYOUT_STILL && !current.hasChannel(PocketPadPackets.Update.TYPE)) return;
            target = picked;
            connection = current;
            // Keep the sequence across disconnects. Every later acquisition has its own nonce.
            long token = focus.begin(tick, System.nanoTime());
            if (still) frame.begin(token);
        }
        nextRequest = tick + RENEW_TICKS;
        sendFocus(current, target, focus.token(), still, true);
    }

    @SubscribeEvent
    public static void frame(RenderLevelStageEvent event) {
        // The camera pick is current here, even if the old target has left the render frustum.
        if (target != null && event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) validateFocus();
    }

    @SubscribeEvent
    public static void screen(ScreenEvent.Opening event) {
        if (target != null) clear(true);
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide() && target != null && target.getLevel() == event.getLevel()) clear(false);
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear(false);
    }

    public static void receive(Connection source, PocketPadPackets.Update update) {
        if (still || target == null || connection.getConnection() != source
                || !target.getBlockPos().equals(update.pos()) || !validateFocus()) return;
        if (focus.applyMask(update.token(), update.mask(), tick, System.nanoTime()) && focus.token() == 0) clear(false);
    }

    public static void receiveStill(Connection source, PocketPadPackets.StillUpdate update) {
        if (!still || target == null || connection.getConnection() != source || !focus.accepts(update.token())
                || !target.getBlockPos().equals(update.pos()) || !validateFocus()) return;
        if (!frame.apply(update.token(), update.sequence(), update.material())) return;
        if (update.material() == PocketPadPackets.PUBLIC) clear(false);
        else focus.renew(tick, System.nanoTime());
    }

    public static int visibleStill(PocketContent.EntranceEntity entrance) {
        return still && target != null && target == entrance && validateFocus() ? frame.material() : PocketPadPackets.PUBLIC;
    }

    /** The renderer and the hosted fixture both use this exact visibility decision. */
    public static int visibleMask(PocketContent.EntranceEntity entrance) {
        return target != null && target == entrance && validateFocus() ? focus.visibleMask(tick, System.nanoTime()) : -1;
    }

    private static boolean validateFocus() {
        Minecraft mc = Minecraft.getInstance();
        if (connection == mc.getConnection() && target == pickedEntrance(mc, still) && !expired()) return true;
        clear(true);
        return false;
    }

    private static boolean expired() {
        return focus.expired(tick, System.nanoTime());
    }

    private static PocketContent.EntranceEntity pickedEntrance(Minecraft mc, boolean useMarker) {
        if (mc.level == null || mc.player == null || !mc.player.isAlive() || mc.player.isSpectator()
                || mc.screen != null || mc.getCameraEntity() != mc.player
                || !(mc.hitResult instanceof BlockHitResult hit) || !EntranceFaceHit.accepts(hit, useMarker)
                || mc.player.getEyePosition().distanceToSqr(hit.getLocation()) > 64
                || !mc.level.hasChunkAt(hit.getBlockPos())) return null;
        return mc.level.getBlockEntity(hit.getBlockPos()) instanceof PocketContent.EntranceEntity entrance
                && !entrance.isRemoved() ? entrance : null;
    }

    private static void clear(boolean notify) {
        PocketContent.EntranceEntity previous = target;
        ClientPacketListener previousConnection = connection;
        long previousToken = focus.token();
        boolean previousStill = still;
        focus.clear();
        still = false;
        frame.clear();
        target = null;
        connection = null;
        if (previous != null && notify && previousConnection == Minecraft.getInstance().getConnection()
                && previousConnection.getConnection().isConnected()) sendFocus(previousConnection, previous, previousToken, previousStill, false);
    }

    private static void sendFocus(ClientPacketListener listener, PocketContent.EntranceEntity entrance, long nonce,
                                  boolean useStill, boolean watching) {
        if (useStill && listener.hasChannel(PocketPadPackets.StillFocus.TYPE))
            PacketDistributor.sendToServer(new PocketPadPackets.StillFocus(entrance.getBlockPos(), nonce, watching));
        else if (!useStill && listener.hasChannel(PocketPadPackets.Focus.TYPE))
            PacketDistributor.sendToServer(new PocketPadPackets.Focus(entrance.getBlockPos(), nonce, watching));
    }

    private EntranceLayoutClient() {}
}
