package dev.anya.storage.appearance;

import com.mojang.blaze3d.platform.NativeImage;
import dev.anya.storage.*;
import dev.anya.storage.images.PngSanitizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Memory-only client cache. No downloaded image is written into the client's filesystem. */
public final class ClientInteriors {
    private record Cached(ResourceLocation texture,long until,int width,int height){}
    private static final LinkedHashMap<UUID,Cached> cache=new LinkedHashMap<>(16,.75f,true);
    private static final Map<UUID,ByteArrayOutputStream> incoming=new HashMap<>();
    private static byte[] upload;
    private static int uploadMenu,uploadSession;
    private static long requestAt,uploadUntil;
    public static ExpansionPackets.Look current;
    public static void message(String s){var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(Component.literal("[Anya Interiors] "+s),false);}
    public static void drop(List<Path> paths){
        if(current==null||!current.wardrobe()||!ClientLooks.options.images){message("Open the backpack Wardrobe and enable private images first.");return;}
        if(paths.size()!=1){message("Drop exactly one PNG file.");return;}
        Path path=paths.getFirst();
        try{
            if(!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>PngSanitizer.MAX_INPUT)throw new IOException("Choose a regular PNG file up to 64 KiB.");
            byte[] bytes;try(InputStream in=Files.newInputStream(path)){bytes=in.readNBytes(PngSanitizer.MAX_INPUT+1);}
            PngSanitizer.sanitize(bytes);upload=bytes;uploadMenu=current.menu();uploadSession=current.session();uploadUntil=System.currentTimeMillis()+30000;
            send(new ExpansionPackets.ImageWire(uploadMenu,uploadSession,0,current.uuid(),0,bytes.length,new byte[0]));
            message("Requesting a private upload. The server may require operator permission.");
        }catch(IOException e){message("PNG rejected. Use non-interlaced 8-bit RGB/RGBA, 32..256 pixels per side, at most 64 KiB.");upload=null;}
    }
    public static void accept(ExpansionPackets.ImageReply p){
        if(p.kind()==0){
            if(upload==null||current==null||current.menu()!=uploadMenu||current.session()!=uploadSession||System.currentTimeMillis()>uploadUntil){upload=null;return;}
            for(int at=0,seq=0;at<upload.length;at+=16384,seq++)send(new ExpansionPackets.ImageWire(uploadMenu,uploadSession,1,p.id(),seq,upload.length,Arrays.copyOfRange(upload,at,Math.min(upload.length,at+16384))));
            upload=null;return;
        }
        if(p.kind()==2){upload=null;message(p.info());return;}
        if(p.kind()==3){forget(p.id());return;}
        if(p.kind()==4){Cached old=cache.get(p.id());if(old!=null)cache.put(p.id(),new Cached(old.texture,System.currentTimeMillis()+10000,old.width,old.height));return;}
        if(p.kind()==5){message(p.info());requestAt=0;return;}
        if(p.kind()!=1||!ClientLooks.options.images||current==null||!current.image().equals(p.id())||p.parts()<1||p.parts()>8||p.part()<0||p.part()>=p.parts())return;
        try{
            if(p.part()==0){if(incoming.size()>=2)incoming.clear();incoming.put(p.id(),new ByteArrayOutputStream());}
            ByteArrayOutputStream data=incoming.get(p.id());if(data==null||data.size()!=p.part()*16384||data.size()+p.bytes().length>PngSanitizer.MAX_OUTPUT){incoming.remove(p.id());return;}
            data.write(p.bytes());
            if(p.part()+1==p.parts()){
                incoming.remove(p.id());byte[] png=PngSanitizer.canonical(data.toByteArray()).png();
                // The strict Java validator runs BEFORE the bounded native texture decode.
                NativeImage image=NativeImage.read(new ByteArrayInputStream(png));ResourceLocation id=ExpansionPackets.id("private_interior/"+p.id());
                forget(p.id());Minecraft.getInstance().getTextureManager().register(id,new DynamicTexture(image));cache.put(p.id(),new Cached(id,System.currentTimeMillis()+10000,image.getWidth(),image.getHeight()));
                while(cache.size()>8)forget(cache.keySet().iterator().next());
            }
        }catch(IOException|RuntimeException e){incoming.remove(p.id());message("Interior download rejected; the built-in artwork is still available.");}
    }
    public static void look(ExpansionPackets.Look look){current=look;requestAt=0;}
    public static void tick(){
        long now=System.currentTimeMillis();if(upload!=null&&now>uploadUntil)upload=null;
        if(!ClientLooks.options.images||current==null||current.image().equals(ExpansionPackets.NONE)||now<requestAt)return;
        requestAt=now+5000;boolean has=cache.containsKey(current.image());send(new ExpansionPackets.ImageWire(current.menu(),current.session(),2,current.image(),has?1:0,0,new byte[0]));
    }
    public static ResourceLocation texture(){if(!ClientLooks.options.images||current==null)return null;Cached c=cache.get(current.image());return c!=null&&c.until>System.currentTimeMillis()?c.texture:null;}
    public static int[] size(){Cached c=current==null?null:cache.get(current.image());return c==null?new int[]{1,1}:new int[]{c.width,c.height};}
    private static void send(ExpansionPackets.ImageWire p){if(Minecraft.getInstance().getConnection()!=null)PacketDistributor.sendToServer(p);}
    private static void forget(UUID id){Cached c=cache.remove(id);incoming.remove(id);if(c!=null)Minecraft.getInstance().getTextureManager().release(c.texture);}
    public static void clear(){for(UUID id:new ArrayList<>(cache.keySet()))forget(id);incoming.clear();upload=null;current=null;requestAt=0;}
    private ClientInteriors(){}
}
