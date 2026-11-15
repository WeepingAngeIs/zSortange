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
    private static int ticks,phase,gridIndex;
    private static final int[][] GRIDS={{27,2,0},{45,2,0},{63,2,0},{63,2,99},{81,2,99},{108,2,0},{108,2,99},{144,2,99},{216,2,0},{216,2,99},{27,1,0},{27,3,0},{27,4,0},{108,4,99}};
    private static final List<String> gridCaptures=new ArrayList<>();
    private static long gridStarted;
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
        if(phase==4&&saved.get()==2){mc.options.guiScale().set(2);mc.resizeDisplay();mc.setScreen(new dev.anya.storage.guide.GuideScreen(new dev.anya.storage.guide.GuidePacket(0,dev.anya.storage.guide.GuideContent.renderFixture())));phase=5;ticks=0;}
        if(phase==5&&ticks==45){Screenshot.grab(mc.gameDirectory,"anya-guide-client.png",mc.getMainRenderTarget(),c->saved.incrementAndGet());phase=6;}
        if(phase==6&&saved.get()==3){if(mc.screen instanceof dev.anya.storage.guide.GuideScreen guide)guide.setPage(1);phase=7;ticks=0;}
        if(phase==7&&ticks==45){Screenshot.grab(mc.gameDirectory,"anya-guide-recipe-client.png",mc.getMainRenderTarget(),c->saved.incrementAndGet());phase=8;}
        if(phase==8&&saved.get()==4){
            var original=mc.options.keyMappings;
            var collision=new net.minecraft.client.KeyMapping("key.anyastorage.smoke_collision",org.lwjgl.glfw.GLFW.GLFW_KEY_B,"key.categories.anyastorage");
            var expanded=Arrays.copyOf(original,original.length+1);expanded[original.length]=collision;mc.options.keyMappings=expanded;
            if(Client.bagConflict()==null)throw new IllegalStateException("Binding collision was not detected");
            mc.options.keyMappings=original;
            gridIndex=0;gridStarted=System.nanoTime();openGrid();phase=10;ticks=0;
        }
        if(phase==10&&ticks==18){
            int[] probe=GRIDS[gridIndex];String name="anya-grid-"+probe[0]+"-scale"+probe[1]+"-row"+probe[2]+"-client.png";gridCaptures.add(name);
            Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),c->saved.incrementAndGet());phase=11;
        }
        if(phase==11&&saved.get()==5+gridIndex){
            gridIndex++;
            if(gridIndex<GRIDS.length){openGrid();phase=10;ticks=0;}
            else{
                for(String name:gridCaptures)if(!mc.gameDirectory.toPath().resolve("screenshots").resolve(name).toFile().isFile())throw new IllegalStateException("Missing grid capture "+name);
                AnyaStorage.LOG.info("ANYA_CLIENT_SMOKE_OK: 22 models, six interiors, guide and {} production-screen fixtures; scales 1..4; binding conflict detected without changes",GRIDS.length);
                AnyaStorage.LOG.info("ANYA_UI_PROFILE: capture sequence {} ms; sampled total JVM heap {} bytes; render shells bounded to 22; this is software GL, not a hardware FPS/VRAM benchmark",(System.nanoTime()-gridStarted)/1_000_000,java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                phase=12;mc.stop();
            }
        }
    }
    private static void openGrid(){
        var mc=Minecraft.getInstance();int[] probe=GRIDS[gridIndex];mc.options.guiScale().set(probe[1]);mc.resizeDisplay();
        var inventory=new net.minecraft.world.entity.player.Inventory(null);
        inventory.setItem(9,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LEAVES,24));
        inventory.setItem(10,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STRING,8));
        var menu=new StoreMenu(121,inventory);
        int rows=dev.anya.storage.ui.StorageGrid.layout(mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight(),probe[0],true).rows();
        menu.renderFixture(probe[0],probe[2],rows);
        mc.setScreen(new GridPreview(new Client.Screen(menu,inventory,Component.literal("Oak Grove / "+probe[0]+" slots (render fixture)"))));
        ClientInteriors.current=new ExpansionPackets.Look(-1,UUID.randomUUID(),121,0,"leaf_oak","leaf","botanical",ExpansionPackets.NONE,false);
    }

    /** The production renderer is initialized normally; this title-screen fixture has no LocalPlayer. */
    private static final class GridPreview extends Screen {
        private final Client.Screen delegate;
        GridPreview(Client.Screen delegate){super(Component.literal("Anya storage render fixture"));this.delegate=delegate;}
        @Override protected void init(){delegate.init(minecraft,width,height);}
        @Override public void render(GuiGraphics g,int mx,int my,float partial){delegate.render(g,mx,my,partial);}
        @Override public void removed(){delegate.removed();}
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
