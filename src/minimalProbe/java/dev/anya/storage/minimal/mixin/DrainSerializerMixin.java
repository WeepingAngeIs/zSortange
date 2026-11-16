package dev.anya.storage.minimal.mixin;
import dev.anya.storage.minimal.LeanMetrics;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ChunkSerializer.class)
public abstract class DrainSerializerMixin {
 @Inject(method="write",at=@At("HEAD")) private static void a(ServerLevel l,ChunkAccess chunk,CallbackInfoReturnable<CompoundTag> c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(l))LeanMetrics.begin(5);}
 @Inject(method="write",at=@At("RETURN")) private static void b(ServerLevel l,ChunkAccess chunk,CallbackInfoReturnable<CompoundTag> c){if(LeanMetrics.DIAGNOSTIC&&PocketService.inPocket(l))LeanMetrics.end(5);}
}
