package dev.anya.storage.appearance;

import dev.anya.storage.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit development-only render probe. Never runs unless the launch JVM opts in. */
@EventBusSubscriber(modid=AnyaStorage.ID,value=Dist.CLIENT)
public final class ClientSmoke {
    private static int ticks,phase;
    private static final AtomicInteger saved=new AtomicInteger();
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("anyastorage.clientSmoke"))return;
        var mc=Minecraft.getInstance();
        if(phase==0&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){
            mc.options.guiScale().set(1);mc.resizeDisplay();
            for(String name:ClientLooks.TEXTURES){
                var model=mc.getItemRenderer().getModel(ClientLooks.shell(name),null,null,0);
                if(model.getParticleIcon().contents().name().getPath().contains("missing"))throw new IllegalStateException("Missing backpack model: "+name);
            }
            mc.setScreen(new Preview(false));phase=1;ticks=0;
        }
        if(phase>0&&mc.getOverlay()==null)ticks++;
        if(phase==1&&ticks==30){Screenshot.grab(mc.gameDirectory,"anya-models-client.png",mc.getMainRenderTarget(),c->saved.incrementAndGet());phase=2;}
        if(phase==2&&saved.get()==1){mc.setScreen(new Preview(true));phase=3;ticks=0;}
        if(phase==3&&ticks==30){Screenshot.grab(mc.gameDirectory,"anya-interiors-client.png",mc.getMainRenderTarget(),c->saved.incrementAndGet());phase=4;}
        if(phase==4&&saved.get()==2){mc.setScreen(new dev.anya.storage.guide.GuideScreen(new dev.anya.storage.guide.GuidePacket(0,dev.anya.storage.guide.GuideContent.renderFixture())));phase=5;ticks=0;}
        if(phase==5&&ticks==45){Screenshot.grab(mc.gameDirectory,"anya-guide-client.png",mc.getMainRenderTarget(),c->saved.incrementAndGet());phase=6;}
        if(phase==6&&saved.get()==3){if(mc.screen instanceof dev.anya.storage.guide.GuideScreen guide)guide.setPage(1);phase=7;ticks=0;}
        if(phase==7&&ticks==45){Screenshot.grab(mc.gameDirectory,"anya-guide-recipe-client.png",mc.getMainRenderTarget(),c->saved.incrementAndGet());phase=8;}
        if(phase==8&&saved.get()==4){
            for(String name:List.of("anya-models-client.png","anya-interiors-client.png","anya-guide-client.png","anya-guide-recipe-client.png"))if(!mc.gameDirectory.toPath().resolve("screenshots").resolve(name).toFile().isFile())throw new IllegalStateException("Screenshot was not saved");
            AnyaStorage.LOG.info("ANYA_CLIENT_SMOKE_OK: 22 model variants, six interior resources, client registration and four real renderer captures");phase=9;mc.stop();
        }
    }
    private static final class Preview extends Screen {
        final boolean interiors;
        Preview(boolean interiors){super(Component.literal("Anya asset verification"));this.interiors=interiors;}
        @Override public void render(GuiGraphics g,int mx,int my,float partial){
            g.fill(0,0,width,height,0xff171a28);g.drawString(font,"ANYA STORAGE  |  Minecraft client render inspection",24,20,0xfff4ddea,false);
            g.drawString(font,"Actual bundled assets. This probe does not test multiplayer, wearable positioning or interactive menus.",24,38,0xffbec6d5,false);
            if(interiors){
                int i=0;for(String name:Looks.INTERIORS){int x=24+(i%3)*322,y=68+(i/3)*288;i++;
                    g.drawString(font,Looks.title(name),x,y-12,0xffe9c9de,false);g.blit(ExpansionPackets.id("textures/gui/"+name+".png"),x,y,0,0,304,250,304,250);
                    g.pose().pushPose();g.pose().translate(x+24,y+53,0);g.pose().scale(4.5f,4.5f,1);g.renderItem(ClientLooks.shell(name.equals("botanical")?"leaf_oak":name.equals("ocean")?"water":name.equals("galaxy")?"galaxy":"bow_cat"),0,0);g.pose().popPose();
                    int icon=0;for(var item:Content.UPGRADES.values()){g.renderItem(new net.minecraft.world.item.ItemStack(item.get()),x+125+(icon++%9)*18,y+34);}
                }
            }else{
                int i=0;for(String name:ClientLooks.TEXTURES){int x=24+(i%6)*168,y=66+(i/6)*170;i++;
                    g.fill(x,y,x+154,y+156,0xff252a3b);g.pose().pushPose();g.pose().translate(x+22,y+9,0);g.pose().scale(7,7,1);g.renderItem(ClientLooks.shell(name),0,0);g.pose().popPose();g.drawString(font,Looks.title(name),x+8,y+137,0xffecd3e4,false);
                }
            }
        }
    }
    private ClientSmoke(){}
}
