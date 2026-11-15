package dev.anya.storage.pocket;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** World-local write-ahead identity/return ledger. Contains no inventory snapshots or item stacks. */
public final class PocketLedger {
    public static final int MAX_POCKETS=256, MAX_RETURNS=4096;
    private static final int MAGIC=0x41504b54, VERSION=1, MAX_BYTES=1024*1024;
    public static final Set<String> THEMES=Set.of("galaxy","beach","wasteland");
    public enum Stage { RESERVED, BUILDING, READY }
    public record Anchor(String dimension,long hub,UUID hubId,long entrance) {
        public Anchor { validDimension(dimension);Objects.requireNonNull(hubId); }
    }
    public record Pocket(UUID id,UUID owner,int cell,Anchor anchor,String theme,Stage stage,long epoch) {
        public Pocket {
            Objects.requireNonNull(id);Objects.requireNonNull(owner);Objects.requireNonNull(anchor);Objects.requireNonNull(stage);
            if(cell<0||cell>=MAX_POCKETS||!THEMES.contains(theme)||epoch<0)throw new IllegalArgumentException("Invalid pocket record");
        }
        public int x(){return 4096+(cell%16)*512;}
        public int z(){return 4096+(cell/16)*512;}
        public boolean contains(int x,int y,int z){return x>=x()&&x<x()+32&&z>=z()&&z<z()+32&&y>=65&&y<97;}
    }
    public record ReturnPoint(UUID player,UUID pocket,String dimension,double x,double y,double z,float yaw,float pitch) {
        public ReturnPoint {
            Objects.requireNonNull(player);Objects.requireNonNull(pocket);validDimension(dimension);
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>29999900||Math.abs(z)>29999900||y< -2048||y>4096||!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("Invalid return point");
            if(dimension.equals("anyastorage:pockets"))throw new IllegalArgumentException("Nested pocket return denied");
        }
    }
    private final Path directory,file;
    private Map<UUID,Pocket> pockets=new LinkedHashMap<>();
    private Map<UUID,ReturnPoint> returns=new LinkedHashMap<>();
    private long revision;
    private byte[] diskDigest;
    public PocketLedger(Path world)throws IOException {
        Path root=world.toAbsolutePath().normalize();
        directory=root.resolve("anya_storage");Files.createDirectories(directory);
        if(Files.isSymbolicLink(root)||Files.isSymbolicLink(directory))throw new IOException("Unsafe pocket ledger directory");
        file=directory.resolve("pockets.v1.dat");
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))load();
    }
    public synchronized Pocket get(UUID id){return pockets.get(id);}
    public synchronized ReturnPoint returning(UUID player){return returns.get(player);}
    public synchronized List<Pocket> pockets(){return List.copyOf(pockets.values());}
    public synchronized int returnCount(){return returns.size();}
    public synchronized long revision(){return revision;}
    public synchronized Pocket reserve(UUID owner,Anchor anchor,String theme,int limit)throws IOException {
        if(limit<1||limit>MAX_POCKETS||pockets.size()>=limit)throw new IOException("World pocket quota reached");
        if(pockets.values().stream().anyMatch(p->p.anchor().dimension().equals(anchor.dimension())&&p.anchor().entrance()==anchor.entrance()))throw new IOException("This entrance location already has a persistent pocket; recover it instead");
        int cell=pockets.values().stream().mapToInt(Pocket::cell).max().orElse(-1)+1;
        var p=new Pocket(UUID.randomUUID(),owner,cell,anchor,theme,Stage.RESERVED,0);
        var next=new LinkedHashMap<>(pockets);next.put(p.id(),p);commit(next,returns);return p;
    }
    public synchronized Pocket stage(UUID id,Stage expected,Stage next)throws IOException {
        Pocket p=required(id);
        if(p.stage()!=expected||next.ordinal()!=expected.ordinal()+1)throw new IOException("Invalid pocket preparation transition");
        return replace(new Pocket(id,p.owner(),p.cell(),p.anchor(),p.theme(),next,p.epoch()));
    }
    /** Never deletes/reuses a cell, and never regenerates or copies its physical inventory. */
    public synchronized Pocket reanchor(UUID id,UUID actor,Anchor anchor)throws IOException {
        Pocket p=required(id);if(!p.owner().equals(actor))throw new IOException("Only the recorded owner can reanchor");
        if(pockets.values().stream().anyMatch(q->!q.id().equals(id)&&q.anchor().dimension().equals(anchor.dimension())&&q.anchor().entrance()==anchor.entrance()))throw new IOException("Location belongs to another pocket");
        return replace(new Pocket(id,p.owner(),p.cell(),anchor,p.theme(),p.stage(),p.epoch()+1));
    }
    public synchronized Pocket theme(UUID id,UUID actor,String theme)throws IOException {
        Pocket p=required(id);
        if(!p.owner().equals(actor)||p.stage()!=Stage.RESERVED||!THEMES.contains(theme))throw new IOException("Only the owner may choose galaxy, beach or wasteland before first preparation");
        return replace(new Pocket(id,p.owner(),p.cell(),p.anchor(),theme,p.stage(),p.epoch()+1));
    }
    public synchronized void reserveReturn(ReturnPoint point)throws IOException {
        required(point.pocket());if(returns.containsKey(point.player()))throw new IOException("Unfinished pocket return must be recovered first");
        if(returns.size()>=MAX_RETURNS)throw new IOException("Return-record quota reached");
        var next=new LinkedHashMap<>(returns);next.put(point.player(),point);commit(pockets,next);
    }
    /** Call only after the player is outside the pocket and their new player position has been saved. */
    public synchronized void clearReturn(UUID player)throws IOException {
        if(!returns.containsKey(player))return;var next=new LinkedHashMap<>(returns);next.remove(player);commit(pockets,next);
    }
    private Pocket required(UUID id)throws IOException {Pocket p=pockets.get(id);if(p==null)throw new IOException("Unknown pocket identity");return p;}
    private Pocket replace(Pocket p)throws IOException {var next=new LinkedHashMap<>(pockets);next.put(p.id(),p);commit(next,returns);return p;}
    private void commit(Map<UUID,Pocket> next,Map<UUID,ReturnPoint> back)throws IOException {
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_BYTES||diskDigest==null||!MessageDigest.isEqual(digest(Files.readAllBytes(file)),diskDigest))throw new IOException("Pocket ledger changed externally; refusing stale overwrite");
        }else if(diskDigest!=null)throw new IOException("Existing pocket ledger disappeared; refusing to recreate it");
        byte[] bytes=encode(next,back,Math.addExact(revision,1));
        Path tmp=Files.createTempFile(directory,"pockets-",".pending");
        try {
            if(Files.isSymbolicLink(file))throw new IOException("Unsafe pocket ledger file");
            try(FileChannel out=FileChannel.open(tmp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                ByteBuffer buf=ByteBuffer.wrap(bytes);while(buf.hasRemaining())out.write(buf);out.force(true);
            }
            // No non-atomic fallback: a failed commit must not advance the in-memory identity map.
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            pockets=new LinkedHashMap<>(next);returns=new LinkedHashMap<>(back);revision++;diskDigest=digest(bytes);
            try(FileChannel dir=FileChannel.open(directory,StandardOpenOption.READ)){dir.force(true);}catch(IOException|UnsupportedOperationException ignored){}
        }finally{Files.deleteIfExists(tmp);}
    }
    private static byte[] digest(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static void uuid(DataOutputStream out,UUID id)throws IOException{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    private static byte[] encode(Map<UUID,Pocket> pockets,Map<UUID,ReturnPoint> returns,long revision)throws IOException {
        if(pockets.size()>MAX_POCKETS||returns.size()>MAX_RETURNS)throw new IOException("Ledger quota exceeded");
        var buffer=new ByteArrayOutputStream();var out=new DataOutputStream(buffer);
        out.writeInt(MAGIC);out.writeInt(VERSION);out.writeLong(revision);out.writeInt(pockets.size());
        for(Pocket p:pockets.values()){
            uuid(out,p.id());uuid(out,p.owner());out.writeInt(p.cell());out.writeUTF(p.anchor().dimension());out.writeLong(p.anchor().hub());uuid(out,p.anchor().hubId());out.writeLong(p.anchor().entrance());out.writeUTF(p.theme());out.writeByte(p.stage().ordinal());out.writeLong(p.epoch());
        }
        out.writeInt(returns.size());for(ReturnPoint r:returns.values()){
            uuid(out,r.player());uuid(out,r.pocket());out.writeUTF(r.dimension());out.writeDouble(r.x());out.writeDouble(r.y());out.writeDouble(r.z());out.writeFloat(r.yaw());out.writeFloat(r.pitch());
        }
        out.flush();byte[] payload=buffer.toByteArray();out.write(digest(payload));out.flush();
        if(buffer.size()>MAX_BYTES)throw new IOException("Pocket ledger byte quota exceeded");return buffer.toByteArray();
    }
    private void load()throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_BYTES)throw new IOException("Invalid pocket ledger; existing data was not reset");
        byte[] all=Files.readAllBytes(file);if(all.length<52)throw new IOException("Truncated pocket ledger; existing data was not reset");
        byte[] bytes=Arrays.copyOf(all,all.length-32);
        if(!MessageDigest.isEqual(digest(bytes),Arrays.copyOfRange(all,bytes.length,all.length)))throw new IOException("Pocket ledger checksum failed; existing data was not reset");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unsupported pocket ledger format");
            long rev=in.readLong();if(rev<0)throw new IOException("Invalid ledger revision");
            int count=in.readInt();if(count<0||count>MAX_POCKETS)throw new IOException("Pocket quota corrupt");
            var loaded=new LinkedHashMap<UUID,Pocket>();var cells=new HashSet<Integer>();var anchors=new HashSet<String>();
            for(int i=0;i<count;i++){
                UUID id=uuid(in),owner=uuid(in);int cell=in.readInt();var a=new Anchor(in.readUTF(),in.readLong(),uuid(in),in.readLong());String theme=in.readUTF();int stage=in.readUnsignedByte();long epoch=in.readLong();
                if(stage>=Stage.values().length)throw new IOException("Invalid pocket state");
                var p=new Pocket(id,owner,cell,a,theme,Stage.values()[stage],epoch);
                if(loaded.put(id,p)!=null||!cells.add(cell)||!anchors.add(a.dimension()+":"+a.entrance()))throw new IOException("Duplicate pocket identity, cell or anchor");
            }
            int rc=in.readInt();if(rc<0||rc>MAX_RETURNS)throw new IOException("Return quota corrupt");
            var back=new LinkedHashMap<UUID,ReturnPoint>();
            for(int i=0;i<rc;i++){
                var r=new ReturnPoint(uuid(in),uuid(in),in.readUTF(),in.readDouble(),in.readDouble(),in.readDouble(),in.readFloat(),in.readFloat());
                if(!loaded.containsKey(r.pocket())||back.put(r.player(),r)!=null)throw new IOException("Unknown or duplicate return identity");
            }
            if(in.available()!=0)throw new IOException("Unexpected trailing pocket ledger data");
            pockets=loaded;returns=back;revision=rev;diskDigest=digest(all);
        }catch(IllegalArgumentException e){throw new IOException("Invalid pocket ledger; existing data was not reset",e);}
    }
    private static void validDimension(String id){if(id==null||id.length()>128||!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))throw new IllegalArgumentException("Invalid dimension identifier");}
}
