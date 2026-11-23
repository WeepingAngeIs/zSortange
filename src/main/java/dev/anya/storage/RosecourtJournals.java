package dev.anya.storage;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** Read-only readers for the Rosecourt beta.3 world journals. The jail retains its writer locks. */
final class RosecourtJournals {
    private static final int MAX_FILE = 16 << 20;
    private static final int MAX_SNAPSHOT = 4 << 20;
    private static final int MAX_TERRAIN = 8 << 20;
    private static final int MAX_TRANSACTIONS = 8192;
    private static final int HASH_SIZE = 32;
    private static final String[] ESCROW_STATES = {
            "PREPARED", "REMOVAL_STARTED", "HELD", "RESTORE_STARTED", "COMPLETE", "CANCELLED"};
    private static final String[] INDEX_STATES = {"QUARANTINED", "REVIEWED", "RELEASED"};
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern POINTER = Pattern.compile("[a-zA-Z0-9_./-]+");

    record Escrow(UUID id, UUID owner, String state, byte[] snapshot, byte[] digest) {}
    record Binding(UUID id, UUID owner, String items, String origins, byte[] digest, String state) {}
    private record Anchor(String dimension, int x, int y, int z) {}

    static Map<UUID, Escrow> readEscrow(Path path, int maxBytes, long deadlineNanos) throws IOException {
        var result = new HashMap<UUID, Escrow>();
        var activeOwners = new HashMap<UUID, UUID>();
        var in = input(contents(path, maxBytes, deadlineNanos));
        require(in.readInt() == 0x4a455331 && in.readInt() == 1);
        while (in.available() != 0) {
            time(deadlineNanos);
            require(in.readInt() == 0x4a454631);
            int length = in.readInt();
            require(length > 40 && length <= MAX_SNAPSHOT + 40 && length <= in.available() - HASH_SIZE);
            byte[] body = in.readNBytes(length);
            require(MessageDigest.isEqual(sha256().digest(body), in.readNBytes(HASH_SIZE)));
            var frame = input(body);
            UUID id = uuid(frame), owner = uuid(frame);
            int ordinal = frame.readInt(), size = frame.readInt();
            require(ordinal >= 0 && ordinal < ESCROW_STATES.length && size == frame.available() && size > 0);
            String state = ESCROW_STATES[ordinal];
            byte[] snapshot = frame.readNBytes(size);
            var previous = result.get(id);
            byte[] itemHash;
            if (previous == null) {
                require(result.size() < MAX_TRANSACTIONS && state.equals("PREPARED")
                        && activeOwners.putIfAbsent(owner, id) == null);
                itemHash = sha256().digest(snapshot);
            } else {
                require(owner.equals(previous.owner()) && Arrays.equals(snapshot, previous.snapshot())
                        && transition(previous.state(), state));
                snapshot = previous.snapshot();
                itemHash = previous.digest();
            }
            if (state.equals("COMPLETE") || state.equals("CANCELLED")) require(activeOwners.remove(owner, id));
            result.put(id, new Escrow(id, owner, state, snapshot, itemHash));
        }
        time(deadlineNanos);
        return Map.copyOf(result);
    }

    private static boolean transition(String from, String to) {
        return switch (from) {
            case "PREPARED" -> to.equals("CANCELLED") || to.equals("REMOVAL_STARTED");
            case "REMOVAL_STARTED" -> to.equals("HELD");
            case "HELD" -> to.equals("RESTORE_STARTED");
            case "RESTORE_STARTED" -> to.equals("COMPLETE");
            default -> false;
        };
    }

