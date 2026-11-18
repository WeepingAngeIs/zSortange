package dev.anya.storage.acceptance;

import dev.anya.storage.pocket.EntranceLayoutClient;
import dev.anya.storage.pocket.PocketContent;
import dev.anya.storage.pocket.PocketPadPackets;
import io.netty.channel.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** GitHub-only client observer. The packaged renderer and normal focus sender run throughout. */
final class PadPreviewClient {
    private static Connection connection;
    private static final AtomicInteger positive=new AtomicInteger(), cleared=new AtomicInteger(), held=new AtomicInteger();
    private static volatile PocketPadPackets.Update latestPositive;
    private static volatile long latestWatch;
    private static volatile boolean sawWatch, suppressUpdates;
    private static PocketPadPackets.Update remembered;
    private static int clearsAtGrant, heldBefore, stable;
    private static boolean replayed;
    private static CompletableFuture<Component> saving;

    static boolean active(){return "pad-preview".equals(System.getenv("ANYA_ACCEPTANCE_SCENARIO"));}
    static void observe(Minecraft mc){
        if(mc.getConnection()==null||mc.getConnection().getConnection()==connection)return;
        Acceptance.require("true".equals(System.getenv("GITHUB_ACTIONS")),"Hosted fixture only");
        connection=mc.getConnection().getConnection();
        var watch=new ChannelDuplexHandler(){
            @Override public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise)throws Exception{
                if(msg instanceof ServerboundCustomPayloadPacket packet&&packet.payload() instanceof PocketPadPackets.Focus f
                        &&f.pos().equals(PadPreviewProbe.ENTRANCE)&&f.watching()){
                    latestWatch=f.token();sawWatch=true;
                }
                super.write(ctx,msg,promise);
            }
            @Override public void channelRead(ChannelHandlerContext ctx,Object msg)throws Exception{
                if(msg instanceof ClientboundCustomPayloadPacket packet&&packet.payload() instanceof PocketPadPackets.Update update
                        &&update.pos().equals(PadPreviewProbe.ENTRANCE)){
                    if(suppressUpdates){held.incrementAndGet();return;}
                    if(update.mask()<0)cleared.incrementAndGet();
                    else{latestPositive=update;positive.incrementAndGet();}
                }
                super.channelRead(ctx,msg);
            }
        };
        connection.channel().pipeline().addBefore("packet_handler","anya_fixture_pad_observer",watch);
    }

    static boolean check(Minecraft mc,String[] args,int ticks)throws Exception{
        Acceptance.require(active()&&args.length==5,"Invalid preview request");
        String name=args[1],side=args[3],mode=args[4];int expected=Integer.parseInt(args[2]);
        if(ticks==1){
            stable=0;replayed=false;saving=null;
            if(mode.equals("expiry")){
                Acceptance.require(remembered!=null&&!suppressUpdates,"Expiry test requires prior real grant");
                heldBefore=held.get();suppressUpdates=true;
            }
        }
        boolean photo=mode.equals("image")||name.startsWith("public-");
        Path image=mc.gameDirectory.toPath().resolve("screenshots").resolve("pad-"+name+".png");
        if(saving!=null){
            if(!saving.isDone())return false;
            Acceptance.require(Files.isRegularFile(image)&&Files.size(image)>1024,"Framebuffer write failed: "+saving.get());
            return true;
        }
        Acceptance.require(ticks<=360,"Timed out at "+name+" watch="+sawWatch+" positive="+positive+" clears="+cleared+" held="+held);
        if(mc.screen!=null||mc.getCameraEntity()!=mc.player||!(mc.level.getBlockEntity(PadPreviewProbe.ENTRANCE) instanceof PocketContent.EntranceEntity entrance))return false;
        mc.options.hideGui=true;
        Vec3 centre=Vec3.atCenterOf(PadPreviewProbe.ENTRANCE);
        boolean away=mode.equals("away");
        mc.player.lookAt(EntityAnchorArgument.Anchor.EYES,away?mc.player.getEyePosition().add(0,4,0):centre);
        Direction direction=Direction.valueOf(side.toUpperCase(Locale.ROOT));
        boolean focused=mc.hitResult instanceof BlockHitResult hit&&hit.getType()==HitResult.Type.BLOCK
                &&hit.getBlockPos().equals(PadPreviewProbe.ENTRANCE)&&hit.getDirection()==direction;
        if(away==focused||!sawWatch){stable=0;return false;}
        if(mode.equals("public"))Acceptance.require(positive.get()==0,"Denied/cold viewer received a private layout on the wire: "+name);
        if(mode.equals("replay")&&!replayed){
            if(remembered==null||latestWatch==remembered.token())return false;
            // Deliver a real previously received reply after the ordinary client focused again.
            EntranceLayoutClient.receive(connection,remembered);
            Acceptance.require(EntranceLayoutClient.visibleMask(entrance)==-1,"A former focus token restored an old diagram");
            replayed=true;
        }
        int observed=EntranceLayoutClient.visibleMask(entrance);
        boolean ready=observed==expected;
        if(mode.equals("clear"))ready&=cleared.get()>clearsAtGrant;
        if(mode.equals("expiry"))ready&=held.get()>heldBefore;
        if(mode.equals("remember"))ready&=latestPositive!=null&&latestPositive.mask()==expected&&latestPositive.token()==latestWatch;
        stable=ready?stable+1:0;
        if(stable<16)return false;
        if(mode.equals("remember")){remembered=latestPositive;clearsAtGrant=cleared.get();}
        if(mode.equals("expiry"))suppressUpdates=false;
        String state=String.format(Locale.ROOT,
                "{\"name\":\"%s\",\"role\":\"%s\",\"mask\":%d,\"side\":\"%s\",\"positive_received\":%d,\"clears_received\":%d,\"suppressed_fixture_updates\":%d}",
                name,System.getProperty("anya.role"),observed,side,positive.get(),cleared.get(),held.get());
        Acceptance.marker("pad-"+name+"-state.json",state);
        if(!photo)return true;
        Acceptance.require(!Files.exists(image),"Existing framebuffer: "+image);
        var camera=mc.gameRenderer.getMainCamera();var eye=camera.getPosition();var aim=camera.getLookVector();var up=camera.getUpVector();var pos=PadPreviewProbe.ENTRANCE;
        Acceptance.marker("pad-"+name+"-camera.json",String.format(Locale.ROOT,
                "{\"eye\":[%.8f,%.8f,%.8f],\"aim\":[%.8f,%.8f,%.8f],\"up\":[%.8f,%.8f,%.8f],\"fov\":%d,\"side\":\"%s\",\"entrance\":[%d,%d,%d]}",
                eye.x,eye.y,eye.z,aim.x(),aim.y(),aim.z(),up.x(),up.y(),up.z(),mc.options.fov().get(),side,pos.getX(),pos.getY(),pos.getZ()));
        saving=new CompletableFuture<>();
        Screenshot.grab(mc.gameDirectory,image.getFileName().toString(),mc.getMainRenderTarget(),saving::complete);
        return false;
    }
}
