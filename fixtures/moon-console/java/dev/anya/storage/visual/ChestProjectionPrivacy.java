package dev.anya.storage.visual;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.BasePermissions;
import dev.anya.storage.Packets;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.pocket.PocketService;
import dev.anya.storage.ui.StorageGrid;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Inspects the initial and incremental contents vanilla would send to a disposable guest. */
final class ChestProjectionPrivacy {
    private static final int LATE=49;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError("Chest resync: "+why);}

    static void verify(ServerPlayer owner){
        StorageEntity chest=(StorageEntity)owner.serverLevel().getBlockEntity(new BlockPos(0,80,0));
        check(chest!=null,"Missing physical test chest");
        var guest=new FakePlayer(owner.serverLevel(),new GameProfile(UUID.randomUUID(),"ChestGuest")){
            @Override public boolean hasPermissions(int permission){return false;}
            @Override public OptionalInt openMenu(MenuProvider provider,Consumer<RegistryFriendlyByteBuf> writer){
                // FakePlayer normally discards menus. Keep the real menu produced for this actor.
                containerMenu=provider.createMenu(72,getInventory(),this);
                return OptionalInt.of(containerMenu.containerId);
            }
        };
        guest.setPos(.5,80,2.5);
        chest.setMember(guest.getUUID(),BasePermissions.VIEW);
        try{exercise(guest,chest,()->chest.removeMember(guest.getUUID()));}
        finally{chest.removeMember(guest.getUUID());}
    }

    /** Called by the room-editor fixture with its real second player and active Visit. */
    static void verifyPocket(ServerPlayer owner,ServerPlayer guest,StorageEntity hub)throws Exception{
        BlockPos pos=PocketService.chestPositions(EditorCamera.room(owner)).getFirst();
        EditorCamera.aim(guest,pos,false);
        var chest=(StorageEntity)guest.serverLevel().getBlockEntity(pos);
        hub.setMember(guest.getUUID(),BasePermissions.VISIT|BasePermissions.VIEW);
        exercise(guest,chest,()->hub.setMember(guest.getUUID(),BasePermissions.VISIT));
        check(PocketService.lease(guest.getUUID())!=null,"Closing a single chest must preserve authorized Visit");
    }

    private static void exercise(ServerPlayer guest,StorageEntity chest,Runnable revoke){
        var before=chest.getItem(0).copy();var lateBefore=chest.getItem(LATE).copy();
        var marker=new ItemStack(Items.AMETHYST_SHARD,3);
        marker.set(DataComponents.CUSTOM_NAME,Component.literal("Private guest snapshot"));
        try{
            chest.setItem(0,marker.copy());chest.setItem(LATE,marker.copy());
            StoreMenu.container(guest,chest);
            check(guest.containerMenu instanceof StoreMenu,"Guest could not initially view the chest");
            var menu=(StoreMenu)guest.containerMenu;
            menu.action(new Packets.Action(menu.containerId,menu.session(),menu.revision(),"chest_view","",
                    StorageGrid.chestViewport(0,9,6)));
            var receipt=new Receipt();menu.setSynchronizer(receipt);
            check(receipt.sawBoth && menu.capacity()==54 && menu.shownSlots()==54,"Missing early or extended snapshot slot");
            int oldRevision=menu.revision();
            revoke.run();
            check(!menu.stillValid(guest),"Revoked guest still owns the physical chest menu");
            receipt.denied=true;
            menu.action(new Packets.Action(menu.containerId,menu.session(),oldRevision-1,"slot","",StorageGrid.chestSlot(LATE)));
            check(guest.containerMenu==guest.inventoryMenu,"A stale action left the revoked menu open");
            // Vanilla can request a complete snapshot after input reconciliation. Every subsequent send
            // must also be safe even if a caller still holds the retired menu object.
            menu.broadcastFullState();
            menu.broadcastChanges();
            int delivered=receipt.updates;
            menu.broadcastChanges();
            check(receipt.redactedFull && delivered==receipt.updates && menu.privateView() && menu.capacity()==0
                    && menu.resultCount()==0 && menu.occupiedSlots()==0,"Repeated empty updates or private counts remained");
            check(ItemStack.matches(chest.getItem(0),marker) && ItemStack.matches(chest.getItem(LATE),marker)
                    && guest.getInventory().countItem(Items.AMETHYST_SHARD)==0,"Rejected activity moved an item");
            System.out.println("PASS: revoked physical "+(chest.pocketId==null?"chest":"pocket chest")
                    +" cleared ordinary and extended result slots in the outbound menu snapshot");
        }finally{
            if(guest.containerMenu instanceof StoreMenu)guest.closeContainer();
            chest.setItem(0,before);chest.setItem(LATE,lateBefore);
        }
    }
    private static final class Receipt implements ContainerSynchronizer {
        boolean denied,sawBoth,redactedFull;
        int updates;
        private boolean projected(int id){return id<54 || id>=90;}
        private boolean named(ItemStack stack){return stack.is(Items.AMETHYST_SHARD) && stack.getCount()==3
                && stack.getHoverName().getString().equals("Private guest snapshot");}
        @Override public void sendInitialData(AbstractContainerMenu menu,NonNullList<ItemStack> slots,ItemStack carried,int[] data){
            if(!denied){sawBoth=named(slots.get(0)) && named(slots.get(StorageGrid.chestSlot(LATE)));return;}
            for(int i=0;i<slots.size();i++)if(projected(i))check(slots.get(i).isEmpty(),"Retired full snapshot still contained slot "+i);
            for(int i=6;i<=13;i++)check(data[i]==0,"Retired full snapshot still contained a count or offset");
            check((data[4]&4)!=0,"Private flag absent");
            redactedFull=true;updates++;
        }
        @Override public void sendSlotChange(AbstractContainerMenu menu,int slot,ItemStack stack){
            if(denied && projected(slot)){check(stack.isEmpty(),"Retired slot update exposed content");updates++;}
        }
        @Override public void sendCarriedChange(AbstractContainerMenu menu,ItemStack stack){}
        @Override public void sendDataChange(AbstractContainerMenu menu,int field,int value){if(denied)updates++;}
    }
    private ChestProjectionPrivacy(){}
}
