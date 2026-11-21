package probe;

import com.mojang.blaze3d.platform.NativeImage;
import dev.anya.storage.pocket.CameraFeed;
import dev.anya.storage.pocket.CameraScreen;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import static probe.Orders.check;

/** Drive the client's normal Use and crouch controls. Audit every camera frame before display filtering. */
@EventBusSubscriber(modid="roomroles",value=Dist.CLIENT)
public final class PlayerControls {
    private static final String ROLE=System.getProperty("roomroles.name");
    private static final boolean OWNER=ROLE.equals("CamOwner");
    private static Orders.Order order;
    private static int positive,empty,atPositive,atEmpty,age,stable;
    private static boolean tapped,done;
    private static String diagnostic="";
    private static Field field(Class<?> type,String name)throws Exception{
        var f=type.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static int[] pixels(Object packet)throws Exception{
        var method=packet.getClass().getDeclaredMethod("pixels");method.setAccessible(true);return (int[])method.invoke(packet);
    }
    @SuppressWarnings("unchecked")
    private static void observeWire()throws Exception{
        Class.forName(CameraScreen.class.getName(),true,CameraScreen.class.getClassLoader());
        var receiver=field(CameraFeed.class,"receiver");
        var display=(BiConsumer<Connection,Object>)receiver.get(null);
        receiver.set(null,(BiConsumer<Connection,Object>)(connection,frame)->{
            try{
                if(pixels(frame).length==0)empty++;else positive++;
                if(!OWNER && positive>0)Orders.write("FAIL-"+ROLE,"Received private camera pixels at "+(order==null?"login":order.step()));
                display.accept(connection,frame);
            }catch(Exception failure){throw new IllegalStateException(failure);}
        });
        tapped=true;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        try{update();}catch(Exception|AssertionError failure){Orders.write("FAIL-"+ROLE,(order==null?"login":order.step())+": "+failure);throw failure;}
    }
    private static void update()throws Exception{
        var mc=Minecraft.getInstance();
        if(Files.exists(Orders.ROOT.resolve("quit"))){mc.stop();return;}
        if(mc.player==null || mc.level==null || mc.gameMode==null)return;
        if(!tapped)observeWire();
        var file=Orders.ROOT.resolve(ROLE+".json");
        if(!Files.exists(file))return;
        var next=Orders.JSON.fromJson(Files.readString(file),Orders.Order.class);
        if(order==null || !order.step().equals(next.step())){
            order=next;done=false;age=0;stable=0;atPositive=positive;atEmpty=empty;diagnostic="";
        }
        if(done)return;
        mc.options.keyShift.setDown(order.sneak());
        age++;
        if(age%15==0)Orders.write(ROLE+".status",order.step()+" ticks="+age+" positive="+positive+" empty="+empty+" "+diagnostic);
        if(!mc.level.dimension().location().toString().equals(order.dimension()) || mc.screen!=null)return;
        if(mc.player.position().distanceToSqr(order.x(),order.y(),order.z())>.64)return;
        if(mc.player.isShiftKeyDown()!=order.sneak() || age<7)return;
        var hit=mc.player.pick(mc.player.blockInteractionRange(),1,false);
        diagnostic="ray="+hit+" hand="+mc.player.getMainHandItem()+" pos="+mc.player.position();
        if(!(hit instanceof BlockHitResult block) || hit.getType()!=HitResult.Type.BLOCK || !block.getBlockPos().equals(order.target())){
            stable=0;return;
        }
        if(!order.face().isEmpty() && !block.getDirection().getName().equals(order.face()))return;
        if(!BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString().equals(order.item()))return;
        stable++;
        if(order.action().equals("use") && stable>=3){
            var result=mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,block);
            mc.player.swing(InteractionHand.MAIN_HAND);
            acknowledge("normal Survival Use "+result+" "+block);
        }
    }
    @SubscribeEvent public static void frame(RenderGuiEvent.Post event)throws Exception{
        if(done || order==null || stable<7 || order.action().equals("use"))return;
        try{capture();}catch(Exception|AssertionError failure){Orders.write("FAIL-"+ROLE,order.step()+": "+failure);throw failure;}
    }
    private static void capture()throws Exception{
        int sensor=-1;
        if(!order.action().equals("photo")){
            var target=(net.minecraft.world.level.block.entity.BlockEntity)field(CameraScreen.class,"target").get(null);
            if(target==null || !target.getBlockPos().equals(order.target()))return;
            var frame=field(CameraScreen.class,"current").get(null);
            if(order.action().equals("private")){
                check(!OWNER && positive==0 && frame==null,"Private pixels received or displayed");
                if(empty-atEmpty<3)return;
            }else{
                if(frame==null || positive==atPositive)return;
                sensor=pixels(frame)[136];
                int expected=order.color();
                if(expected>=0x4000000?(sensor&0xff000000)!=expected:sensor!=expected){
                    diagnostic="waiting for source pixel "+Integer.toHexString(expected)+", received "+Integer.toHexString(sensor);return;
                }
            }
        }
        var mc=Minecraft.getInstance();
        try(NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){
            int rgba=image.getPixelRGBA(image.getWidth()/2+7,image.getHeight()/2-7);
            int r=rgba&255,g=(rgba>>8)&255,b=(rgba>>16)&255;
            if(order.action().equals("view") && order.step().startsWith("wall-")){
                boolean matches=order.step().contains("red")?r>g+12 && r>b+12:
                        order.step().contains("lime")?g>r+12 && g>b+12:b>r+12;
                diagnostic="framebuffer="+r+","+g+","+b;
                if(!matches)return;
            }
            image.writeToFile(Orders.ROOT.resolve(order.step()+"-"+ROLE+".png"));
            acknowledge("received positive="+positive+" empty="+empty+" sensor="+Integer.toHexString(sensor)+" framebuffer="+r+","+g+","+b);
        }
    }
    private static void acknowledge(String evidence)throws Exception{
        Orders.write(order.step()+"-"+ROLE+".ok",evidence);
        done=true;
    }
}

