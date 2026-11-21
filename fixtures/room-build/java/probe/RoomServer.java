package probe;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.*;
import java.nio.file.*;
import java.util.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import com.mojang.authlib.GameProfile;

@Mod("buildprobe")
@EventBusSubscriber(modid="buildprobe")
public final class RoomServer {
    static final BlockPos HUB=new BlockPos(2,81,1), DOOR=new BlockPos(3,81,1);
    private static ServerPlayer join(net.minecraft.server.MinecraftServer server,String name,UUID uuid)throws Exception{
        var level=server.overworld();var profile=new GameProfile(uuid,name);
        var p=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
        var connection=new Connection(PacketFlow.SERVERBOUND){
            @Override public boolean isMemoryConnection(){return true;}
            @Override public void send(Packet<?> packet,PacketSendListener callback,boolean flush){}
        };
        var channel=new EmbeddedChannel(connection);NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection,p,CommonListenerCookie.createInitial(profile,false));
        return p;
    }
    static ServerPlayer guest;
    static int stage,start;
    static UUID id,identity;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void click(ServerPlayer p,BlockPos target,boolean remove){
        double x=target.getX()+.5,y=target.getY()+2.2,z=target.getZ()+.5;
        p.connection.teleport(x,y,z,0,90);
        p.setXRot(90);p.setYRot(0);p.setYHeadRot(0);
        var hit=RoomEditor.looking(p);check(hit.getBlockPos().equals(target),"Fixture aim "+target+" hit "+hit.getBlockPos()+" at "+p.position()+" direction "+p.getViewVector(1));
        if(remove)NeoForge.EVENT_BUS.post(new PlayerInteractEvent.LeftClickBlock(p,target,hit.getDirection(),PlayerInteractEvent.LeftClickBlock.Action.START));
        else NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,target,hit));
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        try{step(event);}catch(Throwable error){Files.writeString(Path.of("FAILED"),error.toString());throw error;}
    }
    static ServerPlayer builder;
    static BlockPos log;
    static boolean denyCell;
    @SubscribeEvent public static void claim(StorageAccessEvent event){
        if(denyCell && event.position.equals(log) && PocketService.inPocket(event.level))event.setCanceled(true);
    }
    static void side(ServerPlayer p,BlockPos support){
        double x=support.getX()-2.5,y=support.getY(),z=support.getZ()+.5;
        float pitch=(float)Math.toDegrees(Math.atan2(p.getEyeHeight()-.5,2.5));
        p.connection.teleport(x,y,z,-90,pitch);p.setYRot(-90);p.setXRot(pitch);p.setYHeadRot(-90);
        var hit=RoomEditor.looking(p);
        check(hit.getBlockPos().equals(support) && hit.getDirection()==Direction.WEST,"Side ray: "+hit);
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,support,hit));
    }
    static boolean flag(String id){return Files.exists(Path.of("../Client/"+id));}
    static void step(ServerTickEvent.Post event)throws Exception{
        var server=event.getServer();if(stage==99)return;
        var rules=ExpansionRules.get();rules.pocketStorage=true;rules.pocketEditing=true;
        boolean restart=Files.exists(Path.of("entry.txt"));
        if(stage==0){
            if(restart){
                var lines=Files.readAllLines(Path.of("entry.txt"));id=UUID.fromString(lines.get(0));
                builder=join(server,"RoomBuilder",UUID.fromString(lines.get(1)));
            }else{
                var viewer=server.getPlayerList().getPlayerByName("ImageGuest");if(viewer==null || viewer.tickCount<40)return;
                builder=join(server,"RoomBuilder",UUID.randomUUID());
            }
            var home=server.overworld();builder.teleportTo(home,3.5,81,3.5,180,0);
            if(!restart){
                for(int x=0;x<7;x++)for(int z=0;z<7;z++)home.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());
                home.setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());home.setBlockAndUpdate(DOOR,PocketContent.ENTRANCE.get().defaultBlockState());
                var hub=(StorageEntity)home.getBlockEntity(HUB);hub.owner=builder.getUUID();
                var door=(PocketContent.EntranceEntity)home.getBlockEntity(DOOR);door.owner=builder.getUUID();
                var tablet=new ItemStack(Content.CUPID_TABLET.get());var tag=BagInventory.data(tablet);
                tag.putUUID("HubId",hub.storageIdentity());tag.putLong("HubPos",HUB.asLong());tag.putString("HubDimension","minecraft:overworld");BagInventory.data(tablet,tag);
                builder.setItemInHand(InteractionHand.MAIN_HAND,tablet);PocketService.link(builder,door,tablet);id=door.pocket;
                var viewer=server.getPlayerList().getPlayerByName("ImageGuest");
                hub.setMember(viewer.getUUID(),BasePermissions.VIEW);
                viewer.connection.teleport(3.5,82,1.5,0,90);viewer.setXRot(90);
            }
            PocketService.enter(builder,(PocketContent.EntranceEntity)home.getBlockEntity(DOOR));stage++;return;
        }
        if(stage==12 && !PocketService.inPocket(builder.level()) && PocketService.ownedTicketRequests()==0){
            Files.writeString(Path.of("entry.txt"),id+"\n"+builder.getUUID()+"\n");stage=99;return;
        }
        if(!PocketService.inPocket(builder.level()))return;
        var room=PocketService.ledger(server).get(id);log=new BlockPos(room.x()+9,65,room.z()+17);
        var level=builder.serverLevel();var axis=net.minecraft.world.level.block.RotatedPillarBlock.AXIS;
        if(restart){
            check(level.getBlockState(log).is(Blocks.CHERRY_LOG) && level.getBlockState(log).getValue(axis)==Direction.Axis.X,"Restart axis");
            // The cross-dimension arrival can precede the FULL chunk future used by the editor raycast.
            builder.connection.teleport(log.getX()+.5,log.getY()+2.2,log.getZ()+.5,0,90);
            builder.setXRot(90);builder.setYRot(0);
            try{if(!RoomEditor.looking(builder).getBlockPos().equals(log))return;}
            catch(IllegalArgumentException pending){return;}
            if(!RoomEditor.active(builder))check(RoomEditor.setMode(builder,true).isEmpty(),"Restart builder lease");
            int before=builder.getInventory().countItem(Items.CHERRY_LOG);click(builder,log,true);
            check(level.isEmptyBlock(log) && builder.getInventory().countItem(Items.CHERRY_LOG)==before+1,"Restart refund");
            Files.writeString(Path.of("CHECKS.txt"),"PASS clicked-side placement, precise state, rejection of modified item, claim denial, ordinary removal and exact refund after restart.\n");
            stage=99;server.halt(false);return;
        }
        if(stage==1){
            level.setBlockAndUpdate(log.east(),Blocks.STONE.defaultBlockState());
            var named=new ItemStack(Items.CHERRY_LOG);named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Keep me"));
            builder.setItemInHand(InteractionHand.MAIN_HAND,named);side(builder,log.east());
            check(level.isEmptyBlock(log) && ItemStack.matches(named,builder.getMainHandItem()),"Modified log consumed");
            builder.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.CHERRY_LOG,2));
            denyCell=true;side(builder,log.east());check(level.isEmptyBlock(log)&&builder.getMainHandItem().getCount()==2,"Claim bypass");denyCell=false;
            side(builder,log.east());check(level.getBlockState(log).getValue(axis)==Direction.Axis.X && builder.getMainHandItem().getCount()==1,"Side log or cost");
            stage++;
        }else if(stage==2 && flag("side")){
            level.setBlockAndUpdate(log,level.getBlockState(log).setValue(axis,Direction.Axis.Z));stage++;
        }else if(stage==3 && flag("rotate")){
            denyCell=true;stage++;
        }else if(stage==4 && flag("denied")){
            denyCell=false;stage++;
        }else if(stage==5 && flag("restored")){
            var hub=(StorageEntity)server.overworld().getBlockEntity(HUB);hub.setMember(server.getPlayerList().getPlayerByName("ImageGuest").getUUID(),0);stage++;
        }else if(stage==6 && flag("revoked")){
            var hub=(StorageEntity)server.overworld().getBlockEntity(HUB);hub.setMember(server.getPlayerList().getPlayerByName("ImageGuest").getUUID(),BasePermissions.VIEW);stage++;
        }else if(stage==7 && flag("regained")){
            // Changing the member list invalidates every edit session tied to that policy revision.
            check(!RoomEditor.active(builder),"Stale build session survived a grant change");
            check(RoomEditor.setMode(builder,true).isEmpty() && RoomEditor.active(builder),"Owner could not resume building");
            click(builder,log,true);check(level.isEmptyBlock(log) && builder.getInventory().countItem(Items.CHERRY_LOG)==2,"Remove refund");start=server.getTickCount();stage++;
        }else if(stage==8 && flag("removed") && server.getTickCount()-start>=6){
            side(builder,log.east());check(level.getBlockState(log).getValue(axis)==Direction.Axis.X,"Replace");stage++;
        }else if(stage==9 && flag("replaced")){
            PocketService.recover(builder);stage=12;
        }
    }
}
