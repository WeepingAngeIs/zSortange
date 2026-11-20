package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;

/** Shows the fixed plan of the room to its builder. No pad contents or occupancy are sampled. */
final class ChestPadGuide {
    private static final String PLACE = "/anya pocket build place";

    static BlockPos nearestFloor(PocketLedger.Pocket room, BlockPos at) {
        return PocketRoomLayout.pads(room).stream()
                .map(BlockPos::below)
                .min(java.util.Comparator.comparingDouble(at::distSqr))
                .orElseThrow();
    }

    static void show(ServerPlayer player) {
        var server = player.getServer();
        if (server == null || !server.isSameThread()) return;
        var lease = PocketService.lease(player.getUUID());
        var room = lease == null ? null : PocketService.loadedPocket(server, lease.pocket());
        if (!RoomEditor.enabled() || room == null || room.stage() != PocketLedger.Stage.READY
                || lease.kind() != PocketLeases.Kind.VISIT || lease.epoch() != room.epoch()
                || RoomEditor.quarantined(room.id()) || player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE
                || !PocketService.authorize(player, room, PocketLeases.Kind.VISIT, false,
                        RoomEditor.CHEST_EDIT_RIGHTS).isEmpty()) {
            AnyaStorage.tell(player, "Pad directions need an active room visit with editing enabled and Build, View, Deposit and Withdraw access.");
            return;
        }

        var pads = PocketRoomLayout.pads(room);
        // The same four markers are sent only to this player, whether or not a chest occupies a pad.
        for (var pad : pads) {
            player.serverLevel().sendParticles(player, ParticleTypes.END_ROD, false,
                    pad.getX() + 0.5, pad.getY() + 0.08, pad.getZ() + 0.5, 7, 0.2, 0.04, 0.2, 0.002);
        }

        BlockPos floor = nearestFloor(room, player.blockPosition());
        String message = "The sparks mark designated chest spots. Nearest designated floor: "
                + floor.toShortString() + ". Hold a plain Labelled Storage Chest and aim at the top of an empty spot.";
        try {
            BlockHitResult hit = RoomEditor.looking(player);
            if (hit.getDirection() == Direction.UP && pads.contains(hit.getBlockPos().above())) {
                message = "You're aiming at a designated chest pad. Keep aiming here with one plain Labelled Storage Chest in your main hand.";
            }
        } catch (IllegalArgumentException ignored) {
            // Directions are also available when the builder is looking into open air.
        }
        player.sendSystemMessage(Component.literal("[Anya Storage] " + message + " Open chat, then select ")
                .append(Component.literal(PLACE).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, PLACE))))
                .append(" and press Enter to place it."));
    }

    private ChestPadGuide() {}
}
