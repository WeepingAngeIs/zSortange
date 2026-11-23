package dev.anya.storage.pocket;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/** Receives one authorized in-room display. Passive viewers never contact a media origin. */
public final class RoomMediaClient {
    private static final Map<UUID,UUID> stopped = new HashMap<>();
    private static Connection connection;
    private static Transfer current;
    private static long ticks;

    static { RoomMediaNet.receiver = RoomMediaClient::receive; }

    private static final class Transfer {
        final RoomMediaNet.Begin begin;
        final ClientLevel level;
        final BlockEntity display;
        byte[] bytes;
        int offset;
        long deadline;
        boolean ready;
        long start = -1;

        Transfer(RoomMediaNet.Begin begin, ClientLevel level, BlockEntity display) {
            this.begin=begin;
            this.level=level;
            this.display=display;
            deadline=ticks+120;
        }
    }

    public static void tick() {
        var mc=Minecraft.getInstance();
        Connection actual=mc.getConnection()==null ? null : mc.getConnection().getConnection();
        if(actual!=connection) {
            disconnect();
            connection=actual;
        }
        if(!mc.isPaused()) ticks++;
        if(current!=null && (!present(current) || ticks>=current.deadline)) forget();
    }

    public static void disconnect() {
        forget();
        stopped.clear();
        connection=null;
        ticks=0;
    }

    /** Local preference for this playback, including after walking out and back into the room. */
    public static void stopForMe(BlockPos pos) {
        Transfer transfer=current;
        if(transfer!=null && transfer.begin.pos().equals(pos)) {
            stopped.put(transfer.begin.roomId(),transfer.begin.id());
            forget();
        }
        ScreenMedia.stop(pos);
    }

    private static void forget() {
        if(current!=null) ScreenMedia.stopRoom(current.begin.id());
        current=null;
    }

    private static boolean present(Transfer t) {
        var mc=Minecraft.getInstance();
        var pos=t.begin.pos();
        return mc.player!=null && mc.player.isAlive() && mc.level==t.level && t.level.hasChunkAt(pos)
                && withinRoomExtent(mc.player.blockPosition(),pos)
                && !t.display.isRemoved() && t.level.getBlockEntity(pos)==t.display;
    }

    // A Skygrid room is 32 blocks per side. After a same-dimension teleport the old chunk
    // can still be in the client cache until its unload packet arrives.
    private static boolean withinRoomExtent(BlockPos player,BlockPos screen) {
        return Math.abs(player.getX()-screen.getX())<32 && Math.abs(player.getY()-screen.getY())<32
                && Math.abs(player.getZ()-screen.getZ())<32;
    }

    private static void receive(Connection sender, RoomMediaNet.View message) {
        var mc=Minecraft.getInstance();
        if(mc.getConnection()==null || mc.getConnection().getConnection()!=sender) return;
        if(sender!=connection) { disconnect(); connection=sender; }
        if(message instanceof RoomMediaNet.Notice notice) {
            if(mc.player!=null && notice.message().length()<=256)
                mc.player.displayClientMessage(Component.literal(notice.message()),true);
            return;
        }
        if(message instanceof RoomMediaNet.Begin begin) {
            begin(begin);
            return;
        }
        Transfer t=current;
        if(t==null) return;
        if(!present(t)) { forget(); return; }
        if(message instanceof RoomMediaNet.End end && end.id().equals(t.begin.id())) forget();
        else if(message instanceof RoomMediaNet.Part part && part.id().equals(t.begin.id())) part(t,part);
        else if(message instanceof RoomMediaNet.Clock clock && clock.id().equals(t.begin.id()) && t.ready) {
            long start=clock.startGameTime();
            long age=t.level.getGameTime()-start;
            if(start<0 || age < -120 || age>7200 || (t.start>=0 && t.start!=start)) { forget(); return; }
            t.start=start;
            t.deadline=ticks+120;
            ScreenMedia.startRoomClock(t.begin.id(),start);
        }
    }

    private static void begin(RoomMediaNet.Begin begin) {
        var mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null || !mc.player.isAlive()
                || !mc.level.dimension().location().equals(begin.dimension()) || !PocketService.inPocket(mc.level)
                || begin.length()<=12 || begin.length()>MediaDownload.MAX_BYTES
                || begin.durationMs()<0 || begin.durationMs()>60_000) return;
        UUID lastStopped=stopped.get(begin.roomId());
        if(begin.id().equals(lastStopped) || (lastStopped==null && stopped.size()>=PocketLedger.MAX_POCKETS)) return;
        var pos=begin.pos();
        if(!withinRoomExtent(mc.player.blockPosition(),pos) || !mc.level.hasChunkAt(pos)
                || !(mc.level.getBlockEntity(pos) instanceof CameraDevices.Device screen)
                || !(screen.getBlockState().getBlock() instanceof WallMonitorBlock)) return;
        stopped.remove(begin.roomId());
        if(current!=null && current.begin.id().equals(begin.id())) {
            if(!current.begin.equals(begin) || !present(current)) { forget(); return; }
        } else {
            forget();
            current=new Transfer(begin,mc.level,screen);
        }
        if(current.offset==0) PacketDistributor.sendToServer(new RoomMediaNet.Receive(begin.id()));
    }

    private static void part(Transfer t,RoomMediaNet.Part part) {
        byte[] chunk=part.bytes();
        int remaining=t.begin.length()-t.offset;
        if(part.offset()!=t.offset || chunk.length==0 || chunk.length>RoomMediaNet.CHUNK_BYTES || chunk.length>remaining) {
            forget(); return;
        }
        if(t.bytes==null) t.bytes=new byte[t.begin.length()];
        System.arraycopy(chunk,0,t.bytes,t.offset,chunk.length);
        t.offset+=chunk.length;
        t.deadline=ticks+120;
        if(t.offset!=t.begin.length()) return;
        byte[] complete=t.bytes;
        t.bytes=null;
        t.deadline=ticks+3600;
        ScreenMedia.prepareRoom(t.begin.pos(),t.begin.id(),complete,clip -> {
            if(current!=t || !present(t)) return;
            if(clip.duration()!=t.begin.durationMs() || clip.loop()!=t.begin.loop()) { forget(); return; }
            t.ready=true;
            PacketDistributor.sendToServer(new RoomMediaNet.Prepared(t.begin.id()));
        });
    }

    private RoomMediaClient() {}
}
