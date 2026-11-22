package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

@EventBusSubscriber(modid = AnyaStorage.ID, value = Dist.CLIENT)
public final class CameraPlayerAppearance {
    private static ClientPacketListener connection;
    private static CameraAppearanceHistory history = new CameraAppearanceHistory();

    @SubscribeEvent public static void joined(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof AbstractClientPlayer player) {
            var current = Minecraft.getInstance().getConnection();
            if (current == null) return;
            if (connection != current) {
                history = new CameraAppearanceHistory();
                connection = current;
            }
            history.join(player);
        }
    }

    @SubscribeEvent public static void left(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractClientPlayer player) history.leave(player);
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        connection = null;
        history = new CameraAppearanceHistory();
    }

    static PlayerInfo resolve(int handle) {
        var mc = Minecraft.getInstance();
        var network = mc.getConnection();
        if (handle == CameraAppearanceHistory.UNKNOWN || mc.player == null || network == null) return null;
        boolean self = handle == CameraAppearanceHistory.SELF;
        if (!self && connection != network) return null;
        var profile = self ? mc.player.getUUID() : history.profile(handle);
        var info = profile == null ? null : network.getPlayerInfo(profile);
        return info != null && (self || network.getListedOnlinePlayers().contains(info)) ? info : null;
    }
}
