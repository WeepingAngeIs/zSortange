package dev.anya.storage.furniture;

import dev.anya.storage.AnyaStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.BiConsumer;

/** A permitted viewer receives only the base item ID and the owner's plain-text public-facing caption. */
@EventBusSubscriber(modid=AnyaStorage.ID,bus=EventBusSubscriber.Bus.MOD)
public final class ShelfPackets {
    static BiConsumer<Connection,Display> receiver = (connection,display)->{};
    private static final Map<ServerPlayer,Integer> last = new WeakHashMap<>();
    private ShelfPackets() {}
    record Focus(BlockPos pos,long token) implements CustomPacketPayload {
        static final Type<Focus> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"shelf_focus"));
        static final StreamCodec<RegistryFriendlyByteBuf,Focus> CODEC=StreamCodec.ofMember(Focus::write,
                b->new Focus(b.readBlockPos(),b.readLong()));
        private void write(RegistryFriendlyByteBuf b) { b.writeBlockPos(pos);b.writeLong(token); }
        public Type<Focus> type() { return TYPE; }
    }
    record Display(BlockPos pos,long token,ResourceLocation item,String caption) implements CustomPacketPayload {
        static final Type<Display> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"shelf_display"));
        static final StreamCodec<RegistryFriendlyByteBuf,Display> CODEC=StreamCodec.ofMember(Display::write,
                b->{ var pos=b.readBlockPos();long token=b.readLong();
                    return b.readBoolean() ? new Display(pos,token,b.readResourceLocation(),b.readUtf(FurnitureEntity.TEXT_LIMIT))
                            : new Display(pos,token,null,""); });
        private void write(RegistryFriendlyByteBuf b) {
            b.writeBlockPos(pos);b.writeLong(token);b.writeBoolean(item!=null);
            if(item!=null) { b.writeResourceLocation(item);b.writeUtf(caption,FurnitureEntity.TEXT_LIMIT); }
        }
        public Type<Display> type() { return TYPE; }
    }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent e) {
        var r=e.registrar("keepsake1").optional();
        r.playToServer(Focus.TYPE,Focus.CODEC,(query,ctx)->{
            if (!(ctx.player() instanceof ServerPlayer p) || p.connection.getConnection()!=ctx.connection()
                    || !p.connection.hasChannel(Display.TYPE) || query.token()<=0) return;
            int now=p.getServer().getTickCount();
            Integer previous=last.get(p);
            if(previous!=null && now-previous<4) return;
            last.put(p,now);
            FurnitureEntity f=Furniture.target(p);
            if(f==null || !query.pos().equals(f.getBlockPos()) || !Furniture.canSee(p,f)) {
                PacketDistributor.sendToPlayer(p,new Display(query.pos(),query.token(),null,""));return;
            }
            var id=BuiltInRegistries.ITEM.getKey(f.inventory.getItem(0).getItem());
            PacketDistributor.sendToPlayer(p,new Display(f.getBlockPos(),query.token(),id,f.caption));
        });
        r.playToClient(Display.TYPE,Display.CODEC,(display,ctx)->receiver.accept(ctx.connection(),display));
    }
}
