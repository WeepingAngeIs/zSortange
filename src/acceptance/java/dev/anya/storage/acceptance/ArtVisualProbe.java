package dev.anya.storage.acceptance;

import dev.anya.storage.BasePermissions;
import dev.anya.storage.Content;
import dev.anya.storage.ExpansionRules;
import dev.anya.storage.StorageBlock;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.pocket.PocketContent;
import java.nio.file.Files;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Only the GitHub-only disposable packaged fixture loads this class. */
@EventBusSubscriber(modid = Acceptance.ID)
public final class ArtVisualProbe {
    static final BlockPos CHEST = new BlockPos(1, 81, 1);
    static final BlockPos ENTRANCE = new BlockPos(8, 81, 1);
    static final BlockPos HUB = new BlockPos(-3, 81, 1);
    private static boolean running;
    private static int phase, since;
    private static ServerPlayer firstGuest;

    @SubscribeEvent
    public static void start(ServerStartedEvent event) throws Exception {
        if (!Boolean.getBoolean("anya.artAcceptance")) return;
        Acceptance.require("true".equals(System.getenv("GITHUB_ACTIONS")), "Hosted visual fixture only");
        Acceptance.require(!ExpansionRules.get().pocketStorage && !Boolean.getBoolean("anyastorage.pocketPrototype"),
                "The visual fixture requires pockets off");
        var server = event.getServer();
        Acceptance.require(!Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),
                "Expected a fresh fixture world");
        Acceptance.verifyJar("art-server");
        var level = server.overworld();
        for (int x = -7; x <= 13; x++) for (int z = -4; z <= 10; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 80, z), Blocks.SMOOTH_STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(CHEST, Content.CRATE.get().defaultBlockState().setValue(StorageBlock.FACING, Direction.SOUTH));
        level.setBlockAndUpdate(ENTRANCE, PocketContent.ENTRANCE.get().defaultBlockState());
        level.setBlockAndUpdate(HUB, Content.HUB.get().defaultBlockState());
        level.setDefaultSpawnPos(new BlockPos(2, 81, 5), 180);
        level.setDayTime(6000);
        server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        since = server.getTickCount();
        running = true;
        Acceptance.marker("server-ready", "Small art set built with pockets off");
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!running) return;
        var server = event.getServer();
        var level = server.overworld();
        var alpha = server.getPlayerList().getPlayerByName("AnyaQAAlpha");
        var beta = server.getPlayerList().getPlayerByName("AnyaQABeta");
        try {
            int age = server.getTickCount() - since;
            Acceptance.require(age < (phase == 0 || phase == 11 ? 3600 : 1200), "Art phase timeout " + phase);
            switch (phase) {
                case 0 -> {
                    if (alpha == null || beta == null) return;
                    firstGuest = beta;
                    ItemStack keepsake = new ItemStack(Items.AMETHYST_SHARD, 3);
                    keepsake.set(DataComponents.CUSTOM_NAME, Component.literal("Lid study"));
                    beta.getInventory().setItem(8, keepsake);
                    beta.inventoryMenu.broadcastChanges();
                    // The observer sees the lid move while the second real client has the menu open.
                    chest(level).owner = beta.getUUID();
                    var hub = (StorageEntity) level.getBlockEntity(HUB);
                    hub.owner = alpha.getUUID();
                    hub.setMember(beta.getUUID(), BasePermissions.VIEW);
                    alpha.teleportTo(level, 2.6, 81, 3.85, 155, 20);
                    beta.teleportTo(level, 2.38, 81, 4.65, 165, 14);
                    ServerProbe.send(alpha, "art-closed");
                }
                case 1 -> {
                    if (!ServerProbe.done(alpha)) return;
                    ServerProbe.send(beta, "block 1 81 1");
                }
                case 2 -> {
                    if (!ServerProbe.done(beta)) return;
                    Acceptance.require(beta.containerMenu instanceof StoreMenu, "Second client did not open the actual chest");
                    ServerProbe.send(alpha, "art-open");
                }
                case 3 -> {
                    if (!ServerProbe.done(alpha)) return;
                    ServerProbe.send(beta, "close");
                }
                case 4 -> {
                    if (!ServerProbe.done(beta) || chest(level).openness != 0) return;
                    // Unfocused first: the survival player aims at the face from outside reach.
                    alpha.teleportTo(level, 9.9, 81, 7.7, 165, 10);
                    ServerProbe.send(alpha, "art-veil");
                }
                case 5 -> {
                    if (!ServerProbe.done(alpha)) return;
                    alpha.teleportTo(level, 9.65, 81, 4, 160, 17);
                    ServerProbe.send(alpha, "art-window");
                }
                case 6 -> {
                    if (!ServerProbe.done(alpha)) return;
                    alpha.teleportTo(level, -2.5, 81, 3.7, 180, 14);
                    ServerProbe.send(alpha, "block -3 81 1");
                }
                case 7 -> {
                    if (!ServerProbe.done(alpha)) return;
                    Acceptance.require(alpha.containerMenu instanceof StoreMenu, "Owner hub did not open");
                    ServerProbe.send(alpha, "art-members");
                }
                case 8 -> {
                    if (!ServerProbe.done(alpha)) return;
                    ServerProbe.send(alpha, "close");
                }
                case 9 -> {
                    if (!ServerProbe.done(alpha)) return;
                    Acceptance.marker("kill-beta", "Disconnect real guest after the chest has closed");
                }
                case 10 -> {
                    if (beta != null) return;
                    Acceptance.marker("restart-beta", "Reconnect the same guest to the ordinary overworld");
                }
                case 11 -> {
                    if (beta == null || age < 8) return;
                    Acceptance.require(beta != firstGuest && beta.getUUID().equals(firstGuest.getUUID()),
                            "Expected the same guest UUID on a new player connection");
                    var stored = beta.getInventory().getItem(8);
                    Acceptance.require(stored.is(Items.AMETHYST_SHARD) && stored.getCount() == 3
                            && stored.getHoverName().getString().equals("Lid study"), "Reconnected guest lost its named stack");
                    Acceptance.marker("PASSED", "Five live client captures; guest reconnected with the same three named shards; pockets off.");
                    running = false;
                    server.saveEverything(true, true, true);
                    server.halt(false);
                }
                default -> throw new IllegalStateException("Unknown visual phase " + phase);
            }
            phase++;
            since = server.getTickCount();
        } catch (Throwable error) {
            running = false;
            Acceptance.marker("FAILED-art-server", "phase=" + phase + " " + error);
            dev.anya.storage.AnyaStorage.LOG.error("ANYA_STORAGE_ART_FAILED", error);
            server.halt(false);
        }
    }

    private static StorageEntity chest(ServerLevel level) {
        return (StorageEntity) level.getBlockEntity(CHEST);
    }
}
