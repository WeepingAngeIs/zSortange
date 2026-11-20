package dev.anya.storage;

import dev.anya.storage.images.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import java.nio.*;

public final class ImageProtocolTests {
    private static int count;
    private static void check(boolean value,String reason){count++;if(!value)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        ImageRateGate gate=new ImageRateGate();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        for(int i=0;i<12;i++)check(gate.allow(a,1),"legitimate burst");
        for(int i=0;i<1000;i++)check(!gate.allow(a,1),"per-player flood rejected");
        check(gate.allow(b,1),"another player remains eligible");check(gate.allow(a,2),"rate window recovers");gate.clear();
        for(int i=0;i<128;i++)check(gate.allow(UUID.randomUUID(),8),"bounded global burst");
        check(!gate.allow(UUID.randomUUID(),8),"global flood rejected");check(gate.allow(a,9),"global window recovers");
        // Exercise all five PNG unfilters with real RGB and RGBA rows, not only encoder filter 0.
        for(int channels:new int[]{3,4})for(int filter=0;filter<=4;filter++){
            int w=32,h=32,stride=w*channels;byte[] raw=new byte[h*(stride+1)],rgba=new byte[w*h*4];
            byte[] prior=new byte[stride];int at=0;
            for(int y=0;y<h;y++){
                raw[at++]=(byte)filter;byte[] row=new byte[stride];
                for(int x=0;x<stride;x++)row[x]=(byte)(x*7+y*13);
                for(int x=0;x<stride;x++){
                    int left=x>=channels?row[x-channels]&255:0,up=prior[x]&255,ul=x>=channels?prior[x-channels]&255:0;
                    int predicted=switch(filter){case 0->0;case 1->left;case 2->up;case 3->(left+up)/2;default->paeth(left,up,ul);};raw[at++]=(byte)((row[x]&255)-predicted);
                }
                for(int x=0;x<w;x++){int j=(y*w+x)*4;rgba[j]=row[x*channels];rgba[j+1]=row[x*channels+1];rgba[j+2]=row[x*channels+2];rgba[j+3]=channels==4?row[x*channels+3]:(byte)255;}prior=row;
            }
            ByteArrayOutputStream zip=new ByteArrayOutputStream();try(DeflaterOutputStream out=new DeflaterOutputStream(zip)){out.write(raw);}
            ByteArrayOutputStream png=new ByteArrayOutputStream();png.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
            chunk(png,"IHDR",ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte)8).put((byte)(channels==4?6:2)).put(new byte[3]).array());chunk(png,"IDAT",zip.toByteArray());chunk(png,"IEND",new byte[0]);
            check(Arrays.equals(PngSanitizer.sanitize(png.toByteArray()).png(),PngSanitizer.encode(w,h,rgba)),"exact pixels for filter "+filter+" channels "+channels);
        }
        System.out.println("PASS: "+count+" image protocol/rate/filter assertions");
    }
    private static int paeth(int a,int b,int c){int p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);return pa<=pb&&pa<=pc?a:pb<=pc?b:c;}
    private static void chunk(OutputStream out,String name,byte[] data)throws IOException{byte[] type=name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);DataOutputStream d=new DataOutputStream(out);d.writeInt(data.length);d.write(type);d.write(data);CRC32 crc=new CRC32();crc.update(type);crc.update(data);d.writeInt((int)crc.getValue());}
}
