package dev.anya.storage.visual;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.BasePermissions;
import dev.anya.storage.Content;
import dev.anya.storage.Packets;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.pocket.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Second registered server player with a transport sink, as in the dedicated pocket smoke fixture. */
final class EditorGuest {
    private static ServerPlayer guest;
    private static StorageEntity hub;
    private static EmbeddedChannel channel;
    static void join(ServerPlayer owner) {
        var server=owner.getServer();var home=server.overworld();
        var profile=new GameProfile(UUID.randomUUID(),"EditorGuest");
        guest=new ServerPlayer(server,home,profile,ClientInformation.createDefault()){
            @Override public boolean hasPermissions(int level){return false;}
        };
        var connection=new Connection(PacketFlow.SERVERBOUND){
            @Override public boolean isMemoryConnection(){return true;}
            @Override public void send(Packet<?> packet, PacketSendListener callback, boolean flush){}
        };
        channel=new EmbeddedChannel(connection);NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection,guest,CommonListenerCookie.createInitial(profile,false));
        guest.setGameMode(GameType.SURVIVAL);
        hub=(StorageEntity)home.getBlockEntity(EditorCamera.HUB);
        hub.setMember(guest.getUUID(),BasePermissions.VISIT);
        EditorCamera.aim(guest,EditorCamera.ENTRANCE,false);
        PocketService.enter(guest,(PocketContent.EntranceEntity)home.getBlockEntity(EditorCamera.ENTRANCE));
    }
    static void exercise(ServerPlayer owner)throws Exception {
        EditorCamera.check(PocketService.inPocket(guest.level())&&guest.serverLevel()==owner.serverLevel(),"Second player physically arrived");
        var tablet=owner.getInventory().getItem(0).copy();
        guest.getInventory().selected=0;
        guest.getInventory().setItem(0,tablet);
        var denied=privatePanel(tablet,false);
        denied.action(new Packets.Action(denied.containerId,denied.session(),denied.revision(),"room_edit","",1));
        EditorCamera.check(!RoomEditor.active(guest),"Visit alone cannot enable editing");
        guest.closeContainer();
        hub.setMember(guest.getUUID(),BasePermissions.VISIT|BasePermissions.BUILD);
        var allowed=privatePanel(tablet,true);
        allowed.action(new Packets.Action(allowed.containerId,allowed.session(),allowed.revision(),"room_edit","",1));
        EditorCamera.check(RoomEditor.active(guest)&&guest.containerMenu==guest.inventoryMenu,"Explicit guest Build grant");
        var pad=EditorCamera.world(EditorCamera.PAD);
        guest.getInventory().selected=0;
        var crate=new ItemStack(Content.CRATE.get(),2);
        guest.getInventory().setItem(0,crate);
        EditorCamera.aim(guest,pad.below(),true);
        var hit=RoomEditor.looking(guest);
        EditorCamera.check(hit.getBlockPos().equals(pad.below())&&hit.getDirection()==Direction.UP,"Guest aimed at real optional pad");
        guest.gameMode.useItemOn(guest,guest.serverLevel(),crate,InteractionHand.MAIN_HAND,hit);
        EditorCamera.check(guest.serverLevel().isEmptyBlock(pad)&&guest.getInventory().countItem(Content.CRATE.get().asItem())==2,
                "Build without storage rights cannot spend a chest");
        var target=EditorCamera.world(EditorCamera.DECOR);
        EditorCamera.aim(guest,target.below(),true);
        hit=RoomEditor.looking(guest);
        var quartz=new ItemStack(Items.QUARTZ_BLOCK,2);guest.getInventory().setItem(0,quartz);
        hub.setMember(guest.getUUID(),BasePermissions.VISIT);
        guest.gameMode.useItemOn(guest,guest.serverLevel(),quartz,InteractionHand.MAIN_HAND,hit);
        EditorCamera.check(guest.serverLevel().isEmptyBlock(target)&&guest.getInventory().countItem(Items.QUARTZ_BLOCK)==2,
                "Revoked Build refuses next input before spending material");
        hub.removeMember(guest.getUUID());
    }
    private static StoreMenu privatePanel(ItemStack tablet,boolean allowed) {
        guest.gameMode.useItem(guest,guest.serverLevel(),tablet,InteractionHand.MAIN_HAND);
        EditorCamera.check(guest.containerMenu instanceof StoreMenu,"Linked ordinary tablet opens for guest");
        var menu=(StoreMenu)guest.containerMenu;
        EditorCamera.check(menu.localRoomPanel() && menu.privateView() && menu.capacity()==0 && menu.resultCount()==0
                && menu.roomEditAllowed()==allowed && !menu.roomEditActive(),"Private panel must hide chest data while reflecting Build");
        return menu;
    }
    static void finish(ServerPlayer owner)throws Exception {
        EditorCamera.check(!RoomEditor.active(guest)&&!PocketService.inPocket(guest.level())&&PocketService.lease(guest.getUUID())==null
                &&PocketService.ledger(owner.getServer()).returning(guest.getUUID())==null,"Revoked visitor safely returned");
        owner.getServer().getPlayerList().remove(guest);
        channel.finishAndReleaseAll();
    }
    private EditorGuest(){}
}
