package dev.anya.storage.pocket;

import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class RoomMediaNet {
    public static final int CHUNK_BYTES=32*1024;
    static BiConsumer<Connection,View> receiver=(connection,packet)->{};
    public interface View extends CustomPacketPayload {}
    private static <P extends CustomPacketPayload> CustomPacketPayload.Type<P> id(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("anyastorage","room_media_"+name));
    }
    public record Play(BlockPos pos,String address) implements CustomPacketPayload {
        public static final Type<Play> TYPE=id("play");
        public Type<Play> type(){return TYPE;}
        @Override public String toString(){return "Room media Play at "+pos;}
    }
    public record Receive(UUID id) implements CustomPacketPayload {
        public static final Type<Receive> TYPE=RoomMediaNet.id("receive");
        public Type<Receive> type(){return TYPE;}
    }
    public record Prepared(UUID id) implements CustomPacketPayload {
        public static final Type<Prepared> TYPE=RoomMediaNet.id("prepared");
        public Type<Prepared> type(){return TYPE;}
    }
    public record Begin(UUID id,BlockPos pos,ResourceLocation dimension,int length,int durationMs,boolean loop,UUID roomId) implements View {
        public static final Type<Begin> TYPE=RoomMediaNet.id("begin");
        public Type<Begin> type(){return TYPE;}
    }
    public record Part(UUID id,int offset,byte[] bytes) implements View {
        public static final Type<Part> TYPE=RoomMediaNet.id("part");
        public Type<Part> type(){return TYPE;}
    }
    public record Clock(UUID id,long startGameTime) implements View {
        public static final Type<Clock> TYPE=RoomMediaNet.id("clock");
        public Type<Clock> type(){return TYPE;}
    }
    public record End(UUID id) implements View {
        public static final Type<End> TYPE=RoomMediaNet.id("end");
        public Type<End> type(){return TYPE;}
    }
    public record Notice(BlockPos pos,String message) implements View {
        public static final Type<Notice> TYPE=id("notice");
        public Type<Notice> type(){return TYPE;}
    }

    public static boolean canControl(ServerPlayer player,CameraDevices.Device display) {
        return RoomMedia.canControl(player, display);
    }
    static void register(RegisterPayloadHandlersEvent e) {
        var r=e.registrar("roommedia1").optional();
        StreamCodec<RegistryFriendlyByteBuf,Play> play=StreamCodec.ofMember((v,b)->{b.writeBlockPos(v.pos);b.writeUtf(v.address,2048);},
                b->new Play(b.readBlockPos(),b.readUtf(2048)));
        r.playToServer(Play.TYPE,play,(v,c)->{if(c.player() instanceof ServerPlayer p)RoomMedia.play(p,v.pos,v.address);});
        StreamCodec<RegistryFriendlyByteBuf,Receive> receive=StreamCodec.ofMember((v,b)->b.writeUUID(v.id),b->new Receive(b.readUUID()));
        r.playToServer(Receive.TYPE,receive,(v,c)->{if(c.player() instanceof ServerPlayer p)RoomMedia.ack(p,v.id,false);});
        StreamCodec<RegistryFriendlyByteBuf,Prepared> prepared=StreamCodec.ofMember((v,b)->b.writeUUID(v.id),b->new Prepared(b.readUUID()));
        r.playToServer(Prepared.TYPE,prepared,(v,c)->{if(c.player() instanceof ServerPlayer p)RoomMedia.ack(p,v.id,true);});
        StreamCodec<RegistryFriendlyByteBuf,Begin> begin=StreamCodec.ofMember((v,b)->{
            b.writeUUID(v.id);b.writeBlockPos(v.pos);b.writeResourceLocation(v.dimension);b.writeVarInt(v.length);
            b.writeVarInt(v.durationMs);b.writeBoolean(v.loop);b.writeUUID(v.roomId);
        }, b->new Begin(b.readUUID(),b.readBlockPos(),b.readResourceLocation(),b.readVarInt(),b.readVarInt(),b.readBoolean(),b.readUUID()));
        r.playToClient(Begin.TYPE,begin,(v,c)->receiver.accept(c.connection(),v));
        StreamCodec<RegistryFriendlyByteBuf,Part> part=StreamCodec.ofMember((v,b)->{
            b.writeUUID(v.id);b.writeVarInt(v.offset);b.writeByteArray(v.bytes);
        }, b->new Part(b.readUUID(),b.readVarInt(),b.readByteArray(CHUNK_BYTES)));
        r.playToClient(Part.TYPE,part,(v,c)->receiver.accept(c.connection(),v));
        StreamCodec<RegistryFriendlyByteBuf,Clock> clock=StreamCodec.ofMember((v,b)->{b.writeUUID(v.id);b.writeLong(v.startGameTime);},
                b->new Clock(b.readUUID(),b.readLong()));
        r.playToClient(Clock.TYPE,clock,(v,c)->receiver.accept(c.connection(),v));
        StreamCodec<RegistryFriendlyByteBuf,End> end=StreamCodec.ofMember((v,b)->b.writeUUID(v.id),b->new End(b.readUUID()));
        r.playToClient(End.TYPE,end,(v,c)->receiver.accept(c.connection(),v));
        StreamCodec<RegistryFriendlyByteBuf,Notice> notice=StreamCodec.ofMember((v,b)->{b.writeBlockPos(v.pos);b.writeUtf(v.message,200);},
                b->new Notice(b.readBlockPos(),b.readUtf(200)));
        r.playToClient(Notice.TYPE,notice,(v,c)->receiver.accept(c.connection(),v));
    }
    private RoomMediaNet(){}
}
