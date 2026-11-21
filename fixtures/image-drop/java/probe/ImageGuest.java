package probe;

import dev.anya.storage.StoreMenu;
import dev.anya.storage.appearance.ClientInteriors;
import dev.anya.storage.appearance.ClientLooks;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@EventBusSubscriber(modid="imageprobe",value=Dist.CLIENT)
public final class ImageGuest {
    static int stage;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        var mc=Minecraft.getInstance();
        if(mc.player==null||mc.screen==null||!(mc.player.containerMenu instanceof StoreMenu menu))return;
        var look=ClientInteriors.current;
        if(look==null||look.menu()!=menu.containerId||look.session()!=menu.session()||!menu.storageView())return;
        if(stage==0){
            ClientLooks.options.images=false;
            if(ClientInteriors.imagesVisible())throw new AssertionError("Initial display preference");
            var png=Path.of("sample.png");
            ImageIO.write(new BufferedImage(48,32,BufferedImage.TYPE_INT_RGB),"png",png.toFile());
            stage++;mc.screen.onFilesDrop(List.of(png));
        }else if(stage==1 && ClientInteriors.texture()!=null){
            if(ClientLooks.options.images || !ClientInteriors.imagesVisible() || ClientInteriors.size()[0]!=48)
                throw new AssertionError("Wrong image or preference");
            Files.writeString(Path.of("CHECKS.txt"),"PASS A non-operator with Images off dropped one PNG on the real backpack screen and received the visible private texture. Its dimensions and saved display preference matched.\n");
            stage++;mc.stop();
        }
    }
}