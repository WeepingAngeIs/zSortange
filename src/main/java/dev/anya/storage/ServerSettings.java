package dev.anya.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/** The server settings editable by command or an optional pack settings screen. */
public final class ServerSettings {
    public enum Kind { BOOLEAN, INTEGER, DECIMAL, REGISTRY_ID }

    /** Values and numeric bounds use the Java type represented by the kind. */
    public record Definition(String key, Kind kind, String label, String description,
                             Object defaultValue, Number minimum, Number maximum) {}

    private record Entry<T>(Definition definition, Function<Config.Data, T> read,
                            BiConsumer<Config.Data, T> write, Function<String, T> parse) {
        void update(Config.Data draft, String text) {
            write.accept(draft, parse.apply(text));
        }
    }

    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static final Config.Data DEFAULTS = new Config.Data();
    private static final Map<String, Entry<?>> ENTRIES = entries();
    private static final List<String> KEYS = List.copyOf(ENTRIES.keySet());

    private ServerSettings() {}

    public static List<String> keys() {
        return KEYS;
    }

    public static Definition definition(String key) {
        Entry<?> entry = key == null ? null : ENTRIES.get(key);
        return entry == null ? null : entry.definition();
    }

    static Map<String, Object> values(Config.Data data) {
        Map<String, Object> result = new LinkedHashMap<>();
        ENTRIES.forEach((key, entry) -> result.put(key, entry.read().apply(data)));
        return Collections.unmodifiableMap(result);
    }

    static void update(Config.Data data, String key, String value) {
        Entry<?> entry = key == null ? null : ENTRIES.get(key);
        if (entry == null) throw new IllegalArgumentException("Unknown server setting " + key);
        entry.update(data, value);
    }

