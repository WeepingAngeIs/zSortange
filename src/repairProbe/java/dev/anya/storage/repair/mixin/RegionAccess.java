package dev.anya.storage.repair.mixin;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;import net.minecraft.world.level.ChunkPos;import net.minecraft.nbt.CompoundTag;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.gen.Invoker;import java.io.IOException;
@Mixin(RegionFileStorage.class) public interface RegionAccess { @Invoker("write") void anyaWrite(ChunkPos pos,CompoundTag tag)throws IOException; }
