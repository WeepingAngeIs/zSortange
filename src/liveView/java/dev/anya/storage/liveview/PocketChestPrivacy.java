package dev.anya.storage.liveview;

import dev.anya.storage.Config;
import dev.anya.storage.Content;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.pocket.PocketLedger;
import dev.anya.storage.pocket.PocketRoomLayout;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;

final class PocketChestPrivacy {
    private static final Set<String> PUBLIC_KEYS = Set.of("Symbol", "ShowLabels");

    static boolean publicTag(CompoundTag tag) {
        if (tag == null) return true;
        if (!PUBLIC_KEYS.containsAll(tag.getAllKeys())) return false;
        if (tag.contains("Symbol") && (!tag.contains("Symbol", Tag.TAG_STRING)
                || !Config.SYMBOLS.contains(tag.getString("Symbol")))) return false;
        if (!tag.contains("ShowLabels")) return true;
        if (!tag.contains("ShowLabels", Tag.TAG_BYTE)) return false;
        byte visible = tag.getByte("ShowLabels");
        return visible == 0 || visible == 1;
    }

    static boolean visitorAt(PocketLedger.Pocket visit, BlockPos position) {
        return visit != null && position != null
                && visit.contains(position.getX(), position.getY(), position.getZ());
    }

    static boolean publicChunk(ServerLevel room, ClientboundLevelChunkWithLightPacket packet,
                               Function<UUID, PocketLedger.Pocket> findPocket) {
        LevelChunk chunk = room == null ? null : room.getChunkSource().getChunkNow(packet.getX(), packet.getZ());
        if (chunk == null) return false;
        for (BlockPos pos : chunk.getBlockEntities().keySet()) {
            if (!currentStorage(room, chunk, pos, findPocket)) return false;
        }
        // A queued snapshot can outlive a block entity. Check the transmitted entries too.
        boolean[] safe = {true};
        packet.getChunkData().getBlockEntitiesTagsConsumer(packet.getX(), packet.getZ()).accept((pos, type, tag) -> {
            if (safe[0] && !publicEntry(room, chunk, pos, type, tag, findPocket)) safe[0] = false;
        });
        return safe[0];
    }

    static boolean publicUpdate(ServerLevel room, ClientboundBlockEntityDataPacket packet,
                                Function<UUID, PocketLedger.Pocket> findPocket) {
        BlockPos pos = packet.getPos();
        LevelChunk chunk = room == null ? null : room.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && publicEntry(room, chunk, pos, packet.getType(), packet.getTag(), findPocket);
    }

    private static boolean publicEntry(ServerLevel room, LevelChunk chunk, BlockPos pos, BlockEntityType<?> type,
                                       CompoundTag tag, Function<UUID, PocketLedger.Pocket> findPocket) {
        return type == Content.STORAGE.get() && publicTag(tag) && currentStorage(room, chunk, pos, findPocket);
    }

    private static boolean currentStorage(ServerLevel room, LevelChunk chunk, BlockPos pos,
                                          Function<UUID, PocketLedger.Pocket> findPocket) {
        // Reading the live map never creates a missing block entity or advances a chunk.
        if (!(chunk.getBlockEntities().get(pos) instanceof StorageEntity storage) || storage.isRemoved()
                || storage.getLevel() != room || !pos.equals(storage.getBlockPos())
                || storage.pocketId == null || !chunk.getBlockState(pos).is(Content.CRATE.get())) return false;
        var pocket = findPocket.apply(storage.pocketId);
        return pocket != null && storage.pocketId.equals(pocket.id()) && pocket.owner().equals(storage.owner)
                && PocketRoomLayout.registeredPosition(pocket, pos)
                && publicTag(storage.getUpdateTag(room.registryAccess()));
    }

    private PocketChestPrivacy() {}
}