    private static Map<String, Entry<?>> entries() {
        Map<String, Entry<?>> entries = new TreeMap<>();

        flag(entries, "compatibility", "Optional tier compatibility", "Allow installed third-party tier materials.",
                d -> d.compatibility, (d, v) -> d.compatibility = v);
        flag(entries, "fallbackMissingTiers", "Tier fallback", "Skip unavailable predecessor tiers when allowed.",
                d -> d.fallbackMissingTiers, (d, v) -> d.fallbackMissingTiers = v);
        flag(entries, "backpacks", "Backpacks", "Enable backpack features.",
                d -> d.backpacks, (d, v) -> d.backpacks = v);
        flag(entries, "network", "Storage network", "Enable hubs and bound storage terminals.",
                d -> d.network, (d, v) -> d.network = v);
        flag(entries, "quickStack", "QuickStack", "Enable nearby QuickStack and Dump operations.",
                d -> d.quickStack, (d, v) -> d.quickStack = v);
        flag(entries, "organize", "Organize", "Enable applying organization plans.",
                d -> d.organize, (d, v) -> d.organize = v);
        flag(entries, "labels", "Storage labels", "Enable storage labels.",
                d -> d.labels, (d, v) -> d.labels = v);
        flag(entries, "audit", "Audit logging", "Record storage audit events in the server log.",
                d -> d.audit, (d, v) -> d.audit = v);
        flag(entries, "vanillaChests", "Vanilla chests", "Allow supported vanilla chests in storage scans.",
                d -> d.vanillaChests, (d, v) -> d.vanillaChests = v);
        flag(entries, "vanillaBarrels", "Vanilla barrels", "Allow supported vanilla barrels in storage scans.",
                d -> d.vanillaBarrels, (d, v) -> d.vanillaBarrels = v);
        flag(entries, "vanillaShulkers", "Vanilla shulker boxes", "Allow placed vanilla shulker boxes in storage scans.",
                d -> d.vanillaShulkers, (d, v) -> d.vanillaShulkers = v);

        integer(entries, "initialRadius", "Initial hub radius", "Blocks. Must be at most the maximum hub radius.", 4, 128,
                d -> d.initialRadius, (d, v) -> d.initialRadius = v);
        integer(entries, "maxRadius", "Maximum hub radius", "Blocks. Must be at least the initial hub radius.", 4, 128,
                d -> d.maxRadius, (d, v) -> d.maxRadius = v);
        integer(entries, "radiusStep", "Hub radius upgrade step", "Blocks added by each radius upgrade.", 1, 32,
                d -> d.radiusStep, (d, v) -> d.radiusStep = v);
        integer(entries, "quickRadius", "QuickStack radius", "Blocks searched by nearby QuickStack.", 1, 16,
                d -> d.quickRadius, (d, v) -> d.quickRadius = v);
        integer(entries, "scanChunksPerTick", "Scan budget", "Maximum number of scanned chunks per tick.", 1, 8,
                d -> d.scanChunksPerTick, (d, v) -> d.scanChunksPerTick = v);
        integer(entries, "maxContainers", "Network container limit", "Maximum containers included in a network scan.", 1, 512,
                d -> d.maxContainers, (d, v) -> d.maxContainers = v);
        integer(entries, "maxScanEntries", "Scan entry budget", "Maximum indexed entries in one network scan.", 32, 2048,
                d -> d.maxScanEntries, (d, v) -> d.maxScanEntries = v);
        integer(entries, "maxOperationSlots", "Operation slot budget", "Maximum slots checked for an operation.", 54, 32768,
                d -> d.maxOperationSlots, (d, v) -> d.maxOperationSlots = v);
        integer(entries, "upgradeBaseCost", "Base radius upgrade cost", "Base ore points for a radius upgrade.", 1, 100000,
                d -> d.upgradeBaseCost, (d, v) -> d.upgradeBaseCost = v);
        integer(entries, "cubeRecipe", "UwU Storage Cube recipe",
                "0 disables crafting, 1 uses Dragon's Breath, and 2 uses Dragon Victory Tokens. Also requires recipes.endgame.",
                0, 2, d -> d.cubeRecipe, (d, v) -> d.cubeRecipe = v);

        for (String group : Config.GROUPS) {
            flag(entries, "recipes." + group, "Recipe group " + group, "Enable recipes in this group.",
                    d -> d.recipes.get(group), (d, v) -> d.recipes.put(group, v));
        }
        for (String id : Config.UPGRADE_FAMILIES) {
            flag(entries, "upgrades." + id, "Backpack upgrade " + id, "Enable behavior and crafting for this upgrade.",
                    d -> d.upgrades.get(id), (d, v) -> d.upgrades.put(id, v));
        }

        flag(entries, "expansion.tutorialGrant", "Tutorial grant", "Offer the guide to eligible players.",
                d -> d.expansion.tutorialGrant, (d, v) -> d.expansion.tutorialGrant = v);
        flag(entries, "expansion.tutorialRecovery", "Tutorial recovery", "Enable guide recovery.",
                d -> d.expansion.tutorialRecovery, (d, v) -> d.expansion.tutorialRecovery = v);
        flag(entries, "expansion.activityLogging", "Base activity logging", "Record base storage activity.",
                d -> d.expansion.activityLogging, (d, v) -> d.expansion.activityLogging = v);
        flag(entries, "expansion.activityActorIds", "Base activity actor IDs", "Include actor UUIDs in base activity records.",
                d -> d.expansion.activityActorIds, (d, v) -> d.expansion.activityActorIds = v);
        flag(entries, "expansion.activityMemberLogs", "Member access to base logs", "Allow permitted base members to view activity.",
                d -> d.expansion.activityMemberLogs, (d, v) -> d.expansion.activityMemberLogs = v);
        integer(entries, "expansion.activityLogEntries", "Base activity log size", "Maximum retained entries per base.", 1, 128,
                d -> d.expansion.activityLogEntries, (d, v) -> d.expansion.activityLogEntries = v);

        flag(entries, "expansion.elementalEffects", "Elemental backpack effects", "Enable gameplay effects for worn backpacks.",
                d -> d.expansion.elementalEffects, (d, v) -> d.expansion.elementalEffects = v);
        flag(entries, "expansion.wearableModels", "Wearable backpack models", "Show worn backpack models.",
                d -> d.expansion.wearableModels, (d, v) -> d.expansion.wearableModels = v);
        flag(entries, "expansion.cosmeticParticles", "Backpack particles", "Allow cosmetic backpack particles.",
                d -> d.expansion.cosmeticParticles, (d, v) -> d.expansion.cosmeticParticles = v);
        decimal(entries, "expansion.stoneArmor", "Stone backpack armor", "Armor points added.", 0, 4,
                d -> d.expansion.stoneArmor, (d, v) -> d.expansion.stoneArmor = v);
        decimal(entries, "expansion.diamondArmor", "Diamond backpack armor", "Armor points added.", 0, 6,
                d -> d.expansion.diamondArmor, (d, v) -> d.expansion.diamondArmor = v);
        decimal(entries, "expansion.magmaReduction", "Magma backpack fire reduction", "Fraction of incoming fire damage reduced.", 0, .5,
                d -> d.expansion.magmaReduction, (d, v) -> d.expansion.magmaReduction = v);
        decimal(entries, "expansion.waterSwimBonus", "Water backpack swim bonus", "Fractional swimming speed bonus.", 0, .25,
                d -> d.expansion.waterSwimBonus, (d, v) -> d.expansion.waterSwimBonus = v);
        decimal(entries, "expansion.netheriteKnockback", "Netherite backpack knockback resistance",
                "Knockback resistance added by a qualifying high-tier backpack.", 0, .1,
                d -> d.expansion.netheriteKnockback, (d, v) -> d.expansion.netheriteKnockback = v);
        flag(entries, "expansion.monsterBackpacks", "Monster backpacks", "Allow eligible monsters to carry backpacks.",
                d -> d.expansion.monsterBackpacks, (d, v) -> d.expansion.monsterBackpacks = v);
        decimal(entries, "expansion.monsterChance", "Monster backpack chance", "Spawn probability as a fraction.", 0, .1,
                d -> d.expansion.monsterChance, (d, v) -> d.expansion.monsterChance = v);
        decimal(entries, "expansion.monsterDropChance", "Monster backpack drop chance", "Loot probability as a fraction.", 0, 1,
                d -> d.expansion.monsterDropChance, (d, v) -> d.expansion.monsterDropChance = v);

        flag(entries, "expansion.imagesEnabled", "Custom interior images", "Enable custom interior uploads and attachments.",
                d -> d.expansion.imagesEnabled, (d, v) -> d.expansion.imagesEnabled = v);
        flag(entries, "expansion.imageUploadsForAll", "Public upload permission", "Allow players to upload when images are enabled.",
                d -> d.expansion.imageUploadsForAll, (d, v) -> d.expansion.imageUploadsForAll = v);
        flag(entries, "expansion.imageSharing", "Custom image sharing", "Allow sharing approved images with explicitly granted viewers.",
                d -> d.expansion.imageSharing, (d, v) -> d.expansion.imageSharing = v);
        flag(entries, "expansion.imageModeration", "Moderate custom images", "New uploads await approval before they can be shared.",
                d -> d.expansion.imageModeration, (d, v) -> d.expansion.imageModeration = v);
        integer(entries, "expansion.imagesPerPlayer", "Images per player", "Maximum stored images for each player.", 1, 8,
                d -> d.expansion.imagesPerPlayer, (d, v) -> d.expansion.imagesPerPlayer = v);
        integer(entries, "expansion.imagesTotal", "World image count", "Maximum number of stored images in a world.", 1, 512,
                d -> d.expansion.imagesTotal, (d, v) -> d.expansion.imagesTotal = v);
        integer(entries, "expansion.imageWorldMiB", "World image storage", "Maximum image storage in MiB.", 1, 64,
                d -> d.expansion.imageWorldMiB, (d, v) -> d.expansion.imageWorldMiB = v);

        add(entries, "expansion.pocketCoreMaterial", Kind.REGISTRY_ID, "Legacy pocket core", "Core for data packs using the legacy entrance recipe type. The standard recipe uses diamonds and Ancient Debris.",
                null, null, d -> d.expansion.pocketCoreMaterial, (d, v) -> d.expansion.pocketCoreMaterial = v,
                ServerSettings::parseRegistryId);
        integer(entries, "expansion.pocketSessionTicks", "Remote pocket session length", "Time limit for remote access and pending entry. Physical visits continue while authorized.", 200, 12000,
                d -> d.expansion.pocketSessionTicks, (d, v) -> d.expansion.pocketSessionTicks = v);
        flag(entries, "expansion.pocketEditing", "Pocket room editing", "Requires active pocket storage.",
                d -> d.expansion.pocketEditing, (d, v) -> {d.expansion.pocketEditing=v;d.expansion.editingOnFirstEnable=false;});
        flag(entries, "expansion.pocketStorage", "Pocket storage", "Requires the pocket dimension. Turning it off also turns off room editing.",
                d -> d.expansion.pocketStorage, (d, v) -> {
                    if(v && !d.expansion.pocketStorage && d.expansion.editingOnFirstEnable){
                        d.expansion.editingOnFirstEnable=false;
                    }
                    d.expansion.pocketStorage = v;
                    if (!v) d.expansion.pocketEditing = false;
                });
        integer(entries, "expansion.pocketLimit", "Pocket allocation limit", "Maximum number of allocated pockets.", 1, 256,
                d -> d.expansion.pocketLimit, (d, v) -> d.expansion.pocketLimit = v);
        integer(entries, "expansion.activePocketLimit", "Active pocket limit", "Maximum simultaneous active pockets.", 1, 8,
                d -> d.expansion.activePocketLimit, (d, v) -> d.expansion.activePocketLimit = v);
        integer(entries, "expansion.pocketContainers", "Pocket container limit", "Maximum containers accounted for in a pocket.", 1, 64,
                d -> d.expansion.pocketContainers, (d, v) -> d.expansion.pocketContainers = v);

        integer(entries, "expansion.tabletOpeningChancePercent", "Cupid Tablet opening chance",
                "Percent chance of the intro on an authorized Cupid Tablet opening.", 0, 100,
                d -> d.expansion.tabletOpeningChancePercent, (d, v) -> d.expansion.tabletOpeningChancePercent = v);
        flag(entries, "expansion.chestCameoEnabled", "Chest Cupid cameo", "Allow Cupid's cameo on direct Anya chest openings.",
                d -> d.expansion.chestCameoEnabled, (d, v) -> d.expansion.chestCameoEnabled = v);
        integer(entries, "expansion.chestCameoChancePercent", "Chest Cupid cameo chance", "Percent chance when the chest cameo is enabled.",
                0, 100, d -> d.expansion.chestCameoChancePercent, (d, v) -> d.expansion.chestCameoChancePercent = v);
        flag(entries, "expansion.backpackCameoEnabled", "Backpack Cupid cameo", "Allow Cupid's cameo on direct backpack inventory openings.",
                d -> d.expansion.backpackCameoEnabled, (d, v) -> d.expansion.backpackCameoEnabled = v);

        return Collections.unmodifiableMap(entries);
    }

