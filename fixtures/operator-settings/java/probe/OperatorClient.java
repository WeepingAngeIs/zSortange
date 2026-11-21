package probe;

import java.nio.file.*;
import java.util.*;
import dev.anya.storage.OperatorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid="settingsprobe",value=Dist.CLIENT)
public final class OperatorClient {
    static int stage,ticks;
    static Button button(Screen s,String label){return s.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.active && b.getMessage().getString().equals(label)).findFirst().orElse(null);}
    static List<EditBox> edits(Screen s){return s.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e)throws Exception{
        var mc=Minecraft.getInstance();if(mc.player==null || mc.level==null || ++ticks%5!=0)return;
        switch(stage){
            case 0->{if(mc.screen!=null)return;mc.setScreen(new PauseScreen(true));
                if(button(mc.screen,"Storage server settings")!=null)throw new AssertionError("Guest entry");
                mc.setScreen(null);Files.writeString(Path.of("guest"),"ok");stage++;}
            case 1->{if(!mc.player.hasPermissions(2))return;mc.setScreen(new PauseScreen(true));
                var entry=button(mc.screen,"Storage server settings");if(entry==null)throw new AssertionError("Operator entry");entry.onPress();stage++;}
            case 2->{if(!(mc.screen instanceof OperatorScreen)||button(mc.screen,"Next")==null)return;
                button(mc.screen,"Next").onPress();stage++;}
            case 3->{if(button(mc.screen,"Prev")==null)return;edits(mc.screen).getFirst().setValue("Cupid Tablet opening chance");stage++;}
            case 4->{var fields=edits(mc.screen);var save=button(mc.screen,"Save");
                if(fields.size()!=2 || !fields.get(1).getValue().equals("24") || save==null)return;
                fields.get(1).setValue("31");save.onPress();stage++;}
            case 5->{var fields=edits(mc.screen);if(button(mc.screen,"Save")==null || !fields.get(1).getValue().equals("31"))return;
                Files.writeString(Path.of("applied"),"ok");stage++;}
            case 6->{if(!Files.exists(Path.of("../Server/ready")))return;edits(mc.screen).getFirst().setValue("expansion.chestCameoEnabled");stage++;}
            case 7->{var off=button(mc.screen,"Off");if(off==null)return;off.onPress();stage++;}
            case 8->{if(button(mc.screen,"On")==null)return;Files.writeString(Path.of("toggled"),"ok");stage++;}
            case 9->{if(!Files.exists(Path.of("../Server/CHECKS.txt")))return;Files.writeString(Path.of("CHECKS.txt"),"PASS guest and operator pause menu, single-page navigation, search, direct numeric save and one-click toggle on a connected client.\n");mc.stop();stage++;}
        }
    }
}
