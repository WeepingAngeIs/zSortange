package fit;

import java.nio.file.*;
import java.util.*;
import java.lang.reflect.Method;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

@Mod("backfitcheck")
@EventBusSubscriber(modid="backfitcheck",value=Dist.CLIENT)
public final class FitCheck {
    private static final String WORLD="BackFit";
    private static String mode;
    private static Path root,output;
    private static List<String> phases;
    private static int tick,phase=-1,since,waitSince;
    private static boolean active,finished,ordered,waiting;
    private static volatile boolean setUp;
    private static volatile Throwable setupError;
    private static Object previous,body;
    private static float minY,maxY;
    private static double startZ;
    private static int photographedTick=-1;
    private static Row current;
    private static final Map<String,List<Row>> rows=new LinkedHashMap<>();
    private static final StringBuilder csv=new StringBuilder("phase,tick,walking,walkAmount,rigPulse,x,y,z,scaleX,scaleY,scaleZ,vanillaDelta\n");
    private record Row(int tick,boolean walking,float walk,double pulse,float x,float y,float z,float sx,float sy,float sz,float vanillaDelta) {}
    public static boolean observing(){return active&&!finished&&!waiting&&phase>=0&&setUp;}
    private static void require(boolean condition,String why){if(!condition)throw new IllegalStateException(why);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){
        var mc=Minecraft.getInstance();
        if(finished)return;
        try{
            if(!active){
                if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;
                String raw=System.getProperty("anyastorage.fitRoot","");
                if(raw.isBlank()){finished=true;return;}
                root=Path.of(raw).toRealPath();mode=System.getProperty("anyastorage.fitMode","");
                require(Set.of("private","synthetic","absent").contains(mode),"Unknown mode");
                boolean privateRun=mode.equals("private");
                String marker=privateRun?"private-check.marker":"hosted-check.marker";
                require(Files.readString(root.resolve(marker)).strip().equals(privateRun?"private":"synthetic"),"Missing disposable marker");
                require(privateRun || "true".equals(System.getenv("GITHUB_ACTIONS")),"Public synthetic checks require the hosted runner");
                require(root.resolve("game").equals(mc.gameDirectory.toPath().toRealPath())
                        && !Files.exists(root.resolve("game/saves/"+WORLD))&&mc.getConnection()==null,"Requires fresh isolated game");
                require(ModList.get().isLoaded("figura")!=mode.equals("absent"),"Unexpected Figura installation");
                output=Files.createDirectory(root.resolve("evidence"));
                phases=mode.equals("absent")?List.of("vanilla"):
                        privateRun?List.of("vanilla","a","b","a-return"):List.of("vanilla","a","b","a-return","no-anchor");
                active=true;mc.options.pauseOnLostFocus=false;mc.options.hideGui=true;
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                var settings=new LevelSettings(WORLD,GameType.SURVIVAL,false,Difficulty.PEACEFUL,false,new GameRules(),WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(WORLD,settings,new WorldOptions(9875,false,false),
                        regs->regs.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);
                return;
            }
            if(mc.player==null||mc.screen!=null)return;
            var server=mc.getSingleplayerServer();
            require(server!=null&&!server.isPublished(),"Expected an unpublished solo game");
            if(!ordered){
                ordered=true;UUID id=mc.player.getUUID();
                server.execute(()->{try{setup(server.getPlayerList().getPlayer(id));setUp=true;}catch(Throwable ex){setupError=ex;}});
            }
            if(setupError!=null)throw new IllegalStateException("Disposable world setup",setupError);
            if(++tick>5200)throw new IllegalStateException("Timed out during phase "+phase);
            if(!setUp)return;
            if(phase<0){advance(mc);return;}
            if(waiting){
                var loaded=avatar(mc);
                if(loaded==null||loaded==previous){require(tick-waitSince<480,"Avatar did not finish loading in "+phases.get(phase));return;}
                Object renderer=loaded.getClass().getField("renderer").get(loaded);
                if(renderer==null)return;
                body=mode.equals("synthetic")?findBody(renderer.getClass().getField("root").get(renderer)):null;
                if(mode.equals("synthetic")&&!phases.get(phase).equals("no-anchor"))require(body!=null,"Synthetic torso missing");
                waiting=false;since=tick;startZ=mc.player.getZ();
            }
            int elapsed=tick-since;
            mc.options.keyUp.setDown(elapsed>=32 && elapsed<=75);
            if(elapsed>83){
                var data=rows.get(phases.get(phase));
                require(data.size()>15,"The backpack layer did not submit its actual item for "+phases.get(phase));
                require(Math.abs(mc.player.getZ()-startZ)>2,"Normal walking input did not move the player in "+phases.get(phase));
                require(data.stream().anyMatch(r->r.walking&&r.walk>.15f),"No real walking animation for "+phases.get(phase));
                advance(mc);
            }
        }catch(Throwable ex){fail(mc,ex);}
    }
    private static void setup(ServerPlayer p)throws Exception {
        var level=p.serverLevel();
        var server=p.getServer();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
        level.setDayTime(6000);
        for(int z=-8;z<=85;z++)for(int x=-5;x<=5;x++)
            level.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState());
        p.connection.teleport(.5,81,.5,0,0);
        var item=BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("anyastorage","starter_backpack"));
        if(item==net.minecraft.world.item.Items.AIR)item=BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("anyastorage","backpack"));
        require(item!=net.minecraft.world.item.Items.AIR,"Missing backpack in registry");
        var bag=new ItemStack(item);
        p.getInventory().setItem(12,bag);
        Class.forName("dev.anya.storage.appearance.Wearables").getMethod("toggle",ServerPlayer.class,ItemStack.class).invoke(null,p,bag);
        p.inventoryMenu.broadcastChanges();
    }
    private static Object avatar(Minecraft mc)throws Exception{
        var av=Class.forName("org.figuramc.figura.avatar.AvatarManager").getMethod("getLoadedAvatar",UUID.class).invoke(null,mc.player.getUUID());
        return av!=null&&av.getClass().getField("loaded").getBoolean(av)?av:null;
    }
    private static Object findBody(Object part)throws Exception{
        if(part==null)return null;
        if("Body".equals(part.getClass().getField("name").get(part)))return part;
        for(var child:(List<?>)part.getClass().getField("children").get(part)){
            var found=findBody(child);if(found!=null)return found;
        }
        return null;
    }
    private static void advance(Minecraft mc)throws Exception{
        mc.options.keyUp.setDown(false);current=null;minY=Float.MAX_VALUE;maxY=-Float.MAX_VALUE;
        if(++phase==phases.size()){finish(mc);return;}
        String name=phases.get(phase);
        rows.put(name,new ArrayList<>());since=tick;startZ=mc.player.getZ();body=null;
        if(name.equals("vanilla"))return;
        previous=avatar(mc);waitSince=tick;waiting=true;
        String slot=name.startsWith("a")?"A":name.equals("b")?"B":"None";
        Path path=Path.of(System.getProperty("anyastorage.fitAvatar"+slot,root.resolve("avatar-"+slot.toLowerCase(Locale.ROOT)).toString())).toRealPath();
        require(path.startsWith(root)&&!path.equals(root)&&Files.isRegularFile(path.resolve("avatar.json")),"Invalid staged avatar input");
        Class.forName("org.figuramc.figura.avatar.AvatarManager").getMethod("loadLocalAvatar",Path.class).invoke(null,path);
    }
    public static void sample(Matrix4f actual,Matrix4f vanilla,float walk){
        if(!observing() || current!=null&&current.tick==tick)return;
        try{
            var scale=actual.getScale(new Vector3f());
            var m=actual.get(new float[16]);var v=vanilla.get(new float[16]);float error=0;
            for(int i=0;i<16;i++){require(Float.isFinite(m[i]),"Invalid pack matrix");error=Math.max(error,Math.abs(m[i]-v[i]));}
            double pulse=0;
            if(body!=null){
                Object pos=body.getClass().getMethod("getPos").invoke(body);
                pulse=pos.getClass().getField("y").getDouble(pos);
            }
            current=new Row(tick,tick-since>=38&&tick-since<=75,walk,pulse,actual.m30(),actual.m31(),actual.m32(),scale.x,scale.y,scale.z,error);
            var name=phases.get(phase);rows.get(name).add(current);
            csv.append(String.format(Locale.ROOT,"%s,%d,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                    name,tick,current.walking,walk,pulse,current.x,current.y,current.z,scale.x,scale.y,scale.z,error));
        }catch(Throwable ex){fail(Minecraft.getInstance(),ex);}
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void frame(RenderLevelStageEvent event){
        if(!observing()||current==null||current.tick==photographedTick||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;
        var mc=Minecraft.getInstance();
        try{
            photographedTick=current.tick;String name=phases.get(phase);
            if(tick-since>=24&&!Files.exists(output.resolve(name+"-idle.png")))photo(mc,name+"-idle");
            if(current.walking){
                if(current.y<minY-.002){minY=current.y;photo(mc,name+"-walk-low");}
                if(current.y>maxY+.002){maxY=current.y;photo(mc,name+"-walk-high");}
            }
        }catch(Exception ex){fail(mc,ex);}
    }
    private static void photo(Minecraft mc,String label)throws Exception{
        try(var img=Screenshot.takeScreenshot(mc.getMainRenderTarget())){img.writeToFile(output.resolve(label+".png"));}
    }
    private static double mean(String phase,java.util.function.ToDoubleFunction<Row> field){
        return rows.get(phase).stream().filter(r->r.walking).mapToDouble(field).average().orElseThrow();
    }
    private static double spread(String phase){
        return rows.get(phase).stream().filter(r->r.walking).mapToDouble(r->r.y).max().orElseThrow()-
                rows.get(phase).stream().filter(r->r.walking).mapToDouble(r->r.y).min().orElseThrow();
    }
    private static double correlation(String phase){
        double y=mean(phase,r->r.y),pulse=mean(phase,r->r.pulse);
        double sig=0,sy=0,sp=0;
        for(var r:rows.get(phase))if(r.walking){double a=r.y-y,b=r.pulse-pulse;sig+=a*b;sy+=a*a;sp+=b*b;}
        return sy*sp<1e-9?0:sig/Math.sqrt(sy*sp);
    }
    private static void finish(Minecraft mc)throws Exception{
        Files.writeString(output.resolve("frames.csv"),csv.toString());
        for(String name:phases){
            require(Files.exists(output.resolve(name+"-idle.png"))&&Files.exists(output.resolve(name+"-walk-low.png")),"Missing actual game captures "+name);
            if(name.equals("vanilla")||name.equals("no-anchor")){
                double delta=rows.get(name).stream().mapToDouble(r->r.vanillaDelta).max().orElseThrow();
                require(delta<.002,"Vanilla body attachment changed in "+name+": "+delta);
            }
        }
        String measured="Vanilla fallback matched the actual rendered item. ";
        if(phases.contains("b")){
            double a=mean("a",r->r.sx),b=mean("b",r->r.sx),returned=mean("a-return",r->r.sx);
            double ay=mean("a",r->r.y),by=mean("b",r->r.y);
            String observed=String.format(Locale.ROOT,"Worn scales first=%.4f, smaller=%.4f, returned=%.4f. Anchor Y first=%.4f, smaller=%.4f. Walk Y range first=%.4f, smaller=%.4f. Synthetic pulse correlations first=%.3f, smaller=%.3f.",a,b,returned,ay,by,spread("a"),spread("b"),correlation("a"),correlation("b"));
            Files.writeString(output.resolve("observations.txt"),observed);
            require(b/a<.94&&b/a>.20,"Smaller avatar did not receive a smaller backpack. "+observed);
            require(Math.abs(returned/a-1)<.08,"Pack did not refit on return switch. "+observed);
            require(by>ay+.035,"Smaller avatar did not receive a lower torso attachment. "+observed);
            require(spread("a")>.015,"Moving first avatar torso did not carry the worn bag. "+observed);
            if(mode.equals("synthetic")){
                for(String name:List.of("a","b","a-return"))require(Math.abs(correlation(name))>.78,"Pack movement failed to follow authored torso bob in "+name+". "+observed);
            }
            measured+=observed;
        }
        Files.writeString(output.resolve("PASS.txt"),measured);finished=true;mc.stop();
    }
    private static void fail(Minecraft mc,Throwable e){
        finished=true;mc.options.keyUp.setDown(false);
        if(output!=null)try{Files.writeString(output.resolve("frames.csv"),csv.toString());Files.writeString(output.resolve("failure.txt"),e.toString()+"\n"+String.valueOf(e.getCause()));}catch(Exception ignored){}
        if(active)mc.stop();else throw new IllegalStateException(e);
    }
}
