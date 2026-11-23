import java.io.*;
import java.lang.reflect.*;
import java.util.Arrays;

public class SourceAac {
    public static void main(String[] args) throws Exception {
        var out=new BufferedOutputStream(System.out); System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        byte[] a=System.in.readAllBytes();
        boolean modern=args[0].equals("jaad094");
        Class<?> type=Class.forName("net.sourceforge.jaad.aac.Decoder");
        Class<?> buf=Class.forName(modern?"net.sourceforge.jaad.SampleBuffer":"net.sourceforge.jaad.aac.SampleBuffer");
        int objectType=1+((a[2]&0xc0)>>6), freq=(a[2]>>2)&15, ch=(a[2]&1)<<2|(a[3]>>6)&3;
        byte[] asc={(byte)(objectType<<3|freq>>1),(byte)((freq&1)<<7|ch<<3)};
        Object decoder=modern?type.getMethod("create",byte[].class).invoke(null,(Object)asc):type.getConstructor(byte[].class).newInstance((Object)asc);
        Method decode=Arrays.stream(type.getMethods()).filter(m->m.getName().equals("decodeFrame")).findFirst().orElseThrow();
        for(int i=0;i<a.length;){
            int size=((a[i+3]&3)<<11)|((a[i+4]&255)<<3)|((a[i+5]&255)>>5);
            if(size<=7 || i+size>a.length)throw new IOException("ADTS");
            byte[] frame=Arrays.copyOfRange(a,i+7,i+size);i+=size;
            Object output=buf.getConstructor().newInstance();
            buf.getMethod("setBigEndian",boolean.class).invoke(output,false);
            decode.invoke(decoder,frame,output);
            byte[] pcm=(byte[])buf.getMethod("getData").invoke(output);
            out.write(pcm);
        }
        out.flush();
    }
}
