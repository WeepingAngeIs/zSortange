package dev.anya.storage.liveview.mixin;
import dev.anya.storage.liveview.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
/** CE has already wrapped redirection and flushed any collected bundle at this boundary. */
@Mixin(value=ServerCommonPacketListenerImpl.class,priority=900)
public abstract class FinalSendFence {
 @Redirect(method="send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",at=@At(value="INVOKE",target="Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V"))
 private void send(Connection connection,Packet<?> packet,PacketSendListener listener,boolean flush){
  if((Object)this instanceof ServerGamePacketListenerImpl game){var allowed=PacketFence.filter(game.player,packet);if(allowed!=null)connection.send(allowed,listener,flush);}
  else connection.send(packet,listener,flush);
 }
}
