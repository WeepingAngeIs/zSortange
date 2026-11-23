package dev.anya.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/** One atomically replaced, checksummed world-local record per backpack. An observation cannot authorize a return. */
final class BagHistoryStore {
    static final long RETENTION_MS = Duration.ofDays(30).toMillis();
    private static final int FORMAT = 1, MAX_RECORDS = 8192, MAX_FILE = 4 << 20, MAX_NBT = 8 << 20;
    private static final long MAX_TOTAL = 64L << 20;
    // Minecraft owns the world lock across processes. This also serializes independent store instances in this JVM.
    private static final Object DISK_WRITE = new Object();
    private static WeakReference<MinecraftServer> currentServer = new WeakReference<>(null);
    private static BagHistoryStore currentStore;

    enum State { OBSERVED, CAPTURING, AVAILABLE, PENDING, DELIVERING, RETURNED, LOCATED, CONFLICT }

    record Entry(UUID id, UUID owner, String ownerName, String bagName, long time, long expires,
                 State state, UUID transaction, CompoundTag stack, CompoundTag proof) {
        Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(state);
            if (time < 0 || time > Long.MAX_VALUE - RETENTION_MS || expires != time + RETENTION_MS
                    || ownerName == null || ownerName.length() > 128 || bagName == null || bagName.length() > 256)
                throw new IllegalArgumentException("Invalid backpack history metadata");
            if (state == State.OBSERVED && (transaction != null || stack != null))
                throw new IllegalArgumentException("Observations do not hold recoverable items");
            if (state != State.OBSERVED && state != State.CONFLICT && (transaction == null || stack == null))
                throw new IllegalArgumentException("Missing backpack recovery item or loss ID");
            stack = stack == null ? null : stack.copy();
            proof = proof == null ? new CompoundTag() : proof.copy();
        }

        @Override public CompoundTag stack() { return stack == null ? null : stack.copy(); }
        @Override public CompoundTag proof() { return proof.copy(); }

        static Entry observed(UUID id, UUID owner, String ownerName, String bagName, long now) {
            return new Entry(id, owner, limit(ownerName, 128), limit(bagName, 256), now, now + RETENTION_MS,
                    State.OBSERVED, null, null, null);
        }

        Entry capture(ItemStack bag, HolderLookup.Provider registries, long now, UUID cycle) throws IOException {
            if (!BackpackAccess.isBag(bag) || !id.equals(bagId(bag))) throw new IOException("Different backpack identity");
            try {
                if (!(bag.save(registries) instanceof CompoundTag saved) || saved.sizeInBytes() > MAX_NBT)
                    throw new IOException("Backpack exceeds recovery storage limits");
                var captured = new Entry(id, owner, ownerName, limit(bag.getHoverName().getString(), 256),
                        now, now + RETENTION_MS, State.CAPTURING, Objects.requireNonNull(cycle), saved, null);
                if (!ItemStack.matches(bag, captured.restoreStack(registries)))
                    throw new IOException("The complete backpack could not be preserved");
                return captured;
            } catch (RuntimeException ex) {
                throw new IOException("Cannot encode the complete backpack", ex);
            }
        }

        ItemStack restoreStack(HolderLookup.Provider registries) throws IOException {
            if (stack == null) throw new IOException("No recoverable backpack in this record");
            try {
                var decoded = ItemStack.parse(registries, stack.copy()).orElseThrow();
                if (!BackpackAccess.isBag(decoded) || !id.equals(bagId(decoded)) || !stack.equals(decoded.save(registries)))
                    throw new IOException("The exact backpack components are unavailable");
                return decoded;
            } catch (RuntimeException ex) {
                throw new IOException("The exact backpack components are unavailable", ex);
            }
        }

        Entry withState(State next) {
            return new Entry(id, owner, ownerName, bagName, time, expires, next, transaction, stack, proof);
        }

        Entry withProof(CompoundTag nextProof) {
            return new Entry(id, owner, ownerName, bagName, time, expires, state, transaction, stack, nextProof);
        }

        Entry conflict() {
            return withState(State.CONFLICT);
        }

