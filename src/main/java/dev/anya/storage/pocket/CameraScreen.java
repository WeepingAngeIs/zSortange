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
    private static long tick,expiry,request;
    static {
        CameraFeed.receiver=(connection,frame)->{
            var mc=Minecraft.getInstance();
            if(target==null || target.getLevel()!=mc.level || target.isRemoved() || mc.getConnection()==null
                    || mc.getConnection().getConnection()!=connection || !target.getBlockPos().equals(frame.pos()))return;
            current=frame.pixels().length==256?frame:null;
            expiry=tick+16;
        };
    }
    private static CameraDevices.Device pick(Minecraft mc){
        if(mc.player==null || !mc.player.isAlive() || mc.player.isSpectator() || mc.screen!=null || mc.level==null
                || mc.getCameraEntity()!=mc.player || !mc.options.getCameraType().isFirstPerson())return null;
        Vec3 eye=mc.player.getEyePosition(),look=mc.player.getLookAngle();
        var end=eye.add(look.scale(10));
        var hit=mc.level.clip(new net.minecraft.world.level.ClipContext(eye,end,net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,mc.player));
        if(hit.getType()!=HitResult.Type.BLOCK)return null;
        BlockPos pos=hit.getBlockPos();
        for(int y=0;y<=1;y++)for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            var cell=pos.offset(x,-y,z);
            if(!mc.level.hasChunkAt(cell) || !(mc.level.getBlockEntity(cell) instanceof CameraDevices.Device d)
                    || d.getBlockState().is(CameraDevices.CAMERA.get()))continue;
            Vec3 intersection=CameraFeed.looking(d,eye,look);
            if(intersection!=null && eye.distanceTo(intersection)<=eye.distanceTo(hit.getLocation())+.02)return d;
        }
        return null;
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){target=null;current=null;}
    @SubscribeEvent public static void update(ClientTickEvent.Post event){
        tick++;
        var mc=Minecraft.getInstance();var d=pick(mc);
        if(target!=d){current=null;target=d;request=0;}
        if(tick>expiry)current=null;
        if(d!=null && tick>=request && mc.getConnection()!=null && mc.getConnection().hasChannel(CameraFeed.Watch.TYPE)){
            PacketDistributor.sendToServer(new CameraFeed.Watch(d.getBlockPos()));request=tick+8;
        }
    }
    @SubscribeEvent public static void draw(RenderLevelStageEvent event){
        var mc=Minecraft.getInstance();
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL || target==null || target.isRemoved() || mc.screen!=null
                || mc.level!=target.getLevel() || mc.getCameraEntity()!=mc.player || event.getCamera()!=mc.gameRenderer.getMainCamera()
                || event.getLevelRenderer()!=mc.levelRenderer || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)!=mc.getMainRenderTarget().frameBufferId)return;
        var p=event.getPoseStack();p.pushPose();
        try{
            var pos=CameraFeed.screenCenter(target).subtract(event.getCamera().getPosition());
            p.mulPose(event.getModelViewMatrix());p.translate(pos.x,pos.y,pos.z);
            p.mulPose(target.getBlockState().getValue(BlockStateProperties.FACING).getRotation());
            float side=target.getBlockState().is(CameraDevices.SKY.get())?4:.94f;
            var buffers=mc.renderBuffers().bufferSource();var type=RenderType.lightning();var vertices=buffers.getBuffer(type);
            for(int y=0;y<16;y++)for(int x=0;x<16;x++){
                int color=current==null?0x161722:current.pixels()[y*16+x];
                if(color==CameraFeed.UNKNOWN || color==CameraFeed.SKY)color=0x161722;
                float x0=side*(x/16f-.5f),z0=side*(y/16f-.5f),step=side/16;
                int rgb=color|0xff000000;
                vertices.addVertex(p.last(),x0,0,z0).setColor(rgb);
                vertices.addVertex(p.last(),x0,0,z0+step).setColor(rgb);
                vertices.addVertex(p.last(),x0+step,0,z0+step).setColor(rgb);
                vertices.addVertex(p.last(),x0+step,0,z0).setColor(rgb);
            }
            buffers.endBatch(type);
        }finally{p.popPose();}
    }
    @SubscribeEvent public static void status(RenderGuiEvent.Post event){
        var mc=Minecraft.getInstance();if(target==null || target.isRemoved() || mc.screen!=null || mc.level!=target.getLevel())return;
        String line;
        if(current==null)line="Camera unavailable";
        else{
            long hour=Math.floorMod(current.time()/1000+6,24);
            line="Outdoor camera  "+String.format(java.util.Locale.ROOT,"%02d:%02d",hour,Math.floorMod(current.time(),1000)*60/1000);
            if(java.util.Arrays.stream(current.pixels()).anyMatch(value->value==CameraFeed.SKY))line+="  ·  Sky image unavailable";
        }
        var gui=event.getGuiGraphics();gui.drawCenteredString(mc.font,line,gui.guiWidth()/2,gui.guiHeight()-63,0xffeedcec);
    }
}
