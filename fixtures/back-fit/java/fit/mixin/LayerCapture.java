package fit.mixin;
import fit.FitCheck;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only tap at the precise place where the worn layer submits the real item to Minecraft. */
@Mixin(targets="dev.anya.storage.appearance.ClientLooks$Layer", remap=false)
public abstract class LayerCapture {
    @Unique private Matrix4f backfitBase;
    @Unique private Matrix4f backfitVanilla;
    @Inject(method="render",at=@At("HEAD"))
    private void backfitStart(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity living,
                             float walk,float amount,float partial,float age,float yaw,float pitch,CallbackInfo ci) {
        if (!FitCheck.observing() || living!=Minecraft.getInstance().player) return;
        backfitBase=new Matrix4f(pose.last().pose()).invert();
        var model=((RenderLayer<?,?>)(Object)this).getParentModel();
        if(model instanceof HumanoidModel<?> humanoid){
            var baseline = new PoseStack();
            humanoid.body.translateAndRotate(baseline);
            baseline.translate(0,.29,.29);
            baseline.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180));
            baseline.scale(.72f,.72f,.72f);
            backfitVanilla=new Matrix4f(baseline.last().pose());
        }
    }
    @Inject(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;IILcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;I)V"))
    private void backfitRendered(PoseStack pose,MultiBufferSource buffers,int light,LivingEntity living,
                                float walk,float amount,float partial,float age,float yaw,float pitch,CallbackInfo ci) {
        if(backfitBase==null || living!=Minecraft.getInstance().player || !FitCheck.observing())return;
        FitCheck.sample(backfitBase.mul(pose.last().pose(), new Matrix4f()),backfitVanilla,amount);
    }
}
