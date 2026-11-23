package probe;

import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.mojang.blaze3d.platform.NativeImage;
import dev.anya.storage.pocket.ScreenMedia;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;

@EventBusSubscriber(modid="anyadisplayprobe",value=Dist.CLIENT)
public final class MediaClient {
    private static final String PREFIX="screen.anyastorage.media.";
    private static final Set<BlockPos> streamed=ConcurrentHashMap.newKeySet();
    private static final Set<String> pictures=ConcurrentHashMap.newKeySet();
    private static int step, ticks, initialTicks;
    private static boolean wall;
    private static long sampled;
    private static String expect="";
    private static BlockPos pos(){return wall?MediaTrial.WALL:MediaTrial.TV;}
    private static String prefix(){return wall?"wall":"tv";}
    private static Path path(String name){return MediaTrial.proof.resolve(name);}
    private static void next(){step++;ticks=0;}
    private static boolean source(Minecraft mc){return mc.screen!=null&&mc.screen.getClass().getSimpleName().equals("DisplaySourceScreen");}
    private static void open(Minecraft mc){
        if(mc.screen!=null||mc.player.distanceToSqr(Vec3.atCenterOf(pos()))>25)return;
        var hit=new BlockHitResult(Vec3.atCenterOf(pos()).add(0,0,.07),Direction.SOUTH,pos(),false);
        mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
    }
    private static Button button(Minecraft mc,String key){
        return mc.screen.children().stream().filter(e->e instanceof Button).map(e->(Button)e).filter(b->{
            var message=b.getMessage();
            return message.getContents() instanceof TranslatableContents s && s.getKey().equals(PREFIX+key)
                    || message.getString().startsWith(key);
        }).findFirst().orElseThrow(()->new IllegalStateException("Missing button "+key));
    }
    private static void click(Minecraft mc,String label){
        Button b=button(mc,label);
        if(!b.active || !mc.screen.mouseClicked(b.getX()+3,b.getY()+3,0)) throw new IllegalStateException("Inactive "+label);
    }
    private static void play(Minecraft mc,String link){
        var field=mc.screen.children().stream().filter(e->e instanceof EditBox).map(e->(EditBox)e).findFirst().orElseThrow();
        field.setValue(link);click(mc,"play");
    }
    private static String status(){return ScreenMedia.status(pos());}
    private static boolean linkedScene() throws Exception {
        var f=Class.forName("dev.anya.storage.pocket.CameraScreen").getDeclaredField("scene");
        f.setAccessible(true);return f.get(null)!=null;
    }
    private static void endMedia(){expect="";}
    private static String clip(){
        String sha=System.getenv("GITHUB_SHA");
        if(sha==null||!sha.matches("[0-9a-f]{40}"))throw new IllegalStateException("Missing frozen source");
        return "https://raw.githubusercontent.com/WeepingAngeIs/zSortange/"+sha+"/fixtures/display-media/inputs/color-tone.mp4";
    }
    private static void directAttachment() throws Exception {
        var uri=Class.forName("dev.anya.storage.pocket.MediaDownload").getDeclaredMethod("directUri",String.class);
        uri.setAccessible(true);
        String synthetic="https://cdn.discordapp.com/attachments/1/2/test.gif?ex=abcd&sig=synthetic";
        if(!synthetic.equals(String.valueOf(uri.invoke(null,synthetic))))throw new IllegalStateException("Direct attachment URL lost its query");
    }
    private static void broken(String reason) throws Exception {
        Files.writeString(path("FAILED"),reason);throw new IllegalStateException(reason);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post e) throws Exception {
        var mc=Minecraft.getInstance();
        if(mc.player==null||mc.level==null||mc.gameMode==null) return;
        if(Files.exists(path("PASS")) || Files.exists(path("FAILED"))) return;
        if(!Files.exists(path(wall?"stage-wall":"stage-tv"))) return;
        if(initialTicks++==0)MediaTrial.hosted();
        ticks++;
        if(ticks>1000){broken(prefix()+" timed out at step "+step+", status="+status()+", captures="+pictures);return;}
        try {
            switch(step){
                case 0 -> {if(ticks%12==0){open(mc);if(source(mc))next();}else if(source(mc))next();}
                case 1 -> {
                    if(!source(mc))return;
                    if(!wall){
                        directAttachment();
                        mc.screen.onFilesDrop(java.util.List.of(Path.of(System.getenv("ANYA_MEDIA_INPUTS"),"color-tone.mp4")));
                        if(!source(mc))throw new IllegalStateException("Local MP4 incorrectly started");
                        play(mc,"https://discord.com/channels/1/2/3");
                    }else click(mc,"controls");
                    next();
                }
                case 2 -> {
                    if(wall){
                        if(mc.screen==null||!mc.screen.getClass().getSimpleName().equals("CameraControlsScreen"))return;
                        click(mc,"Width: 1 ");next();
                    }else if(status().contains("Discord message")){open(mc);next();}
                }
                case 3 -> {
                    if(wall){if(button(mc,"Width:").getMessage().getString().startsWith("Width: 2 ")){click(mc,"Width: 2 ");next();}}
                    else if(source(mc)){play(mc,"https://www.youtube.com/watch?v=synthetic-fixture");next();}
                }
                case 4 -> {
                    if(wall){
                        if(button(mc,"Width:").getMessage().getString().startsWith("Width: 3 ")){click(mc,"Height: 1 ");next();}
                    }else if(status().contains("YouTube")){open(mc);next();}
                }
                case 5 -> {
                    if(wall){
                        if(button(mc,"Height:").getMessage().getString().startsWith("Height: 2 ")){mc.screen.onClose();next();}
                    }else if(source(mc)){play(mc,"file:///synthetic-test.gif");next();}
                }
                case 6 -> {
                    if(!wall && (status().isEmpty()||status().contains("Loading")))return;
                    if(!wall && !status().toLowerCase(java.util.Locale.ROOT).contains("https"))throw new IllegalStateException("Unexpected local URL outcome: "+status());
                    if(wall){
                        var be=mc.level.getBlockEntity(pos());
                        if(be==null)return;
                        var panel=be.getUpdateTag(mc.level.registryAccess()).getCompound("Panel");
                        if(panel.getInt("W")!=3||panel.getInt("H")!=2)return;
                    }
                    open(mc);next();
                }
                case 7 -> {if(source(mc)){click(mc,"link");next();}}
                case 8 -> {if(ticks>=18&&source(mc)){click(mc,"camera");next();}}
                case 9 -> {if(linkedScene()&&mc.screen==null){expect="camera-before";next();}}
                case 10 -> {if(pictures.contains(prefix()+"-camera-before")){endMedia();open(mc);next();}}
                case 11 -> {
                    if(source(mc)){Files.writeString(path(prefix()+"-movie-start"),"explicit Play");play(mc,clip());expect="movie";next();}
                }
                case 12 -> {
                    if(ticks>=65&&pictures.contains(prefix()+"-movie-red")&&pictures.contains(prefix()+"-movie-green")&&streamed.contains(pos())){
                        Files.writeString(path(prefix()+"-movie-end"),"observed both pictures and streaming sound");
                        endMedia();open(mc);next();
                    }
                }
                case 13 -> {
                    if(source(mc)){
                        mc.screen.onFilesDrop(java.util.List.of(Path.of(System.getenv("ANYA_MEDIA_INPUTS"),"cards.gif")));
                        expect="gif";next();
                    }
                }
                case 14 -> {
                    if(pictures.contains(prefix()+"-gif-blue")&&pictures.contains(prefix()+"-gif-yellow")){
                        endMedia();open(mc);next();
                    }
                }
                case 15 -> {if(source(mc)){click(mc,"camera");expect="camera-after";next();}}
                case 16 -> {
                    if(pictures.contains(prefix()+"-camera-after")&&linkedScene()){
                        endMedia();
                        if(!wall){
                            Files.writeString(path("next-wall"),"move the disposable player to the wall");
                            wall=true;step=0;ticks=0;
                        }else{
                            Files.writeString(path("PASS"),"Both displays: normal right-click, camera selected by tablet, linked feed, direct public HTTPS movie via Play, actual red and green rendered frames, streaming sound at the correct block, deliberate dropped GIF with blue and yellow frames, then live camera again. Resized wall remained 3 x 2. Local MP4, file URL, Discord message and YouTube page did not start playback.\n");
                        }
                    }
                }
                default -> throw new IllegalStateException("Unexpected stage");
            }
        }catch(Exception ex){broken(prefix()+" step "+step+": "+ex);}
    }
    @SubscribeEvent public static void sound(PlayStreamingSourceEvent e){
        var sound=e.getSound();
        if(sound.getLocation().toString().equals("anyastorage:screen_media")&&sound.getSource()==SoundSource.BLOCKS&&e.getChannel()!=null)
            streamed.add(BlockPos.containing(sound.getX(),sound.getY(),sound.getZ()));
    }
    @SubscribeEvent public static void draw(RenderGuiEvent.Post e)throws Exception {
        var mc=Minecraft.getInstance();
        if(mc.screen!=null||mc.player==null||expect.isEmpty()||System.nanoTime()-sampled<90_000_000L)return;
        sampled=System.nanoTime();
        try(NativeImage frame=Screenshot.takeScreenshot(mc.getMainRenderTarget())){
            int w=frame.getWidth(),h=frame.getHeight();
            int red=0,green=0,blue=0,yellow=0,n=0;
            for(int x=w/2-18;x<w/2+18;x+=3)for(int y=h/2-12;y<h/2+12;y+=3){
                int c=frame.getPixelRGBA(x,y),r=c&255,g=(c>>8)&255,b=(c>>16)&255;n++;
                if(r>115&&r>g*2&&r>b*2)red++;
                if(g>105&&g>r*2&&g>b*2)green++;
                if(b>115&&b>g*2&&b>r*2)blue++;
                if(r>130&&g>120&&b*2<g&&b*2<r)yellow++;
            }
            String color=null;
            if(expect.equals("movie")){if(red*4>n*3)color="movie-red";if(green*4>n*3)color="movie-green";}
            if(expect.equals("gif")){if(blue*4>n*3)color="gif-blue";if(yellow*4>n*3)color="gif-yellow";}
            if(expect.startsWith("camera-") && linkedScene() && status().isEmpty())color=expect;
            if(color!=null && pictures.add(prefix()+"-"+color))frame.writeToFile(path(prefix()+"-"+color+".png"));
        }
    }
    private MediaClient(){}
}
