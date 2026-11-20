package dev.anya.storage.furniture;

import dev.anya.storage.AnyaStorage;
import dev.anya.storage.StorageBlock;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.*;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Project onto the physical display at final HUD composition. No private pixels enter a world render pass. */
@EventBusSubscriber(modid=AnyaStorage.ID,value=Dist.CLIENT)
public final class ShelfRenderer {
    static { ShelfPackets.receiver=ShelfRenderer::receive; }
    private static FurnitureEntity target, projectedTarget;
    private static ItemStack model=ItemStack.EMPTY;
    private static String caption="";
    private static final ShelfGrant grant=new ShelfGrant();
    private static long nextRequest,ticks;
    private static Matrix4f projection;
    private static Vec3 origin;
    private static Vector3f left;
    private static FurnitureEntity picked(Minecraft mc) {
        Camera camera=mc.gameRenderer.getMainCamera();
        if(mc.level==null || mc.player==null || !mc.player.isAlive() || mc.player.isSpectator() || mc.screen!=null
                || mc.getCameraEntity()!=mc.player || camera.getEntity()!=mc.player || !mc.options.getCameraType().isFirstPerson()
                || mc.player.level()!=mc.level || camera.getPosition().distanceToSqr(mc.player.getEyePosition())>4
                || !(mc.hitResult instanceof BlockHitResult h) || h.getType()!=HitResult.Type.BLOCK
                || mc.player.getEyePosition().distanceToSqr(h.getLocation())>25 || !mc.level.hasChunkAt(h.getBlockPos())) return null;
        return mc.level.getBlockEntity(h.getBlockPos()) instanceof FurnitureEntity f && !f.isRemoved() && f.shelf()
                && f.publicModel.isEmpty() ? f : null;
    }
    private static void clearDisplay() { model=ItemStack.EMPTY;caption=""; }
    private static void reset() { clearDisplay();target=null;projectedTarget=null;grant.clear(); }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e) { reset(); }
    @SubscribeEvent public static void tick(ClientTickEvent.Post e) {
        ticks++;
        Minecraft mc=Minecraft.getInstance();
        var f=picked(mc);
        if(f!=target) { reset();target=f; }
        if(f==null || mc.getConnection()==null || !mc.getConnection().hasChannel(ShelfPackets.Focus.TYPE) || ticks<nextRequest) return;
        nextRequest=ticks+6;
        PacketDistributor.sendToServer(new ShelfPackets.Focus(f.getBlockPos(),grant.request(ticks,System.nanoTime())));
    }
    private static void receive(Connection connection,ShelfPackets.Display display) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.getConnection()==null || mc.getConnection().getConnection()!=connection || target==null
                || !grant.accepts(display.token()) || !target.getBlockPos().equals(display.pos()) || picked(mc)!=target) return;
        clearDisplay();
        if(!grant.consume(display.token(),display.item()!=null,ticks,System.nanoTime())) return;
        model=new ItemStack(BuiltInRegistries.ITEM.get(display.item()));
        caption=display.caption();
    }
    @SubscribeEvent public static void projection(RenderLevelStageEvent e) {
        if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft mc=Minecraft.getInstance();
        projectedTarget=null;
        if(target==null || e.getCamera()!=mc.gameRenderer.getMainCamera() || picked(mc)!=target) return;
        // This callback captures only camera geometry. Portal, map, and shadow passes never receive item or text.
        projection=new Matrix4f(e.getProjectionMatrix()).mul(e.getModelViewMatrix());
        origin=e.getCamera().getPosition();
        left=new Vector3f(e.getCamera().getLeftVector());
        projectedTarget=target;
    }
    private static Vector2f screenPoint(Vec3 world,GuiGraphics gui) {
        Vec3 d=world.subtract(origin);
        Vector4f clip=projection.transform(new Vector4f((float)d.x,(float)d.y,(float)d.z,1));
        if(clip.w<=0.01f || java.lang.Math.abs(clip.x)>clip.w || java.lang.Math.abs(clip.y)>clip.w) return null;
        return new Vector2f((1+clip.x/clip.w)*gui.guiWidth()/2,(1-clip.y/clip.w)*gui.guiHeight()/2);
    }
    @SubscribeEvent public static void draw(RenderGuiEvent.Post e) {
        Minecraft mc=Minecraft.getInstance();
        FurnitureEntity f=projectedTarget;
        projectedTarget=null;
        if(f==null || f!=target || !grant.visible(ticks,System.nanoTime()) || picked(mc)!=f
                || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)!=mc.getMainRenderTarget().frameBufferId) return;
        var gui=e.getGuiGraphics();
        Vec3 center=Vec3.atCenterOf(f.getBlockPos()).add(0,.06,0);
        Vector2f point=screenPoint(center,gui);
        Vector2f edge=screenPoint(center.add(left.x*.24,left.y*.24,left.z*.24),gui);
        if(point==null || edge==null) return;
        float width=2*point.distance(edge);
        if(width<2 || width>gui.guiWidth()) return;
        var pose=gui.pose();
        if(!model.isEmpty()) {
            pose.pushPose();
            pose.translate(point.x,point.y,0);
            pose.scale(width/16,width/16,1);
            gui.renderItem(model,-8,-8);
            pose.popPose();
        }
        var facing=f.getBlockState().getValue(StorageBlock.FACING);
        Vec3 plate=center.add(.48*facing.getStepX(),-.44,.48*facing.getStepZ());
        Vector2f label=screenPoint(plate,gui);
        if(label!=null && !caption.isBlank() && origin.subtract(plate).dot(Vec3.atLowerCornerOf(facing.getNormal()))>0) {
            pose.pushPose();
            pose.translate(label.x,label.y,0);
            float scale=width/92f;
            pose.scale(scale,scale,1);
            String line=mc.font.plainSubstrByWidth(caption,128);
            if(!line.equals(caption)) line=mc.font.plainSubstrByWidth(caption,119)+"…";
            gui.drawString(mc.font,line,-mc.font.width(line)/2,-4,0xff362120,false);
            pose.popPose();
        }
    }
}
