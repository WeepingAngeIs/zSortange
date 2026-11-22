package probe;

import dev.anya.storage.pocket.CameraDevices;
import dev.anya.storage.pocket.CameraScene;
import dev.anya.storage.pocket.CameraScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import static probe.Orders.check;

/** Receipts come from the receiving connection and the actual rendered client frame. */
final class RangeChecks {
    private static final boolean BASELINE="sharp-range-old".equals(System.getProperty("roomroles.trial"));
    private static int lastStatusAge=-1;
    private static CameraType mode(Orders.Order o){
        if(o.action().startsWith("sharp.range.back."))return CameraType.THIRD_PERSON_BACK;
        if(o.action().startsWith("sharp.range.front."))return CameraType.THIRD_PERSON_FRONT;
        return CameraType.FIRST_PERSON;
    }
    static void perspective(Minecraft mc,Orders.Order o){lastStatusAge=-1;mc.options.setCameraType(mode(o));}
    private static Object read(Class<?> type,String key)throws Exception{
        var field=type.getDeclaredField(key);field.setAccessible(true);return field.get(null);
    }
    private static void saveScreen(Minecraft mc,Path path)throws Exception{
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(path);}
    }
    static String capture(Minecraft mc,Orders.Order o,Path path,int age,int received,int blank,int legacyBlank)throws Exception{
        String expect=o.action().substring(o.action().lastIndexOf('.')+1);
        check(mc.options.getCameraType()==mode(o),"Unexpected Minecraft perspective");
        if(SharpChecks.single())check(mc.hasSingleplayerServer()&&mc.getSingleplayerServer().getPlayerList().getPlayers().size()==1,"Expected one integrated player");
        CameraDevices.Device monitor=(CameraDevices.Device)read(CameraScreen.class,"target");
        CameraScene scene=(CameraScene)read(CameraScreen.class,"scene");
        Class<?> picture=Class.forName("dev.anya.storage.pocket.CameraPicture");
        var cache=read(picture,"rendered");
        Object legacy=read(CameraScreen.class,"current");
        if(age%20==0&&age!=lastStatusAge){
            lastStatusAge=age;
            Orders.write(System.getProperty("roomroles.name")+"-view.status",o.step()+" mode="+mode(o)+" received="+received
                    +" blank="+(blank+legacyBlank)+" target="+(monitor==null?null:monitor.getBlockPos())+" cached="+(cache!=null)
                    +" distinctGold="+(scene==null?0:scene.surfaces().stream().filter(s->Block.stateById(s.stateId()).is(Blocks.GOLD_BLOCK)).count())
                    +" actors="+(scene==null?"[]":scene.actors())+" camera="+mc.gameRenderer.getMainCamera().getPosition());
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("step",o.step());result.put("perspective",mode(o));result.put("condition",expect);
        result.put("freshScenes",received);result.put("freshRefusals",blank+legacyBlank);
        if(expect.equals("inactive")){
            if(age<60||monitor!=null||scene!=null||cache!=null||legacy!=null)return null;
            saveScreen(mc,path);
        }else if(expect.equals("private")||expect.equals("denied")){
            if(monitor==null||!monitor.getBlockPos().equals(o.target())||scene!=null||cache!=null||legacy!=null||blank+legacyBlank<1)return null;
            SharpChecks.savePanel(mc,monitor,path);
        }else{
            if(monitor==null||!monitor.getBlockPos().equals(o.target())||scene==null||cache!=scene||received<2)return null;
            int[] cells=scene.cells();
            check(cells.length==CameraScene.SIDE*CameraScene.SIDE,"Unexpected wire sample dimensions");
            int upper=cells[2*CameraScene.SIDE+CameraScene.SIDE/2];
            result.put("upper",upper);
            var people=scene.actors().stream().filter(a->a.typeId()==BuiltInRegistries.ENTITY_TYPE.getId(EntityType.PLAYER)).toList();
            if(expect.equals("noactor")&&!people.isEmpty()||expect.equals("actor")&&people.size()!=1)return null;
            if(expect.equals("actor")){
                var player=people.getFirst();
                int index=-1-scene.actors().indexOf(player);
                if(Arrays.stream(cells).noneMatch(v->v==index))return null;
                var info=mc.getConnection().getPlayerInfo("RoomBuilder");
                check(info!=null,"Missing naturally listed player");
                if(!BASELINE){
                    int slot=(int)player.getClass().getMethod("appearance").invoke(player);
                    if(slot<=0)return null;
                    var resolve=Class.forName("dev.anya.storage.pocket.CameraPlayerAppearance").getDeclaredMethod("resolve",int.class);
                    resolve.setAccessible(true);
                    if(resolve.invoke(null,slot)!=info)return null;
                    result.put("trackedAppearance",slot);
                }
                result.put("profileTexture",info.getSkin().texture().toString());
                result.put("profileModel",info.getSkin().model().toString());
                Vec3 delta=player.feet().add(0,.9,0).subtract(scene.eye());double depth=delta.dot(scene.forward());
                result.put("actorX",.5+.5*delta.dot(scene.right())/depth/scene.right().lengthSqr());
                result.put("actorY",.5-.5*delta.dot(scene.up())/depth/scene.up().lengthSqr());
            }
            if(expect.startsWith("seen-")||expect.startsWith("beyond-")||expect.equals("masked-48")){
                int distance=Integer.parseInt(expect.substring(expect.indexOf('-')+1));
                int count=0;double nearest=Double.POSITIVE_INFINITY;
                for(int i=0;i<cells.length;i++){
                    int index=cells[i]-2;
                    if(index<0||index>=scene.surfaces().size())continue;
                    var surface=scene.surfaces().get(index);
                    if(!Block.stateById(surface.stateId()).is(Blocks.GOLD_BLOCK))continue;
                    check(surface.z()==-distance,"Received another gold panel at "+surface.z());
                    if(surface.face()!=Direction.SOUTH)continue;
                    double x=(2.0*(i%CameraScene.SIDE)+1)/CameraScene.SIDE-1;
                    double y=1-(2.0*(i/CameraScene.SIDE)+1)/CameraScene.SIDE;
                    Vec3 ray=scene.forward().add(scene.right().scale(x)).add(scene.up().scale(y)).normalize();
                    double depth=(surface.plane()-scene.eye().z)/ray.z;
                    nearest=Math.min(nearest,depth);count++;
                }
                if(expect.startsWith("seen-")&&count==0||!expect.startsWith("seen-")&&count!=0)return null;
                if(!expect.startsWith("seen-")&&scene.surfaces().stream().anyMatch(s->Block.stateById(s.stateId()).is(Blocks.GOLD_BLOCK)))return null;
                if(expect.startsWith("masked-")&&cells[16*32+16]!=0)return null;
                result.put("markerOffsetBlocks",distance);
                result.put("nearFaceAxialBlocks",distance-1+scene.eye().z);
                result.put("goldCells",count);
                if(count>0)result.put("nearestGoldRadialBlocks",nearest);
            }
            if(expect.equals("edge")&&(upper!=0||scene.surfaces().stream().noneMatch(s->Block.stateById(s.stateId()).is(Blocks.STONE_BRICKS))))return null;
            if(scene.surfaces().isEmpty())return null;
            var texture=(DynamicTexture)read(picture,"texture");
            check(texture!=null&&texture.getPixels()!=null,"Missing displayed texture");
            texture.getPixels().writeToFile(path.resolveSibling(path.getFileName()+".source.png"));
            SharpChecks.savePanel(mc,monitor,path);
            result.put("surfaceCount",scene.surfaces().size());
        }
        Files.writeString(path.resolveSibling(path.getFileName()+".range.json"),Orders.JSON.toJson(result));
        return Orders.JSON.toJson(result);
    }
    private RangeChecks(){}
}
