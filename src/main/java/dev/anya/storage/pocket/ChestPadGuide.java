package dev.anya.storage.pocket;

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
            ?(RoomEditor.active(player)?"Hold a plain Labelled Storage Chest and right-click clear room floor.":"Turn on Edit Mode in your linked tablet, then right-click clear floor with a plain Labelled Storage Chest.")
            :denial),true);
    }
    private ChestPadGuide(){}
}
