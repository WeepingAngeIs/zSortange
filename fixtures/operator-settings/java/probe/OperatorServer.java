package probe;

import dev.anya.storage.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("settingsprobe")
@EventBusSubscriber(modid="settingsprobe")
public final class OperatorServer {
    static int stage;
    static long initial;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e)throws Exception{
        var server=e.getServer();var p=server.getPlayerList().getPlayerByName("ImageGuest");if(p==null)return;
        String key="expansion.tabletOpeningChancePercent";
        if(stage==0 && Files.exists(Path.of("../Client/guest"))){
            initial=Config.get().revision;
            var result=new AtomicReference<OperatorPage.View>();
            OperatorPage.handle(p,new OperatorPage.Request(5,initial,"",0,8,key,"77",false),result::set);
            check(result.get().cells().isEmpty() && result.get().revision()<0 && Config.get().revision==initial,"Guest read or wrote settings");
            server.getPlayerList().op(p.getGameProfile());stage++;
        }else if(stage==1 && Files.exists(Path.of("../Client/applied"))){
            check(Config.get().revision==initial+1 && Config.get().expansion.tabletOpeningChancePercent==31,"Failed live edit");
            var backup=com.google.gson.JsonParser.parseString(Files.readString(Path.of("config/anya-storage-plus.json.bak"))).getAsJsonObject();
            check(backup.getAsJsonObject("expansion").get("tabletOpeningChancePercent").getAsInt()==24,"Backup did not preserve original");
            var result=new AtomicReference<OperatorPage.View>();
            OperatorPage.handle(p,new OperatorPage.Request(1,initial,"",0,8,key,"29",false),result::set);
            check(result.get().status().contains("another")||result.get().status().contains("Another"),"Failed stale revision guard");
            OperatorPage.handle(p,new OperatorPage.Request(2,initial+1,"",0,8,key,"120",false),result::set);
            check(Config.get().revision==initial+1 && !result.get().status().isBlank(),"Invalid value accepted");
            String sharing="expansion.imageSharing";
            OperatorPage.handle(p,new OperatorPage.Request(3,initial+1,"image",0,8,sharing,"true",true),result::set);
            check(result.get().confirmation().equals(sharing) && !Config.get().expansion.imageSharing,"Initial confirmation bypass");
            Files.writeString(Path.of("ready"),"ok");stage++;
        }else if(stage==2 && Files.exists(Path.of("../Client/toggled"))){
            check(Config.get().expansion.chestCameoEnabled && Config.get().revision==initial+2,"Toggle did not persist");
            var file=com.google.gson.JsonParser.parseString(Files.readString(Path.of("config/anya-storage-plus.json"))).getAsJsonObject();
            check(file.getAsJsonObject("expansion").get("chestCameoEnabled").getAsBoolean(),"Missing saved toggle");
            server.getPlayerList().deop(p.getGameProfile());
            var result=new AtomicReference<OperatorPage.View>();
            OperatorPage.handle(p,new OperatorPage.Request(4,initial+2,"",0,8,key,"29",false),result::set);
            check(result.get().revision()<0 && Config.get().revision==initial+2,"Revoked operator could edit");
            Files.writeString(Path.of("CHECKS.txt"),"PASS guest/revoked denial, persisted toggle and number, prior-value backup, stale revision, invalid input, disruptive confirmation.\n");stage++;
        }
    }
}
