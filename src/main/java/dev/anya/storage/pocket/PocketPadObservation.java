package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import dev.anya.storage.BasePermissions;
import dev.anya.storage.Content;
import dev.anya.storage.Network;
import dev.anya.storage.Safety;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.Transactions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * One focus per connection, at most eight in total. Every server tick rechecks authorization;
 * an authorized viewer receives at most one mask per ten ticks, and a revocation clears immediately.
 */
@EventBusSubscriber(modid = AnyaStorage.ID)
public final class PocketPadObservation {
    private static final int MAX_VIEWERS = 8;
    private static final int RENEWAL_TIMEOUT = 25;
    private static final int HEARTBEAT = 10;
    private static final int MAX_SESSION = 1200;
    private static final Map<ServerPlayer, Focus> viewers = new IdentityHashMap<>();
    // A renewal already in transit cannot reopen a revoked grant on the same connection.
    private static final Map<ServerPlayer, Long> retiredTokens = new IdentityHashMap<>();

    private static final class Focus {
        final BlockPos pos;
        final long token;
        final ServerLevel home;
        final long started;
        final Connection socket;
        long renewed, sent;
        boolean published;
        PocketLedger.Pocket pocket;
        PocketContent.EntranceEntity entrance;
        StorageEntity hub;
        long coordinates;
        final UUID[] originalChests = new UUID[4];

        Focus(ServerPlayer player, BlockPos pos, long token, long now) {
            this.pos = pos.immutable();
            this.token = token;
            home = player.serverLevel();
            started = renewed = now;
            socket = player.connection.getConnection();
        }
    }

    /** A packet requests attention only. The bounded server tick performs every private read. */
    public static void focus(ServerPlayer player, BlockPos pos, long token) {
        var server = player.getServer();
        if (server == null || !server.isSameThread() || token <= 0 || !PocketService.enabled()
                || !player.isAlive() || player.isSpectator() || PocketService.inPocket(player.level())
                || player.distanceToSqr(Vec3.atCenterOf(pos)) > 100
                || server.getPlayerList().getPlayer(player.getUUID()) != player) return;
        long now = server.overworld().getGameTime();
        Focus old = viewers.get(player);
        if (old != null && old.socket != player.connection.getConnection()) {
            viewers.remove(player);
            retiredTokens.remove(player);
            old = null;
        }
        if (token <= retiredTokens.getOrDefault(player, 0L) || old != null && token < old.token) return;
        if (old != null && old.token == token) {
            if (old.home == player.level() && old.pos.equals(pos)) old.renewed = now;
            return;
        }
        if (old != null) clear(player, old);
        if (viewers.size() < MAX_VIEWERS) viewers.put(player, new Focus(player, pos, token, now));
    }

    public static void unfocus(ServerPlayer player, BlockPos pos, long token) {
        if (player.getServer() == null || !player.getServer().isSameThread()) return;
        Focus current = viewers.get(player);
        if (current != null && current.token == token && current.pos.equals(pos)) clear(player, current);
    }

    private static void clear(ServerPlayer player, Focus focus) {
        if (viewers.remove(player, focus) && focus.published) {
            retiredTokens.merge(player, focus.token, Math::max);
            if (player.connection.getConnection() == focus.socket && focus.socket.isConnected())
                PocketPadPackets.send(player, focus.pos, focus.token, -1);
        }
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (viewers.isEmpty()) return;
        var server = event.getServer();
        long now = server.overworld().getGameTime();
        for (var player : new ArrayList<>(viewers.keySet())) {
            Focus focus = viewers.get(player);
            if (focus == null) continue;
            if (server.getPlayerList().getPlayer(player.getUUID()) != player || player.connection.getConnection() != focus.socket) {
                viewers.remove(player);
                retiredTokens.remove(player);
                continue;
            }
            int value = -1;
            if (now - focus.renewed < RENEWAL_TIMEOUT && now - focus.started < MAX_SESSION
                    && player.serverLevel() == focus.home && player.isAlive() && !player.isRemoved()
                    && !player.isSpectator() && PocketService.enabled()) {
                try {
                    value = sample(player, focus);
                } catch (RuntimeException unavailable) {
                    // An incompatible claim hook, chunk future or modded outline must fail closed.
                    value = -1;
                }
            }
            if (viewers.get(player) != focus) continue;
            if (value < 0) {
                clear(player, focus);
            } else if (!focus.published || now - focus.sent >= HEARTBEAT) {
                focus.published = true;
                focus.sent = now;
                PocketPadPackets.send(player, focus.pos, focus.token, value,focus.coordinates);
            }
        }
    }

