package dev.anya.storage.pocket;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;

/** One observation on the server thread. Never waits for a chunk or constructs a block entity. */
final class LoadedPocketBlocks implements BlockGetter {
    private final ServerLevel level;
    private final Map<Long,LevelChunk> chunks = new HashMap<>();
    private boolean missing;

    LoadedPocketBlocks(ServerLevel level) {
        this.level = level;
    }

    LevelChunk chunk(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        LevelChunk chunk = chunks.get(key);
        if (chunk != null) return chunk;
        var holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(key);
        if (holder != null) {
            chunk = holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
        }
        if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.FULL)) {
            missing = true;
            return null;
        }
        chunks.put(key, chunk);
        return chunk;
    }

    boolean complete() { return !missing; }

    boolean fullPlot(PocketLedger.Pocket pocket) {
        // The four 16x16 build chunks cover the complete 32x32 plot, including its far half.
        for (int x = pocket.x() >> 4; x <= (pocket.x() + 31) >> 4; x++) {
            for (int z = pocket.z() >> 4; z <= (pocket.z() + 31) >> 4; z++) {
                if (chunk(x, z) == null) return false;
            }
        }
        return true;
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        var chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? Blocks.BARRIER.defaultBlockState() : chunk.getBlockState(pos);
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        var chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        var entity = chunk.getBlockEntities().get(pos);
        if (entity == null && chunk.getBlockState(pos).hasBlockEntity()) missing = true;
        return entity;
    }

    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public int getHeight() { return level.getHeight(); }
    @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
}
