package dev.anya.storage;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Cobblemon 1.8.4 GeneralPlayerData. A registered extension controls its own JSON encoding. */
final class CobblemonPlayerData {
    private static final int MAX_FILE = 1 << 20;
    private static final int MAX_TOKENS = 32_768;
    private static final String UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern FILE = Pattern.compile("(" + UUID_TEXT + ")\\.json(?:\\.(?:old|tmp))?");
    private static final Pattern PLAYER_NBT = Pattern.compile("(" + UUID_TEXT + ")\\.nbt\\.(?:old|tmp)");
    private static final Pattern POKEMON_NBT = Pattern.compile("(" + UUID_TEXT + ")\\.dat\\.(?:old|temp)");
    private static final Pattern UUID_VALUE = Pattern.compile(UUID_TEXT);
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern LABEL = Pattern.compile("[a-zA-Z0-9_./:=+ -]+");
    private static final Pattern COUNT = Pattern.compile("0|[1-9][0-9]{0,9}");
    private static final Set<String> TOTALS = Set.of("totalCaptureCount", "totalEggsCollected", "totalEggsHatched",
            "totalEvolvedCount", "totalBattleVictoryCount", "totalPvPBattleVictoryCount", "totalPvWBattleVictoryCount",
            "totalPvNBattleVictoryCount", "totalShinyCaptureCount", "totalAlphaCaptureCount", "totalTradedCount",
            "totalTMLearnedCount");

    static boolean recognizes(Path relative) {
        if (relative.isAbsolute() || relative.getNameCount() != 3 || !relative.getName(0).toString().equals("cobblemonplayerdata"))
            return false;
        return matchesShard(relative, FILE);
    }

    /** Alternate saves that must undergo the same bounded NBT inspection as their primary files. */
    static boolean nbtFallback(Path relative) {
        if (relative.isAbsolute() || relative.getRoot() != null) return false;
        int count = relative.getNameCount();
        if (count != 3 && count != 4) return false;
        String directory = relative.getName(0).toString();
        if (count == 3) {
            return switch (directory) {
                case "pokedex", "tm_moves", "counter" -> matchesShard(relative, PLAYER_NBT);
                default -> false;
            };
        }
        String store = relative.getName(1).toString();
        if (directory.equals("cobblenav") && store.equals("spawndata")) return matchesShard(relative, PLAYER_NBT);
        return directory.equals("pokemon") && (store.equals("pcstore") || store.equals("playerpartystore"))
                && matchesShard(relative, POKEMON_NBT);
    }

    private static boolean matchesShard(Path relative, Pattern format) {
        var name = format.matcher(relative.getFileName().toString());
        String shard = relative.getName(relative.getNameCount() - 2).toString();
        return name.matches() && name.group(1).substring(0, 2).equals(shard);
    }

    static void verify(Path absolute, Path relative, long deadline) throws IOException {
        if (!absolute.isAbsolute() || !recognizes(relative) || !absolute.endsWith(relative)) throw unsupported();
        checkTime(deadline);
        var parent = absolute.getParent();
        for (int i = 0; i < relative.getNameCount(); i++, parent = parent.getParent()) {
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw unsupported();
        }
        var before = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() <= 0 || before.size() > MAX_FILE) throw unsupported();
        ByteBuffer bytes = ByteBuffer.allocate((int) before.size());
        try (var channel = FileChannel.open(absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (bytes.hasRemaining()) {
                checkTime(deadline);
                if (channel.read(bytes) <= 0) throw unsupported();
            }
            if (channel.size() != bytes.capacity()) throw unsupported();
        }
        String json = StandardCharsets.UTF_8.newDecoder().decode(bytes.flip()).toString();
        String owner = relative.getFileName().toString().substring(0, 36);
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            var schema = new Schema(reader, deadline);
            schema.player(owner);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw unsupported();
        } catch (IllegalStateException | NumberFormatException malformed) {
            throw unsupported();
        }
        checkTime(deadline);
        var after = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.size() != before.size()
                || !after.lastModifiedTime().equals(before.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(), after.fileKey())) throw unsupported();
    }

    private static void checkTime(long deadline) throws IOException {
        if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted())
            throw new IOException("Cobblemon player data exceeded the recovery check time limit.");
    }

    private static IOException unsupported() {
        return new IOException("Unrecognized Cobblemon player data; backpack recovery is paused.");
    }

    @FunctionalInterface
    private interface Field { void read(String name) throws IOException; }

    // Only the fixed object/array shapes below can be traversed. No unbounded recursive JSON tree is constructed.
    private static final class Schema {
        private final JsonReader in;
        private final long deadline;
        private int tokens;

        Schema(JsonReader in, long deadline) { this.in = in; this.deadline = deadline; }

        private void count() throws IOException {
            if (++tokens > MAX_TOKENS) throw unsupported();
            if ((tokens & 255) == 0) checkTime(deadline);
        }

        private Set<String> object(Field field) throws IOException {
            in.beginObject();
            Set<String> keys = new HashSet<>();
            while (in.hasNext()) {
                count();
                String key = in.nextName();
                if (key.length() > 256 || !keys.add(key)) throw unsupported();
                field.read(key);
            }
            in.endObject();
            return keys;
        }

        void player(String owner) throws IOException {
            var fields = object(key -> {
                switch (key) {
                    case "uuid" -> { if (!owner.equals(text(UUID_VALUE))) throw unsupported(); }
                    case "starterUUID" -> text(UUID_VALUE);
                    case "starterPrompted", "starterLocked", "starterSelected", "partySelectTutorialDone" -> in.nextBoolean();
                    case "battleTheme" -> text(RESOURCE);
                    case "keyItems" -> strings(RESOURCE);
                    case "advancementData" -> object(this::advancement);
                    case "extraData" -> {
                        in.beginObject();
                        if (in.hasNext()) throw new IOException("An unverified Cobblemon player-data extension prevents backpack recovery.");
                        in.endObject();
                    }
                    default -> throw unsupported();
                }
            });
            if (!fields.containsAll(Set.of("uuid", "keyItems", "extraData", "starterPrompted", "starterLocked",
                    "starterSelected", "partySelectTutorialDone"))) throw unsupported();
        }

        private void advancement(String key) throws IOException {
            if (TOTALS.contains(key)) { number(); return; }
            switch (key) {
                case "totalTypeCaptureCounts" -> object(name -> { label(name, LABEL); number(); });
                case "totalDefeatedCounts" -> object(name -> { label(name, RESOURCE); number(); });
                case "aspectsCollected" -> object(name -> { label(name, RESOURCE); strings(LABEL); });
                default -> throw unsupported();
            }
        }

        private void number() throws IOException {
            if (in.peek() != JsonToken.NUMBER) throw unsupported();
            String value = in.nextString();
            if (value.length() > 10 || !COUNT.matcher(value).matches() || Long.parseLong(value) > Integer.MAX_VALUE)
                throw unsupported();
        }

        private static void label(String value, Pattern format) throws IOException {
            if (value.length() > 256 || !format.matcher(value).matches()) throw unsupported();
        }

        private String text(Pattern pattern) throws IOException {
            if (in.peek() != JsonToken.STRING) throw unsupported();
            String s = in.nextString();
            label(s, pattern);
            return s;
        }

        private void strings(Pattern pattern) throws IOException {
            in.beginArray();
            while (in.hasNext()) { count(); text(pattern); }
            in.endArray();
        }
    }

    private CobblemonPlayerData() {}
}
