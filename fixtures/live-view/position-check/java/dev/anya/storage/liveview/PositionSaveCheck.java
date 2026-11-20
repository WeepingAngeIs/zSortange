package dev.anya.storage.liveview;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.anya.storage.pocket.PocketLedger;
import dev.anya.storage.pocket.PocketService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkDataEvent;

/** Checks a clean save in the disposable world and reads its primary record on the next boot. */
@EventBusSubscriber(modid = "anyapositioncheck")
public final class PositionSaveCheck {
    private static Consumer<ChunkDataEvent.Save> observation;

    @SubscribeEvent
    public static void observeSave(ChunkDataEvent.Save event) {
        if (observation != null) observation.accept(event);
    }
    private static Path record(String name) {
        return Path.of(System.getProperty("anya.positionPrivacyEvidence")).resolveSibling(name);
    }

    private static String ledgerHash(MinecraftServer server) throws Exception {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static void write(String name, JsonObject data) throws Exception {
        Path target = record(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(data) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    static void save(MinecraftServer server, ServerLevel room, PocketLedger.Pocket pocket, BlockPos marker,
                     ServerPlayer remoteViewer)
            throws Exception {
        var ledger = PocketService.ledger(server);
        if (ledger.pockets().size() != 2 || !pocket.equals(ledger.get(pocket.id()))
                || pocket.stage() != PocketLedger.Stage.READY || !pocket.contains(marker.getX(), marker.getY(), marker.getZ())) {
            throw new IllegalStateException("Expected marker in the first of two recorded test rooms");
        }
        String material = BuiltInRegistries.BLOCK.getKey(room.getBlockState(marker).getBlock()).toString();
        if (!material.equals("minecraft:purple_concrete")) throw new IllegalStateException("Final marker differs");
        // Observe the real serialized chunk immediately before vanilla submits its primary write.
        var prepared = new ClientboundBlockUpdatePacket(room, marker);
        var column = room.getChunkAt(marker);
        long revision = ledger.revision();
        int tickets = PocketService.ownedTicketRequests();
        boolean[] observed = {false};
        observation = event -> {
            if (event.getLevel() != room || !event.getChunk().getPos().equals(column.getPos())) return;
            var visit = PositionTerrainPolicy.visitAtDelivery(server, remoteViewer, ledger::get);
            if (!server.isSameThread() || visit != null || !material.equals(materialAt(event.getData(), marker))
                    || PositionTerrainPolicy.filterData(room, visit, prepared, new HashSet<>(), ledger::get) != null
                    || revision != ledger.revision() || tickets != PocketService.ownedTicketRequests()) {
                throw new IllegalStateException("Save-time remote refusal or ownership check failed");
            }
            observed[0] = true;
        };
        try {
            column.setUnsaved(true);
            server.saveEverything(true, true, true);
        } finally {
            observation = null;
        }
        if (!observed[0]) throw new IllegalStateException("Marker column did not reach the save-time check");
        JsonObject oracle = new JsonObject();
        oracle.addProperty("dimension", room.dimension().location().toString());
        oracle.addProperty("pocket", pocket.id().toString());
        oracle.addProperty("owner", pocket.owner().toString());
        oracle.addProperty("epoch", pocket.epoch());
        oracle.addProperty("marker", marker.asLong());
        oracle.addProperty("material", material);
        oracle.addProperty("ledger_sha256", ledgerHash(server));
        oracle.addProperty("return_count", ledger.returnCount());
        oracle.addProperty("raw_update_refused_during_primary_save", true);
        write("position-save-oracle.json", oracle);
    }

    static void verify(MinecraftServer server) throws Exception {
        JsonObject expected = JsonParser.parseString(Files.readString(record("position-save-oracle.json"))).getAsJsonObject();
        if (!ledgerHash(server).equals(expected.get("ledger_sha256").getAsString())) {
            throw new IllegalStateException("Pocket ledger changed between the clean save and restart");
        }
        var ledger = PocketService.ledger(server);
        var pocket = ledger.get(UUID.fromString(expected.get("pocket").getAsString()));
        if (pocket == null || !pocket.owner().toString().equals(expected.get("owner").getAsString())
                || pocket.stage() != PocketLedger.Stage.READY || pocket.epoch() != expected.get("epoch").getAsLong()
                || ledger.pockets().size() != 2 || ledger.returnCount() != expected.get("return_count").getAsInt()) {
            throw new IllegalStateException("Recorded pocket identity or return obligations changed");
        }
        ServerLevel room = PocketService.dimension(server);
        if (room == null || !room.dimension().location().toString().equals(expected.get("dimension").getAsString())) {
            throw new IllegalStateException("Recorded dimension unavailable");
        }
        BlockPos marker = BlockPos.of(expected.get("marker").getAsLong());
        ChunkPos column = new ChunkPos(marker);
        if (room.getChunkSource().getChunkNow(column.x, column.z) != null) {
            throw new IllegalStateException("Marker column was already loaded before independent readback");
        }
        CompoundTag saved = room.getChunkSource().chunkMap.read(column).join().orElseThrow();
        String material = expected.get("material").getAsString();
        if (saved.getInt("xPos") != column.x || saved.getInt("zPos") != column.z
                || !material.equals(materialAt(saved, marker))
                || !material.equals(BuiltInRegistries.BLOCK.getKey(room.getBlockState(marker).getBlock()).toString())) {
            throw new IllegalStateException("Saved marker differs in primary storage or the reopened world");
        }
        JsonObject result = expected.deepCopy();
        result.addProperty("primary_block_before_chunk_load", true);
        result.addProperty("reopened_block_matches", true);
        result.addProperty("ledger_rooms", ledger.pockets().size());
        write("position-restart.json", result);
    }

    private static String materialAt(CompoundTag column, BlockPos marker) {
        for (Tag entry : column.getList("sections", Tag.TAG_COMPOUND)) {
            CompoundTag section = (CompoundTag) entry;
            if (section.getByte("Y") != (marker.getY() >> 4)) continue;
            var states = section.getCompound("block_states");
            var palette = states.getList("palette", Tag.TAG_COMPOUND);
            if (palette.isEmpty()) throw new IllegalStateException("Missing section palette");
            int value = 0;
            if (palette.size() > 1) {
                int bits = Math.max(4, Mth.ceillog2(palette.size()));
                var indices = new SimpleBitStorage(bits, 4096, states.getLongArray("data"));
                int index = ((marker.getY() & 15) << 8) | ((marker.getZ() & 15) << 4) | (marker.getX() & 15);
                value = indices.get(index);
            }
            return palette.getCompound(value).getString("Name");
        }
        throw new IllegalStateException("Saved marker section missing");
    }

    private PositionSaveCheck() {}
}
