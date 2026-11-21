package dev.anya.storage.pocket;

import java.io.IOException;
import dev.anya.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

final class ChestPadGuide {
    static BlockPos nearestFloor(PocketLedger.Pocket room, BlockPos at) {
        return PocketRoomLayout.pads(room).stream()
                .map(BlockPos::below)
                .min(java.util.Comparator.comparingDouble(at::distSqr))
                .orElseThrow();
    }

    static void showPlan(ServerPlayer player){show(player);}
    static void show(ServerPlayer player){
        String denial=RoomEditor.modeDenial(player);
        player.displayClientMessage(Component.literal(denial.isEmpty()
            ?"Turn on Edit Mode in your linked tablet. Hold a plain Labelled Storage Chest and right-click clear room floor."
            :denial),true);
    }
    private ChestPadGuide(){}
}
