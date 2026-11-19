package dev.anya.storage.liveview;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/** Server-thread delivery records. Each UUID's current listener owns its own columns and entity IDs. */
final class PositionDeliveryLedger<C> {
    final Map<UUID, Set<Long>> columns = new HashMap<>();
    final Map<UUID, Set<Integer>> entities = new HashMap<>();
    private final Map<UUID, C> listeners = new HashMap<>();

    void clear() {
        listeners.clear();
        columns.clear();
        entities.clear();
    }

    // The caller checks that this is the current online player's connected listener before binding it.
    void bind(UUID player, C listener) {
        if (listeners.put(player, listener) != listener) clearDelivered(player, listener);
    }

    private boolean owns(UUID player, C listener) {
        return listener != null && listeners.get(player) == listener;
    }

    void clearDelivered(UUID player, C listener) {
        if (owns(player, listener)) {
            columns.remove(player);
            entities.remove(player);
        }
    }

    void disconnected(UUID player, C listener) {
        if (owns(player, listener)) {
            clearDelivered(player, listener);
            listeners.remove(player);
        }
    }

    void forget(UUID player, C listener, long column, LongConsumer send) {
        var sent = columns.get(player);
        if (!owns(player, listener) || sent == null || !sent.remove(column)) return;
        try {
            // Reserve this forget across a nested send. On a failed send the same connection can retry.
            send.accept(column);
        } catch (RuntimeException | Error failed) {
            if (owns(player, listener) && columns.get(player) == sent) sent.add(column);
            throw failed;
        }
    }

    void reconcileColumns(UUID player, C listener, LongPredicate retain, LongConsumer send) {
        var sent = columns.get(player);
        if (!owns(player, listener) || sent == null || sent.isEmpty()) return;
        for (long column : new HashSet<>(sent)) {
            if (!retain.test(column)) forget(player, listener, column, send);
        }
    }
}
