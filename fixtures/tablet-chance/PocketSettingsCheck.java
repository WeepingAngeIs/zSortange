package dev.anya.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.level.ServerPlayer;

/** Exercises the packaged settings API with real config files and a recording server boundary. */
public final class PocketSettingsCheck {
    private static final String STORAGE = "expansion.pocketStorage";
    private static final String EDITING = "expansion.pocketEditing";

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void unchanged(Path config, String previous, long revision) throws Exception {
        require(Files.readString(config).equals(previous), "Rejected change rewrote the config file");
        require(Config.get().revision == revision, "Rejected change advanced the live revision");
    }

    private static void assertPolicy(ServerPlayer actor, boolean storage, boolean editing) {
        Config.SettingsSnapshot snapshot = Config.serverSettings(actor);
        require(snapshot != null && snapshot.revision() == Config.get().revision, "Settings snapshot lost its revision");
        require(Boolean.valueOf(storage).equals(snapshot.values().get(STORAGE))
                && Boolean.valueOf(editing).equals(snapshot.values().get(EDITING)), "Wrong pocket settings snapshot");
        require(Config.pocketStorageEnabled() == storage && Config.get().expansion.pocketEditing == editing,
                "Wrong active pocket policy");
    }

    public static void main(String[] args) throws Exception {
        require(System.getProperty("anyastorage.pocketPrototype") == null, "Test must launch without the old JVM option");
        Path config = Path.of(args[0], "anya-storage-plus.json");
        String original = "{\"schema\":2,\"revision\":7,\"audit\":false,\"quickRadius\":13,\"expansion\":{\"pocketLimit\":28}}";
        Files.writeString(config, original);
        Config.init();

        var actor = new ServerPlayer();
        var server = actor.getServer();
        require(Config.serverSettings(null) == null && Config.serverSettings(actor) == null, "Settings exposed without an operator");
        require(!Config.setServerSetting(actor, 7, STORAGE, "true"), "Nonoperator enabled pockets");
        actor.operator = true;
        server.serverThread = false;
        require(Config.serverSettings(actor) == null && !Config.setServerSetting(actor, 7, STORAGE, "true"),
                "Off-thread caller accessed settings");
        server.serverThread = true;
        assertPolicy(actor, false, false);
        var originalSnapshot = Config.serverSettings(actor);
        try {
            originalSnapshot.values().put(STORAGE, true);
            throw new AssertionError("Settings snapshot allowed a caller to change its values");
        } catch (UnsupportedOperationException expected) {}

        require(!Config.setServerSetting(actor, 6, STORAGE, "true"), "Stale writer enabled pockets");
        require(!Config.setServerSetting(actor, 7, "schema", "1"), "Setter changed a non-setting field");
        require(!Config.setServerSetting(actor, 7, STORAGE, "maybe"), "Setter accepted invalid Boolean text");
        require(!Config.setServerSetting(actor, 7, EDITING, "true"), "Room editing enabled without pocket storage");
        require(Config.setServerSetting(actor, 7, STORAGE, "false"), "Unchanged setting was refused");
        unchanged(config, original, 7);

        server.pocketDimensionLoaded = false;
        int transitions = server.pocketTransitions;
        require(!Config.setServerSetting(actor, 7, STORAGE, "true"), "Enabled pockets without a loaded dimension");
        require(server.pocketTransitions > transitions, "Setter skipped the enable safety check");
        unchanged(config, original, 7);
        assertPolicy(actor, false, false);

        server.pocketDimensionLoaded = true;
        require(Config.setServerSetting(actor, 7, STORAGE, "true"), "Operator could not enable pockets without a JVM flag");
        assertPolicy(actor, true, false);
        require(Config.get().revision == 8 && Config.get().quickRadius == 13 && Config.get().expansion.pocketLimit == 28,
                "Enabling a setting changed unrelated policy");
        require(originalSnapshot.revision() == 7 && Boolean.FALSE.equals(originalSnapshot.values().get(STORAGE)),
                "Previously returned policy changed after a save");
        require(Config.setServerSetting(actor, 8, EDITING, "true"), "Operator could not enable editing after storage");
        assertPolicy(actor, true, true);

        String editable = Files.readString(config);
        server.pocketPreparing = true;
        transitions = server.pocketTransitions;
        require(!Config.setServerSetting(actor, 9, STORAGE, "false"), "Disable interrupted an active preparation");
        require(server.pocketTransitions > transitions, "Setter skipped the disable safety check");
        require(server.pocketDisables == 0, "Rejected disable called active lease cleanup");
        unchanged(config, editable, 9);
        assertPolicy(actor, true, true);

        server.pocketPreparing = false;
        require(Config.setServerSetting(actor, 9, STORAGE, "false"), "Operator could not disable idle pocket storage");
        assertPolicy(actor, false, false);
        require(server.pocketDisables == 1, "Committed disable did not immediately call lease cleanup");
        require(Config.get().revision == 10 && server.reloads == 0, "Pocket-only settings edited twice or reloaded recipes");
        Config.init();
        assertPolicy(actor, false, false);
        require(Config.get().revision == 10, "Disabling storage and editing was not saved atomically");

        server.deferReload = true;
        var mixed = Config.copy(Config.get());
        mixed.quickRadius = 12;
        mixed.expansion.pocketStorage = true;
        require(Config.save(actor, mixed) && server.reloads == 1 && server.pendingReload != null,
                "Mixed settings did not use the resource reload path");
        require(!Config.pocketStorageEnabled(), "Mixed edit activated pocket storage before reload succeeded");
        require(!Config.setServerSetting(actor, Config.get().revision, EDITING, "true"), "An API write bypassed the active reload");
        server.deferReload = false;
        server.pendingReload.completeExceptionally(new IllegalStateException("Synthetic resource reload failure"));
        assertPolicy(actor, false, false);
        require(Config.get().quickRadius == 13 && !Config.read(config).expansion.pocketStorage,
                "Reload failure did not restore the old config");

        server.deferReload = true;
        mixed = Config.copy(Config.get());
        mixed.quickRadius = 12;
        mixed.expansion.pocketStorage = true;
        require(Config.save(actor, mixed) && !Config.pocketStorageEnabled(), "Second mixed enable became active early");
        server.deferReload = false;
        server.pendingReload.complete(null);
        assertPolicy(actor, true, false);
        Config.init();
        assertPolicy(actor, true, false);
        require(Config.get().quickRadius == 12, "Mixed policy was not restored on restart");

        System.out.println("PASS: operator settings API, startup without a JVM flag, safe pocket transition calls, atomic editor disable, and mixed resource reload success/rollback");
    }
}
