package dev.anya.storage.images;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

/** Deliberately small PNG subset. No ImageIO plugins, URLs, files, metadata or native decoders on server. */
public final class PngSanitizer {
    public static final int MAX_INPUT=65536, MAX_OUTPUT=131072, MAX_SIDE=256, MAX_PIXELS=65536;
    private static final byte[] MAGIC={(byte)137,80,78,71,13,10,26,10};
    public record Image(int width,int height,byte[] png){}
    public static Image sanitize(byte[] input) throws IOException {return sanitize(input,MAX_INPUT);}
    public static Image canonical(byte[] input)throws IOException{return sanitize(input,MAX_OUTPUT);}
    private static Image sanitize(byte[] input,int limit) throws IOException {
        if(input==null||input.length<45||input.length>limit)throw bad("PNG must be 45..65536 bytes");
        ByteBuffer b=ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN);byte[] magic=new byte[8];b.get(magic);
        if(!Arrays.equals(magic,MAGIC))throw bad("Only PNG files are accepted");
        int w=0,h=0,channels=0,chunks=0;boolean header=false,end=false,data=false,dataEnded=false;
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        while(b.hasRemaining()){
            if(++chunks>128||b.remaining()<12)throw bad("Invalid PNG structure");
            int n=b.getInt();if(n<0||n>b.remaining()-8)throw bad("Invalid PNG chunk length");
            byte[] type=new byte[4];b.get(type);String kind=new String(type,java.nio.charset.StandardCharsets.US_ASCII);
            byte[] payload=new byte[n];b.get(payload);int checksum=b.getInt();CRC32 crc=new CRC32();crc.update(type);crc.update(payload);
            if((int)crc.getValue()!=checksum)throw bad("PNG checksum failed");
            if(!header&&!kind.equals("IHDR"))throw bad("PNG header must be first");
            switch(kind){
                case "IHDR" -> {
                    if(header||n!=13)throw bad("Duplicate or invalid PNG header");
                    ByteBuffer p=ByteBuffer.wrap(payload);w=p.getInt();h=p.getInt();int depth=p.get()&255,color=p.get()&255;
                    if(w<32||h<32||w>MAX_SIDE||h>MAX_SIDE||(long)w*h>MAX_PIXELS)throw bad("PNG dimensions must be 32..256 on each side");
                    if(depth!=8||(color!=2&&color!=6)||p.get()!=0||p.get()!=0||p.get()!=0)throw bad("Use a non-interlaced 8-bit RGB or RGBA PNG");
                    channels=color==6?4:3;header=true;
                }
                case "IDAT" -> {if(dataEnded||end)throw bad("PNG image chunks must be contiguous");data=true;compressed.write(payload);}
                case "IEND" -> {if(n!=0||!data||end||b.hasRemaining())throw bad("Invalid PNG ending");end=true;}
                case "acTL","fcTL","fdAT" -> throw bad("Animated PNGs are not accepted");
                default -> {
                    // Unknown critical chunks cannot safely be interpreted. Strip all ancillary metadata.
                    if((type[0]&32)==0)throw bad("Unsupported critical PNG chunk");
                    if(data)dataEnded=true;
                }
            }
        }
        if(!header||!end)throw bad("Truncated PNG");
        int stride=Math.multiplyExact(w,channels),expected=Math.multiplyExact(h,stride+1);
        byte[] raw=new byte[expected];Inflater inflater=new Inflater();inflater.setInput(compressed.toByteArray());int count=0;
        try{
            while(count<raw.length){int n=inflater.inflate(raw,count,raw.length-count);if(n==0)break;count+=n;}
            byte[] extra=new byte[1];int overflow=inflater.inflate(extra);
            if(count!=expected||overflow!=0||!inflater.finished()||inflater.getRemaining()!=0)throw bad("Invalid or oversized PNG pixel stream");
        }catch(DataFormatException e){throw bad("Invalid PNG compression");}finally{inflater.end();}
        byte[] rgba=new byte[Math.multiplyExact(w*h,4)],prior=new byte[stride],row=new byte[stride];int at=0,out=0;
        for(int y=0;y<h;y++){
            int filter=raw[at++]&255;if(filter>4)throw bad("Invalid PNG filter");
            for(int x=0;x<stride;x++){
                int left=x>=channels?row[x-channels]&255:0,up=prior[x]&255,upperLeft=x>=channels?prior[x-channels]&255:0;
                int v=raw[at++]&255;
                v+=switch(filter){case 0->0;case 1->left;case 2->up;case 3->(left+up)/2;default->paeth(left,up,upperLeft);};
                row[x]=(byte)v;
            }
            for(int x=0;x<w;x++){int i=x*channels;rgba[out++]=row[i];rgba[out++]=row[i+1];rgba[out++]=row[i+2];rgba[out++]=channels==4?row[i+3]:(byte)255;}
            byte[] swap=prior;prior=row;row=swap;
        }
        byte[] png=encode(w,h,rgba);if(png.length>MAX_OUTPUT)throw bad("Normalized PNG exceeds 128 KiB; use a smaller image");return new Image(w,h,png);
    }
    public static byte[] encode(int w,int h,byte[] rgba)throws IOException{
        if(w<1||h<1||w>MAX_SIDE||h>MAX_SIDE||(long)w*h*4!=rgba.length)throw bad("Invalid pixels");
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(MAGIC);
        ByteBuffer header=ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte)8).put((byte)6).put((byte)0).put((byte)0).put((byte)0);
        chunk(out,"IHDR",header.array());ByteArrayOutputStream deflated=new ByteArrayOutputStream();
        try(DeflaterOutputStream z=new DeflaterOutputStream(deflated)){for(int y=0;y<h;y++){z.write(0);z.write(rgba,y*w*4,w*4);}}
        chunk(out,"IDAT",deflated.toByteArray());chunk(out,"IEND",new byte[0]);return out.toByteArray();
    }
    private static void chunk(OutputStream out,String type,byte[] data)throws IOException{DataOutputStream d=new DataOutputStream(out);byte[] t=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);d.writeInt(data.length);d.write(t);d.write(data);CRC32 c=new CRC32();c.update(t);c.update(data);d.writeInt((int)c.getValue());}
    private static int paeth(int a,int b,int c){int p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);return pa<=pb&&pa<=pc?a:pb<=pc?b:c;}
    private static IOException bad(String s){return new IOException(s);}
    private PngSanitizer(){}
}
