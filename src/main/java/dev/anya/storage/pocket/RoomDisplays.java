package dev.anya.storage.pocket;

import dev.anya.storage.Safety;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** The owner's tablet lists anchors in the room's already loaded chunks. */
public final class RoomDisplays {
    private static List<CameraDevices.Device> monitors(ServerPlayer p,UUID roomId){
        if(!PocketService.visitingOwner(p,roomId))return List.of();
        var room=PocketService.loadedPocket(p.getServer(),roomId);
        var found=new HashSet<CameraDevices.Device>();
        for(int x=room.x()>>4;x<=(room.x()+31)>>4;x++)for(int z=room.z()>>4;z<=(room.z()+31)>>4;z++)
            for(var d:WallPanel.at(p.level(),new BlockPos(x<<4,65,z<<4))){
                var pos=d.getBlockPos();
                if(!d.isRemoved()&&d.owned(p)&&d.identity!=null&&room.contains(pos.getX(),pos.getY(),pos.getZ())
                        &&Safety.locationReason(p,p.serverLevel(),pos).isEmpty())found.add(d);
            }
        return found.stream().sorted(Comparator.comparing(d->d.getBlockPos())).toList();
    }
    public record Choice(UUID id,String label){}
    public static Choice choose(ServerPlayer p,UUID room,UUID current,boolean advance){
        var options=monitors(p,room);if(options.isEmpty())return new Choice(null,"Place a wall monitor");
        int index=0;
        for(int i=0;i<options.size();i++)if(options.get(i).identity.equals(current)){index=advance?(i+1)%options.size():i;break;}
        var d=options.get(index);return new Choice(d.identity,(index+1)+"/"+options.size()+"  "+d.getBlockState().getBlock().getName().getString()+"  "+d.panel.width()+" × "+d.panel.height());
    }
    public static void change(ServerPlayer p,UUID room,UUID selected,boolean fit){
        if(selected==null)return;
        for(var d:monitors(p,room))if(selected.equals(d.identity)){
            String result=fit?WallMonitorBlock.resize(p,d,d.panel.width(),d.panel.height(),true):CameraDevices.linkSelected(p,d);
            p.displayClientMessage(Component.literal(result.isEmpty()?"Screen fitted to the clear wall.":result),true);return;
        }
        p.displayClientMessage(Component.literal("The selected monitor is unavailable."),true);
    }
}
