package dev.anya.storage.pocket;

import dev.anya.storage.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Pins real chest objects and policy revision. No proxy inventory or duplicate item store exists. */
public final class PocketView {
    private final ServerPlayer player;
    private final UUID id;
    private final long epoch,policy;
    private final PocketLeases.Kind kind;
    private final List<StorageEntity> chests,registry;
    public PocketView(ServerPlayer player,PocketLedger.Pocket pocket,PocketLeases.Kind kind)throws java.io.IOException{this(player,pocket,kind,null);}
    public PocketView(ServerPlayer player,PocketLedger.Pocket pocket,PocketLeases.Kind kind,StorageEntity selected)throws java.io.IOException{
        this.player=player;this.id=pocket.id();this.epoch=pocket.epoch();this.kind=kind;
        StorageEntity hub=PocketService.home(player,pocket);if(hub==null)throw new java.io.IOException("Home identity unavailable");policy=hub.profileRevision;
        var level=PocketService.dimension(player.getServer());var list=new ArrayList<StorageEntity>();
        if(level==null||pocket.stage()!=PocketLedger.Stage.READY)throw new java.io.IOException("Pocket is not prepared");
        registry=PocketRoomLayout.present(level,pocket);
        for(StorageEntity chest:registry)if(selected==null||selected==chest)list.add(chest);
        if(list.isEmpty())throw new java.io.IOException("Selected chest is not part of this pocket");chests=List.copyOf(list);
        if(!valid())throw new java.io.IOException("Pocket view is no longer authorized");
    }
    public UUID id(){return id;}
    public List<Container> containers(){return new ArrayList<>(chests);}
    public boolean permits(int rights){
        try{
            var r=PocketService.ledger(player.getServer()).get(id);var l=PocketService.lease(player.getUUID());
            if(r==null||r.epoch()!=epoch||l==null||!l.pocket().equals(id)||l.kind()!=kind||l.epoch()!=epoch||!PocketService.authorize(player,r,kind,false,rights).isEmpty())return false;
            StorageEntity hub=PocketService.home(player,r);if(hub==null||hub.profileRevision!=policy)return false;
            var level=PocketService.dimension(player.getServer());
            if(!registry.equals(PocketRoomLayout.present(level,r)))return false;
            for(StorageEntity chest:chests){
                if(chest.isRemoved()||!level.hasChunkAt(chest.getBlockPos())||level.getBlockEntity(chest.getBlockPos())!=chest||!id.equals(chest.pocketId)||Transactions.quarantined(chest))return false;
                if(!Safety.locationReason(player,level,chest.getBlockPos()).isEmpty())return false;
            }
            return true;
        }catch(java.io.IOException|RuntimeException e){return false;}
    }
    public boolean valid(){return permits(0);}
    public BooleanSupplier guard(int rights){return ()->permits(rights);}
    public void log(BaseActivity.Action action,int count){
        try{var r=PocketService.ledger(player.getServer()).get(id);var home=r==null?null:PocketService.home(player,r);if(home!=null)home.logTransfer(player,action,count);}catch(java.io.IOException ignored){}
    }
    public void close(){PocketService.closeView(player);}
}
