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
    private static boolean tapped,done,clicked;
    private static int ceilingPackets,initialCeiling;
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
        var sky=Class.forName("dev.anya.storage.pocket.SkyCeilingClient");
        var skyReceiver=field(Class.forName("dev.anya.storage.pocket.SkyCeiling"),"receiver");
        var show=(BiConsumer<Connection,Object>)skyReceiver.get(null);
        skyReceiver.set(null,(BiConsumer<Connection,Object>)(connection,frame)->{
            try{
                if(pixels(frame).length>0){
                    ceilingPackets++;
                    if(!OWNER)Orders.write("FAIL-"+ROLE,"Received another player's live ceiling");
                }
                show.accept(connection,frame);
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
            order=next;done=false;age=0;stable=0;atPositive=positive;atEmpty=empty;initialCeiling=ceilingPackets;clicked=false;diagnostic="";
            if(!order.action().equals("use"))mc.gui.getChat().clearMessages(false);
        }
        if(done)return;
        mc.options.keyShift.setDown(order.sneak());
        age++;
        if(age%15==0)Orders.write(ROLE+".status",order.step()+" ticks="+age+" positive="+positive+" empty="+empty+" "+diagnostic);
        if(!mc.level.dimension().location().toString().equals(order.dimension()))return;
        if(order.action().startsWith("ui.")){ui(mc,order.action().substring(3));return;}
        if(mc.screen!=null)return;
        if(mc.player.position().distanceToSqr(order.x(),order.y(),order.z())>.64)return;
        if(mc.player.isShiftKeyDown()!=order.sneak() || age<7)return;
        if(order.action().equals("tablet")){
            if(mc.player.getMainHandItem().is(dev.anya.storage.Content.CUPID_TABLET.get())){
                mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);acknowledge("Used the bound tablet inside Skygrid");
            }
            return;
        }
        if(order.action().startsWith("sky.")||order.action().equals("cinema")){stable++;return;}
        var hit=mc.player.pick(mc.player.blockInteractionRange(),1,false);
        diagnostic="ray="+(hit instanceof BlockHitResult h?h.getType()+" "+h.getBlockPos()+" "+h.getDirection():hit.getType())
                +" hand="+mc.player.getMainHandItem()+" pos="+mc.player.position();
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
        if(order.action().startsWith("ui.")||order.action().equals("tablet"))return;
        if(order.action().startsWith("sky.")){
            var active=field(Class.forName("dev.anya.storage.pocket.SkyCeilingClient"),"current").get(null);
            if(active==null||initialCeiling==ceilingPackets)return;
            var data=pixels(active);if(data.length!=1024)return;
            long count=java.util.Arrays.stream(data).filter(c->c==0x47384f).count();
            boolean expected=order.action().equals("sky.fly");
            if(expected!=(count>0))return;
            int center=data[16*32+16];
            if(order.color()>=0&&(order.color()>=0x4000000?(center&0xff000000)!=order.color():center!=order.color()))return;
            photo("Sky camera samples="+data.length+" visible player pixels="+count+" center="+Integer.toHexString(center));return;
        }
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
                if(order.action().equals("motion")){
                    if(!"minecraft:creeper".equals(field(CameraScreen.class,"motion").get(null))
                            ||java.util.Arrays.stream(pixels(frame)).noneMatch(c->c==0xd4b9db))return;
                    expected=-1;
                }
                if(order.action().equals("cinema")&&pixels(frame).length!=1024)return;
                if(expected>=0&&(expected>=0x4000000?(sensor&0xff000000)!=expected:sensor!=expected)){
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

    private static void photo(String label)throws Exception{
        var mc=Minecraft.getInstance();try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){
            image.writeToFile(Orders.ROOT.resolve(order.step()+"-"+ROLE+".png"));
        }
        acknowledge(label);
    }
    @SubscribeEvent public static void uiFrame(ScreenEvent.Render.Post event)throws Exception{
        if(!done&&order!=null&&order.action().equals("ui.photo")&&age>9)photo(event.getScreen().getTitle().getString());
    }
    private static void ui(Minecraft mc,String action)throws Exception{
        if(age<6||mc.screen==null)return;
        var screen=mc.screen;
        if(action.equals("photo"))return;
        if(action.equals("close")){screen.onClose();acknowledge("Closed the player screen");return;}
        if(screen instanceof dev.anya.storage.Client.Screen store){
            var menu=store.getMenu();
            if(action.equals("cosmetics")&&clicked&&menu.getSlot(49).getItem().getHoverName().getString().startsWith("Live ceiling")
                    ||action.equals("ceiling")&&clicked&&menu.getSlot(49).getItem().getHoverName().getString().contains("ON")){
                acknowledge("Confirmed tablet change "+action);return;
            }
            if(clicked&&age%20!=0)return;
            double x=46,y=184;
            var layout=(dev.anya.storage.ui.StorageGrid.Layout)field(store.getClass(),"layout").get(store);
            if(layout==null)return;
            if(action.equals("ceiling")){var b=layout.control(4);x=b.x()+6;y=b.y()+6;}
            if(!action.equals("ceiling")&&!action.equals("cosmetics"))return;
            screen.mouseClicked(layout.left()+x*layout.scale(),layout.top()+y*layout.scale(),0);clicked=true;return;
        }
        if(!screen.getClass().getSimpleName().equals("CameraControlsScreen"))return;
        if((boolean)field(screen.getClass(),"waiting").get(screen))return;
        if(clicked){
            if(action.equals("blocked")){
                var view=field(screen.getClass(),"v").get(screen);var m=view.getClass().getDeclaredMethod("message");m.setAccessible(true);
                check(m.invoke(view).toString().contains("Clear the space"),"No blocked-wall explanation: "+m.invoke(view));
            }
            acknowledge("Server acknowledged settings button "+action);return;
        }
        String prefix=switch(action){
            case "width","blocked"->"Width:";case "height"->"Height:";case "group"->"Targets:";case "watch"->"Watch";
            case "ignore"->"Ignore";case "fit"->"Fit wall";default->throw new AssertionError(action);
        };
        if(action.equals("watch")||action.equals("ignore")){
            var search=(net.minecraft.client.gui.components.EditBox)field(screen.getClass(),"search").get(screen);
            if(search==null)return;search.setValue(action.equals("watch")?"minecraft:creeper":"minecraft:cow");
        }
        for(var widget:java.util.List.copyOf(screen.children()))if(widget instanceof net.minecraft.client.gui.components.Button b
                &&b.active&&b.getMessage().getString().startsWith(prefix)){
            screen.mouseClicked(b.getX()+b.getWidth()/2.,b.getY()+b.getHeight()/2.,0);clicked=true;return;
        }
        diagnostic="Waiting for button "+prefix+" on "+screen.getTitle().getString();
    }
    private static void acknowledge(String evidence)throws Exception{
        Orders.write(order.step()+"-"+ROLE+".ok",evidence);
        done=true;
    }
}

