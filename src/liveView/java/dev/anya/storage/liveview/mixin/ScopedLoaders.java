package dev.anya.storage.liveview.mixin;
import dev.anya.storage.liveview.LiveViews;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.chunk_loading.*;
import java.util.*;
import java.util.function.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
/** Preserve all non-pocket CE paths; replace only protected destination footprints. */
@Mixin(value=ChunkVisibility.class,remap=false)
public abstract class ScopedLoaders {
 @Redirect(method="foreachBaseChunkLoaders",at=@At(value="INVOKE",target="Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"),require=1)
 private static void filter(Consumer<ChunkLoader> sink,Object raw){var loader=(ChunkLoader)raw;if(LiveViews.ENABLED&&LiveViews.protectedDimension(loader.dimension()))LiveViews.autoLoadersSuppressed++;else sink.accept(loader);}
 @Inject(method="foreachBaseChunkLoaders",at=@At("RETURN")) private static void append(ServerPlayer p,Consumer<ChunkLoader> sink,CallbackInfo ci){LiveViews.appendLoaders(p,sink);}
 @Inject(method="getNearbyPortals",at=@At("HEAD"),cancellable=true)
 private static void noNested(ServerLevel world,Vec3 pos,Predicate<Portal> predicate,int radius,int globalRadius,CallbackInfoReturnable<List<Portal>> ci){if(LiveViews.ENABLED&&LiveViews.protectedDimension(world.dimension())){LiveViews.nestedQueriesSuppressed++;ci.setReturnValue(List.of());}}
}
