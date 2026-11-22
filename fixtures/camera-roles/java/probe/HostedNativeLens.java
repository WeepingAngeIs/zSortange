package probe;

import com.mojang.blaze3d.platform.NativeImage;
import dev.anya.storage.pocket.CameraDevices;
import dev.anya.storage.pocket.CameraScene;
import dev.anya.storage.pocket.CameraScreen;
import java.io.*;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import static probe.Orders.check;
import static probe.HostedNativeStage.*;

/** Test-only transport on one synthetic runner. No network or private assets are used. */
@EventBusSubscriber(modid="roomroles",value=Dist.CLIENT)
public final class HostedNativeLens {
    private static final int MAX_BYTES=1_048_576;
    private static int sequence, lastSeen, presentedCount;
    private static String lastState="";
    private static long lastRender, firstRender, renderNanos, byteCount;
    private static boolean watching;
    private static CameraScene patched;
    private static byte[] lastImage;
    private static boolean freshPermit()throws IOException{
        var permit=Orders.ROOT.resolve("native-permit");
        if(!Files.isRegularFile(permit,LinkOption.NOFOLLOW_LINKS))return false;
        long age=System.currentTimeMillis()-Long.parseLong(Files.readString(permit));
        return age>=0 && age<2500;
    }
    private static boolean owner(){return "CamOwner".equals(System.getProperty("roomroles.name"));}
    static String capture(Orders.Order order,String action,Path image,int refusals)throws Exception{
        var mc=Minecraft.getInstance();
        if(action.equals("native.unavailable")){
            check(hosted("sharp-horizon-solo") && owner() && mc.hasSingleplayerServer(),"Expected private hosted solo world");
            var server=mc.getSingleplayerServer();
            if(refusals==0 || !server.submit(()->server.overworld().getChunkSource()
                    .getChunkNow(LENS.getX()>>4,LENS.getZ()>>4)==null).get(5,java.util.concurrent.TimeUnit.SECONDS))return null;
            var device=(CameraDevices.Device)field(CameraScreen.class,"target");
            if(device==null||!device.getBlockPos().equals(order.target())||field(CameraScreen.class,"scene")!=null
                    ||field(CameraScreen.class,"current")!=null||field(picture(),"rendered")!=null)return null;
            check(!Files.exists(Orders.ROOT.resolve("native-frame.bin")),"Frame from a nonexistent source renderer");
            SharpChecks.savePanel(mc,device,image);
            return "Outdoor chunk naturally unloaded. One integrated client is in Skygrid. No outdoor renderer, frame or idle load.";
        }
        check(hosted(TRIAL)&&STEP.equals(order.step()),"Native feed restricted to one synthetic lens and session");
        if(!freshPermit())return null;
        if(action.equals("native.send"))return send(mc,order);
        check(action.equals("native.watch")&&owner(),"Unexpected receiving role");
        watching=true;
        var device=(CameraDevices.Device)field(CameraScreen.class,"target");
        if(device==null||!device.getBlockPos().equals(order.target())||patched==null||patched!=field(CameraScreen.class,"scene")
                ||presentedCount<2||lastImage==null)return null;
        var receipt=Orders.ROOT.resolve("native-presented-"+lastState);
        if(Files.exists(receipt))return null;
        String base="native-"+lastState;
        SharpChecks.savePanel(mc,device,Orders.ROOT.resolve(base+"-skygrid-monitor.png"));
        Files.write(Orders.ROOT.resolve(base+"-outdoor-lens.png"),lastImage);
        Orders.write("native-presented-"+lastState,"frame="+lastSeen+"; seen="+presentedCount+"; viewer="+mc.level.dimension().location());
        if(lastState.equals(STATE_TWO)){
            watching=false;restore();
            return "Inside player saw successive live native frames, including both actual outdoor marker states.";
        }
        return null;
    }
    private static String send(Minecraft mc,Orders.Order order)throws Exception{
        check(!owner()&&"RoomBuilder".equals(System.getProperty("roomroles.name"))&&order.target().equals(LENS)
                && mc.level.dimension()==net.minecraft.world.level.Level.OVERWORLD && mc.getCameraEntity()==mc.player,"Untrusted native source");
        if(Files.exists(Orders.ROOT.resolve("native-presented-blue"))){
            double seconds=(lastRender-firstRender)/1e9;
            Orders.write("native-render-stats.json",Orders.JSON.toJson(java.util.Map.of(
                    "frames",sequence,"firstToLastFrameSeconds",seconds,"framesPerSecond",(sequence-1)/seconds,
                    "meanCaptureMsIncludingPng",renderNanos/1e6/sequence,"pngBytes",byteCount)));
            return "Outside render completed "+sequence+" live second frames.";
        }
        if(System.nanoTime()-lastRender<400_000_000L)return null;
        lastRender=System.nanoTime();
        if(!mc.level.hasChunkAt(MARK))return null;
        var marker=mc.level.getBlockState(MARK);
        String state=marker.isAir()?VISTA:marker.is(Blocks.ORANGE_CONCRETE)?STATE_ONE:marker.is(Blocks.LIGHT_BLUE_CONCRETE)?STATE_TWO:null;
        if(state==null)return null;
        Path raw=Orders.ROOT.resolve("native-current.png");
        long before=System.nanoTime();
        if(firstRender==0)firstRender=lastRender;
        SharpReference.capture(mc,LENS,raw);
        byte[] png=Files.readAllBytes(raw);
        renderNanos+=System.nanoTime()-before;byteCount+=png.length;
        check(png.length<MAX_BYTES,"Frame too large");
        if(!Files.exists(Orders.ROOT.resolve("native-normal-outside.png")))
            try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(Orders.ROOT.resolve("native-normal-outside.png"));}
        var next=Orders.ROOT.resolve("native-next.tmp");
        try(var packet=new DataOutputStream(Files.newOutputStream(next))){
            packet.writeInt(++sequence);packet.writeUTF(state);packet.writeInt(png.length);packet.write(png);
        }
        Files.move(next,Orders.ROOT.resolve("native-frame.bin"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        return null;
    }
    private static Class<?> picture()throws Exception{return Class.forName("dev.anya.storage.pocket.CameraPicture");}
    private static Object field(Class<?> type,String name)throws Exception{
        var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);
    }
    private static void restore()throws Exception{
        if(patched==null)return;
        var f=picture().getDeclaredField("rendered");f.setAccessible(true);
        if(f.get(null)==patched)f.set(null,null);
        patched=null;
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void paint(RenderLevelStageEvent e)throws Exception{
        if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL || !watching || !owner() || !hosted(TRIAL))return;
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||e.getCamera()!=mc.gameRenderer.getMainCamera())return;
        var scene=(CameraScene)field(CameraScreen.class,"scene");
        if(scene==null||mc.getCameraEntity()!=mc.player||mc.level.dimension()==net.minecraft.world.level.Level.OVERWORLD
                ||!freshPermit()){restore();return;}
        var file=Orders.ROOT.resolve("native-frame.bin");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))return;
        try(var input=new DataInputStream(Files.newInputStream(file))){
            int serial=input.readInt();String state=input.readUTF();int bytes=input.readInt();
            check(state.equals(VISTA)||state.equals(STATE_ONE)||state.equals(STATE_TWO),"Unknown scene");
            check(bytes>0 && bytes<=MAX_BYTES && serial>=lastSeen,"Invalid hosted source frame");
            if(serial==lastSeen && patched==scene)return;
            var png=input.readNBytes(bytes);check(png.length==bytes && input.read()==-1,"Partial frame");
            var render=picture().getDeclaredMethod("surface",CameraScene.class);render.setAccessible(true);
            if(render.invoke(null,scene)==null)return;
            var target=(DynamicTexture)field(picture(),"texture");var pixels=target.getPixels();
            try(var original=NativeImage.read(new ByteArrayInputStream(png))){
                check(original.getWidth()==480 && original.getHeight()==480 && pixels!=null,"Unexpected native capture dimensions");
                for(int y=0;y<pixels.getHeight();y++)for(int x=0;x<pixels.getWidth();x++)
                    pixels.setPixelRGBA(x,y,original.getPixelRGBA(x*480/pixels.getWidth(),y*480/pixels.getHeight()));
            }
            target.upload();patched=scene;
            if(serial!=lastSeen){
                presentedCount=state.equals(lastState)?presentedCount+1:1;
                lastState=state;lastSeen=serial;lastImage=png;
            }
        }
    }
}
