package dev.anya.storage.ticketexperiment.mixin;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Typed read-only bridge to the existing private immutable ticket key. */
@Mixin(targets="dev.anya.storage.pocket.PocketService$Ticket",remap=false)
public interface TicketKeyAccess {
    @Accessor("dimension") ResourceKey<Level> anya$dimension();
    @Accessor("chunk") ChunkPos anya$chunk();
    @Accessor("pocket") UUID anya$pocket();
}
