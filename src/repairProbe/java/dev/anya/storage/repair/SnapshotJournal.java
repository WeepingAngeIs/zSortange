package dev.anya.storage.repair;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Test-only bounded full snapshots, not inventory commands. Single IO writer; nonblocking published readers. */
public final class SnapshotJournal {
 public enum State { PENDING, APPLIED, REPAIRED }
 public record Record(UUID token,long key,State state,boolean locked,byte[] payload) {
  public Record {payload=payload.clone();}
  @Override public byte[] payload(){return payload.clone();}
 }
 public interface Cut {void at(String point)throws IOException;}
 public static final int MAX_BYTES=2*1024*1024,MAX_RECORDS=64;
 public static final long MAX_TOTAL=64L*1024*1024;
 private static final int MAGIC=0x41525031,VERSION=1;
 private final Path root;private final UUID binding;
 private final Map<Long,Record> records=new LinkedHashMap<>();
 private record ResetMark(UUID token,long key,boolean pending) {}
 private final Map<Long,ResetMark> resetMarks=new LinkedHashMap<>();
 private volatile Map<Long,ResetMark> publishedResets=Map.of();
 private volatile Map<Long,Record> published=Map.of();
 private volatile long bytes;
 private Cut cut=p->{};
 public SnapshotJournal(Path root)throws IOException {
  this.root=root.toAbsolutePath().normalize();
  if(Files.isSymbolicLink(this.root))throw new IOException("Journal symlink refused");
  Files.createDirectories(this.root);Path bind=this.root.resolve("world.id");
  if(Files.exists(bind)){checkFile(bind);binding=UUID.fromString(Files.readString(bind).trim());}
  else {binding=UUID.randomUUID();atomic(bind,binding.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),"binding");}
  try(var stream=Files.list(this.root)){
   for(Path p:stream.toList()){
    if(p.getFileName().toString().contains(".tmp-"))throw new IOException("Incomplete publication retained for inspection: "+p.getFileName());
    if(p.getFileName().toString().endsWith(".reset")){
     checkFile(p);if(Files.size(p)!=81)throw new IOException("Reset marker size mismatch");
     ResetMark m=decodeReset(Files.readAllBytes(p));
     if(!p.getFileName().equals(resetPath(m.key()).getFileName())||resetMarks.put(m.key(),m)!=null||resetMarks.size()>MAX_RECORDS)throw new IOException("Reset marker key/quota mismatch");
     continue;
    }
    if(!p.getFileName().toString().endsWith(".wal"))continue;
    checkFile(p);if(Files.size(p)>MAX_BYTES+128)throw new IOException("Oversized journal record");
    Record r=decode(Files.readAllBytes(p));
    if(!p.getFileName().equals(path(r.key()).getFileName())||records.put(r.key(),r)!=null)throw new IOException("Journal key mismatch");
    bytes+=r.payload.length;if(records.size()>MAX_RECORDS||bytes>MAX_TOTAL)throw new IOException("Journal quota exceeded");
   }
  }
  for(long key:resetMarks.keySet())if(!records.containsKey(key))throw new IOException("Reset marker without retained snapshot");
  published=Map.copyOf(records);publishedResets=Map.copyOf(resetMarks);
 }
 public boolean fatal(){return Files.exists(root.resolve("journal-fault"));}
 public synchronized void failClosed(String reason)throws IOException{atomic(root.resolve("journal-fault"),reason.getBytes(java.nio.charset.StandardCharsets.UTF_8),"fatal");}
 public synchronized void cuts(Cut value){cut=Objects.requireNonNull(value);}
 public List<Record> all(){return List.copyOf(published.values());}
 public Record get(long key){return published.get(key);}
 public long bytes(){return bytes;}
 public synchronized Record pending(long key,byte[] payload)throws IOException {
  Record old=records.get(key);
  if(payload.length==0||payload.length>MAX_BYTES)throw new IOException("Snapshot size limit");
  if(old==null&&records.size()>=MAX_RECORDS||bytes-(old==null?0:old.payload.length)+payload.length>MAX_TOTAL)throw new IOException("Snapshot quota; no retained record evicted");
  return publish(new Record(UUID.randomUUID(),key,State.PENDING,old!=null&&(old.locked||old.state!=State.APPLIED||resetPending(key)),payload),"pending");
 }
 public synchronized Record lock(long key,UUID token)throws IOException {
  Record r=exact(key,token);return r.locked?r:publish(new Record(r.token,r.key,r.state,true,r.payload),"lock");
 }
 public synchronized Record applied(long key,UUID token,boolean repair)throws IOException {
  Record r=exact(key,token);boolean protect=repair||r.locked||resetPending(key);return publish(new Record(r.token,r.key,protect?State.REPAIRED:State.APPLIED,protect,r.payload),"applied");
 }
 /** Separate reset after durable repair and caller read-back; never discard pending records. */
 public synchronized Record reset(long key,UUID token)throws IOException {
  Record r=exact(key,token);if(r.state!=State.REPAIRED||!r.locked)throw new IOException("Verified repair proof missing");
  return publish(new Record(r.token,r.key,State.APPLIED,false,r.payload),resetPending(key)?"reset-projection":"reset");
 }
 /** Persist intent before exposing any unlocked projection. It remains authoritative across crashes. */
 public synchronized void beginReset(long key,UUID token)throws IOException {
  Record r=exact(key,token);if(r.state!=State.REPAIRED||!r.locked)throw new IOException("Verified repair proof missing");
  publishReset(new ResetMark(token,key,true),"reset-intent");
 }
 /** Invoked only after service revalidation/fence; never cancels an outstanding newer snapshot. */
 public synchronized void completeReset(long key,UUID token)throws IOException {
  Record r=exact(key,token);ResetMark m=resetMarks.get(key);
  if(m==null||!m.pending||!m.token.equals(token)||r.state!=State.APPLIED||r.locked)throw new IOException("Current reset intent/projection missing");
  publishReset(new ResetMark(token,key,false),"reset");
 }
 public boolean resetPending(long key){ResetMark m=publishedResets.get(key);return m!=null&&m.pending;}
 public boolean blocked(long key){Record r=published.get(key);return resetPending(key)||r!=null&&(r.locked||r.state!=State.APPLIED);}
 private Path resetPath(long key){return root.resolve(Long.toUnsignedString(key,16)+".reset");}
 private void publishReset(ResetMark m,String phase)throws IOException {
  var body=new ByteArrayOutputStream();try(var d=new DataOutputStream(body)){d.writeInt(0x41525331);d.writeInt(1);uuid(d,binding);uuid(d,m.token);d.writeLong(m.key);d.writeBoolean(m.pending);}
  byte[] data=body.toByteArray();var frame=new ByteArrayOutputStream();frame.write(data);frame.write(digest(data));
  atomic(resetPath(m.key),frame.toByteArray(),phase);resetMarks.put(m.key,m);publishedResets=Map.copyOf(resetMarks);
 }
 private ResetMark decodeReset(byte[] frame)throws IOException {
  if(frame.length!=81)throw new IOException("Reset marker size mismatch");int n=frame.length-32;
  if(!MessageDigest.isEqual(Arrays.copyOfRange(frame,n,frame.length),digest(Arrays.copyOf(frame,n))))throw new IOException("Reset marker checksum mismatch");
  try(var in=new DataInputStream(new ByteArrayInputStream(frame,0,n))){
   if(in.readInt()!=0x41525331||in.readInt()!=1||!uuid(in).equals(binding))throw new IOException("Reset marker world/version mismatch");
   UUID token=uuid(in);long key=in.readLong();int flag=in.readUnsignedByte();if(flag>1||in.available()!=0)throw new IOException("Reset marker structure mismatch");return new ResetMark(token,key,flag==1);
  }
 }
 private Record exact(long key,UUID token)throws IOException {Record r=records.get(key);if(r==null||!r.token.equals(token))throw new IOException("Stale journal operation refused");return r;}
 private Record publish(Record r,String phase)throws IOException {
  atomic(path(r.key),encode(r),phase);Record old=records.put(r.key,r);bytes+=r.payload.length-(old==null?0:old.payload.length);published=Map.copyOf(records);return r;
 }
 private Path path(long key){return root.resolve(Long.toUnsignedString(key,16)+".wal");}
 private byte[] encode(Record r)throws IOException {
  var body=new ByteArrayOutputStream();try(var d=new DataOutputStream(body)){
   d.writeInt(MAGIC);d.writeInt(VERSION);uuid(d,binding);uuid(d,r.token);d.writeLong(r.key);d.writeByte(r.state.ordinal());d.writeBoolean(r.locked);d.writeInt(r.payload.length);d.write(r.payload);
  }byte[] data=body.toByteArray();var out=new ByteArrayOutputStream();out.write(data);out.write(digest(data));return out.toByteArray();
 }
 private Record decode(byte[] frame)throws IOException {
  if(frame.length<87)throw new IOException("Truncated journal record");int body=frame.length-32;
  if(!MessageDigest.isEqual(Arrays.copyOfRange(frame,body,frame.length),digest(Arrays.copyOf(frame,body))))throw new IOException("Journal checksum mismatch");
  try(var in=new DataInputStream(new ByteArrayInputStream(frame,0,body))){
   if(in.readInt()!=MAGIC||in.readInt()!=VERSION||!uuid(in).equals(binding))throw new IOException("Journal version/world mismatch");
   UUID token=uuid(in);long key=in.readLong();int state=in.readUnsignedByte();boolean locked=in.readBoolean();int len=in.readInt();
   if(state>=State.values().length||len<1||len>MAX_BYTES||len!=in.available())throw new IOException("Journal structure/size mismatch");
   return new Record(token,key,State.values()[state],locked,in.readNBytes(len));
  }
 }
 private void atomic(Path target,byte[] data,String phase)throws IOException {
  if(Files.exists(target))checkFile(target);Path tmp=root.resolve(target.getFileName()+".tmp-"+UUID.randomUUID());
  // Orphan temps remain on error and block reopening; never silently promote an incomplete publication.
  try(FileChannel ch=FileChannel.open(tmp,CREATE_NEW,WRITE)){
   ByteBuffer b=ByteBuffer.wrap(data);while(b.hasRemaining())ch.write(b);
   cut.at(phase+"-before-force");ch.force(true);cut.at(phase+"-after-force");
  }
  Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  try(FileChannel dir=FileChannel.open(root,READ)){dir.force(true);}cut.at(phase+"-published");
 }
 private static void checkFile(Path p)throws IOException {if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe journal path");}
 private static byte[] digest(byte[] b){try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
 private static void uuid(DataOutputStream out,UUID u)throws IOException{out.writeLong(u.getMostSignificantBits());out.writeLong(u.getLeastSignificantBits());}
 private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
}
