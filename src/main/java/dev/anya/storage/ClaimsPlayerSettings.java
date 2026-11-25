package dev.anya.storage;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/** Reads OPAC's per-player settings using its installed option names and types, without loading or correcting the file. */
final class ClaimsPlayerSettings {
    private static final Path DIRECTORY = Path.of("data", "openpartiesandclaims", "player-configs");
    private static final List<String> VERSION = List.of("playerConfig", "version");
    private static final int MAX_BYTES = 256 * 1024;
    private final Map<List<String>, Class<?>> options = new HashMap<>();
    private final Set<List<String>> tables = new HashSet<>();

    static boolean recognizes(Path relative) {
        if (!DIRECTORY.equals(relative.getParent())) return false;
        String name = relative.getFileName().toString();
        if (name.length() != 41 || !name.endsWith(".toml")) return false;
        String id = name.substring(0, 36);
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    ClaimsPlayerSettings(MinecraftServer server) throws IOException {
        var mod = ModList.get().getModContainerById("openpartiesandclaims");
        if (mod.isEmpty() || !"0.33.0".equals(mod.get().getModInfo().getVersion().toString())) throw invalid();
        try {
            var api = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            var manager = api.getMethod("getPlayerConfigManager").invoke(api.getMethod("get", MinecraftServer.class).invoke(null, server));
            var spec = Class.forName("xaero.pac.common.server.player.config.api.v2.IPlayerConfigOptionSpecAPI");
            var getPath = spec.getMethod("getPath");
            var getType = spec.getMethod("getType");
            var getOptions = api.getMethod("getPlayerConfigManager").getReturnType().getMethod("getAllOptionsStream");
            try (Stream<?> stream = (Stream<?>) getOptions.invoke(manager)) {
                var iterator = stream.iterator();
                while (iterator.hasNext()) {
                    Object option = iterator.next();
                    if (options.size() >= 4096 || !(getPath.invoke(option) instanceof List<?> parts)
                            || parts.size() < 2 || parts.size() > 20) throw invalid();
                    List<String> path = new ArrayList<>(parts.size());
                    for (Object part : parts) {
                        if (!(part instanceof String s) || s.isEmpty() || s.length() > 256) throw invalid();
                        path.add(s);
                    }
                    if (!path.getFirst().equals("playerConfig") || !(getType.invoke(option) instanceof Class<?> type)) throw invalid();
                    options.put(List.copyOf(path), type);
                    for (int i = 1; i < path.size(); i++) tables.add(List.copyOf(path.subList(0, i)));
                }
            }
            if (options.isEmpty()) throw invalid();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            throw new IOException("The installed claim settings schema could not be read. Recovery is paused.", error);
        }
    }

    void verify(Path path, long size) throws IOException {
        if (size < 1 || size > MAX_BYTES) throw invalid();
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes((int) size + 1);
            if (bytes.length != size) throw invalid();
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            var config = new TomlParser().parse(text);
            if (!Integer.valueOf(18).equals(config.get(VERSION))) throw invalid();
            verifyTable(config, List.of());
        } catch (RuntimeException | StackOverflowError error) {
            throw new IOException("Unreadable claim settings. Recovery is paused.", error);
        }
    }

    private void verifyTable(UnmodifiableConfig table, List<String> parent) throws IOException {
        for (var entry : table.entrySet()) {
            var path = new ArrayList<>(parent);
            path.add(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig nested) {
                if (!tables.contains(path)) throw invalid();
                verifyTable(nested, path);
            } else if (!path.equals(VERSION)) {
                Class<?> type = options.get(path);
                if (type == null || !type.isInstance(value)) throw invalid();
                if (value instanceof List<?> members) {
                    if (members.size() > 4096) throw invalid();
                    for (Object member : members) if (!(member instanceof String s) || s.length() > 4096) throw invalid();
                } else if (!(value instanceof Boolean || value instanceof Integer || value instanceof String s && s.length() <= 4096)) {
                    throw invalid();
                }
            }
        }
    }

    private static IOException invalid() {
        return new IOException("Unrecognized Open Parties and Claims player settings. Recovery is paused.");
    }
}
