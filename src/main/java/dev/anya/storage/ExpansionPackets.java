package dev.anya.storage;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.Consumer;

/** Small bounded wire messages. Public looks never carry backpack contents, names, image IDs or file paths. */
public final class ExpansionPackets {
    private static final dev.anya.storage.images.ImageRateGate IMAGE_GATE=new dev.anya.storage.images.ImageRateGate();
    public static void clearImageGate(){IMAGE_GATE.clear();}
    public static final UUID NONE=new UUID(0,0);
    public static Consumer<Look> lookReceiver=p->{};
    public static Consumer<ImageReply> imageReceiver=p->{};
    public record Look(int entity,UUID uuid,int menu,int session,String texture,String element,String interior,UUID image,boolean wardrobe,String bagTitle) implements CustomPacketPayload {
        public Look(int entity,UUID uuid,int menu,int session,String texture,String element,String interior,UUID image,boolean wardrobe){
            this(entity,uuid,menu,session,texture,element,interior,image,wardrobe,"");
        }
        public static final Type<Look> TYPE=new Type<>(id("look"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Look> CODEC=new StreamCodec<>(){
            public Look decode(RegistryFriendlyByteBuf b){int entity=b.readVarInt();return new Look(entity,b.readUUID(),b.readVarInt(),b.readInt(),b.readUtf(32),b.readUtf(16),b.readUtf(16),b.readUUID(),b.readBoolean(),entity<0 && b.isReadable()?b.readUtf(128):"");}
            public void encode(RegistryFriendlyByteBuf b,Look p){b.writeVarInt(p.entity);b.writeUUID(p.uuid);b.writeVarInt(p.menu);b.writeInt(p.session);b.writeUtf(p.texture,32);b.writeUtf(p.element,16);b.writeUtf(p.interior,16);b.writeUUID(p.image);b.writeBoolean(p.wardrobe);if(p.entity<0 && !p.bagTitle.isEmpty())b.writeUtf(p.bagTitle,128);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record ImageWire(int menu,int session,int op,UUID token,int sequence,int total,byte[] bytes) implements CustomPacketPayload {
        public static final Type<ImageWire> TYPE=new Type<>(id("interior_upload"));
        public static final StreamCodec<RegistryFriendlyByteBuf,ImageWire> CODEC=new StreamCodec<>(){
            public ImageWire decode(RegistryFriendlyByteBuf b){return new ImageWire(b.readVarInt(),b.readInt(),b.readVarInt(),b.readUUID(),b.readVarInt(),b.readVarInt(),b.readByteArray(16384));}
            public void encode(RegistryFriendlyByteBuf b,ImageWire p){b.writeVarInt(p.menu);b.writeInt(p.session);b.writeVarInt(p.op);b.writeUUID(p.token);b.writeVarInt(p.sequence);b.writeVarInt(p.total);b.writeByteArray(p.bytes);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record ImageReply(int kind,UUID id,int part,int parts,String info,byte[] bytes) implements CustomPacketPayload {
        public static final Type<ImageReply> TYPE=new Type<>(ExpansionPackets.id("interior_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf,ImageReply> CODEC=new StreamCodec<>(){
            public ImageReply decode(RegistryFriendlyByteBuf b){return new ImageReply(b.readVarInt(),b.readUUID(),b.readVarInt(),b.readVarInt(),b.readUtf(160),b.readByteArray(16384));}
            public void encode(RegistryFriendlyByteBuf b,ImageReply p){b.writeVarInt(p.kind);b.writeUUID(p.id);b.writeVarInt(p.part);b.writeVarInt(p.parts);b.writeUtf(p.info,160);b.writeByteArray(p.bytes);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static ResourceLocation id(String key){return ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,key);}
    public static void register(RegisterPayloadHandlersEvent event){
        var r=event.registrar("3");
        r.playToClient(Look.TYPE,Look.CODEC,(p,c)->c.enqueueWork(()->lookReceiver.accept(p)));
        r.playToClient(ImageReply.TYPE,ImageReply.CODEC,(p,c)->c.enqueueWork(()->imageReceiver.accept(p)));
        r.executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK).playToServer(ImageWire.TYPE,ImageWire.CODEC,(p,c)->{
            if(c.player() instanceof ServerPlayer sp && IMAGE_GATE.allow(sp.getUUID(),System.nanoTime()/1_000_000_000L))
                c.enqueueWork(()->dev.anya.storage.images.InteriorImages.receive(sp,p));
        });
    }
    private ExpansionPackets(){}
}
