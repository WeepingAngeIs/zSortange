package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Optional play channel for one ephemeral diagram, scoped to the requesting connection. */
public final class PocketPadPackets {
    public static BiConsumer<Connection, Update> receiver = (connection, update) -> {};

    public record Focus(BlockPos pos, long token, boolean watching) implements CustomPacketPayload {
        public static final Type<Focus> TYPE = new Type<>(id("pad_focus"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Focus> CODEC = StreamCodec.ofMember(
                Focus::write, buf -> new Focus(buf.readBlockPos(), buf.readLong(), buf.readBoolean()));

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(pos);
            buf.writeLong(token);
            buf.writeBoolean(watching);
        }

        @Override public Type<Focus> type() { return TYPE; }
    }

    /** A clear carries no pad byte. No pocket identity, contents, destination or player names are sent. */
    public record Update(BlockPos pos, long token, int mask) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(id("pad_layout"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = StreamCodec.ofMember(
                Update::write, buf -> new Update(buf.readBlockPos(), buf.readLong(),
                        buf.readBoolean() ? buf.readUnsignedByte() : -1));

        public Update {
            if (mask < -1 || mask > 255) throw new IllegalArgumentException("Invalid pocket pad mask");
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(pos);
            buf.writeLong(token);
            buf.writeBoolean(mask >= 0);
            if (mask >= 0) buf.writeByte(mask);
        }

        @Override public Type<Update> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("pad1").optional();
        // NeoForge play handlers run on the main thread by default.
        registrar.playToServer(Focus.TYPE, Focus.CODEC, (focus, context) -> {
            if (focus.token() <= 0 || !(context.player() instanceof ServerPlayer player)) return;
            if (player.connection.getConnection() != context.connection() || !player.connection.hasChannel(Update.TYPE)) return;
            if (focus.watching()) PocketPadObservation.focus(player, focus.pos(), focus.token());
            else PocketPadObservation.unfocus(player, focus.pos(), focus.token());
        });
        registrar.playToClient(Update.TYPE, Update.CODEC,
                (update, context) -> receiver.accept(context.connection(), update));
    }

    static void send(ServerPlayer player, BlockPos pos, long token, int mask) {
        if (player.connection.hasChannel(Update.TYPE)) {
            PacketDistributor.sendToPlayer(player, new Update(pos, token, mask));
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, path);
    }

    private PocketPadPackets() {}
}
