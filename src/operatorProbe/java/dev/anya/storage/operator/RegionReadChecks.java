package dev.anya.storage.operator;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.repair.RepairService;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
/** Synthetic format fixtures outside the disposable world. Inspection itself is always READ only. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class RegionReadChecks {
 private static int assertions;
 private static void check(boolean v){if(!v)throw new IllegalStateException("Region read check "+assertions);assertions++;}
 private static byte[] frame(CompoundTag tag,int compression)throws IOException{
  byte[] data=RepairService.encode(tag),encoded=data;
  if(compression!=3){var out=new ByteArrayOutputStream();try(OutputStream z=compression==1?new GZIPOutputStream(out):new DeflaterOutputStream(out)){z.write(data);}encoded=out.toByteArray();}
  int count=(5+encoded.length+4095)/4096;var bytes=ByteBuffer.allocate(8192+5+encoded.length);bytes.putInt(0,(2<<8)|count);bytes.position(8192);bytes.putInt(encoded.length+1);bytes.put((byte)compression);bytes.put(encoded);return bytes.array();
 }
 private static void exercise(Path base,String name,byte[] data,CompoundTag wanted,boolean accept)throws Exception{
  Path d=base.resolve(name);Files.createDirectory(d);Path f=d.resolve("r.0.0.mca");Files.write(f,data,StandardOpenOption.CREATE_NEW);String before=JournalDisk.hash(Files.readAllBytes(f));boolean okay=false;
  try{var p=ReadOnlyRegion.inspect(d,0);okay=true;check(accept&&p.tag().equals(wanted));}catch(IOException expected){check(!accept);}
  check(okay==accept);check(before.equals(JournalDisk.hash(Files.readAllBytes(f))));try(var list=Files.list(d)){check(list.count()==1);}
 }
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void start(ServerStartedEvent e)throws Exception{
  if(!System.getProperty("anya.operatorProbe","").equals("seed"))return;
  if(!Files.isRegularFile(Path.of(".anya-durable-fixture")))throw new IOException("Disposable marker required");
  Path base=Path.of("operator-region-parser-fixtures");if(Files.exists(base))throw new IOException("Fresh format fixtures required");Files.createDirectory(base);
  var tag=new CompoundTag();tag.putInt("xPos",0);tag.putInt("zPos",0);tag.putString("proof","bounded native NBT");
  for(int type:new int[]{1,2,3}){byte[] b=frame(tag,type);check(b.length%4096!=0);exercise(base,"valid-unpadded-"+type,b,tag,true);exercise(base,"truncated-"+type,Arrays.copyOf(b,b.length-1),tag,false);}
  byte[] valid=frame(tag,2),padded=Arrays.copyOf(valid,((valid.length+4095)/4096)*4096);exercise(base,"valid-padded",padded,tag,true);
  byte[] invalid=valid.clone();ByteBuffer.wrap(invalid).putInt(0,(100<<8)|1);exercise(base,"outside-location",invalid,tag,false);
  invalid=valid.clone();ByteBuffer.wrap(invalid).putInt(8192,4096);exercise(base,"length-outside-allocation",invalid,tag,false);
  invalid=valid.clone();invalid[8196]=(byte)130;exercise(base,"external-refused",invalid,tag,false);
  invalid=valid.clone();invalid[8196]=4;exercise(base,"unknown-compression-refused",invalid,tag,false);
  var wrong=tag.copy();wrong.putInt("xPos",1);exercise(base,"wrong-coordinates",frame(wrong,2),tag,false);
  var huge=tag.copy();huge.putByteArray("large",new byte[JournalDisk.MAX_PAYLOAD+1]);var out=new ByteArrayOutputStream();try(var z=new DeflaterOutputStream(out)){var nbt=new ByteArrayOutputStream();net.minecraft.nbt.NbtIo.write(huge,new DataOutputStream(nbt));z.write(nbt.toByteArray());}
  byte[] compressed=out.toByteArray();var bomb=ByteBuffer.allocate(8192+5+compressed.length);bomb.putInt(0,(2<<8)|1);bomb.position(8192);bomb.putInt(compressed.length+1);bomb.put((byte)2);bomb.put(compressed);exercise(base,"expanded-bound",bomb.array(),tag,false);
  Trace.marker("REGION_READER_CHECKS","assertions="+assertions+" accepts complete unpadded records; rejects actual truncation, allocation overflow, unsupported compression, external files, coordinate mismatch and expanded bounds; filenames/content unchanged after read");
 }
 private RegionReadChecks(){}
}