        private static String limit(String value, int max) { return value.substring(0, Math.min(max, value.length())); }
        private static UUID bagId(ItemStack bag) {
            var data = BagInventory.data(bag);
            return data.hasUUID("BagId") ? data.getUUID("BagId") : null;
        }
    }

    private record Stored(Entry entry, byte[] checksum, long weight) {}
    private final Map<UUID, Stored> records = new HashMap<>();
    private final LongSupplier clock;
    private final Path directory;
    private long weight;
    private boolean writeUncertain;

    static synchronized BagHistoryStore open(MinecraftServer server) throws IOException {
        if (!server.isSameThread()) throw new IOException("Backpack history requires the server thread");
        if (currentServer.get() != server || currentStore == null) {
            var store = new BagHistoryStore(server.getWorldPath(LevelResource.ROOT), System::currentTimeMillis);
            currentServer = new WeakReference<>(server);
            currentStore = store;
        }
        return currentStore;
    }

    static synchronized BagHistoryStore openIfExists(MinecraftServer server) throws IOException {
        if (!server.isSameThread()) throw new IOException("Backpack history requires the server thread");
        if (currentServer.get() == server && currentStore != null) return currentStore;
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("anya_storage/backpack_history");
        return Files.notExists(dir, LinkOption.NOFOLLOW_LINKS) ? null : open(server);
    }

    BagHistoryStore(Path worldRoot, LongSupplier clock) throws IOException {
        this.clock = clock;
        Path root = worldRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid backpack history world");
        directory = subdirectory(subdirectory(root, "anya_storage"), "backpack_history");
        synchronized (DISK_WRITE) { load(); }
        prune();
    }

    private void load() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.startsWith(".write-") && name.endsWith(".tmp") && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    // The server holds the world lock. A temp file has never been authorized for recovery.
                    Files.delete(file);
                    continue;
                }
                if (!name.endsWith(".dat") || records.size() >= MAX_RECORDS) throw new IOException("Invalid backpack history directory");
                byte[] bytes = readBytes(file);
                Entry row = decode(bytes);
                if (!name.equals(row.id + ".dat") || records.containsKey(row.id))
                    throw new IOException("Backpack history file identity mismatch");
                Stored s = stored(row, bytes);
                if (weight + s.weight > MAX_TOTAL) throw new IOException("Backpack history exceeds its size limit");
                records.put(row.id, s);
                weight += s.weight;
            }
        }
    }

    synchronized Entry get(UUID id) throws IOException {
        checkReady();
        var current = records.get(id);
        if (current == null) return null;
        if (current.entry.expires <= clock.getAsLong()) {
            removeExpired(current);
            return null;
        }
        return current.entry;
    }

    synchronized List<Entry> entries() throws IOException {
        prune();
        return records.values().stream().map(Stored::entry).toList();
    }

    synchronized void prune() throws IOException {
        checkReady();
        long now = clock.getAsLong();
        for (var current : new ArrayList<>(records.values()))
            if (current.entry.expires <= now) removeExpired(current);
    }

    synchronized void put(Entry expected, Entry next) throws IOException {
        var current = get(next.id);
        if (!Objects.equals(current, expected) || next.expires <= clock.getAsLong())
            throw new IOException("Expired or stale backpack history operation");
        checkTransition(expected, next);
        byte[] bytes = encode(next);
        if (!next.equals(decode(bytes))) throw new IOException("Backpack history did not survive the disk codec");
        Stored newRecord = stored(next, bytes);
        Stored before = records.get(next.id);
        long delta = newRecord.weight - (before == null ? 0 : before.weight);
        if ((before == null && records.size() >= MAX_RECORDS) || weight + delta > MAX_TOTAL) {
            prune();
            if ((before == null && records.size() >= MAX_RECORDS) || weight + delta > MAX_TOTAL)
                throw new IOException("Backpack history is full; existing recoveries retained");
        }
        synchronized (DISK_WRITE) {
            Path file = path(next.id);
            verifyDisk(file, before);
            Path temp = Files.createTempFile(directory, ".write-", ".tmp");
            try {
                try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    var data = ByteBuffer.wrap(bytes);
                    while (data.hasRemaining()) out.write(data);
                    out.force(true);
                }
                if (next.expires <= clock.getAsLong()) throw new IOException("Backpack history expired before the write completed");
                writeUncertain = true;
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                syncDirectory(directory);
                records.put(next.id, newRecord);
                weight += delta;
                writeUncertain = false;
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static void checkTransition(Entry old, Entry next) throws IOException {
        if (old == null) {
            if (next.state != State.OBSERVED && next.state != State.CAPTURING)
                throw new IOException("History needs an observation or capture before recovery");
            return;
        }
        if (next.state == State.CAPTURING && (old.state == State.OBSERVED || old.state == State.RETURNED || old.state == State.LOCATED)) {
            // A later, separately witnessed loss can belong to a player the item was transferred to.
            // The gameplay service supplies the attested owner; an in-flight cycle can never be reassigned.
            if (Objects.equals(old.transaction, next.transaction))
                throw new IOException("A new loss needs a new loss ID");
            return;
        }
        if (old.state == State.OBSERVED && next.state == State.OBSERVED) return;
        if (!old.owner.equals(next.owner) || !Objects.equals(old.transaction, next.transaction)
                || old.time != next.time || old.expires != next.expires
                || !Objects.equals(old.stack, next.stack))
            throw new IOException("Backpack loss identity, snapshot or expiry changed");
        boolean allowed = old.state == next.state || next.state == State.CONFLICT || switch (old.state) {
            case CAPTURING -> next.state == State.AVAILABLE || next.state == State.LOCATED;
            case AVAILABLE -> next.state == State.PENDING || next.state == State.LOCATED;
            case PENDING -> next.state == State.DELIVERING || next.state == State.LOCATED;
            case DELIVERING -> next.state == State.RETURNED || next.state == State.LOCATED;
            default -> false;
        };
        if (!allowed) throw new IOException("Invalid backpack return state transition");
    }

    private void removeExpired(Stored stored) throws IOException {
        synchronized (DISK_WRITE) {
            Path file = path(stored.entry.id);
            verifyDisk(file, stored);
            writeUncertain = true;
            Files.delete(file);
            syncDirectory(directory);
            records.remove(stored.entry.id);
            weight -= stored.weight;
            writeUncertain = false;
        }
    }

    private Path path(UUID id) { return directory.resolve(id + ".dat"); }

    private static Path subdirectory(Path parent, String name) throws IOException {
        Path child = parent.resolve(name);
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(child);
            syncDirectory(parent);
        }
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid backpack history directory");
        return child;
    }

    private static void syncDirectory(Path dir) throws IOException {
        // Windows does not expose directory fsync through FileChannel. File data is always forced before atomic rename.
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) { channel.force(true); }
        catch (java.nio.file.AccessDeniedException ex) {
            if (java.io.File.separatorChar != '\\') throw ex;
        } catch (UnsupportedOperationException ignored) {}
    }

    private void checkReady() throws IOException {
        if (writeUncertain || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(directory.getParent(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Backpack history backing storage is unavailable");
    }

    private static void verifyDisk(Path file, Stored expected) throws IOException {
        if (expected == null && Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (expected == null || !MessageDigest.isEqual(expected.checksum, hash(readBytes(file))))
            throw new IOException("Backpack history changed on disk; refusing stale write");
    }

    private static Stored stored(Entry row, byte[] bytes) {
        long size = (row.stack == null ? 0 : (long)row.stack.sizeInBytes()) + row.proof.sizeInBytes() + 512;
        return new Stored(row, hash(bytes), Math.max(size, bytes.length));
    }

    private static byte[] readBytes(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE)
            throw new IOException("Invalid backpack history file");
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = in.readNBytes(MAX_FILE + 1);
            if (data.length < 33 || data.length > MAX_FILE) throw new IOException("Invalid backpack history length");
            return data;
        }
    }

    private static byte[] encode(Entry entry) throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Format", FORMAT);
        tag.putUUID("Bag", entry.id); tag.putUUID("Owner", entry.owner);
        tag.putString("OwnerName", entry.ownerName); tag.putString("BagName", entry.bagName);
        tag.putLong("Time", entry.time); tag.putLong("Expires", entry.expires);
        tag.putString("State", entry.state.name());
        if (entry.transaction != null) tag.putUUID("Loss", entry.transaction);
        if (entry.stack != null) tag.put("Stack", entry.stack);
        tag.put("Proof", entry.proof);
        if (tag.sizeInBytes() < 0 || tag.sizeInBytes() > MAX_NBT) throw new IOException("Backpack history record too large");
        var buffer = new ByteArrayOutputStream();
        // The normal vanilla writer can substitute an empty string for overlong UTF. Refuse that escrow instead.
        NbtIo.writeUnnamedTag(tag, new DataOutputStream(buffer));
        if (buffer.size() + 32 > MAX_FILE) throw new IOException("Backpack history record too large");
        buffer.write(hash(buffer.toByteArray()));
        return buffer.toByteArray();
    }

    private static Entry decode(byte[] bytes) throws IOException {
        int end = bytes.length - 32;
        byte[] payload = Arrays.copyOf(bytes, end);
        if (!MessageDigest.isEqual(hash(payload), Arrays.copyOfRange(bytes, end, bytes.length)))
            throw new IOException("Backpack history checksum mismatch");
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            CompoundTag tag = NbtIo.read(in, NbtAccounter.create(MAX_NBT));
            if (in.available() != 0 || tag.getAllKeys().size() != 9 + (tag.contains("Loss") ? 1 : 0) + (tag.contains("Stack") ? 1 : 0)
                    || !tag.contains("Format", Tag.TAG_INT) || tag.getInt("Format") != FORMAT
                    || !tag.hasUUID("Bag") || !tag.hasUUID("Owner") || !tag.contains("OwnerName", Tag.TAG_STRING)
                    || !tag.contains("BagName", Tag.TAG_STRING) || !tag.contains("Time", Tag.TAG_LONG)
                    || !tag.contains("Expires", Tag.TAG_LONG) || !tag.contains("State", Tag.TAG_STRING)
                    || !tag.contains("Proof", Tag.TAG_COMPOUND) || (tag.contains("Loss") && !tag.hasUUID("Loss"))
                    || (tag.contains("Stack") && !tag.contains("Stack", Tag.TAG_COMPOUND)))
                throw new IOException("Invalid backpack history schema");
            return new Entry(tag.getUUID("Bag"), tag.getUUID("Owner"), tag.getString("OwnerName"), tag.getString("BagName"),
                    tag.getLong("Time"), tag.getLong("Expires"), State.valueOf(tag.getString("State")),
                    tag.hasUUID("Loss") ? tag.getUUID("Loss") : null,
                    tag.contains("Stack") ? tag.getCompound("Stack") : null, tag.getCompound("Proof"));
        } catch (RuntimeException ex) {
            throw new IOException("Invalid backpack history record; existing data was not reset", ex);
        }
    }

    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
