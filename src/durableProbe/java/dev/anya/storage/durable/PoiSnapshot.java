package dev.anya.storage.durable;
import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;
/** Pinned test adapter. Main-thread, strict encoding of already loaded POI sections; no dirty flag clearing. */
public final class PoiSnapshot {
 public static void verifyFixedPalette(net.minecraft.server.MinecraftServer server)throws IOException{
  Preparation.main(server);
  // Only the exit lodestone changes POI data in this fixed-room prototype. Other registered
  // POI blocks would require a tracked touched-column set, not erasing unknown empty columns.
  for(var block:List.of(net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE,net.minecraft.world.level.block.Blocks.POLISHED_BLACKSTONE,net.minecraft.world.level.block.Blocks.BLACK_CONCRETE,net.minecraft.world.level.block.Blocks.LIGHT_BLUE_STAINED_GLASS,net.minecraft.world.level.block.Blocks.BROWN_STAINED_GLASS,net.minecraft.world.level.block.Blocks.PURPLE_STAINED_GLASS,net.minecraft.world.level.block.Blocks.BARRIER,net.minecraft.world.level.block.Blocks.SEA_LANTERN,dev.anya.storage.Content.CRATE.get()))
   for(var state:block.getStateDefinition().getPossibleStates())if(net.minecraft.world.entity.ai.village.poi.PoiTypes.hasPoi(state))throw new IOException("Unsupported additional POI-producing room block; completion scope refused");
  if(!net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(net.minecraft.world.level.block.Blocks.LODESTONE.defaultBlockState()).map(h->h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.LODESTONE)).orElse(false))throw new IOException("Pinned lodestone POI registration differs");
 }
 private static final Field STORAGE=field("storage"),CODEC=field("codec"),REGION=field("simpleRegionStorage");
 private static Field field(String name){try{Field f=SectionStorage.class.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 public static SimpleRegionStorage region(ServerLevel level){Preparation.main(level.getServer());try{return (SimpleRegionStorage)REGION.get(level.getPoiManager());}catch(Exception e){throw new IllegalStateException(e);}}
 @SuppressWarnings("unchecked") public static CompoundTag capture(ServerLevel level,ChunkPos pos)throws IOException{
  Preparation.main(level.getServer());
  try{
   var manager=level.getPoiManager();var cache=(Map<Long,Optional<Object>>)STORAGE.get(manager);
   // An absent cache entry is not proof of an empty POI section. Never trigger a blocking getOrLoad.
   for(int y=level.getMinSection();y<level.getMaxSection();y++)if(cache.get(SectionPos.asLong(pos.x,y,pos.z))==null)return null;
   var codec=((Function<Runnable,Codec<Object>>)CODEC.get(manager)).apply(()->{throw new IllegalStateException("Unexpected POI mutation during encode");});
   var ops=level.registryAccess().createSerializationContext(NbtOps.INSTANCE);CompoundTag sections=new CompoundTag();
   for(int y=level.getMinSection();y<level.getMaxSection();y++){
    var value=cache.get(SectionPos.asLong(pos.x,y,pos.z));if(value.isEmpty())continue;
    var encoded=codec.encodeStart(ops,value.get());
    if(encoded.error().isPresent())throw new IOException("POI encoding incomplete: "+encoded.error().get().message());
    sections.put(Integer.toString(y),encoded.result().orElseThrow(()->new IOException("POI encode returned no data")));
   }
   CompoundTag out=new CompoundTag();out.put("Sections",sections);out.putInt("DataVersion",SharedConstants.getCurrentVersion().getDataVersion().getVersion());
   if(out.sizeInBytes()>2*1024*1024)throw new IOException("POI snapshot exceeds bounded size");return out;
  }catch(IllegalAccessException e){throw new IOException("Pinned auxiliary storage API unavailable",e);}
 }
 private PoiSnapshot(){}
}
