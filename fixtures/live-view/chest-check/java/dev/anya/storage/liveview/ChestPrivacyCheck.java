package dev.anya.storage.liveview;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.anya.storage.Config;
import dev.anya.storage.Content;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.pocket.PocketLedger;
import dev.anya.storage.pocket.PocketService;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.zip.ZipFile;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@Mod("anyachestcheck")
@EventBusSubscriber(modid = "anyachestcheck")
public final class ChestPrivacyCheck {
    private static final String REVIEW_SHA = "0a7eb462e4256518b2bed9b52e782b80649a03f4068ebdad0f6742a1a6515ea7";

    public ChestPrivacyCheck() {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || System.getProperty("anya.chestPrivacyEvidence") == null) {
            throw new IllegalStateException("This check requires its disposable hosted server");
        }
    }

    @SubscribeEvent
    public static void serverStarted(ServerStartedEvent event) throws Exception {
        try {
            checkChestPrivacy(event.getServer());
        } finally {
            event.getServer().halt(false);
        }
    }

    private static void checkChestPrivacy(net.minecraft.server.MinecraftServer server) throws Exception {
        JsonObject report = new JsonObject();
        verifyRuntime(report);
        checkMetadata();

        var pocketA = pocket(0);
        var pocketB = pocket(1);
        checkVisitorBounds(pocketA, pocketB);
        ServerLevel room = PocketService.dimension(server);
        check(room != null, "Missing pocket test dimension");
        Function<UUID, PocketLedger.Pocket> findPocket = id -> id.equals(pocketA.id()) ? pocketA : null;
        BlockPos pos = PocketService.chestPositions(pocketA).getFirst();
        var chunk = room.getChunkAt(pos);
        check(chunk.getBlockEntities().isEmpty() && room.isEmptyBlock(pos), "Expected a fresh, empty column");
        room.setBlockAndUpdate(pos, Content.CRATE.get().defaultBlockState());
        StorageEntity chest = (StorageEntity) room.getBlockEntity(pos);
        check(chest != null, "Missing registered StorageEntity");
        chest.owner = pocketA.owner();
        chest.pocketId = pocketA.id();
        chest.setItem(0, new ItemStack(Items.EMERALD, 11));
        var closed = inspectPacketState(room, chest, findPocket, false, report);
        chest.openViewer();
        check(chest.triggerEvent(1, 1), "Production chest rejected the opening event");
        var opened = inspectPacketState(room, chest, findPocket, true, report);
        check(!chest.triggerEvent(1, 0), "Production chest accepted an obsolete close event");
        chest.closeViewer();
        check(chest.triggerEvent(1, 0), "Production chest rejected the closing event");
        check(!chest.getUpdateTag(room.registryAccess()).getBoolean("LidOpen"), "Chest did not close");
        check(chest.getItem(0).is(Items.EMERALD) && chest.getItem(0).getCount() == 11, "Test stack changed");

        // Replace the disposable chest without dropping items. Its old packet snapshots
        // must still be declined even though the fresh empty column is now acceptable.
        chest.clearContent();
        room.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        check(chunk.getBlockEntities().isEmpty(), "The test chest survived replacement");
        check(PocketChestPrivacy.publicChunk(room, chunkPacket(room, pos), findPocket), "Fresh empty column refused");
        check(!PocketChestPrivacy.publicChunk(room, closed, findPocket), "Queued closed-lid chunk escaped");
        check(!PocketChestPrivacy.publicChunk(room, opened, findPocket), "Queued open-lid chunk escaped");
        report.addProperty("queued_chunk_after_removal", "fresh accepted; prepared closed and open packets refused");
        report.addProperty("physical_visit_scope", "two independent ledger plots; boundary, halo and height assertions passed");
        report.addProperty("dimension", room.dimension().location().toString());
        report.addProperty("scope", "One disposable dedicated server. No CE session, network client, rendered frame or full modpack.");
        Path evidence = Path.of(System.getProperty("anya.chestPrivacyEvidence"));
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    private static ClientboundLevelChunkWithLightPacket inspectPacketState(ServerLevel room, StorageEntity chest,
            Function<UUID, PocketLedger.Pocket> findPocket, boolean open, JsonObject report) {
        BlockPos pos = chest.getBlockPos();
        CompoundTag expected = chest.getUpdateTag(room.registryAccess());
        check(expected.getAllKeys().equals(Set.of("LidOpen", "Symbol", "ShowLabels")), "Unexpected candidate chest metadata");
        check(expected.contains("LidOpen", Tag.TAG_BYTE) && expected.getBoolean("LidOpen") == open, "Unexpected lid state");
        CompoundTag cosmetic = expected.copy();
        cosmetic.remove("LidOpen");
        check(PocketChestPrivacy.publicTag(cosmetic) && !PocketChestPrivacy.publicTag(expected), "Wrong metadata policy");

        var initial = chunkPacket(room, pos);
        AtomicReference<CompoundTag> encodedChest = new AtomicReference<>();
        initial.getChunkData().getBlockEntitiesTagsConsumer(initial.getX(), initial.getZ()).accept((where, type, tag) -> {
            if (where.equals(pos)) {
                check(type == Content.STORAGE.get() && tag != null, "Wrong initial chest type or tag");
                check(encodedChest.getAndSet(tag) == null, "Repeated initial chest record");
            }
        });
        check(expected.equals(encodedChest.get()), "Prepared chunk differs from the real chest metadata");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), room.registryAccess());
        ClientboundBlockEntityDataPacket update;
        try {
            ClientboundBlockEntityDataPacket.STREAM_CODEC.encode(buffer, chest.getUpdatePacket());
            update = ClientboundBlockEntityDataPacket.STREAM_CODEC.decode(buffer);
            check(buffer.readableBytes() == 0, "Incomplete incremental packet round trip");
        } finally {
            buffer.release();
        }
        check(update.getPos().equals(pos) && update.getType() == Content.STORAGE.get()
                && expected.equals(update.getTag()), "Incremental update differs from the real chest");
        check(!PocketChestPrivacy.publicChunk(room, initial, findPocket), "Initial chest packet permitted");
        check(!PocketChestPrivacy.publicUpdate(room, update, findPocket), "Incremental chest packet permitted");
        JsonObject state = new JsonObject();
        state.addProperty("lid_open", expected.getBoolean("LidOpen"));
        state.addProperty("symbol", expected.getString("Symbol"));
        state.addProperty("show_labels", expected.getBoolean("ShowLabels"));
        state.addProperty("chest_stack_count", chest.getItem(0).getCount());
        state.addProperty("initial_and_incremental", "refused");
        report.add(open ? "open_chest" : "closed_chest", state);
        return initial;
    }

    private static ClientboundLevelChunkWithLightPacket chunkPacket(ServerLevel room, BlockPos pos) {
        var packet = new ClientboundLevelChunkWithLightPacket(room.getChunkAt(pos),
                room.getChunkSource().getLightEngine(), null, null);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), room.registryAccess());
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
            var decoded = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
            check(buffer.readableBytes() == 0, "Incomplete initial packet round trip");
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static void checkMetadata() {
        check(PocketChestPrivacy.publicTag(null) && PocketChestPrivacy.publicTag(new CompoundTag()), "Empty metadata refused");
        for (String symbol : Config.SYMBOLS) {
            for (boolean show : List.of(false, true)) {
                var tag = new CompoundTag();
                tag.putString("Symbol", symbol);
                tag.putBoolean("ShowLabels", show);
                check(PocketChestPrivacy.publicTag(tag), "Recognized cosmetic fields refused");
            }
        }
        for (String key : List.of("Items", "Owner", "PocketId", "Activity", "CustomName", "UnknownField")) {
            var privateTag = new CompoundTag();
            privateTag.putString(key, "private");
            check(!PocketChestPrivacy.publicTag(privateTag), "Private or unknown field accepted: " + key);
        }
        var tag = new CompoundTag();
        tag.putString("Symbol", "private text");
        check(!PocketChestPrivacy.publicTag(tag), "Unknown symbol accepted");
        tag.putByte("Symbol", (byte) 1);
        check(!PocketChestPrivacy.publicTag(tag), "Wrong symbol type accepted");
        tag.remove("Symbol");
        tag.putString("ShowLabels", "true");
        check(!PocketChestPrivacy.publicTag(tag), "Wrong label type accepted");
        tag.putByte("ShowLabels", (byte) 2);
        check(!PocketChestPrivacy.publicTag(tag), "Non-boolean label value accepted");
    }

    private static PocketLedger.Pocket pocket(int cell) {
        var anchor = new PocketLedger.Anchor("minecraft:overworld", 0, UUID.randomUUID(), 1);
        return new PocketLedger.Pocket(UUID.randomUUID(), UUID.randomUUID(), cell, anchor,
                "galaxy", PocketLedger.Stage.READY, 0);
    }

    private static void checkVisitorBounds(PocketLedger.Pocket visit, PocketLedger.Pocket view) {
        var floor = new BlockPos(visit.x(), 65, visit.z());
        check(PocketChestPrivacy.visitorAt(visit, floor)
                && PocketChestPrivacy.visitorAt(visit, floor.offset(31, 31, 31)), "Physical visitor lost own room");
        check(!PocketChestPrivacy.visitorAt(visit, new BlockPos(view.x()+6, 65, view.z()+6)), "Visit A granted lid activity in viewed B");
        check(!PocketChestPrivacy.visitorAt(null, floor) && !PocketChestPrivacy.visitorAt(visit, null), "Absent visit or position accepted");
        for (BlockPos outside : List.of(floor.west(), floor.north(), floor.below(),
                floor.east(32), floor.south(32), floor.above(32))) {
            check(!PocketChestPrivacy.visitorAt(visit, outside), "Halo or outside height received visitor activity");
        }
    }

    private static void verifyRuntime(JsonObject report) throws Exception {
        Path storageJar = ModList.get().getModFileById("anyastorage").getFile().getFilePath();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(storageJar)));
        check(REVIEW_SHA.equals(hash), "Running Storage Plus differs from the review binary");
        String resource = PocketChestPrivacy.class.getName().replace('.', '/') + ".class";
        try (ZipFile adapter = new ZipFile(System.getProperty("anya.expectedAdapter"));
             InputStream original = adapter.getInputStream(adapter.getEntry(resource));
             InputStream loaded = PocketChestPrivacy.class.getResourceAsStream("/" + resource)) {
            check(loaded != null && Arrays.equals(loaded.readAllBytes(), original.readAllBytes()), "Wrong adapter policy bytecode");
        }
        report.addProperty("loaded_storage_sha256", hash);
        report.addProperty("adapter_policy_class", resource);
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new IllegalStateException(message);
    }
}
