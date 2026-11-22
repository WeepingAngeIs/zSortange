package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Remembers only player spawns the ordinary game has already paired with this connection. */
@EventBusSubscriber(modid = AnyaStorage.ID)
public final class CameraAppearanceHistory {
    static final int SELF = -1, UNKNOWN = 0;
    private static final long GRACE_MS = 600_000;
    private static final int LIMIT = 128;
    private record Known(UUID profile, long until) {}
    private final Map<Integer, Known> known = new LinkedHashMap<>();
    private static final Map<ServerGamePacketListenerImpl, CameraAppearanceHistory> tracking = new WeakHashMap<>();

    void join(Player player) {
        if (player.getId() <= 0) return;
        known.remove(player.getId());
        if (known.size() >= LIMIT) known.remove(known.keySet().iterator().next());
        known.put(player.getId(), new Known(player.getUUID(), Long.MAX_VALUE));
    }

    void leave(Player player) {
        var entry = known.get(player.getId());
        if (entry != null && entry.profile.equals(player.getUUID()))
            known.put(player.getId(), new Known(entry.profile, Util.getMillis() + GRACE_MS));
    }

    UUID profile(int id) {
        var entry = known.get(id);
        if (entry == null) return null;
        if (Util.getMillis() <= entry.until) return entry.profile;
        known.remove(id);
        return null;
    }

    @SubscribeEvent public static void started(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer target)
            tracking.computeIfAbsent(viewer.connection, ignored -> new CameraAppearanceHistory()).join(target);
    }

    @SubscribeEvent public static void stopped(PlayerEvent.StopTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer target) {
            var history = tracking.get(viewer.connection);
            if (history != null) history.leave(target);
        }
    }

    static int appearance(ServerPlayer observer, ServerPlayer subject) {
        if (subject == observer) return SELF;
        if (!subject.allowsListing()) return UNKNOWN;
        var history = tracking.get(observer.connection);
        return history != null && subject.getUUID().equals(history.profile(subject.getId())) ? subject.getId() : UNKNOWN;
    }
}
