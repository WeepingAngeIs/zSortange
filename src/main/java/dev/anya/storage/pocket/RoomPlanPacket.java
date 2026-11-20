package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** A private drawing of the builder's own marks. No block entity data or storage information. */
public record RoomPlanPacket(List<Outline> marks) implements CustomPacketPayload {
    public record Outline(BlockPos pos, int state) {}
    static final Type<RoomPlanPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"room_plan"));
    static final StreamCodec<RegistryFriendlyByteBuf, RoomPlanPacket> CODEC = StreamCodec.ofMember(RoomPlanPacket::write, RoomPlanPacket::read);
    static BiConsumer<Connection,RoomPlanPacket> receiver = (connection,packet) -> {};
    public RoomPlanPacket {
        marks = List.copyOf(marks);
        if (marks.size() > RoomPlans.LIMIT) throw new IllegalArgumentException("Too many room marks");
    }
    private static RoomPlanPacket read(RegistryFriendlyByteBuf buf) {
        int count = buf.readUnsignedByte();
        if (count > RoomPlans.LIMIT) throw new IllegalArgumentException("Too many room marks");
        var result = new ArrayList<Outline>(count);
        for (int i=0; i<count; i++) result.add(new Outline(buf.readBlockPos(),buf.readVarInt()));
        return new RoomPlanPacket(result);
    }
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeByte(marks.size());
        for (var mark : marks) {
            buf.writeBlockPos(mark.pos());
            buf.writeVarInt(mark.state());
        }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("field1").optional().playToClient(TYPE, CODEC,
                (packet,ctx) -> receiver.accept(ctx.connection(),packet));
    }
    static void send(ServerPlayer player, Collection<RoomEditor.Mark> marks) {
        if (player.connection.getConnection().isConnected() && player.connection.hasChannel(TYPE)) PacketDistributor.sendToPlayer(player,new RoomPlanPacket(
                marks.stream().map(m -> new Outline(m.pos(), Block.getId(m.state()))).toList()));
    }
    @Override public Type<RoomPlanPacket> type() { return TYPE; }
}
