package dev.anya.storage.acceptance;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid=Acceptance.ID,bus=EventBusSubscriber.Bus.MOD)
public final class Wire {
    public record Signal(int sequence,String action) implements CustomPacketPayload {
        public static final Type<Signal> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(Acceptance.ID,"signal"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Signal> CODEC=StreamCodec.of((b,v)->{b.writeInt(v.sequence);b.writeUtf(v.action,256);},b->new Signal(b.readInt(),b.readUtf(256)));
        @Override public Type<Signal> type(){return TYPE;}
    }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent e){
        PayloadRegistrar r=e.registrar("1");
        r.playBidirectional(Signal.TYPE,Signal.CODEC,(s,c)->c.enqueueWork(()->{
            if(c.player() instanceof net.minecraft.server.level.ServerPlayer p){if(Boolean.getBoolean("anya.coldRecoveryAcceptance"))ColdRecoveryProbe.ack(p,s);else ServerProbe.ack(p,s);}
            else ClientProbe.receive(s);
        }));
    }
}
