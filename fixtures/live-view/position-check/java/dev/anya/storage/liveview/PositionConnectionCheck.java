package dev.anya.storage.liveview;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.level.ChunkPos;

/** Uses the real adapter bookkeeping. Listener objects and delivery callbacks are simulated on the server thread. */
final class PositionConnectionCheck {
    static JsonObject check() {
        var delivered = new PositionDeliveryLedger<Object>();
        UUID guest = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Object old = new Object(), replacement = new Object(), independent = new Object();
        long revoked = ChunkPos.asLong(4, 6), retained = ChunkPos.asLong(5, 6);
        var guestForgets = new ArrayList<Long>();
        delivered.bind(guest, old);
        delivered.bind(other, independent);
        delivered.columns.put(guest, new HashSet<>(Set.of(revoked, retained)));
        delivered.columns.put(other, new HashSet<>(Set.of(revoked)));
        delivered.entities.put(guest, new HashSet<>(Set.of(51)));
        delivered.entities.put(other, new HashSet<>(Set.of(72)));

        // CE can already have discarded its watch. The reconciliation input is the actual
        // delivery ledger; the absence of a watch must neither suppress nor duplicate this forget.
        delivered.reconcileColumns(guest, old, key -> key == retained, key -> {
            guestForgets.add(key);
            delivered.forget(guest, old, key, guestForgets::add);
        });
        delivered.reconcileColumns(guest, old, key -> key == retained, guestForgets::add);
        expect(guestForgets.equals(List.of(revoked)), "Expected exactly one scoped column forget");
        expect(delivered.columns.get(guest).equals(Set.of(retained)), "Guest lost a retained column");
        expect(delivered.columns.get(other).equals(Set.of(revoked)), "Another listener lost its column");

        delivered.bind(guest, replacement);
        expect(!delivered.columns.containsKey(guest) && !delivered.entities.containsKey(guest),
                "Replacement listener inherited old deliveries");
        delivered.columns.put(guest, new HashSet<>(Set.of(revoked)));
        delivered.entities.put(guest, new HashSet<>(Set.of(81)));
        delivered.disconnected(guest, old);
        delivered.reconcileColumns(guest, old, key -> false, guestForgets::add);
        expect(guestForgets.size() == 1 && delivered.columns.get(guest).equals(Set.of(revoked)), "Late old-listener callback changed the new session");
        expect(delivered.entities.get(guest).equals(Set.of(81)), "Late logout removed replacement entity history");

        var ids = delivered.entities.get(guest);
        expect(PositionTerrainPolicy.removeDelivered(new ClientboundRemoveEntitiesPacket(51, 72), ids) == null,
                "Replacement listener received an entity removal for the old or independent listener");
        var remove = PositionTerrainPolicy.removeDelivered(new ClientboundRemoveEntitiesPacket(51, 81), ids);
        expect(remove != null && remove.getEntityIds().size() == 1 && remove.getEntityIds().getInt(0) == 81,
                "The replacement should receive only its own previously delivered entity ID");
        expect(ids.isEmpty() && delivered.entities.get(other).equals(Set.of(72)), "Entity removal changed the wrong listener");
        delivered.disconnected(guest, replacement);
        expect(!delivered.columns.containsKey(guest) && !delivered.entities.containsKey(guest), "Disconnected listener retained deliveries");
        expect(delivered.columns.get(other).equals(Set.of(revoked)), "Independent listener changed on guest disconnect");

        var result = new JsonObject();
        result.addProperty("scope", "Adapter ledger and removal policy; simulated listener identities and cleanup callbacks, no CE watch or socket");
        result.addProperty("revoked_column_forgets", guestForgets.size());
        result.addProperty("retained_column_preserved", true);
        result.addProperty("independent_recipient_preserved", true);
        result.addProperty("new_socket_inherited_old_deliveries", false);
        result.addProperty("late_logout_changed_replacement", false);
        result.addProperty("unseen_entity_removal_forwarded", false);
        return result;
    }

    private static void expect(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private PositionConnectionCheck() {}
}
