package probe;

import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Drives only the known full-pack startup prompt and normal local multiplayer connection. */
@EventBusSubscriber(modid = "anyabagreview", value = Dist.CLIENT)
public final class HistoryPackClient {
    private static final String LOOPBACK = "127.0.0.1:25565";
    private static boolean connected, declined;
    private static int readyTicks;

    @SubscribeEvent
    public static void afterTick(ClientTickEvent.Post event) throws Exception {
        if (connected || !"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))
                || !"pack-custody".equals(System.getenv("GITHUB_JOB"))) return;
        String phase = System.getenv("ANYA_BAG_PHASE");
        if (!"pack-storage".equals(phase) && !"pack-restart".equals(phase)) return;
        var game = Minecraft.getInstance();
        if (game.level != null || game.getConnection() != null || game.screen == null) return;
        if (!game.gameDirectory.toPath().toRealPath().equals(Path.of(System.getenv("RUNNER_TEMP"), "pack249-client-native").toRealPath()))
            throw new IllegalStateException("Wrong full-pack test directory");
        if (!declined && game.screen.getClass().getName().equals("xaero.lib.client.gui.GuiUpdateAll")) {
            for (var widget : game.screen.children()) {
                if (widget instanceof Button button && button.active && button.visible
                        && CommonComponents.GUI_NO.equals(button.getMessage())) {
                    declined = true;
                    button.onPress();
                    return;
                }
            }
        }
        if (game.screen instanceof TitleScreen title && game.getOverlay() == null && ++readyTicks >= 60) {
            connected = true;
            var data = new ServerData("Disposable recovery check", LOOPBACK, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(title, game, ServerAddress.parseString(LOOPBACK), data, false, null);
        }
    }

    private HistoryPackClient() {}
}
