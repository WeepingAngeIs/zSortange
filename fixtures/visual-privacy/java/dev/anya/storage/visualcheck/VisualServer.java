package dev.anya.storage.visualcheck;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.anya.storage.AnyaStorage;
import dev.anya.storage.BagInventory;
import dev.anya.storage.BasePermissions;
import dev.anya.storage.Content;
import dev.anya.storage.ExpansionRules;
import dev.anya.storage.StorageAccessEvent;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.pocket.PocketContent;
import dev.anya.storage.pocket.PocketLeases;
import dev.anya.storage.pocket.PocketLedger;
import dev.anya.storage.pocket.PocketRoomLayout;
import dev.anya.storage.pocket.PocketService;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Test-only orchestration. Both observations use the packaged mod's normal focus, access and network paths. */
@Mod("anyavisualcheck")
@EventBusSubscriber(modid = "anyavisualcheck")
public final class VisualServer {
    private static final BlockPos ENTRANCE = new BlockPos(8, 81, 1);
    private static final BlockPos HUB = new BlockPos(4, 81, 1);
    private static final AABB CUBE_INTERIOR = new AABB(ENTRANCE).deflate(1.0e-6);
    private static final Vec3 SOUTH = new Vec3(8.0, 81, 4.5);
    private static final Vec3 EAST = new Vec3(11.45, 81, 2.40);
    private static final Vec3 WALK_SOUTH = new Vec3(8.5, 81, 3.5);
    private static final Vec3 WALK_EAST = new Vec3(10.5, 81, 1.5);
    private static final String ITEM_NAME = "Visual fixture private chest item";
    private static final boolean STILL = Boolean.getBoolean("anyastorage.stillFramePeek");
    private enum Step { CONNECT, PREPARE, RETURN, PUBLIC, GRANTED, CHANGED, CELL_DENIED, RESUMED, USE, REVOKED, REGRANTED, FINAL_PUBLIC, WALK, STOP }
    private static Path dir;
    private static MinecraftServer server;
    private static ServerLevel target;
    private static PocketLedger ledger;
    private static StorageEntity hub, chest;
    private static PocketContent.EntranceEntity entrance;
    private static PocketLedger.Pocket room;
    private static UUID chestId;
    private static ServerPlayer alpha, beta;
    private static ServerPlayer setupOwner;
    private static EmbeddedChannel setupChannel;
    private static final List<ChunkPos> forced = new ArrayList<>();
    private static final Map<UUID, Integer> sourceUses = new HashMap<>();
    private static final JsonObject result = new JsonObject();
    private static final JsonObject phaseTicks = new JsonObject();
    private static final JsonObject markerWrites = new JsonObject();
    private static BlockPos marker;
    private static int exactVetoes;
    private static int otherUses, dimensionChanges, viewerDimensionChangesSinceLogin, started, observedTicks;
    private static boolean measuring;
    private static Step step;

