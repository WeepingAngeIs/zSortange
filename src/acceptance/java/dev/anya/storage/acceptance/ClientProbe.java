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
    private static int ticks,age;
    private static Packets.Action saved;
    public static void receive(Wire.Signal s){work=s;ticks=0;AnyaStorage.LOG.info("ANYA_REAL_CLIENT_ACTION {} {}",s.sequence(),s.action());}
    private static void ack(){PacketDistributor.sendToServer(new Wire.Signal(work.sequence(),"done"));work=null;}
    private static void shot(String name){var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,"room-"+System.getProperty("anya.role")+"-"+name+".png",mc.getMainRenderTarget(),c->{});}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){
        if(!Boolean.getBoolean("anya.acceptance"))return;
        var mc=Minecraft.getInstance();age++;
        try{
            if(!connected&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){
                Acceptance.verifyJar("client-"+System.getProperty("anya.role"));
                mc.options.pauseOnLostFocus=false;mc.options.guiScale().set(2);mc.options.renderDistance().set(3);mc.options.simulationDistance().set(2);mc.options.enableVsync().set(false);mc.options.framerateLimit().set(25);mc.resizeDisplay();
                connected=true;
                ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString("127.0.0.1:25577"),new ServerData("Disposable acceptance","127.0.0.1:25577",ServerData.Type.OTHER),false,null);
            }
            if(age>18000)throw new IllegalStateException("Client acceptance deadline exceeded");
            if(work==null||mc.player==null||mc.level==null||mc.getOverlay()!=null)return;
            ticks++;String[] a=work.action().split(" ");String action=a[0];
            if(ticks==1){
                switch(action){
                    case "block" -> {BlockPos pos=new BlockPos(Integer.parseInt(a[1]),Integer.parseInt(a[2]),Integer.parseInt(a[3]));mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));}
                    case "remote" -> {mc.player.getInventory().selected=0;mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);}
                    case "exit" -> mc.player.connection.sendCommand("anya pocket exit");
                    case "close" -> mc.player.closeContainer();
                    case "deposit", "withdraw", "withdrawone" -> {
                        Acceptance.require(mc.player.containerMenu instanceof StoreMenu,"Expected production StoreMenu for "+action);
                        StoreMenu m=(StoreMenu)mc.player.containerMenu;int index=action.equals("deposit")?54:0;
                        if(action.equals("withdrawone"))index|=256;
                        saved=new Packets.Action(m.containerId,m.session(),m.revision(),"slot","",index);
                        PacketDistributor.sendToServer(saved);
                    }
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
