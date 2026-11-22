package dev.anya.storage;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

public final class StorageSettingsCommands {
    private static final String POCKET_STORAGE = "expansion.pocketStorage";

    static LiteralArgumentBuilder<CommandSourceStack> settings() {
        return Commands.literal("settings").requires(source -> source.hasPermission(2))
                .executes(context -> {
                    StoreMenu.settings(context.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("list").executes(context -> {
                    var actor = context.getSource().getPlayerOrException();
                    AnyaStorage.tell(actor, String.join(", ", ServerSettings.keys()));
                    return 1;
                }))
                .then(Commands.literal("get").then(settingKey().executes(context ->
                        show(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("set").then(settingKey().then(
                        Commands.argument("value", StringArgumentType.greedyString()).executes(context -> {
                            var actor = context.getSource().getPlayerOrException();
                            String key = StringArgumentType.getString(context, "key");
                            String value = StringArgumentType.getString(context, "value").strip();
                            return apply(actor, key, value) ? show(actor, key) : 0;
                        }))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> settingKey() {
        return Commands.argument("key", StringArgumentType.word()).suggests((context, builder) ->
                SharedSuggestionProvider.suggest(ServerSettings.keys(), builder));
    }

    /** Attach the operator subcommands without restricting the existing visit and exit commands. */
    public static LiteralArgumentBuilder<CommandSourceStack> pocketCommands() {
        return Commands.literal("pocket")
                .then(pocketToggle("enable", true))
                .then(pocketToggle("disable", false))
                .then(Commands.literal("status").requires(source -> source.hasPermission(2))
                        .executes(context -> showPocketStatus(context.getSource().getPlayerOrException())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pocketToggle(String name, boolean enabled) {
        return Commands.literal(name).requires(source -> source.hasPermission(2)).executes(context -> {
            var actor = context.getSource().getPlayerOrException();
            return apply(actor, POCKET_STORAGE, Boolean.toString(enabled)) ? showPocketStatus(actor) : 0;
        });
    }

    private static boolean apply(ServerPlayer actor, String key, String value) {
        var snapshot = Config.serverSettings(actor);
        return snapshot != null && Config.setServerSetting(actor, snapshot.revision(), key, value);
    }

    private static int show(ServerPlayer actor, String key) {
        var snapshot = Config.serverSettings(actor);
        if (snapshot == null) return 0;
        Object value = snapshot.values().get(key);
        if (value == null) {
            AnyaStorage.tell(actor, "Unknown setting. Run /anya storage settings list.");
            return 0;
        }
        AnyaStorage.tell(actor, key + " = " + value);
        return 1;
    }

    private static int showPocketStatus(ServerPlayer actor) {
        var snapshot = Config.serverSettings(actor);
        if (snapshot == null) return 0;
        if (Boolean.TRUE.equals(snapshot.values().get(POCKET_STORAGE))) {
            if (PocketService.dimension(actor.getServer()) == null) {
                AnyaStorage.tell(actor, "Pocket storage is enabled in settings. The pocket dimension is unavailable.");
                return 1;
            }
            if (!PocketService.enabled()) {
                AnyaStorage.tell(actor, "Pocket storage is waiting for the settings reload.");
                return 1;
            }
            String editing = Boolean.TRUE.equals(snapshot.values().get("expansion.pocketEditing")) ? "on" : "off";
            AnyaStorage.tell(actor, "Pocket storage is on. Room editing is " + editing + ".");
        } else {
            AnyaStorage.tell(actor, "Pocket storage is off. Room editing is off.");
        }
        return 1;
    }

    private StorageSettingsCommands() {}
}
