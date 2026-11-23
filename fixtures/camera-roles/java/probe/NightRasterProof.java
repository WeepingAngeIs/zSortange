package probe;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.joml.Matrix4f;

/** Trace the photographed monitor's pixel provenance in this disposable synthetic scene only. */
final class NightRasterProof {
    private static Field field(Class<?> type,String name)throws Exception{
        var result=type.getDeclaredField(name);result.setAccessible(true);return result;
    }
    private static int tag(int rgba,int mask){
        int changed=rgba^0x005b82ad;
        return changed==mask ? changed^1 : changed;
    }
    static void check(Object scene,NativeImage displayed,Path output)throws Exception{
        RenderSystem.assertOnRenderThread();
        var sceneType=scene.getClass();
        Orders.check("true".equals(System.getenv("GITHUB_ACTIONS"))
                && System.getProperty("roomroles.trial","").equals("sharp-wall-night-candidate-solo")
                && (boolean)sceneType.getMethod("localLighting").invoke(scene),"Pixel proof requires the hosted local-light candidate");
        var terrain=Class.forName("dev.anya.storage.pocket.CameraTerrain");
        var picture=Class.forName("dev.anya.storage.pocket.CameraPicture");
        var shape=Class.forName("dev.anya.storage.pocket.CameraPicture$SurfaceView");
        var ctor=shape.getDeclaredConstructor(sceneType);ctor.setAccessible(true);
        var view=ctor.newInstance(scene);
        var composite=terrain.getDeclaredMethod("composite",sceneType,shape,Matrix4f.class,NativeImage.class,float[].class);
        composite.setAccessible(true);
        var software=picture.getDeclaredMethod("drawScene",sceneType,shape,NativeImage.class);
        software.setAccessible(true);
        var colorField=field(terrain,"color");
        var raster=(NativeImage)colorField.get(null);
        var depth=((FloatBuffer)field(terrain,"depth").get(null)).duplicate();
        var frameDepth=(float[])field(picture,"depth").get(null);
        var mask=field(picture,"MASK").getInt(null);
        int size=displayed.getWidth(),count=size*size;
        Orders.check(raster!=null && raster.getWidth()==size && depth.capacity()==count,"Native color and depth must match the displayed frame");
        var up=(net.minecraft.world.phys.Vec3)sceneType.getMethod("up").invoke(scene);
        var projection=new Matrix4f().perspective((float)(2*Math.atan(up.length())),1,
                field(terrain,"NEAR").getFloat(null),field(terrain,"FAR").getFloat(null));

        // Nothing is uploaded or redrawn in the player's window. Replay the same compositor twice
        // with the original GL depth. Change only the RGB of valid fragments for the second pass.
        try(var unmarked=new NativeImage(size,size,false);var marked=new NativeImage(size,size,false);
            var substitution=new NativeImage(size,size,false);var cpu=new NativeImage(size,size,false);
            var admittedImage=new NativeImage(size,size,false)){
            for(int y=0;y<size;y++)for(int x=0;x<size;x++){
                int raw=raster.getPixelRGBA(x,y);
                substitution.setPixelRGBA(x,y,(raw>>>24)>=32 && raw!=mask ? tag(raw,mask) : raw);
            }
            var scratchDepth=new float[count];
            composite.invoke(null,scene,view,projection,unmarked,scratchDepth);
            try{
                colorField.set(null,substitution);
                composite.invoke(null,scene,view,projection,marked,scratchDepth);
            }finally{colorField.set(null,raster);}
            var savedDepth=frameDepth.clone();
            try{software.invoke(null,scene,view,cpu);}
            finally{System.arraycopy(savedDepth,0,frameDepth,0,count);}

            int opaque=0,accepted=0,visible=0,different=0,admittedFacade=0,admittedPavement=0;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++){
                int glY=size-1-y,raw=raster.getPixelRGBA(x,glY);
                float z=depth.get(glY*size+x);
                boolean solid=(raw>>>24)>=32 && raw!=mask && z>=0 && z<1;
                if(solid)opaque++;
                int original=unmarked.getPixelRGBA(x,y);
                boolean admitted=solid && original==(raw|0xff000000)
                        && marked.getPixelRGBA(x,y)==(tag(raw,mask)|0xff000000);
                if(admitted)accepted++;
                // Actor and weather pixels could overwrite terrain after compositing. Only credit
                // colors actually present in the original monitor texture saved before this probe.
                boolean onScreen=admitted && displayed.getPixelRGBA(x,y)==original;
                if(onScreen){
                    visible++;
                    if(cpu.getPixelRGBA(x,y)!=original)different++;
                    if(x>.29*size && x<.71*size && y>.33*size && y<.48*size)admittedFacade++;
                    if(x>.365*size && x<.385*size && y>.538*size && y<.604*size)admittedPavement++;
                }
                admittedImage.setPixelRGBA(x,y,onScreen?0xffffffff:0xff000000);
            }
            var receipt=new LinkedHashMap<String,Integer>();
            receipt.put("pixels",count);receipt.put("opaqueDepthTestedFragments",opaque);
            receipt.put("admittedColorAndDepth",accepted);receipt.put("visibleExactGpuColors",visible);
            receipt.put("visibleGpuColorsDifferentFromSoftware",different);
            receipt.put("visibleGpuPixelsOnFacade",admittedFacade);receipt.put("visibleGpuPixelsOnGrazingPavement",admittedPavement);
            Files.writeString(output.resolveSibling(output.getFileName()+".gpu.json"),Orders.JSON.toJson(receipt));
            cpu.writeToFile(output.resolveSibling(output.getFileName()+".cpu.png"));
            admittedImage.writeToFile(output.resolveSibling(output.getFileName()+".gpu-mask.png"));
            Orders.check(visible>=count/10 && different>=count/100,"Insufficient real GPU contribution: "+receipt);
        }
    }
    private NightRasterProof(){}
}
