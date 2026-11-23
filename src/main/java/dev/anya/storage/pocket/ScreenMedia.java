package dev.anya.storage.pocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** A deliberate local choice on one physical screen. No source information goes on the network or into saves. */
public final class ScreenMedia {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("anyastorage","dynamic/screen_media");
    private static final ResourceLocation SOUND = ResourceLocation.fromNamespaceAndPath("anyastorage","screen_media");
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),Thread.ofPlatform().daemon().name("Storage screen media").factory(),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private static Session current;

    public static void play(BlockPos pos, String address) { start(pos, () -> MediaDownload.fetch(address)); }

    public static void playFile(BlockPos pos, Path file) {
        start(pos, () -> {
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
        });
    }

    private static void start(BlockPos pos, Callable<byte[]> source) {
        clear();
        Minecraft mc=Minecraft.getInstance();
        if (mc.level==null || mc.player==null || !mc.level.hasChunkAt(pos)) return;
        var block=mc.level.getBlockEntity(pos);
        if (block==null || mc.player.distanceToSqr(Vec3.atCenterOf(pos)) > 32*32) return;
        var session=new Session(mc.level,pos.immutable(),block);
        current=session;
        session.task=WORK.submit(() -> {
            try {
                byte[] bytes=source.call();
                if (bytes.length<=12 || bytes.length>MediaDownload.MAX_BYTES) throw new IOException();
                ScreenClip clip=ScreenClip.decode(bytes,session.process);
                mc.execute(() -> {
                    if (current!=session || !present(session)) return;
                    session.clip=clip;
                    session.texture=new DynamicTexture(clip.width(),clip.height(),true);
                    mc.getTextureManager().register(TEXTURE,session.texture);
                    session.lastNanos=System.nanoTime();
                    session.wasPaused=mc.isPaused();
                    session.message=clip.duration()==0 ? "Showing image" : "Playing";
                    draw(session,0);
                    if (clip.audio().length>0) {
                        session.sound=new MediaSound(session.pos,clip);
                        if(!mc.isPaused()) startSound(session);
                    }
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
                && mc.player.distanceToSqr(Vec3.atCenterOf(s.pos)) <= 32*32;
    }
    public static void tick() {
        var s=current;
        if(s==null) return;
        if(!present(s)) { clear(); return; }
        if(s.clip==null || s.clip.duration()==0) return;
        long now=System.nanoTime();
        boolean paused=Minecraft.getInstance().isPaused();
        if(!paused && !s.wasPaused) s.elapsed+=Math.max(0,now-s.lastNanos);
        if(!paused && s.sound!=null && !s.soundStarted) startSound(s);
        s.wasPaused=paused;
        s.lastNanos=now;
        long elapsedMillis=s.elapsed/1_000_000L;
        if(!s.clip.loop() && elapsedMillis>=s.clip.duration()) s.message="Finished";
        draw(s, (int)(s.clip.loop() ? elapsedMillis % s.clip.duration() : Math.min(s.clip.duration(),elapsedMillis)));
    }

    private static void startSound(Session s) {
        Minecraft.getInstance().getSoundManager().play(s.sound);
        s.soundStarted=true;
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
        final BlockEntity block;
        final AtomicReference<Process> process=new AtomicReference<>();
        String message="Loading media…";
        Future<?> task;
        ScreenClip clip;
        DynamicTexture texture;
        MediaSound sound;
        int frame=-1;
        boolean wasPaused, soundStarted;
        long elapsed,lastNanos;
        Session(ClientLevel level,BlockPos pos,BlockEntity block) { this.level=level; this.pos=pos; this.block=block; }
    }

    private static final class MediaSound extends AbstractSoundInstance {
        private final ScreenClip clip;
        MediaSound(BlockPos pos,ScreenClip clip) {
            super(SOUND, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.clip=clip; x=pos.getX()+.5; y=pos.getY()+.5; z=pos.getZ()+.5;
        }
        @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers,Sound sound,boolean looping) {
            return CompletableFuture.completedFuture(new AudioStream() {
                private int offset;
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
