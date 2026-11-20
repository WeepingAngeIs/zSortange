package dev.anya.storage.livecheck.mixin;
import dev.anya.storage.livecheck.LiveClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import qouteall.imm_ptl.core.network.PacketRedirectionClient;
import qouteall.imm_ptl.core.api.PortalAPI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=PacketRedirectionClient.class,remap=false)
public abstract class AppliedPacketAudit {
 @Inject(method="handleRedirectedPacket",at=@At("RETURN")) private static void observe(int dim,Packet<ClientGamePacketListener> packet,ClientGamePacketListener listener,CallbackInfo ci){if(Minecraft.getInstance().isSameThread())LiveClient.observe(PortalAPI.clientIntToDimKey(dim),packet);}
}
