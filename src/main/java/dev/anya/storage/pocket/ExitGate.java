package dev.anya.storage.pocket;

import java.util.*;
import java.util.function.BiConsumer;
import dev.anya.storage.ExpansionPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ExitGate {
    static final int[] COLORS={0xb397ff,0xff94c8,0x7fdfff,0x83edba,0xffcf79,0xff8369};
    static final String[] NAMES={"Lavender","Rose","Ice blue","Mint","Gold","Coral"};
    record Marker(BlockPos stone,int color) implements CustomPacketPayload {
        static final Type<Marker> TYPE=new Type<>(ExpansionPackets.id("home_exit"));
        public Type<Marker> type(){return TYPE;}
    }
    static BiConsumer<Connection,Marker> receiver=(c,m)->{};
    public static void register(RegisterPayloadHandlersEvent e){
        StreamCodec<RegistryFriendlyByteBuf,Marker> codec=StreamCodec.ofMember(
                (m,b)->{b.writeBlockPos(m.stone);b.writeByte(m.color);},b->new Marker(b.readBlockPos(),b.readByte()));
        e.registrar("homeexit1").optional().playToClient(Marker.TYPE,codec,(m,c)->c.enqueueWork(()->receiver.accept(c.connection(),m)));
    }
    private static final class Palette extends SavedData{
        final CompoundTag colors;
        Palette(){colors=new CompoundTag();}
        Palette(CompoundTag tag,HolderLookup.Provider lookup){colors=tag.getCompound("Colors").copy();}
        public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){tag.put("Colors",colors.copy());return tag;}
    }
    private static Palette palette(ServerPlayer p){return p.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(Palette::new,Palette::new,null),"anya_room_exit_colors");}
    public static int color(ServerPlayer p,UUID id){return Math.floorMod(palette(p).colors.getInt(id.toString()),COLORS.length);}
    public static void cycle(ServerPlayer p,UUID id){
        if(!PocketService.visitingOwner(p,id))return;
        var data=palette(p);int index=(color(p,id)+1)%COLORS.length;data.colors.putInt(id.toString(),index);data.setDirty();
        p.displayClientMessage(Component.literal("Exit portal color: "+NAMES[index]),true);
    }
    private record Track(UUID room,Vec3 previous,int color){}
    private static final Map<UUID,Track> tracks=new HashMap<>();
    static void end(UUID id){tracks.remove(id);}
    static void clear(){tracks.clear();}
    static Vec3 center(BlockPos stone){return new Vec3(stone.getX()+.5,stone.getY(),stone.getZ()-1.25);}
    static boolean crossed(Vec3 a,Vec3 b,Vec3 gate){
        double dz=b.z-a.z;
        if(dz==0 || a.distanceToSqr(b)>16)return false;
        double t=(gate.z-a.z)/dz;
        if(t<=0 || t>1)return false;
        double x=a.x+t*(b.x-a.x)-gate.x,y=a.y+t*(b.y-a.y)-gate.y;
        double side=x/1.28,height=(y+.85-1.6)/1.52;
        return y>=-.15 && side*side+height*height<1;
    }
    static void visit(ServerPlayer p,PocketLedger.Pocket room,long tick){
        // Do not expose an invisible trigger to clients without this renderer.
        if(p.connection==null || !p.connection.hasChannel(Marker.TYPE))return;
        var stone=PocketService.exitPosition(room);var track=tracks.get(p.getUUID());
        int tint=track==null || tick%10==0?color(p,room.id()):track.color;
        if(track==null || tint!=track.color || tick%40==0 || !room.id().equals(track.room))
            PacketDistributor.sendToPlayer(p,new Marker(stone,tint));
        if(track!=null && room.id().equals(track.room) && crossed(track.previous,p.position(),center(stone))){
            PocketService.recover(p);return;
        }
        tracks.put(p.getUUID(),new Track(room.id(),p.position(),tint));
    }
}
