package dev.anya.storage;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** The sticker's two halves use the same texture and follow the base and hinged lid separately. */
public final class ChestRenderer implements BlockEntityRenderer<StorageEntity> {
    private final ModelPart base,lid;
    private static final ResourceLocation SHELL=ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"textures/entity/chest.png");
    public ChestRenderer(BlockEntityRendererProvider.Context context){
        MeshDefinition mesh=new MeshDefinition();PartDefinition root=mesh.getRoot();
        root.addOrReplaceChild("base",CubeListBuilder.create().texOffs(0,19).addBox(-7,0,-7,14,10,14),PartPose.ZERO);
        root.addOrReplaceChild("lid",CubeListBuilder.create().texOffs(0,0).addBox(-7,0,-14,14,4,14),PartPose.offset(0,10,7));
        ModelPart model=LayerDefinition.create(mesh,64,64).bakeRoot();base=model.getChild("base");lid=model.getChild("lid");
    }
    @Override public void render(StorageEntity entity,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        if(!entity.kind().equals("chest"))return;
        pose.pushPose();pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(180-entity.getBlockState().getValue(StorageBlock.FACING).toYRot()));
        float angle=Mth.lerp(partial,entity.previousOpenness,entity.openness);angle=1-(1-angle)*(1-angle)*(1-angle);angle*=Mth.HALF_PI;
        VertexConsumer shell=buffers.getBuffer(RenderType.entityCutout(SHELL));base.render(pose,shell,light,overlay);lid.xRot=angle;lid.render(pose,shell,light,overlay);
        String symbol=Config.SYMBOLS.contains(entity.symbol)?entity.symbol:"heart";
        VertexConsumer sticker=buffers.getBuffer(RenderType.entityCutoutNoCull(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"textures/entity/label_"+symbol+".png")));
        quad(pose,sticker,-4/16f,6/16f,4/16f,10/16f,-7.02f/16f,.5f,1,light,overlay);
        pose.pushPose();pose.translate(0,10/16f,7/16f);pose.mulPose(Axis.XP.rotation(angle));quad(pose,sticker,-4/16f,0,4/16f,4/16f,-14.02f/16f,0,.5f,light,overlay);pose.popPose();pose.popPose();
    }
    private static void quad(PoseStack pose,VertexConsumer v,float left,float bottom,float right,float top,float z,float vt,float vb,int light,int overlay){
        vertex(pose,v,left,bottom,z,0,vb,light,overlay);vertex(pose,v,left,top,z,0,vt,light,overlay);vertex(pose,v,right,top,z,1,vt,light,overlay);vertex(pose,v,right,bottom,z,1,vb,light,overlay);
    }
    private static void vertex(PoseStack pose,VertexConsumer v,float x,float y,float z,float u,float w,int light,int overlay){v.addVertex(pose.last().pose(),x,y,z).setColor(255,255,255,255).setUv(u,w).setOverlay(overlay).setLight(light).setNormal(0,0,-1);}
}
