package dev.anya.storage.pocket;

import dev.anya.storage.*;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

final class CameraConsole {
    static final UUID NONE=new UUID(0,0);
    record View(UUID token, UUID source, boolean opening, int width,int height,int maxW,int maxH,int mode,int group,
                String watch,String ignore,String message,int lens) implements CustomPacketPayload {
        static final Type<View> TYPE=new Type<>(ExpansionPackets.id("camera_settings_view"));
        public Type<View> type(){return TYPE;}
        View withLens(int lens){return new View(token,source,opening,width,height,maxW,maxH,mode,group,watch,ignore,message,lens);}
    }
    record LensView(View state) implements CustomPacketPayload{
        static final Type<LensView> TYPE=new Type<>(ExpansionPackets.id("camera_lens_settings"));
        public Type<LensView> type(){return TYPE;}
    }
    record Edit(UUID token,UUID source,String control,int value,String kind) implements CustomPacketPayload {
        static final Type<Edit> TYPE=new Type<>(ExpansionPackets.id("camera_settings_edit"));
        public Type<Edit> type(){return TYPE;}
    }
    record Motion(BlockPos pos,String kind) implements CustomPacketPayload {
        static final Type<Motion> TYPE=new Type<>(ExpansionPackets.id("camera_motion"));
        public Type<Motion> type(){return TYPE;}
    }
    static BiConsumer<Connection,View> receiver=(connection,view)->{};
    static BiConsumer<Connection,Motion> motionReceiver=(connection,motion)->{};
    private record Session(CameraDevices.Device device,UUID token,long until){}
    private static final Map<ServerPlayer,Session> sessions=new WeakHashMap<>();
    static void register(RegisterPayloadHandlersEvent e){
        var r=e.registrar("camerastudio1").optional();
        StreamCodec<RegistryFriendlyByteBuf,View> view=StreamCodec.ofMember((v,b)->{
            b.writeUUID(v.token);b.writeUUID(v.source);b.writeBoolean(v.opening);b.writeByte(v.width);b.writeByte(v.height);b.writeByte(v.maxW);b.writeByte(v.maxH);
            b.writeByte(v.mode);b.writeByte(v.group);b.writeUtf(v.watch,4200);b.writeUtf(v.ignore,4200);b.writeUtf(v.message,160);
        },b->new View(b.readUUID(),b.readUUID(),b.readBoolean(),b.readByte(),b.readByte(),b.readByte(),b.readByte(),b.readByte(),b.readByte(),
                b.readUtf(4200),b.readUtf(4200),b.readUtf(160),-1));
        StreamCodec<RegistryFriendlyByteBuf,LensView> optics=StreamCodec.ofMember((v,b)->{
            view.encode(b,v.state);b.writeBoolean(v.state.lens()==1);
        },b->new LensView(view.decode(b).withLens(b.readBoolean()?1:0)));
        e.registrar("cameraoptics1").optional().playToClient(LensView.TYPE,optics,(v,c)->receiver.accept(c.connection(),v.state));
        StreamCodec<RegistryFriendlyByteBuf,Edit> edit=StreamCodec.ofMember((v,b)->{
            b.writeUUID(v.token);b.writeUUID(v.source);b.writeUtf(v.control,12);b.writeByte(v.value);b.writeUtf(v.kind,128);
        },b->new Edit(b.readUUID(),b.readUUID(),b.readUtf(12),b.readByte(),b.readUtf(128)));
        StreamCodec<RegistryFriendlyByteBuf,Motion> motion=StreamCodec.ofMember((v,b)->{b.writeBlockPos(v.pos);b.writeUtf(v.kind,128);},
                b->new Motion(b.readBlockPos(),b.readUtf(128)));
        r.playToClient(View.TYPE,view,(v,c)->receiver.accept(c.connection(),v));
        r.playToClient(Motion.TYPE,motion,(v,c)->motionReceiver.accept(c.connection(),v));
        r.playToServer(Edit.TYPE,edit,(v,c)->{if(c.player() instanceof ServerPlayer p)edit(p,v);});
    }
    static void signal(ServerPlayer p,CameraDevices.Device screen,Mob mob){
        String kind=mob==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        if(kind.length()<=128)PacketDistributor.sendToPlayer(p,new Motion(screen.getBlockPos(),kind));
    }
    private static boolean nearbyOwner(ServerPlayer p,CameraDevices.Device d){
        if(!d.owned(p)||!p.isAlive()||p.isSpectator()||d.isRemoved()||d.getLevel()!=p.level()
                ||!p.serverLevel().hasChunkAt(d.getBlockPos())||p.serverLevel().getBlockEntity(d.getBlockPos())!=d
                ||!Safety.locationReason(p,p.serverLevel(),d.getBlockPos()).isEmpty())return false;
        if(PocketService.inPocket(p.level())){
            var lease=PocketService.lease(p.getUUID());
            if(lease==null||!PocketService.visitingOwner(p,lease.pocket()))return false;
        }
        if(!(p.pick(p.blockInteractionRange(),1,false) instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return false;
        return hit.getBlockPos().equals(d.getBlockPos())||d.getBlockState().getBlock() instanceof WallMonitorBlock
                &&WallPanel.atHit(p.level(),hit,p.getEyePosition(),p.getLookAngle())==d;
    }
    static void open(ServerPlayer p,CameraDevices.Device d){
        if(!nearbyOwner(p,d))return;
        if(!p.connection.hasChannel(View.TYPE)){p.displayClientMessage(Component.literal("Update your client to open camera settings."),true);return;}
        var session=new Session(d,UUID.randomUUID(),p.serverLevel().getGameTime()+2400);
        sessions.put(p,session);show(p,session,true,"");
    }
    private static CameraDevices.Device camera(ServerPlayer p,CameraDevices.Device d){
        return d.getBlockState().is(CameraDevices.CAMERA.get())?d:CameraFeed.source(p,d);
    }
    private static void show(ServerPlayer p,Session session,boolean opening,String message){
        var d=session.device;var state=d.getBlockState();var tier=state.getBlock() instanceof WallMonitorBlock w?w:null;boolean wall=tier!=null;
        var cam=camera(p,d);
        var t=cam==null||cam.getBlockState().is(CameraDevices.SKY_CAMERA.get())?null:cam.tracking;
        var displayed=new View(session.token,cam==null||cam.identity==null?NONE:cam.identity,opening,
                wall?d.panel.width():0,wall?d.panel.height():0,wall?tier.maxW:0,wall?tier.maxH:0,
                t==null?-1:t.mode.ordinal(),t==null?-1:t.group.ordinal(),
                t==null?"":String.join(",",t.watch),t==null?"":String.join(",",t.ignore),message,t!=null&&t.wideLens?1:0);
        if(p.connection.hasChannel(LensView.TYPE))PacketDistributor.sendToPlayer(p,new LensView(displayed));
        else PacketDistributor.sendToPlayer(p,displayed);
    }
    private static void edit(ServerPlayer p,Edit e){
        var session=sessions.get(p);
        if(session==null||!session.token.equals(e.token))return;
        if(p.serverLevel().getGameTime()>session.until||!nearbyOwner(p,session.device)){
            sessions.remove(p);PacketDistributor.sendToPlayer(p,new View(e.token,NONE,false,0,0,0,0,-1,-1,"","","Access ended. Open the device again.",-1));return;
        }
        sessions.put(p,new Session(session.device,session.token,p.serverLevel().getGameTime()+2400));
        var d=session.device;var state=d.getBlockState();String message;
        if(e.control.equals("link") && state.getBlock() instanceof WallMonitorBlock){
            message=CameraDevices.linkSelected(p,d);
        }else if(e.control.equals("width")||e.control.equals("height")||e.control.equals("fit")){
            if(!(state.getBlock() instanceof WallMonitorBlock))return;
            message=WallMonitorBlock.resize(p,d,e.control.equals("width")?e.value:d.panel.width(),
                    e.control.equals("height")?e.value:d.panel.height(),e.control.equals("fit"));
        }else{
            var cam=camera(p,d);
            if(cam==null||cam.identity==null||!cam.identity.equals(e.source))message="The selected camera is unavailable.";
            else if(!Safety.buildReason(p,(net.minecraft.server.level.ServerLevel)cam.getLevel(),cam.getBlockPos(),cam.getBlockState(),false).isEmpty())
                message="You cannot change that camera here.";
            else if(cam.getBlockState().is(CameraDevices.SKY_CAMERA.get()))message="A Sky Camera always faces up.";
            else message=configure(p,cam,e);
        }
        show(p,session,false,message.isEmpty()?"Saved.":message);
    }
    private static String configure(ServerPlayer p,CameraDevices.Device cam,Edit e){
        var t=cam.tracking;
        switch(e.control){
            case "lens" -> {
                if(e.value!=0 && e.value!=1)return "Unknown lens.";
                t.wideLens=e.value==1;cam.setChanged();return "";
            }
            case "mode" -> {
                if(e.value<0||e.value>=CameraTracking.Mode.values().length)return "Unknown tracking mode.";
                var mode=CameraTracking.Mode.values()[e.value];
                if(mode==CameraTracking.Mode.MOTION&&!cam.getBlockState().getValue(BlockStateProperties.FACING).getAxis().isHorizontal())
                    return "Mount the camera on a wall for motion tracking.";
                CameraTracking.aim(p,(net.minecraft.server.level.ServerLevel)cam.getLevel(),cam,t.rest);
                t.mode=mode;
            }
            case "group" -> {
                if(e.value<0||e.value>=CameraTracking.Group.values().length)return "Unknown target group.";
                t.group=CameraTracking.Group.values()[e.value];
            }
            case "rule" -> {
                String result=t.rule(e.kind,e.value);if(!result.isEmpty())return result;
            }
            default -> {return "Unknown control.";}
        }
        t.clearMotion();cam.setChanged();return "";
    }
}
