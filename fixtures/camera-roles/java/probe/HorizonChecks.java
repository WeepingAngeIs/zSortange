package probe;

import dev.anya.storage.pocket.CameraDevices;
import dev.anya.storage.pocket.CameraScene;
import dev.anya.storage.pocket.CameraScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import static probe.Orders.check;

/** The server's observed cells and the pixels the viewer actually drew are recorded independently. */
final class HorizonChecks {
    private static final boolean OLD="sharp-horizon-old".equals(System.getProperty("roomroles.trial"));
    private static CameraScene pendingSoftware;
    private static Object field(Class<?> type,String key)throws Exception{
        var field=type.getDeclaredField(key);field.setAccessible(true);return field.get(null);
    }
    private static Object instanceField(Object instance,String name)throws Exception{
        var field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);
    }
    private static LinkedHashMap<String,Object> centerPixel(CameraScene scene,com.mojang.blaze3d.platform.NativeImage displayed,boolean hardware)throws Exception{
        int size=displayed.getWidth(),x=size/2,y=size/2,side=CameraScene.SIDE,tile=size/side,cell=(y/tile)*side+x/tile;
        int[] detail=(int[])scene.getClass().getMethod("detail").invoke(scene);
        var info=new LinkedHashMap<String,Object>();
        info.put("pixelXY",List.of(x,y));info.put("cell",cell);info.put("primary",scene.cells()[cell]);
        int micro=detail.length==0?0:(int)Math.sqrt(detail.length/(side*side));
        info.put("detail",micro==0?0:detail[cell*micro*micro+(y%tile)*micro/tile*micro+(x%tile)*micro/tile]);
        info.put("displayedABGR",String.format("%08x",displayed.getPixelRGBA(x,y)));
        var viewClass=Class.forName("dev.anya.storage.pocket.CameraPicture$SurfaceView");
        var constructor=viewClass.getDeclaredConstructor(CameraScene.class);constructor.setAccessible(true);
        var view=constructor.newInstance(scene);var at=viewClass.getDeclaredMethod("at",CameraScene.class,int.class,int.class,int.class);
        at.setAccessible(true);int[] refs=(int[])at.invoke(view,scene,cell,x%tile,y%tile);
        info.put("permittedRefs",refs);
        var faces=(Object[])instanceField(view,"surfaces");var materials=new ArrayList<Map<String,Object>>();
        var ray=scene.forward().add(scene.right().scale((x+.5)*2/size-1)).add(scene.up().scale(1-(y+.5)*2/size));
        for(int ref:refs==null?new int[0]:refs){
            var surface=scene.surfaces().get(ref);
            var projected=faces[ref];var data=new LinkedHashMap<String,Object>();
            var intersect=projected.getClass().getDeclaredMethod("intersection",double.class,double.class,double.class);
            var sample=projected.getClass().getDeclaredMethod("sample",double.class,double.class,double.class);
            intersect.setAccessible(true);sample.setAccessible(true);
            double t=(double)intersect.invoke(projected,ray.x,ray.y,ray.z);
            var hit=scene.eye().add(ray.scale(t));
            data.put("ref",ref);data.put("block",block(surface));data.put("stateId",surface.stateId());data.put("x",surface.x());data.put("y",surface.y());data.put("z",surface.z());
            data.put("face",surface.face());data.put("plane",surface.plane());data.put("skyLight",surface.skyLight());data.put("blockLight",surface.blockLight());
            data.put("bakedTriangles",((List<?>)instanceField(projected,"facets")).size());data.put("intersectionDistance",t);
            data.put("triangleColorABGR",String.format("%08x",(int)sample.invoke(projected,hit.x,hit.y,hit.z)));materials.add(data);
        }
        info.put("faces",materials);
        if(refs!=null){
            var pixelClass=Class.forName("dev.anya.storage.pocket.CameraPicture$SurfacePixel");
            var make=pixelClass.getDeclaredConstructor();make.setAccessible(true);var resolved=make.newInstance();
            var sample=viewClass.getDeclaredMethod("sample",int.class,int[].class,double.class,double.class,double.class,pixelClass);
            sample.setAccessible(true);sample.invoke(view,cell,refs,ray.x,ray.y,ray.z,resolved);
            info.put("analyticColorABGR",String.format("%08x",(int)instanceField(resolved,"color")));
            info.put("analyticDistance",String.valueOf(instanceField(resolved,"distance")));
        }
        if(hardware){
            var terrain=Class.forName("dev.anya.storage.pocket.CameraTerrain");
            var raw=(com.mojang.blaze3d.platform.NativeImage)field(terrain,"color");
            var depth=(java.nio.FloatBuffer)field(terrain,"depth");int glY=size-1-y;
            info.put("rawTerrainABGR",String.format("%08x",raw.getPixelRGBA(x,glY)));
            info.put("rawTerrainDepth",depth.get(glY*size+x));
        }
        return info;
    }
    private static String block(CameraScene.Surface surface){
        return BuiltInRegistries.BLOCK.getKey(Block.stateById(surface.stateId()).getBlock()).getPath();
    }
    static String capture(Minecraft mc,Orders.Order order,Path path,int age,int fresh,int refusals)throws Exception{
        if(age<48)return null;
        String kind=order.action().substring("sharp.horizon.".length());
        var target=(CameraDevices.Device)field(CameraScreen.class,"target");
        if(target==null||!target.getBlockPos().equals(order.target()))return null;
        var scene=(CameraScene)field(CameraScreen.class,"scene");
        var picture=Class.forName("dev.anya.storage.pocket.CameraPicture");
        var cached=field(picture,"rendered");
        var stats=new LinkedHashMap<String,Object>();
        stats.put("case",kind);stats.put("fresh",fresh);stats.put("refusals",refusals);
        boolean compare=!OLD&&(kind.equals("plate-96")||kind.equals("masked-plate"));
        if(compare)stats.put("samePacketRendererComparison",true);
        var software=compare?picture.getDeclaredField("forceSoftware"):null;
        if(compare)software.setAccessible(true);
        if(compare&&pendingSoftware!=null&&scene!=pendingSoftware){
            pendingSoftware=null;software.setBoolean(null,false);return null;
        }
        if(compare&&pendingSoftware!=null&&cached==scene){
            var hardware=picture.getDeclaredMethod("hardware");hardware.setAccessible(true);
            if((boolean)hardware.invoke(null))return null;
            try{
                var texture=(DynamicTexture)field(picture,"texture");
                Files.writeString(path.resolveSibling(path.getFileName()+".software-center.json"),Orders.JSON.toJson(centerPixel(scene,texture.getPixels(),false)));
                texture.getPixels().writeToFile(path.resolveSibling(path.getFileName()+".software.png"));
                SharpChecks.savePanel(mc,target,path.resolveSibling(path.getFileName()+".software-frame.png"));
            }finally{software.setBoolean(null,false);pendingSoftware=null;}
            return "Same received scene photographed on the actual monitor with hardware and software rendering";
        }
        if(kind.equals("unavailable")){
            if(scene!=null||cached!=null||field(CameraScreen.class,"current")!=null||refusals==0)return null;
        }else{
            if(scene==null||cached!=scene||fresh<2)return null;
            var found=new TreeMap<String,Integer>();
            for(var face:scene.surfaces())found.merge(block(face),1,Integer::sum);
            int[] cells=scene.cells(), kinds=new int[cells.length];
            for(int i=0;i<cells.length;i++)kinds[i]=cells[i]==0?0:cells[i]==1?1:cells[i]==CameraScene.ATMOSPHERE?2:cells[i]<0?4:3;
            int upper=cells[2*32+16],center=cells[16*32+16];
            boolean sky=upper==1||upper==CameraScene.ATMOSPHERE;
            if((kind.equals("masked-sky")||kind.equals("edge"))&&upper!=0)return null;
            if(kind.equals("roof")&&(sky||upper<2||!block(scene.surfaces().get(upper-2)).equals("spruce_planks")))return null;
            if(kind.equals("near-gold")&&!found.containsKey("gold_block"))return null;
            if(kind.equals("normal")&&!found.containsKey("stone_bricks"))return null;
            if(kind.equals("open")&&(found.containsKey("gold_block")||found.containsKey("stone_bricks")||found.containsKey("spruce_planks")))return null;
            if(kind.equals("masked-plate")&&center!=0)return null;
            if(kind.equals("wide")&&Math.abs(scene.right().length()-Math.tan(Math.toRadians(52)))>.001)return null;
            if(!kind.equals("wide")&&Math.abs(scene.right().length()-1)>.001)return null;
            if(kind.startsWith("plate-")){
                int blocks=Integer.parseInt(kind.substring(6)),count=0;
                double minimum=Double.POSITIVE_INFINITY;
                for(int i=0;i<cells.length;i++){
                    int index=cells[i]-2;if(index<0||index>=scene.surfaces().size())continue;
                    var f=scene.surfaces().get(index);
                    if(!Set.of("gold_block","lapis_block").contains(block(f)))continue;
                    check(f.z()==-blocks,"Different marker at "+f.z()+" expected "+(-blocks));
                    if(f.face()!=Direction.SOUTH)continue;
                    var ray=scene.forward().add(scene.right().scale((2.0*(i%32)+1)/32-1)).add(scene.up().scale(1-(2.0*(i/32)+1)/32)).normalize();
                    minimum=Math.min(minimum,(f.plane()-scene.eye().z)/ray.z);count++;
                }
                boolean inRange=blocks<=(OLD?64:96);
                if((!OLD&&inRange&&count==0)||(!inRange&&(found.containsKey("gold_block")||found.containsKey("lapis_block"))))return null;
                stats.put("axialNearFaceBlocks",blocks-1+scene.eye().z);
                stats.put("primaryCheckerRays",count);
                if(count>0)stats.put("nearestPrimaryHitBlocks",minimum);
            }
            stats.put("horizontalFovDegrees",Math.toDegrees(2*Math.atan(scene.right().length())));
            stats.put("eye",new double[]{scene.eye().x,scene.eye().y,scene.eye().z});
            stats.put("palette",found);stats.put("upper",upper);stats.put("center",center);
            stats.put("primaryTypes",kinds);
            if(!OLD){
                var fine=(int[])scene.getClass().getMethod("detail").invoke(scene);
                stats.put("fineAuthorized",Arrays.stream(fine).filter(v->v>=2).count());
                stats.put("fineMasked",Arrays.stream(fine).filter(v->v<0).count());
            }
            var texture=(DynamicTexture)field(picture,"texture");
            check(texture!=null&&texture.getPixels()!=null,"Missing displayed texture");
            if(compare)stats.put("nativeCenter",centerPixel(scene,texture.getPixels(),true));
            texture.getPixels().writeToFile(path.resolveSibling(path.getFileName()+".source.png"));
        }
        SharpChecks.savePanel(mc,target,path);
        Files.writeString(path.resolveSibling(path.getFileName()+".horizon.json"),Orders.JSON.toJson(stats));
        if(compare){
            var hardware=picture.getDeclaredMethod("hardware");hardware.setAccessible(true);
            check((boolean)hardware.invoke(null),"Expected a hardware-rendered comparison control");
            pendingSoftware=scene;software.setBoolean(null,true);return null;
        }
        return "Captured "+order.step()+" fresh="+fresh+" refusals="+refusals;
    }
    private HorizonChecks(){}
}
