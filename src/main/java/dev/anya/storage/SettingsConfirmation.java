package dev.anya.storage;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;

/** One short-lived confirmation per player and settings surface, consumed at most once. */
final class SettingsConfirmation {
    private record Pending(long revision, String key, String value, long until) {}
    private final Map<ServerPlayer, Pending> pending = new WeakHashMap<>();

    void clear(ServerPlayer player) {
        pending.remove(player);
    }

    boolean accept(ServerPlayer player, long revision, String key, String value, boolean confirmed) {
        var previous = pending.remove(player);
        long now = player.getServer().overworld().getGameTime();
        if (confirmed && previous != null && previous.until >= now && previous.revision == revision
                && previous.key.equals(key) && previous.value.equals(value)) return true;
        pending.put(player, new Pending(revision, key, value, now + 600));
        return false;
    }
}
