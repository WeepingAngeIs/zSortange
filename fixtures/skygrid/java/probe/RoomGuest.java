package probe;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
@EventBusSubscriber(modid="wallprobe",value=Dist.CLIENT)
public final class RoomGuest {
    @SubscribeEvent public static void tick(ClientTickEvent.Post e)throws Exception{
        var photo=Class.forName("dev.anya.storage.pocket.SkygridWallsClient").getDeclaredField("photo");photo.setAccessible(true);
        if(photo.getBoolean(null) && !Files.exists(Path.of("photo")))Files.writeString(Path.of("photo"),"owner texture received");
        if(Files.exists(Path.of("../Server/FAILED"))){Minecraft.getInstance().stop();return;}
        if(Files.exists(Path.of("../Server/entry.txt"))){
            Files.writeString(Path.of("CHECKS.txt"),"PASS Connected owner returned through the exit.\n");Minecraft.getInstance().stop();
        }
    }
}
