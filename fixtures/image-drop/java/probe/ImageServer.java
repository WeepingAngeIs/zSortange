package probe;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import dev.anya.storage.images.InteriorImages;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.UUID;

@Mod("imageprobe")
@EventBusSubscriber(modid="imageprobe")
public final class ImageServer {
    static boolean opened,checked;
    static final Path receipt=Path.of("entry.txt");
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    @SubscribeEvent public static void start(ServerStartedEvent e)throws Exception{
        var s=e.getServer();
        var dir=s.getWorldPath(LevelResource.ROOT).resolve("anya_storage/interiors");
        if(!Files.exists(receipt)){
            check(!Files.exists(dir),"Expected a fresh image directory");
            var saved=Config.JSON.toJsonTree(Config.get()).getAsJsonObject();
            var options=saved.getAsJsonObject("expansion");
            var reader=Config.class.getDeclaredMethod("read",Path.class);
            reader.setAccessible(true);
            Path file=Path.of("fixture-settings.json");
            for(boolean missing:new boolean[]{false,true}){
                if(missing){options.remove("imagesEnabled");options.remove("imageUploadsForAll");}
                else{options.addProperty("imagesEnabled",false);options.addProperty("imageUploadsForAll",false);}
                Files.writeString(file,saved.toString());
                var loaded=((Config.Data)reader.invoke(null,file)).expansion;
                check(!loaded.imagesEnabled && !loaded.imageUploadsForAll,"Saved server opt-out or legacy default lost");
            }
            return;
        }
        var lines=Files.readAllLines(receipt);
        var owner=new ServerPlayer(s,s.overworld(),new GameProfile(UUID.fromString(lines.get(0)),"ImageGuest"),ClientInformation.createDefault());
        var outsider=new ServerPlayer(s,s.overworld(),new GameProfile(UUID.randomUUID(),"Outsider"),ClientInformation.createDefault());
        var list=InteriorImages.list(owner,false);
        check(list.size()==1,"Reloaded catalog");
        var image=list.getFirst();
        check(image.id.toString().equals(lines.get(1)) && Files.size(dir.resolve(image.id+".png"))==image.bytes && image.width==48,"Saved image");
        check(!image.approved && image.viewers.isEmpty() && InteriorImages.canView(owner,image,false)
           && !InteriorImages.canView(outsider,image,false) && InteriorImages.list(outsider,false).isEmpty(),"Private on restart");
        Files.writeString(Path.of("CHECKS.txt"),"PASS First-use world directory and sanitized PNG survived a dedicated server restart. Pending image visible to owner only. A stranger could neither list nor view it.\n");
        s.halt(false);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e)throws Exception{
        var s=e.getServer();if(checked||s.getPlayerList().getPlayers().isEmpty())return;
        var p=s.getPlayerList().getPlayers().getFirst();if(p.tickCount<40)return;
        if(!opened){
            check(!p.hasPermissions(2),"Expected non-operator");
            p.getInventory().setItem(0,new ItemStack(Content.BACKPACK.get()));
            StoreMenu.bag(p,new BagInventory(p,0),false);opened=true;return;
        }
        if(!(p.containerMenu instanceof StoreMenu menu))return;
        var data=BagInventory.data(p.getInventory().getItem(0));
        if(!data.hasUUID("InteriorImage"))return;
        var id=data.getUUID("InteriorImage");
        var policy=ExpansionRules.get();policy.imagesEnabled=false;
        InteriorImages.receive(p,new ExpansionPackets.ImageWire(menu.containerId,menu.session(),0,data.getUUID("BagId"),0,128,new byte[0]));
        policy.imagesEnabled=true;
        check(InteriorImages.list(p,false).size()==1 && BagInventory.data(p.getInventory().getItem(0)).getUUID("InteriorImage").equals(id),"Rejected attempt changed attachment");
        Files.writeString(receipt,p.getUUID()+"\n"+id+"\n");checked=true;
    }
}