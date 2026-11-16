package dev.anya.storage.minimal;
import net.minecraft.server.level.ChunkHolder;
/** Read-only fixture/audit view. No new queue or persistence state. */
public interface OwnershipView {
 ChunkHolder anyaPending(long key);
 ChunkHolder anyaActive(long key);
 int anyaPendingCount();
 int anyaCallbackCount();
}
