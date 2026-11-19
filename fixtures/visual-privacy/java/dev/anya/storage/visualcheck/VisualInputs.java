package dev.anya.storage.visualcheck;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.ModList;

/** Attests to the files selected by the loader in this JVM before recording client or server observations. */
final class VisualInputs {
    static void verify(String role) throws Exception {
        if (Runtime.version().feature() != 21 || !"true".equals(System.getenv("GITHUB_ACTIONS"))) {
            throw new IllegalStateException("Expected a disposable Java 21 GitHub runner");
        }
        Path control = Path.of(System.getProperty("anya.control")).toAbsolutePath().normalize();
        Path manifest = Path.of(System.getProperty("anya.runtimeManifest")).toAbsolutePath().normalize();
        if (!manifest.equals(control.resolve("inputs.json")) || !Files.isRegularFile(control.resolve(".disposable-visual-privacy"))) {
            throw new IllegalStateException("Separate visual test inputs required");
        }
        JsonObject expected = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        boolean client = !role.equals("server");
        var requirements = expected.getAsJsonObject(client ? "client" : "server");
        Set<String> allowed = new HashSet<>();
        expected.getAsJsonArray("allowlist").forEach(entry -> allowed.add(entry.getAsString()));
        var inventory = new JsonObject();
        for (var mod : ModList.get().getMods()) {
            String id = mod.getModId();
            if (!allowed.contains(id)) throw new IllegalStateException("Unexpected active module " + id);
            var source = mod.getOwningFile().getFile().getFilePath();
            var row = new JsonObject();
            row.addProperty("version", mod.getVersion().toString());
            row.addProperty("file", source.toString());
            if (requirements.has(id)) {
                var rule = requirements.getAsJsonObject(id);
                if (rule.has("version") && !mod.getVersion().toString().equals(rule.get("version").getAsString())) {
                    throw new IllegalStateException("Unexpected version for " + id + ": " + mod.getVersion());
                }
                if (rule.has("sha256")) {
                    String digest = sha(Files.readAllBytes(source));
                    if (!digest.equals(rule.get("sha256").getAsString())) throw new IllegalStateException("Active archive differs for " + id);
                    row.addProperty("sha256", digest);
                }
            }
            inventory.add(id, row);
        }
        if (!inventory.keySet().containsAll(requirements.keySet()) || client && !inventory.keySet().equals(allowed)) {
            throw new IllegalStateException("Unexpected loader result for " + role + ": " + inventory.keySet());
        }
        if (!client && (inventory.has("sodium") || inventory.has("iris"))) throw new IllegalStateException("Renderer loaded on server");
        if (client) {
            // The selected Sodium implementation lives inside the verified wrapper. Its selected
            // class resource is readable even when the loader presents a virtual nested filesystem.
            var sodium = ModList.get().getModFileById("sodium").getFile();
            for (var entry : expected.getAsJsonObject("sodium_class_resources").entrySet()) {
                Path resource = sodium.findResource(entry.getKey().split("/"));
                if (!sha(Files.readAllBytes(resource)).equals(entry.getValue().getAsString())) {
                    throw new IllegalStateException("Nested Sodium class resource differs: " + entry.getKey());
                }
            }
        }
        for (String flag : List.of("anyastorage.pocketPrototype", "anya.nonblockingPrepare", "anya.minimalOwnership",
                "anya.serializationBudget", "anya.backpressure", "anya.repairJournal")) {
            if (!Boolean.getBoolean(flag)) throw new IllegalStateException("Missing runtime flag " + flag);
        }
        Files.writeString(control.resolve("runtime-mods-" + role + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(inventory) + "\n", StandardOpenOption.CREATE_NEW);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private VisualInputs() {}
}
