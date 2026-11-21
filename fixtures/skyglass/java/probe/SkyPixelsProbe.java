package probe;

import dev.anya.storage.pocket.SkyglassPixels;
import java.nio.file.*;
import java.lang.reflect.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.BufferUtils;

@EventBusSubscriber(modid="wallprobe",value=Dist.CLIENT)
public final class SkyPixelsProbe {
    private static int dayBackground,dayWindow,northWindow,eastWindow,blankFrames;
    static Object read(Object target,String name)throws Exception{
        var method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(target);
    }
    @SubscribeEvent public static void inspect(RenderGuiEvent.Post event)throws Exception{
        var field=Class.forName("dev.anya.storage.pocket.CameraScreen").getDeclaredField("current");field.setAccessible(true);
        var frame=field.get(null);
        if(frame==null){
            if(Files.exists(Path.of("../Server/checking-unloaded"))){
                var target=field.getDeclaringClass().getDeclaredField("target");target.setAccessible(true);
                if(target.get(null)!=null && ++blankFrames>=16)Files.writeString(Path.of("unavailable"),"Focused monitor lost source");
            }
            return;
        }
        int bg=(int)read(frame,"background");float clarity=(float)read(frame,"clarity");
        int[] pixels=(int[])read(frame,"pixels");long time=(long)read(frame,"time")%24000;
        int pixel=pixels[136];var mc=Minecraft.getInstance();
        var window=mc.getWindow();var data=BufferUtils.createByteBuffer(4);
        int readTarget=GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,mc.getMainRenderTarget().frameBufferId);
        GL11.glReadPixels(window.getWidth()/2+7,window.getHeight()/2+7,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data);
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,readTarget);
        int windowColor=(data.get(0)&255)<<16|(data.get(1)&255)<<8|(data.get(2)&255);
        String snapshot=time+" "+Integer.toHexString(pixel)+" "+Integer.toHexString(windowColor)+" bg="+bg+" clarity="+clarity;
        if(mc.level.getGameTime()%40==0)Files.writeString(Path.of("last-camera"),snapshot);
        String key;
        if(!Files.exists(Path.of("noon")) && time>=6000 && time<6600 && pixel>>>24==4 && bg>0){
            dayBackground=bg;dayWindow=windowColor;key="noon";
        }else if(Files.exists(Path.of("noon")) && !Files.exists(Path.of("roof")) && pixel==net.minecraft.world.level.material.MapColor.GOLD.col && windowColor!=dayWindow)key="roof";
        else if(Files.exists(Path.of("roof")) && !Files.exists(Path.of("night")) && time>=18000 && time<18400 && bg==0 && pixel>>>24==5 && windowColor!=dayWindow)key="night";
        else if(Files.exists(Path.of("night")) && !Files.exists(Path.of("rain")) && clarity<.05 && bg>0 && bg!=dayBackground && pixel>>>24==4)key="rain";
        else if(Files.exists(Path.of("rain")) && !Files.exists(Path.of("north")) && pixel==net.minecraft.world.level.material.MapColor.COLOR_RED.col && ((windowColor>>16)&255)>(windowColor&255)+12){
            northWindow=windowColor;key="north";
        }else if(Files.exists(Path.of("north")) && !Files.exists(Path.of("east")) && pixel==net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE.col && (windowColor&255)>((windowColor>>16)&255)+12){
            eastWindow=windowColor;key="east";
        }else if(Files.exists(Path.of("east")) && !Files.exists(Path.of("masked")) && pixel==0x2000000 && windowColor!=eastWindow && (windowColor&255)<128)key="masked";
        else return;
        Files.writeString(Path.of(key),"camera="+Integer.toHexString(SkyglassPixels.color(pixel,bg,clarity))+" window="+Integer.toHexString(windowColor));
    }
}
