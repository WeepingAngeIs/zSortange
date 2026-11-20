package dev.anya.storage.ui;

import dev.anya.storage.Content;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

/** Artwork coordinates in control_atlas.png. Markers are only placed on disposable menu display stacks. */
public enum ControlGlyph {
    VIEW, DEPOSIT, WITHDRAW, ACTIVITY, VISIT, BUILD, MEMBER,
    MEMBERS, ADD_MEMBER, REMOVE_MEMBER, BACK, PREVIOUS, NEXT, SCROLL_UP,
    SCROLL_DOWN, CLOSE, ORGANIZE, QUICKSTACK, DUMP, PROTECT, UPGRADES,
    FAVORITES, INFO, BASE, SYMBOL, EXIT, CANCEL, CONFIRM,
    TIERS, LIMITS, ORE_VALUES, RECIPES, UPGRADE_SWITCH, PRESETS, DIAGNOSTICS,
    CATEGORY_RULES, POLICY, MODERATION, DISCARD, REVIEW, TIER, ADD_TIER,
    CAPACITY, UPGRADE_CAPACITY, RECIPE_CELLS, PREDECESSOR, MATERIAL, NAME, RECIPE_PREVIEW,
    RECIPE_STATUS, ORE_ADD, CUBE_RECIPE, SURVIVAL, LARGE_NETWORK, CHANGE, IMPACT,
    APPLY, HOTBAR, EXPLAIN, BASE_NAME, FEED_ORE, RADIUS, ROUTING,
    PERFORMANCE, TRUSTED, BASE_LOG, LOG_LINE, LOG_PRIVACY, CLEAR_LOG, CONTAINER,
    CATEGORY, PRIORITY, OVERFLOW, FILTER, CLEAR_FILTER, ROUTE_ORDER, WARDROBE,
    UPGRADE_LIST, PICKUP_FILTER, SMELT_FILTER, FEEDING, CRAFT, COMPACT, RESUPPLY,
    EDIT_PICKUP, EDIT_SMELT, STOP_SMELT, CONFIRM_FEED, HELD_RULE, TYPE_RULE, SETTINGS,
    PERMISSIONS, SAFETY, PROGRESSION, WEAR, PERSONAL_IMAGES, INTERIOR, IMAGE_PRIVACY,
    IMAGE_VIEWER, BUILTIN_INTERIOR, APPROVE_IMAGE, DELETE_IMAGE, IMAGE, PARTICLES, MODELS,
    CONTENTS, READ, CHAPTER, SLOT_PREF, PICKUP, REFILL, SMELTING,
    COMPAT, FALLBACK, BACKPACKS, NETWORK, LABELS, AUDIT, VANILLA_CHESTS,
    VANILLA_BARRELS, VANILLA_SHULKERS, HUBS, TERMINALS, CONTAINERS, ENDGAME, INITIAL_RADIUS,
    MAX_RADIUS, RADIUS_STEP, QUICK_RADIUS, SCAN_CHUNKS, MAX_CONTAINERS, MAX_ENTRIES, OPERATION_SLOTS,
    UPGRADE_COST, TUTORIAL_GRANT, TUTORIAL_RECOVERY, LOGGING, ACTOR_IDS, MEMBER_LOGS, LOG_ENTRIES,
    ELEMENTAL, WEARABLE_MODELS, COSMETIC_PARTICLES, STONE_ARMOR, DIAMOND_ARMOR, MAGMA, SWIM,
    KNOCKBACK, MONSTER_BAGS, MONSTER_CHANCE, MONSTER_DROP, MONSTER_SPECIES, IMAGES_ENABLED, IMAGE_UPLOAD,
    IMAGE_SHARING, IMAGE_MODERATION, UPLOADERS, IMAGES_PER_PLAYER, IMAGES_TOTAL, IMAGE_SIZE, POCKET_CORE,
    SESSION, POCKET_EDIT, POCKET_STORAGE, POCKET_LOG, POCKET_NAMES, POCKET_LIMIT, ACTIVE_POCKETS,
    POCKET_CONTAINERS, POCKET_ENTRIES, ELEMENT_PLAIN, ELEMENT_LEAF, ELEMENT_STONE, ELEMENT_DIAMOND, ELEMENT_MAGMA,
    ELEMENT_WATER, SKIN_ELEMENTAL, SKIN_BOW_CAT, SKIN_MIDNIGHT_CAT, SKIN_SPOOKY, SKIN_CUTE_SPOOKY, SKIN_FUTURISTIC,
    SKIN_GALAXY, SKIN_OAK, SKIN_CHERRY, SKIN_DARK_OAK;

    public enum State { NORMAL, ON, OFF, SELECTED, DISABLED, READ_ONLY }
    public record Face(ControlGlyph glyph, State state) {}

