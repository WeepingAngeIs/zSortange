package dev.anya.storage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
/** Addon hook: cancel to deny this remote inventory interaction. Cancellation is never configurable away. */
public final class StorageAccessEvent extends Event implements ICancellableEvent {
    public final ServerPlayer player;
    public final BlockPos position;
    public final net.minecraft.server.level.ServerLevel level;
    public StorageAccessEvent(ServerPlayer player,BlockPos position) { this(player,player.serverLevel(),position); }
    public StorageAccessEvent(ServerPlayer player,net.minecraft.server.level.ServerLevel level,BlockPos position){this.player=player;this.level=level;this.position=position.immutable();}
}
