package dev.anya.storage;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** The non-inventory files written by the published pack. Unrecognized fields remain inconclusive. */
final class PackWorldMetadata {
    enum Kind { SERVER_ID, MAP_ID, REDSTONE, TRANSLATION, BALANCE, RANKS, RAID_DEFINITIONS }

    private static final Path SERVER_ID = Path.of("data/mezz_config/server-id.txt");
    private static final Path TRANSLATION = Path.of("data/collective/itemnametranslation.txt");
    private static final Path RANKS = Path.of("curanks/players.json");
    private static final Path BALANCES = Path.of("cobbledollarsplayerdata");
    private static final Path RAIDS = Path.of("cobblemonraiddens");
    private static final Pattern GENERATION = Pattern.compile("bosses\\.bin\\.[1-9][0-9]{0,9}");
    private static final Pattern DECIMAL = Pattern.compile("-?(?:0|[1-9][0-9]{0,127})");
    private static final Set<String> ORDERS = Set.of("horizontal_first_outward", "horizontal_first_inward",
            "vertical_first_outward", "vertical_first_inward");

    static Kind kind(Path path) {
        if (path.equals(SERVER_ID)) return Kind.SERVER_ID;
        if (path.equals(TRANSLATION)) return Kind.TRANSLATION;
        if (path.equals(Path.of("xaeromap.txt"))) return Kind.MAP_ID;
        if (path.equals(Path.of("alternate-current.conf"))) return Kind.REDSTONE;
        if (path.equals(RANKS)) return Kind.RANKS;
        String name = path.getFileName().toString();
        if (BALANCES.equals(path.getParent()) && name.endsWith(".json") && uuid(name.substring(0,name.length()-5)))
            return Kind.BALANCE;
        if (RAIDS.equals(path.getParent()) && GENERATION.matcher(name).matches()) return Kind.RAID_DEFINITIONS;
        return null;
    }

    static void verify(Kind kind, Path path, long size, long deadline) throws IOException {
        if (kind == Kind.RAID_DEFINITIONS) {
            RaidDefinitionCache.verify(path, size, deadline);
            return;
        }
        int limit = kind == Kind.RANKS ? 2 << 20 : kind == Kind.BALANCE ? 16 << 10 : 4096;
        require(size > 0 && size <= limit);
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes((int)size + 1);
            require(bytes.length == size);
        }
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().strip();
        try {
            switch (kind) {
                case SERVER_ID -> require(uuid(text));
                case MAP_ID -> require(text.startsWith("id:") && text.substring(3).equals(Integer.toString(Integer.parseInt(text.substring(3)))));
                case TRANSLATION -> require(text.equals("literal") || text.equals("translatable"));
                case REDSTONE -> redstone(text);
                case BALANCE, RANKS -> {
                    try (var json = new JsonReader(new StringReader(text))) {
                        json.setLenient(false);
                        if (kind == Kind.BALANCE) balance(json);
                        else ranks(json, deadline);
                        require(json.peek() == JsonToken.END_DOCUMENT);
                    }
                }
                default -> throw invalid();
            }
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid " + path.getFileName() + " metadata. Recovery is paused.", malformed);
        }
    }

    private static void redstone(String text) throws IOException {
        Set<String> keys = new HashSet<>();
        for (String line : text.lines().toList()) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] option = line.split("=", -1);
            require(option.length == 2 && keys.add(option[0]));
            switch (option[0]) {
                case "enabled" -> require(option[1].equals("true") || option[1].equals("false"));
                case "update-order" -> require(ORDERS.contains(option[1]));
                default -> throw invalid();
            }
        }
        require(keys.size() == 2);
    }

    private static void balance(JsonReader json) throws IOException {
        Set<String> keys = new HashSet<>();
        json.beginObject();
        while (json.hasNext()) {
            String name = json.nextName();
            require(keys.add(name));
            switch (name) {
                case "name" -> label(json);
                case "cobbledollars", "offline_earnings" -> {
                    require(json.peek() == JsonToken.NUMBER && DECIMAL.matcher(json.nextString()).matches());
                }
                default -> throw invalid();
            }
        }
        json.endObject();
        require(keys.contains("cobbledollars") || keys.contains("offline_earnings"));
    }

    private static void ranks(JsonReader json, long deadline) throws IOException {
        var ids = new HashSet<String>();
        json.beginObject();
        while (json.hasNext()) {
            String id = json.nextName();
            require(uuid(id) && ids.add(id) && ids.size() <= 8192 && System.nanoTime() - deadline < 0);
            Set<String> keys = new HashSet<>();
            json.beginObject();
            while (json.hasNext()) {
                String key = json.nextName();
                require(keys.add(key));
                switch (key) {
                    case "name", "gymType" -> label(json);
                    case "ranks" -> {
                        json.beginArray();
                        int grants = 0;
                        while (json.hasNext()) {
                            require(++grants <= 2048);
                            Set<String> fields = new HashSet<>();
                            json.beginObject();
                            while (json.hasNext()) {
                                String field = json.nextName();
                                require(fields.add(field));
                                if (field.equals("rank")) label(json);
                                else if (field.equals("expiresAt")) timestamp(json);
                                else throw invalid();
                            }
                            json.endObject();
                            require(fields.contains("rank"));
                        }
                        json.endArray();
                    }
                    case "claims" -> {
                        json.beginObject();
                        Set<String> rewards = new HashSet<>();
                        while (json.hasNext()) {
                            String name = json.nextName();
                            require(name.length() <= 256 && rewards.add(name) && rewards.size() <= 4096);
                            timestamp(json);
                        }
                        json.endObject();
                    }
                    default -> throw invalid();
                }
            }
            json.endObject();
            require(keys.contains("name"));
        }
        json.endObject();
    }

    private static void label(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.STRING && reader.nextString().length() <= 256);
    }

    private static void timestamp(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.NUMBER);
        String value = reader.nextString();
        require(value.equals(Long.toString(Long.parseLong(value))));
    }

    private static boolean uuid(String value) {
        if (value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException malformed) { return false; }
    }

    private static void require(boolean value) throws IOException { if (!value) throw invalid(); }
    private static IOException invalid() { return new IOException("Unrecognized pack metadata. Recovery is paused."); }
    private PackWorldMetadata() {}
}
