package dev.anya.storage.pocket;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.*;
import dev.anya.storage.AnyaStorage;
import dev.anya.storage.images.PngSanitizer;
import java.io.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.lwjgl.opengl.*;

@EventBusSubscriber(modid=AnyaStorage.ID,value=Dist.CLIENT)
public final class SkygridWallsClient {
    private static final ResourceLocation PAPER=id("textures/block/skygrid_light.png"), PHOTO=id("dynamic/skygrid_wall_photo");
    private static ResourceLocation id(String p){return ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,p);}
    private static BlockPos origin;
    private static ClientLevel level;
    private static int paper;
    private static boolean photo;
    private static long until;
    static {SkygridWalls.receiver=(connection,w)->{
        var mc=Minecraft.getInstance();
        if(mc.getConnection()==null || mc.getConnection().getConnection()!=connection)return;
        if(w.paper()<0 || mc.level==null || !PocketService.inPocket(mc.level)){clear();return;}
        if(level!=mc.level || !w.origin().equals(origin))clear();
        if(!w.hasPhoto() || w.pixels().length>0)forget();
        if(w.hasPhoto() && w.pixels().length>0)try{
            var canonical=PngSanitizer.canonical(w.pixels());var tex=NativeImage.read(new ByteArrayInputStream(canonical.png()));
            mc.getTextureManager().register(PHOTO,new DynamicTexture(tex));photo=true;
        }catch(IOException ignored){forget();}
        level=mc.level;origin=w.origin();paper=w.paper();until=System.nanoTime()+2_500_000_000L;
    };}
    private static void forget(){if(photo)Minecraft.getInstance().getTextureManager().release(PHOTO);photo=false;}
    private static void clear(){forget();origin=null;level=null;}
    @SubscribeEvent public static void unload(LevelEvent.Unload e){if(e.getLevel()==level)clear();}
    @SubscribeEvent public static void hud(RenderGuiEvent.Post e){
        var mc=Minecraft.getInstance();if(mc.level==null || !PocketService.inPocket(mc.level) || mc.options.hideGui)return;
        var g=e.getGuiGraphics();g.drawString(mc.font,"Skygrid",g.guiWidth()-mc.font.width("Skygrid")-6,g.guiHeight()/2-14,0xfff3c5dc,true);
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent e){
        if(origin==null || e.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;
        var mc=Minecraft.getInstance();if(System.nanoTime()>until){clear();return;}
        if(mc.level!=level || mc.player==null || !mc.player.isAlive() || mc.player.isSpectator()
                || e.getCamera()!=mc.gameRenderer.getMainCamera() || mc.getCameraEntity()!=mc.player
                || e.getLevelRenderer()!=mc.levelRenderer || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)!=mc.getMainRenderTarget().frameBufferId)return;
        var cam=e.getCamera().getPosition();double x=cam.x-origin.getX(),y=cam.y-origin.getY(),z=cam.z-origin.getZ();
        if(x<0||x>=32||y<0||y>=32||z<0||z>=32)return;
        var pose=e.getPoseStack();pose.pushPose();
        try{
            pose.mulPose(e.getModelViewMatrix());pose.translate(-x,-y,-z);
            var buffers=mc.renderBuffers().bufferSource();
            if(paper>0 && paper<4){
                int tint=new int[]{0,0xffd2e2,0xb9ffde,0xd8c8ff}[paper];
                var type=RenderType.entityCutoutNoCull(PAPER);var v=buffers.getBuffer(type);
                for(int side=0;side<4;side++){
                    pose.pushPose();pose.translate(16,0,16);pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(side*90));pose.translate(-16,0,-16);
                    for(int i=0;i<32;i+=4)for(int j=0;j<32;j+=4)quad(pose,v,i,j,i+4,j+4,.015f,tint);
                    pose.popPose();
                }
                buffers.endBatch(type);
            }
            if(photo){var type=RenderType.entityCutoutNoCull(PHOTO);quad(pose,buffers.getBuffer(type),12,2,20,10,.035f,0xffffff);buffers.endBatch(type);}
        }finally{pose.popPose();}
    }
    private static void quad(PoseStack p,VertexConsumer v,float x,float y,float xx,float yy,float z,int tint){
        point(p,v,x,y,z,0,1,tint);point(p,v,xx,y,z,1,1,tint);point(p,v,xx,yy,z,1,0,tint);point(p,v,x,yy,z,0,0,tint);
    }
    private static void point(PoseStack p,VertexConsumer v,float x,float y,float z,float u,float w,int c){
        v.addVertex(p.last(),x,y,z).setColor(c>>16,c>>8&255,c&255,255).setUv(u,w).setLight(LightTexture.FULL_BRIGHT)
                .setOverlay(OverlayTexture.NO_OVERLAY).setNormal(p.last(),0,0,1);
    }
}
