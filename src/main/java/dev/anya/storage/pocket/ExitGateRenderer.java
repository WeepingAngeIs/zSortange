package dev.anya.storage.pocket;

import com.mojang.blaze3d.vertex.*;
import dev.anya.storage.AnyaStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.lwjgl.opengl.*;

@EventBusSubscriber(modid=AnyaStorage.ID,value=Dist.CLIENT)
public final class ExitGateRenderer {
    private static ExitGate.Marker marker;
    private static ClientLevel level;
    private static long expires;
    static {ExitGate.receiver=(connection,m)->{
        var mc=Minecraft.getInstance();
        if(mc.getConnection()==null || mc.getConnection().getConnection()!=connection || mc.level==null || !PocketService.inPocket(mc.level))return;
        marker=m;level=mc.level;expires=level.getGameTime()+65;
    };}
    @SubscribeEvent public static void unload(LevelEvent.Unload e){if(e.getLevel()==level){level=null;marker=null;}}
    @SubscribeEvent public static void render(RenderLevelStageEvent e){
        var mc=Minecraft.getInstance();
        if(marker==null || mc.level!=level || level.getGameTime()>expires || e.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL
                || mc.player==null || !mc.player.isAlive() || mc.player.isSpectator() || mc.getCameraEntity()!=mc.player
                || e.getCamera()!=mc.gameRenderer.getMainCamera() || e.getLevelRenderer()!=mc.levelRenderer
                || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)!=mc.getMainRenderTarget().frameBufferId)return;
        var pos=ExitGate.center(marker.stone());var camera=e.getCamera().getPosition();
        var pose=e.getPoseStack();pose.pushPose();
        try{
            pose.mulPose(e.getModelViewMatrix());pose.translate(pos.x-camera.x,pos.y-camera.y+.03,pos.z-camera.z);
            if(camera.z>pos.z)pose.scale(-1,1,1);
            var buffers=mc.renderBuffers().bufferSource();var type=RenderType.lightning();var v=buffers.getBuffer(type);
            int rgb=ExitGate.COLORS[Math.floorMod(marker.color(),ExitGate.COLORS.length)];
            float time=(level.getGameTime()%2400+e.getPartialTick().getGameTimeDeltaPartialTick(false))*.075f;
            // Elliptical frame with a translucent curtain and a second glint that travels around the rim.
            for(int i=0;i<48;i++){
                double a=Math.PI*2*i/48,b=Math.PI*2*(i+1)/48;
                float x=(float)Math.cos(a),y=(float)Math.sin(a),u=(float)Math.cos(b),w=(float)Math.sin(b);
                float pulse=(float)(.5+.5*Math.sin(a*3-time));
                strip(v,pose,x,y,u,w,.96f,1,rgb,190);
                strip(v,pose,x,y,u,w,.90f,.96f,rgb,(int)(65+85*pulse));
                strip(v,pose,x,y,u,w,.01f,.90f,rgb,(int)(22+16*pulse));
            }
            buffers.endBatch(type);
        }finally{pose.popPose();}
    }
    private static void strip(VertexConsumer v,PoseStack p,float x,float y,float u,float w,float inner,float outer,int color,int alpha){
        point(v,p,x,y,inner,color,alpha);point(v,p,u,w,inner,color,alpha);
        point(v,p,u,w,outer,color,alpha);point(v,p,x,y,outer,color,alpha);
    }
    private static void point(VertexConsumer v,PoseStack p,float x,float y,float scale,int color,int alpha){
        v.addVertex(p.last(),1.38f*x*scale,1.6f+1.6f*y*scale,0).setColor(color>>16,(color>>8)&255,color&255,alpha);
    }
}
