package dev.anya.storage.pocket;

import dev.anya.storage.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Pins real chest objects and policy revision. No proxy inventory or duplicate item store exists. */
public final class PocketView {
    private final ServerPlayer player;
    private final UUID id;
    private final long epoch,policy;
    private final PocketLeases.Kind kind;
    private final List<StorageEntity> chests,registry;
    private final InteractionHand hand;
    private final ItemStack tablet;
    private final CustomData binding;
    private final PocketLedger.Anchor anchor;
    private final long visitDeadline;
    static PocketView localTablet(ServerPlayer player, PocketLedger.Pocket room, InteractionHand hand) throws java.io.IOException {
        return new PocketView(player,room,PocketLeases.Kind.VISIT,null,hand);
    }
    public PocketView(ServerPlayer player,PocketLedger.Pocket pocket,PocketLeases.Kind kind)throws java.io.IOException{this(player,pocket,kind,null);}
    public PocketView(ServerPlayer player,PocketLedger.Pocket pocket,PocketLeases.Kind kind,StorageEntity selected)throws java.io.IOException{
        this(player,pocket,kind,selected,null);
    }
    private PocketView(ServerPlayer player,PocketLedger.Pocket pocket,PocketLeases.Kind kind,StorageEntity selected,InteractionHand hand)throws java.io.IOException{
        this.player=player;this.id=pocket.id();this.epoch=pocket.epoch();this.kind=kind;
        this.hand=hand;
        tablet=hand==null ? ItemStack.EMPTY : player.getItemInHand(hand);
        binding=tablet.get(DataComponents.CUSTOM_DATA);
        anchor=pocket.anchor();
        var lease=PocketService.lease(player.getUUID());
        visitDeadline=lease==null ? -1 : lease.deadline();
        StorageEntity hub=PocketService.home(player,pocket);if(hub==null)throw new java.io.IOException("Home identity unavailable");policy=hub.profileRevision;
        if(localTablet()){
            var data=BagInventory.data(tablet);
            if(!StorageItem.isTablet(tablet) || !StorageItem.permittedTablet(player,tablet)
                    || !data.hasUUID("PocketId") || !id.equals(data.getUUID("PocketId")) || !data.contains("HubPos",4)
                    || data.getLong("HubPos")!=anchor.hub() || !anchor.dimension().equals(data.getString("HubDimension"))
                    || !CreativeTabletAccess.priorHubBinding(player,tablet,hub))
                throw new java.io.IOException("Use a tablet linked to the room you are visiting.");
        }
        var level=PocketService.dimension(player.getServer());var list=new ArrayList<StorageEntity>();
        if(level==null||pocket.stage()!=PocketLedger.Stage.READY)throw new java.io.IOException("Pocket is not prepared");
        registry=PocketRoomLayout.present(level,pocket);
        for(StorageEntity chest:registry)if(selected==null||selected==chest)list.add(chest);
        if(list.isEmpty())throw new java.io.IOException("Selected chest is not part of this pocket");chests=List.copyOf(list);
        if(!valid())throw new java.io.IOException("Pocket view is no longer authorized");
    }
    public UUID id(){return id;}
    public boolean localTablet(){return hand!=null;}
    public int tabletSlot(){return hand==InteractionHand.MAIN_HAND ? player.getInventory().selected : -1;}
    public List<Container> containers(){return new ArrayList<>(chests);}
    public boolean permits(int rights){
        try{
            var r=PocketService.ledger(player.getServer()).get(id);var l=PocketService.lease(player.getUUID());
            if(r==null||r.epoch()!=epoch||l==null||!l.pocket().equals(id)||l.kind()!=kind||l.epoch()!=epoch||!PocketService.authorize(player,r,kind,false,rights).isEmpty())return false;
            StorageEntity hub=PocketService.home(player,r);if(hub==null||hub.profileRevision!=policy)return false;
            if (localTablet() && !sameLocalVisit(r,l)) return false;
            var level=PocketService.dimension(player.getServer());
            if(!registry.equals(PocketRoomLayout.present(level,r)))return false;
            for(StorageEntity chest:chests){
                if(chest.isRemoved()||!level.hasChunkAt(chest.getBlockPos())||level.getBlockEntity(chest.getBlockPos())!=chest||!id.equals(chest.pocketId)||Transactions.quarantined(chest))return false;
                if(!Safety.locationReason(player,level,chest.getBlockPos()).isEmpty())return false;
            }
            // Claim hooks above can change the held item, current grant or chest registry.
            // Pin all of them again before exposing rows or committing a local transfer.
            return !localTablet() || (sameLocalVisit(r,PocketService.lease(player.getUUID()))
                    && PocketService.ledger(player.getServer()).get(id)==r && PocketService.home(player,r)==hub
                    && hub.profileRevision==policy && hub.permits(player,BasePermissions.VISIT|rights)
                    && registry.equals(PocketRoomLayout.present(level,r)));
        }catch(java.io.IOException|RuntimeException e){return false;}
    }
    private boolean sameLocalVisit(PocketLedger.Pocket room,PocketLeases.Lease lease) {
        return lease!=null && lease.kind()==PocketLeases.Kind.VISIT && id.equals(lease.pocket())
                && lease.deadline()==visitDeadline && lease.epoch()==epoch && anchor.equals(room.anchor())
                && player.getItemInHand(hand)==tablet && tablet.getCount()==1
                && tablet.get(DataComponents.CUSTOM_DATA)==binding && StorageItem.isTablet(tablet)
                && StorageItem.permittedTablet(player,tablet);
    }
    public boolean valid(){return permits(0);}
    public BooleanSupplier guard(int rights){return ()->permits(rights);}
    public void log(BaseActivity.Action action,int count){
        try{var r=PocketService.ledger(player.getServer()).get(id);var home=r==null?null:PocketService.home(player,r);if(home!=null)home.logTransfer(player,action,count);}catch(java.io.IOException ignored){}
    }
    public void close(){if(kind==PocketLeases.Kind.REMOTE)PocketService.closeView(player);}
}
