package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import dev.anya.storage.BasePermissions;
import dev.anya.storage.Content;
import dev.anya.storage.ExpansionRules;
import dev.anya.storage.Safety;
import java.io.IOException;
import java.util.ArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;

/** Private directions and a read-only status display for the four fixed chest pads. */
final class ChestPadGuide {
    private static final String PLACE = "/anya pocket build place";
    private static final String MARK = "/anya pocket build plan add";
    private static final String APPLY = "/anya pocket build plan apply";

    static BlockPos nearestFloor(PocketLedger.Pocket room, BlockPos at) {
        return PocketRoomLayout.pads(room).stream()
                .map(BlockPos::below)
                .min(java.util.Comparator.comparingDouble(at::distSqr))
                .orElseThrow();
    }

    static void showPlan(ServerPlayer player) {
        var panel = Component.literal("[Anya Storage] Chest pads (+54 slots each)\n")
                .append(Component.literal("One plain Labelled Storage Chest per pad, spent on Apply.")
                        .withStyle(ChatFormatting.GRAY));
        try {
            panel.append(planStatus(player));
        } catch (IOException denied) {
            panel.append(Component.literal("\nPad status unavailable. " + denied.getMessage()).withStyle(ChatFormatting.RED));
        } catch (RuntimeException unavailable) {
            // A claim hook or an inconsistent chunk must never yield a partial occupancy list.
            panel.append(Component.literal("\nThe room layout could not be verified.").withStyle(ChatFormatting.RED));
        }
        player.sendSystemMessage(panel);
    }

    private static Component planStatus(ServerPlayer player) throws IOException {
        var server = player.getServer();
        if (server == null || !server.isSameThread()) throw new IOException("Open the plan again on the server.");
        if (!RoomEditor.enabled()) throw new IOException("Room editing is disabled.");
        var lease = PocketService.lease(player.getUUID());
        if (lease == null || lease.kind() != PocketLeases.Kind.VISIT) throw new IOException("Visit your pocket room first.");
        var room = PocketService.loadedPocket(server, lease.pocket());
        if (room == null || room.stage() != PocketLedger.Stage.READY || lease.epoch() != room.epoch()
                || RoomEditor.quarantined(room.id())) throw new IOException("This room's layout is unavailable.");
        if (player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) throw new IOException("Adventure mode cannot build.");
        var access = RoomEditor.Access.capture(player, room);
        if (!access.hub().permits(player, BasePermissions.BUILD))
            throw new IOException("You need Pocket Build permission to use these pads.");
        if (!access.hub().permits(player, BasePermissions.LEGACY))
            throw new IOException("Chest pads also need View, Deposit and Withdraw permissions.");
        int rights = RoomEditor.CHEST_EDIT_RIGHTS;
        String denied = PocketService.authorize(player, room, PocketLeases.Kind.VISIT, false, rights);
        if (!denied.isEmpty()) throw new IOException(denied);
        if (!access.current(player, rights)) throw new IOException("Your visit or permissions changed.");

        // A protected starter or pad must not leak occupancy through the room-wide budget.
        var blocks = new LoadedPocketBlocks(access.level());
        for (BlockPos pos : PocketRoomLayout.positions(room)) {
            if (blocks.chunk(pos.getX() >> 4, pos.getZ() >> 4) == null)
                throw new IOException("A chest position is outside the loaded room.");
            if (!Safety.locationReason(player, access.level(), pos).isEmpty())
                throw new IOException("A claim prevents checking one or more chest positions.");
        }
        var pads = PocketRoomLayout.pads(room);
        var blocked = new ArrayList<String>(pads.size());
        var crate = Content.CRATE.get().defaultBlockState();
        for (BlockPos pad : pads) blocked.add(Safety.buildReason(player, access.level(), pad, crate, false));
        if (!access.current(player, rights)) throw new IOException("Your visit or permissions changed.");

        var registered = PocketRoomLayout.observe(new LoadedPocketBlocks(access.level()), room);
        boolean space = RoomEditor.canAddChest(registered.size(), ExpansionRules.get().pocketContainers);
        var status = Component.empty();
        boolean hasVacancy = false;
        for (int i = 0; i < pads.size(); i++) {
            BlockPos pad = pads.get(i);
            status.append(Component.literal("\nPad " + (i + 1) + " (" + pad.below().toShortString() + ") "));
            if (!blocked.get(i).isEmpty()) {
                status.append(Component.literal("Unavailable. " + blocked.get(i)).withStyle(ChatFormatting.RED));
            } else if (registered.stream().anyMatch(chest -> chest.getBlockPos().equals(pad))) {
                status.append(Component.literal("Occupied").withStyle(ChatFormatting.GRAY));
            } else if (!space) {
                status.append(Component.literal("Unavailable. Room chest limit reached.").withStyle(ChatFormatting.YELLOW));
            } else {
                hasVacancy = true;
                status.append(Component.literal("Vacant").withStyle(ChatFormatting.GREEN));
            }
        }
        if (hasVacancy) {
            status.append(Component.literal("\nHold a plain chest and aim at the top of a vacant pad. "))
                    .append(suggest("Mark pad", MARK))
                    .append(Component.literal(" then "))
                    .append(suggest("Apply plan", APPLY))
                    .append(Component.literal(". Marking a pad is free."));
        }
        return status;
    }

    private static Component suggest(String label, String command) {
        return Component.literal(label).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)));
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
                .append(suggest(PLACE, PLACE))
                .append(" and press Enter to place it."));
    }

    private ChestPadGuide() {}
}