    @SubscribeEvent
    public static void start(ServerStartedEvent event) throws Exception {
        require("true".equals(System.getenv("GITHUB_ACTIONS")), "Disposable hosted runner required");
        dir = Path.of(System.getProperty("anya.control")).toAbsolutePath();
        require(Files.isRegularFile(dir.resolve(".disposable-visual-privacy")), "Missing disposable world marker");
        server = event.getServer();
        try {
            VisualInputs.verify("server");
            require(!Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")), "Expected a new world");
            ExpansionRules.get().pocketStorage = true;
            require(PocketService.enabled(), "Pocket prototype gate missing");
            target = PocketService.dimension(server);
            ledger = PocketService.ledger(server);
            require(target != null && ledger.pockets().isEmpty(), "Expected a fresh pocket dimension");
            var home = server.overworld();
            for (int x = 0; x <= 16; x++) for (int z = -5; z <= 8; z++) {
                home.setBlockAndUpdate(new BlockPos(x, 80, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
            home.setBlockAndUpdate(HUB, Content.HUB.get().defaultBlockState());
            home.setBlockAndUpdate(ENTRANCE, PocketContent.ENTRANCE.get().defaultBlockState());
            hub = (StorageEntity) home.getBlockEntity(HUB);
            entrance = (PocketContent.EntranceEntity) home.getBlockEntity(ENTRANCE);
            home.setDefaultSpawnPos(new BlockPos(8, 81, 5), 180);
            home.setDayTime(6000);
            home.setWeatherParameters(6000, 0, false, false);
            server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            createRoom();
            next(Step.PREPARE);
        } catch (Exception | Error failure) {
            fail(failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (step == null || event.getServer() != server) return;
        try {
            int age = server.getTickCount() - started;
            require(age < (step == Step.CONNECT ? 9000 : 3600), "Timeout at " + step);
            if (measuring) verifyObservers();
            switch (step) {
                case PREPARE -> {
                    if (setupOwner.level() != target || ledger.returning(setupOwner.getUUID()) == null) return;
                    seedRoom();
                    PocketService.recover(setupOwner);
                    next(Step.RETURN);
                }
                case RETURN -> {
                    if (setupOwner.level() != server.overworld() || ledger.returning(setupOwner.getUUID()) != null
                            || PocketService.lease(setupOwner.getUUID()) != null || PocketService.ownedTicketRequests() != 0) return;
                    removeSetupOwner();
                    require(server.getPlayerList().getPlayerCount() == 0, "Setup actor remained registered");
                    next(Step.CONNECT);
                    Files.writeString(dir.resolve("server-ready"), "Setup Visit complete and owner removed; accepting two graphical players\n",
                            StandardOpenOption.CREATE_NEW);
                }
                case CONNECT -> {
                    alpha = server.getPlayerList().getPlayerByName("Alpha");
                    beta = server.getPlayerList().getPlayerByName("Beta");
                    if (alpha == null || beta == null || !exists("client-ready-Alpha.json") || !exists("client-ready-Beta.json")) return;
                    for (var viewer : List.of(alpha, beta)) {
                        require(viewer.serverLevel() == server.overworld() && !viewer.hasPermissions(2)
                                && !server.getPlayerList().isOp(viewer.getGameProfile()), "Expected fresh non-operators in the Overworld");
                        viewer.setGameMode(GameType.SURVIVAL);
                        viewer.getInventory().clearContent();
                        viewer.inventoryMenu.broadcastChanges();
                    }
                    pose(alpha, SOUTH);
                    pose(beta, EAST);
                    rights(false);
                    measuring = true;
                    publish(Step.PUBLIC);
                }
                case PUBLIC -> {
                    if (!bothDone()) return;
                    hub.setMember(alpha.getUUID(), BasePermissions.VIEW);
                    rights(true);
                    publish(Step.GRANTED);
                }
                case GRANTED -> {
                    if (!bothDone()) return;
                    rights(true);
                    if (STILL) {
                        writeMarker("changed", Blocks.PURPLE_CONCRETE);
                        publish(Step.CHANGED);
                    } else publish(Step.USE);
                }
                case CHANGED -> {
                    if (!bothDone()) return;
                    publish(Step.CELL_DENIED);
                    writeMarker("cell_denied", Blocks.CYAN_CONCRETE);
                }
                case CELL_DENIED -> {
                    if (!bothDone()) return;
                    require(exactVetoes > 0, "Expected a destination check for the actor at the sampled cell");
                    rights(true);
                    publish(Step.RESUMED);
                }
                case RESUMED -> {
                    if (!bothDone()) return;
                    rights(true);
                    publish(Step.USE);
                }
                case USE -> {
                    if (!bothDone()) return;
                    verifyUseReceipt(alpha, "SOUTH");
                    verifyUseReceipt(beta, "EAST");
                    rights(true);
                    hub.removeMember(alpha.getUUID());
                    rights(false);
                    if (STILL) writeMarker("revoked", Blocks.PURPLE_CONCRETE);
                    publish(Step.REVOKED);
                }
                case REVOKED -> {
                    if (!bothDone()) return;
                    if (STILL) {
                        hub.setMember(alpha.getUUID(), BasePermissions.VIEW);
                        rights(true);
                        publish(Step.REGRANTED);
                    } else startWalk();
                }
                case REGRANTED -> {
                    if (!bothDone()) return;
                    hub.removeMember(alpha.getUUID());
                    rights(false);
                    publish(Step.FINAL_PUBLIC);
                }
                case FINAL_PUBLIC -> {
                    if (!bothDone()) return;
                    startWalk();
                }
                case WALK -> {
                    if (!bothDone()) return;
                    // Movement and completion arrive on separate channels. Allow ordinary queued movement to reach the server.
                    if (!contact(alpha, true) || !contact(beta, false)) return;
                    complete();
                }
                case STOP -> {
                    if (age >= 30) {
                        step = null;
                        server.halt(false);
                    }
                }
            }
        } catch (Exception | Error failure) {
            fail(failure);
        }
    }

    private static void createRoom() throws IOException {
        require(server.getPlayerList().getPlayerCount() == 0, "Graphical observers must join after setup");
        // Use the same registered setup actor pattern as the hosted position fixture.
        // Its packets terminate in memory. Both graphical clients join only after its Visit ends.
        var profile = new GameProfile(UUID.randomUUID(), "VisualOwner");
        setupOwner = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault()) {
            @Override public boolean hasPermissions(int ignored) { return false; }
        };
        var connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public boolean isMemoryConnection() { return true; }
            @Override public void send(Packet<?> packet, PacketSendListener callback, boolean flush) {}
        };
        setupChannel = new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, setupOwner, CommonListenerCookie.createInitial(profile, false));
        require(!server.getPlayerList().isOp(profile), "Setup owner must be a non-operator");
        setupOwner.setPos(8.5, 81, 3.5);
        hub.owner = entrance.owner = setupOwner.getUUID();
        var remote = new ItemStack(Content.CUPID_TABLET.get());
        var data = BagInventory.data(remote);
        data.putString("HubDimension", server.overworld().dimension().location().toString());
        data.putLong("HubPos", HUB.asLong());
        BagInventory.data(remote, data);
        setupOwner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,remote);
        PocketService.link(setupOwner, entrance, remote);
        require(entrance.pocket != null && ledger.get(entrance.pocket).stage() == PocketLedger.Stage.RESERVED, "Home link did not reserve a room");
        PocketService.enter(setupOwner, entrance);
        var lease = PocketService.lease(setupOwner.getUUID());
        require(lease != null && lease.kind() == PocketLeases.Kind.VISIT, "The temporary setup Visit was denied");
    }

    private static void removeSetupOwner() {
        if (setupOwner != null && server.getPlayerList().getPlayer(setupOwner.getUUID()) == setupOwner) {
            server.getPlayerList().remove(setupOwner);
        }
        setupOwner = null;
        if (setupChannel != null) {
            setupChannel.finishAndReleaseAll();
            setupChannel = null;
        }
    }

    private static void seedRoom() throws IOException {
        room = ledger.get(entrance.pocket);
        require(room.stage() == PocketLedger.Stage.READY && PocketService.ownedTicketRequests() > 0, "Setup Visit missing a prepared room");
        for (int x = room.x() >> 4; x <= (room.x() + 31) >> 4; x++) {
            for (int z = room.z() >> 4; z <= (room.z() + 31) >> 4; z++) {
                require(target.setChunkForced(x, z, true), "Setup column already held");
                forced.add(new ChunkPos(x, z));
            }
        }
        var pad = PocketRoomLayout.pads(room).get(3);
        require(target.isEmptyBlock(pad), "Expected empty optional pad eight");
        target.setBlockAndUpdate(pad, Content.CRATE.get().defaultBlockState());
        chest = (StorageEntity) target.getBlockEntity(pad);
        chest.owner = room.owner();
        chest.pocketId = room.id();
        chestId = chest.storageIdentity();
        var item = new ItemStack(Items.EMERALD, 11);
        item.set(DataComponents.CUSTOM_NAME, Component.literal(ITEM_NAME));
        chest.setItem(0, item);
        require(PocketRoomLayout.present(target, room).size() == 5, "Expected four starter chests and pad eight");
        if (STILL) {
            marker = new BlockPos(room.x() + 24, 68, room.z() + 24);
            require(target.isEmptyBlock(marker) && target.getChunkAt(marker).getBlockEntities().isEmpty(),
                    "Expected an empty decoration cell in the disposable room");
            writeMarker("setup", Blocks.CYAN_CONCRETE);
        }
    }

    private static void writeMarker(String phase, Block block) {
        require(target.hasChunkAt(marker) && target.setBlockAndUpdate(marker, block.defaultBlockState()),
                "Expected a genuine change in the warm disposable room");
        var applied = target.getBlockState(marker);
        require(applied.is(block), "Marker readback differs from the server edit");
        var value = new JsonObject();
        value.addProperty("tick", server.getTickCount());
        value.addProperty("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(applied.getBlock()).toString());
        markerWrites.add(phase, value);
    }

    @SubscribeEvent
    public static void denySample(StorageAccessEvent event) {
        if (STILL && step == Step.CELL_DENIED && event.player == alpha && event.level == target
                && event.position.equals(marker)) {
            exactVetoes++;
            event.setCanceled(true);
        }
    }

    private static void startWalk() throws IOException {
        pose(alpha, WALK_SOUTH);
        pose(beta, WALK_EAST);
        publish(Step.WALK);
    }

    private static void rights(boolean alphaView) {
        require(!hub.ownedBy(alpha) && !hub.ownedBy(beta)
                && hub.memberMask(alpha.getUUID()) == (alphaView ? BasePermissions.VIEW : 0)
                && hub.memberMask(beta.getUUID()) == 0, "Unexpected member rights");
        require(PocketService.authorize(alpha, room, PocketLeases.Kind.REMOTE, false, BasePermissions.VIEW).isEmpty() == alphaView
                && !PocketService.authorize(beta, room, PocketLeases.Kind.REMOTE, false, BasePermissions.VIEW).isEmpty(),
                "Remote View authorization differs from hub membership");
        for (var viewer : List.of(alpha, beta)) {
            require(!PocketService.authorize(viewer, room, PocketLeases.Kind.VISIT, true, 0).isEmpty(), "Viewer acquired physical Visit");
        }
    }

    private static void pose(ServerPlayer viewer, Vec3 pos) {
        Vec3 aim = Vec3.atCenterOf(ENTRANCE).subtract(pos.add(0, viewer.getEyeHeight(), 0));
        float yaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(aim.y, aim.horizontalDistance()));
        viewer.teleportTo(server.overworld(), pos.x, pos.y, pos.z, yaw, pitch);
        viewer.setDeltaMovement(Vec3.ZERO);
    }

    @SubscribeEvent
    public static void clicked(PlayerInteractEvent.RightClickBlock event) {
        if (!measuring || event.getHand() != InteractionHand.MAIN_HAND || !(event.getEntity() instanceof ServerPlayer p)
                || (p != alpha && p != beta)) return;
        if (step == Step.USE && p.level() == server.overworld() && event.getPos().equals(ENTRANCE) && p.getMainHandItem().isEmpty()) {
            sourceUses.merge(p.getUUID(), 1, Integer::sum);
        } else otherUses++;
    }

    @SubscribeEvent
    public static void changedWorld(PlayerEvent.PlayerChangedDimensionEvent event) {
        String name = event.getEntity().getGameProfile().getName();
        if (name.equals("Alpha") || name.equals("Beta")) viewerDimensionChangesSinceLogin++;
        if (measuring && (event.getEntity() == alpha || event.getEntity() == beta)) dimensionChanges++;
    }

    private static int countUses(ServerPlayer viewer) { return sourceUses.getOrDefault(viewer.getUUID(), 0); }

    private static void verifyUseReceipt(ServerPlayer viewer, String face) throws IOException {
        String role = viewer.getGameProfile().getName();
        var receipt = JsonParser.parseString(Files.readString(dir.resolve("use-" + role + "-done.json"))).getAsJsonObject();
        require(receipt.get("target").getAsLong() == ENTRANCE.asLong()
                && face.equals(receipt.get("side").getAsString())
                && "minecraft:overworld".equals(receipt.get("home_dimension").getAsString())
                && "SUCCESS".equals(receipt.get("interaction_result").getAsString())
                && receipt.get("observed_ticks_after_use").getAsInt() >= 12
                && receipt.get("denial_count").getAsInt() == 1
                && "[Anya Storage] Pocket permission denied; Visit is separate from storage rights".equals(receipt.get("denial_message").getAsString()),
                "Expected a normal use at the home entrance and one matching server denial for " + role);
    }

    private static void verifyObservers() {
        for (var viewer : List.of(alpha, beta)) {
            require(server.getPlayerList().getPlayer(viewer.getUUID()) == viewer && viewer.level() == server.overworld()
                    && viewer.isAlive() && !viewer.isSpectator(), "Viewer disconnected or left home");
            require(viewer.containerMenu == viewer.inventoryMenu && PocketService.lease(viewer.getUUID()) == null,
                    "Viewing opened storage or acquired a lease");
            require(!viewer.getBoundingBox().intersects(CUBE_INTERIOR), "Player passed into the source cube");
        }
        require(ledger.returnCount() == 0 && PocketService.ownedTicketRequests() == 0 && dimensionChanges == 0
                        && viewerDimensionChangesSinceLogin == 0 && otherUses == 0,
                "Unexpected travel, alternate interaction target or storage ticket");
        observedTicks++;
    }

    private static boolean contact(ServerPlayer viewer, boolean south) {
        var box = viewer.getBoundingBox();
        if (south) return Math.abs(box.minZ - (ENTRANCE.getZ() + 1)) < .02
                && Math.abs(viewer.getX() - WALK_SOUTH.x) < .08 && WALK_SOUTH.z - viewer.getZ() > .75;
        return Math.abs(box.minX - (ENTRANCE.getX() + 1)) < .02
                && Math.abs(viewer.getZ() - WALK_EAST.z) < .08 && WALK_EAST.x - viewer.getX() > .75;
    }

    private static void complete() throws IOException {
        rights(false);
        require(target.getBlockEntity(chest.getBlockPos()) == chest && chestId.equals(chest.existingStorageIdentity())
                && chest.getItem(0).is(Items.EMERALD) && chest.getItem(0).getCount() == 11
                && chest.getItem(0).getHoverName().getString().equals(ITEM_NAME), "Destination chest or its item changed");
        result.addProperty("room", room.id().toString());
        if (STILL) {
            result.add("marker_writes", markerWrites);
            result.addProperty("exact_cell_vetoes", exactVetoes);
        } else result.addProperty("diagram_mask", 143);
        result.addProperty("authorized_role", "Alpha");
        result.addProperty("never_authorized_role", "Beta");
        result.addProperty("home_only_observation_ticks", observedTicks);
        result.addProperty("dimension_changes_during_observation", dimensionChanges);
        result.addProperty("graphical_player_dimension_changes_since_login", viewerDimensionChangesSinceLogin);
        result.addProperty("setup", "Separate non-operator server actor linked, visited, returned and disconnected before either graphical client started.");
        result.addProperty("alpha_uuid", alpha.getUUID().toString());
        result.addProperty("beta_uuid", beta.getUUID().toString());
        result.addProperty("alpha_source_right_click_events_observed", countUses(alpha));
        result.addProperty("beta_source_right_click_events_observed", countUses(beta));
        result.addProperty("other_block_uses", otherUses);
        result.addProperty("alpha_walk_metres", WALK_SOUTH.z - alpha.getZ());
        result.addProperty("beta_walk_metres", WALK_EAST.x - beta.getX());
        result.addProperty("alpha_stopped_at_south_face", contact(alpha, true));
        result.addProperty("beta_stopped_at_east_face", contact(beta, false));
        result.addProperty("destination_sample_count", chest.getItem(0).getCount());
        result.addProperty("owned_tickets_after_observation", PocketService.ownedTicketRequests());
        result.addProperty("return_records_after_observation", ledger.returnCount());
        result.addProperty("fixture_warmed_columns", forced.size());
        release();
        require(target.getForcedChunks().isEmpty(), "Fixture-owned destination column remained forced");
        result.addProperty("forced_columns_after_cleanup", target.getForcedChunks().size());
        result.add("phase_start_ticks", phaseTicks);
        result.addProperty("scope", STILL
                ? "One real allowlisted block state sampled on the server. The corner and closed chest are public illustrations."
                : "Solid illustrated fallback in art.2 running with CE. Normal source-block click and walking, distinct from an active live portal.");
        Files.writeString(dir.resolve("server-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n",
                StandardOpenOption.CREATE_NEW);
        measuring = false;
        writePhase("done");
        Files.writeString(dir.resolve("PASSED"), "Graphical clients and server completed the disposable fallback scenario\n", StandardOpenOption.CREATE_NEW);
        next(Step.STOP);
    }

    private static void release() {
        for (var column : forced) target.setChunkForced(column.x, column.z, false);
        forced.clear();
    }

    private static void publish(Step stage) throws IOException {
        next(stage);
        String name = stage.name().toLowerCase(Locale.ROOT);
        phaseTicks.addProperty(name, started);
        writePhase(name);
    }

    private static void writePhase(String name) throws IOException {
        var tmp = dir.resolve("phase.next");
        Files.writeString(tmp, name + "\n", StandardOpenOption.CREATE_NEW);
        Files.move(tmp, dir.resolve("phase.txt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void next(Step stage) {
        step = stage;
        started = server.getTickCount();
        AnyaStorage.LOG.info("Disposable fallback phase {}", stage);
    }

    private static boolean exists(String name) { return Files.isRegularFile(dir.resolve(name)); }
    private static boolean bothDone() {
        String name = step.name().toLowerCase(Locale.ROOT);
        return exists(name + "-Alpha-done.json") && exists(name + "-Beta-done.json");
    }

    private static void fail(Throwable failure) {
        measuring = false;
        AnyaStorage.LOG.error("Disposable visual server check failed at " + step, failure);
        try {
            Files.writeString(dir.resolve("FAILED-server"), step + " " + failure + "\n", StandardOpenOption.CREATE_NEW);
        } catch (IOException writing) {
            AnyaStorage.LOG.error("Unable to write the failure report", writing);
        } finally {
            step = null;
            try { removeSetupOwner(); } finally {
                try { release(); } finally { server.halt(false); }
            }
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
