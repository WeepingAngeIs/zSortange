package dev.anya.storage.liveview;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@Mod("anyapositioncheck")
@EventBusSubscriber(modid = "anyapositioncheck")
public final class PositionPrivacyCheck {
    private static final String ART2 = "0a7eb462e4256518b2bed9b52e782b80649a03f4068ebdad0f6742a1a6515ea7";
    private static final JsonObject report = new JsonObject();
    private static final JsonArray deliveries = new JsonArray();
    private static final JsonArray locationChecks = new JsonArray();
    private static MinecraftServer server;
    private static ServerLevel room;
    private static PocketLedger ledger;
    private static Actor owner, guest, visitor;
    private static PocketContent.EntranceEntity entranceA, entranceB;
    private static BlockPos denied, marker;
    private static ClientboundBlockUpdatePacket queuedA;
    private static ItemEntity retainedA;
    private static ChunkPos warmedA;
    private static int queuedTick;
    private static int cancellations, ticks;
    private enum Phase { VISITING_A, LIGHT_A, RETURNING_A, VISITING_B, RETURNING_B }
    private static Phase phase;

    public PositionPrivacyCheck() {
        require("true".equals(System.getenv("GITHUB_ACTIONS")) && System.getProperty("anya.positionPrivacyEvidence") != null,
                "Requires the disposable hosted position check");
    }

    private record Actor(ServerPlayer player, Set<Integer> sent) {
        Actor(ServerPlayer player) { this(player, new HashSet<>()); }
        PocketLedger.Pocket visit() { return PositionTerrainPolicy.visitAtDelivery(server, player, ledger::get); }
        Packet<?> filter(Packet<?> packet) {
            return PositionTerrainPolicy.filterData(room, visit(), packet, sent, ledger::get);
        }
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) throws Exception {
        server = event.getServer();
        if (Boolean.getBoolean("anya.positionRestart")) {
            try { PositionSaveCheck.verify(server); } finally { server.halt(false); }
            return;
        }
        try {
            verifyInputs();
            ExpansionRules.get().pocketStorage = true;
            require(PocketService.enabled(), "Hosted pocket gate missing");
            room = Objects.requireNonNull(PocketService.dimension(server));
            ledger = PocketService.ledger(server);
            require(ledger.pockets().isEmpty(), "Fresh world expected");
            owner = fake("PositionOwner");
            guest = fake("PositionGuest");
            visitor = new Actor(connectedVisitor());
            for (Actor actor : List.of(owner, guest, visitor)) {
                require(!actor.player.hasPermissions(2) && !server.getPlayerList().isOp(actor.player.getGameProfile()), "Actor was op");
            }
            entranceA = home(1);
            entranceB = home(11);
            require(!entranceA.pocket.equals(entranceB.pocket), "Duplicate allocated pockets");
            requestVisit(entranceA, Phase.VISITING_A);
        } catch (Exception | Error failure) {
            server.halt(false);
            throw failure;
        }
    }

    private static Actor fake(String name) {
        return new Actor(new FakePlayer(server.overworld(), new GameProfile(UUID.randomUUID(), name)) {
            @Override public boolean hasPermissions(int ignored) { return false; }
        });
    }