    private static void flag(Map<String, Entry<?>> entries, String key, String label, String description,
                             Function<Config.Data, Boolean> read, BiConsumer<Config.Data, Boolean> write) {
        add(entries, key, Kind.BOOLEAN, label, description, null, null, read, write, ServerSettings::parseBoolean);
    }

    private static void integer(Map<String, Entry<?>> entries, String key, String label, String description,
                                int minimum, int maximum, Function<Config.Data, Integer> read,
                                BiConsumer<Config.Data, Integer> write) {
        add(entries, key, Kind.INTEGER, label, description, minimum, maximum, read, write, ServerSettings::parseInteger);
    }

    private static void decimal(Map<String, Entry<?>> entries, String key, String label, String description,
                                double minimum, double maximum, Function<Config.Data, Double> read,
                                BiConsumer<Config.Data, Double> write) {
        add(entries, key, Kind.DECIMAL, label, description, minimum, maximum, read, write, ServerSettings::parseDecimal);
    }

    private static <T> void add(Map<String, Entry<?>> entries, String key, Kind kind, String label, String description,
                                Number minimum, Number maximum, Function<Config.Data, T> read,
                                BiConsumer<Config.Data, T> write, Function<String, T> parse) {
        Definition definition = new Definition(key, kind, label, description, read.apply(DEFAULTS), minimum, maximum);
        if (entries.putIfAbsent(key, new Entry<>(definition, read, write, parse)) != null)
            throw new IllegalStateException("Duplicate server setting " + key);
    }

    private static boolean parseBoolean(String text) {
        if ("true".equals(text)) return true;
        if ("false".equals(text)) return false;
        throw new IllegalArgumentException("Enter true or false.");
    }

    private static int parseInteger(String text) {
        if (text != null && INTEGER.matcher(text).matches()) {
            try { return Integer.parseInt(text); }
            catch (NumberFormatException ignored) {}
        }
        throw new IllegalArgumentException("Enter a whole number within the supported range.");
    }

    private static double parseDecimal(String text) {
        if (text != null && DECIMAL.matcher(text).matches()) {
            double value = Double.parseDouble(text);
            if (Double.isFinite(value)) return value;
        }
        throw new IllegalArgumentException("Enter a finite decimal number.");
    }

    private static String parseRegistryId(String text) {
        if (!Config.validId(text)) throw new IllegalArgumentException("Enter a full registry ID such as minecraft:diamond.");
        return text;
    }
}
