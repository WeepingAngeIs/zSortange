package dev.anya.storage.pocket;

import dev.anya.storage.appearance.ClientLooks;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** One bounded decoder, texture and positional audio stream for a physical display. */
public final class ScreenMedia {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("anyastorage","dynamic/screen_media");
    private static final SoundEvent SOUND = SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("anyastorage","screen_media"));
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),Thread.ofPlatform().daemon().name("Storage screen media").factory(),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private static Session current;

    public static void play(BlockPos pos, String address) {
        RoomMediaClient.stopForMe(pos);
        start(pos, null, () -> MediaDownload.fetch(address), null);
    }

    public static void playFile(BlockPos pos, Path file) {
        RoomMediaClient.stopForMe(pos);
        start(pos, null, () -> {
            if (file == null || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gif"))
                throw new MediaDownload.Failure("Choose one local GIF file.");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MediaDownload.MAX_BYTES)
                throw new MediaDownload.Failure("Choose a GIF up to 16 MiB.");
            try (var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=stream.readNBytes(MediaDownload.MAX_BYTES + 1);
                if (bytes.length<6 || bytes.length>MediaDownload.MAX_BYTES || bytes[0]!='G' || bytes[1]!='I' || bytes[2]!='F')
                    throw new MediaDownload.Failure("Choose a GIF up to 16 MiB.");
                return bytes;
            }
        }, null);
    }

    static void prepareRoom(BlockPos pos, UUID id, byte[] bytes, Consumer<ScreenClip> ready) {
        start(pos, id, () -> bytes, ready);
    }

    static void startRoomClock(UUID id, long startGameTime) {
        Session session=current;
        if(session==null || !id.equals(session.room) || session.clip==null || session.roomStart>=0) return;
        session.roomStart=startGameTime;
        if(session.level.getGameTime()>=startGameTime) startPresentation(session);
    }

    static void stopRoom(UUID id) { if(current!=null && id.equals(current.room)) clear(); }

    private static void start(BlockPos pos, UUID room, Callable<byte[]> source, Consumer<ScreenClip> ready) {
        clear();
        Minecraft mc=Minecraft.getInstance();
        if (mc.level==null || mc.player==null || !mc.level.hasChunkAt(pos)) return;
        if (!(mc.level.getBlockEntity(pos) instanceof CameraDevices.Device block) || !(block.getBlockState().getBlock() instanceof WallMonitorBlock)
                || (room==null && mc.player.distanceToSqr(Vec3.atCenterOf(pos)) > 32*32)) return;
        var session=new Session(mc.level,pos.immutable(),block,room);
        current=session;
        session.task=WORK.submit(() -> {
            try {
                byte[] bytes=source.call();
                if (bytes.length<=12 || bytes.length>MediaDownload.MAX_BYTES) throw new IOException();
                ScreenClip clip=ScreenClip.decode(bytes,session.process);
                mc.execute(() -> {
                    if (current!=session || !present(session)) return;
                    session.clip=clip;
                    if(ready!=null) ready.accept(clip);
                    else startPresentation(session);
                });
            } catch (Exception error) {
                String safe = error instanceof MediaDownload.Failure ? error.getMessage()
                        : error instanceof java.net.SocketException || error instanceof java.net.UnknownHostException
                            || error instanceof javax.net.ssl.SSLException || error instanceof java.net.SocketTimeoutException
                        ? "Could not connect securely to that media server."
                        : "Could not play this file. Use a PNG, JPEG, GIF or H.264/AAC MP4 up to 16 MiB and 60 seconds.";
                mc.execute(() -> { if(current==session) session.message=safe; });
            }
        });
    }

    public static boolean active(BlockPos pos) { return current!=null && current.pos.equals(pos); }
    public static BlockPos activePosition() { return current!=null && present(current) ? current.pos : null; }
    public static String status(BlockPos pos) { return active(pos) ? current.message : ""; }
    public static ResourceLocation texture(BlockPos pos) { return active(pos) && current.texture!=null ? TEXTURE : null; }
    public static float aspectRatio(BlockPos pos) {
        return active(pos) && current.clip!=null ? (float)current.clip.width()/current.clip.height() : 16f/9;
    }
    public static void stop(BlockPos pos) { if (active(pos)) clear(); }
    public static void clear() {
        var s=current;
        current=null;
        if(s==null) return;
        if(s.task!=null) s.task.cancel(true);
        var process=s.process.getAndSet(null);
        if(process!=null) process.destroyForcibly();
        var mc=Minecraft.getInstance();
        if(s.sound!=null) mc.getSoundManager().stop(s.sound);
        if(s.texture!=null) mc.getTextureManager().release(TEXTURE);
    }

    private static boolean present(Session s) {
        var mc=Minecraft.getInstance();
        return mc.level==s.level && mc.player!=null && s.level.hasChunkAt(s.pos)
                && !s.block.isRemoved() && s.level.getBlockEntity(s.pos)==s.block
                && (s.room!=null || mc.player.distanceToSqr(Vec3.atCenterOf(s.pos)) <= 32*32);
    }

    public static float volume() {
        float value=ClientLooks.options.screenVolume;
        return Float.isFinite(value) ? Math.clamp(value,0f,1f) : 1f;
    }

    public static void volume(float value) {
        if(!Float.isFinite(value)) return;
        float bounded=Math.clamp(value,0f,1f);
        if(bounded==ClientLooks.options.screenVolume) return;
        ClientLooks.options.screenVolume=bounded;
        ClientLooks.saveOptions();
    }

    private static void startPresentation(Session s) {
        var mc=Minecraft.getInstance();
        s.texture=new DynamicTexture(s.clip.width(),s.clip.height(),true);
        mc.getTextureManager().register(TEXTURE,s.texture);
        s.lastNanos=System.nanoTime();
        s.wasPaused=mc.isPaused();
        s.message=s.clip.duration()==0 ? "Showing image" : "Playing";
        displayAt(s,elapsedMillis(s));
    }

    private static long elapsedMillis(Session s) {
        return s.room==null ? s.elapsed/1_000_000L : 50L*Math.max(0L,s.level.getGameTime()-s.roomStart);
    }

    public static void tick() {
        var s=current;
        if(s==null) return;
        if(!present(s)) { clear(); return; }
        if(s.clip==null) return;
        if(s.texture==null) {
            if(s.roomStart>=0 && s.level.getGameTime()>=s.roomStart) startPresentation(s);
            return;
        }
        if(s.clip.duration()==0) return;
        long now=System.nanoTime();
        boolean paused=Minecraft.getInstance().isPaused();
        if(!paused && !s.wasPaused) s.elapsed+=Math.max(0,now-s.lastNanos);
        s.wasPaused=paused;
        s.lastNanos=now;
        displayAt(s,elapsedMillis(s));
    }

    private static void displayAt(Session s,long elapsed) {
        var mc=Minecraft.getInstance();
        int duration=s.clip.duration();
        int position=(int)(s.clip.loop() && duration>0 ? elapsed%duration : Math.min(duration,elapsed));
        draw(s,position);
        boolean finished=duration>0 && !s.clip.loop() && elapsed>=duration;
        if(finished) s.message="Finished";
        if(s.clip.audio().length==0) return;
        boolean audible=volume()>0 && mc.options.getSoundSourceVolume(SoundSource.BLOCKS)>0
                && mc.options.getSoundSourceVolume(SoundSource.MASTER)>0;
        int offset=s.clip.audioOffset(position);
        // When the server lags or corrects its clock, reseek the audio to the shared picture.
        long soundMillis=s.soundAtMillis+(s.elapsed-s.soundAtNanos)/1_000_000L;
        boolean drifted=s.room!=null && Math.abs(position-soundMillis)>350;
        if(s.sound!=null && (!audible || finished || drifted || offset>=s.clip.audio().length)) {
            mc.getSoundManager().stop(s.sound);
            s.sound=null;
        }
        if(s.sound==null && audible && !finished && offset<s.clip.audio().length && !mc.isPaused()) {
            s.soundAtMillis=position;
            s.soundAtNanos=s.elapsed;
            s.sound=new MediaSound(s.block,s.clip,offset,s.room!=null);
            mc.getSoundManager().play(s.sound);
        }
    }

    private static void draw(Session s,int time) {
        var frames=s.clip.frames();
        int low=0,high=frames.size()-1;
        while(low<high) {
            int mid=(low+high+1)>>>1;
            if(frames.get(mid).millis()<=time) low=mid; else high=mid-1;
        }
        if(s.frame==low) return;
        var image=s.texture.getPixels();
        if(image==null) return;
        int[] colors=frames.get(low).abgr();
        int w=s.clip.width(), h=s.clip.height();
        for(int y=0,i=0;y<h;y++) for(int x=0;x<w;x++,i++) image.setPixelRGBA(x,y,colors[i]);
        s.texture.upload();
        s.frame=low;
    }

    private static final class Session {
        final ClientLevel level;
        final BlockPos pos;
        final CameraDevices.Device block;
        final UUID room;
        long roomStart=-1;
        final AtomicReference<Process> process=new AtomicReference<>();
        String message="Loading media…";
        Future<?> task;
        ScreenClip clip;
        DynamicTexture texture;
        MediaSound sound;
        int frame=-1;
        boolean wasPaused;
        long elapsed,lastNanos,soundAtNanos,soundAtMillis;
        Session(ClientLevel level,BlockPos pos,CameraDevices.Device block,UUID room) {
            this.level=level; this.pos=pos; this.block=block; this.room=room;
        }
    }

    private static final class MediaSound extends AbstractTickableSoundInstance {
        private final ScreenClip clip;
        private final int offset;
        private final CameraDevices.Device display;
        MediaSound(CameraDevices.Device display,ScreenClip clip,int offset,boolean room) {
            super(SOUND, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.display=display; this.clip=clip; this.offset=offset;
            if(room) attenuation=SoundInstance.Attenuation.NONE;
            updateSource();
        }
        private void updateSource() {
            Vec3 center=CameraFeed.screenCenter(display);
            x=center.x; y=center.y; z=center.z;
            volume=ScreenMedia.volume();
        }
        @Override public void tick() { updateSource(); }
        @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers,Sound sound,boolean looping) {
            return CompletableFuture.completedFuture(new AudioStream() {
                private int offset=MediaSound.this.offset;
                @Override public AudioFormat getFormat() { return new AudioFormat(clip.rate(),16,1,true,false); }
                @Override public ByteBuffer read(int requested) {
                    if(offset>=clip.audio().length) return null;
                    int length=Math.min(clip.audio().length-offset,requested);
                    ByteBuffer block=ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
                    block.put(clip.audio(),offset,length); offset+=length;
                    return block.flip();
                }
                @Override public void close() { offset=clip.audio().length; }
            });
        }
    }
    private ScreenMedia() {}
}
