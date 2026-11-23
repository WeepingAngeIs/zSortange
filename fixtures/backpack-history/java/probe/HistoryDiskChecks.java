package probe;

import dev.anya.storage.BagInventory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import net.minecraft.core.HolderLookup;
import dev.anya.storage.BackpackHistory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** The store and crash-boundary seams are package-private in the playable and exposed only here by reflection. */
final class HistoryDiskChecks {
    private static final String STORE = "dev.anya.storage.BagHistoryStore";

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        var m = type.getDeclaredMethod(name, parameters);
        m.setAccessible(true);
        return m;
    }

    static void expiry(ServerPlayer player, ItemStack stack) throws Exception {
        Class<?> ledger = Class.forName(STORE);
        Class<?> entry = Class.forName(ledger.getName() + "$Entry");
        Class<?> state = Class.forName(ledger.getName() + "$State");
        var constructor = ledger.getDeclaredConstructor(Path.class, LongSupplier.class);
        constructor.setAccessible(true);
        var put = method(ledger, "put", entry, entry);
        var get = method(ledger, "get", UUID.class);
        var restore = method(entry, "restoreStack", HolderLookup.Provider.class);
        var registries = player.registryAccess();
        UUID id = BagInventory.data(stack).getUUID("BagId");
        long start = 1_750_000_000_000L;
        var time = new AtomicLong(start);
        LongSupplier clock = time::get;
        var root = Files.createDirectory(Path.of("expiry-fixture")).toAbsolutePath();
        Object store = constructor.newInstance(root, clock);
        Object observed = method(entry, "observed", UUID.class, UUID.class, String.class, String.class, long.class)
                .invoke(null, id, player.getUUID(), "HistoryOwner", "Clock sample", start);
        put.invoke(store, null, observed);
        Object captured = method(entry, "capture", ItemStack.class, HolderLookup.Provider.class, long.class, UUID.class)
                .invoke(observed, stack, registries, start, UUID.randomUUID());
        put.invoke(store, observed, captured);
        Object available = method(entry, "withState", state).invoke(captured, method(state, "valueOf", String.class).invoke(null, "AVAILABLE"));
        put.invoke(store, captured, available);
        var record = root.resolve("anya_storage/backpack_history/" + id + ".dat");
        if (!Files.isRegularFile(record)) throw new IllegalStateException("Expiry fixture wrote no journal");
        time.set(start + Duration.ofDays(30).toMillis() - 1);
        Object before = get.invoke(constructor.newInstance(root, clock), id);
        if (before == null || !ItemStack.matches(stack, (ItemStack)restore.invoke(before, registries)))
            throw new IllegalStateException("Exact stack unavailable an instant before expiry");
        time.incrementAndGet();
        if (get.invoke(constructor.newInstance(root, clock), id) != null || Files.exists(record))
            throw new IllegalStateException("Expired backpack still recoverable");
    }

    static void interrupt(ServerPlayer owner, BackpackHistory.Row row, ChestBlockEntity movedTo) throws Exception {
        Class<?> ledger = Class.forName(STORE);
        Class<?> entry = Class.forName(STORE + "$Entry");
        Class<?> state = Class.forName(STORE + "$State");
        Object store = method(ledger, "open", MinecraftServer.class).invoke(null, owner.getServer());
        Object existing = method(ledger, "get", UUID.class).invoke(store, row.id());
        Method transition = method(entry, "withState", state);
        Method valueOf = method(state, "valueOf", String.class);
        Method put = method(ledger, "put", entry, entry);
        Object pending = transition.invoke(existing, valueOf.invoke(null, "PENDING"));
        put.invoke(store, existing, pending);
        var receipt = method(Class.forName("dev.anya.storage.BagDeliveryReceipt"), "save",
                ServerPlayer.class, UUID.class, boolean.class, int.class, ItemStack.class);
        receipt.invoke(null, owner, row.lossId(), false, -1, null);
        Object delivering = transition.invoke(pending, valueOf.invoke(null, "DELIVERING"));
        put.invoke(store, pending, delivering);
        if (movedTo != null) {
            var inv = owner.getInventory();
            int slot = inv.getFreeSlot();
            if (slot < 0 || !movedTo.getItem(2).isEmpty()) throw new IllegalStateException("No room for interruption fixture");
            var bag = (ItemStack)method(entry, "restoreStack", HolderLookup.Provider.class).invoke(delivering, owner.registryAccess());
            method(Class.forName("dev.anya.storage.BagDeliveryReceipt"), "insertPrepared",
                    ServerPlayer.class, UUID.class, int.class, ItemStack.class).invoke(null, owner, row.lossId(), slot, bag);
            receipt.invoke(null, owner, row.lossId(), true, slot, bag);
            movedTo.setItem(2, inv.removeItemNoUpdate(slot));
            movedTo.setChanged();
            inv.setItem(slot, new ItemStack(Items.QUARTZ, 64));
            if (inv.getFreeSlot() >= 0) throw new IllegalStateException("Committed-receipt fixture needs a full recipient");
        }
        // The caller saves the inventory and chest and stops this same tick, before normal retries run.
    }

    static void returned(MinecraftServer server, UUID bag, UUID cycle) throws Exception {
        Class<?> ledger = Class.forName(STORE);
        Class<?> entry = Class.forName(STORE + "$Entry");
        Object store = method(ledger, "open", MinecraftServer.class).invoke(null, server);
        Object value = method(ledger, "get", UUID.class).invoke(store, bag);
        if (value == null || !method(entry, "state").invoke(value).toString().equals("RETURNED")
                || !cycle.equals(method(entry, "transaction").invoke(value)))
            throw new IllegalStateException("Interrupted recovery did not reach RETURNED for its original loss");
    }
}
