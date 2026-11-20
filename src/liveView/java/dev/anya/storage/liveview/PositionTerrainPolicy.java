package dev.anya.storage.liveview;

import dev.anya.storage.BasePermissions;
import dev.anya.storage.pocket.PocketLeases;
import dev.anya.storage.pocket.PocketLedger;
import dev.anya.storage.pocket.PocketService;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** Decisions on decoded game packets. The connection boundary supplies a freshly verified physical visit. */
final class PositionTerrainPolicy {
    static String remoteTerrainReason(ServerPlayer player, PocketLedger.Pocket pocket, Predicate<UUID> quarantined) {
        if (pocket == null || pocket.stage() != PocketLedger.Stage.READY) return "Only an existing READY pocket can be previewed";
        String reason = PocketService.authorize(player, pocket, PocketLeases.Kind.REMOTE, false, BasePermissions.VIEW);
        if (!reason.isEmpty()) return reason;
        if (player.distanceToSqr(Vec3.atCenterOf(BlockPos.of(pocket.anchor().entrance()))) > 24 * 24) return "Preview distance exceeded";
        if (quarantined.test(pocket.id())) return "Persistence quarantine";
        // A position-specific veto cannot certify the contents of a complete terrain column.
        return "Remote terrain has no complete destination visibility check";
    }

    static PocketLedger.Pocket visitAtDelivery(MinecraftServer server, ServerPlayer player,
                                              Function<UUID, PocketLedger.Pocket> findPocket) {
        if (server == null || player == null || player.getServer() != server || !server.isSameThread()
                || !PocketService.inPocket(player.level())) return null;
        var lease = PocketService.lease(player.getUUID());
        if (lease == null || lease.kind() != PocketLeases.Kind.VISIT) return null;
        var pocket = findPocket.apply(lease.pocket());
        if (pocket == null || pocket.epoch() != lease.epoch()
                || !PocketService.authorize(player, pocket, lease.kind(), false, 0).isEmpty()) return null;
        return pocket;
    }

    static boolean covers(PocketLedger.Pocket visit, long column) {
        return Math.abs(ChunkPos.getX(column) - (visit.x() >> 4)) <= LiveViews.DATA_RADIUS
                && Math.abs(ChunkPos.getZ(column) - (visit.z() >> 4)) <= LiveViews.DATA_RADIUS;
    }

    private static boolean visitColumn(PocketLedger.Pocket visit, BlockPos pos) {
        return covers(visit, ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    @SuppressWarnings("unchecked")
    static ClientboundBundlePacket filterBundle(ClientboundBundlePacket bundle, Function<Packet<?>, Packet<?>> each) {
        var kept = new ArrayList<Packet<? super ClientGamePacketListener>>();
        for (var part : bundle.subPackets()) {
            Packet<?> result = each.apply(part);
            if (result != null) kept.add((Packet<? super ClientGamePacketListener>) result);
        }
        return kept.isEmpty() ? null : new ClientboundBundlePacket(kept);
    }

    static ClientboundRemoveEntitiesPacket removeDelivered(ClientboundRemoveEntitiesPacket removal, Set<Integer> sentEntities) {
        if (sentEntities == null || sentEntities.isEmpty()) return null;
        var ids = removal.getEntityIds().intStream().filter(sentEntities::remove).toArray();
        return ids.length == 0 ? null : new ClientboundRemoveEntitiesPacket(ids);
    }

    static Packet<?> filterData(ServerLevel room, PocketLedger.Pocket visit, Packet<?> packet, Set<Integer> sentEntities,
                                Function<UUID, PocketLedger.Pocket> findPocket) {
        if (visit == null || room == null || !room.dimension().equals(PocketService.DIMENSION)) return null;
        boolean allowed;
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            allowed = covers(visit, ChunkPos.asLong(chunk.getX(), chunk.getZ()))
                    && PocketChestPrivacy.publicChunk(room, chunk, findPocket);
        } else if (packet instanceof ClientboundLightUpdatePacket light) {
            allowed = covers(visit, ChunkPos.asLong(light.getX(), light.getZ()));
        } else if (packet instanceof ClientboundBlockUpdatePacket block) {
            allowed = visitColumn(visit, block.getPos());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            boolean[] seen = {false};
            boolean[] inside = {true};
            section.runUpdates((pos, state) -> {
                seen[0] = true;
                if (!visitColumn(visit, pos)) inside[0] = false;
            });
            allowed = seen[0] && inside[0];
        } else if (packet instanceof ClientboundBlockEntityDataPacket update) {
            allowed = visitColumn(visit, update.getPos()) && PocketChestPrivacy.publicUpdate(room, update, findPocket);
        } else if (packet instanceof ClientboundBlockEventPacket event) {
            allowed = PocketChestPrivacy.visitorAt(visit, event.getPos());
        } else if (packet instanceof ClientboundSetTimePacket || packet instanceof ClientboundGameEventPacket) {
            allowed = true;
        } else if (packet instanceof ClientboundAddEntityPacket spawn) {
            var entity = room.getEntity(spawn.getId());
            allowed = entity != null && entity.getUUID().equals(spawn.getUUID()) && entity.getType() == spawn.getType()
                    && PocketChestPrivacy.visitorAt(visit, entity.blockPosition())
                    && PocketChestPrivacy.visitorAt(visit, BlockPos.containing(spawn.getX(), spawn.getY(), spawn.getZ()));
            if (allowed) sentEntities.add(spawn.getId());
        } else {
            allowed = previouslyDeliveredEntity(room, visit, packet, sentEntities);
        }
        return allowed ? packet : null;
    }

    private static boolean previouslyDeliveredEntity(ServerLevel room, PocketLedger.Pocket visit, Packet<?> packet,
                                                     Set<Integer> sentEntities) {
        if (!packet.getClass().getSimpleName().contains("Entity")) return false;
        try {
            for (String name : new String[] {"getId", "getEntityId", "id"}) {
                Object result;
                try {
                    result = packet.getClass().getMethod(name).invoke(packet);
                } catch (NoSuchMethodException missing) {
                    continue;
                }
                if (!(result instanceof Integer id)) continue;
                if (!sentEntities.contains(id)) return false;
                var entity = room.getEntity(id);
                if (entity == null || !PocketChestPrivacy.visitorAt(visit, entity.blockPosition())) return false;
                if (packet instanceof ClientboundTeleportEntityPacket move) {
                    return PocketChestPrivacy.visitorAt(visit, BlockPos.containing(move.getX(), move.getY(), move.getZ()));
                }
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return false;
        }
        return false;
    }

    private PositionTerrainPolicy() {}
}
