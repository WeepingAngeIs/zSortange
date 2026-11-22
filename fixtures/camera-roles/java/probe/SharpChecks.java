package probe;

import dev.anya.storage.pocket.CameraScreen;
import dev.anya.storage.pocket.CameraDevices;
import dev.anya.storage.pocket.CameraFeed;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import static probe.Orders.check;

final class SharpChecks {
    private static final String trial=System.getProperty("roomroles.trial","");
    static boolean sharp(){return trial.startsWith("sharp-");}
    static boolean single(){return trial.equals("sharp-solo");}
    private static boolean creating, referenceInProgress;
    private static int scenes,blankScenes,startScenes,startBlank;
    private static Matrix4f projection;
    private static Vec3 eye;
    static void rememberProjection(RenderLevelStageEvent frame){
        projection=new Matrix4f(frame.getProjectionMatrix()).mul(frame.getModelViewMatrix());
        eye=frame.getCamera().getPosition();
    }
    private static void savePanel(Minecraft mc,CameraDevices.Device monitor,Path image)throws Exception{
        var centerMethod=CameraFeed.class.getDeclaredMethod("screenCenter",CameraDevices.Device.class);
        centerMethod.setAccessible(true);
        var center=(Vec3)centerMethod.invoke(null,monitor);
        var panel=field(CameraDevices.Device.class,"panel").get(monitor);
        double halfWidth=(((Number)prop(panel,"width")).doubleValue()-.075)*.5;
        double halfHeight=(((Number)prop(panel,"height")).doubleValue()-.075)*.5;
        var facing=monitor.getBlockState().getValue(BlockStateProperties.FACING);
        var right=Vec3.atLowerCornerOf(facing.getCounterClockWise().getNormal());
        int width=mc.getMainRenderTarget().width,height=mc.getMainRenderTarget().height;
        var corners=new java.util.ArrayList<Double>();
        for(var signs:new int[][]{{-1,1},{-1,-1},{1,-1},{1,1}}){
            var corner=center.add(right.scale(halfWidth*signs[0])).add(0,halfHeight*signs[1],0).subtract(eye);
            var clip=projection.transform(new Vector4f((float)corner.x,(float)corner.y,(float)corner.z,1));
            check(clip.w>0,"Monitor behind the room viewer");
            corners.add((1+(double)clip.x/clip.w)*width*.5);
            corners.add((1-(double)clip.y/clip.w)*height*.5);
        }
        Files.writeString(image.resolveSibling(image.getFileName()+".json"),Orders.JSON.toJson(corners));
        screenshot(mc,image);
    }
    private static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object comparedScene;
    private static Object transferredScene;
    private static String transfer(Minecraft mc,CameraDevices.Device target,Object scene)throws Exception{
        check(System.getenv("GITHUB_ACTIONS").equals("true") && trial.equals("sharp-new")
                && "CamOwner".equals(System.getProperty("roomroles.name"))
                && Orders.ROOT.toAbsolutePath().normalize().endsWith(Path.of("role-check","sharp-new","control")),
                "Stock comparison is restricted to the synthetic hosted room owner");
        var type=Class.forName("dev.anya.storage.pocket.CameraPicture");
        var cached=field(type,"rendered");
        if(cached.get(null)!=scene)return null;
        var file=Orders.ROOT.resolve("reference-day-RoomBuilder.png");
        check(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(Orders.ack("RoomBuilder","reference-day")),
                "The trusted outdoor client has not captured this synthetic lens");
        if(transferredScene==scene){
            try{
                savePanel(mc,target,Orders.ROOT.resolve("stock-lens-on-the-indoor-wall-CamOwner.png"));
                return "Rendered the outdoor client's source-lens-only stock framebuffer on the authorized indoor monitor; disposable fixture transfer";
            }finally{
                // Rebuild the original scene on the next draw. No bytes are sent over mod networking.
                transferredScene=null;cached.set(null,null);
            }
        }
        transferredScene=null;
        var texture=(DynamicTexture)field(type,"texture").get(null);
        if(texture==null)return null;
        try(var in=Files.newInputStream(file);var shot=NativeImage.read(in)){
            var pixels=texture.getPixels();
            check(shot.getWidth()==shot.getHeight() && shot.getWidth()==480 && pixels!=null,"Unexpected source reference surface");
            for(int y=0;y<pixels.getHeight();y++)for(int x=0;x<pixels.getWidth();x++)
                pixels.setPixelRGBA(x,y,shot.getPixelRGBA(x*shot.getWidth()/pixels.getWidth(),y*shot.getHeight()/pixels.getHeight()));
            texture.upload();
        }
        transferredScene=scene;return null;
    }
    private static String compareRenderers(Minecraft mc,CameraDevices.Device target,Object scene)throws Exception{
        var type=Class.forName("dev.anya.storage.pocket.CameraPicture");
        var method=type.getDeclaredMethod("hardware");method.setAccessible(true);
        boolean hardware=(boolean)method.invoke(null);
        var software=field(type,"forceSoftware");
        if(comparedScene!=null && comparedScene!=scene){
            software.setBoolean(null,false);comparedScene=null;return null;
        }
        if(comparedScene==null){
            // A new observation may just have arrived in the frame which disarmed the fallback.
            if(!hardware)return null;
            savePanel(mc,target,Orders.ROOT.resolve("same-scene-world-renderer-CamOwner.png"));
            comparedScene=scene;software.setBoolean(null,true);return null;
        }
        if(hardware)return null;
        try{
            savePanel(mc,target,Orders.ROOT.resolve("same-scene-software-CamOwner.png"));
            return "Two actual on-monitor framebuffer captures drew the identical received observation with both renderers";
        }finally{software.setBoolean(null,false);comparedScene=null;}
    }
    private static Object prop(Object value,String name)throws Exception{var m=value.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(value);}
    private static boolean old(){return trial.equals("sharp-old");}
    @SuppressWarnings("unchecked")
    static void tap(boolean owner)throws Exception{
        if(old())return;
        var type=Class.forName("dev.anya.storage.pocket.CameraScene");
        var receiver=field(type,"receiver");var normal=(BiConsumer<Connection,Object>)receiver.get(null);
        receiver.set(null,(BiConsumer<Connection,Object>)(connection,scene)->{
            try{
                if(((int[])prop(scene,"cells")).length>0){
                    scenes++;
                    if(!owner)Orders.write("FAIL-private-receiver","Guest received a nonempty sharp camera scene");
                }else blankScenes++;
                normal.accept(connection,scene);
            }catch(Exception error){throw new IllegalStateException(error);}
        });
    }
    static void next(){startScenes=scenes;startBlank=blankScenes;}
    static void startSolo(Minecraft mc)throws Exception{
        mc.options.pauseOnLostFocus=false;
        if(creating || !(mc.screen instanceof TitleScreen) || mc.getOverlay()!=null)return;
        check("true".equals(System.getenv("GITHUB_ACTIONS")) && mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("role-check","sharp-solo","CamOwner")) && !Files.exists(mc.gameDirectory.toPath().resolve("saves/CameraSolo")),"Expected a fresh hosted single-player client");
        creating=true;
        var settings=new LevelSettings("CameraSolo",GameType.SURVIVAL,false,Difficulty.NORMAL,false,new GameRules(),WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel("CameraSolo",settings,new WorldOptions(19211L,false,false),
            regs->regs.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);
    }
    private static void screenshot(Minecraft mc,Path path)throws Exception{
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(path);}
    }
    static String capture(Orders.Order order,int age,int legacyDelta,int legacyBlankDelta)throws Exception{
        if(referenceInProgress || age<36)return null;
        var action=order.action().substring(6);
        var mc=Minecraft.getInstance();
        var path=Orders.ROOT.resolve(order.step()+"-"+System.getProperty("roomroles.name")+".png");
        if(action.equals("reference")){
            referenceInProgress=true;
            try{
                var camera=mc.getCameraEntity();var level=mc.level;var pose=mc.player.position();
                var rot=mc.options.getCameraType();var main=mc.getMainRenderTarget();var yaw=mc.player.getYRot();var pitch=mc.player.getXRot();
                check(camera==mc.player,"Outdoor player is not in ordinary first person");
                screenshot(mc,Orders.ROOT.resolve(order.step()+"-outside-before.png"));
                long started=System.nanoTime();SharpReference.capture(mc,order.target(),path);
                double millis=(System.nanoTime()-started)/1_000_000.0;
                check(mc.getCameraEntity()==camera && mc.level==level && mc.getMainRenderTarget()==main && mc.options.getCameraType()==rot
                        && pose.equals(mc.player.position()) && yaw==mc.player.getYRot() && pitch==mc.player.getXRot(),"Native reference failed to restore the outside player");
                screenshot(mc,Orders.ROOT.resolve(order.step()+"-outside-after.png"));
                return "Native lens framebuffer, normal outside player before/after and restored viewpoint. Single capture including framebuffer readback and PNG I/O: "+millis+" ms";
            }finally{referenceInProgress=false;}
        }
        var target=(CameraDevices.Device)field(CameraScreen.class,"target").get(null);
        if(target==null || !target.getBlockPos().equals(order.target()))return null;
        Object legacy=field(CameraScreen.class,"current").get(null);
        Object scene=old()?null:field(CameraScreen.class,"scene").get(null);
        if(single())check(mc.hasSingleplayerServer()&&mc.getSingleplayerServer().getPlayerList().getPlayers().size()==1,"Expected one actual integrated-server player");
        if(action.equals("private")||action.equals("unavailable")){
            if(legacy!=null || scene!=null || blankScenes==startBlank && legacyBlankDelta==0)return null;
            if(action.equals("private"))check(scenes==0,"Guest received a single positive sharp frame");
            savePanel(mc,target,path);return "No authorized image, fresh server refusal and both renderer caches clear";
        }
        if(old()){
            if(legacy==null||legacyDelta<2)return null;
            savePanel(mc,target,path);return "Low-resolution reference from actual baseline client";
        }
        if(scene==null||scenes-startScenes<2)return null;
        var rendered=Class.forName("dev.anya.storage.pocket.CameraPicture").getDeclaredMethod("rendered");rendered.setAccessible(true);
        if(rendered.invoke(null)!=scene)return null;
        var actors=(List<?>)prop(scene,"actors");int[] cells=(int[])prop(scene,"cells");
        var types=new java.util.HashSet<Integer>();
        for(int i=0;i<actors.size();i++){
            int k=-1-i;
            if(java.util.Arrays.stream(cells).anyMatch(c->c==k))types.add(((Number)prop(actors.get(i),"typeId")).intValue());
        }
        var surfaces=(List<?>)prop(scene,"surfaces");
        long visible=java.util.Arrays.stream(cells).filter(v->v!=0).count();
        int cow=BuiltInRegistries.ENTITY_TYPE.getId(EntityType.COW),person=BuiltInRegistries.ENTITY_TYPE.getId(EntityType.PLAYER);
        float rain=((Number)prop(scene,"rain")).floatValue();
        int upper=cells[32*2+16];
        int atmosphere=32767;
        var blockTypes=new java.util.HashSet<net.minecraft.world.level.block.Block>();
        for(var surface:surfaces){
            int state=((Number)prop(surface,"stateId")).intValue();
            blockTypes.add(net.minecraft.world.level.block.Block.stateById(state).getBlock());
        }
        if(action.equals("brick") && !blockTypes.contains(Blocks.STONE_BRICKS)
                || action.equals("gold") && !blockTypes.contains(Blocks.GOLD_BLOCK))return null;
        if(action.equals("masked") && (blockTypes.contains(Blocks.GOLD_BLOCK) || !blockTypes.contains(Blocks.STONE_BRICKS)
                || cells[16*32+16]!=0))return null;
        if(action.equals("cow") && !types.contains(cow) || action.equals("player") && !types.contains(person))return null;
        if(action.equals("actor-denied")||action.equals("invisible")){
            for(var actor:actors)if(((Number)prop(actor,"typeId")).intValue()==person)return null;
            if(!types.contains(cow))return null;
        }
        if(action.equals("rain") && rain<.8 || action.equals("night") && (rain>.1 || Math.floorMod(((Number)prop(scene,"time")).longValue(),24000)!=18000))return null;
        if(action.equals("open-sky") && upper!=1 && upper!=atmosphere
                || (action.equals("shadow-sky")||action.equals("edge-sky")) && (upper!=0||!blockTypes.contains(Blocks.STONE_BRICKS)&&!blockTypes.contains(Blocks.STONE)))return null;
        if(action.equals("roof") && (rain>.01 || upper<2 || upper==atmosphere || upper-2>=surfaces.size()
                || !net.minecraft.world.level.block.Block.stateById(((Number)prop(surfaces.get(upper-2),"stateId")).intValue()).is(Blocks.SPRUCE_PLANKS)))return null;
        if(visible<cells.length/12)return null;
        if(action.equals("renderer-compare"))return compareRenderers(mc,target,scene);
        if(action.equals("reference-transfer")){
            if(!types.contains(person)||!types.contains(cow)||!blockTypes.contains(Blocks.STONE_BRICKS)||rain!=0)return null;
            return transfer(mc,target,scene);
        }
        if(order.step().equals("large-brick")||order.step().equals("large-cow")||order.step().equals("large-player")){
            // Record only what the actual client received. 0 = masked/unknown, 1 = authorized
            // background, 2 = the exposed top of the specific moss floor being compared.
            var moss=new java.util.HashSet<Integer>();
            for(int i=0;i<surfaces.size();i++){
                var s=surfaces.get(i);
                if(prop(s,"face")==net.minecraft.core.Direction.UP &&
                        net.minecraft.world.level.block.Block.stateById(((Number)prop(s,"stateId")).intValue()).is(Blocks.MOSS_BLOCK))moss.add(i+2);
            }
            int[] backdrop=(int[])prop(scene,"backdrop"),witness=new int[cells.length];
            for(int i=0;i<cells.length;i++){
                int surface=cells[i]<0?backdrop[i]:cells[i];
                witness[i]=moss.contains(surface)?2:surface>0?1:0;
            }
            Files.writeString(path.resolveSibling(path.getFileName()+".floor.json"),Orders.JSON.toJson(witness));
        }
        if(action.equals("player")){
            for(var actor:actors)if(((Number)prop(actor,"typeId")).intValue()==person){
                Vec3 rel=((Vec3)prop(actor,"feet")).add(0,.9,0).subtract((Vec3)prop(scene,"eye"));
                Vec3 right=(Vec3)prop(scene,"right"),up=(Vec3)prop(scene,"up");
                double distance=rel.dot((Vec3)prop(scene,"forward"));
                Files.writeString(path.resolveSibling(path.getFileName()+".actor.json"),Orders.JSON.toJson(
                        new double[]{.5+.5*rel.dot(right)/distance/right.lengthSqr(),.5-.5*rel.dot(up)/distance/up.lengthSqr()}));
            }
        }
        savePanel(mc,target,path);return "Fresh authorized source rendered. surface types="+surfaces.size()+", visible cells="+visible+" of "+cells.length+", actors="+types+", upper center="+upper+", rain="+rain+", time="+prop(scene,"time");
    }
    private SharpChecks(){}
}
