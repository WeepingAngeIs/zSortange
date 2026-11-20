package dev.anya.storage.visual;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.Content;
import dev.anya.storage.Config;
import dev.anya.storage.Client;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.TabletThreatCue;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** One connected client observes real target events, private payloads and local sound playback. */
final class WarningCamera {
    private enum Stage { START, DISTANT, APPROACH, GUARDIAN, OTHER_PLAYER, PACK, CLEAR, BLOCKED, VISIBLE,
        LINGER, BREAK, OFFHAND, HOLD, QUIET, PREPARE_MENU, MENU, REVEAL, NO_QUEUE, FINISHED }
    private static Stage stage=Stage.START;
    private static final List<Integer> heard=new ArrayList<>();
    private static int sounds;
    private static int ticks, menuChoice;
    private static boolean active, leftPictured, rightPictured;
    private static Husk mob, second;
    private static Guardian guardian;
    private static CompletableFuture<Void> task;
    private static BiConsumer<Connection,TabletThreatCue> original;

    static void sound(PlaySoundEvent event){
        var sound=event.getOriginalSound();
        if(!sound.getLocation().toString().equals("anyastorage:tablet_warning"))return;
        require(active,"Ordinary tablet openings or idle played the warning sound");
        require(sound.getSource()==SoundSource.PLAYERS && sound.isRelative() && !sound.isLooping()
                && sound.getAttenuation()==SoundInstance.Attenuation.NONE,"Single relative, private player sound");
        sounds++;
    }
    static void frame(Minecraft mc){
        if(!active || sounds<1 || sounds>2 || (sounds==1?leftPictured:rightPictured) || mc.player==null || mc.screen!=null)return;
        var stack=sounds==1 ? mc.player.getInventory().getItem(1) : mc.player.getOffhandItem();
        var id=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("anyastorage","cupid_face");
        var prop=net.minecraft.client.renderer.item.ItemProperties.getProperty(stack,id);
        if(prop==null || Math.round(prop.call(stack,mc.level,mc.player,0)*256)!=(sounds==1?38:47))return;
        if(sounds==1){leftPictured=true;Screenshot.grab(mc.gameDirectory,"warning-left.png",mc.getMainRenderTarget(),ignored->{});}
        else{rightPictured=true;Screenshot.grab(mc.gameDirectory,"warning-right.png",mc.getMainRenderTarget(),ignored->{});}
    }
    private static void require(boolean ok,String message){
        if(!ok)throw new IllegalStateException("Expression review: warning "+stage+" "+message+" cues="+heard+" sounds="+sounds);
    }
    private static void next(Stage s){
        stage=s;ticks=0;
        System.out.println("Warning review "+stage);
    }
    private static void server(Minecraft mc, Consumer<ServerPlayer> action){
        var srv=mc.getSingleplayerServer();var id=mc.player.getUUID();
        task=srv.submit(()->action.accept(srv.getPlayerList().getPlayer(id)));
    }
    private static Husk place(ServerPlayer p,double x){
        var l=p.serverLevel();var h=EntityType.HUSK.create(l);
        h.setNoAi(true);h.setNoGravity(true);h.setPersistenceRequired();
        h.moveTo(x,80,-3.25,0,0);require(l.addFreshEntity(h),"Spawn fixture husk");return h;
    }
    private static void wall(ServerPlayer p,boolean closed){
        var block=closed?Blocks.STONE:Blocks.AIR;
        for(var pos:BlockPos.betweenClosed(new BlockPos(3,79,-6),new BlockPos(3,83,0)))
            p.serverLevel().setBlockAndUpdate(pos,block.defaultBlockState());
    }
    private static void count(int n){require(heard.size()==n && sounds==n,"Private cues and blips expected "+n);}
    static boolean tick(Minecraft mc) throws IllegalAccessException {
        if(task!=null){if(!task.isDone())return false;task.join();task=null;}
        if(mc.player==null || mc.level==null)return false;
        require(++ticks<350,"Stage timeout");
        switch(stage){
            case START -> {
                active=true;original=TabletThreatCue.receiver;
                TabletThreatCue.receiver=(connection,cue)->{heard.add(cue.octant());original.accept(connection,cue);};
                // One standard tablet in the hotbar, empty hands. Park its Creative sibling in the main inventory.
                mc.player.getInventory().selected=0;
                server(mc,p->{
                    var l=p.serverLevel();l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,p.getServer());
                    p.getServer().setDifficulty(Difficulty.NORMAL,true);p.setGameMode(GameType.SURVIVAL);
                    p.teleportTo(l,.5,80,-3.25,0,0);
                    var inv=p.getInventory();inv.selected=0;inv.setItem(18,inv.removeItemNoUpdate(2));p.inventoryMenu.broadcastChanges();
                    mob=place(p,16.5);second=place(p,-6.5);
                    guardian=EntityType.GUARDIAN.create(l);guardian.setNoAi(true);guardian.setNoGravity(true);guardian.setPersistenceRequired();
                    guardian.moveTo(6.5,80,-1.25,0,0);require(l.addFreshEntity(guardian),"Spawn fixture Guardian");
                    mob.setTarget(p);require(mob.getTarget()==p,"Distant genuine target");
                });next(Stage.DISTANT);
            }
            case DISTANT -> {
                if(ticks<24)return false;count(0);
                server(mc,p->{mob.setTarget(null);mob.moveTo(6.5,80,-3.25,0,0);});next(Stage.APPROACH);
            }
            case APPROACH -> {if(ticks<22)return false;count(0);server(mc,p->mob.setTarget(guardian));next(Stage.GUARDIAN);}
            case GUARDIAN -> {
                if(ticks<22)return false;count(0);
                server(mc,p->{
                    require(mob.getTarget()==guardian,"Mob retained Guardian target");
                    var other=FakePlayerFactory.get(p.serverLevel(),new GameProfile(UUID.randomUUID(),"OtherTabletUser"));
                    other.setPos(5.5,80,-2);mob.setTarget(other);require(mob.getTarget()==other,"Alternate player as target");
                });next(Stage.OTHER_PLAYER);
            }
            case OTHER_PLAYER -> {
                if(ticks<22)return false;count(0);
                server(mc,p->{var inv=p.getInventory();inv.setItem(19,inv.removeItemNoUpdate(1));p.inventoryMenu.broadcastChanges();mob.setTarget(p);});
                next(Stage.PACK);
            }
            case PACK -> {
                if(ticks<28)return false;count(0);
                server(mc,p->{require(mob.getTarget()==p,"Actual player target");mob.setTarget(null);});
                next(Stage.CLEAR);
            }
            case CLEAR -> {
                if(ticks<110)return false;count(0);
                server(mc,p->{
                    var inv=p.getInventory();inv.setItem(1,inv.removeItemNoUpdate(19));p.inventoryMenu.broadcastChanges();
                    wall(p,true);require(!p.hasLineOfSight(mob),"Occluded target setup");mob.setTarget(p);
                });next(Stage.BLOCKED);
            }
            case BLOCKED -> {
                if(ticks<28)return false;count(0);
                server(mc,p->{wall(p,false);require(p.hasLineOfSight(mob),"Actual visible target");});
                next(Stage.VISIBLE);
            }
            case VISIBLE -> {
                if(heard.isEmpty()||sounds==0)return false;count(1);
                require(heard.get(0)==6 && ticks<28,"Prompt left-hand side glance while only carrying tablet on hotbar");
                mc.player.getInventory().selected=1;
                server(mc,p->second.setTarget(p));next(Stage.LINGER);
            }
            case LINGER -> {
                if(ticks<110)return false;count(1);require(leftPictured,"Left glance seen on the tablet after pickup");
                server(mc,p->{mob.setTarget(null);second.setTarget(null);});next(Stage.BREAK);
            }
            case BREAK -> {
                if(ticks<110)return false;count(1);require(leftPictured,"Left glance seen on the tablet after pickup");
                mc.player.getInventory().selected=0;
                server(mc,p->{
                    var inv=p.getInventory();inv.setItem(19,inv.removeItemNoUpdate(1));
                    p.setItemInHand(InteractionHand.OFF_HAND,inv.removeItemNoUpdate(18));p.inventoryMenu.broadcastChanges();
                    require(p.getOffhandItem().is(Content.CREATIVE_TABLET.get()),"Only the offhand holds a tablet");
                    p.setGameMode(GameType.CREATIVE);second.setTarget(p);require(second.getTarget()==p,"Second visible attacker targets player");
                });next(Stage.OFFHAND);
            }
            case OFFHAND -> {
                if(heard.size()<2 || sounds<2)return false;count(2);
                require(heard.get(1)==2 && ticks<28,"Rearmed right-hand side glance on the Creative offhand tablet");
                next(Stage.HOLD);
            }
            case HOLD -> {
                if(ticks<30)return false;count(2);require(rightPictured,"Right glance seen on the Creative offhand tablet");
                server(mc,p->{second.setTarget(null);var inv=p.getInventory();inv.setItem(1,inv.removeItemNoUpdate(19));p.inventoryMenu.broadcastChanges();
                    require(Config.setTabletOpeningChancePercent(p,Config.get().revision,100),"Enable intro for no-interruption check");});
                next(Stage.QUIET);
            }
            case QUIET -> {
                if(ticks<110 || !mc.player.getInventory().getItem(1).is(Content.CUPID_TABLET.get()))return false;
                count(2);mc.player.getInventory().selected=1;next(Stage.PREPARE_MENU);
            }
            case PREPARE_MENU -> {
                if(ticks<20)return false;
                mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);next(Stage.MENU);
            }
            case MENU -> {
                if(!(mc.screen instanceof Client.Screen s) || !(mc.player.containerMenu instanceof StoreMenu m)
                        || !m.showsTabletIntro() || !ExpressionCamera.clock(s).coversInput())return false;
                menuChoice=ExpressionCamera.clock(s).sequence();
                require(menuChoice>=0,"Actual Cupid intro before hostile retargets");
                server(mc,p->mob.setTarget(p));next(Stage.REVEAL);
            }
            case REVEAL -> {
                require(mc.screen instanceof Client.Screen,"Storage screen stays open while targeted");
                var s=(Client.Screen)mc.screen;
                var clock=ExpressionCamera.clock(s);
                require(clock.sequence()==menuChoice && sounds==2,"Ongoing opening keeps its original sequence");
                if(clock.coversInput())return false;
                require(ticks<35 && heard.size()==3,"Warning received and discarded during the ordinary opening");
                s.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);next(Stage.NO_QUEUE);
            }
            case NO_QUEUE -> {
                if(ticks<35)return false;
                require(mc.screen==null && sounds==2 && heard.size()==3,"Escape cannot replay a stale warning");
                server(mc,p->{mob.discard();second.discard();guardian.discard();});next(Stage.FINISHED);
            }
            case FINISHED -> {TabletThreatCue.receiver=original;active=false;return true;}
        }
        return false;
    }
    private WarningCamera(){}
}
