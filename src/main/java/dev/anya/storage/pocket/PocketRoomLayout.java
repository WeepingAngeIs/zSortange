package dev.anya.storage.pocket;

import dev.anya.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Discovers physical Labelled Storage Chests in the four already-loaded room chunks. */
public final class PocketRoomLayout {
    public static List<BlockPos> pads(PocketLedger.Pocket r){
        return RoomBounds.PAD_X.stream()
                .map(x -> new BlockPos(r.x() + x, RoomBounds.CHEST_Y, r.z() + RoomBounds.PAD_Z))
                .toList();
    }
    /** Original illustration coordinates, retained for interpreting old layouts and fixtures. */
    static List<BlockPos> positions(PocketLedger.Pocket room) {
        var positions = new ArrayList<>(PocketService.chestPositions(room));
        positions.addAll(pads(room));
        return positions;
    }
    public static boolean registeredPosition(PocketLedger.Pocket r,BlockPos p){
        return RoomBounds.decoration(p.getX()-r.x(),p.getY(),p.getZ()-r.z());
    }
    public static List<StorageEntity> present(ServerLevel level,PocketLedger.Pocket r)throws IOException{
        if(level==null)throw new IOException("Pocket is not ready");
        return scan(r,locations(new LoadedPocketBlocks(level),r),level::hasChunkAt,level::isEmptyBlock,level::getBlockEntity,StorageEntity::storageIdentity);
    }
    static List<BlockPos> locations(LoadedPocketBlocks blocks,PocketLedger.Pocket room)throws IOException{
        var actual=new TreeSet<BlockPos>();
        for(int x=room.x()>>4;x<=(room.x()+31)>>4;x++)for(int z=room.z()>>4;z<=(room.z()+31)>>4;z++){
            var chunk=blocks.chunk(x,z);
            if(chunk==null)throw new IOException("The room is still loading.");
            for(var pos:chunk.getBlockEntitiesPos()){
                if(registeredPosition(room,pos)&&blocks.getBlockState(pos).is(Content.CRATE.get()))actual.add(pos);
            }
        }
        // Preserve legacy storage ordering wherever the original four are still physically present.
        var ordered=new ArrayList<BlockPos>();
        for(var legacy:PocketService.chestPositions(room))if(actual.remove(legacy))ordered.add(legacy);
        ordered.addAll(actual);
        return List.copyOf(ordered);
    }
    static List<StorageEntity> present(PocketLedger.Pocket r, Predicate<BlockPos> loaded,
            Predicate<BlockPos> empty, Function<BlockPos,BlockEntity> entityAt)throws IOException{
        return scan(r,positions(r),loaded,empty,entityAt,StorageEntity::storageIdentity);
    }

    /** Verify the loaded layout for display without assigning identities or materializing block entities. */
    static List<StorageEntity> observe(LoadedPocketBlocks blocks, PocketLedger.Pocket room) throws IOException {
        var chests = scan(room, locations(blocks,room), pos -> blocks.chunk(pos.getX() >> 4, pos.getZ() >> 4) != null,
                pos -> blocks.getBlockState(pos).isAir() && blocks.getFluidState(pos).isEmpty(),
                blocks::getBlockEntity, StorageEntity::existingStorageIdentity);
        for (var chest : chests) {
            if (!blocks.getBlockState(chest.getBlockPos()).is(Content.CRATE.get())
                    || room.anchor().hubId().equals(chest.existingStorageIdentity()))
                throw new IOException("Registered pocket chest does not match the room layout");
        }
        if (!blocks.complete()) throw new IOException("Registered pocket chest layout is not fully loaded");
        return chests;
    }

    private static List<StorageEntity> scan(PocketLedger.Pocket r,List<BlockPos> positions, Predicate<BlockPos> loaded,
            Predicate<BlockPos> empty, Function<BlockPos,BlockEntity> entityAt,
            Function<StorageEntity,UUID> identityOf)throws IOException{
        if(r.stage()!=PocketLedger.Stage.READY)throw new IOException("Pocket is not ready");
        List<StorageEntity> found=new ArrayList<>();Set<UUID> identities=new HashSet<>();
        for(int i=0;i<positions.size();i++){
            BlockPos p=positions.get(i);
            if(!loaded.test(p))throw new IOException("Registered chest chunk is not loaded");
            if(empty.test(p)){
                if(entityAt.apply(p)!=null)throw new IOException("Empty registered pad still has a block entity");
                continue;
            }
            if(!(entityAt.apply(p) instanceof StorageEntity c)||!p.equals(c.getBlockPos())||c.isRemoved()||!r.id().equals(c.pocketId)||!r.owner().equals(c.owner)||!c.kind().equals("chest")||c.getContainerSize()!=54||Transactions.quarantined(c))throw new IOException("Registered pocket chest changed; no regeneration attempted");
            UUID identity = identityOf.apply(c);
            if(identity==null)throw new IOException("Registered pocket chest identity is unavailable");
            if(!identities.add(identity))throw new IOException("Duplicate physical chest identity; access refused");
            found.add(c);
        }
        return List.copyOf(found);
    }
    private PocketRoomLayout(){}
}
