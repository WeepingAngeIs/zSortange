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
    public static final int PUBLIC = 0, CYAN = 1, PURPLE = 2;

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
    public record Update(BlockPos pos, long token, int mask,long coordinates) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(id("chest_floor_layout"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = StreamCodec.ofMember(
                Update::write, buf -> {
                    var pos=buf.readBlockPos();long token=buf.readLong();
                    return buf.readBoolean()?new Update(pos,token,buf.readUnsignedByte(),buf.readVarLong()):new Update(pos,token,-1);
                });

        public Update(BlockPos pos,long token,int mask){this(pos,token,mask,0);}
        public Update {
            if (mask < -1 || mask > 255) throw new IllegalArgumentException("Invalid pocket pad mask");
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(pos);
            buf.writeLong(token);
            buf.writeBoolean(mask >= 0);
            if (mask >= 0){buf.writeByte(mask);buf.writeVarLong(coordinates);}
        }

        @Override public Type<Update> type() { return TYPE; }
    }

    /** Historical fixture shape. No handler is registered for this payload. */
    @Deprecated
    public record StillFocus(BlockPos pos, long token, boolean watching) implements CustomPacketPayload {
        public static final Type<StillFocus> TYPE = new Type<>(id("still_focus"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StillFocus> CODEC = StreamCodec.ofMember(
                StillFocus::write, buf -> new StillFocus(buf.readBlockPos(), buf.readLong(), buf.readBoolean()));

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(pos);
            buf.writeLong(token);
            buf.writeBoolean(watching);
        }

        @Override public Type<StillFocus> type() { return TYPE; }
    }

    /** Historical fixture shape. The playable mod never sends or accepts this payload. */
    @Deprecated
    public record StillUpdate(BlockPos pos, long token, long sequence, int material) implements CustomPacketPayload {
        public static final Type<StillUpdate> TYPE = new Type<>(id("still_frame"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StillUpdate> CODEC = StreamCodec.ofMember(
                StillUpdate::write, buf -> new StillUpdate(buf.readBlockPos(), buf.readLong(), buf.readLong(), buf.readUnsignedByte()));

        public StillUpdate {
            if (token <= 0 || sequence <= 0 || material < PUBLIC || material > PURPLE)
                throw new IllegalArgumentException("Invalid still-frame update");
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(pos);
            buf.writeLong(token);
            buf.writeLong(sequence);
            buf.writeByte(material);
        }

        @Override public Type<StillUpdate> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        RoomCover.register(event);
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
        send(player,pos,token,mask,0);
    }
    static void send(ServerPlayer player,BlockPos pos,long token,int mask,long coordinates){
        if (player.connection.hasChannel(Update.TYPE)) {
            PacketDistributor.sendToPlayer(player, new Update(pos, token, mask, coordinates));
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, path);
    }

    private PocketPadPackets() {}
}
