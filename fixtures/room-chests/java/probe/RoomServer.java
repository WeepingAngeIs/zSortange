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

@Mod("roomprobe")
@EventBusSubscriber(modid="roomprobe")
public final class RoomServer {
    static final BlockPos HUB=new BlockPos(2,81,1), DOOR=new BlockPos(3,81,1);
    static int stage,start;
    static UUID id,identity;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void click(ServerPlayer p,BlockPos target,boolean remove){
        double y=target.getY()+(remove?0:1);
        float angle=(float)Math.toDegrees(Math.atan2(y+p.getEyeHeight()-target.getY()-(remove?.5:.99),2));
        p.connection.teleport(target.getX()+.5,y,target.getZ()+2.5,180,angle);
        var hit=RoomEditor.looking(p);check(hit.getBlockPos().equals(target),"Fixture aim "+target+" hit "+hit);
        if(remove)NeoForge.EVENT_BUS.post(new PlayerInteractEvent.LeftClickBlock(p,target,hit.getDirection(),PlayerInteractEvent.LeftClickBlock.Action.START));
        else NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,target,hit));
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        var server=event.getServer();
        var policy=ExpansionRules.get();policy.pocketStorage=true;policy.pocketEditing=true;policy.pocketSessionTicks=200;
        if(stage==99)return;
        if(Files.exists(Path.of("entry.txt"))){
            var lines=Files.readAllLines(Path.of("entry.txt"));var room=PocketService.ledger(server).get(UUID.fromString(lines.get(0)));
            var level=PocketService.dimension(server);
            for(int x=0;x<32;x+=16)for(int z=0;z<32;z+=16)level.setChunkForced((room.x()+x)>>4,(room.z()+z)>>4,true);
            if(server.getTickCount()<80)return;
            var registry=PocketRoomLayout.present(level,room);var extra=registry.get(4);
            check(registry.size()==5 && extra.getBlockPos().equals(new BlockPos(room.x()+9,65,room.z()+17)) &&
                extra.storageIdentity().equals(UUID.fromString(lines.get(1))) && extra.getItem(0).getCount()==7 && extra.getItem(0).is(Items.EMERALD),"Restart identity or contents");
            Files.writeString(Path.of("CHECKS.txt"),"PASS Off-pad placement spent one chest, retained seven emeralds and chest identity across restart. Starter, occupied chest and exit protected. Visit exceeded 200 ticks, exit released tickets.\\n");
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
            p.setItemInHand(InteractionHand.MAIN_HAND,tablet);PocketService.link(p,door,tablet);id=door.pocket;check(id!=null,"Link");PocketService.enter(p,door);stage++;return;
        }
        if(stage==5){
            if(PocketService.inPocket(p.level())||PocketService.ownedTicketRequests()!=0)return;
            Files.writeString(Path.of("entry.txt"),id+"\\n"+identity+"\\n");stage=99;return;
        }
        if(!PocketService.inPocket(p.level())){check(stage==1,"Unexpected timed return");return;}
        var room=PocketService.ledger(server).get(id);var pos=new BlockPos(room.x()+9,65,room.z()+17);
        if(stage==1){
            if(p.tickCount%10!=0)return;
            var reason=RoomEditor.setMode(p,true);check(reason.isEmpty(),reason);
            p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Content.CRATE.get(),2));
            click(p,pos.below(),false);var c=(StorageEntity)p.serverLevel().getBlockEntity(pos);
            check(c!=null&&p.getMainHandItem().getCount()==1,"Normal right click did not place one chest");
            identity=c.storageIdentity();c.setItem(0,new ItemStack(Items.EMERALD,7));start=server.getTickCount();stage++;
        }else if(stage==2 && server.getTickCount()-start>10){
            click(p,pos,true);check(p.serverLevel().getBlockEntity(pos)!=null,"Nonempty removal");stage++;
        }else if(stage==3 && server.getTickCount()-start>20){
            var starter=PocketService.chestPositions(room).getFirst();click(p,starter,true);
            check(p.serverLevel().getBlockEntity(starter)!=null,"Starter protection");
            var route=new BlockPos(room.x()+16,64,room.z()+22);click(p,route,false);check(p.serverLevel().isEmptyBlock(route.above()),"Exit path");
            stage++;
        }else if(stage==4 && server.getTickCount()-start>240){
            check(PocketService.lease(p.getUUID())!=null&&RoomEditor.active(p),"Visit after former timeout");
            PocketService.recover(p);stage++;
        }
    }
}
