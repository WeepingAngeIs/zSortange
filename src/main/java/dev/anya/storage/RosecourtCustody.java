package dev.anya.storage;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/** Read-only view of Rosecourt's V1 on-disk format. Each inventory must resolve from its verified index. */
final class RosecourtCustody {
    static final Path INTEGRATED = Path.of("integrated-jail-v1"), INTERFACE = Path.of("custody-interface-v1");
    private static final Path PLANS = INTEGRATED.resolve("escrow.v1"), INDEX = INTERFACE.resolve("custody.v1");
    private static final Path TERRAIN = Path.of("data", "unyielding-jail-placement.before");
    private static final Path MARKERS = INTEGRATED.resolve("backpack-locks");
    private static final int MAX_FILE = 16 << 20;
    private static final Set<String> CONTRACTS = Set.of("human-empty-v1", "native-equipment-luck-v1",
            "native-packed-wallet-v1", "native-supported-v2", "native-retained-xp-v3");
    private final Path world;
    private final MinecraftServer server;
    private final UUID bag;
    private final long deadline;
    private final Map<Path,BasicFileAttributes> files = new HashMap<>();
    private final Set<Path> verified = new HashSet<>();
    private long bytes;
    boolean integrated, indexed, declared;

    RosecourtCustody(MinecraftServer server, Path world, UUID bag, long deadline) {
        this.server = server;
        this.world = world;
        this.bag = bag;
        this.deadline = deadline;
    }

    static boolean ownerHeld(MinecraftServer server, UUID owner) throws IOException {
        if (!ModList.get().isLoaded("minecraftjail")) return false;
        try {
            // Use the jail's own custody decision before depositing into a player's inventory.
            var service = Class.forName("dev.unyielding.jail.JailCustodyService");
            return (boolean)service.getMethod("blocked", MinecraftServer.class, UUID.class).invoke(null, server, owner);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            throw new IOException("Rosecourt could not confirm the owner's custody status.", unavailable);
        }
    }

    boolean collect(Path rel, BasicFileAttributes attrs) throws IOException {
        if (!(rel.equals(INDEX) || rel.equals(TERRAIN) || rel.getFileName().toString().equals("escrow.v1")
                || rel.startsWith(MARKERS))) return false;
        if (attrs.size() < 1 || attrs.size() > MAX_FILE || (bytes += attrs.size()) > (64L << 20) || files.size() >= 8192)
            throw new IOException("Rosecourt records exceed the recovery scan limits.");
        files.put(rel, attrs);
        return true;
    }

    void verify(BagHistoryPresence scan) throws IOException {
        if (files.isEmpty() && !declared && !integrated && !indexed) return;
        if (files.containsKey(TERRAIN)) {
            byte[] compressed = RosecourtJournals.readTerrain(take(TERRAIN), MAX_FILE, deadline);
            scan.inspectReference(new GZIPInputStream(new ByteArrayInputStream(compressed)));
        }
        if ((declared || integrated) && (!files.containsKey(PLANS) || !files.containsKey(INDEX)))
            throw invalid("the integrated jail or its index is missing");
        var plans = integrated ? journal(PLANS) : Map.<UUID,RosecourtJournals.Escrow>of();
        var bindings = indexed ? RosecourtJournals.readIndex(take(INDEX), MAX_FILE, deadline)
                : Map.<UUID,RosecourtJournals.Binding>of();
        if (indexed && ModList.get().isLoaded("minecraftjail")) RosecourtAuthority.verify(server, bindings, deadline);
        for (var plan : plans.values()) {
            if (plan.state().equals("CANCELLED")) checkCancelled(plan, bindings.get(plan.id()));
            else if (!bindings.containsKey(plan.id())) throw invalid("a coordinator record has no inventory pointer");
        }
        boolean found = false;
        for (var binding : bindings.values()) {
            checkTime();
            var plan = plans.get(binding.id());
            if (plan != null && plan.state().equals("CANCELLED")) continue;
            Path itemsPath = pointer(binding.items()), originsPath = pointer(binding.origins());
            var items = record(itemsPath, binding);
            var origin = record(originsPath, binding);
            if (!MessageDigest.isEqual(items.digest(), binding.digest()) || !linkedOrigin(origin.snapshot(), items.digest()))
                throw invalid("the inventory and its custody receipt disagree");
            if (plan != null || itemsPath.startsWith(INTEGRATED) || originsPath.startsWith(INTEGRATED)) {
                Path base = INTEGRATED.resolve(binding.id().toString());
                if (plan == null || !plan.owner().equals(binding.owner()) || !itemsPath.equals(base.resolve("items/escrow.v1"))
                        || !originsPath.equals(base.resolve("origins/escrow.v1"))) throw invalid("an integrated pointer disagrees with its plan");
                var body = compound(plan.snapshot());
                var contract = body.getString("contract");
                if (!CONTRACTS.contains(contract) || !MessageDigest.isEqual(binding.digest(), body.getByteArray("digest")))
                    throw invalid("the coordinator's inventory digest or contract is unrecognized");
                if (!contract.equals("human-empty-v1")) {
                    for (String child : List.of("remove-effects", "return-effects", "escapes")) {
                        var entries = journal(base.resolve(child).resolve("escrow.v1"));
                        for (var effect : entries.values()) {
                            if (!effect.owner().equals(binding.owner()) || !child.equals("escapes") && !effect.id().equals(binding.id()))
                                throw invalid("a custody side record has another owner or transaction");
                            scan.inspectReference(new ByteArrayInputStream(effect.snapshot()));
                        }
                    }
                }
                if (binding.state().equals("RELEASED") != plan.state().equals("COMPLETE"))
                    throw invalid("custody release has not settled");
            }
            boolean released = binding.state().equals("RELEASED");
            if (released) {
                if (!items.state().equals("COMPLETE") || !origin.state().equals("COMPLETE"))
                    throw invalid("the index and return receipts disagree");
                continue;
            }
            if (!items.state().equals("HELD") || !origin.state().equals("HELD")
                    || plan != null && !plan.state().equals("HELD"))
                throw invalid("a custody transfer is unfinished");
            found |= RosecourtInventory.contains(bag, items.snapshot(), deadline);
        }
        for (Path rel : files.keySet()) {
            if (rel.startsWith(MARKERS)) {
                if (rel.getNameCount() != 3 || files.get(rel).size() != 36) throw invalid("invalid backpack custody marker");
                UUID id = canonicalId(rel.getFileName().toString());
                var tx = canonicalId(Files.readString(take(rel), StandardCharsets.US_ASCII));
                var plan = plans.get(tx);
                if (plan == null || !compound(plan.snapshot()).getCompound("backpack_records").contains(id.toString()))
                    throw invalid("a provider's backpack marker has no matching plan");
            }
            if (!verified.contains(rel)) throw invalid("an unindexed journal requires review");
            BagHistoryPresence.checkUnchanged(world.resolve(rel), files.get(rel));
            checkTime();
        }
        if (indexed) RosecourtAuthority.verify(server, bindings, deadline);
        if (found) throw new BagHistoryPresence.Present();
    }

