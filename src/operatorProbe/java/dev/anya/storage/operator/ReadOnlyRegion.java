package dev.anya.storage.operator;
import dev.anya.storage.repair.RepairService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.zip.*;
/** No RegionFileStorage cache/open-or-create behavior: open one existing region with READ only. */
public final class ReadOnlyRegion {
 public record Primary(CompoundTag tag,String digest){}
 private static void read(FileChannel f,ByteBuffer b,long offset)throws IOException{while(b.hasRemaining()){int n=f.read(b,offset+b.position());if(n<0)throw new IOException("PRIMARY_TRUNCATED");}}
 public static Primary inspect(Path region,long key)throws IOException{
  var pos=new ChunkPos(key);Path file=region.resolve("r."+(pos.x>>5)+"."+(pos.z>>5)+".mca");JournalDisk.safe(file);
  if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("PRIMARY_REGION_MISSING");
  try(var f=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
   long size=f.size();if(size<8192)throw new IOException("PRIMARY_HEADER_TRUNCATED");
   var loc=ByteBuffer.allocate(4);read(f,loc,4L*((pos.x&31)+(pos.z&31)*32));int value=loc.getInt(0),sector=value>>>8,count=value&255;
   if(sector<2||count==0)throw new IOException("PRIMARY_CHUNK_MISSING");long start=sector*4096L;
   if(start+count*4096L>size)throw new IOException("PRIMARY_LOCATION_OUTSIDE_FILE");
   var h=ByteBuffer.allocate(5);read(f,h,start);int len=h.getInt(0),type=h.get(4)&255;
   if((type&128)!=0)throw new IOException("EXTERNAL_CHUNK_UNSUPPORTED_REFUSE");
   if(len<2||len>count*4096-4||len>JournalDisk.MAX_PAYLOAD)throw new IOException("PRIMARY_COMPRESSED_BOUND");
   byte[] packed=new byte[len-1];read(f,ByteBuffer.wrap(packed),start+5);
   InputStream in=switch(type){case 1->new GZIPInputStream(new ByteArrayInputStream(packed));case 2->new InflaterInputStream(new ByteArrayInputStream(packed));case 3->new ByteArrayInputStream(packed);default->throw new IOException("PRIMARY_COMPRESSION_UNSUPPORTED_REFUSE");};
   byte[] data;try(in){data=in.readNBytes(JournalDisk.MAX_PAYLOAD+1);if(data.length>JournalDisk.MAX_PAYLOAD||in.read()!=-1)throw new IOException("PRIMARY_DECOMPRESSED_BOUND");}
   var again=ByteBuffer.allocate(4);read(f,again,4L*((pos.x&31)+(pos.z&31)*32));if(again.getInt(0)!=value||f.size()!=size)throw new IOException("PRIMARY_CHANGED_DURING_INSPECTION");
   var tag=RepairService.decode(data);if(tag.getInt("xPos")!=pos.x||tag.getInt("zPos")!=pos.z)throw new IOException("PRIMARY_COORDINATE_MISMATCH");
   return new Primary(tag,JournalDisk.hash(data));
  }
 }
 private ReadOnlyRegion(){}
}
