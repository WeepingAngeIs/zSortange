package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import dev.anya.storage.Safety;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Session-only relay. Only the requesting player provides an address; recipients get bounded bytes. */
@EventBusSubscriber(modid=AnyaStorage.ID)
public final class RoomMedia {
    private static final int MAX_PENDING=3, MAX_ROOMS=8, CACHE_BYTES=32<<20;
    private static final int PIECES_PER_TICK=8, AUDIENCE_TICKS=6000;
    private static RoomMedia relay;
    private final MinecraftServer server;
    private final Map<UUID,Pending> pending=new HashMap<>();
    private final Map<UUID,Show> rooms=new LinkedHashMap<>();
    private final Map<ServerPlayer,Long> requests=new WeakHashMap<>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING),Thread.ofPlatform().daemon().name("Skygrid room media").factory());
    private int offset;

    private RoomMedia(MinecraftServer server){this.server=server;}
    private static boolean display(CameraDevices.Device d) {
        return d!=null && !d.isRemoved() && d.getBlockState().getBlock() instanceof WallMonitorBlock;
    }
    private static boolean visit(ServerPlayer p,PocketLedger.Pocket room,CameraDevices.Device d) {
        if(room==null || !display(d) || d.getLevel()!=p.level())return false;
        var pos=d.getBlockPos();
        if(!room.contains(pos.getX(),pos.getY(),pos.getZ()) || !p.serverLevel().hasChunkAt(pos)
                || p.serverLevel().getBlockEntity(pos)!=d || PocketService.admittedVisit(p)!=room) return false;
        var lease=PocketService.lease(p.getUUID());
        return Safety.locationReason(p,p.serverLevel(),pos).isEmpty()
                && PocketService.authorize(p,room,PocketLeases.Kind.VISIT,false,0).isEmpty()
                && display(d) && PocketService.admittedVisit(p)==room && PocketService.lease(p.getUUID())==lease
                && d.getLevel()==p.level() && p.serverLevel().hasChunkAt(pos) && p.serverLevel().getBlockEntity(pos)==d;
    }
    static boolean canControl(ServerPlayer p,CameraDevices.Device d){
        if(!visit(p,PocketService.admittedVisit(p),d))return false;
        if(!(p.pick(p.blockInteractionRange(),1,false) instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return false;
        return hit.getBlockPos().equals(d.getBlockPos()) ||
                WallPanel.atHit(p.level(),hit,p.getEyePosition(),p.getLookAngle())==d;
    }
    private static void tell(ServerPlayer p,BlockPos pos,String message){
        if(p.connection.hasChannel(RoomMediaNet.Notice.TYPE))
            PacketDistributor.sendToPlayer(p,new RoomMediaNet.Notice(pos,message));
    }
    private static final class Pending {
        final UUID room,requester;
        final CameraDevices.Device device;
        final long visitId,submitted;
        final AtomicReference<Process> process=new AtomicReference<>();
        Future<?> task;
        Pending(UUID room,ServerPlayer p,CameraDevices.Device device){
            this.room=room;requester=p.getUUID();this.device=device;
            visitId=PocketService.lease(requester).deadline();submitted=p.serverLevel().getGameTime();
        }
        void cancel(){
            if(task!=null)task.cancel(true);
            var child=process.getAndSet(null);
            if(child!=null)child.destroyForcibly();
        }
    }
    private static final class Cursor {
        final long visitId,joined;
        int sent;
        boolean receiving,ready;
        long lastBegin=Long.MIN_VALUE, lastClock=Long.MIN_VALUE;
        Cursor(long visitId,long tick){this.visitId=visitId;joined=tick;}
    }
    private record Movie(byte[] bytes,int duration,boolean loop) {}
    private static final class Show {
        final UUID id=UUID.randomUUID(),room;
        final CameraDevices.Device device;
        final Movie movie;
        final long opened;
        final Map<UUID,Cursor> audience=new LinkedHashMap<>();
        long firstReady=-1,start=-1;
        Show(Pending request,Movie movie,long tick){room=request.room;device=request.device;this.movie=movie;opened=tick;}
    }
    private record Delivery(Show show,ServerPlayer player,Cursor cursor) {}

    static void play(ServerPlayer p,BlockPos pos,String address){
        var room=PocketService.admittedVisit(p);
        if(room==null || !p.connection.hasChannel(RoomMediaNet.Begin.TYPE))return;
        if(relay==null)relay=new RoomMedia(p.getServer());
        if(relay.server!=p.getServer())return;
        var self=relay;
        long tick=p.serverLevel().getGameTime();
        if(tick<self.requests.getOrDefault(p,Long.MIN_VALUE))return;
        self.requests.put(p,tick+20);
        if(!room.contains(pos.getX(),pos.getY(),pos.getZ()) || !p.serverLevel().hasChunkAt(pos)
                || !(p.serverLevel().getBlockEntity(pos) instanceof CameraDevices.Device d) || !canControl(p,d)){
            tell(p,pos,"Stand near a screen in the Skygrid room you are visiting.");return;
        }
        try {MediaDownload.directUri(address);}
        catch(MediaDownload.Failure refused){tell(p,pos,refused.getMessage());return;}
        if(self.pending.containsKey(room.id()) || self.pending.size()>=MAX_PENDING){
            tell(p,pos,"A screen is preparing media. Try again shortly.");return;
        }
        self.requests.put(p,tick+100);
        var request=new Pending(room.id(),p,d);
        self.pending.put(room.id(),request);
        self.worker.purge();
        request.task=self.worker.submit(()->self.prepare(request,address));
        tell(p,pos,"Preparing room media…");
    }
    private void prepare(Pending request,String address){
        Movie movie;
        try {
            byte[] bytes=MediaDownload.fetch(address);
            if(bytes.length<=12 || bytes.length>MediaDownload.MAX_BYTES) throw new java.io.IOException();
            if(Thread.currentThread().isInterrupted())return;
            var clip=ScreenClip.decode(bytes,request.process);
            movie=new Movie(bytes,clip.duration(),clip.loop());
        }catch(Exception error){
            String safe = error instanceof MediaDownload.Failure f ? f.getMessage() :
                    "Could not prepare this file. Use a PNG, JPEG, GIF or H.264/AAC MP4 up to 16 MiB and 60 seconds.";
            if(!worker.isShutdown())server.execute(()->complete(request,null,safe));
            return;
        }
        if(!worker.isShutdown())server.execute(()->complete(request,movie,""));
    }
    private void complete(Pending request,Movie movie,String reason){
        if(relay!=this || !pending.remove(request.room,request))return;
        var p=server.getPlayerList().getPlayer(request.requester);
        if(p==null || !validRequester(p,request))return;
        var pos=request.device.getBlockPos();
        if(movie==null){tell(p,pos,reason);return;}
        var previous=rooms.get(request.room);
        int used=rooms.values().stream().filter(s->s!=previous).mapToInt(s->s.movie.bytes.length).sum();
        if(used+movie.bytes.length>CACHE_BYTES || previous==null && rooms.size()>=MAX_ROOMS){
            tell(p,pos,"The room media cache is full. Try again after another screen finishes.");return;
        }
        if(previous!=null)end(previous);
        rooms.put(request.room,new Show(request,movie,p.serverLevel().getGameTime()));
    }
    private static boolean validRequester(ServerPlayer p,Pending request){
        var room=PocketService.admittedVisit(p);
        return room!=null && room.id().equals(request.room) && PocketService.lease(p.getUUID()).deadline()==request.visitId
                && visit(p,room,request.device);
    }
    static void ack(ServerPlayer p,UUID id,boolean prepared){
        var self=relay;
        if(self==null || self.server!=p.getServer()) return;
        var lease=PocketService.lease(p.getUUID());
        if(lease==null || lease.kind()!=PocketLeases.Kind.VISIT)return;
        var show=self.rooms.get(lease.pocket());
        if(show==null || !show.id.equals(id))return;
        var cursor=show.audience.get(p.getUUID());
        if(cursor==null || cursor.visitId!=lease.deadline())return;
        if(prepared){
            if(!cursor.receiving || cursor.sent!=show.movie.bytes.length || cursor.ready)return;
            cursor.ready=true;
        }else if(cursor.lastBegin!=Long.MIN_VALUE)cursor.receiving=true;
    }
    private void end(Show show){
        rooms.remove(show.room);
        for(UUID id:show.audience.keySet())ended(id,show.id);
        show.audience.clear();
    }
    private void ended(UUID player,UUID id){
        var p=server.getPlayerList().getPlayer(player);
        if(p!=null && p.connection.hasChannel(RoomMediaNet.End.TYPE))PacketDistributor.sendToPlayer(p,new RoomMediaNet.End(id));
    }
    private boolean deliverable(Show show,ServerPlayer p,Cursor cursor){
        if(rooms.get(show.room)!=show || show.audience.get(p.getUUID())!=cursor || !p.connection.hasChannel(RoomMediaNet.Begin.TYPE))return false;
        var lease=PocketService.lease(p.getUUID());
        return lease!=null && lease.deadline()==cursor.visitId && visit(p,PocketService.loadedPocket(server,show.room),show.device);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        if(relay!=null && relay.server==event.getServer())relay.advance();
    }
    private void advance(){
        if(rooms.isEmpty() && pending.isEmpty())return;
        var world=PocketService.dimension(server);
        if(world==null){clear();return;}
        long now=world.getGameTime();
        for(var it=pending.values().iterator();it.hasNext();){
            var request=it.next();
            var player=server.getPlayerList().getPlayer(request.requester);
            if(player==null || now-request.submitted>3600 || !validRequester(player,request)){
                it.remove();request.cancel();
            }
        }
        if(rooms.isEmpty())return;
        List<Delivery> sendable=new ArrayList<>();
        for(var show:List.copyOf(rooms.values())){
            var room=PocketService.loadedPocket(server,show.room);
            long life=show.movie.duration==0 || show.movie.loop?AUDIENCE_TICKS:(show.movie.duration+49L)/50;
            if(room==null || !display(show.device) || !world.hasChunkAt(show.device.getBlockPos())
                    || world.getBlockEntity(show.device.getBlockPos())!=show.device
                    || (show.start<0 ? now-show.opened>AUDIENCE_TICKS : now-show.start>=life)){
                end(show);continue;
            }
            Set<UUID> seen=new HashSet<>();
            for(var p:server.getPlayerList().getPlayers()){
                var lease=PocketService.lease(p.getUUID());
                if(lease==null || !lease.pocket().equals(show.room) || !p.connection.hasChannel(RoomMediaNet.Begin.TYPE)
                        || !visit(p,room,show.device))continue;
                UUID id=p.getUUID();seen.add(id);
                var cursor=show.audience.get(id);
                if(cursor==null || cursor.visitId!=lease.deadline()){
                    if(cursor!=null)ended(id,show.id);
                    cursor=new Cursor(lease.deadline(),now);show.audience.put(id,cursor);
                }
                if(!cursor.receiving && now-cursor.joined<=400
                        && (cursor.lastBegin==Long.MIN_VALUE || now-cursor.lastBegin>=20)){
                    PacketDistributor.sendToPlayer(p,new RoomMediaNet.Begin(show.id,show.device.getBlockPos(),
                            world.dimension().location(),show.movie.bytes.length,show.movie.duration,show.movie.loop,show.room));
                    cursor.lastBegin=now;
                }else if(cursor.receiving && cursor.sent<show.movie.bytes.length){
                    sendable.add(new Delivery(show,p,cursor));
                }
            }
            for(var it=show.audience.keySet().iterator();it.hasNext();){
                UUID id=it.next();
                if(!seen.contains(id)){it.remove();ended(id,show.id);}
            }
            if(seen.isEmpty()){end(show);continue;}
            if(show.audience.values().stream().anyMatch(c->c.ready)){
                if(show.firstReady<0)show.firstReady=now;
                if(show.start<0 && (now-show.firstReady>=100 || show.audience.values().stream().allMatch(c->c.ready)))
                    show.start=now+5;
            }
            if(show.start>=0) for(var e:show.audience.entrySet()){
                var cursor=e.getValue();
                if(cursor.ready && (cursor.lastClock==Long.MIN_VALUE || now-cursor.lastClock>=40)){
                    var p=server.getPlayerList().getPlayer(e.getKey());
                    if(p!=null && deliverable(show,p,cursor)){
                        PacketDistributor.sendToPlayer(p,new RoomMediaNet.Clock(show.id,show.start));
                        cursor.lastClock=now;
                    }
                }
            }
        }
        int n=sendable.size();
        if(n==0){offset=0;return;}
        int count=Math.min(n,PIECES_PER_TICK);
        for(int i=0;i<count;i++){
            var d=sendable.get((offset+i)%n);
            if(!deliverable(d.show,d.player,d.cursor))continue;
            var bytes=d.show.movie.bytes;
            int first=d.cursor.sent;
            int next=Math.min(bytes.length,first+RoomMediaNet.CHUNK_BYTES);
            PacketDistributor.sendToPlayer(d.player,new RoomMediaNet.Part(d.show.id,first,Arrays.copyOfRange(bytes,first,next)));
            d.cursor.sent=next;
        }
        offset=(offset+count)%n;
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent e){
        if(relay!=null && relay.server==e.getServer()){relay.clear();relay.worker.shutdownNow();relay=null;}
    }
    private void clear(){
        for(var request:pending.values())request.cancel();
        pending.clear();
        for(var show:List.copyOf(rooms.values()))end(show);
        requests.clear();
    }
}
