package dev.anya.storage;

import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/** Raid Dens rebuilds this generation from data-pack boss definitions. Its live index must cover every byte. */
final class RaidDefinitionCache {
    private static final String PKG = "com.necro.raid.dens.common.";

    static void verify(Path path, long size, long deadline) throws IOException {
        var mod = ModList.get().getModContainerById("cobblemonraiddens");
        require(mod.isPresent() && mod.get().getModInfo().getVersion().toString().equals("0.14.2+1.21.1")
                && size > 0 && size <= (16 << 20));
        try {
            Object registry = Class.forName(PKG + "registry.CustomRaidRegistries").getField("RAID_REGISTRY").get(null);
            Object store = field(registry, "store");
            Object state = field(store, "state");
            require(field(state, "path") instanceof Path active && active.toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize()));
            require(field(state, "channel") instanceof FileChannel channel && channel.isOpen() && channel.size() == size);
            if (!(field(state, "index") instanceof Map<?, ?> index) || index.isEmpty() || index.size() > 16_384) throw invalid();
            Object listed = registry.getClass().getMethod("getAll").invoke(registry);
            require(listed instanceof List<?> ids && ids.size() == index.size() && index.keySet().equals(new HashSet<>(ids)));
            var entryType = Class.forName(PKG + "registry.store.BinaryRecordStore$Entry");
            var offset = entryType.getMethod("offset");
            var length = entryType.getMethod("length");
            var ordered = new TreeMap<Long,Integer>();
            for (var row : index.entrySet()) {
                require(row.getKey() instanceof ResourceLocation && entryType.isInstance(row.getValue()));
                long start = (Long)offset.invoke(row.getValue());
                int count = (Integer)length.invoke(row.getValue());
                require(start >= 0 && count > 0 && count <= (1 << 20) && start <= size-count
                        && ordered.put(start, count) == null);
            }
            var keys = new HashSet<String>();
            var type = Class.forName(PKG + "data.raid.RaidBoss");
            for (var member : type.getDeclaredFields()) {
                if (Modifier.isStatic(member.getModifiers()) || Modifier.isTransient(member.getModifiers()) || member.isSynthetic()) continue;
                var alias = member.getAnnotation(SerializedName.class);
                keys.add(alias == null ? member.getName() : alias.value());
            }
            require(keys.containsAll(List.of("pokemon","boss","raid_tier","adventure")));
            try (var file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                for (var row : ordered.entrySet()) {
                    time(deadline);
                    require(file.position() == row.getKey());
                    var bytes = ByteBuffer.allocate(row.getValue());
                    while (bytes.hasRemaining()) require(file.read(bytes) > 0);
                    String text = StandardCharsets.UTF_8.newDecoder().decode(bytes.flip()).toString();
                    try (var json = new JsonReader(new StringReader(text))) {
                        json.setLenient(false);
                        json.beginObject();
                        var seen = new HashSet<String>();
                        while (json.hasNext()) {
                            String key = json.nextName();
                            require(keys.contains(key) && seen.add(key));
                            value(json, 0);
                        }
                        json.endObject();
                        require((seen.contains("pokemon") || seen.contains("boss") || seen.contains("adventure"))
                                && json.peek() == JsonToken.END_DOCUMENT);
                    }
                }
                require(file.position() == size && file.size() == size);
            }
            require(state == field(store,"state"));
            time(deadline);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException problem) {
            throw new IOException("The active raid definition cache could not be verified. Recovery is paused.", problem);
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException, IOException {
        if (owner == null) throw invalid();
        var field = owner.getClass().getDeclaredField(name);
        if (!field.trySetAccessible()) throw invalid();
        return field.get(owner);
    }

    private static void value(JsonReader json,int depth) throws IOException {
        require(depth < 40);
        switch (json.peek()) {
            case BEGIN_OBJECT -> {
                var names = new HashSet<String>();
                json.beginObject();
                while (json.hasNext()) {
                    String key = json.nextName();
                    require(key.length() <= 256 && !key.equalsIgnoreCase("BagId") && names.add(key));
                    value(json,depth+1);
                }
                json.endObject();
            }
            case BEGIN_ARRAY -> {
                json.beginArray();
                while (json.hasNext()) value(json,depth+1);
                json.endArray();
            }
            case STRING -> {
                String string = json.nextString();
                require(string.length() <= 16_384 && !string.contains("BagId"));
            }
            case NUMBER, BOOLEAN, NULL -> json.skipValue();
            default -> throw invalid();
        }
    }

    private static void time(long deadline) throws IOException {
        require(System.nanoTime() - deadline < 0 && !Thread.currentThread().isInterrupted());
    }
    private static void require(boolean ok) throws IOException { if (!ok) throw invalid(); }
    private static IOException invalid() { return new IOException("Unrecognized raid definition cache. Recovery is paused."); }
    private RaidDefinitionCache() {}
}
