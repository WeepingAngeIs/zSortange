package dev.anya.storage;

import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** One private view-relative direction. The sender and receiver never exchange a mob identity or position. */
public record TabletThreatCue(int octant) implements CustomPacketPayload {
    static final Type<TabletThreatCue> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, "tablet_threat"));
    private static final StreamCodec<RegistryFriendlyByteBuf, TabletThreatCue> CODEC = StreamCodec.ofMember(
            (cue, buffer) -> buffer.writeByte(cue.octant()), buffer -> new TabletThreatCue(buffer.readUnsignedByte()));
    public static BiConsumer<Connection, TabletThreatCue> receiver = (connection, cue) -> {};

    public TabletThreatCue {
        if (octant < 0 || octant > 7) throw new IllegalArgumentException("Invalid threat direction");
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("threat1").optional().playToClient(TYPE, CODEC,
                (cue, ctx) -> ctx.enqueueWork(() -> receiver.accept(ctx.connection(), cue)));
    }

    @Override public Type<TabletThreatCue> type() { return TYPE; }
}
