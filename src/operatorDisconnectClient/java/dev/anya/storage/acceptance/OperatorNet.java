package dev.anya.storage.acceptance;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
/** Test directives and observations only. Operator actions are ordinary Minecraft command packets. */
@EventBusSubscriber(modid=Acceptance.ID,bus=EventBusSubscriber.Bus.MOD)
public final class OperatorNet {
 public record Message(int sequence,String verb,String text) implements CustomPacketPayload {
  public static final Type<Message> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(Acceptance.ID,"operator_observation"));
  public static final StreamCodec<RegistryFriendlyByteBuf,Message> CODEC=StreamCodec.of((b,v)->{b.writeInt(v.sequence);b.writeUtf(v.verb,32);b.writeUtf(v.text,8192);},b->new Message(b.readInt(),b.readUtf(32),b.readUtf(8192)));
  public Type<Message> type(){return TYPE;}
 }
 @SubscribeEvent public static void register(RegisterPayloadHandlersEvent e){e.registrar("1").playBidirectional(Message.TYPE,Message.CODEC,(m,c)->c.enqueueWork(()->{
  if(c.player() instanceof net.minecraft.server.level.ServerPlayer p)RepairClientProbe.observe(p,m);else OperatorClient.receive(m);
 }));}
 private OperatorNet(){}
}
