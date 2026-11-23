package dev.anya.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Preflight for a single deliberate restore, after player and dimension saves have been flushed.
 * Reads the active world files without opening a writable Minecraft region cache. World-local
 * unknown stores make the result inconclusive. External mod databases need a provider-specific check.
 */
final class BagHistoryPresence extends SimpleFileVisitor<Path> {
    static final class Present extends IOException {
        Present() { super("That backpack already exists in storage."); }
    }

    private static final int MAX_RECORD = 16 << 20;
    private static final long MAX_WORLD_BYTES = 512L << 20;
    private static final long MAX_REGION = 512L << 20;
    private static final int MAX_PATHS = 65_536;
    private static final Pattern REGION = Pattern.compile("r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca");
    private static final Path JOURNAL = Path.of("anya_storage", "backpack_history");
    private static final Path POCKET_INDEX = Path.of("anya_storage", "pockets.v1.dat");
    private final Set<Path> externalFiles = new HashSet<>(), referenced = new HashSet<>();
    private final Path root;
    private final UUID bag;
    private final long started = System.nanoTime();
    private final RosecourtCustody custody;
    private boolean readingJailSettings;
    private boolean live = true;
    private Path unrecognized;
    private final byte[] discard = new byte[8192];
    private long inflated, packed, tags;
    private int paths;

    private BagHistoryPresence(MinecraftServer server, Path root, UUID bag) {
        this.root = root;
        this.bag = bag;
        custody = new RosecourtCustody(server, root, bag, started + TimeUnit.SECONDS.toNanos(5));
    }

    /** One instance per restore request so shared menu/inventory slots consume a single inspection budget. */
    static final class LiveStacks {
        private final UUID bag;
        private final Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private final long start = System.nanoTime();
        private long total, record, tags;

        LiveStacks(UUID bagId) { bag = Objects.requireNonNull(bagId); }

        void check(ItemStack stack, HolderLookup.Provider registries) throws IOException {
            if (stack.isEmpty() || !seen.add(stack)) return;
            if (seen.size() > MAX_PATHS) throw new IOException("Too many live slots for one recovery check.");
            time();
            record = 0;
            try {
                // Serializing through the real registry covers bundles, container items, custom data, equipment,
                // and nested stacks represented by other mods' persistent component codecs.
                visit(stack.save(registries), 0);
            } catch (RuntimeException | StackOverflowError ex) {
                throw new IOException("A live item could not be inspected safely; recovery paused.", ex);
            }
            time();
        }

        private void time() throws IOException {
            if (System.nanoTime() - start > TimeUnit.SECONDS.toNanos(5) || Thread.currentThread().isInterrupted())
                throw new IOException("The live inventory check exceeded its time limit; recovery paused.");
        }

        private void charge(long bytes) throws IOException {
            record += bytes;
            total += bytes;
            if (record > MAX_RECORD || total > MAX_WORLD_BYTES)
                throw new IOException("Live inventory data exceeds the recovery check limit.");
            if ((++tags & 1023) == 0) time();
        }

        private void visit(Tag tag, int depth) throws IOException {
            if (depth > 256) throw new IOException("Live inventory nesting exceeds the recovery check limit.");
            charge(1);
            if (tag instanceof CompoundTag compound) {
                if (compound.contains("BagId")) {
                    if (!compound.hasUUID("BagId")) throw new IOException("Unsupported live backpack identity format.");
                    if (bag.equals(compound.getUUID("BagId"))) throw new Present();
                }
                for (String key : compound.getAllKeys()) {
                    charge(2L + 3L * key.length());
                    visit(compound.get(key), depth + 1);
                }
            } else if (tag instanceof ListTag list) {
                for (Tag member : list) visit(member, depth + 1);
            } else if (tag instanceof CollectionTag<?> array) {
                int width = switch (tag.getId()) {
                    case Tag.TAG_BYTE_ARRAY -> 1;
                    case Tag.TAG_INT_ARRAY -> 4;
                    case Tag.TAG_LONG_ARRAY -> 8;
                    default -> throw new IOException("Unrecognized live NBT collection.");
                };
                charge(4L + (long) width * array.size());
            } else if (tag.getId() == Tag.TAG_STRING) {
                charge(2L + 3L * tag.getAsString().length());
            } else {
                int width = width(tag.getId());
                if (width == 0) throw new IOException("Unrecognized live NBT value.");
                charge(width);
            }
        }
    }

