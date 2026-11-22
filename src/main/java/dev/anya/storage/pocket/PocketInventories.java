package dev.anya.storage.pocket;

import dev.anya.storage.StorageEntity;
import dev.anya.storage.Transactions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.LockCode;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Physical room inventories. A double contributes the actual 27 slots of each half once. */
final class PocketInventories {
    record Bank(BlockEntity entity, BlockState state, UUID labelledId) {
        Container inventory() { return (Container)entity; }
    }

    static int count(ServerLevel level, PocketLedger.Pocket room) throws IOException {
        return PocketRoomLayout.present(level, room).size() + vanillaPositions(new LoadedPocketBlocks(level), room).size();
    }

    static List<Bank> discover(ServerLevel level, PocketLedger.Pocket room, StorageEntity selected) throws IOException {
        List<Bank> result = new ArrayList<>();
        for (StorageEntity chest : PocketRoomLayout.present(level, room)) {
            if (selected==null || selected==chest)
                result.add(new Bank(chest, chest.getBlockState(), chest.existingStorageIdentity()));
        }
        if (selected!=null) return List.copyOf(result);
        var blocks = new LoadedPocketBlocks(level);
        var positions = vanillaPositions(blocks, room);
        // The configuration accepts at most 64 physical chests. Older worlds could place
        // unrestricted vanilla chests as decoration. Keep them physically usable on overflow.
        if (result.size()+positions.size()>64)
            throw new IOException("This room exceeds the tablet limit of 64 chest blocks. You can still open the chests in person.");
        for (BlockPos pos : positions) {
            BlockState state = blocks.getBlockState(pos);
            ChestBlockEntity chest = availableChest(level, pos);
            if (chest == null) continue;
            ChestType type = state.getValue(ChestBlock.TYPE);
            if (type != ChestType.SINGLE) {
                BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(state));
                if (!positions.contains(otherPos)) continue;
                BlockState other = blocks.getBlockState(otherPos);
                if (other.getValue(ChestBlock.TYPE) != type.getOpposite()
                        || other.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
                        || availableChest(level, otherPos) == null) continue;
            }
            result.add(new Bank(chest, state, null));
        }
        return List.copyOf(result);
    }

    private static TreeSet<BlockPos> vanillaPositions(LoadedPocketBlocks blocks, PocketLedger.Pocket room) throws IOException {
        var positions = new TreeSet<BlockPos>();
        for (int x = room.x() >> 4; x <= (room.x() + 31) >> 4; x++) {
            for (int z = room.z() >> 4; z <= (room.z() + 31) >> 4; z++) {
                var chunk = blocks.chunk(x, z);
                if (chunk == null) throw new IOException("Skygrid storage is still loading.");
                for (var pos : chunk.getBlockEntitiesPos()) {
                    if (PocketRoomLayout.registeredPosition(room, pos) && chunk.getBlockState(pos).is(Blocks.CHEST))
                        positions.add(pos);
                }
            }
        }
        return positions;
    }

    // Called only for positions confirmed to be in an already loaded room chunk.
    static ChestBlockEntity availableChest(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        var entity = level.getBlockEntity(pos);
        if (!(entity instanceof ChestBlockEntity chest) || chest.getType() != BlockEntityType.CHEST
                || chest.isRemoved() || !pos.equals(chest.getBlockPos()) || chest.getLevel() != level
                || !chest.getBlockState().is(Blocks.CHEST) || chest.getContainerSize() != 27
                || !LockCode.NO_LOCK.equals(chest.lockKey) || chest.getLootTable() != null
                || Transactions.quarantined(chest)) return null;
        return chest;
    }

    private PocketInventories() {}
}
