package dev.anya.storage;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class Packets {
    public record Action(int menu,int session,int revision,String verb,String text,int value) implements CustomPacketPayload {
        public static final Type<Action> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"action"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Action> CODEC=new StreamCodec<>() {
            public Action decode(RegistryFriendlyByteBuf b) { return new Action(b.readVarInt(),b.readInt(),b.readVarInt(),b.readUtf(16),b.readUtf(256),b.readVarInt()); }
            public void encode(RegistryFriendlyByteBuf b,Action p) { b.writeVarInt(p.menu);b.writeInt(p.session);b.writeVarInt(p.revision);b.writeUtf(p.verb,16);b.writeUtf(p.text,256);b.writeVarInt(p.value); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        TabletThreatCue.register(event);
        event.registrar("4").playToServer(Action.TYPE,Action.CODEC,(p,context)->context.enqueueWork(()->{
            if(!(context.player() instanceof ServerPlayer player) || player.isSpectator() || !player.isAlive()) return;
            try {
                if(player.containerMenu instanceof StoreMenu menu && p.menu==menu.containerId) menu.action(p);
                else if(p.menu==-1 && player.containerMenu==player.inventoryMenu) {
                    switch(p.verb) {
                        case "quick" -> Network.quick(player,false);
                        case "dump" -> Network.quick(player,true);
                        case "undo_dump" -> Network.undoNearby(player);
                        case "bag" -> StorageItem.openFirst(player);
                        default -> { }
                    }
                }
            } catch(Exception error) { AnyaStorage.LOG.error("Rejected storage action from {}",player.getUUID(),error);AnyaStorage.tell(player,"Action stopped. No retry was attempted; check server log."); }
        }));
    }
}