    static void verifyAbsent(MinecraftServer server, UUID bagId) throws IOException {
        var scan = new BagHistoryPresence(server, server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(), bagId);
        if (!Files.isDirectory(scan.root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(scan.root.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The active world save is unavailable.");
        try {
            Files.walkFileTree(scan.root, Set.of(), 64, scan);
        } catch (RuntimeException e) {
            throw new IOException("A saved-storage record could not be inspected; recovery paused.", e);
        }
        if (!scan.externalFiles.equals(scan.referenced)) throw new IOException("Unmatched external chunk files; recovery paused.");
        scan.custody.verify(scan);
        if (scan.unrecognized != null)
            throw new IOException("Unrecognized world storage (" + scan.unrecognized + "); recovery cannot verify its contents.");
        scan.checkTime();
    }

    private void checkTime() throws IOException {
        if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(5) || Thread.currentThread().isInterrupted())
            throw new IOException("The saved-storage check exceeded its time limit; recovery paused.");
    }

    private void countPath() throws IOException {
        checkTime();
        if (++paths > MAX_PATHS) throw new IOException("Too many saved files for a bounded recovery check.");
    }

    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        countPath();
        Path rel = root.relativize(dir);
        if (rel.equals(RosecourtCustody.INTEGRATED)) custody.integrated = true;
        if (rel.equals(RosecourtCustody.INTERFACE)) custody.indexed = true;
        if (rel.startsWith("jail-runtime-transitions-v1") && rel.getNameCount() > 1) {
            // Migration bundles can contain historical playerdata copies. A backup cannot close a live claim.
            if (unrecognized == null) unrecognized = rel;
            return FileVisitResult.SKIP_SUBTREE;
        }
        // Datapacks are definitions/structures available to the world, never live inventory instances.
        if (rel.equals(JOURNAL) || rel.equals(Path.of("datapacks"))) return FileVisitResult.SKIP_SUBTREE;
        return FileVisitResult.CONTINUE;
    }

    @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
        countPath();
        if (!attrs.isRegularFile()) throw new IOException("A linked, nested too deeply, or unreadable world entry prevents recovery.");
        Path rel = root.relativize(path);
        String name = path.getFileName().toString();
        boolean cobblemonBackup = CobblemonPlayerData.nbtFallback(rel);
        if (rel.equals(POCKET_INDEX) || rel.equals(Path.of("level.dat_old"))
                || rel.getNameCount() == 2 && rel.startsWith("playerdata") && name.endsWith(".dat_old")
                || rel.equals(Path.of("session.lock")) || rel.equals(Path.of("serverconfig", "readme.txt"))
                || rel.equals(Path.of("icon.png")) || (rel.startsWith("stats") || rel.startsWith("advancements")) && name.endsWith(".json")
                || rel.startsWith("serverconfig") && name.endsWith(".toml")
                || rel.startsWith(Path.of("anya_storage", "guide_grants")) && name.endsWith(".claim")
                || rel.startsWith(Path.of("anya_storage", "interiors")) && (name.endsWith(".png") || name.equals("catalog.json")))
            return FileVisitResult.CONTINUE;
        if (custody.collect(rel, attrs)) {
            chargePacked(attrs.size());
        } else if (CobblemonPlayerData.recognizes(rel)) {
            chargePacked(attrs.size());
            CobblemonPlayerData.verify(path, rel, started + TimeUnit.SECONDS.toNanos(5));
        } else if (name.endsWith(".mca")) {
            region(path);
        } else if (name.endsWith(".mcc")) {
            externalFiles.add(path);
        } else if (name.endsWith(".dat") || name.endsWith(".nbt") || cobblemonBackup) {
            chargePacked(attrs.size());
            readingJailSettings = rel.equals(Path.of("data", "unyielding_jail.dat"));
            try (var input = new BufferedInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))) {
                input.mark(2);
                int a = input.read(), b = input.read();
                input.reset();
                if (a == 0x1f && b == 0x8b) inspect(new GZIPInputStream(input));
                else if ((a & 15) == 8 && b >= 0 && ((a << 8) + b) % 31 == 0) inspect(new InflaterInputStream(input));
                else inspect(input);
            } catch (Present present) {
                if (cobblemonBackup) throw new IOException("A Cobblemon backup contains that backpack. Check its active location before recovery.");
                throw present;
            } finally { readingJailSettings = false; }
        } else if (unrecognized == null) {
            // A known existing item can close the claim. Absence still requires every store to be understood.
            unrecognized = rel;
        }
        checkUnchanged(path, attrs);
        return FileVisitResult.CONTINUE;
    }

    static void checkUnchanged(Path path, BasicFileAttributes before) throws IOException {
        var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime())
                || !Objects.equals(after.fileKey(), before.fileKey()))
            throw new IOException("Saved storage changed during the check; retry recovery.");
    }

    private void chargePacked(long bytes) throws IOException {
        if (bytes < 1 || bytes > MAX_RECORD || (packed += bytes) > MAX_WORLD_BYTES)
            throw new IOException("Saved data exceeds the bounded recovery check.");
    }

    private static void readAt(FileChannel channel, long offset, ByteBuffer dst) throws IOException {
        dst.clear();
        while (dst.hasRemaining()) {
            int n = channel.read(dst, offset + dst.position());
            if (n <= 0) throw new EOFException("Truncated region file.");
        }
        dst.flip();
    }

    private void region(Path path) throws IOException {
        var match = REGION.matcher(path.getFileName().toString());
        if (!match.matches()) throw new IOException("Unsupported region filename.");
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int originX = Math.multiplyExact(Integer.parseInt(match.group(1)), 32);
            int originZ = Math.multiplyExact(Integer.parseInt(match.group(2)), 32);
            long size = channel.size();
            // A cache read can create an empty region before it has ever written a header.
            if (size == 0) return;
            if (size < 8192 || size > MAX_REGION) throw new IOException("Invalid or oversized region.");
            var locations = ByteBuffer.allocate(4096);
            readAt(channel, 0, locations);
            var used = new BitSet();
            var header = ByteBuffer.allocate(5);
            while (locations.hasRemaining()) {
                int slot = locations.position() / 4, entry = locations.getInt();
                if (entry == 0) continue;
                checkTime();
                int sector = entry >>> 8, count = entry & 255;
                int next = used.nextSetBit(sector);
                if (count == 0 || sector < 2 || sector + count > (size + 4095) / 4096 || next >= 0 && next < sector + count)
                    throw new IOException("Invalid or overlapping region entries.");
                used.set(sector, sector + count);
                long offset = (long) sector * 4096;
                readAt(channel, offset, header);
                int length = header.getInt(), flags = header.get() & 255, compression = flags & 127;
                if (length < 1 || length > count * 4096 - 4 || offset + 4 + length > size || compression < 1 || compression > 4)
                    throw new IOException("Unsupported or truncated chunk record.");
                var codec = RegionFileVersion.fromId(compression);
                if ((flags & 128) != 0) {
                    if (length != 1) throw new IOException("Invalid external chunk reference.");
                    Path external = path.resolveSibling("c." + (originX + (slot & 31)) + "." + (originZ + (slot >> 5)) + ".mcc");
                    var before = Files.readAttributes(external, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!before.isRegularFile() || !referenced.add(external))
                        throw new IOException("Missing or ambiguous external chunk.");
                    chargePacked(before.size());
                    try (var bytes = Files.newInputStream(external, LinkOption.NOFOLLOW_LINKS)) { inspect(codec.wrap(bytes)); }
                    checkUnchanged(external, before);
                } else {
                    chargePacked(length - 1);
                    byte[] bytes = new byte[length - 1];
                    readAt(channel, offset + 5, ByteBuffer.wrap(bytes));
                    inspect(codec.wrap(new ByteArrayInputStream(bytes)));
                }
            }
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IOException("Region coordinates are outside the supported range.", e);
        }
    }

    void inspectReference(InputStream decoded) throws IOException {
        live = false;
        try { inspect(decoded); } finally { live = true; }
    }

    private void inspect(InputStream decoded) throws IOException {
        // Charge skipped arrays as well as visited bytes. This bounds highly compressed chunks.
        try (var in = new DataInputStream(new BufferedInputStream(new FilterInputStream(decoded) {
            int recordBytes;
            @Override public int read(byte[] b, int off, int len) throws IOException {
                checkTime();
                int n = super.read(b, off, len);
                if (n > 0 && ((recordBytes += n) > MAX_RECORD || (inflated += n) > MAX_WORLD_BYTES))
                    throw new IOException("Expanded world data exceeds the recovery check limit.");
                return n;
            }
            @Override public long skip(long n) throws IOException {
                return n <= 0 ? 0 : Math.max(0, read(discard, 0, (int) Math.min(n, discard.length)));
            }
        }))) {
            if (in.readUnsignedByte() != 10) throw new IOException("Unsupported saved NBT root.");
            in.readUTF();
            value(in, 10, 0);
            if (in.read() != -1) throw new IOException("Extra data after a saved NBT record.");
        }
    }

    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_RECORD) throw new IOException("Invalid saved NBT array or list length.");
        return n;
    }

    private static int width(int type) {
        return switch (type) { case 1 -> 1; case 2 -> 2; case 3, 5 -> 4; case 4, 6 -> 8; default -> 0; };
    }

    private void value(DataInputStream in, int type, int depth) throws IOException {
        if ((++tags & 4095) == 0) checkTime();
        if (depth > 256) throw new IOException("Saved inventory nesting exceeds the recovery check limit.");
        switch (type) {
            case 1, 2, 3, 4, 5, 6 -> in.skipNBytes(width(type));
            case 7, 11, 12 -> in.skipNBytes((long) length(in) * (type == 7 ? 1 : type == 11 ? 4 : 8));
            case 8 -> in.skipNBytes(in.readUnsignedShort());
            case 9 -> {
                int member = in.readUnsignedByte(), size = length(in);
                if (member > 12 || member == 0 && size > 0) throw new IOException("Invalid saved NBT list.");
                if (width(member) > 0) in.skipNBytes((long) size * width(member));
                else for (int i = 0; i < size; i++) value(in, member, depth + 1);
            }
            case 10 -> {
                for (int entry; (entry = in.readUnsignedByte()) != 0;) {
                    String key = in.readUTF();
                    if (readingJailSettings && key.equals("integratedCustodyInitialized")) {
                        if (entry != 1) throw new IOException("Unrecognized jail initialization record.");
                        custody.declared |= in.readByte() != 0;
                    } else if (key.equals("BagId")) {
                        if (entry != 11 || in.readInt() != 4) throw new IOException("Unsupported backpack identity format.");
                        long high = in.readLong(), low = in.readLong();
                        if (live && high == bag.getMostSignificantBits() && low == bag.getLeastSignificantBits()) throw new Present();
                    } else value(in, entry, depth + 1);
                }
            }
            default -> throw new IOException("Unknown saved NBT tag.");
        }
    }
}
