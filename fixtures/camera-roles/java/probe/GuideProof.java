package probe;

import dev.anya.storage.Content;
import dev.anya.storage.guide.GuideScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import java.nio.file.Files;

@EventBusSubscriber(modid="roomroles",value=Dist.CLIENT)
public final class GuideProof {
    private static int phase, heldTicks;
    private static boolean enabled(){return "guide-solo".equals(System.getProperty("roomroles.trial"));}
    static void tick(Minecraft mc)throws Exception{
        if(mc.player.tickCount>500 && phase<5){Orders.write("FAIL-guide","Missing guide evidence at phase "+phase);return;}
        if(phase==0 && mc.screen==null){
            for(int i=0;i<9;i++)if(mc.player.getInventory().getItem(i).is(Content.GUIDE.get())){
                mc.player.getInventory().selected=i;
                if(++heldTicks>=35)phase=1;
                break;
            }
        }else if(phase==2 && ++heldTicks>=46){photo("held-second");phase=3;mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);}
    }
    private static void photo(String name)throws Exception{
        try(var shot=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())){
            shot.writeToFile(Orders.ROOT.resolve(name+".png"));
        }
    }
    @SubscribeEvent public static void held(RenderGuiEvent.Post e)throws Exception{
        if(enabled() && phase==1){photo("held");phase=2;}
    }
    @SubscribeEvent public static void menu(ScreenEvent.Render.Post e)throws Exception{
        if(!enabled() || phase<3 || !(e.getScreen() instanceof GuideScreen guide))return;
        var field=GuideScreen.class.getDeclaredField("openedAt");field.setAccessible(true);
        long age=net.minecraft.Util.getMillis()-field.getLong(guide);
        if(phase==3 && age>=345 && age<650){photo("opening");phase=4;}
        if(phase==4 && age>850){photo("open");guide.keyPressed(268,0,0);phase=5;}
        else if(phase==5 && age>1150){photo("contents");Orders.write("PASS.txt","Starter guide held, used, hologram shown, reading screen and Contents opened.");phase=6;}
    }
}