    private static int sample(ServerPlayer player, Focus focus) {
        var blocks = new LoadedPocketBlocks(focus.home);
        if (!blocks.getBlockState(focus.pos).is(PocketContent.ENTRANCE.get())
                || !(blocks.getBlockEntity(focus.pos) instanceof PocketContent.EntranceEntity entrance)
                || entrance.isRemoved() || entrance.getLevel() != focus.home
                || !focus.pos.equals(entrance.getBlockPos()) || entrance.pocket == null) return -1;
        var pocket = PocketService.loadedPocket(player.getServer(), entrance.pocket);
        if (pocket == null || pocket.stage() != PocketLedger.Stage.READY
                || RoomEditor.quarantined(pocket.id())
                || !pocket.anchor().dimension().equals(focus.home.dimension().location().toString())
                || pocket.anchor().entrance() != focus.pos.asLong() || !pocket.owner().equals(entrance.owner)) return -1;
        BlockPos hubPos = BlockPos.of(pocket.anchor().hub());
        if (!blocks.getBlockState(hubPos).is(Content.HUB.get())
                || !(blocks.getBlockEntity(hubPos) instanceof StorageEntity hub) || hub.isRemoved()
                || hub.getLevel() != focus.home || !hubPos.equals(hub.getBlockPos())
                || hub.pocketId != null || !pocket.owner().equals(hub.owner)
                || !pocket.anchor().hubId().equals(hub.existingStorageIdentity()) || Transactions.quarantined(hub)
                || !Network.inRange(player, hub) || !hub.permits(player, BasePermissions.VIEW)) return -1;
        if (focus.pocket != null && (!focus.pocket.equals(pocket) || focus.entrance != entrance || focus.hub != hub)) return -1;

        Vec3 eye = player.getEyePosition();
        double reach = Math.min(8, player.blockInteractionRange());
        var hit = blocks.clip(new ClipContext(eye, eye.add(player.getViewVector(1).scale(reach)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (!blocks.complete() || !EntranceFaceHit.accepts(hit, false)
                || !hit.getBlockPos().equals(focus.pos)) return -1;

        long policy = hub.profileRevision;
        if (!PocketService.homeAccess(player, pocket, hub, BasePermissions.VIEW).isEmpty()) return -1;
        var room = PocketService.dimension(player.getServer());
        if (room == null) return -1;
        if (!new LoadedPocketBlocks(room).fullPlot(pocket)) return -1;
        java.util.List<BlockPos> positions;
        try{positions=PocketRoomLayout.locations(new LoadedPocketBlocks(room),pocket);}
        catch(java.io.IOException unavailable){return -1;}
        // An empty pad is private too. Supply the visitor and actual destination dimension to each claim hook.
        for (BlockPos pad : positions) if (!Safety.locationReason(player, room, pad).isEmpty()) return -1;
        // Addon hooks ran above. Discard earlier world references and recheck before reading the layout.
        var roomBlocks = new LoadedPocketBlocks(room);
        if (!roomBlocks.fullPlot(pocket) || !validHome(player, focus, pocket, hub, entrance, hubPos, policy)) return -1;
        // A recovery quarantine on any sampled chest column also closes the exterior preview.
        if (!StillRepairGuard.permits(pocket.id(), positions)) return -1;
        try{if(!positions.equals(PocketRoomLayout.locations(roomBlocks,pocket)))return -1;}
        catch(java.io.IOException unavailable){return -1;}
        int mask = 0;
        long coordinates=0;
        var oldPads=PocketRoomLayout.pads(pocket);
        int reserved=0;
        for(var pos:positions){int slot=oldPads.indexOf(pos);if(slot>=0)reserved|=1<<slot;}
        var identities = new HashSet<UUID>();
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            var state = roomBlocks.getBlockState(pos);
            var entity = roomBlocks.getBlockEntity(pos);
            if (i >= 4 && state.isAir() && entity == null) continue;
            if (!state.is(Content.CRATE.get()) || !(entity instanceof StorageEntity chest) || !chest.getBlockState().is(Content.CRATE.get())
                    || chest.isRemoved() || chest.getLevel() != room || !pos.equals(chest.getBlockPos())
                    || !pocket.id().equals(chest.pocketId) || !pocket.owner().equals(chest.owner)
                    || chest.getContainerSize() != 54 || Transactions.quarantined(chest)) return -1;
            UUID id = chest.existingStorageIdentity();
            if (id == null || !identities.add(id) || id.equals(pocket.anchor().hubId())) return -1;
            if (i < 4) {
                if (focus.pocket != null && !id.equals(focus.originalChests[i])) return -1;
                focus.originalChests[i] = id;
            }
            int bit=i;
            if(i>=4){
                int slot=oldPads.indexOf(pos);
                if(slot<0){slot=Integer.numberOfTrailingZeros(~reserved);reserved|=1<<slot;}
                bit=4+slot;
                coordinates|=(long)((pos.getX()-pocket.x())|((pos.getZ()-pocket.z())<<5))<<(10*slot);
            }
            mask |= 1 << bit;
        }
        if (!roomBlocks.complete() || !validHome(player, focus, pocket, hub, entrance, hubPos, policy)) return -1;
        focus.pocket = pocket;
        focus.entrance = entrance;
        focus.hub = hub;
        focus.coordinates=coordinates;
        return mask;
    }

    private static boolean validHome(ServerPlayer player, Focus focus, PocketLedger.Pocket pocket, StorageEntity hub,
                                     PocketContent.EntranceEntity entrance, BlockPos hubPos, long policy) {
        var currentHome = new LoadedPocketBlocks(focus.home);
        return viewers.get(player) == focus && player.serverLevel() == focus.home && player.isAlive() && !player.isSpectator()
                && player.connection.getConnection() == focus.socket && focus.socket.isConnected()
                && currentHome.getBlockEntity(hubPos) == hub && currentHome.getBlockEntity(focus.pos) == entrance
                && !hub.isRemoved() && !entrance.isRemoved() && !Transactions.quarantined(hub)
                && PocketService.enabled() && !RoomEditor.quarantined(pocket.id()) && hub.profileRevision == policy
                && pocket.id().equals(entrance.pocket) && pocket.owner().equals(entrance.owner)
                && pocket.owner().equals(hub.owner) && hub.permits(player, BasePermissions.VIEW) && Network.inRange(player, hub)
                && PocketService.loadedPocket(player.getServer(), entrance.pocket) == pocket;
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            viewers.remove(player);
            retiredTokens.remove(player);
        }
    }

    @SubscribeEvent public static void changeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Focus focus = viewers.get(player);
            if (focus != null) clear(player, focus);
        }
    }

    @SubscribeEvent public static void stop(ServerStoppingEvent event) {
        viewers.clear();
        retiredTokens.clear();
    }

    private PocketPadObservation() {}
}
