package dev.anya.storage.pocket;

import com.mojang.blaze3d.vertex.*;
import dev.anya.storage.AnyaStorage;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid=AnyaStorage.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class WallPanelRenderer implements BlockEntityRenderer<CameraDevices.Device> {
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers e){
        e.registerBlockEntityRenderer(CameraDevices.TYPE.get(),ctx->new WallPanelRenderer());
    }
    @Override public AABB getRenderBoundingBox(CameraDevices.Device d){return d.panel.bounds(d);}
    @Override public boolean shouldRender(CameraDevices.Device d,Vec3 eye){return d.panel.bounds(d).distanceToSqr(eye)<64*64;}
    @Override public void render(CameraDevices.Device d,float partial,PoseStack p,MultiBufferSource buffers,int light,int overlay){
        if(!(d.getBlockState().getBlock() instanceof WallMonitorBlock)||d.panel.equals(WallPanel.ONE))return;
        var face=WallPanel.face(d);var center=d.panel.center(d).subtract(Vec3.atLowerCornerOf(d.getBlockPos()));
        var normal=Vec3.atLowerCornerOf(face.getNormal());var r=WallPanel.right(face);p.pushPose();
        try{
            // Both surfaces lie in front of the wall and behind the live pixels, in depth order.
            var base=center.subtract(normal.scale(.002));p.translate(base.x,base.y,base.z);
            var v=buffers.getBuffer(RenderType.debugQuads());float w=d.panel.width()*.5f,h=d.panel.height()*.5f;
            quad(v,p,r.getStepX(),r.getStepZ(),-w,-h,w,h,0xff736576);
            p.translate(normal.x*.001,normal.y*.001,normal.z*.001);
            quad(v,p,r.getStepX(),r.getStepZ(),-w+.035f,-h+.035f,w-.035f,h-.035f,0xff171924);
        }finally{p.popPose();}
    }
    static void quad(VertexConsumer v,PoseStack p,int dx,int dz,float l,float bottom,float r,float top,int color){
        v.addVertex(p.last(),dx*l,top,dz*l).setColor(color);v.addVertex(p.last(),dx*l,bottom,dz*l).setColor(color);
        v.addVertex(p.last(),dx*r,bottom,dz*r).setColor(color);v.addVertex(p.last(),dx*r,top,dz*r).setColor(color);
    }
}