    private void checkCancelled(RosecourtJournals.Escrow plan, RosecourtJournals.Binding binding) throws IOException {
        var base = INTEGRATED.resolve(plan.id().toString());
        var body = compound(plan.snapshot());
        byte[] digest = body.getByteArray("digest");
        if (!CONTRACTS.contains(body.getString("contract")) || digest.length != 32)
            throw invalid("unknown cancelled admission");
        if (binding != null && (!binding.state().equals("RELEASED") || !binding.owner().equals(plan.owner())
                || !pointer(binding.items()).equals(base.resolve("items/escrow.v1"))
                || !pointer(binding.origins()).equals(base.resolve("origins/escrow.v1"))
                || !MessageDigest.isEqual(digest, binding.digest()))) throw invalid("inconsistent cancelled locker");
        for (String child : List.of("items", "origins", "remove-effects", "return-effects", "escapes")) {
            var path = base.resolve(child).resolve("escrow.v1");
            if (!files.containsKey(path)) continue;
            var records = journal(path);
            if (records.isEmpty()) continue;
            var item = records.get(plan.id());
            if (!child.equals("items") || records.size() != 1 || item == null || !item.state().equals("CANCELLED")
                    || !item.owner().equals(plan.owner()) || !MessageDigest.isEqual(digest, item.digest()))
                throw invalid("a cancelled admission still has an unresolved record");
        }
    }

    private Path take(Path rel) throws IOException {
        checkTime();
        if (!files.containsKey(rel) || !verified.add(rel)) throw invalid("a custody file is missing or referenced twice");
        var file = world.resolve(rel);
        BagHistoryPresence.checkUnchanged(file, files.get(rel));
        return file;
    }
    private Map<UUID,RosecourtJournals.Escrow> journal(Path rel) throws IOException {
        return RosecourtJournals.readEscrow(take(rel), MAX_FILE, deadline);
    }
    private RosecourtJournals.Escrow record(Path rel, RosecourtJournals.Binding binding) throws IOException {
        var entries = journal(rel);
        var entry = entries.get(binding.id());
        if (entries.size() != 1 || entry == null || !entry.owner().equals(binding.owner()))
            throw invalid("a locker does not identify exactly one matching owner and transaction");
        return entry;
    }
    private static Path pointer(String path) throws IOException {
        Path dir = Path.of(path);
        if (dir.isAbsolute() || !dir.equals(dir.normalize()) || path.contains("\\") || path.contains(":"))
            throw invalid("invalid custody path");
        return dir.resolve("escrow.v1");
    }
    private static boolean linkedOrigin(byte[] snapshot, byte[] digest) {
        if (snapshot.length <= 40) return false;
        var body = ByteBuffer.wrap(snapshot);
        byte[] stored = new byte[32];
        if (body.getInt() != 0x4a4f4c31) return false;
        body.get(stored);
        return body.getInt() == body.remaining() && MessageDigest.isEqual(stored, digest);
    }
    private static CompoundTag compound(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            var root = NbtIo.read(in, NbtAccounter.create(4L << 20));
            if (in.read() != -1) throw invalid("invalid coordinator data");
            return root;
        }
    }
    private static UUID canonicalId(String text) throws IOException {
        try {
            UUID id = UUID.fromString(text);
            if (id.toString().equals(text)) return id;
        } catch (IllegalArgumentException ignored) {}
        throw invalid("invalid custody identity");
    }
    private void checkTime() throws IOException {
        if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted()) throw invalid("scan time exceeded");
    }
    private static IOException invalid(String why) { return new IOException("Rosecourt recovery paused because " + why + "."); }
}
