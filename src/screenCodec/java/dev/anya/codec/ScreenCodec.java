package dev.anya.codec;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.jcodec.api.FrameGrab;
import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.aac.Decoder;
import org.jcodec.common.Codec;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack;
import org.jcodec.scale.ColorUtil;
import org.w3c.dom.Node;

/** Runs in a short-lived, heap-limited JVM, reads only stdin and emits a bounded pixel/PCM protocol. */
public final class ScreenCodec {
    static final int MAX_BYTES = 16 << 20, MAX_DURATION = 60_000, MAX_PIXELS = 12_000_000;
    private final DataOutputStream out;
    private int width, height, sent;

    private ScreenCodec(OutputStream output) { out = new DataOutputStream(new BufferedOutputStream(output)); }

    public static void main(String[] args) {
        var codec = new ScreenCodec(System.out);
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        ImageIO.setUseCache(false);
        try {
            byte[] input = System.in.readNBytes(MAX_BYTES + 1);
            require(input.length > 12 && input.length <= MAX_BYTES);
            int duration = input[4] == 'f' && input[5] == 't' && input[6] == 'y' && input[7] == 'p'
                    ? codec.mp4(input) : codec.image(input);
            require(codec.sent > 0);
            codec.out.writeByte(0);
            codec.out.writeInt(duration);
            codec.out.flush();
        } catch (Throwable failure) { System.exit(2); }
    }

    private void header(int w, int h, int frames) throws IOException {
        require(w > 0 && h > 0 && w <= 4096 && h <= 4096 && (long)w * h <= 8_400_000 && frames >= 1 && frames <= 1201);
        double scale = Math.min(1, Math.min(640d / Math.max(w,h), Math.sqrt((double)MAX_PIXELS / frames / w / h)));
        width = Math.max(1, (int)(w * scale)); height = Math.max(1, (int)(h * scale));
        out.writeInt(0x41535031);
        out.writeInt(width); out.writeInt(height);
    }

    private void frame(int millis, BufferedImage image) throws IOException {
        require((long)++sent * width * height <= MAX_PIXELS && millis >= 0 && millis <= MAX_DURATION);
        out.writeByte(1); out.writeInt(millis);
        int[] row = new int[image.getWidth()];
        for (int y=0; y<height; y++) {
            image.getRGB(0, y * image.getHeight() / height, row.length, 1, row, 0, row.length);
            for (int x=0; x<width; x++) out.writeInt(row[x * row.length / width]);
        }
    }

