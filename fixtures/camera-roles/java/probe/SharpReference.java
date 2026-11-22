package probe;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.anya.storage.pocket.CameraDevices;
import dev.anya.storage.pocket.CameraFeed;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Hosted reference only. Never sends client pixels to a viewer and never changes the player's camera entity. */
final class SharpReference {
    private static final class Lens extends Camera {
        void at(Minecraft mc, Vec3 eye, Direction face) {
            setup(mc.level, mc.player, true, false, 1);
            setPosition(eye);
            setRotation(face.getAxis().isVertical() ? 180 : face.toYRot(),
                    face == Direction.UP ? -90 : face == Direction.DOWN ? 90 : 0, 0);
        }
    }
    private static boolean rendering;

    static void capture(Minecraft mc, BlockPos cameraPos, Path image) throws IOException {capture(mc,cameraPos,image,90);}
    static void capture(Minecraft mc, BlockPos cameraPos, Path image,int verticalDegrees) throws IOException {
        RenderSystem.assertOnRenderThread();
        if (rendering) throw new IOException("Recursive stock camera reference");
        if (mc.level == null || mc.player == null || !mc.level.hasChunkAt(cameraPos)
                || !(mc.level.getBlockEntity(cameraPos) instanceof CameraDevices.Device camera))
            throw new IOException("Lens is unavailable on this outside client");

        var primary = mc.getMainRenderTarget();
        var playerCamera = mc.gameRenderer.getMainCamera();
        var ownPosition = mc.player.position();
        var primaryEye = playerCamera.getPosition();
        var primaryRotation = new Quaternionf(playerCamera.rotation());
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        RenderTarget second = null;
        rendering = true;
        modelView.pushMatrix();
        try {
            var eyeMethod = CameraFeed.class.getDeclaredMethod("eye",CameraDevices.Device.class);
            var aimMethod = CameraFeed.class.getDeclaredMethod("direction",CameraDevices.Device.class);
            eyeMethod.setAccessible(true); aimMethod.setAccessible(true);
            var lens = new Lens();
            lens.at(mc,(Vec3)eyeMethod.invoke(null,camera),(Direction)aimMethod.invoke(null,camera));
            var view = new Matrix4f().rotation(lens.rotation().conjugate(new Quaternionf()));
            int width=primary.width,height=primary.height;
            if(width<height)throw new IOException("Fixture expects a landscape client");
            var perspective = new Matrix4f().perspective((float)Math.toRadians(verticalDegrees),(float)width/height,.05f,128);
            // LevelRenderer internally rebinds getMainRenderTarget() after outline/translucent passes.
            // Redirect that field only inside this disposable client render and restore it in finally.
            var main = Minecraft.class.getDeclaredField("mainRenderTarget");
            var frustum = LevelRenderer.class.getDeclaredField("cullingFrustum");
            main.setAccessible(true); frustum.setAccessible(true);
            Object oldFrustum = frustum.get(mc.levelRenderer);
            // Use the real window dimensions: stock entity/weather passes can reset the viewport
            // to the window even while another framebuffer is bound. Crop the centered 90° square.
            second = new TextureTarget(width,height,true,Minecraft.ON_OSX);
            try {
                main.set(mc,second);
                if (mc.getMainRenderTarget()!=second) throw new IOException("World renderer target was not redirected");
                modelView.identity(); RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(perspective,VertexSorting.DISTANCE_TO_ORIGIN);
                second.bindWrite(true);
                mc.levelRenderer.prepareCullFrustum(lens.getPosition(),view,perspective);
                mc.getEntityRenderDispatcher().prepare(mc.level,lens,null);
                mc.getBlockEntityRenderDispatcher().prepare(mc.level,lens,null);
                mc.getProfiler().push("hosted_camera_reference");
                try {
                    mc.levelRenderer.renderLevel(mc.getTimer(),false,lens,mc.gameRenderer,mc.gameRenderer.lightTexture(),view,perspective);
                } finally { mc.getProfiler().pop(); }
                try(var result=Screenshot.takeScreenshot(second);var square=new NativeImage(height,height,false)){
                    int left=(width-height)/2;
                    for(int y=0;y<height;y++)for(int x=0;x<height;x++)square.setPixelRGBA(x,y,result.getPixelRGBA(left+x,y));
                    square.writeToFile(image);
                }
                Vec3 rel=mc.player.position().add(0,.9,0).subtract(lens.getPosition());
                var approach=Vec3.atLowerCornerOf(((Direction)aimMethod.invoke(null,camera)).getNormal());
                var right=approach.cross(new Vec3(0,1,0)).normalize();
                var up=right.cross(approach).normalize();
                double distance=rel.dot(approach);
                Files.writeString(image.resolveSibling(image.getFileName()+".actor.json"),Orders.JSON.toJson(
                        new double[]{.5+.5*rel.dot(right)/distance,.5-.5*rel.dot(up)/distance}));
            } finally {
                main.set(mc,primary);
                frustum.set(mc.levelRenderer,oldFrustum);
                mc.getEntityRenderDispatcher().prepare(mc.level,playerCamera,mc.crosshairPickEntity);
                mc.getBlockEntityRenderDispatcher().prepare(mc.level,playerCamera,mc.hitResult);
            }
            if (!ownPosition.equals(mc.player.position()) || mc.getCameraEntity()!=mc.player
                    || !primaryEye.equals(playerCamera.getPosition()) || !primaryRotation.equals(playerCamera.rotation()))
                throw new IOException("The second render changed the player's own camera");
        } catch (ReflectiveOperationException e) {
            throw new IOException("Cannot isolate the stock renderer on this Minecraft build",e);
        } finally {
            if (second!=null) second.destroyBuffers();
            modelView.popMatrix(); RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection,sorting);
            primary.bindWrite(true);
            rendering=false;
        }
    }
    private SharpReference() {}
}