    private static final ControlGlyph[] GLYPHS = values();
    private static final State[] STATES = State.values();
    private static final int FIRST_MODEL = 780000;

    public void mark(ItemStack display, State state) {
        display.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(FIRST_MODEL + ordinal() * STATES.length + state.ordinal()));
    }

    public static Face read(ItemStack display) {
        if (!display.is(Content.REMOTE.get())) return null;
        CustomModelData model = display.get(DataComponents.CUSTOM_MODEL_DATA);
        if (model == null) return null;
        int index = model.value() - FIRST_MODEL;
        if (index < 0 || index >= GLYPHS.length * STATES.length) return null;
        return new Face(GLYPHS[index / STATES.length], STATES[index % STATES.length]);
    }

    public static ControlGlyph setting(String key) {
        return switch (key) {
            case "compatibility" -> COMPAT;
            case "fallbackMissingTiers" -> FALLBACK;
            case "backpacks" -> BACKPACKS;
            case "network" -> NETWORK;
            case "quickStack" -> QUICKSTACK;
            case "organize" -> ORGANIZE;
            case "labels" -> LABELS;
            case "audit" -> AUDIT;
            case "vanillaChests" -> VANILLA_CHESTS;
            case "vanillaBarrels" -> VANILLA_BARRELS;
            case "vanillaShulkers" -> VANILLA_SHULKERS;
            case "initialRadius" -> INITIAL_RADIUS;
            case "maxRadius" -> MAX_RADIUS;
            case "radiusStep" -> RADIUS_STEP;
            case "quickRadius" -> QUICK_RADIUS;
            case "scanChunksPerTick" -> SCAN_CHUNKS;
            case "maxContainers" -> MAX_CONTAINERS;
            case "maxScanEntries" -> MAX_ENTRIES;
            case "maxOperationSlots" -> OPERATION_SLOTS;
            case "upgradeBaseCost" -> UPGRADE_COST;
            case "hubs" -> HUBS;
            case "terminals" -> TERMINALS;
            case "containers" -> CONTAINERS;
            case "upgrades" -> UPGRADES;
            case "endgame" -> ENDGAME;
            case "pickup" -> PICKUP;
            case "feeding" -> FEEDING;
            case "refill" -> REFILL;
            case "crafting" -> CRAFT;
            case "smelting" -> SMELTING;
            case "compacting" -> COMPACT;
            case "resupply" -> RESUPPLY;
            case "tutorialGrant" -> TUTORIAL_GRANT;
            case "tutorialRecovery" -> TUTORIAL_RECOVERY;
            case "activityLogging" -> LOGGING;
            case "activityActorIds" -> ACTOR_IDS;
            case "activityMemberLogs" -> MEMBER_LOGS;
            case "activityLogEntries" -> LOG_ENTRIES;
            case "elementalEffects" -> ELEMENTAL;
            case "wearableModels" -> WEARABLE_MODELS;
            case "cosmeticParticles" -> COSMETIC_PARTICLES;
            case "stoneArmor" -> STONE_ARMOR;
            case "diamondArmor" -> DIAMOND_ARMOR;
            case "magmaReduction" -> MAGMA;
            case "waterSwimBonus" -> SWIM;
            case "netheriteKnockback" -> KNOCKBACK;
            case "monsterBackpacks" -> MONSTER_BAGS;
            case "monsterChance" -> MONSTER_CHANCE;
            case "monsterDropChance" -> MONSTER_DROP;
            case "monsterSpecies" -> MONSTER_SPECIES;
            case "imagesEnabled" -> IMAGES_ENABLED;
            case "imageUploadsForAll" -> IMAGE_UPLOAD;
            case "imageSharing" -> IMAGE_SHARING;
            case "imageModeration" -> IMAGE_MODERATION;
            case "imageUploaders" -> UPLOADERS;
            case "imagesPerPlayer" -> IMAGES_PER_PLAYER;
            case "imagesTotal" -> IMAGES_TOTAL;
            case "imageWorldMiB" -> IMAGE_SIZE;
            case "pocketCoreMaterial" -> POCKET_CORE;
            case "pocketSessionTicks" -> SESSION;
            case "pocketEditing" -> POCKET_EDIT;
            case "pocketStorage" -> POCKET_STORAGE;
            case "pocketActivityLog" -> POCKET_LOG;
            case "pocketLogNames" -> POCKET_NAMES;
            case "pocketLimit" -> POCKET_LIMIT;
            case "activePocketLimit" -> ACTIVE_POCKETS;
            case "pocketContainers" -> POCKET_CONTAINERS;
            case "pocketLogEntries" -> POCKET_ENTRIES;
            default -> SETTINGS;
        };
    }
}
