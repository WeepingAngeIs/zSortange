package dev.anya.storage.pocket;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Sample the signed-in client's current sun and moon artwork only for verified open source rays. */
public final class SkyglassPixels {
    private static NativeImage sun,moon;
    private static long lastLoad=-1;
    static void clear(){
        if(sun!=null)sun.close();if(moon!=null)moon.close();sun=null;moon=null;lastLoad=-1;
    }
    private static NativeImage read(String name)throws java.io.IOException{
        var id=ResourceLocation.withDefaultNamespace("textures/environment/"+name+".png");
        try(var stream=Minecraft.getInstance().getResourceManager().open(id)){return NativeImage.read(stream);}
    }
    public static int color(int pixel,int background,float clarity){
        if(pixel==CameraFeed.UNKNOWN)return 0x161722;
        if((pixel>>>24)==0)return pixel;
        if(background<0)return 0x161722;
        if(pixel==CameraFeed.SKY)return background;
        boolean solar=(pixel&0xff000000)==SourceSky.SUN;
        if(!solar && (pixel&0xff000000)!=SourceSky.MOON)return 0x161722;
        if(sun==null || moon==null){
            long now=System.nanoTime();
            if(lastLoad!=-1 && now-lastLoad<5_000_000_000L)return background;
            clear();lastLoad=now;
            try{sun=read("sun");moon=read("moon_phases");}catch(java.io.IOException unavailable){return background;}
        }
        var image=solar?sun:moon;
        int phase=(pixel>>>16)&7,u=pixel&255,v=(pixel>>>8)&255;
        int tx=solar?u*image.getWidth()/256:((phase%4)*256+u)*image.getWidth()/1024;
        int ty=solar?v*image.getHeight()/256:((phase/4)*256+v)*image.getHeight()/512;
        int abgr=image.getPixelRGBA(tx,ty);float alpha=(abgr>>>24)/255f*clarity;
        int rgb=0;
        for(int i=0;i<3;i++)rgb|=Math.min(255,((background>>(16-8*i))&255)+(int)(alpha*((abgr>>(8*i))&255)))<<(16-8*i);
        return rgb;
    }
}
