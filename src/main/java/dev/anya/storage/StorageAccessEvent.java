package dev.anya.storage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
/** Addon hook: cancel to deny this remote inventory interaction. Cancellation is never configurable away. */
public final class StorageAccessEvent extends Event implements ICancellableEvent {
    public final ServerPlayer player;
    public final BlockPos position;
    public StorageAccessEvent(ServerPlayer player,BlockPos position) { this.player=player;this.position=position.immutable(); }
}
