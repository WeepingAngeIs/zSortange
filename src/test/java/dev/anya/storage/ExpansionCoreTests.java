package dev.anya.storage;

import dev.anya.storage.images.PngSanitizer;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

public final class ExpansionCoreTests {
    private static int checks;
    private static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void rejected(byte[] png,String why){boolean failed=false;try{PngSanitizer.sanitize(png);}catch(IOException e){failed=true;}ok(failed,why);}
    private static void invalid(ExpansionRules.Options o,String why){boolean failed=false;try{ExpansionRules.validate(o);}catch(IllegalArgumentException e){failed=true;}ok(failed,why);}
    private static byte[] chunk(String key,byte[] payload)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);d.writeInt(payload.length);byte[] type=key.getBytes(java.nio.charset.StandardCharsets.US_ASCII);d.write(type);d.write(payload);CRC32 crc=new CRC32();crc.update(type);crc.update(payload);d.writeInt((int)crc.getValue());return out.toByteArray();}
    private static byte[] insert(byte[] source,int pos,byte[] add){byte[] result=new byte[source.length+add.length];System.arraycopy(source,0,result,0,pos);System.arraycopy(add,0,result,pos,add.length);System.arraycopy(source,pos,result,pos+add.length,source.length-pos);return result;}
    private static byte[] withHeader(byte[] png,int width,int height,int depth,int type,int interlace)throws IOException{
        byte[] h=ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte)depth).put((byte)type).put((byte)0).put((byte)0).put((byte)interlace).array();byte[] c=chunk("IHDR",h);byte[] result=png.clone();System.arraycopy(c,0,result,8,c.length);return result;
    }
    public static void run()throws Exception{
        ExpansionRules.Options o=new ExpansionRules.Options();ExpansionRules.validate(o);ok(!o.imagesEnabled&&!o.imageSharing&&!o.monsterBackpacks&&!o.pocketStorage,"experimental features are opt-in");ok(o.imageModeration,"moderation defaults on");
        o.stoneArmor=Double.NaN;invalid(o,"reject NaN attributes");o=new ExpansionRules.Options();o.magmaReduction=1;invalid(o,"fire immunity is not configurable");o=new ExpansionRules.Options();o.netheriteKnockback=.11;invalid(o,"knockback is modest");o=new ExpansionRules.Options();o.waterSwimBonus=4;invalid(o,"swim bonus bounded");o=new ExpansionRules.Options();o.imageUploaders.add("../../x");invalid(o,"permission identity is a UUID, not a path");
        for(int n:new int[]{-100,-1,0,9,100000}){o=new ExpansionRules.Options();o.imagesPerPlayer=n;invalid(o,"image quota bounds");}
        byte[] pixels=new byte[32*32*4];for(int i=0;i<pixels.length;i+=4){pixels[i]=(byte)210;pixels[i+1]=(byte)130;pixels[i+2]=(byte)170;pixels[i+3]=(byte)255;}
        byte[] png=PngSanitizer.encode(32,32,pixels);var image=PngSanitizer.sanitize(png);ok(image.width()==32&&image.height()==32,"valid RGBA PNG");ok(Arrays.equals(image.png(),png),"normalization deterministic");
        for(int side:new int[]{32,64,128,256}){byte[] solid=new byte[side*side*4];Arrays.fill(solid,(byte)255);var result=PngSanitizer.sanitize(PngSanitizer.encode(side,side,solid));ok(result.width()==side&&result.height()==side,"bounded dimension roundtrip");}
        rejected(null,"null input");rejected(new byte[0],"empty input");rejected(new byte[65537],"oversized upload before decode");rejected("GIF89a".getBytes(),"GIF denied");
        for(int n=0;n<png.length;n++){rejected(Arrays.copyOf(png,n),"truncated PNG "+n);}
        byte[] corrupt=png.clone();corrupt[corrupt.length-5]^=1;rejected(corrupt,"CRC integrity");
        rejected(withHeader(png,Integer.MAX_VALUE,32,8,6,0),"dimension overflow");rejected(withHeader(png,257,32,8,6,0),"max width");rejected(withHeader(png,31,32,8,6,0),"minimum width");rejected(withHeader(png,32,32,16,6,0),"16-bit unsupported");rejected(withHeader(png,32,32,8,3,0),"palette unsupported");rejected(withHeader(png,32,32,8,6,1),"interlace denied");
        rejected(insert(png,33,chunk("acTL",new byte[8])),"animated PNG denied");rejected(insert(png,33,chunk("IHDR",new byte[13])),"duplicate header denied");rejected(insert(png,33,chunk("EVIL",new byte[0])),"unknown critical chunk denied");
        byte[] metadata=insert(png,33,chunk("tEXt","PrivateComment\0do not retain".getBytes()));ok(Arrays.equals(PngSanitizer.sanitize(metadata).png(),png),"metadata removed");
        byte[] bomb=withHeader(PngSanitizer.encode(256,256,new byte[256*256*4]),32,32,8,6,0);rejected(bomb,"inflated pixel stream cannot exceed declared size");
        rejected(insert(png,png.length,new byte[]{1}),"trailing data denied");
        // Fuzz every bit of a representative image. Acceptances are re-encoded and bounded, never unchecked.
        Random random=new Random(0xA11A03);for(int i=0;i<400;i++){byte[] f=png.clone();int at=random.nextInt(f.length);f[at]^=(byte)(1<<random.nextInt(8));try{var result=PngSanitizer.sanitize(f);ok(result.width()<=256&&result.png().length<=131072,"bounded valid fuzz output");}catch(IOException expected){ok(true,"malformed fuzz safely rejected");}}
        Config.Data old=Config.JSON.fromJson("{\"schema\":2}",Config.Data.class);Config.validate(old);ok(old.expansion!=null&&!old.expansion.imagesEnabled,"old config gains safe defaults");
        System.out.println("PASS: "+checks+" expansion/security assertions");
    }
    public static void main(String[] args)throws Exception{run();}
}
