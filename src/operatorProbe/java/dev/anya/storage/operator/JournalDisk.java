package dev.anya.storage.operator;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Strict read-only view. Never invokes SnapshotJournal's creating constructor or mutators. */
public final class JournalDisk {
 public static final int MAX_PAYLOAD=2*1024*1024,MAX_KEYS=64;
 public record Entry(UUID token,long key,int state,boolean locked,byte[] payload){
  public Entry{payload=payload.clone();} public byte[] payload(){return payload.clone();}
 }
 public record Intent(UUID token,long key,boolean pending){}
 public record View(UUID world,Entry entry,Intent intent,boolean fatal,String fingerprint,int keys,long payloadBytes){}
 public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
 public static void safe(Path p)throws IOException{
  Path full=p.toAbsolutePath().normalize();for(Path q=full;q!=null;q=q.getParent())if(Files.isSymbolicLink(q))throw new IOException("SYMLINK_REFUSED");
 }
 public static byte[] bytes(Path p,int max)throws IOException{
  safe(p);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("MISSING_OR_UNSAFE_FILE:"+p.getFileName());
  try(var c=FileChannel.open(p,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
   long n=c.size();if(n<1||n>max)throw new IOException("FILE_BOUND:"+p.getFileName());var b=ByteBuffer.allocate((int)n);
   while(b.hasRemaining()){if(c.read(b)<0)throw new IOException("TRUNCATED_READ");}if(c.size()!=n)throw new IOException("CHANGED_DURING_READ");return b.array();
  }
 }
 private static UUID uuid(DataInputStream d)throws IOException{return new UUID(d.readLong(),d.readLong());}
 private static DataInputStream checked(byte[] b)throws IOException{
  if(b.length<33)throw new IOException("TRUNCATED_RECORD");int n=b.length-32;
  byte[] body=Arrays.copyOf(b,n);if(!hash(body).equals(HexFormat.of().formatHex(Arrays.copyOfRange(b,n,b.length))))throw new IOException("CHECKSUM_MISMATCH");
  return new DataInputStream(new ByteArrayInputStream(body));
 }
 public static View read(Path root,long wanted)throws IOException{
  safe(root);if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("MISSING_JOURNAL_NO_CREATION");
  List<Path> files=new ArrayList<>();try(var ds=Files.newDirectoryStream(root)){for(Path p:ds){if(files.size()>=2*MAX_KEYS+2)throw new IOException("DIRECTORY_BOUND");files.add(p);}}
  files.sort(Comparator.comparing(p->p.getFileName().toString()));Map<String,byte[]> raw=new TreeMap<>();
  int walCount=0,resetCount=0;long rawBytes=0;
  for(Path p:files){String n=p.getFileName().toString();if(n.contains(".tmp-"))throw new IOException("INCOMPLETE_PUBLICATION_RETAINED:"+n);
   if(!n.equals("world.id")&&!n.equals("journal-fault")&&!n.matches("[0-9a-f]+\\.(wal|reset)"))throw new IOException("UNRECOGNIZED_ENTRY:"+n);
   if(n.endsWith(".wal")&&++walCount>MAX_KEYS||n.endsWith(".reset")&&++resetCount>MAX_KEYS)throw new IOException("RECORD_COUNT_BOUND");
   safe(p);rawBytes+=Files.size(p);if(rawBytes>64L*1024*1024+MAX_KEYS*(128+81)+65536+64)throw new IOException("AGGREGATE_READ_BOUND");
   raw.put(n,bytes(p,n.endsWith(".wal")?MAX_PAYLOAD+128:n.endsWith(".reset")?81:n.equals("world.id")?64:65536));
  }
  UUID world;try{world=UUID.fromString(new String(Objects.requireNonNull(raw.get("world.id")),java.nio.charset.StandardCharsets.UTF_8).trim());}catch(RuntimeException e){throw new IOException("MISSING_OR_INVALID_WORLD_BINDING",e);}
  Map<Long,Entry> entries=new HashMap<>();Map<Long,Intent> intents=new HashMap<>();long total=0;StringBuilder manifest=new StringBuilder();
  for(var item:raw.entrySet()){
   String n=item.getKey();byte[] b=item.getValue();manifest.append(n).append(':').append(b.length).append(':').append(hash(b)).append('\n');
   if(n.endsWith(".wal")){try(var d=checked(b)){
    if(d.readInt()!=0x41525031||d.readInt()!=1||!uuid(d).equals(world))throw new IOException("WAL_WORLD_OR_FORMAT");
    UUID t=uuid(d);long k=d.readLong();int state=d.readUnsignedByte(),lock=d.readUnsignedByte(),len=d.readInt();
    if(state>2||lock>1||len<1||len>MAX_PAYLOAD||d.available()!=len||!n.equals(Long.toUnsignedString(k,16)+".wal"))throw new IOException("WAL_STRUCTURE_OR_KEY");
    entries.put(k,new Entry(t,k,state,lock==1,d.readNBytes(len)));total+=len;
   }}else if(n.endsWith(".reset")){if(b.length!=81)throw new IOException("INTENT_SIZE");try(var d=checked(b)){
    if(d.readInt()!=0x41525331||d.readInt()!=1||!uuid(d).equals(world))throw new IOException("INTENT_WORLD_OR_FORMAT");UUID t=uuid(d);long key=d.readLong();int pending=d.readUnsignedByte();
    if(pending>1||d.available()!=0||!n.equals(Long.toUnsignedString(key,16)+".reset"))throw new IOException("INTENT_STRUCTURE_OR_KEY");intents.put(key,new Intent(t,key,pending==1));
   }}
  }
  if(entries.size()>MAX_KEYS||intents.size()>MAX_KEYS||total>64L*1024*1024)throw new IOException("RETAINED_QUOTA_EXCEEDED");
  for(long key:intents.keySet())if(!entries.containsKey(key))throw new IOException("INTENT_WITHOUT_SNAPSHOT");
  Entry e=entries.get(wanted);if(e==null)throw new IOException("REQUESTED_SNAPSHOT_MISSING");
  return new View(world,e,intents.get(wanted),raw.containsKey("journal-fault"),hash(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),entries.size(),total);
 }
 /** Eligibility is conservative: matching old bytes cannot resolve an uncertain old-token reset. */
 public static String decision(View v,boolean samePrimary){
  if(v.fatal)return "REFUSE_GLOBAL_FAULT";
  if(v.intent==null)return "REFUSE_MISSING_RESET_EVIDENCE";
  if(!v.intent.pending)return "REFUSE_NO_PENDING_INTENT";
  if(v.intent.token.equals(v.entry.token))return "REFUSE_UNCERTAIN_OLD_VERSION";
  if(v.entry.state!=2||!v.entry.locked)return "REFUSE_VERIFIED_REPAIR_PROOF_MISSING";
  if(!samePrimary)return "REFUSE_PRIMARY_JOURNAL_DISAGREEMENT";
  return "ELIGIBLE_FOR_EXPLICIT_REVERIFY";
 }
 private JournalDisk(){}
}
