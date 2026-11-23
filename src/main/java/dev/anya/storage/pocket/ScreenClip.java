package dev.anya.storage.pocket;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

record ScreenClip(int width, int height, List<Frame> frames, int duration, byte[] audio, int rate, boolean loop) {
    record Frame(int millis, int[] abgr) {}

    int audioOffset(int millis) {
        return (int)Math.min(audio.length,(long)Math.max(0,millis)*rate/1000*2);
    }
    private static final ScheduledExecutorService WATCH = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("Storage screen decoder timeout").factory());

    static ScreenClip decode(byte[] source, AtomicReference<Process> active) throws IOException {
        Path directory = Files.createTempDirectory("anya-screen-codec-");
        Path jar = directory.resolve("screen-codec.jar");
        Process child = null;
        try {
            try (var resource = ScreenClip.class.getResourceAsStream("/META-INF/anya-media/screen-codec.jar")) {
                if (resource == null) throw new IOException("Decoder unavailable");
                Files.copy(resource,jar);
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            boolean windows = System.getProperty("os.name","").startsWith("Windows");
            Path java = Path.of(System.getProperty("java.home"),"bin",windows ? "javaw.exe" : "java");
            var builder = new ProcessBuilder(java.toString(), "-Xmx160m", "-Xss512k", "-XX:MaxDirectMemorySize=24m",
                    "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError",
                    "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + directory, "-jar", jar.toString());
            builder.directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            String systemRoot = builder.environment().get("SystemRoot");
            builder.environment().clear();
            if (windows && systemRoot != null) builder.environment().put("SystemRoot",systemRoot);
            child = builder.start();
            active.set(child);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            var kill = WATCH.schedule(child::destroyForcibly, 90, TimeUnit.SECONDS);
            try {
                try (var input = child.getOutputStream()) { input.write(source); }
                try (var data = new DataInputStream(new BufferedInputStream(child.getInputStream()))) {
                    return read(data, source[0]=='G' && source[1]=='I' && source[2]=='F');
                }
            } finally { kill.cancel(false); }
        } finally {
            if (child != null) {
                active.compareAndSet(child,null);
                child.destroyForcibly();
                try { child.toHandle().onExit().get(2,TimeUnit.SECONDS); }
                catch (Exception ignored) { /* Retry normal temporary-file cleanup on JVM exit. */ }
            }
            try { Files.deleteIfExists(jar); Files.deleteIfExists(directory); }
            catch (IOException busy) { directory.toFile().deleteOnExit(); jar.toFile().deleteOnExit(); }
        }
    }

    private static ScreenClip read(DataInputStream in, boolean loop) throws IOException {
        if (in.readInt() != 0x41535031) throw invalid();
        int w=in.readInt(), h=in.readInt(), rate=0;
        if (w<=0 || h<=0 || w>640 || h>640) throw invalid();
        byte[] pcm = {};
        var frames = new ArrayList<Frame>();
        int total=0;
        while (true) {
            switch (in.readUnsignedByte()) {
                case 0 -> {
                    int duration = in.readInt();
                    if (frames.isEmpty() || duration<0 || duration>60_000) throw invalid();
                    frames.sort(Comparator.comparingInt(Frame::millis));
                    return new ScreenClip(w,h,List.copyOf(frames),duration,pcm,rate,loop);
                }
                case 1 -> {
                    int time = in.readInt();
                    total += w*h;
                    if (time<0 || time>60_000 || total>12_000_000 || frames.size()>=1201) throw invalid();
                    int[] pixels = new int[w*h];
                    for (int i=0;i<pixels.length;i++) {
                        int color = in.readInt();
                        pixels[i] = (color & 0xff00ff00) | ((color & 255)<<16) | ((color>>>16)&255);
                    }
                    frames.add(new Frame(time,pixels));
                }
                case 2 -> {
                    if (pcm.length!=0) throw invalid();
                    rate=in.readInt();
                    int length=in.readInt();
                    if(rate<8000 || rate>48000 || length<=0 || length>6_000_000 || (length&1)!=0) throw invalid();
                    pcm=new byte[length]; in.readFully(pcm);
                }
                default -> throw invalid();
            }
        }
    }

    private static IOException invalid() { return new IOException("Unsupported media stream"); }
}