    private int mp4(byte[] data) throws Exception {
        try (var demux = MP4Demuxer.createRawMP4Demuxer(channel(data)); var source = channel(data)) {
            var tracks = demux.getVideoTracks();
            require(tracks.size() == 1 && demux.getAudioTracks().size() <= 1);
            var meta = tracks.get(0).getMeta();
            require(meta.getCodec() == Codec.H264 && meta.getTotalFrames() > 0 && meta.getTotalFrames() <= 3600);
            int duration = (int)Math.ceil(meta.getTotalDuration() * 1000);
            require(duration > 0 && duration <= MAX_DURATION);
            var size = meta.getVideoCodecMeta().getSize();
            require(size.getWidth() <= 1920 && size.getHeight() <= 1920 && (long)size.getWidth()*size.getHeight() <= 2_100_000);
            var orientation = meta.getOrientation();
            boolean turned = orientation == org.jcodec.common.DemuxerTrackMeta.Orientation.D_90
                    || orientation == org.jcodec.common.DemuxerTrackMeta.Orientation.D_270;
            header(turned ? size.getHeight() : size.getWidth(), turned ? size.getWidth() : size.getHeight(),
                    1 + (duration + 99) / 100);
            FrameGrab grab = FrameGrab.createFrameGrab(source);
            BitSet slots = new BitSet();
            double start = Double.NaN;
            for (int i = 0; i < meta.getTotalFrames(); i++) {
                var frame = grab.getNativeFrameWithMetadata();
                if (frame == null) break;
                if (Double.isNaN(start)) start = frame.getTimestamp();
                int time = Math.max(0, (int)Math.round((frame.getTimestamp() - start) * 1000));
                if (time >= duration || slots.get(time / 100)) continue;
                slots.set(time / 100);
                Picture pic = frame.getPicture();
                require(pic.getWidth() <= 2048 && pic.getHeight() <= 2048);
                Picture rgb = Picture.create(pic.getWidth(), pic.getHeight(), ColorSpace.RGB);
                ColorUtil.getTransform(pic.getColor(), ColorSpace.RGB).transform(pic, rgb);
                var colors = rgb.getPlaneData(0);
                var crop = pic.getCrop();
                int left = crop == null ? 0 : crop.getX(), top = crop == null ? 0 : crop.getY();
                int w = pic.getCroppedWidth(), h = pic.getCroppedHeight();
                BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                for(int y = 0; y < height; y++) for(int x = 0; x < width; x++) {
                    int sx = x * w / width, sy = y * h / height;
                    if (orientation == org.jcodec.common.DemuxerTrackMeta.Orientation.D_90) { sx=y*w/height; sy=h-1-x*h/width; }
                    else if (orientation == org.jcodec.common.DemuxerTrackMeta.Orientation.D_270) { sx=w-1-y*w/height; sy=x*h/width; }
                    else if (orientation == org.jcodec.common.DemuxerTrackMeta.Orientation.D_180) { sx=w-1-sx; sy=h-1-sy; }
                    int p = ((top + sy) * pic.getWidth() + left + sx)*3;
                    small.setRGB(x, y, (colors[p]+128)<<16 | (colors[p+1]+128)<<8 | (colors[p+2]+128));
                }
                frame(time, small);
            }
            if (!demux.getAudioTracks().isEmpty())
                audio(demux.getAudioTracks().get(0), duration, demux.getMovie().getTimescale());
            return duration;
        }
    }

    private static ByteBufferSeekableByteChannel channel(byte[] data) {
        return ByteBufferSeekableByteChannel.readFromByteBuffer(ByteBuffer.wrap(data));
    }

    private void audio(AbstractMP4DemuxerTrack track, int duration, int movieTimescale) throws Exception {
        var meta = track.getMeta();
        require(meta.getCodec() == Codec.AAC && meta.getCodecPrivate() != null);
        var config = meta.getCodecPrivate().duplicate();
        require(config.remaining() > 0 && config.remaining() <= 128);
        byte[] asc = new byte[config.remaining()];
        config.get(asc);
        var decoder = Decoder.create(asc);
        var audio = new SampleBuffer();
        audio.setBigEndian(false);
        require(decoder != null && track.getTimescale() > 0 && movieTimescale > 0);
        double trimSeconds = 0, delaySeconds = 0;
        var edits = track.getBox().getEdits();
        if (edits != null && !edits.isEmpty()) {
            require(edits.size() == 1 || edits.size() == 2 && edits.get(0).getMediaTime() == -1);
            if (edits.size() == 2) delaySeconds = edits.get(0).getDuration() / (double)movieTimescale;
            var edit = edits.get(edits.size()-1);
            require(edit.getRate()==1 && edit.getMediaTime() >= 0);
            trimSeconds = edit.getMediaTime() / (double)track.getTimescale();
            require(trimSeconds <= 60 && delaySeconds >= 0 && delaySeconds <= 60);
        }
        var pcm = new ByteArrayOutputStream();
        int rate = 0, packets = 0, trim = 0;
        for (Packet packet; (packet = track.nextFrame()) != null;) {
            require(++packets <= 6500);
            var raw = packet.getData();
            require(raw.remaining() > 0 && raw.remaining() <= 65536);
            byte[] encoded = new byte[raw.remaining()]; raw.get(encoded);
            decoder.decodeFrame(encoded, audio);
            if (audio.getData().length == 0) continue;
            int channels = audio.getChannels();
            require(channels >= 1 && channels <= 2 && audio.getBitsPerSample() == 16 && !audio.isBigEndian()
                    && audio.getSampleRate() >= 8000 && audio.getSampleRate() <= 48000
                    && (rate == 0 || rate == audio.getSampleRate()));
            if (rate == 0) {
                rate = audio.getSampleRate();
                trim = (int)Math.round(trimSeconds*rate);
                pcm.write(new byte[2*(int)Math.round(delaySeconds*rate)]);
            }
            var data = ByteBuffer.wrap(audio.getData()).order(ByteOrder.LITTLE_ENDIAN);
            require(data.remaining() % (2*channels) == 0);
            while (data.hasRemaining()) {
                int sample = data.getShort();
                if (channels == 2) sample = (sample + data.getShort()) / 2;
                if (trim > 0) { trim--; continue; }
                pcm.write(sample); pcm.write(sample >>> 8);
            }
            require(pcm.size() <= 6_000_000);
        }
        require(rate != 0 && pcm.size() > 0);
        int length = Math.min(pcm.size(), 2 * (int)((long)rate*duration/1000));
        out.writeByte(2); out.writeInt(rate); out.writeInt(length);
        out.write(pcm.toByteArray(), 0, length);
    }

