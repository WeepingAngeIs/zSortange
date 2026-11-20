package dev.anya.storage.acceptance;

import dev.anya.storage.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.*;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Runs a real Minecraft client. Fixture commands arrive over a real localhost TCP connection. */
@EventBusSubscriber(modid=Acceptance.ID,value=Dist.CLIENT)
public final class ClientProbe {
    private static boolean connected;
    private static Wire.Signal work;
    private static int ticks,age,readyTicks,waitingTicks;
    private static int expectedOffset=-1,viewportRequestedAt,viewportAttempts,withdrawSentAt=-1;
    private static int depositRevision=-1,depositSentAt=-1;
    private static Packets.Action saved;
    public static void receive(Wire.Signal s){
        work=s;ticks=0;readyTicks=0;waitingTicks=0;depositRevision=-1;depositSentAt=-1;expectedOffset=-1;viewportRequestedAt=0;viewportAttempts=0;withdrawSentAt=-1;
        AnyaStorage.LOG.info("ANYA_REAL_CLIENT_ACTION {} {}",s.sequence(),s.action());
    }
    private static void ack(){PacketDistributor.sendToServer(new Wire.Signal(work.sequence(),"done"));work=null;}
    private static void shot(String name){var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,"room-"+System.getProperty("anya.role")+"-"+name+".png",mc.getMainRenderTarget(),c->{});}

    /** Scroll only through server-synchronized display slots; send the mutating intent exactly once. */
    private static void withdrawLocatedSample(Minecraft mc){
        Acceptance.require(mc.player.containerMenu instanceof StoreMenu,"Expected production StoreMenu while locating sample");
        StoreMenu m=(StoreMenu)mc.player.containerMenu;
        Acceptance.require(!m.privateView()&&!m.filteredView()&&m.capacity()==216,"Expected authorized unfiltered physical pocket view");
        Acceptance.require(ticks<=360,"Timed out locating synchronized sample; offset="+m.firstSlot());
        if(withdrawSentAt>=0){if(ticks-withdrawSentAt>=40){shot("after-located-withdrawal");ack();}return;}
        if(expectedOffset>=0&&m.firstSlot()!=expectedOffset){
            if(ticks-viewportRequestedAt>=20)requestViewport(m,expectedOffset);
            return;
        }
        // Allow the complete snapshot following a viewport acknowledgement to arrive.
        if(expectedOffset>=0&&ticks-viewportRequestedAt<2)return;
        for(int i=0;i<Math.min(45,m.shownSlots());i++){
            var stack=m.getSlot(i).getItem();
            if(!stack.is(Items.DIAMOND)||!stack.getHoverName().getString().equals("Network conserved diamonds"))continue;
            Acceptance.require(stack.getCount()==17,"Sample changed before the one-item withdrawal");
            // ServerProbe deposits into chest #3, whose first physical slot is 108, not display slot zero.
            Acceptance.require(m.firstSlot()+i==108,"Sample is not in the expected third-chest physical slot");
            shot("located-third-chest-sample");
            AnyaStorage.LOG.info("ANYA_ACCEPTANCE_LOCATED_SAMPLE offset={} display={} physical={} count={} revision={}",m.firstSlot(),i,m.firstSlot()+i,stack.getCount(),m.revision());
            saved=new Packets.Action(m.containerId,m.session(),m.revision(),"slot","",i|256);
            PacketDistributor.sendToServer(saved);withdrawSentAt=ticks;return;
        }
        int next=m.firstSlot()+m.visibleRows()*9;
        int max=dev.anya.storage.ui.StorageGrid.maxStartRow(m.resultCount(),m.visibleRows())*9;
        next=Math.min(next,max);
        Acceptance.require(next>m.firstSlot(),"Named sample absent from all synchronized physical slots");
        requestViewport(m,next);
    }
    /** One deposit, issued on a fresh observed revision; never blindly retries a mutating intent. */
    private static void depositRoomSample(Minecraft mc){
        Acceptance.require(mc.player.containerMenu instanceof StoreMenu,"Room deposit lost its menu");
        StoreMenu m=(StoreMenu)mc.player.containerMenu;
        Acceptance.require(m.capacity()==54&&!m.privateView(),"Expected authorized single added chest");
        var held=mc.player.getInventory().getItem(9);var stored=m.getSlot(0).getItem();
        Acceptance.require(ticks<=180,"Room deposit deadline: inventory="+held.getCount()+" stored="+stored.getCount()+" revision="+m.revision());
        if(depositSentAt<0){
            Acceptance.require(held.is(Items.EMERALD)&&held.getCount()==13&&held.getHoverName().getString().equals("Room acceptance emeralds"),"Room deposit input was not synchronized");
            if(depositRevision<0){depositRevision=m.revision();return;}
            if(m.revision()==depositRevision)return;
            PacketDistributor.sendToServer(new Wire.Signal(work.sequence(),"deposit-probe "+m.revision()));
            saved=new Packets.Action(m.containerId,m.session(),m.revision(),"slot","",54);
            PacketDistributor.sendToServer(saved);depositSentAt=ticks;
            AnyaStorage.LOG.info("ANYA_ROOM_DEPOSIT_SENT revision={} inventory=13 stored={}",m.revision(),stored.getCount());return;
        }
        if(ticks-depositSentAt<20)return;
        if(held.isEmpty()&&stored.is(Items.EMERALD)&&stored.getCount()==13){shot("conserved-room-deposit");ack();return;}
        Acceptance.require(ticks-depositSentAt<60,"Single deposit not accepted: inventory="+held.getCount()+" stored="+stored.getCount()+" revision="+m.revision()+"; no retry performed");
    }
    /** New room scenario: prove the added physical chest reaches the unchanged production grid. */
    private static void inspectExpandedRoom(Minecraft mc){
        Acceptance.require(ticks<=180,"Timed out inspecting added chest in synchronized network view");
        if(ticks<5)return;
        Acceptance.require(mc.player.containerMenu instanceof StoreMenu,"Expected combined pocket menu");
        StoreMenu m=(StoreMenu)mc.player.containerMenu;
        Acceptance.require(!m.privateView()&&!m.filteredView()&&m.capacity()==270,"Expected five real 54-slot room chests");
        if(expectedOffset<0){requestViewport(m,216);return;}
        if(m.firstSlot()!=216){if(ticks-viewportRequestedAt>=20)requestViewport(m,216);return;}
        if(ticks-viewportRequestedAt<3)return;
        var sample=m.getSlot(0).getItem();
        Acceptance.require(sample.is(Items.EMERALD)&&sample.getCount()==13&&sample.getHoverName().getString().equals("Room acceptance emeralds"),"Added-chest physical slot 216 lost named sample");
        shot("expanded-room-network-270");
        AnyaStorage.LOG.info("ANYA_ROOM_CLIENT_GRID_OK capacity={} offset={} display=0 physical=216 count={} revision={}",m.capacity(),m.firstSlot(),sample.getCount(),m.revision());
        ack();
    }
    /** Multi-pocket fixture uses synced production menus and a single fresh-revision withdrawal. */
    private static void multiView(Minecraft mc,String action,String name,int expected){
        Acceptance.require(ticks<=200,"Multi-pocket client deadline: "+action);
        if(!(mc.player.containerMenu instanceof StoreMenu m)||ticks<5)return;
        Acceptance.require(!m.privateView()&&m.capacity()==216&&m.firstSlot()==0,"Wrong multi-pocket capacity/privacy/offset");
        var sample=m.getSlot(0).getItem();String title="Pocket "+name+" sample";
        if(action.equals("multiwithdraw")&&withdrawSentAt>=0){
            if(ticks-withdrawSentAt<15)return;
            if(sample.getCount()==expected-1&&sample.getHoverName().getString().equals(title)){
                shot("multi-"+name+"-withdrawn");ack();return;
            }
            Acceptance.require(ticks-withdrawSentAt<60,"Single multi-pocket withdrawal failed; not retried");return;
        }
        Acceptance.require(sample.is(name.equals("Alpha")?Items.DIAMOND:Items.EMERALD)&&sample.getCount()==expected&&sample.getHoverName().getString().equals(title),"Cross-pocket content mismatch: "+title);
        for(int i=0;i<m.shownSlots();i++){
            var other=m.getSlot(i).getItem();
            Acceptance.require(other.isEmpty()||other.getHoverName().getString().equals(title),"Another pocket leaked into displayed storage");
        }
        if(action.equals("multiview")){shot("multi-"+name+"-view-"+work.sequence());ack();return;}
        if(depositRevision<0){depositRevision=m.revision();return;}
        if(m.revision()==depositRevision)return;
        saved=new Packets.Action(m.containerId,m.session(),m.revision(),"slot","",256);
        PacketDistributor.sendToServer(saved);withdrawSentAt=ticks;
        AnyaStorage.LOG.info("ANYA_MULTI_CLIENT_WITHDRAW_ONCE name={} count={} revision={}",name,expected,m.revision());
    }
    private static void requestViewport(StoreMenu m,int offset){
        Acceptance.require(++viewportAttempts<=24,"Viewport retry bound exceeded");
        expectedOffset=offset;viewportRequestedAt=ticks;
        PacketDistributor.sendToServer(new Packets.Action(m.containerId,m.session(),m.revision(),"viewport","",((offset/9)<<3)|m.visibleRows()));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){
        if(!Boolean.getBoolean("anya.acceptance"))return;
        var mc=Minecraft.getInstance();age++;
        try{
            if(PadPreviewClient.active())PadPreviewClient.observe(mc);
            if(!connected&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){
                Acceptance.verifyJar("client-"+System.getProperty("anya.role"));
                mc.options.pauseOnLostFocus=false;mc.options.guiScale().set(2);mc.options.renderDistance().set(3);mc.options.simulationDistance().set(2);mc.options.enableVsync().set(false);mc.options.framerateLimit().set(25);mc.resizeDisplay();
                connected=true;
                ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString("127.0.0.1:25577"),new ServerData("Disposable acceptance","127.0.0.1:25577",ServerData.Type.OTHER),false,null);
            }
            if(age>18000)throw new IllegalStateException("Client acceptance deadline exceeded");
            if(work==null||mc.player==null||mc.level==null||mc.getOverlay()!=null)return;
            String[] a=work.action().split(" ");String action=a[0];
            if(ticks==0&&(action.equals("block")||action.equals("remote"))){
                Acceptance.require(++waitingTicks<=240,"Client never became ready for single interaction: "+work.action());
                boolean ready=mc.gameMode!=null&&!(mc.screen instanceof ReceivingLevelScreen);
                if(action.equals("block")){
                    BlockPos pos=new BlockPos(Integer.parseInt(a[1]),Integer.parseInt(a[2]),Integer.parseInt(a[3]));
                    ready=ready&&mc.level.hasChunkAt(pos)&&!mc.level.getBlockState(pos).isAir()&&mc.player.distanceToSqr(Vec3.atCenterOf(pos))<=64;
                }else ready=ready&&mc.player.getInventory().getItem(0).is(Content.REMOTE.get());
                if(!ready){readyTicks=0;return;}
                if(++readyTicks<12)return;
                AnyaStorage.LOG.info("ANYA_SINGLE_INTENT_READY action={} position={} waitedTicks={}",work.action(),mc.player.position(),waitingTicks);
            }
            ticks++;
            if(action.equals("pad")){
                if(PadPreviewClient.check(mc,a,ticks))ack();
                return;
            }
            if(action.startsWith("art-")){
                if (ticks == 1 && action.equals("art-closed")) ArtSoundObserver.arm(ArtVisualProbe.CHEST);
                if (ArtVisualClient.run(mc, action, ticks)) {
                    ArtSoundObserver.checkCapture(action);
                    ack();
                }
                return;
            }
            if(action.equals("repairtry")){
                Acceptance.require(ticks<=180,"Repair action deadline; no mutation retry");
                if(!(mc.player.containerMenu instanceof StoreMenu m)||ticks<5)return;
                if(depositRevision<0){depositRevision=m.revision();return;}
                if(withdrawSentAt<0){
                    if(m.revision()==depositRevision)return;
                    saved=new Packets.Action(m.containerId,m.session(),m.revision(),"slot","",Integer.parseInt(a[1]));
                    AnyaStorage.LOG.info("REPAIR_CLIENT_SINGLE_INTENT revision={} value={}",m.revision(),a[1]);
                    PacketDistributor.sendToServer(saved);withdrawSentAt=ticks;return;
                }
                if(ticks-withdrawSentAt>=30){shot("repair-intent-"+work.sequence());ack();}return;
            }
            if(action.equals("repairview")){
                Acceptance.require(ticks<=180,"Repair snapshot client deadline");
                if(!(mc.player.containerMenu instanceof StoreMenu m)||ticks<8)return;
                var sample=m.getSlot(0).getItem();
                Acceptance.require(sample.getCount()==Integer.parseInt(a[3])&&sample.getHoverName().getString().equals("Repair client "+a[1]+" v"+a[2]),"Wrong current snapshot displayed to real client");
                shot("repair-view-"+work.sequence());ack();return;
            }
            if(action.equals("multiview")||action.equals("multiwithdraw")){multiView(mc,action,a[1],Integer.parseInt(a[2]));return;}
            if(action.equals("withdrawone")){withdrawLocatedSample(mc);return;}
            if(action.equals("roomdeposit")){depositRoomSample(mc);return;}
            if(action.equals("roomnetwork")){inspectExpandedRoom(mc);return;}
            if(ticks==1){
                switch(action){
                    case "block" -> {BlockPos pos=new BlockPos(Integer.parseInt(a[1]),Integer.parseInt(a[2]),Integer.parseInt(a[3]));mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));}
                    case "remote" -> {mc.player.getInventory().selected=0;mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);}
                    case "command" -> {mc.player.closeContainer();mc.player.connection.sendCommand(work.action().substring("command ".length()));}
                    case "exit" -> mc.player.connection.sendCommand("anya pocket exit");
                    case "close" -> mc.player.closeContainer();
                    case "deposit", "withdraw" -> {
                        Acceptance.require(mc.player.containerMenu instanceof StoreMenu,"Expected production StoreMenu for "+action);
                        StoreMenu m=(StoreMenu)mc.player.containerMenu;int index=action.equals("deposit")?54:0;
                        saved=new Packets.Action(m.containerId,m.session(),m.revision(),"slot","",index);
                        PacketDistributor.sendToServer(saved);
                    }
                    case "multicapture" -> {Acceptance.require(mc.player.containerMenu instanceof StoreMenu,"No menu to capture");StoreMenu m=(StoreMenu)mc.player.containerMenu;saved=new Packets.Action(m.containerId,m.session(),m.revision(),"slot","",256);}
                    case "stale" -> {Acceptance.require(saved!=null,"No captured stale packet");PacketDistributor.sendToServer(saved);}
                    case "walk" -> {mc.player.closeContainer();mc.player.setYRot(180);mc.player.setXRot(12);mc.options.keyUp.setDown(true);shot("walk-start");}
                    case "respawn" -> mc.player.respawn();
                    case "photo" -> shot(a[1]);
                    default -> throw new IllegalStateException("Unknown test directive "+action);
                }
            }
            if(action.equals("walk")){
                if(ticks%15==0)shot("walk-"+ticks);
                if(ticks==75)mc.options.keyUp.setDown(false);
                if(ticks>=85){shot("walk-end");ack();}
            }else if(ticks>=40){
                if(action.equals("block")||action.equals("remote"))shot("interaction-"+work.sequence());
                ack();
            }
        }catch(Throwable error){
            mc.options.keyUp.setDown(false);Acceptance.marker("FAILED-client-"+System.getProperty("anya.role"),error.toString());AnyaStorage.LOG.error("ANYA_REAL_CLIENT_FAILED",error);mc.stop();
        }
    }
}
