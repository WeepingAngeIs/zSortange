package dev.anya.storage.pocket;

import dev.anya.storage.*;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** A ceiling is room decor. Only the physically present owner receives camera pixels. */
public final class SkyCeiling extends SavedData {
    private final CompoundTag choices, rooms;
    private SkyCeiling(){choices=new CompoundTag();rooms=new CompoundTag();}
    private SkyCeiling(CompoundTag n,HolderLookup.Provider r){choices=n.getCompound("Cameras").copy();rooms=n.getCompound("Rooms").copy();}
    @Override public CompoundTag save(CompoundTag n,HolderLookup.Provider r){n.put("Cameras",choices.copy());n.put("Rooms",rooms.copy());return n;}
    private static SkyCeiling data(ServerPlayer p){return p.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(SkyCeiling::new,SkyCeiling::new,null),"anya_skygrid_ceiling");}
    static boolean remember(ServerPlayer p,CameraDevices.Device d){
        if(d.identity==null||!d.owned(p)||d.getLevel()!=p.level()||d.isRemoved()||PocketService.inPocket(p.level())
                ||!d.getBlockState().is(CameraDevices.SKY_CAMERA.get())||!Safety.locationReason(p,p.serverLevel(),d.getBlockPos()).isEmpty())return false;
        var n=new CompoundTag();n.putUUID("Id",d.identity);n.putString("Dimension",p.level().dimension().location().toString());n.putLong("Pos",d.getBlockPos().asLong());
        var store=data(p);store.choices.put(p.getStringUUID(),n);store.setDirty();return true;
    }
    static CameraDevices.Device resolve(ServerPlayer p,String dimension,BlockPos pos,UUID identity){
        if(identity==null||!p.isAlive()||p.isSpectator())return null;
        var id=ResourceLocation.tryParse(dimension);if(id==null)return null;
        var l=p.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,id));
        if(l==null||PocketService.inPocket(l)||!(new LoadedPocketBlocks(l).getBlockEntity(pos) instanceof CameraDevices.Device d)
                ||!CameraDevices.isCamera(d.getBlockState())||!d.owned(p)||!identity.equals(d.identity)||!Safety.locationReason(p,l,pos).isEmpty())return null;
        return d;
    }
    private static CameraDevices.Device sky(ServerPlayer p,CompoundTag n){
        if(!n.hasUUID("Id"))return null;
        var d=resolve(p,n.getString("Dimension"),BlockPos.of(n.getLong("Pos")),n.getUUID("Id"));
        return d!=null&&d.getBlockState().is(CameraDevices.SKY_CAMERA.get())?d:null;
    }
    public static boolean enabled(ServerPlayer p,UUID id){
        return PocketService.visitingOwner(p,id)&&data(p).rooms.getCompound(id.toString()).getBoolean("Enabled");
    }
    public static void set(ServerPlayer p,UUID id,boolean on){
        if(!PocketService.visitingOwner(p,id))return;
        var store=data(p);var n=store.rooms.getCompound(id.toString());
        if(on){
            var selected=store.choices.getCompound(p.getStringUUID());
            if(sky(p,selected)==null){
                p.displayClientMessage(Component.literal("Sneak-use your tablet on your outdoor Sky Camera first. Its area must be loaded."),true);return;
            }
            n=selected.copy();
        }
        n.putBoolean("Enabled",on);store.rooms.put(id.toString(),n);store.setDirty();
        if(!on)end(p.getUUID(),p.getServer());
        p.displayClientMessage(Component.literal(on?"Live ceiling on. Look up.":"Live ceiling off."),true);
    }
    record Frame(BlockPos origin,boolean active,int background,float clarity,int[] pixels) implements CustomPacketPayload {
        static final Type<Frame> TYPE=new Type<>(ExpansionPackets.id("skygrid_live_ceiling"));
        public Type<Frame> type(){return TYPE;}
    }
    static BiConsumer<Connection,Frame> receiver=(c,f)->{};
    static void register(RegisterPayloadHandlersEvent e){
        StreamCodec<RegistryFriendlyByteBuf,Frame> codec=StreamCodec.ofMember((f,b)->{
            b.writeBlockPos(f.origin);b.writeBoolean(f.active);b.writeInt(f.background);b.writeFloat(f.clarity);b.writeVarIntArray(f.pixels);
        },b->new Frame(b.readBlockPos(),b.readBoolean(),b.readInt(),b.readFloat(),b.readVarIntArray(1024)));
        e.registrar("camerastudio1").optional().playToClient(Frame.TYPE,codec,(f,c)->receiver.accept(c.connection(),f));
    }
    private static final Map<ServerPlayer,UUID> showing=new WeakHashMap<>();
    static void visit(ServerPlayer p,PocketLedger.Pocket room,long tick){
        if(!p.connection.hasChannel(Frame.TYPE))return;
        if(!room.owner().equals(p.getUUID())||p.isSpectator()||!p.isAlive()||p.getCamera()!=p||CameraFeed.viewDirection(p).y<.12
                ||!room.contains(p.getBlockX(),p.getBlockY(),p.getBlockZ())){
            if(showing.containsKey(p))end(p.getUUID(),p.getServer());return;
        }
        if(Math.floorMod(tick+p.getId(),12)!=0)return;
        var entry=data(p).rooms.getCompound(room.id().toString());
        if(!entry.getBoolean("Enabled")||!PocketService.authorize(p,room,PocketLeases.Kind.VISIT,false,BasePermissions.VIEW).isEmpty()){
            end(p.getUUID(),p.getServer());return;
        }
        var origin=new BlockPos(room.x(),65,room.z());SourceSky atmosphere=null;int[] pixels={};
        try{
            var cam=sky(p,entry);
            if(cam!=null&&Safety.locationReason(p,p.serverLevel(),p.blockPosition()).isEmpty()){
                var l=(ServerLevel)cam.getLevel();atmosphere=SourceSky.observe(l,cam.getBlockPos());
                pixels=CameraFeed.capture(p,l,cam,atmosphere,null,32);
                if(sky(p,entry)!=cam||Arrays.stream(pixels).allMatch(v->v==CameraFeed.UNKNOWN))pixels=new int[0];
            }
        }catch(RuntimeException unavailable){pixels=new int[0];}
        showing.put(p,room.id());PacketDistributor.sendToPlayer(p,new Frame(origin,true,atmosphere==null?-1:atmosphere.background(),atmosphere==null?0:atmosphere.clarity(),pixels));
    }
    static void end(UUID id,MinecraftServer server){
        if(server==null)return;var p=server.getPlayerList().getPlayer(id);
        if(p!=null&&showing.remove(p)!=null&&p.connection.hasChannel(Frame.TYPE))
            PacketDistributor.sendToPlayer(p,new Frame(BlockPos.ZERO,false,-1,0,new int[0]));
    }
}