    private int image(byte[] data) throws Exception {
        boolean gif = data[0]=='G' && data[1]=='I' && data[2]=='F';
        boolean png = (data[0]&255)==137 && data[1]=='P' && data[2]=='N' && data[3]=='G';
        require(gif || png || (data[0]&255)==255 && (data[1]&255)==216);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            var reader = ImageIO.getImageReadersByFormatName(gif ? "gif" : png ? "png" : "jpeg").next();
            try {
                reader.setInput(input, false, false);
                if (!gif) {
                    header(reader.getWidth(0), reader.getHeight(0), 1);
                    frame(0,reader.read(0));
                    return 0;
                }
                int n = reader.getNumImages(true);
                require(n >= 1 && n <= 600);
                Node desc = child(reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0"),"LogicalScreenDescriptor");
                int w = attrInt(desc, "logicalScreenWidth"), h = attrInt(desc, "logicalScreenHeight");
                header(w,h,n);
                var canvas = new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
                int elapsed = 0;
                for (int i=0;i<n;i++) {
                    Node root = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                    Node descriptor = child(root,"ImageDescriptor"), ctl = child(root,"GraphicControlExtension");
                    int left = attrInt(descriptor,"imageLeftPosition"), top = attrInt(descriptor,"imageTopPosition");
                    int iw = reader.getWidth(i), ih = reader.getHeight(i);
                    require(left >= 0 && top >= 0 && iw > 0 && ih > 0 && left+iw <= w && top+ih <= h);
                    String disposal = ctl == null ? "" : ctl.getAttributes().getNamedItem("disposalMethod").getNodeValue();
                    int[] prior = disposal.equals("restoreToPrevious") ? canvas.getRGB(left,top,iw,ih,null,0,iw) : null;
                    var graphics = canvas.createGraphics();
                    try { graphics.drawImage(reader.read(i),left,top,null); } finally { graphics.dispose(); }
                    frame(elapsed,canvas);
                    elapsed += ctl == null ? 100 : Math.max(20,10*attrInt(ctl,"delayTime"));
                    require(elapsed <= MAX_DURATION);
                    if (prior != null) canvas.setRGB(left,top,iw,ih,prior,0,iw);
                    if (disposal.equals("restoreToBackgroundColor")) {
                        Graphics2D clear = canvas.createGraphics();
                        try { clear.setComposite(AlphaComposite.Clear); clear.fillRect(left,top,iw,ih); }
                        finally { clear.dispose(); }
                    }
                }
                return n == 1 ? 0 : elapsed;
            } finally { reader.dispose(); }
        }
    }

    private static Node child(Node parent,String tag) {
        for(Node n=parent.getFirstChild(); n!=null; n=n.getNextSibling()) if(tag.equals(n.getNodeName())) return n;
        return null;
    }
    private static int attrInt(Node node,String key) {
        return Integer.parseInt(node.getAttributes().getNamedItem(key).getNodeValue());
    }
    private static void require(boolean ok) throws IOException { if(!ok) throw new IOException("Unsupported media"); }
}