    static Map<UUID, Binding> readIndex(Path path, int maxBytes, long deadlineNanos) throws IOException {
        var result = new HashMap<UUID, Binding>();
        var occupied = new HashMap<Anchor, UUID>();
        var byId = new HashMap<UUID, Anchor>();
        var in = input(contents(path, maxBytes, deadlineNanos));
        require(in.readInt() == 0x55435631 && in.readInt() == 1);
        byte[] chain = new byte[HASH_SIZE];
        long sequence = 0;
        while (in.available() != 0) {
            time(deadlineNanos);
            int length = in.readInt();
            require(length > 0 && length <= 16384 && length <= in.available() - HASH_SIZE);
            byte[] body = in.readNBytes(length);
            var hasher = sha256();
            hasher.update(chain);
            chain = hasher.digest(body);
            require(MessageDigest.isEqual(chain, in.readNBytes(HASH_SIZE)));
            var frame = input(body);
            require(++sequence <= 100000 && frame.readLong() == sequence);
            UUID id = uuid(frame), owner = uuid(frame);
            String items = pointer(frame.readUTF()), origins = pointer(frame.readUTF());
            byte[] digest = frame.readNBytes(HASH_SIZE);
            String dim = frame.readUTF();
            require(digest.length == HASH_SIZE && DIMENSION.matcher(dim).matches());
            var anchor = new Anchor(dim, frame.readInt(), frame.readInt(), frame.readInt());
            int ordinal = frame.readUnsignedByte();
            require(ordinal < INDEX_STATES.length);
            String state = INDEX_STATES[ordinal];
            text(frame.readUTF(), 128); // actor
            text(frame.readUTF(), 512); // reason
            require(frame.available() == 0);
            var previous = result.get(id);
            if (previous == null) {
                require(result.size() < MAX_TRANSACTIONS && state.equals("QUARANTINED"));
            } else {
                require(owner.equals(previous.owner()) && items.equals(previous.items())
                        && origins.equals(previous.origins()) && MessageDigest.isEqual(digest, previous.digest()));
            }
            UUID lockerOwner = occupied.get(anchor);
            require(lockerOwner == null || lockerOwner.equals(id));
            var oldAnchor = byId.remove(id);
            if (oldAnchor != null) occupied.remove(oldAnchor, id);
            if (!state.equals("RELEASED")) {
                occupied.put(anchor, id);
                byId.put(id, anchor);
            }
            result.put(id, new Binding(id, owner, items, origins, digest, state));
        }
        time(deadlineNanos);
        return Map.copyOf(result);
    }

    /** The backup stores only before/after block states. Reject unknown schemas rather than treating other data as terrain. */
    static byte[] readTerrain(Path path, int maxBytes, long deadlineNanos) throws IOException {
        var in = input(contents(path, Math.min(maxBytes, MAX_TERRAIN + 40), deadlineNanos));
        require(in.readInt() == 0x554a4231);
        int length = in.readInt();
        require(length >= 18 && length <= MAX_TERRAIN && length == in.available() - HASH_SIZE);
        byte[] payload = in.readNBytes(length);
        require((payload[0] & 255) == 0x1f && (payload[1] & 255) == 0x8b && payload[2] == 8
                && MessageDigest.isEqual(sha256().digest(payload), in.readNBytes(HASH_SIZE)));
        verifyTerrain(terrainNbt(payload, deadlineNanos), deadlineNanos);
        return payload;
    }

    /** The native writer emits a single gzip member with a ten-byte header and an eight-byte trailer. */
    private static CompoundTag terrainNbt(byte[] gzip, long deadline) throws IOException {
        require(gzip[3] == 0);
        var trailer = ByteBuffer.wrap(gzip, gzip.length - 8, 8).order(ByteOrder.LITTLE_ENDIAN);
        long crc = Integer.toUnsignedLong(trailer.getInt());
        int size = trailer.getInt();
        require(size >= 4 && size <= (16 << 20));
        var raw = new byte[size];
        var deflate = new Inflater(true);
        try {
            deflate.setInput(gzip, 10, gzip.length - 18);
            int used = 0;
            while (!deflate.finished() && used < size) {
                time(deadline);
                int count = deflate.inflate(raw, used, Math.min(16384, size - used));
                require(count > 0);
                used += count;
            }
            if (!deflate.finished()) require(deflate.inflate(new byte[1]) == 0 && deflate.finished());
            require(used == size && deflate.getRemaining() == 0);
        } catch (DataFormatException failure) {
            throw new IOException("Rosecourt terrain compression is invalid.", failure);
        } finally {
            deflate.end();
        }
        var checksum = new CRC32();
        checksum.update(raw);
        require(checksum.getValue() == crc && raw[0] == Tag.TAG_COMPOUND && raw[1] == 0 && raw[2] == 0);
        var input = input(raw);
        try {
            var root = NbtIo.read(input, NbtAccounter.create(16L << 20));
            require(input.available() == 0);
            time(deadline);
            return root;
        } catch (RuntimeException | StackOverflowError failure) {
            throw new IOException("Rosecourt terrain is invalid or exceeds the recovery limits.", failure);
        }
    }

