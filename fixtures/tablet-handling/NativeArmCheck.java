import dev.anya.storage.appearance.TabletArmRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.joml.Matrix4f;

/** Runs production bytecode against a recording renderer in place of the GPU and live player. */
public final class NativeArmCheck {
    private static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
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
