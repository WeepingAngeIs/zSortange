package dev.anya.storage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;
public final class Packets {
    private Packets(){}
    public record Input(UUID session,int sequence,String text) implements CustomPacketPayload {
        public static final Type<Input> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"input"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Input> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.session);b.writeVarInt(p.sequence);b.writeUtf(p.text,256);},b->new Input(b.readUUID(),b.readVarInt(),b.readUtf(256)));
        @Override public Type<Input> type(){return TYPE;}
    }
    public record Shortcut(int action) implements CustomPacketPayload {
        public static final Type<Shortcut> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"shortcut"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Shortcut> CODEC=StreamCodec.of((b,p)->b.writeVarInt(p.action),b->new Shortcut(b.readVarInt()));
        @Override public Type<Shortcut> type(){return TYPE;}
    }
    public record Config(String json) implements CustomPacketPayload {
        public static final Type<Config> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"rules"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Config> CODEC=StreamCodec.of((b,p)->b.writeUtf(p.json,200000),b->new Config(b.readUtf(200000)));
        @Override public Type<Config> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent e){
        var r=e.registrar("2");
        r.playToServer(Input.TYPE,Input.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer sp && sp.containerMenu instanceof StorageMenu menu && menu.session.equals(p.session))menu.input(p.sequence,p.text);});
        r.playToServer(Shortcut.TYPE,Shortcut.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer sp && sp.containerMenu==sp.inventoryMenu && (p.action==0||p.action==1))StorageOps.quickStack(sp,p.action==1);});
        r.playToClient(Config.TYPE,Config.CODEC,(p,c)->{Rules.Config next=Rules.JSON.fromJson(p.json,Rules.Config.class);Rules.validate(next);Rules.current=next;});
    }
    public static void sync(ServerPlayer p){PacketDistributor.sendToPlayer(p,new Config(Rules.JSON.toJson(Rules.current)));}
    public static void syncAll(MinecraftServer server){for(ServerPlayer p:server.getPlayerList().getPlayers())sync(p);}
}
