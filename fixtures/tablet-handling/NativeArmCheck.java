package dev.anya.storage.appearance;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Runs production bytecode against a recording renderer in place of the GPU and live player. */
public final class NativeArmCheck {
    private static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }

    private static void reversingTurns(LocalPlayer player, PlayerRenderer renderer) {
        var graphics = new GuiGraphics();
        Minecraft.getInstance().getEntityRenderDispatcher().renderer = renderer;
        var motions = new TabletHandMotion[]{
            new TabletHandMotion(4, -4, .1f, -.3f, 1.3f),
            new TabletHandMotion(-4, 4, -.2f, -.1f, -1.3f),
            new TabletHandMotion(3, 4, .2f, -.2f, 1.3f),
            TabletHandMotion.STILL
        };
        for (boolean right : new boolean[]{false, true}) {
            for (boolean slim : new boolean[]{false, true}) {
                float side = right ? 1 : -1;
                var endOfHand = new Vector3f(-side * (slim ? .5f : 1f), 10, 0)
                        .rotateZ(side * .1f).add(-side * 5f, 2, 0).div(16);
                for (float progress : new float[]{0, .4f, 1, .65f, 0}) {
                    for (var motion : motions) {
                        var pose = graphics.pose();
                        pose.pushPose();
                        motion.apply(pose, 480, 320, progress);
                        var device = TabletHandlingPose.deviceMatrix(480, 320, right, .4f, .2f, progress);
                        var rail = new Matrix4f(pose.last().pose()).mul(device)
                                .transformPosition(new Vector3f(right ? 420f : 12f, 170f, -5f));
                        var arm = TabletHandlingPose.armMatrix(480, 320, right, slim, .4f, .2f, progress);
                        TabletArmRenderer.draw(graphics, player, right, 15728880, arm);
                        var wrist = renderer.pose.transformPosition(new Vector3f(endOfHand));
                        require(Math.hypot(wrist.x - rail.x, wrist.y - rail.y) < .01,
                                "Turn moved the rendered wrist off the rail: " + wrist + " vs " + rail);
                        require(Math.abs(wrist.z - rail.z + 260f) < .01,
                                "Depth behind glass must remain constant during turns");
                        pose.popPose();
                    }
                }
            }
        }
        System.out.println("PASS: actual native hand matrix keeps the wrist on the case during rapid yaw and pitch reversals, swing, lift and return");
    }

    public static void main(String[] args) {
        var g = new GuiGraphics();
        var player = new LocalPlayer();
        var dispatch = Minecraft.getInstance().getEntityRenderDispatcher();
        g.pose().translate(7, 9, 20);
        var base = new Matrix4f(g.pose().last().pose());
        var transform = new Matrix4f().translation(81, 62, 11).rotateX(.36f).scale(34);
        var expectedPose = new Matrix4f(base).translate(0, 0, -260).mul(transform);
        var wide = new PlayerRenderer();
        var slim = new PlayerRenderer();
        for (var renderer : new PlayerRenderer[]{wide, slim}) {
            dispatch.renderer = renderer;
            for (boolean right : new boolean[]{false, true}) {
                int draws = renderer.draws, flushes = g.flushes;
                TabletArmRenderer.draw(g, player, right, 0xb000d0, transform);
                require(renderer.draws == draws + 1 && renderer.right == right && renderer.player == player,
                        "A single native dispatch for the selected hand and actual player");
                require(renderer.light == 0xb000d0 && renderer.buffer == g.bufferSource(), "Inherited light and buffers");
                require(renderer.pose.equals(expectedPose, 1e-5f), "GUI layer and supplied matrix");
                require(g.flushes == flushes + 2 && g.pose().last().pose().equals(base), "Paint order and balanced pose");
            }
        }
        reversingTurns(player, wide);
        int flushes = g.flushes;
        player.invisible = true;
        TabletArmRenderer.draw(g, player, true, 0, transform);
        player.invisible = false;
        player.spectator = true;
        TabletArmRenderer.draw(g, player, true, 0, transform);
        player.spectator = false;
        dispatch.renderer = new EntityRenderer<>();
        TabletArmRenderer.draw(g, player, false, 0, transform);
        TabletArmRenderer.draw(g, null, true, 0, transform);
        require(g.flushes == flushes, "Invisible, spectator, absent player or incompatible renderer drew nothing");
        dispatch.renderer = slim;
        slim.fail = true;
        try {
            TabletArmRenderer.draw(g, player, true, 0, transform);
            throw new AssertionError("Expected simulated renderer failure");
        } catch (IllegalStateException expected) {
            require(g.flushes == flushes + 2 && g.pose().last().pose().equals(base), "Failed draw restores the GUI pose");
        }
        System.out.println("PASS: native-arm adapter dispatches left/right to the selected renderer, preserves pose, light and buffers, and skips invisible or unsupported players");
    }
}
