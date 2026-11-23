package probe;

import com.mojang.blaze3d.platform.NativeImage;
import dev.anya.storage.pocket.ScreenMedia;
import dev.anya.storage.pocket.RoomMediaNet;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import static probe.RoomMediaServer.*;

@EventBusSubscriber(modid="anyadisplayprobe",value=Dist.CLIENT)
public final class RoomMediaClient {
    private static final String PREFIX="screen.anyastorage.media.";
    private static final AtomicInteger begins=new AtomicInteger(), parts=new AtomicInteger(), clocks=new AtomicInteger(), ends=new AtomicInteger();
    private static final Set<String> frames=ConcurrentHashMap.newKeySet();
    private static BlockPos pos;
    private static volatile boolean streamed;
    private static volatile long gameStart=-1;
    private static volatile String receivedHash="";
    private static boolean audited,played,volumeClicked,stopped,playedGif;
    private static int endedBegins,endedParts;
    private static int activeTicks,afterVolume,afterStop;
    private static long captured;

    private static boolean menu(Minecraft mc){
        return mc.screen!=null && mc.screen.getClass().getSimpleName().equals("DisplaySourceScreen");
    }
    private static void open(Minecraft mc){
        if(mc.screen!=null||mc.player.tickCount%10!=0)return;
        var hit=mc.player.pick(5,1,false);
        if(hit instanceof BlockHitResult block && block.getBlockPos().equals(pos))
            mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,block);
    }
    private static Button button(Minecraft mc,String name){
        return mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b->b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(PREFIX+name))
                .findFirst().orElse(null);
    }
    private static void click(Minecraft mc,String name){
        var button=button(mc,name);
        require(button!=null && button.visible && button.active && mc.screen.mouseClicked(button.getX()+4,button.getY()+4,0),"No action for "+name);
    }
    private static String summary(){
        return "Begin="+begins.get()+" Parts="+parts.get()+" Clock="+clocks.get()+" End="+ends.get()+" streaming="+streamed;
    }
    private static void sendAddress(Minecraft mc,String filename){
        String commit=System.getenv("GITHUB_SHA");
        require(commit.matches("[0-9a-f]{40}"),"Expected frozen run commit");
        String address="https://raw.githubusercontent.com/WeepingAngeIs/zSortange/"+commit+"/fixtures/display-media/inputs/"+filename;
        var field=mc.screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
        field.setValue(address);click(mc,"play_room");
    }
    private static void observe(Minecraft mc) throws Exception {
        var channel=mc.getConnection().getConnection().channel();
        if(channel.pipeline().get("room_media_audit")!=null)return;
        channel.pipeline().addBefore("packet_handler","room_media_audit",new ChannelDuplexHandler(){
            private MessageDigest media;
            private int seen,expected;
            @Override public void channelRead(ChannelHandlerContext c,Object msg) throws Exception {
                if(msg instanceof ClientboundCustomPayloadPacket packet){
                    var type=packet.payload().getClass();
                    if(type.getName().startsWith("dev.anya.storage.pocket.RoomMediaNet$")){
                        switch(type.getSimpleName()){
                            case "Begin" -> {
                                begins.incrementAndGet();receivedHash="";seen=0;
                                expected=((RoomMediaNet.Begin)packet.payload()).length();media=MessageDigest.getInstance("SHA-256");
                            }
                            case "Part" -> {
                                parts.incrementAndGet();
                                var part=(RoomMediaNet.Part)packet.payload();
                                require(media!=null && part.offset()==seen,"Noncontiguous room media bytes");
                                byte[] bytes=part.bytes();media.update(bytes);seen+=bytes.length;
                                if(seen==expected)receivedHash=HexFormat.of().formatHex(media.digest());
                            }
                            case "Clock" -> {
                                clocks.incrementAndGet();
                                gameStart=((RoomMediaNet.Clock)packet.payload()).startGameTime();
                            }
                            case "End" -> ends.incrementAndGet();
                        }
                    }
                }
                super.channelRead(c,msg);
            }
        });
        audited=true;mark("audit-"+SIDE,"Attached normal received-packet observer");
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post e) throws Exception {
        if(SIDE.isEmpty()||flag("FAILED")||flag("PASS"))return;
        var mc=Minecraft.getInstance();
        if(mc.player==null||mc.getConnection()==null||mc.gameMode==null)return;
        try{
            if(!audited)observe(mc);
            if(!flag("screen"))return;
            if(pos==null)pos=BlockPos.of(Long.parseLong(Files.readString(MediaTrial.proof.resolve("screen"))));
            if(flag("tv-gif")){
                if(SIDE.equals("LateGuest")){
                    if(flag("gif-RoomHost")&&!flag("gif-excluded")){
                        require(begins.get()==endedBegins && parts.get()==endedParts && !ScreenMedia.active(pos),"Revoked participant received second media share");
                        mark("gif-excluded",summary());
                    }
                } else if(SIDE.equals("RoomGuest")&&!playedGif){
                    if(!menu(mc)){open(mc);return;}
                    sendAddress(mc,"cards.gif");playedGif=true;
                } else if(!flag("gif-"+SIDE)&&frames.containsAll(Set.of("blue","yellow"))&&begins.get()>endedBegins){
                    require(mc.screen==null && ScreenMedia.texture(pos)!=null && receivedHash.equals("4963cf5d4649cf45ad53c9f12675536b3ea8d0371b61277fa1a63088cecf9693"),"Second session did not present the original GIF bytes");
                    mark("gif-"+SIDE,summary());
                }
                return;
            }
            if(SIDE.equals("LateGuest")&&!flag("late-inside")&&!flag("excluded-LateGuest")&&flag("movie-RoomHost")){
                require(!ScreenMedia.active(pos) && begins.get()==0 && parts.get()==0 && clocks.get()==0 && !streamed,
                        "An outsider received a session: "+summary());
                mark("excluded-LateGuest",summary());
            }
            if(SIDE.equals("RoomGuest")&&!played){
                if(!menu(mc)){open(mc);return;}
                for(String restricted:new String[]{"link","camera","controls"}){
                    Button b=button(mc,restricted);
                    require(b==null||!b.active||!b.visible,"Visit-only user has owner action "+restricted);
                }
                sendAddress(mc,"room-color-tone.mp4");played=true;return;
            }
            boolean active=ScreenMedia.active(pos)&&ScreenMedia.texture(pos)!=null;
            if(active)activeTicks++;
            if(!flag("movie-"+SIDE)&&activeTicks>=40&&streamed&&frames.containsAll(Set.of("red","green"))){
                require(begins.get()>0 && parts.get()>0 && clocks.get()>0,"Missing actual session packets: "+summary());
                require(receivedHash.equals("0d3325307dddf50ef755e368fa6f3181aacb5ed9dc6d8e8770c3ada1a978cc76"),"Client did not receive the original generated movie bytes");
                require(mc.screen==null,"Automatic playback opened a menu");
                mark("movie-"+SIDE,summary()+" clock="+gameStart+" sample="+mc.level.getGameTime());
            }
            if(!SIDE.equals("LateGuest") && flag("volume-test") && !flag("volume-"+SIDE)){
                if(!volumeClicked){
                    if(!menu(mc)){open(mc);return;}
                    var slider=mc.screen.children().stream().filter(AbstractSliderButton.class::isInstance)
                            .map(AbstractSliderButton.class::cast).findFirst().orElseThrow();
                    double target=SIDE.equals("RoomHost")?.35:.7;
                    int x=slider.getX()+4+(int)Math.round((slider.getWidth()-8)*target), y=slider.getY()+5;
                    mc.screen.mouseClicked(x,y,0);mc.screen.mouseReleased(x,y,0);
                    require(Math.abs(ScreenMedia.volume()-target)<.05,"Volume slider did not save independently");
                    mc.screen.onClose();volumeClicked=true;
                }
                if(active && ++afterVolume>=54)mark("volume-"+SIDE,Float.toString(ScreenMedia.volume()));
            }
            if(SIDE.equals("LateGuest") && flag("revoked") && !active && ends.get()>0 && !flag("ended-"+SIDE)){
                endedBegins=begins.get();endedParts=parts.get();
                mark("ended-"+SIDE,summary());
            }
            if(SIDE.equals("RoomHost") && flag("ended-RoomGuest") && !flag("host-continued") && ++afterStop>=46){
                require(active && !stopped && ends.get()==0,"Someone else's Stop ended the owner's movie: "+summary());
                mark("host-continued",summary());
            }
            boolean shouldStop=SIDE.equals("RoomGuest")&&flag("stop-request") || SIDE.equals("RoomHost")&&flag("stop-host");
            if(shouldStop&&!stopped){
                if(!menu(mc)){open(mc);return;}
                click(mc,"stop");if(mc.screen!=null)mc.screen.onClose();stopped=true;
            }
            if(stopped&&!active&&!flag("ended-"+SIDE)){
                endedBegins=begins.get();endedParts=parts.get();mark("ended-"+SIDE,summary());
            }
        }catch(Exception ex){mark("FAILED",SIDE+" "+summary()+" "+ex);throw ex;}
    }
    @SubscribeEvent public static void sound(PlayStreamingSourceEvent e){
        var sound=e.getSound();
        if(pos!=null && e.getChannel()!=null && sound.getLocation().toString().equals("anyastorage:screen_media")
                && sound.getSource()==SoundSource.BLOCKS && pos.equals(BlockPos.containing(sound.getX(),sound.getY(),sound.getZ())))streamed=true;
    }
    @SubscribeEvent public static void draw(RenderGuiEvent.Post e) throws Exception {
        var mc=Minecraft.getInstance();long now=System.nanoTime();
        if(pos==null || captured+100_000_000L>now || mc.screen!=null || ScreenMedia.texture(pos)==null)return;
        boolean gif=flag("tv-gif");
        if(frames.size()==(gif?4:2))return;
        captured=now;
        try(NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){
            int red=0,green=0,blue=0,yellow=0,n=0;
            for(int x=image.getWidth()/2-16;x<image.getWidth()/2+16;x+=3){
                for(int y=image.getHeight()/2-9;y<image.getHeight()/2+9;y+=3){
                    int c=image.getPixelRGBA(x,y),r=c&255,g=c>>8&255,b=c>>16&255;n++;
                    if(r>115&&r>g*2&&r>b*2)red++;
                    if(g>105&&g>r*2&&g>b*2)green++;
                    if(b>115&&b>r*2&&b>g*2)blue++;
                    if(r>130&&g>120&&b*2<g&&b*2<r)yellow++;
                }
            }
            String color=gif?(blue*4>n*3?"blue":yellow*4>n*3?"yellow":""):(red*4>n*3?"red":green*4>n*3?"green":"");
            if(!color.isEmpty()&&frames.add(color))image.writeToFile(MediaTrial.proof.resolve(SIDE+"-"+color+".png"));
        }
    }
    private RoomMediaClient(){}
}
