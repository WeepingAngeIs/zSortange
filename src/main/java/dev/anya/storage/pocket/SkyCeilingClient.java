package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.lwjgl.opengl.*;

@EventBusSubscriber(modid=AnyaStorage.ID,value=Dist.CLIENT)
public final class SkyCeilingClient {
    private static SkyCeiling.Frame current;
    private static ClientLevel level;
    private static long tick,until;
    static {SkyCeiling.receiver=(connection,frame)->{
        var mc=Minecraft.getInstance();
        if(mc.getConnection()==null||mc.getConnection().getConnection()!=connection)return;
        boolean valid=frame.active()&&mc.level!=null&&PocketService.inPocket(mc.level)&&(frame.pixels().length==0||frame.pixels().length==1024);
        current=valid?frame:null;level=valid?mc.level:null;until=tick+18;
    };}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){if(++tick>until)current=null;}
    @SubscribeEvent public static void unload(LevelEvent.Unload event){if(event.getLevel()==level){current=null;level=null;}}
    private static boolean visible(Minecraft mc){
        if(current==null||mc.level!=level||mc.player==null||!mc.player.isAlive()||mc.player.isSpectator()||mc.getCameraEntity()!=mc.player)return false;
        var rel=mc.player.blockPosition().subtract(current.origin());
        return rel.getX()>=0&&rel.getX()<32&&rel.getZ()>=0&&rel.getZ()<32&&rel.getY()>=0&&rel.getY()<32;
    }
    @SubscribeEvent public static void status(RenderGuiEvent.Post e){
        var mc=Minecraft.getInstance();if(!visible(mc)||current.pixels().length!=0||mc.player.getLookAngle().y<.4||mc.screen!=null||mc.options.hideGui)return;
        var g=e.getGuiGraphics();g.drawCenteredString(mc.font,"Sky Camera unavailable",g.guiWidth()/2,g.guiHeight()-62,0xffeedcec);
    }
    @SubscribeEvent public static void draw(RenderLevelStageEvent e){
        if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;
        var mc=Minecraft.getInstance();if(!visible(mc)||e.getCamera()!=mc.gameRenderer.getMainCamera()||e.getLevelRenderer()!=mc.levelRenderer
                ||GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)!=mc.getMainRenderTarget().frameBufferId)return;
        var eye=e.getCamera().getPosition();var origin=current.origin();var pose=e.getPoseStack();pose.pushPose();
        try{
            pose.mulPose(e.getModelViewMatrix());pose.translate(origin.getX()-eye.x,origin.getY()+31.98-eye.y,origin.getZ()-eye.z);
            var buffers=mc.renderBuffers().bufferSource();var type=RenderType.debugQuads();var v=buffers.getBuffer(type);
            int side=current.pixels().length==0?1:32;float k=32f/side;
            for(int z=0;z<side;z++)for(int x=0;x<side;x++){
                int rgb=current.pixels().length==0?0x171a21:SkyglassPixels.color(current.pixels()[z*side+x],current.background(),current.clarity());rgb|=0xff000000;
                float left=x*k,right=left+k,near=z*k,far=near+k;
                v.addVertex(pose.last(),left,0,near).setColor(rgb);v.addVertex(pose.last(),right,0,near).setColor(rgb);
                v.addVertex(pose.last(),right,0,far).setColor(rgb);v.addVertex(pose.last(),left,0,far).setColor(rgb);
            }
            buffers.endBatch(type);
        }finally{pose.popPose();}
    }
}
