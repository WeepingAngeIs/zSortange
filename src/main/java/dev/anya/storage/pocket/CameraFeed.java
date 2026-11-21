package dev.anya.storage.pocket;

import dev.anya.storage.*;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid=AnyaStorage.ID)
public final class CameraFeed {
    static final int SKY=0x1000000, UNKNOWN=0x2000000;
    record Watch(BlockPos pos) implements CustomPacketPayload {
        static final Type<Watch> TYPE=new Type<>(ExpansionPackets.id("camera_watch"));public Type<Watch> type(){return TYPE;}
    }
    record Frame(BlockPos pos,long time,int background,float clarity,int[] pixels) implements CustomPacketPayload {
        static final Type<Frame> TYPE=new Type<>(ExpansionPackets.id("camera_frame"));public Type<Frame> type(){return TYPE;}
    }
    static BiConsumer<Connection,Frame> receiver=(c,f)->{};
    public static void register(RegisterPayloadHandlersEvent e){
        var channel=e.registrar("skyglass2").optional();
        StreamCodec<RegistryFriendlyByteBuf,Watch> watch=StreamCodec.ofMember((w,b)->b.writeBlockPos(w.pos),b->new Watch(b.readBlockPos()));
        StreamCodec<RegistryFriendlyByteBuf,Frame> frame=StreamCodec.ofMember((f,b)->{b.writeBlockPos(f.pos);b.writeLong(f.time);b.writeInt(f.background);b.writeFloat(f.clarity);b.writeVarIntArray(f.pixels);},
                b->new Frame(b.readBlockPos(),b.readLong(),b.readInt(),b.readFloat(),b.readVarIntArray(256)));
        channel.playToServer(Watch.TYPE,watch,(w,c)->{if(c.player() instanceof ServerPlayer p)watch(p,w.pos);});
        channel.playToClient(Frame.TYPE,frame,(f,c)->receiver.accept(c.connection(),f));
    }
    static Vec3 normal(CameraDevices.Device d){return Vec3.atLowerCornerOf(d.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING).getNormal());}
    static Vec3 screenCenter(CameraDevices.Device d){return Vec3.atCenterOf(d.getBlockPos()).add(normal(d).scale(.512));}
    static Vec3 looking(CameraDevices.Device d,Vec3 eye,Vec3 look){
        var n=normal(d);double dot=n.dot(look);if(dot>=-.02)return null;
        var c=screenCenter(d);double t=c.subtract(eye).dot(n)/dot;if(t<0 || t>10)return null;
        var hit=eye.add(look.scale(t));var delta=hit.subtract(c);double radius=d.getBlockState().is(CameraDevices.SKY.get())?2:.5;
        return Math.max(Math.max(Math.abs(delta.x),Math.abs(delta.y)),Math.abs(delta.z))<radius?hit:null;
    }
    private record Session(CameraDevices.Device device,long renewed){}
    private static final Map<ServerPlayer,Session> sessions=new WeakHashMap<>();
    private static void watch(ServerPlayer p,BlockPos pos){
        if(!p.isAlive() || p.isSpectator() || p.distanceToSqr(Vec3.atCenterOf(pos))>144 || !p.connection.hasChannel(Frame.TYPE))return;
        var blocks=new LoadedPocketBlocks(p.serverLevel());
        if(!(blocks.getBlockEntity(pos) instanceof CameraDevices.Device d) || d.getBlockState().is(CameraDevices.CAMERA.get()))return;
        if(!sessions.containsKey(p) && sessions.size()>=4)return;
        sessions.put(p,new Session(d,p.serverLevel().getGameTime()));
    }
    private static CameraDevices.Device source(ServerPlayer p,CameraDevices.Device d){
        if(!d.owned(p) || p.isSpectator() || !p.isAlive() || d.getLevel()!=p.level() || d.isRemoved() || d.linked==null || d.owner==null)return null;
        var eye=p.getEyePosition();var hit=looking(d,eye,p.getLookAngle());if(hit==null)return null;
        var blocks=new LoadedPocketBlocks(p.serverLevel());
        if(blocks.getBlockEntity(d.getBlockPos())!=d || blocks.clip(new ClipContext(eye,hit,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p)).getType()!=HitResult.Type.MISS
                || !blocks.complete() || !Safety.locationReason(p,p.serverLevel(),d.getBlockPos()).isEmpty())return null;
        if(PocketService.inPocket(p.level())){
            var l=PocketService.lease(p.getUUID());var room=l==null?null:PocketService.loadedPocket(p.getServer(),l.pocket());
            if(room==null || l.kind()!=PocketLeases.Kind.VISIT || !room.owner().equals(d.owner) || !room.contains(d.getBlockPos().getX(),d.getBlockPos().getY(),d.getBlockPos().getZ())
                    || !PocketService.authorize(p,room,l.kind(),false,BasePermissions.VIEW).isEmpty())return null;
        }else if(!d.owned(p))return null;
        var name=ResourceLocation.tryParse(d.dimension);if(name==null)return null;
        var level=p.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,name));
        if(level==null || PocketService.inPocket(level))return null;
        if(!(new LoadedPocketBlocks(level).getBlockEntity(d.camera) instanceof CameraDevices.Device cam)
                || !cam.getBlockState().is(CameraDevices.CAMERA.get()) || !d.owner.equals(cam.owner) || !d.linked.equals(cam.identity)
                || !Safety.locationReason(p,level,cam.getBlockPos()).isEmpty())return null;
        return cam;
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){
        for(var entry:new ArrayList<>(sessions.entrySet())){
            var p=entry.getKey();var session=entry.getValue();if(p==null || session==null)continue;
            if(p.getServer()!=e.getServer() || e.getServer().getPlayerList().getPlayer(p.getUUID())!=p){sessions.remove(p);continue;}
            var d=session.device;
            try{
                var cam=source(p,d);
                if(cam==null || p.serverLevel().getGameTime()-session.renewed>18){sessions.remove(p);send(p,d,-1,null,new int[0]);continue;}
                if(Math.floorMod(e.getServer().getTickCount()+p.getId(),12)!=0)continue;
                var level=(ServerLevel)cam.getLevel();
                boolean sky=d.getBlockState().is(CameraDevices.SKY.get());
                if(sky && OutdoorCameraBlock.lensDirection(cam.getBlockState())!=Direction.UP){send(p,d,-1,null,new int[0]);continue;}
                var atmosphere=SourceSky.observe(level,cam.getBlockPos());
                int[] pixels=capture(p,level,cam,sky,atmosphere);
                if(source(p,d)!=cam || Arrays.stream(pixels).allMatch(pixel->pixel==UNKNOWN)){send(p,d,-1,null,new int[0]);continue;}
                send(p,d,level.getDayTime(),atmosphere,pixels);
            }catch(RuntimeException unavailable){sessions.remove(p);send(p,d,-1,null,new int[0]);}
        }
    }
    private static void send(ServerPlayer p,CameraDevices.Device d,long time,SourceSky sky,int[] pixels){
        PacketDistributor.sendToPlayer(p,new Frame(d.getBlockPos(),time,sky==null?-1:sky.background(),sky==null?0:sky.clarity(),pixels));
    }
    private static int[] capture(ServerPlayer p,ServerLevel level,CameraDevices.Device cam,boolean sky){
        return capture(p,level,cam,sky,null);
    }
    private static int[] capture(ServerPlayer p,ServerLevel level,CameraDevices.Device cam,boolean sky,SourceSky atmosphere){
        var loaded=new LoadedPocketBlocks(level);
        var face=OutdoorCameraBlock.lensDirection(cam.getBlockState());
        var eye=Vec3.atCenterOf(cam.getBlockPos()).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.58));
        var access=new HashMap<BlockPos,Boolean>();var pixels=new int[256];
        var target=!sky && cam.glances && Math.floorMod(level.getGameTime(),300)<80?glance(p,level,eye):null;
        var forward=target==null?null:target.getEyePosition().subtract(eye).normalize();
        // Spend the access budget on the center first; unknown peripheral rays stay masked.
        for(int row=0;row<16;row++)for(int col=0;col<16;col++){
            int x=8+((col&1)==0?col/2:-(col+1)/2),y=8+((row&1)==0?row/2:-(row+1)/2);
            Vec3 ray=CameraOptics.ray(face,x,y);
            if(forward!=null){
                var right=forward.cross(new Vec3(0,1,0)).normalize();var up=right.cross(forward);
                ray=forward.add(right.scale((x-7.5)/8)).add(up.scale((7.5-y)/8)).normalize();
            }
            int pixel=sample(p,level,loaded,eye,ray,access,target);
            pixels[y*16+x]=pixel==SKY && atmosphere!=null?atmosphere.direction(ray):pixel;
        }
        return pixels;
    }
    private static boolean allowed(ServerPlayer p,ServerLevel level,BlockPos pos,Map<BlockPos,Boolean> cache){
        var old=cache.get(pos);if(old!=null)return old;
        if(cache.size()>=1024 || !level.hasChunkAt(pos))return false;
        boolean ok=Safety.locationReason(p,level,pos).isEmpty();cache.put(pos.immutable(),ok);return ok;
    }
    private static int sample(ServerPlayer p,ServerLevel level,LoadedPocketBlocks blocks,Vec3 eye,Vec3 ray,Map<BlockPos,Boolean> access,Mob mob){
        var end=eye.add(ray.scale(24));
        var silhouette=mob==null?null:mob.getBoundingBox().clip(eye,end).map(BlockPos::containing).orElse(null);
        return net.minecraft.world.level.BlockGetter.traverseBlocks(eye,end,ray,(dir,pos)->{
            if(!allowed(p,level,pos,access))return UNKNOWN;
            var state=blocks.getBlockState(pos);
            if(blocks.chunk(pos.getX()>>4,pos.getZ()>>4)==null)return UNKNOWN;
            if(state.hasBlockEntity())return UNKNOWN;
            if(!state.isAir()){
                var shape=state.getShape(blocks,pos);
                if(!shape.isEmpty() && shape.clip(eye,end,pos)!=null){
                    int color=state.getMapColor(blocks,pos).col;
                    return color==0?0x5a5360:color;
                }
            }
            if(pos.equals(silhouette))return 0xd4b9db;
            return null;
        },dir->{
            var pos=BlockPos.containing(end);
            if(dir.y<=.15 || !level.dimensionType().hasSkyLight() || !allowed(p,level,pos,access))return UNKNOWN;
            // Prove there is no roof farther along this slanted ray, without loading more chunks.
            double distance=(level.getMaxBuildHeight()-end.y)/dir.y;
            if(distance<=0)return SKY;
            if(distance>600)return UNKNOWN;
            return net.minecraft.world.level.BlockGetter.traverseBlocks(end,end.add(dir.scale(distance)),blocks,
                    (columns,cell)->{
                        var chunk=columns.chunk(cell.getX()>>4,cell.getZ()>>4);
                        if(chunk==null || chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,cell.getX()&15,cell.getZ()&15)>=cell.getY())return UNKNOWN;
                        return null;
                    },columns->SKY);
        });
    }
    private static Mob glance(ServerPlayer p,ServerLevel level,Vec3 eye){
        var mobs=new ArrayList<Mob>();
        level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),new AABB(eye,eye).inflate(7),
                mob->mob.isAlive()&&!mob.isInvisible()&&!(mob instanceof TamableAnimal t&&t.isTame()),mobs,16);
        Mob best=null;double score=-1;
        for(var m:mobs){
            if(!Safety.locationReason(p,level,m.blockPosition()).isEmpty())continue;
            var access=new HashMap<BlockPos,Boolean>();
            boolean visible=net.minecraft.world.level.BlockGetter.traverseBlocks(eye,m.getEyePosition(),access,
                    (cache,pos)->allowed(p,level,pos,cache)?null:Boolean.FALSE,cache->Boolean.TRUE);
            if(!visible)continue;
            var blocks=new LoadedPocketBlocks(level);
            if(blocks.clip(new ClipContext(eye,m.getEyePosition(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p)).getType()!=HitResult.Type.MISS||!blocks.complete())continue;
            double weight=8/(1+m.distanceToSqr(eye));
            if(m.getClass().getName().startsWith("com.cobblemon.")){
                try{
                    var pokemon=m.getClass().getMethod("getPokemon").invoke(m);
                    var api=pokemon.getClass();
                    if(!Boolean.TRUE.equals(api.getMethod("isWild").invoke(pokemon)) || api.getMethod("getOwnerUUID").invoke(pokemon)!=null)continue;
                    var species=pokemon.getClass().getMethod("getSpecies").invoke(pokemon);
                    if(Boolean.TRUE.equals(api.getMethod("isLegendary").invoke(pokemon))
                            || Boolean.TRUE.equals(api.getMethod("isMythical").invoke(pokemon))
                            || Boolean.TRUE.equals(api.getMethod("isUltraBeast").invoke(pokemon))
                            || CameraRarity.rare(level.getServer(),species)
                            || species.getClass().getMethod("getLabels").invoke(species) instanceof Collection<?> labels
                            && labels.stream().anyMatch(s->Set.of("legendary","mythical","ultra_beast","rare").contains(s)))weight+=20;
                }catch(ReflectiveOperationException unknown){continue;}
            }
            if(weight>score){score=weight;best=m;}
        }
        return best;
    }
}
