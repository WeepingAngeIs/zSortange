package dev.anya.storage.pocket;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.anya.storage.AnyaStorage;
import dev.anya.storage.BasePermissions;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class RoomPlans {
    static final int LIMIT = 8;
    private static final int LIFETIME = 20 * 60 * 5;
    private static final Map<UUID,Draft> drafts = new HashMap<>();
    private static final String HELP = "Hold a plain furnishing, aim at a surface and use /anya pocket build plan add. Walk through the outlines. Use undo, cancel or apply. Stand within reach of all marked spots to apply.";

    static final class Draft {
        final LinkedHashMap<BlockPos,RoomEditor.Mark> marks = new LinkedHashMap<>();
        final RoomEditor.Access access;
        long expires;

        Draft(ServerPlayer player, RoomEditor.Access access) {
            this.access = access;
            expires = player.serverLevel().getGameTime() + LIFETIME;
        }

        boolean current(ServerPlayer player, PocketLedger.Pocket actual) {
            return drafts.get(player.getUUID()) == this && actual == access.room() && player.serverLevel().getGameTime() < expires
                    && access.current(player,BasePermissions.BUILD)
                    && PocketService.authorize(player,actual,PocketLeases.Kind.VISIT,false,BasePermissions.BUILD).isEmpty()
                    && drafts.get(player.getUUID()) == this && access.current(player,BasePermissions.BUILD);
        }
    }

    static LiteralArgumentBuilder<CommandSourceStack> commands() {
        var root = Commands.literal("plan").executes(c -> { AnyaStorage.tell(c.getSource().getPlayerOrException(), HELP); return 1; });
        for (String action : new String[]{"add", "undo", "cancel", "apply"})
            root.then(Commands.literal(action).executes(c -> run(c.getSource().getPlayerOrException(), action)));
        return root;
    }

    private static int run(ServerPlayer player, String action) {
        UUID id = player.getUUID();
        try {
            if (!player.connection.hasChannel(RoomPlanPacket.TYPE)) throw new IOException("Room outlines need the field-kit client.");
            if (action.equals("cancel")) {
                forget(player);
                AnyaStorage.tell(player,"Cleared your room plan.");
                return 1;
            }
            Draft draft = drafts.get(id);
            var lease = PocketService.lease(id);
            var room = lease == null ? null : PocketService.loadedPocket(player.getServer(),lease.pocket());
            if (draft != null && !draft.current(player,room)) {
                forget(player);
                throw new IOException("Your previous plan expired or its room access changed. Mark the spots again.");
            }
            if (action.equals("add")) {
                var edit = RoomEditor.prepare(player,RoomEditor.looking(player),false);
                if (draft == null) draft = new Draft(player,edit.access());
                if (!draft.access.equals(edit.access()) || !draft.access.current(player, BasePermissions.BUILD))
                    throw new IOException("The room changed. Mark this spot again.");
                var mark = edit.mark();
                if (draft.marks.size() >= LIMIT && !draft.marks.containsKey(mark.pos()))
                    throw new IOException("A plan holds eight spots. Apply it or undo a mark first.");
                draft.marks.remove(mark.pos());
                draft.marks.put(mark.pos(),mark);
                draft.expires = player.serverLevel().getGameTime() + LIFETIME;
                drafts.put(id,draft);
                AnyaStorage.tell(player,"Marked " + mark.state().getBlock().getName().getString() + " (" + draft.marks.size() + "/" + LIMIT + ").");
            } else {
                if (draft == null) throw new IOException("Your room plan is empty. " + HELP);
                if (action.equals("apply")) {
                    int count = draft.marks.size();
                    RoomEditor.apply(player,draft);
                    forget(player);
                    AnyaStorage.tell(player,"Placed all " + count + " planned blocks.");
                    return 1;
                }
                draft.marks.pollLastEntry();
                if (draft.marks.isEmpty()) forget(player);
                AnyaStorage.tell(player,"Removed your last outline.");
            }
            RoomPlanPacket.send(player,draft.marks.values());
            return 1;
        } catch (IOException | IllegalArgumentException denied) {
            AnyaStorage.tell(player,denied.getMessage());
            return 0;
        }
    }

    static void tick(MinecraftServer server) {
        if (drafts.isEmpty() || server.getTickCount() % 20 != 0) return;
        for (UUID id : java.util.List.copyOf(drafts.keySet())) {
            var player = server.getPlayerList().getPlayer(id);
            Draft draft = drafts.get(id);
            if (draft == null) continue;
            if (player == null) drafts.remove(id);
            else if (!draft.current(player,PocketService.loadedPocket(server,draft.access.room().id()))) forget(player);
        }
    }

    static void forget(ServerPlayer player) {
        if (drafts.remove(player.getUUID()) != null) RoomPlanPacket.send(player,java.util.List.of());
    }
    static void endVisit(MinecraftServer server, UUID id) {
        if (!drafts.containsKey(id)) return;
        var player = server == null ? null : server.getPlayerList().getPlayer(id);
        if (player == null) drafts.remove(id);
        else forget(player);
    }
    static void clear() { drafts.clear(); }
    private RoomPlans() {}
}
