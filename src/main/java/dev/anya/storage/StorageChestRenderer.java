package dev.anya.storage;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Both halves of the symbol use the same UV image and follow their own chest part. */
public final class StorageChestRenderer implements BlockEntityRenderer<StorageBlockEntity> {
    public static final ModelLayerLocation LAYER=new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"storage_chest"),"main");
    private static final ResourceLocation TEXTURE=ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"textures/entity/storage_chest.png");
    private final ModelPart base,lid;
    public StorageChestRenderer(BlockEntityRendererProvider.Context context){ModelPart root=context.bakeLayer(LAYER);base=root.getChild("base");lid=root.getChild("lid");}
    public static LayerDefinition layer(){
        MeshDefinition mesh=new MeshDefinition();PartDefinition root=mesh.getRoot();
        root.addOrReplaceChild("base",CubeListBuilder.create().texOffs(0,0).addBox(1,0,1,14,10,14),PartPose.ZERO);
        root.addOrReplaceChild("lid",CubeListBuilder.create().texOffs(0,24).addBox(1,0,-14,14,4,14),PartPose.offset(0,10,15));
        return LayerDefinition.create(mesh,64,64);
    }
    @Override public void render(StorageBlockEntity block,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        if(block.kind()!=1)return;
        pose.pushPose();pose.translate(0.5,0,0.5);
        float degrees=switch(block.getBlockState().getValue(StorageBlock.FACING)){case SOUTH->180;case EAST->270;case WEST->90;default->0;};
        pose.mulPose(Axis.YP.rotationDegrees(degrees));pose.translate(-0.5,0,-0.5);
        float opening=Mth.lerp(partial,block.previousOpen,block.open);float eased=1-(float)Math.pow(1-opening,3);lid.xRot=eased*Mth.HALF_PI;
        VertexConsumer body=buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));base.render(pose,body,light,overlay);lid.render(pose,body,light,overlay);
        if(Rules.current.labels){
            ResourceLocation symbol=ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"textures/entity/label_"+Math.floorMod(block.label,7)+".png");
            VertexConsumer sticker=buffers.getBuffer(RenderType.entityCutoutNoCull(symbol));
            face(pose,sticker,5/16f,11/16f,7/16f,10/16f,0.99f/16,0.5f,1,light,overlay);
            pose.pushPose();lid.translateAndRotate(pose);
            face(pose,sticker,5/16f,11/16f,0,3/16f,-14.01f/16,0,0.5f,light,overlay);pose.popPose();
        }
        pose.popPose();
    }
    private static void face(PoseStack p,VertexConsumer v,float x0,float x1,float y0,float y1,float z,float top,float bottom,int light,int overlay){
        vertex(p,v,x0,y1,z,0,top,light,overlay);vertex(p,v,x0,y0,z,0,bottom,light,overlay);
        vertex(p,v,x1,y0,z,1,bottom,light,overlay);vertex(p,v,x1,y1,z,1,top,light,overlay);
    }
    private static void vertex(PoseStack p,VertexConsumer v,float x,float y,float z,float u,float w,int light,int overlay){
        v.addVertex(p.last().pose(),x,y,z).setColor(255,255,255,255).setUv(u,w).setOverlay(overlay).setLight(light).setNormal(p.last(),0,0,-1);
    }
}
