package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** A short decorative visit sent privately to a physically present, authorized viewer. */
public record RoomHologram(BlockPos origin, int act) implements CustomPacketPayload {
    private static final RoomHologram CLEAR = new RoomHologram(BlockPos.ZERO, RoomVisitSchedule.HIDE);
    private static final Type<RoomHologram> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, "room_cupid"));
    private static final StreamCodec<RegistryFriendlyByteBuf, RoomHologram> CODEC = StreamCodec.ofMember(
            (cue, buf) -> { buf.writeBlockPos(cue.origin); buf.writeByte(cue.act); },
            buf -> new RoomHologram(buf.readBlockPos(), buf.readByte()));
    static BiConsumer<Connection,RoomHologram> receiver = (connection, cue) -> {};
    private static final RoomVisitSchedule visits = new RoomVisitSchedule(new Random());

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("field1").optional().playToClient(TYPE, CODEC,
                (cue, ctx) -> receiver.accept(ctx.connection(), cue));
    }

    // Called only after the current physical lease and room permissions were both renewed.
    static void visit(ServerPlayer player, PocketLedger.Pocket pocket, long tick) {
        if (player.connection == null || !player.connection.getConnection().isConnected() || !player.connection.hasChannel(TYPE)) return;
        int act = visits.advance(player.getUUID(), tick);
        if (act == RoomVisitSchedule.NONE) return;
        var cue = act == RoomVisitSchedule.HIDE ? CLEAR : new RoomHologram(new BlockPos(pocket.x(), 65, pocket.z()), act);
        PacketDistributor.sendToPlayer(player, cue);
    }

    static void end(MinecraftServer server, UUID id) {
        if (!visits.end(id) || server == null) return;
        var player = server.getPlayerList().getPlayer(id);
        if (player != null && player.connection.getConnection().isConnected() && player.connection.hasChannel(TYPE))
            PacketDistributor.sendToPlayer(player, CLEAR);
    }

    static void clear() { visits.clear(); }
    @Override public Type<RoomHologram> type() { return TYPE; }
}
