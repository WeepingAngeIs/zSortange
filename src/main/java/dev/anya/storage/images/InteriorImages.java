package dev.anya.storage.images;

import dev.anya.storage.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Private, world-local image library. All authorization/catalog mutation is on the server thread. */
public final class InteriorImages {
    public static final class Entry {
        public UUID id,owner;
        public long created;
        public int width,height,bytes;
        public boolean approved;
        public Set<UUID> viewers=new LinkedHashSet<>();
    }
    private static final class Catalog { public List<Entry> entries=new ArrayList<>(); }
    private static final class Upload {
        final UUID id=UUID.randomUUID(),bag;final int menu,session;final long until;final byte[] data;int received,sequence;
        Upload(UUID bag,int menu,int session,int bytes,long now){this.bag=bag;this.menu=menu;this.session=session;data=new byte[bytes];until=now+30000;}
    }
    private static MinecraftServer server;
    private static Path directory;
    private static String unavailable;
    private static final Map<UUID,Entry> entries=new LinkedHashMap<>();
    private static final Map<UUID,Upload> uploads=new HashMap<>();
    private static final Map<UUID,Long> startTimes=new HashMap<>(),readTimes=new HashMap<>();
    private static final Map<UUID,Set<UUID>> recipients=new HashMap<>();
    private static ThreadPoolExecutor worker;
    private static long globalStart,downloadSecond;
    private static int downloadBytes;
    private static void init(MinecraftServer s)throws IOException{
        if(server==s){if(unavailable!=null)throw new IOException(unavailable);return;}
        stop();server=s;
        try{
            directory=s.getWorldPath(LevelResource.ROOT).resolve("anya_storage").resolve("interiors").toAbsolutePath().normalize();
            Files.createDirectories(directory);
            if(Files.isSymbolicLink(directory)||Files.isSymbolicLink(directory.getParent()))throw new IOException("Unsafe image storage directory");
            Path catalog=directory.resolve("catalog.json");
            if(Files.exists(catalog)){
                if(Files.isSymbolicLink(catalog)||Files.size(catalog)>1048576)throw new IOException("Image catalog needs operator review");
                Catalog c=Config.JSON.fromJson(Files.readString(catalog),Catalog.class);
                if(c==null||c.entries==null||c.entries.size()>512)throw new IOException("Invalid image catalog");
                for(Entry e:c.entries){
                    if(e==null||e.id==null||e.owner==null||e.viewers==null||e.viewers.size()>16||e.viewers.contains(null)||e.bytes<1||e.bytes>PngSanitizer.MAX_OUTPUT||entries.putIfAbsent(e.id,e)!=null)throw new IOException("Invalid image catalog entry");
                }
            }
            worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"Anya interior PNG validator");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        }catch(Exception e){unavailable="Image library unavailable; original files retained for operator review";AnyaStorage.LOG.error(unavailable,e);throw new IOException(unavailable);}
    }
    public static void receive(ServerPlayer p,ExpansionPackets.ImageWire wire){
        if(!p.isAlive()||p.isSpectator()||!(p.containerMenu instanceof StoreMenu menu)||wire.menu()!=menu.containerId||wire.session()!=menu.session()||!menu.stillValid(p))return;
        try{
            if(!ExpansionRules.get().imagesEnabled)throw new IOException("Custom interiors are disabled by this server");
            init(p.getServer());
            switch(wire.op()){
                case 0->begin(p,menu,wire);
                case 1->part(p,menu,wire);
                case 2->view(p,menu,wire);
                default->{}
            }
        }catch(IOException|IllegalArgumentException e){uploads.remove(p.getUUID());reply(p,2,ExpansionPackets.NONE,0,0,safe(e.getMessage()),new byte[0]);}
    }
    private static boolean permitted(ServerPlayer p){var r=ExpansionRules.get();return r.imagesEnabled&&(p.hasPermissions(2)||r.imageUploadsForAll||r.imageUploaders.contains(p.getUUID().toString()));}
    private static void begin(ServerPlayer p,StoreMenu menu,ExpansionPackets.ImageWire w)throws IOException{
        BagInventory bag=menu.imageBag();long now=System.currentTimeMillis();
        if(!permitted(p)||bag==null||!menu.wardrobeOpen()||!bag.valid())throw new IOException("Upload requires permission and an open backpack Wardrobe");
        if(w.total()<45||w.total()>PngSanitizer.MAX_INPUT||w.bytes().length!=0||!BagInventory.data(bag.stack).getUUID("BagId").equals(w.token()))throw new IOException("Invalid upload request");
        if(uploads.containsKey(p.getUUID())||uploads.size()>=8||worker.getQueue().remainingCapacity()==0)throw new IOException("Image service busy; no upload reserved");
        if(now-startTimes.getOrDefault(p.getUUID(),0L)<60000||now-globalStart<1000)throw new IOException("Upload limit: one image per player per minute");
        quota(p.getUUID(),PngSanitizer.MAX_OUTPUT);
        Upload u=new Upload(w.token(),w.menu(),w.session(),w.total(),now);uploads.put(p.getUUID(),u);startTimes.put(p.getUUID(),now);globalStart=now;
        reply(p,0,u.id,0,0,"Upload granted",new byte[0]);
    }
    private static void part(ServerPlayer p,StoreMenu menu,ExpansionPackets.ImageWire w)throws IOException{
        Upload u=uploads.get(p.getUUID());if(u==null)throw new IOException("Upload reservation expired");
        BagInventory bag=menu.imageBag();
        if(!permitted(p)||bag==null||!menu.wardrobeOpen()||!bag.valid()||!BagInventory.data(bag.stack).getUUID("BagId").equals(u.bag)||u.menu!=w.menu()||u.session!=w.session()||!u.id.equals(w.token())||u.until<System.currentTimeMillis()||w.sequence()!=u.sequence||w.bytes().length==0||w.bytes().length!=Math.min(16384,u.data.length-u.received))throw new IOException("Upload sequence rejected");
        System.arraycopy(w.bytes(),0,u.data,u.received,w.bytes().length);u.received+=w.bytes().length;u.sequence++;
        if(u.received!=u.data.length)return;
        uploads.remove(p.getUUID());UUID user=p.getUUID();MinecraftServer active=server;
        try{worker.execute(()->{
            PngSanitizer.Image image;try{image=PngSanitizer.sanitize(u.data);}catch(IOException e){active.execute(()->{ServerPlayer online=active.getPlayerList().getPlayer(user);if(online!=null)reply(online,2,u.id,0,0,safe(e.getMessage()),new byte[0]);});return;}
            active.execute(()->{
                if(server!=active)return;ServerPlayer online=active.getPlayerList().getPlayer(user);if(online==null)return;
                try{
                    if(!permitted(online)||!(online.containerMenu instanceof StoreMenu current)||current.containerId!=u.menu||current.session()!=u.session||!current.wardrobeOpen()||!current.stillValid(online))throw new IOException("Upload cancelled because the backpack menu changed");
                    BagInventory currentBag=current.imageBag();if(currentBag==null||!BagInventory.data(currentBag.stack).getUUID("BagId").equals(u.bag))throw new IOException("Backpack changed; image was not saved");
                    quota(user,image.png().length);Entry entry=new Entry();entry.id=UUID.randomUUID();entry.owner=user;entry.created=System.currentTimeMillis();entry.width=image.width();entry.height=image.height();entry.bytes=image.png().length;entry.approved=!ExpansionRules.get().imageModeration;
                    write(entry.id,image.png());entries.put(entry.id,entry);
                    try{persist();}catch(IOException e){entries.remove(entry.id);Files.deleteIfExists(file(entry.id));throw e;}
                    attach(online,currentBag,entry.id);current.refreshAppearance();reply(online,5,entry.id,0,0,"Saved privately. Shared visibility requires approval and explicit permission.",new byte[0]);
                }catch(IOException e){reply(online,2,u.id,0,0,safe(e.getMessage()),new byte[0]);}
            });
        });}catch(RejectedExecutionException e){throw new IOException("PNG validation queue full");}
    }
    private static void quota(UUID owner,int incoming)throws IOException{
        var r=ExpansionRules.get();if(entries.values().stream().filter(e->e.owner.equals(owner)).count()>=r.imagesPerPlayer)throw new IOException("Your image quota is full; delete an old image first");
        long used=0;int count=0;
        // Count orphan files too, so an interrupted save cannot bypass the disk quota.
        try(DirectoryStream<Path> files=Files.newDirectoryStream(directory,"*.png")){for(Path p:files){if(Files.isSymbolicLink(p))throw new IOException("Image storage needs operator review");used+=Files.size(p);if(++count>512)break;}}
        if(count>=r.imagesTotal||used+incoming>(long)r.imageWorldMiB*1048576)throw new IOException("Server image quota is full");
    }
    private static Path file(UUID id){return directory.resolve(id.toString()+".png");}
    private static void write(UUID id,byte[] bytes)throws IOException{
        Path temp=directory.resolve(id+".tmp"),out=file(id);
        try{Files.write(temp,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);Files.move(temp,out,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
    }
    private static void persist()throws IOException{
        Catalog c=new Catalog();c.entries.addAll(entries.values());Path temp=directory.resolve("catalog-"+UUID.randomUUID()+".tmp");
        try{Files.writeString(temp,Config.JSON.toJson(c),StandardOpenOption.CREATE_NEW);Files.move(temp,directory.resolve("catalog.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
    public static List<Entry> list(ServerPlayer p,boolean moderation){
        try{init(p.getServer());return entries.values().stream().filter(e->e.owner.equals(p.getUUID())||(moderation&&p.hasPermissions(2))).toList();}
        catch(IOException e){AnyaStorage.tell(p,safe(e.getMessage()));return List.of();}
    }
    public static boolean canView(ServerPlayer p,Entry e,boolean moderation){return e!=null&&(e.owner.equals(p.getUUID())||(moderation&&p.hasPermissions(2))||(ExpansionRules.get().imageSharing&&e.approved&&e.viewers.contains(p.getUUID())));}
    public static void attach(ServerPlayer p,BagInventory bag,UUID id)throws IOException{
        Entry e=entries.get(id);if(!bag.valid()||e==null||!e.owner.equals(p.getUUID()))throw new IOException("Only your own image can be attached");
        CompoundTag data=BagInventory.data(bag.stack);data.putUUID("InteriorImage",id);BagInventory.data(bag.stack,data);bag.hostChanged();
    }
    public static void clearAttachment(ServerPlayer p,BagInventory bag){CompoundTag t=BagInventory.data(bag.stack);t.remove("InteriorImage");BagInventory.data(bag.stack,t);bag.hostChanged();}
    private static void view(ServerPlayer p,StoreMenu menu,ExpansionPackets.ImageWire w)throws IOException{
        long now=System.currentTimeMillis();if(now-readTimes.getOrDefault(p.getUUID(),0L)<750)return;readTimes.put(p.getUUID(),now);
        Entry e=entries.get(w.token());BagInventory bag=menu.imageBag();boolean moderation=menu.moderatingImage(w.token());
        boolean attached=bag!=null&&BagInventory.data(bag.stack).hasUUID("InteriorImage")&&w.token().equals(BagInventory.data(bag.stack).getUUID("InteriorImage"));
        if(!(attached||moderation)||!canView(p,e,moderation)){reply(p,3,w.token(),0,0,"Image unavailable or not shared with you",new byte[0]);return;}
        recipients.computeIfAbsent(e.id,id->new HashSet<>()).add(p.getUUID());
        if(w.sequence()==1){reply(p,4,e.id,0,0,"Access renewed",new byte[0]);return;}
        Path path=file(e.id);if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=e.bytes)throw new IOException("Image unavailable");
        byte[] bytes;try(InputStream input=Files.newInputStream(path)){bytes=input.readNBytes(PngSanitizer.MAX_OUTPUT+1);}if(bytes.length!=e.bytes)throw new IOException("Image unavailable");
        long second=System.nanoTime()/1_000_000_000L;if(second!=downloadSecond){downloadSecond=second;downloadBytes=0;}
        if(downloadBytes+bytes.length>1048576)throw new IOException("Image download budget busy; retry shortly");downloadBytes+=bytes.length;
        int parts=(bytes.length+16383)/16384;
        for(int i=0;i<parts;i++)reply(p,1,e.id,i,parts,"",Arrays.copyOfRange(bytes,i*16384,Math.min(bytes.length,(i+1)*16384)));
    }
    public static String manage(ServerPlayer p,UUID id,String action,UUID viewer){
        try{
            init(p.getServer());Entry e=entries.get(id);if(e==null)return "Image unavailable";
            boolean owner=e.owner.equals(p.getUUID()),admin=p.hasPermissions(2);
            if(action.equals("delete")&&(owner||admin)){
                entries.remove(id);try{persist();}catch(IOException ex){entries.put(id,e);throw ex;}Files.deleteIfExists(file(id));invalidate(id);return "Image deleted. Backpacks fall back to their built-in interior.";
            }
            if(action.equals("approve")&&admin){boolean old=e.approved;e.approved=!e.approved;try{persist();}catch(IOException ex){e.approved=old;throw ex;}invalidate(id);return e.approved?"Approved for explicitly authorized viewers":"Shared access revoked";}
            if(action.equals("share")&&owner&&viewer!=null){
                if(!ExpansionRules.get().imageSharing)return "Image sharing is disabled by the operator";
                Set<UUID> old=new LinkedHashSet<>(e.viewers);if(!e.viewers.remove(viewer)){if(e.viewers.size()>=16)return "At most 16 viewers per image";e.viewers.add(viewer);}
                try{persist();}catch(IOException ex){e.viewers=old;throw ex;}invalidate(id);return "Explicit viewer permission updated. Approval is still required.";
            }
            return "Permission denied";
        }catch(IOException e){AnyaStorage.LOG.warn("Image library operation failed",e);return "Image operation failed; ask an operator to review the server log";}
    }
    private static void invalidate(UUID id){Set<UUID> people=recipients.remove(id);if(people!=null&&server!=null)for(UUID user:people){ServerPlayer p=server.getPlayerList().getPlayer(user);if(p!=null)reply(p,3,id,0,0,"Access changed; cached image removed",new byte[0]);}}
    public static void tick(ServerTickEvent.Post event){long now=System.currentTimeMillis();uploads.values().removeIf(u->u.until<now);startTimes.values().removeIf(t->now-t>600000);readTimes.values().removeIf(t->now-t>60000);}
    public static void disconnect(ServerPlayer p){uploads.remove(p.getUUID());readTimes.remove(p.getUUID());recipients.values().forEach(s->s.remove(p.getUUID()));}
    public static void stop(){ExpansionPackets.clearImageGate();downloadBytes=0;downloadSecond=0;if(worker!=null)worker.shutdownNow();worker=null;server=null;directory=null;unavailable=null;entries.clear();uploads.clear();startTimes.clear();readTimes.clear();recipients.clear();globalStart=0;}
    private static String safe(String text){return text==null||text.indexOf('/')>=0||text.indexOf('\\')>=0?"Image operation failed; ask an operator to review the server log":text.substring(0,Math.min(text.length(),150));}
    private static void reply(ServerPlayer p,int kind,UUID id,int part,int parts,String info,byte[] bytes){PacketDistributor.sendToPlayer(p,new ExpansionPackets.ImageReply(kind,id,part,parts,info,bytes));}
    private InteriorImages(){}
}