    private static void verifyTerrain(CompoundTag root, long deadline) throws IOException {
        require(Set.of("format", "layout", "dimension", "min", "before", "after", "priorGeometry", "actor", "prior")
                .containsAll(root.getAllKeys()) && root.contains("format", Tag.TAG_INT)
                && DIMENSION.matcher(root.getString("dimension")).matches() && root.getIntArray("min").length == 3
                && root.contains("priorGeometry", Tag.TAG_INT) && root.hasUUID("actor"));
        int format = root.getInt("format");
        require(format == 1 && !root.contains("layout") || format == 2 && root.contains("layout", Tag.TAG_STRING));
        String layout = format == 1 ? "CLASSIC" : root.getString("layout");
        require(layout.equals("CLASSIC") || layout.equals("ROSECOURT"));
        int volume = layout.equals("CLASSIC") ? 13 * 7 * 7 : 19 * 10 * 30;
        if (root.contains("prior")) {
            var prior = root.getCompound("prior");
            require(prior.getAllKeys().equals(Set.of("dim", "x", "y", "z", "yaw", "pitch"))
                    && DIMENSION.matcher(prior.getString("dim")).matches());
            for (String key : List.of("x", "y", "z"))
                require(prior.contains(key, Tag.TAG_DOUBLE) && Double.isFinite(prior.getDouble(key)));
            for (String key : List.of("yaw", "pitch"))
                require(prior.contains(key, Tag.TAG_FLOAT) && Float.isFinite(prior.getFloat(key)));
        }
        for (String side : List.of("before", "after")) {
            var states = root.getList(side, Tag.TAG_COMPOUND);
            require(states.size() == volume);
            for (int i = 0; i < volume; i++) {
                time(deadline);
                var state = states.getCompound(i);
                require(state.getAllKeys().size() == (state.contains("Properties") ? 2 : 1)
                        && DIMENSION.matcher(state.getString("Name")).matches());
                if (!state.contains("Properties")) continue;
                require(state.contains("Properties", Tag.TAG_COMPOUND));
                var props = state.getCompound("Properties");
                require(!props.isEmpty() && props.getAllKeys().size() <= 128);
                for (String key : props.getAllKeys()) {
                    require(!key.isBlank() && key.length() <= 256 && props.contains(key, Tag.TAG_STRING));
                    text(props.getString(key), 256);
                }
            }
        }
    }

    private static byte[] contents(Path path, int maxBytes, long deadlineNanos) throws IOException {
        time(deadlineNanos);
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long size = before.size();
        require(before.isRegularFile() && before.fileKey() != null && size >= 8 && size <= Math.min(maxBytes, MAX_FILE));
        var bytes = ByteBuffer.allocate((int) size);
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            require(channel.size() == size);
            while (bytes.position() < size) {
                time(deadlineNanos);
                bytes.limit(Math.min((int) size, bytes.position() + 65536));
                require(channel.read(bytes) > 0);
            }
            require(channel.size() == size);
        }
        var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(after.isRegularFile() && before.fileKey().equals(after.fileKey()) && after.size() == size
                && before.lastModifiedTime().equals(after.lastModifiedTime()));
        return bytes.array();
    }

    private static DataInputStream input(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }

    private static String pointer(String value) throws IOException {
        require(value.length() <= 512 && POINTER.matcher(value).matches());
        for (String part : value.split("/", -1)) require(!part.isEmpty() && !part.equals(".") && !part.equals(".."));
        return value;
    }

    private static void text(String value, int max) throws IOException {
        require(!value.isBlank() && value.length() <= max && value.chars().noneMatch(Character::isISOControl));
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void time(long deadlineNanos) throws IOException {
        if (System.nanoTime() - deadlineNanos >= 0 || Thread.currentThread().isInterrupted())
            throw new IOException("The Rosecourt custody check exceeded its time limit.");
    }

    private static void require(boolean valid) throws IOException {
        if (!valid) throw new IOException("Rosecourt custody data is incomplete or unrecognized. Recovery is paused.");
    }

    private RosecourtJournals() {}
}
