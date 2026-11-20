package dev.anya.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import net.minecraft.server.level.ServerPlayer;

/** Real config parsing, atomic file transactions and policy sampling, with a recording operator/server host. */
public final class ServerChanceCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Percentiles extends Random {
        int count;
        @Override public int nextInt(int bound) {
            require(bound == 100 && count < 100, "One independent draw in [0,100) per roll");
            return count++;
        }
    }

    private static void checkPolicy(boolean tabletAlwaysAppears, boolean chestAlwaysAppears, boolean backpackEnabled) {
        var policy = Config.get().expansion;
        require(policy.rollTabletOpening(new Percentiles()) == tabletAlwaysAppears
                && policy.tabletOpeningChancePercent == (tabletAlwaysAppears ? 100 : 0),
                "The saved Remote percentage disagrees with the forced or skipped opening");
        require(policy.rollChestCameo(new Percentiles()) == chestAlwaysAppears
                && policy.chestCameoEnabled == chestAlwaysAppears
                && (!chestAlwaysAppears || policy.chestCameoChancePercent == 100),
                "The saved chest settings disagree with the independently selected result");
        require(policy.backpackCameoEnabled == backpackEnabled, "The backpack toggle changed with another setting");
    }

    public static void main(String[] args) throws Exception {
        Path config = Path.of(args[0], "anya-storage-plus.json");
        String previous = """
                {"schema":2,"revision":11,"audit":false,"quickRadius":13,
                 "expansion":{"pocketStorage":true,"pocketLimit":28}}
                """;
        Files.writeString(config, previous);
        Config.init();
        require(Files.readString(config).equals(previous), "Loading older settings rewrote them");
        require(Config.tabletOpeningChance().equals(new Config.TabletOpeningChance(11,24)), "Missing key must default to 24");
        require(new Config.Data().expansion.tabletOpeningChancePercent == 24, "Fresh default");
        require(!Config.get().expansion.chestCameoEnabled && Config.get().expansion.chestCameoChancePercent == 1
                && !Config.get().expansion.backpackCameoEnabled, "Older configs inherited an opt-in or lost the chest default");
        for (String key : new String[]{"tabletOpeningChancePercent","chestCameoChancePercent"})
        for (String invalid : new String[]{"-1","101","24.5","4294967320","null","true","\"24\""}) {
            Path sample = config.resolveSibling("invalid.json");
            String raw = "{\"expansion\":{\"" + key + "\":" + invalid + "}}";
            Files.writeString(sample, raw);
            try { Config.read(sample); throw new AssertionError("Accepted " + invalid); }
            catch (IllegalArgumentException | java.io.IOException expected) {}
            require(Files.readString(sample).equals(raw), "Rewrote rejected file");
        }
        var chestPolicy = new ExpansionRules.Options();
        var chestRandom = new Percentiles();
        require(!chestPolicy.rollChestCameo(chestRandom) && chestRandom.count == 0, "Disabled chest consumed a roll");
        chestPolicy.chestCameoEnabled = true;
        int chestHits = 0;
        for (int percentile = 0; percentile < 100; percentile++)
            if (chestPolicy.rollChestCameo(chestRandom)) chestHits++;
        require(chestHits == 1 && chestRandom.count == 100, "Enabled chest must use its own 1% default");
        for (int percent : new int[]{0,1,24,99,100}) {
            var policy = new ExpansionRules.Options();
            var random = new Percentiles();
            policy.tabletOpeningChancePercent = percent;
            int visible = 0;
            for (int percentile = 0; percentile < 100; percentile++) {
                if (policy.rollTabletOpening(random)) visible++;
            }
            require(random.count == 100 && visible == percent, "Wrong visible count at " + percent);
        }
        var pageType = Class.forName("dev.anya.storage.StoreMenu$Page");
        var storage = pageType.getDeclaredMethod("isStorage");
        storage.setAccessible(true);
        for (var page : pageType.getEnumConstants()) {
            require((boolean)storage.invoke(page) == java.util.Set.of("BAG","CHEST","NETWORK").contains(page.toString()),
                    "Wrong eligibility for " + page);
        }

        var actor = new ServerPlayer();
        var draft = Config.copy(Config.get());
        draft.expansion.tabletOpeningChancePercent = 0;
        Config.validateDraft(draft); // An existing dormant pocket gate is unrelated to this edit.
        require(Config.tabletOpeningChance().percent() == 24 && Files.readString(config).equals(previous), "Draft escaped");
        require(!Config.setTabletOpeningChancePercent(actor,11,24), "Unauthorized no-op");
        require(!Config.setTabletOpeningChancePercent(actor,11,100), "Unauthorized edit");
        actor.operator = true;
        actor.getServer().serverThread = false;
        require(!Config.setTabletOpeningChancePercent(actor,11,100), "Off-thread edit");
        actor.getServer().serverThread = true;
        require(!Config.setTabletOpeningChancePercent(actor,10,24), "Stale no-op");
        require(!Config.setTabletOpeningChancePercent(actor,11,101), "Unbounded write");
        require(Config.setTabletOpeningChancePercent(actor,11,24) && Files.readString(config).equals(previous), "No-op rewrote config");
        require(Config.setTabletOpeningChancePercent(actor,11,100), "Fresh authorized edit");
        checkPolicy(true, false, false);
        require(Files.readString(config.resolveSibling("anya-storage-plus.json.bak")).equals(previous), "Backup lost previous settings");
        require(Config.read(config).expansion.tabletOpeningChancePercent == 100, "Persisted chance");
        require(Config.get().quickRadius == 13 && Config.get().expansion.pocketStorage && Config.get().expansion.pocketLimit == 28,
                "Unrelated values changed");
        require(!Config.save(actor,draft), "Stale operator draft overwrote API edit");
        var latest = Config.copy(Config.get());
        latest.expansion.tabletOpeningChancePercent = 0;
        latest.expansion.chestCameoEnabled = true;
        latest.expansion.chestCameoChancePercent = 100;
        Config.validateDraft(latest);
        require(Config.save(actor,latest) && Config.tabletOpeningChance().equals(new Config.TabletOpeningChance(13,0)),
                "Operator change was not applied");
        checkPolicy(false, true, false);
        require(actor.getServer().reloads == 0, "Cosmetic-only settings reloaded recipes");
        require(!Config.setTabletOpeningChancePercent(actor,11,56), "Stale adapter wrote");
        JsonObject disk = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        require(disk.getAsJsonObject("expansion").get("tabletOpeningChancePercent").getAsInt() == 0, "Wrong saved key");
        // Different settings in the same draft must still take the ordinary validation and reload path.
        var mixed = Config.copy(Config.get());
        mixed.expansion.pocketStorage = false;
        mixed.quickRadius = 10;
        mixed.expansion.tabletOpeningChancePercent = 100;
        mixed.expansion.chestCameoEnabled = false;
        mixed.expansion.backpackCameoEnabled = true;
        require(Config.save(actor,mixed) && actor.getServer().reloads == 1, "Mixed edit skipped recipe reload");
        Config.init();
        require(Config.tabletOpeningChance().equals(new Config.TabletOpeningChance(14,100)), "Restart readback");
        checkPolicy(true, false, true);
        System.out.println("PASS: migration, tablet and cameo isolation, exact percentile counts, invalid JSON preserved, private drafts, OP/thread/revision checks, atomic backup/readback, visual-only save and mixed reload");
    }
}
