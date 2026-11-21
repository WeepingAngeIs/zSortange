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

@Mod("wallprobe")
@EventBusSubscriber(modid="wallprobe")
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
    static void step(ServerTickEvent.Post event)throws Exception{
        var server=event.getServer();
        var policy=ExpansionRules.get();policy.pocketStorage=true;policy.pocketEditing=true;policy.pocketSessionTicks=200;
        if(stage==99)return;
        if(Files.exists(Path.of("entry.txt"))){
            var lines=Files.readAllLines(Path.of("entry.txt"));var room=PocketService.ledger(server).get(UUID.fromString(lines.get(0)));
            var actor=join(server,lines.get(1),UUID.fromString(lines.get(2)));
            check(ExitGate.color(actor,room.id())==1 && PocketService.ownedTicketRequests()==0,"Restart color or empty room tickets");
            Files.writeString(Path.of("CHECKS.txt"),"PASS Skygrid floor and panels, changing decor, private owner image and guest denial, persisted owner color, guest denial, lodestone backup return, arrival outside gate, owner walk-through, preserved decoration and final ticket cleanup.\n");
            stage=99;server.halt(false);return;
        }
        if(server.getPlayerList().getPlayers().isEmpty())return;
        var p=server.getPlayerList().getPlayers().getFirst();if(p.tickCount<50)return;
        if(stage==0){
            var home=server.overworld();
            for(int x=0;x<7;x++)for(int z=0;z<7;z++)home.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());
            p.teleportTo(home,3.5,81,3.5,180,0);
            home.setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());home.setBlockAndUpdate(DOOR,PocketContent.ENTRANCE.get().defaultBlockState());
            var hub=(StorageEntity)home.getBlockEntity(HUB);hub.owner=p.getUUID();
            var door=(PocketContent.EntranceEntity)home.getBlockEntity(DOOR);door.owner=p.getUUID();
            var tablet=new ItemStack(Content.CUPID_TABLET.get());var tag=BagInventory.data(tablet);
            tag.putUUID("HubId",hub.storageIdentity());tag.putLong("HubPos",HUB.asLong());tag.putString("HubDimension","minecraft:overworld");BagInventory.data(tablet,tag);
            p.setItemInHand(InteractionHand.MAIN_HAND,tablet);PocketService.link(p,door,tablet);id=door.pocket;check(id!=null,"Link");p.getInventory().setItem(5,tablet.copy());PocketService.enter(p,door);stage++;return;
        }
        if(stage==5){
            if(PocketService.inPocket(p.level())||PocketService.ownedTicketRequests()!=0)return;
            check(p.distanceToSqr(3.5,81,3.5)<4,"Owner safe return");
            Files.writeString(Path.of("entry.txt"),id+"\n"+p.getGameProfile().getName()+"\n"+p.getUUID()+"\n");stage=99;return;
        }
        if(!PocketService.inPocket(p.level())){check(stage==1,"Unexpected timed return");return;}
        var room=PocketService.ledger(server).get(id);var pos=new BlockPos(room.x()+9,65,room.z()+17);
        if(stage==1 && p.tickCount%10==0){
            check(p.distanceToSqr(room.x()+16.5,65,room.z()+24.5)<1,"Landing");
            check(p.serverLevel().getBlockState(new BlockPos(room.x()+3,64,room.z()+3)).is(PocketContent.TILE.get()),"Charcoal floor");
            check(p.serverLevel().getBlockState(new BlockPos(room.x()-1,67,room.z()+8)).is(PocketContent.PANEL.get()),"Panel wall");
            SkygridWalls.cycle(p,id,false);SkygridWalls.cycle(p,id,true);
            ExitGate.cycle(p,id);check(ExitGate.color(p,id)==1,"Owner color");
            guest=join(server,"RoomGuestTwo",UUID.randomUUID());
            var home=server.overworld();guest.teleportTo(home,3.5,81,3.5,180,0);
            ((StorageEntity)home.getBlockEntity(HUB)).setMember(guest.getUUID(),BasePermissions.VISIT);
            PocketService.enter(guest,(PocketContent.EntranceEntity)home.getBlockEntity(DOOR));stage=2;
        }else if(stage==2 && PocketService.inPocket(guest.level())){
            var image=UUID.fromString("b9ca394f-66cb-4bb3-831b-b8dd34648fa0");
            check(dev.anya.storage.images.InteriorImages.wallAllowed(p,room.owner(),image),"Owner image access");
            check(!dev.anya.storage.images.InteriorImages.wallAllowed(guest,room.owner(),image),"Guest saw private image");
            if(!Files.exists(Path.of("../Client/photo")))return;
            ExitGate.cycle(guest,id);check(ExitGate.color(guest,id)==1,"Guest changed color");
            var stone=PocketService.exitPosition(room);
            var obstacle=stone.offset(0,0,-2);p.serverLevel().setBlockAndUpdate(obstacle,Blocks.CHERRY_PLANKS.defaultBlockState());
            guest.connection.teleport(stone.getX()+1.5,65,stone.getZ()+.5,0,0);
            var hit=new BlockHitResult(Vec3.atCenterOf(stone),Direction.UP,stone,false);
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(guest,InteractionHand.MAIN_HAND,stone,hit));
            stage=3;
        }else if(stage==3 && !PocketService.inPocket(guest.level())){
            check(guest.distanceToSqr(3.5,81,3.5)<4,"Guest safe return");
            var stone=PocketService.exitPosition(room);check(p.serverLevel().getBlockState(stone.offset(0,0,-2)).is(Blocks.CHERRY_PLANKS),"Existing decoration overwritten");
            // Walk through the clear side of the opening beside the placed obstruction.
            p.connection.teleport(stone.getX()+1.40,65,stone.getZ()-1.65,0,0);
            start=server.getTickCount();stage=4;
        }else if(stage==4 && server.getTickCount()-start>=10){
            var stone=PocketService.exitPosition(room);
            p.connection.teleport(stone.getX()+1.40,65,stone.getZ()-.95,0,0);
            stage=5;
        }
    }
}
