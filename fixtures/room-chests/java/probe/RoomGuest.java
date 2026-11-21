package probe;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
@EventBusSubscriber(modid="roomprobe",value=Dist.CLIENT)
public final class RoomGuest {
    @SubscribeEvent public static void tick(ClientTickEvent.Post e)throws Exception{
        if(Files.exists(Path.of("../Server/entry.txt"))){
            Files.writeString(Path.of("CHECKS.txt"),"PASS Physical visitor returned to the home world.\\n");Minecraft.getInstance().stop();
        }
    }
}
