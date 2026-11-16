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