    private static ServerPlayer connectedVisitor() {
        var profile = new GameProfile(UUID.randomUUID(), "PositionVisitor");
        var player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault()) {
            @Override public boolean hasPermissions(int ignored) { return false; }
        };
        var connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public boolean isMemoryConnection() { return true; }
            @Override public void send(Packet<?> packet, PacketSendListener callback, boolean flush) {}
        };
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        return player;
    }

    private static PocketContent.EntranceEntity home(int x) {
        var level = server.overworld();
        BlockPos hubPos = new BlockPos(x, 81, 1), entry = hubPos.offset(2, 0, 0);
        for (int dx = -1; dx <= 5; dx++) {
            for (int z = 0; z <= 5; z++) level.setBlockAndUpdate(new BlockPos(x + dx, 80, z), Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(hubPos, Content.HUB.get().defaultBlockState());
        level.setBlockAndUpdate(entry, PocketContent.ENTRANCE.get().defaultBlockState());
        var hub = (StorageEntity) level.getBlockEntity(hubPos);
        var entrance = (PocketContent.EntranceEntity) level.getBlockEntity(entry);
        hub.owner = entrance.owner = owner.player.getUUID();
        hub.setMember(guest.player.getUUID(), BasePermissions.VIEW);
        hub.setMember(visitor.player.getUUID(), BasePermissions.VISIT);
        hub.setChanged();
        standAt(owner, entry);
        ItemStack remote = new ItemStack(Content.REMOTE.get());
        var data = BagInventory.data(remote);
        data.putString("HubDimension", level.dimension().location().toString());
        data.putLong("HubPos", hubPos.asLong());
        BagInventory.data(remote, data);
        PocketService.link(owner.player, entrance, remote);
        require(entrance.pocket != null && ledger.get(entrance.pocket).stage() == PocketLedger.Stage.RESERVED,
                "Ordinary home link failed");
        return entrance;
    }

    private static void standAt(Actor actor, BlockPos entry) {
        actor.player.setPos(entry.getX() + 0.5, entry.getY(), entry.getZ() + 2.5);
    }

    private static void requestVisit(PocketContent.EntranceEntity entry, Phase next) {
        for (Actor actor : List.of(owner, guest, visitor)) standAt(actor, entry.getBlockPos());
        PocketService.enter(visitor.player, entry);
        require(PocketService.lease(visitor.player.getUUID()) != null, "Visit request failed");
        phase = next;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (phase == null || event.getServer() != server) return;
        try {
            require(++ticks <= 1400, "Timed out in " + phase + ": " + PocketService.status());
            var back = ledger.returning(visitor.player.getUUID());
            switch (phase) {
                case VISITING_A, VISITING_B -> {
                    if (visitor.player.level() != room || back == null) return;
                    boolean first = phase == Phase.VISITING_A;
                    var current = ledger.get(first ? entranceA.pocket : entranceB.pocket);
                    require(current.equals(visitor.visit()) && current.id().equals(back.pocket()), "Missing current physical visit");
                    var lease = PocketService.lease(visitor.player.getUUID());
                    int tickets = PocketService.ownedTicketRequests();
                    require(tickets > 0 && current.stage() == PocketLedger.Stage.READY, "Prepared room must hold visit tickets");
                    if (first) checkRoomA(current); else checkRoomB(current);
                    require(lease.equals(PocketService.lease(visitor.player.getUUID())) && tickets == PocketService.ownedTicketRequests()
                            && back.equals(ledger.returning(visitor.player.getUUID())), "Packet decisions changed visit or return state");
                    if (first) phase = Phase.LIGHT_A;
                    else {
                        PocketService.recover(visitor.player);
                        phase = Phase.RETURNING_B;
                    }
                }
                case LIGHT_A -> {
                    if (!checkChangedLight()) return;
                    denied = null;
                    PocketService.recover(visitor.player);
                    phase = Phase.RETURNING_A;
                }
                case RETURNING_A, RETURNING_B -> {
                    if (visitor.player.level() != server.overworld() || back != null || PocketService.ownedTicketRequests() != 0) return;
                    require(ledger.returnCount() == 0 && visitor.visit() == null && PocketService.lease(visitor.player.getUUID()) == null,
                            "Return did not release the visit");
                    if (phase == Phase.RETURNING_A) requestVisit(entranceB, Phase.VISITING_B);
                    else finish();
                }
            }
        } catch (Exception | Error failure) {
            AnyaStorage.LOG.error("Hosted position check failed during " + phase, failure);
            phase = null;
            try { releaseWarmColumn(); } finally { server.halt(false); }
        }
    }

    @SubscribeEvent
    public static void exactPosition(StorageAccessEvent event) {
        if (denied != null && event.level == room && guest != null && event.player == guest.player && denied.equals(event.position)) {
            cancellations++;
            event.setCanceled(true);
        }
    }

    private static void assertVetoAt(BlockPos position) {
        denied = position;
        int previous = cancellations;
        String reason = Safety.locationReason(guest.player, room, position);
        require("Storage access cancelled by an addon".equals(reason) && cancellations == previous + 1, "No exact addon veto");
        require(Safety.locationReason(guest.player, room, position.east()).isEmpty()
                && Safety.locationReason(owner.player, room, position).isEmpty()
                && Safety.locationReason(visitor.player, room, position).isEmpty(), "Veto leaked to another block or player");
        var observed = new JsonObject();
        observed.addProperty("position", position.asLong());
        observed.addProperty("guest_exact", reason);
        observed.addProperty("guest_neighbor_owner_exact_visitor_exact", "permitted");
        locationChecks.add(observed);
    }

    private static void checkHomeAuthority(PocketLedger.Pocket pocket) {
        for (Actor actor : List.of(guest, owner)) {
            require(actor.visit() == null && PocketService.authorize(actor.player, pocket, PocketLeases.Kind.REMOTE, false,
                    BasePermissions.VIEW).isEmpty(), "Eligible home viewer missing");
            require("Remote terrain has no complete destination visibility check".equals(
                    PositionTerrainPolicy.remoteTerrainReason(actor.player, pocket, id -> false)), "Raw remote gate did not fail closed");
        }
        require(!PocketService.authorize(guest.player, pocket, PocketLeases.Kind.VISIT, true, 0).isEmpty(), "VIEW granted physical entry");
    }

    private static void checkRoomA(PocketLedger.Pocket pocket) {
        checkHomeAuthority(pocket);
        marker = new BlockPos(pocket.x() + 24, 68, pocket.z() + 24);
        var neighbor = marker.east();
        var chunk = room.getChunkAt(marker);
        require(RoomBounds.decoration(24, 68, 24) && chunk.getBlockEntities().isEmpty() && room.isEmptyBlock(marker),
                "Expected an editable ordinary block in a chest-free column");
        require(Safety.locationReason(guest.player, room, marker).isEmpty(), "Control position was already denied");
        room.setBlockAndUpdate(marker, Blocks.CYAN_CONCRETE.defaultBlockState());
        require(room.getBrightness(LightLayer.BLOCK, neighbor) < 15, "Expected an unlit control before changing the source");
        var initial = new ClientboundLevelChunkWithLightPacket(chunk, room.getChunkSource().getLightEngine(), null, null);
        queuedA = new ClientboundBlockUpdatePacket(room, marker);
        queuedTick = ticks;
        require(chunkHadCyan(initial) && queuedA.getBlockState().is(Blocks.CYAN_CONCRETE), "Queued sends lack the initial decoration");
        assertVetoAt(marker);
        require(room.setBlockAndUpdate(marker, Blocks.PURPLE_CONCRETE.defaultBlockState()) && chunk.isUnsaved(),
                "Edited marker did not produce a dirty column");
        require(room.setBlockAndUpdate(neighbor, Blocks.SEA_LANTERN.defaultBlockState()), "Light source did not change");
        checkForActors("queued_full_chunk_with_light", initial, true);
        checkForActors("queued_single_block", queuedA, true);
        var changed = new ClientboundBlockUpdatePacket(room, marker);
        require(changed.getBlockState().is(Blocks.PURPLE_CONCRETE), "Updated packet omitted new block");
        checkForActors("updated_single_block", changed, true);
        var positions = new ShortOpenHashSet();
        positions.add(SectionPos.sectionRelativePos(marker));
        positions.add(SectionPos.sectionRelativePos(neighbor));
        var section = new ClientboundSectionBlocksUpdatePacket(SectionPos.of(marker), positions, chunk.getSection(room.getSectionIndex(marker.getY())));
        var sent = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        section.runUpdates((pos, state) -> sent.put(pos.immutable(), state));
        require(sent.size() == 2 && sent.get(marker).is(Blocks.PURPLE_CONCRETE) && sent.get(neighbor).is(Blocks.SEA_LANTERN),
                "Mixed section lacked the private and control blocks");
        checkForActors("mixed_section", section, true);
        require(chunk.isUnsaved() && room.getBlockState(marker).is(Blocks.PURPLE_CONCRETE), "Refused updates lost pending marker save");
        var columnA = chunk.getPos();
        require(room.setChunkForced(columnA.x, columnA.z, true), "Failed to acquire the one fixture-owned A column");
        warmedA = columnA;
        retainedA = checkEntity(marker);
        var chest = (StorageEntity) room.getBlockEntity(PocketService.chestPositions(pocket).getFirst());
        checkForActors("chest_update", chest.getUpdatePacket(), false);
        checkForActors("chest_lid_event", new ClientboundBlockEventPacket(chest.getBlockPos(), Content.CRATE.get(), 1, 1), true);
        checkBundle();
        var halo = new BlockPos(pocket.x() - 16, marker.getY(), pocket.z() + 2);
        checkOutside(pocket, halo, "horizontal_halo");
        checkOutside(pocket, marker.above(40), "vertical_outside_room");
        denied = null;
        require(Safety.locationReason(guest.player, room, marker).isEmpty(), "Point did not reopen after veto removal");
        checkForActors("veto_lifted_ordinary_remote_still_closed", changed, true);
        var hub = PocketService.home(owner.player, pocket);
        hub.removeMember(guest.player.getUUID());
        require(!PocketService.authorize(guest.player, pocket, PocketLeases.Kind.REMOTE, false, BasePermissions.VIEW).isEmpty()
                && PocketService.authorize(owner.player, pocket, PocketLeases.Kind.REMOTE, false, BasePermissions.VIEW).isEmpty()
                && pocket.equals(visitor.visit()), "Guest revocation affected a different actor");
        checkForActors("revoked_guest_other_roles_preserved", changed, true);
        hub.setMember(guest.player.getUUID(), BasePermissions.VIEW);
        assertVetoAt(marker);
        report.addProperty("pocket_a", pocket.id().toString());
        report.addProperty("marker_position", marker.asLong());
        report.addProperty("dirty_before_and_after_distribution", true);
    }

    private static boolean checkChangedLight() {
        BlockPos emitter = marker.east();
        if (room.getBrightness(LightLayer.BLOCK, emitter) != 15) return false;
        var engine = room.getChunkSource().getLightEngine();
        var light = new ClientboundLightUpdatePacket(new ChunkPos(marker), engine, null, null);
        var data = light.getLightData();
        int index = (emitter.getY() >> 4) - engine.getMinLightSection();
        var mask = data.getBlockYMask();
        if (!mask.get(index)) return false;
        var layer = new DataLayer(data.getBlockUpdates().get(mask.get(0, index).cardinality()));
        if (layer.get(emitter.getX() & 15, emitter.getY() & 15, emitter.getZ() & 15) != 15) return false;
        require(ledger.get(entranceA.pocket).equals(visitor.visit()), "Visitor left before real lighting updated");
        checkHomeAuthority(ledger.get(entranceA.pocket));
        checkForActors("changed_block_light_real_nibble_15", light, true);
        return true;
    }

    private static boolean chunkHadCyan(ClientboundLevelChunkWithLightPacket initial) {
        var payload = initial.getChunkData().getReadBuffer();
        try {
            var section = new LevelChunkSection(room.registryAccess().registryOrThrow(Registries.BIOME));
            for (int i = 0; i <= room.getSectionIndex(marker.getY()); i++) section.read(payload);
            return section.getBlockState(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15).is(Blocks.CYAN_CONCRETE);
        } finally { payload.release(); }
    }

    private static void checkOutside(PocketLedger.Pocket pocket, BlockPos outside, String name) {
        require(!pocket.contains(outside.getX(), outside.getY(), outside.getZ())
                && PositionTerrainPolicy.covers(pocket, new ChunkPos(outside).toLong()), "Expected terrain halo control");
        room.getChunkAt(outside);
        room.setBlockAndUpdate(outside, Blocks.MAGENTA_CONCRETE.defaultBlockState());
        assertVetoAt(outside);
        var block = new ClientboundBlockUpdatePacket(room, outside);
        require(block.getBlockState().is(Blocks.MAGENTA_CONCRETE), "Missing halo marker");
        checkForActors(name, block, true);
        checkForActors(name + "_block_event", new ClientboundBlockEventPacket(outside, Blocks.MAGENTA_CONCRETE, 0, 0), false);
    }

    private static ItemEntity checkEntity(BlockPos pos) {
        var drop = new ItemEntity(room, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, new ItemStack(Items.AMETHYST_SHARD));
        drop.setNoGravity(true);
        require(room.addFreshEntity(drop) && room.getEntity(drop.getId()) == drop, "Expected a real dropped item in the visit");
        var spawn = new ClientboundAddEntityPacket(drop, 0, drop.blockPosition());
        checkForActors("entity_spawn", spawn, true);
        drop.setPos(drop.getX() + 1, drop.getY(), drop.getZ());
        checkForActors("entity_movement", new ClientboundTeleportEntityPacket(drop), true);
        var metadata = drop.getEntityData().getNonDefaultValues();
        require(metadata != null && !metadata.isEmpty(), "Expected real dropped-item metadata");
        checkForActors("entity_metadata", new ClientboundSetEntityDataPacket(drop.getId(), metadata), true);
        return drop;
    }

    private static void removeEntity(ItemEntity drop) {
        var removal = new ClientboundRemoveEntitiesPacket(drop.getId(), Integer.MAX_VALUE);
        require(PositionTerrainPolicy.removeDelivered(removal, guest.sent) == null && PositionTerrainPolicy.removeDelivered(removal, owner.sent) == null,
                "Undelivered entity generated remote cleanup");
        var delivered = PositionTerrainPolicy.removeDelivered(removal, visitor.sent);
        require(delivered != null && delivered.getEntityIds().size() == 1 && delivered.getEntityIds().getInt(0) == drop.getId()
                && PositionTerrainPolicy.removeDelivered(removal, visitor.sent) == null, "Unpairing did not remove exactly the delivered entity once");
        drop.discard();
    }

    private static void checkBundle() {
        var homeTime = new ClientboundSetTimePacket(10, 20, true);
        var bundle = new ClientboundBundlePacket(List.of(homeTime, queuedA));
        var filtered = PositionTerrainPolicy.filterBundle(bundle, part -> part == homeTime ? part : guest.filter(part));
        require(filtered != null, "Expected a source-world bundle survivor");
        var parts = filtered.subPackets().iterator();
        require(parts.hasNext() && parts.next() == homeTime && !parts.hasNext(), "Mixed source + pocket bundle released private terrain");
        require(PositionTerrainPolicy.filterBundle(new ClientboundBundlePacket(List.of(queuedA)), guest::filter) == null,
                "An all-denied bundle survived");
        report.addProperty("source_time_survives_mixed_vanilla_bundle", true);
    }

    private static void checkRoomB(PocketLedger.Pocket pocket) {
        checkHomeAuthority(pocket);
        checkForActors("old_a_block_during_b_visit", queuedA, false);
        require(room.getEntity(retainedA.getId()) == retainedA, "A control entity missing during B visit");
        checkForActors("a_spawn_during_b_visit", new ClientboundAddEntityPacket(retainedA, 0, retainedA.blockPosition()), false);
        retainedA.setPos(retainedA.getX(), retainedA.getY() + 1, retainedA.getZ());
        checkForActors("a_movement_during_b_visit", new ClientboundTeleportEntityPacket(retainedA), false);
        var aChest = PocketService.chestPositions(ledger.get(entranceA.pocket)).getFirst();
        checkForActors("a_chest_event_during_b_visit", new ClientboundBlockEventPacket(aChest, Content.CRATE.get(), 1, 1), false);
        removeEntity(retainedA);
        releaseWarmColumn();
        var pos = new BlockPos(pocket.x() + 24, 68, pocket.z() + 24);
        require(room.setBlockAndUpdate(pos, Blocks.ORANGE_CONCRETE.defaultBlockState()), "B control block missing");
        checkForActors("current_b_block_during_b_visit", new ClientboundBlockUpdatePacket(room, pos), true);
        removeEntity(checkEntity(pos));
        report.addProperty("pocket_b", pocket.id().toString());
    }

    private static void releaseWarmColumn() {
        if (warmedA != null) {
            require(room.setChunkForced(warmedA.x, warmedA.z, false), "Fixture column was not held");
            warmedA = null;
        }
    }

    private static void checkForActors(String family, Packet<?> packet, boolean visitorAllowed) {
        Objects.requireNonNull(packet, family);
        require(guest.filter(packet) == null && owner.filter(packet) == null, family + " reached a home player");
        require(visitor.filter(packet) == (visitorAllowed ? packet : null), family + " physical delivery mismatch");
        var result = new JsonObject();
        result.addProperty("case", family);
        result.addProperty("packet", packet.getClass().getSimpleName());
        result.addProperty("decision_tick", ticks);
        result.addProperty("prepared_tick", family.startsWith("queued_") || packet == queuedA ? queuedTick : ticks);
        result.addProperty("current_visit", visitor.visit().id().toString());
        result.addProperty("home_guest", "denied");
        result.addProperty("home_owner", "denied");
        result.addProperty("visit", visitorAllowed ? "allowed" : "denied");
        deliveries.add(result);
    }

    private static void finish() throws Exception {
        standAt(guest, entranceA.getBlockPos());
        require(PocketService.authorize(guest.player, ledger.get(entranceA.pocket), PocketLeases.Kind.REMOTE, false,
                BasePermissions.VIEW).isEmpty(), "Expected an otherwise eligible remote actor for save-time refusal");
        PositionSaveCheck.save(server, room, ledger.get(entranceA.pocket), marker, guest.player);
        require(room.getForcedChunks().isEmpty(), "Pocket dimension has a remaining forced column after the check");
        report.add("connection_cleanup", PositionConnectionCheck.check());
        report.add("decoded_packet_decisions", deliveries);
        report.add("exact_position_controls", locationChecks);
        report.addProperty("exact_addon_vetoes_observed", cancellations);
        report.addProperty("dimension", room.dimension().location().toString());
        report.addProperty("guest_uuid", guest.player.getUUID().toString());
        report.addProperty("owner_uuid", owner.player.getUUID().toString());
        report.addProperty("visitor_uuid", visitor.player.getUUID().toString());
        report.addProperty("fixture_forced_columns_after_checks", room.getForcedChunks().size());
        report.addProperty("current_lease_and_return_marker_preserved_in_both_visits", true);
        report.addProperty("tickets_and_return_markers_after_last_return", 0);
        report.addProperty("scope", "Dedicated server, real generated rooms and normal Visit/return, one registered embedded player and two home FakePlayers. Decoded packet policy calls, no CE client or external transport, rendering, fault injection or receipt replay.");
        Path output = Path.of(System.getProperty("anya.positionPrivacyEvidence"));
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n", StandardOpenOption.CREATE_NEW);
        phase = null;
        server.halt(false);
    }

    private static void verifyInputs() throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        var hex = HexFormat.of();
        Path mod = ModList.get().getModFileById("anyastorage").getFile().getFilePath();
        String base = hex.formatHex(hash.digest(Files.readAllBytes(mod)));
        require(ART2.equals(base), "Loaded mod differs from pinned art.2");
        report.addProperty("loaded_storage_sha256", base);
        try (ZipFile adapter = new ZipFile(System.getProperty("anya.expectedAdapter"))) {
            var classes = new JsonObject();
            for (Class<?> type : List.of(PositionTerrainPolicy.class, PocketChestPrivacy.class, PositionDeliveryLedger.class)) {
                String name = type.getName().replace('.', '/') + ".class";
                try (InputStream running = type.getResourceAsStream("/" + name);
                     InputStream original = adapter.getInputStream(adapter.getEntry(name))) {
                    byte[] bytes = Objects.requireNonNull(running).readAllBytes();
                    require(Arrays.equals(bytes, original.readAllBytes()), "Wrong runtime bytecode for " + name);
                    classes.addProperty(name, hex.formatHex(hash.digest(bytes)));
                }
            }
            report.add("adapter_class_sha256", classes);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
