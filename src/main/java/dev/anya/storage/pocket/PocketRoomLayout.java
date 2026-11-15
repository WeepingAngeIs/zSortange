package dev.anya.storage.pocket;

import dev.anya.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.io.IOException;
import java.util.*;

/** Bounded discovery of four original chests plus four optional pads. Never scans arbitrary machinery. */
public final class PocketRoomLayout {
    public static List<BlockPos> pads(PocketLedger.Pocket r){
        return RoomBounds.PAD_X.stream().map(x->new BlockPos(r.x()+x,65,r.z()+14)).toList();
    }
    public static boolean registeredPosition(PocketLedger.Pocket r,BlockPos p){
        return PocketService.chestPositions(r).contains(p)||pads(r).contains(p);
    }
    public static List<StorageEntity> present(ServerLevel level,PocketLedger.Pocket r)throws IOException{
        if(level==null||r.stage()!=PocketLedger.Stage.READY)throw new IOException("Pocket is not ready");
        List<StorageEntity> found=new ArrayList<>();Set<UUID> identities=new HashSet<>();
        List<BlockPos> positions=new ArrayList<>(PocketService.chestPositions(r));positions.addAll(pads(r));
        for(int i=0;i<positions.size();i++){
            BlockPos p=positions.get(i);
            if(!level.hasChunkAt(p))throw new IOException("Registered chest chunk is not loaded");
            if(i>=4&&level.isEmptyBlock(p))continue;
            if(!(level.getBlockEntity(p) instanceof StorageEntity c)||c.isRemoved()||!r.id().equals(c.pocketId)||!r.owner().equals(c.owner)||!c.kind().equals("chest")||c.getContainerSize()!=54||Transactions.quarantined(c))throw new IOException("Registered pocket chest changed; no regeneration attempted");
            if(!identities.add(c.storageIdentity()))throw new IOException("Duplicate physical chest identity; access refused");
            found.add(c);
        }
        return List.copyOf(found);
    }
    private PocketRoomLayout(){}
}
