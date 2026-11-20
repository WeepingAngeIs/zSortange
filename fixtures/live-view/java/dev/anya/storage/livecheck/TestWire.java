package dev.anya.storage.livecheck;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
@EventBusSubscriber(modid=LiveTest.ID,bus=EventBusSubscriber.Bus.MOD)
public final class TestWire {
 public record Signal(int sequence,String action) implements CustomPacketPayload{
  public static final Type<Signal> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(LiveTest.ID,"signal"));
  public static final StreamCodec<RegistryFriendlyByteBuf,Signal> CODEC=StreamCodec.of((b,v)->{b.writeInt(v.sequence);b.writeUtf(v.action,4096);},b->new Signal(b.readInt(),b.readUtf(4096)));
  public Type<Signal> type(){return TYPE;}
 }
 @SubscribeEvent public static void register(RegisterPayloadHandlersEvent e){e.registrar("1").playBidirectional(Signal.TYPE,Signal.CODEC,(s,c)->c.enqueueWork(()->{if(c.player() instanceof net.minecraft.server.level.ServerPlayer p)LiveTest.receive(p,s);else LiveClient.receive(s);}));}
}
