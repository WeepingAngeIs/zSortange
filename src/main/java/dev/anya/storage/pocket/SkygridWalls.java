package dev.anya.storage.pocket;

import dev.anya.storage.*;
import dev.anya.storage.images.*;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class SkygridWalls extends SavedData{
    private final CompoundTag rooms;
    private SkygridWalls(){rooms=new CompoundTag();}
    private SkygridWalls(CompoundTag tag,HolderLookup.Provider r){rooms=tag.getCompound("Rooms").copy();}
    public CompoundTag save(CompoundTag tag,HolderLookup.Provider r){tag.put("Rooms",rooms.copy());return tag;}
    private static SkygridWalls data(ServerPlayer p){return p.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(SkygridWalls::new,SkygridWalls::new,null),"anya_skygrid_walls");}
    static final String[] PAPERS={"Original walls","Rose circuitry","Mint circuitry","Lilac circuitry"};
    public static void cycle(ServerPlayer p,UUID id,boolean photo){
        if(!PocketService.visitingOwner(p,id))return;
        var data=data(p);var entry=data.rooms.getCompound(id.toString());String message;
        if(photo){
            var available=InteriorImages.list(p,false);
            if(!ExpansionRules.get().imagesEnabled){message="Wall images are disabled in server settings.";}
            else{
                int index=0;
                if(entry.hasUUID("Photo"))for(int i=0;i<available.size();i++)if(available.get(i).id.equals(entry.getUUID("Photo"))){index=i+1;break;}
                if(index<available.size()){
                    entry.putUUID("Photo",available.get(index).id);message="Wall image "+(index+1)+" of "+available.size()+".";
                }else{entry.remove("Photo");message=available.isEmpty()?"Drop a PNG onto your backpack first, then choose Wall image.":"Wall image off.";}
            }
        }else{int pattern=Math.floorMod(entry.getInt("Paper")+1,PAPERS.length);entry.putInt("Paper",pattern);message=PAPERS[pattern];}
        data.rooms.put(id.toString(),entry);data.setDirty();p.displayClientMessage(Component.literal(message),true);
    }
    record Wall(BlockPos origin,int paper,boolean hasPhoto,byte[] pixels) implements CustomPacketPayload{
        static final Type<Wall> TYPE=new Type<>(ExpansionPackets.id("skygrid_wall"));
        public Type<Wall> type(){return TYPE;}
    }
    static BiConsumer<Connection,Wall> receiver=(c,w)->{};
    public static void register(RegisterPayloadHandlersEvent e){
        StreamCodec<RegistryFriendlyByteBuf,Wall> codec=StreamCodec.ofMember((w,b)->{b.writeBlockPos(w.origin);b.writeByte(w.paper);b.writeBoolean(w.hasPhoto);b.writeByteArray(w.pixels);},
                b->new Wall(b.readBlockPos(),b.readByte(),b.readBoolean(),b.readByteArray(PngSanitizer.MAX_OUTPUT)));
        e.registrar("skygrid1").optional().playToClient(Wall.TYPE,codec,(w,c)->receiver.accept(c.connection(),w));
    }
    private record Granted(UUID room,int paper,UUID photo){}
    private static final Map<ServerPlayer,Granted> visible=new WeakHashMap<>();
    static void visit(ServerPlayer p,PocketLedger.Pocket room,long tick){
        if(!p.connection.hasChannel(Wall.TYPE))return;
        var entry=data(p).rooms.getCompound(room.id().toString());
        UUID image=entry.hasUUID("Photo")?entry.getUUID("Photo"):null;
        BlockPos origin=new BlockPos(room.x(),65,room.z());
        if(!InteriorImages.wallAllowed(p,room.owner(),image) || !Safety.locationReason(p,p.serverLevel(),origin.offset(16,5,0)).isEmpty())image=null;
        var now=new Granted(room.id(),Math.floorMod(entry.getInt("Paper"),PAPERS.length),image);var old=visible.get(p);
        boolean change=!now.equals(old);
        if(!change && tick%20!=0)return;
        byte[] png={};
        if(image!=null && (old==null || !image.equals(old.photo) || !room.id().equals(old.room))){
            try{png=InteriorImages.wallBytes(p,room.owner(),image);}
            catch(java.io.IOException unavailable){image=null;now=new Granted(room.id(),now.paper,null);}
        }
        PacketDistributor.sendToPlayer(p,new Wall(origin,now.paper,image!=null,png));visible.put(p,now);
    }
    static void end(UUID player,MinecraftServer server){
        if(server==null)return;var p=server.getPlayerList().getPlayer(player);
        if(p!=null && visible.remove(p)!=null && p.connection.hasChannel(Wall.TYPE))
            PacketDistributor.sendToPlayer(p,new Wall(BlockPos.ZERO,-1,false,new byte[0]));
    }
}
