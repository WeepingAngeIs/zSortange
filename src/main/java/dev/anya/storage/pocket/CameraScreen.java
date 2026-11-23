package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.opengl.*;

@EventBusSubscriber(modid=AnyaStorage.ID,value=Dist.CLIENT)
public final class CameraScreen {
    private static CameraDevices.Device target;
    private static CameraFeed.Frame current;
    private static CameraScene scene;
    private static long tick,expiry,request;
    private static net.minecraft.client.player.LocalPlayer perspectivePlayer;
    private static net.minecraft.client.CameraType perspective;
    private static String motion="";
    static {
        CameraConsole.displayReceiver=(connection,view)->{
            var mc=Minecraft.getInstance();
            if(mc.getConnection()!=null&&mc.getConnection().getConnection()==connection)DisplaySourceScreen.receive(view,false,true);
        };
        CameraConsole.roomDisplayReceiver=(connection,view)->{
            var mc=Minecraft.getInstance();
            if(mc.getConnection()!=null&&mc.getConnection().getConnection()==connection)
                DisplaySourceScreen.receive(view.display(),true,view.manageCamera());
        };
        CameraConsole.receiver=(connection,view)->{
            var mc=Minecraft.getInstance();
            if(mc.getConnection()!=null&&mc.getConnection().getConnection()==connection)CameraControlsScreen.receive(view);
        };
        CameraConsole.motionReceiver=(connection,cue)->{
            var mc=Minecraft.getInstance();
            if((current!=null||scene!=null)&&target!=null&&target.getLevel()==mc.level&&target.getBlockPos().equals(cue.pos())
                    &&mc.getConnection()!=null&&mc.getConnection().getConnection()==connection)motion=cue.kind();
        };
        CameraFeed.receiver=(connection,frame)->{
            if(!accepts(connection,frame.pos()))return;
            current=frame.pixels().length==256||frame.pixels().length==1024?frame:null;
            scene=null;motion="";expiry=tick+16;
        };
        CameraScene.receiver=(connection,frame)->{
            if(!accepts(connection,frame.pos()) || !(target.getBlockState().getBlock() instanceof WallMonitorBlock))return;
            scene=frame.time()>=0 && frame.cells().length==CameraScene.SIDE*CameraScene.SIDE?frame:null;
            current=null;motion="";expiry=tick+16;
        };
    }
    private static boolean accepts(net.minecraft.network.Connection connection,BlockPos pos){
        var mc=Minecraft.getInstance();
        return target!=null && !target.isRemoved() && target.getLevel()==mc.level && target.getBlockPos().equals(pos)
                && mc.getConnection()!=null && mc.getConnection().getConnection()==connection;
    }
    private static CameraDevices.Device pick(Minecraft mc){
        if(mc.player==null || !mc.player.isAlive() || mc.player.isSpectator() || mc.screen!=null || mc.level==null
                || mc.getCameraEntity()!=mc.player)return null;
        Vec3 eye=mc.player.getEyePosition(),look=mc.player.getLookAngle();
        if(!mc.options.getCameraType().isFirstPerson()){
            var camera=mc.gameRenderer.getMainCamera();
            if(!camera.isInitialized() || !camera.isDetached() || camera.getEntity()!=mc.player)return null;
            eye=camera.getPosition();
            look=new Vec3(camera.getLookVector());
        }
        var end=eye.add(look.scale(48));
        var hit=mc.level.clip(new net.minecraft.world.level.ClipContext(eye,end,net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,mc.player));
        if(hit.getType()!=HitResult.Type.BLOCK)return nearbyPanel(mc,eye,look);
        var wall=WallPanel.atHit(mc.level,hit,eye,look);
        if(wall!=null)return wall;
        BlockPos pos=hit.getBlockPos();
        for(int y=0;y<=1;y++)for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            var cell=pos.offset(x,-y,z);
            if(!mc.level.hasChunkAt(cell) || !(mc.level.getBlockEntity(cell) instanceof CameraDevices.Device d)
                    || !d.getBlockState().is(CameraDevices.SKY.get()))continue;
            Vec3 intersection=CameraFeed.looking(d,eye,look);
            if(intersection!=null && eye.distanceTo(intersection)<=eye.distanceTo(hit.getLocation())+.02)return d;
        }
        return nearbyPanel(mc,eye,look);
    }
    private static boolean visible(Minecraft mc,CameraDevices.Device d,Vec3 eye,Vec3 look){
        if(d==null||d.isRemoved()||d.getLevel()!=mc.level||!(d.getBlockState().getBlock() instanceof WallMonitorBlock))return false;
        return hasSight(mc,d,eye,look);
    }
    private static boolean hasSight(Minecraft mc,CameraDevices.Device d,Vec3 eye,Vec3 look){
        var point=CameraFeed.visiblePoint(d,eye,look);
        return point!=null&&mc.level.clip(new net.minecraft.world.level.ClipContext(eye,point,net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,mc.player)).getType()==HitResult.Type.MISS;
    }
    private static CameraDevices.Device nearbyPanel(Minecraft mc,Vec3 eye,Vec3 look){
        if(visible(mc,target,eye,look))return target;
        if(tick%8!=0)return null;
        var seen=new java.util.HashSet<CameraDevices.Device>();var pos=mc.player.blockPosition();
        int rays=0;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(var d:WallPanel.at(mc.level,pos.offset(16*dx,0,16*dz))){
            if(!seen.add(d))continue;
            if(++rays>24)return null;
            if(visible(mc,d,eye,look))return d;
        }
        return null;
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){target=null;current=null;scene=null;perspectivePlayer=null;perspective=null;motion="";SkyglassPixels.clear();CameraPicture.clear();RoomMediaClient.disconnect();ScreenMedia.clear();}
    @SubscribeEvent public static void update(ClientTickEvent.Post event){
        tick++;
        var mc=Minecraft.getInstance();
        RoomMediaClient.tick();
        ScreenMedia.tick();
        var mode=mc.options.getCameraType();
        if(perspectivePlayer!=mc.player || perspective!=mode){
            perspectivePlayer=mc.player;perspective=mode;
            target=null;current=null;scene=null;motion="";request=0;
            if(mc.player!=null && mc.getConnection()!=null && mc.getConnection().hasChannel(CameraFeed.Perspective.TYPE))
                PacketDistributor.sendToServer(new CameraFeed.Perspective(mode.isMirrored()));
        }
        var d=pick(mc);
        if(d!=null && !mode.isFirstPerson()){
            var look=mc.player.getLookAngle();
            if(!hasSight(mc,d,mc.player.getEyePosition(),mode.isMirrored()?look.reverse():look))d=null;
        }
        if(target!=d){motion="";current=null;scene=null;target=d;request=0;}
        if(tick>expiry){current=null;scene=null;}
        if(scene==null && CameraPicture.rendered()!=null)CameraPicture.clear();
        if(d!=null && !ScreenMedia.active(d.getBlockPos()) && tick>=request && mc.getConnection()!=null && mc.getConnection().hasChannel(CameraFeed.Watch.TYPE)){
            PacketDistributor.sendToServer(new CameraFeed.Watch(d.getBlockPos()));request=tick+8;
        }
    }
    @SubscribeEvent public static void draw(RenderLevelStageEvent event){
        var mc=Minecraft.getInstance();
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL || mc.level==null || mc.player==null || mc.screen!=null
                || mc.getCameraEntity()!=mc.player || event.getCamera()!=mc.gameRenderer.getMainCamera()
                || event.getLevelRenderer()!=mc.levelRenderer || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)!=mc.getMainRenderTarget().frameBufferId)return;
        // The media display stays visible in peripheral vision, independent of camera-feed selection.
        var active=ScreenMedia.activePosition();
        CameraDevices.Device media=null;
        if(active!=null && mc.level.hasChunkAt(active) && mc.level.getBlockEntity(active) instanceof CameraDevices.Device d
                && !d.isRemoved() && d.getBlockState().getBlock() instanceof WallMonitorBlock){
            media=d;
            drawDevice(event,mc,d,true);
        }
        if(target!=null && target!=media && !target.isRemoved() && target.getLevel()==mc.level)
            drawDevice(event,mc,target,false);
    }
    private static void drawDevice(RenderLevelStageEvent event,Minecraft mc,CameraDevices.Device display,boolean media){
        var p=event.getPoseStack();p.pushPose();
        try{
            var pos=CameraFeed.screenCenter(display).subtract(event.getCamera().getPosition());
            p.mulPose(event.getModelViewMatrix());p.translate(pos.x,pos.y,pos.z);
            var state=display.getBlockState();var face=state.getValue(BlockStateProperties.FACING);
            boolean wall=state.getBlock() instanceof WallMonitorBlock;
            if(!wall)p.mulPose(face.getRotation());
            float w=wall?display.panel.innerWidth():4;
            float h=wall?display.panel.innerHeight(display):4;
            var right=wall?WallPanel.right(face):null;
            if(media){
                drawMedia(mc,p,display,right,w,h);
                return;
            }
            if(wall && scene!=null){
                var image=CameraPicture.surface(scene);
                if(image!=null){
                    var buffers=mc.renderBuffers().bufferSource();
                    // Opaque unlit texture with the normal world depth test. Lighting is already in the feed.
                    var type=RenderType.beaconBeam(image,false);
                    var v=buffers.getBuffer(type);
                    // Fill non-square panels without stretching the camera's blocks or animals.
                    float uSpan=Math.min(1f,w/h),vSpan=Math.min(1f,h/w);
                    float left=(1-uSpan)*.5f,top=(1-vSpan)*.5f;
                    point(p,v,right,-w*.5f,h*.5f,left,top);
                    point(p,v,right,-w*.5f,-h*.5f,left,top+vSpan);
                    point(p,v,right,w*.5f,-h*.5f,left+uSpan,top+vSpan);
                    point(p,v,right,w*.5f,h*.5f,left+uSpan,top);
                    buffers.endBatch(type);
                    return;
                }
            }
            var buffers=mc.renderBuffers().bufferSource();var type=wall?RenderType.debugQuads():RenderType.lightning();var vertices=buffers.getBuffer(type);
            int side=current!=null&&current.pixels().length==1024?32:16;
            for(int y=0;y<side;y++)for(int x=0;x<side;x++){
                int color=current==null?0x161722:current.pixels()[y*side+x];
                if(current!=null)color=SkyglassPixels.color(color,current.background(),current.clarity());
                float x0=w*((float)x/side-.5f),z0=h*((float)y/side-.5f),step=w/side;
                int rgb=color|0xff000000;
                if(wall){
                    float x1=x0+step,top=-z0,bottom=top-h/side;
                    int dx=right.getStepX(),dz=right.getStepZ();
                    vertices.addVertex(p.last(),dx*x0,top,dz*x0).setColor(rgb);
                    vertices.addVertex(p.last(),dx*x0,bottom,dz*x0).setColor(rgb);
                    vertices.addVertex(p.last(),dx*x1,bottom,dz*x1).setColor(rgb);
                    vertices.addVertex(p.last(),dx*x1,top,dz*x1).setColor(rgb);continue;
                }
                vertices.addVertex(p.last(),x0,0,z0).setColor(rgb);
                vertices.addVertex(p.last(),x0,0,z0+step).setColor(rgb);
                vertices.addVertex(p.last(),x0+step,0,z0+step).setColor(rgb);
                vertices.addVertex(p.last(),x0+step,0,z0).setColor(rgb);
            }
            buffers.endBatch(type);
        }finally{p.popPose();}
    }
    private static void drawMedia(Minecraft mc,com.mojang.blaze3d.vertex.PoseStack p,CameraDevices.Device display,net.minecraft.core.Direction right,float w,float h){
        var buffers=mc.renderBuffers().bufferSource();var black=RenderType.debugQuads();var background=buffers.getBuffer(black);
        float x=right.getStepX()*w*.5f,z=right.getStepZ()*w*.5f;
        background.addVertex(p.last(),-x,h*.5f,-z).setColor(0xff101018);
        background.addVertex(p.last(),-x,-h*.5f,-z).setColor(0xff101018);
        background.addVertex(p.last(),x,-h*.5f,z).setColor(0xff101018);
        background.addVertex(p.last(),x,h*.5f,z).setColor(0xff101018);
        buffers.endBatch(black);
        var pos=display.getBlockPos();var image=ScreenMedia.texture(pos);
        if(image==null)return;
        var normal=WallPanel.face(display);p.translate(normal.getStepX()*.001,0,normal.getStepZ()*.001);
        float aspect=ScreenMedia.aspectRatio(pos);
        float hw=Math.min(w,h*aspect)*.5f,hh=Math.min(h,w/aspect)*.5f;
        var layer=RenderType.beaconBeam(image,false);var v=buffers.getBuffer(layer);
        point(p,v,right,-hw,hh,0,0);point(p,v,right,-hw,-hh,0,1);
        point(p,v,right,hw,-hh,1,1);point(p,v,right,hw,hh,1,0);
        buffers.endBatch(layer);
    }
    private static void point(com.mojang.blaze3d.vertex.PoseStack p,com.mojang.blaze3d.vertex.VertexConsumer v,
                              net.minecraft.core.Direction right,float x,float y,float u,float t){
        v.addVertex(p.last(),right.getStepX()*x,y,right.getStepZ()*x).setColor(0xffffffff).setUv(u,t)
                .setLight(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT)
                .setNormal(p.last(),-right.getStepZ(),0,right.getStepX());
    }
    @SubscribeEvent public static void status(RenderGuiEvent.Post event){
        var mc=Minecraft.getInstance();if(target==null || target.isRemoved() || mc.screen!=null || mc.level!=target.getLevel())return;
        String line;
        if(ScreenMedia.active(target.getBlockPos()))line=ScreenMedia.status(target.getBlockPos());
        else if(current==null && scene==null)line=target.getBlockState().getBlock() instanceof FlatScreenTvBlock
                ?net.minecraft.network.chat.Component.translatable("screen.anyastorage.media.idle").getString():"Camera unavailable";
        else{
            long time=scene==null?current.time():scene.time();
            long hour=Math.floorMod(time/1000+6,24);
            line="Outdoor camera  "+String.format(java.util.Locale.ROOT,"%02d:%02d",hour,Math.floorMod(time,1000)*60/1000);
            var id=net.minecraft.resources.ResourceLocation.tryParse(motion);
            if(!motion.isEmpty()&&id!=null)line+="  ·  Motion: "+net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getOptional(id).map(type->type.getDescription().getString()).orElse("Mob");
            if(current!=null && current.background()<0 && java.util.Arrays.stream(current.pixels()).anyMatch(value->value==CameraFeed.SKY))line+="  Â·  Sky image unavailable";
        }
        var gui=event.getGuiGraphics();gui.drawCenteredString(mc.font,line,gui.guiWidth()/2,gui.guiHeight()-63,0xffeedcec);
    }
}
