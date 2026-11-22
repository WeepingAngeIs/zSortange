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
    record WideFrame(Frame frame) implements CustomPacketPayload {
        static final Type<WideFrame> TYPE=new Type<>(ExpansionPackets.id("wide_wall_frame"));public Type<WideFrame> type(){return TYPE;}
    }
    static BiConsumer<Connection,Frame> receiver=(c,f)->{};
    public static void register(RegisterPayloadHandlersEvent e){
        CameraConsole.register(e);SkyCeiling.register(e);CameraScene.register(e);
        var channel=e.registrar("skyglass2").optional();
        StreamCodec<RegistryFriendlyByteBuf,Watch> watch=StreamCodec.ofMember((w,b)->b.writeBlockPos(w.pos),b->new Watch(b.readBlockPos()));
        StreamCodec<RegistryFriendlyByteBuf,Frame> frame=StreamCodec.ofMember((f,b)->{b.writeBlockPos(f.pos);b.writeLong(f.time);b.writeInt(f.background);b.writeFloat(f.clarity);b.writeVarIntArray(f.pixels);},
                b->new Frame(b.readBlockPos(),b.readLong(),b.readInt(),b.readFloat(),b.readVarIntArray(256)));
        channel.playToServer(Watch.TYPE,watch,(w,c)->{if(c.player() instanceof ServerPlayer p)watch(p,w.pos);});
        channel.playToClient(Frame.TYPE,frame,(f,c)->receiver.accept(c.connection(),f));
        StreamCodec<RegistryFriendlyByteBuf,WideFrame> wide=StreamCodec.ofMember((v,b)->frame.encode(b,v.frame),
                b->new WideFrame(new Frame(b.readBlockPos(),b.readLong(),b.readInt(),b.readFloat(),b.readVarIntArray(1024))));
        e.registrar("camerastudio1").optional().playToClient(WideFrame.TYPE,wide,(v,c)->receiver.accept(c.connection(),v.frame));
    }
    @SubscribeEvent public static void unload(net.neoforged.neoforge.event.level.LevelEvent.Unload e){
        if(e.getLevel() instanceof Level level)WallPanel.unload(level);
    }
    static Direction direction(CameraDevices.Device cam){return cam.getBlockState().is(CameraDevices.SKY_CAMERA.get())?Direction.UP:OutdoorCameraBlock.lensDirection(cam.getBlockState());}
    static Vec3 eye(CameraDevices.Device cam){return cam.getBlockState().is(CameraDevices.SKY_CAMERA.get())?Vec3.atBottomCenterOf(cam.getBlockPos()).add(0,4.5/16+.015625,0):OutdoorCameraBlock.lensEye(cam.getBlockPos(),cam.getBlockState());}
    static Vec3 normal(CameraDevices.Device d){return Vec3.atLowerCornerOf(d.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING).getNormal());}
    static Vec3 screenCenter(CameraDevices.Device d){
        if(d.getBlockState().getBlock() instanceof WallMonitorBlock)return d.panel.center(d);
        return Vec3.atCenterOf(d.getBlockPos()).add(normal(d).scale(.512));}
    static Vec3 looking(CameraDevices.Device d,Vec3 eye,Vec3 look){
        var n=normal(d);double dot=n.dot(look);if(dot>=-.02)return null;
        var c=screenCenter(d);double t=c.subtract(eye).dot(n)/dot;if(t<0 || t>(d.getBlockState().getBlock() instanceof WallMonitorBlock?48:10))return null;
        var hit=eye.add(look.scale(t));var delta=hit.subtract(c);
        if(d.getBlockState().getBlock() instanceof WallMonitorBlock){
            int w=d.panel.width(),h=d.panel.height();
            return Math.max(Math.abs(delta.x),Math.abs(delta.z))<w*.5 && Math.abs(delta.y)<h*.5?hit:null;
        }
        double radius=d.getBlockState().is(CameraDevices.SKY.get())?2:.5;
        return Math.max(Math.max(Math.abs(delta.x),Math.abs(delta.y)),Math.abs(delta.z))<radius?hit:null;
    }
    // A TV stays visible beside the crosshair. Both ends validate the same point on its
    // surface, with distance, room rights, a sight line and the claim at that exact cell.
    static Vec3 visiblePoint(CameraDevices.Device d,Vec3 eye,Vec3 look){
        var direct=looking(d,eye,look);
        if(direct!=null||!(d.getBlockState().getBlock() instanceof WallMonitorBlock))return direct;
        var n=normal(d);var c=screenCenter(d);double dot=n.dot(look),depth=c.subtract(eye).dot(n);
        if(dot>=-.04||depth>=0)return null;
        var projected=eye.add(look.scale(depth/dot)).subtract(c);
        var across=Vec3.atLowerCornerOf(WallPanel.right(WallPanel.face(d)).getNormal());
        double x=Math.clamp(projected.dot(across),.03-d.panel.width()*.5,d.panel.width()*.5-.03);
        double y=Math.clamp(projected.y,.03-d.panel.height()*.5,d.panel.height()*.5-.03);
        var hit=c.add(across.scale(x)).add(0,y,0);var ray=hit.subtract(eye);
        return ray.lengthSqr()<=48*48&&ray.normalize().dot(look)>.89?hit:null;
    }
    private record Session(CameraDevices.Device device,long renewed){}
    private static final Map<ServerPlayer,Session> sessions=new WeakHashMap<>();
    private static void watch(ServerPlayer p,BlockPos pos){
        if(!p.isAlive() || p.isSpectator() || p.distanceToSqr(Vec3.atCenterOf(pos))>10000 || !p.connection.hasChannel(Frame.TYPE))return;
        var blocks=new LoadedPocketBlocks(p.serverLevel());
        if(!(blocks.getBlockEntity(pos) instanceof CameraDevices.Device d) || CameraDevices.isCamera(d.getBlockState()))return;
        if(!sessions.containsKey(p) && sessions.size()>=4)return;
        sessions.put(p,new Session(d,p.serverLevel().getGameTime()));
    }
    static CameraDevices.Device source(ServerPlayer p,CameraDevices.Device d){
        if(!d.owned(p) || p.isSpectator() || !p.isAlive() || d.getLevel()!=p.level() || d.isRemoved() || d.linked==null || d.owner==null)return null;
        var eye=p.getEyePosition();var hit=visiblePoint(d,eye,p.getLookAngle());if(hit==null)return null;
        var blocks=new LoadedPocketBlocks(p.serverLevel());
        if(blocks.getBlockEntity(d.getBlockPos())!=d || blocks.clip(new ClipContext(eye,hit,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p)).getType()!=HitResult.Type.MISS
                || !blocks.complete() || !Safety.locationReason(p,p.serverLevel(),BlockPos.containing(hit.add(normal(d).scale(.004)))).isEmpty() || !Safety.locationReason(p,p.serverLevel(),d.getBlockPos()).isEmpty())return null;
        if(PocketService.inPocket(p.level())){
            var l=PocketService.lease(p.getUUID());var room=l==null?null:PocketService.loadedPocket(p.getServer(),l.pocket());
            if(room==null || l.kind()!=PocketLeases.Kind.VISIT || !room.owner().equals(d.owner) || !room.contains(d.getBlockPos().getX(),d.getBlockPos().getY(),d.getBlockPos().getZ())
                    || !PocketService.authorize(p,room,l.kind(),false,BasePermissions.VIEW).isEmpty())return null;
        }else if(!d.owned(p))return null;
        return SkyCeiling.resolve(p,d.dimension,d.camera,d.linked);
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
                if(sky && direction(cam)!=Direction.UP){send(p,d,-1,null,new int[0]);continue;}
                var atmosphere=SourceSky.observe(level,cam.getBlockPos());
                var target=subject(p,level,cam,sky);
                if(sharpWall(p,d)) {
                    var scene=CameraSceneCapture.capture(p,d,level,cam,atmosphere,target);
                    if(scene.time()<0 || source(p,d)!=cam){send(p,d,-1,null,new int[0]);continue;}
                    PacketDistributor.sendToPlayer(p,scene);
                    if(p.connection.hasChannel(CameraConsole.Motion.TYPE))CameraConsole.signal(p,d,cam.tracking.mode==CameraTracking.Mode.MOTION?target:null);
                    continue;
                }
                int side=d.getBlockState().getBlock() instanceof WallMonitorBlock&&Math.max(d.panel.width(),d.panel.height())>3&&p.connection.hasChannel(WideFrame.TYPE)?32:16;
                int[] pixels=capture(p,level,cam,atmosphere,target,side);
                if(source(p,d)!=cam || Arrays.stream(pixels).allMatch(pixel->pixel==UNKNOWN)){send(p,d,-1,null,new int[0]);continue;}
                send(p,d,level.getDayTime(),atmosphere,pixels);
                if(p.connection.hasChannel(CameraConsole.Motion.TYPE))CameraConsole.signal(p,d,cam.tracking.mode==CameraTracking.Mode.MOTION?target:null);
            }catch(RuntimeException unavailable){sessions.remove(p);send(p,d,-1,null,new int[0]);}
        }
    }
    private static boolean sharpWall(ServerPlayer p,CameraDevices.Device d) {
        return d.getBlockState().getBlock() instanceof WallMonitorBlock && p.connection.hasChannel(CameraScene.TYPE);
    }
    private static void send(ServerPlayer p,CameraDevices.Device d,long time,SourceSky sky,int[] pixels){
        if(time<0 && sharpWall(p,d)){
            PacketDistributor.sendToPlayer(p,CameraScene.empty(d.getBlockPos()));return;
        }
        var frame=new Frame(d.getBlockPos(),time,sky==null?-1:sky.background(),sky==null?0:sky.clarity(),pixels);
        if(pixels.length==1024)PacketDistributor.sendToPlayer(p,new WideFrame(frame));else PacketDistributor.sendToPlayer(p,frame);
    }
    private static int[] capture(ServerPlayer p,ServerLevel level,CameraDevices.Device cam,boolean sky){
        return capture(p,level,cam,null,subject(p,level,cam,sky));
    }
    private static Mob subject(ServerPlayer p,ServerLevel level,CameraDevices.Device cam,boolean sky){
        if(sky)return null;
        var eye=eye(cam);
        if(cam.tracking.mode==CameraTracking.Mode.GLANCE && Math.floorMod(level.getGameTime(),300)<80)
            return glance(p,level,eye,cam.tracking::accepts);
        return cam.tracking.visibleTarget(p,level,cam,eye);
    }
    private static int[] capture(ServerPlayer p,ServerLevel level,CameraDevices.Device cam,SourceSky atmosphere,Mob target){
        return capture(p,level,cam,atmosphere,target,16);
    }
    static int[] capture(ServerPlayer p,ServerLevel level,CameraDevices.Device cam,SourceSky atmosphere,Mob target,int side){
        var loaded=new LoadedPocketBlocks(level);
        var face=direction(cam);
        var eye=eye(cam);
        var access=new HashMap<BlockPos,Boolean>();var pixels=new int[side*side];
        var skyClearance=new CameraSkyClearance(p,level,loaded,cam.getBlockPos(),eye);
        var flyers=new ArrayList<CameraFigure>();
        boolean upwardLens=cam.getBlockState().is(CameraDevices.SKY_CAMERA.get());
        double aperture=upwardLens?CameraOptics.skyAperture(level,eye):1;
        int accessBudget=upwardLens?3072:1024;
        if(upwardLens){
            var bounds=new AABB(eye.x-24,eye.y,eye.z-24,eye.x+24,eye.y+24,eye.z+24);
            var people=new ArrayList<ServerPlayer>();
            level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(ServerPlayer.class),bounds,
                    actor->actor.isAlive()&&!actor.isSpectator()&&!actor.isInvisible()&&!actor.isInvisibleTo(p)&&actor.broadcastToPlayer(p)
                            &&actor.distanceToSqr(eye)<=24*24&&Safety.locationReason(p,level,actor.blockPosition()).isEmpty(),people,8);
            for(var actor:people)CameraFigure.add(flyers,actor);
        }
        var forward=target==null?null:target.getEyePosition().subtract(eye).normalize();
        // Spend the access budget on the center first; unknown peripheral rays stay masked.
        for(int row=0;row<side;row++)for(int col=0;col<side;col++){
            int x=side/2+((col&1)==0?col/2:-(col+1)/2),y=side/2+((row&1)==0?row/2:-(row+1)/2);
            Vec3 ray=CameraOptics.ray(face,x,y,side,aperture);
            if(forward!=null){
                var right=forward.cross(new Vec3(0,1,0)).normalize();var up=right.cross(forward);
                ray=forward.add(right.scale((2.0*x+1)/side-1)).add(up.scale(1-(2.0*y+1)/side)).normalize();
            }
            int pixel=sample(p,level,loaded,eye,ray,access,target,cam.getBlockPos(),flyers,accessBudget,skyClearance,x,y,side);
            pixels[y*side+x]=pixel==SKY && atmosphere!=null?atmosphere.direction(ray):pixel;
        }
        return pixels;
    }
    private static boolean allowed(ServerPlayer p,ServerLevel level,BlockPos pos,Map<BlockPos,Boolean> cache){
        return allowed(p,level,pos,cache,1024);
    }
    private static boolean allowed(ServerPlayer p,ServerLevel level,BlockPos pos,Map<BlockPos,Boolean> cache,int limit){
        var old=cache.get(pos);if(old!=null)return old;
        if(cache.size()>=limit || !level.hasChunkAt(pos))return false;
        boolean ok=Safety.locationReason(p,level,pos).isEmpty();cache.put(pos.immutable(),ok);return ok;
    }
    private static int sample(ServerPlayer p,ServerLevel level,LoadedPocketBlocks blocks,Vec3 eye,Vec3 ray,Map<BlockPos,Boolean> access,Mob mob,BlockPos camera,List<CameraFigure> flyers,int budget,CameraSkyClearance skyClearance,int x,int y,int side){
        var end=eye.add(ray.scale(24));
        Vec3 first=mob==null?null:mob.getBoundingBox().clip(eye,end).orElse(null);
        int tint=0xd4b9db;
        for(var box:flyers){
            var hit=box.box().clip(eye,end).orElse(null);
            if(hit!=null&&(first==null||hit.distanceToSqr(eye)<first.distanceToSqr(eye))){first=hit;tint=box.color();}
        }
        final BlockPos silhouette=first==null?null:BlockPos.containing(first.add(ray.scale(.0001)));
        final int actorColor=tint;
        return net.minecraft.world.level.BlockGetter.traverseBlocks(eye,end,ray,(dir,pos)->{
            if(!allowed(p,level,pos,access,budget))return UNKNOWN;
            var state=blocks.getBlockState(pos);
            if(blocks.chunk(pos.getX()>>4,pos.getZ()>>4)==null)return UNKNOWN;
            if(pos.equals(camera))return null; // The smaller lens starts inside its own cell.
            if(state.hasBlockEntity())return UNKNOWN;
            if(!state.isAir()){
                var shape=state.getShape(blocks,pos);
                if(!shape.isEmpty() && shape.clip(eye,end,pos)!=null){
                    int color=state.getMapColor(blocks,pos).col;
                    return color==0?0x5a5360:color;
                }
            }
            if(pos.equals(silhouette))return actorColor;
            return null;
        },dir->{
            var pos=BlockPos.containing(end);
            if(dir.y<=.15 || !level.dimensionType().hasSkyLight() || !allowed(p,level,pos,access,budget))return UNKNOWN;
            return skyClearance.sector(x,y,side,dir)?SKY:UNKNOWN;
        });
    }
    static List<Mob> nearby(ServerLevel level,Vec3 eye,java.util.function.Predicate<Mob> filter){
        var mobs=new ArrayList<Mob>();
        level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),new AABB(eye,eye).inflate(7),
                mob->mob.isAlive()&&!mob.isInvisible()&&!(mob instanceof TamableAnimal t&&t.isTame())&&filter.test(mob),mobs,16);
        return mobs;
    }
    private static Mob glance(ServerPlayer p,ServerLevel level,Vec3 eye){return glance(p,level,eye,m->true);}
    private static Mob glance(ServerPlayer p,ServerLevel level,Vec3 eye,java.util.function.Predicate<Mob> filter){
        Mob best=null;double score=-1;
        for(var mob:nearby(level,eye,filter)){
            double weight=visibleWeight(p,level,eye,mob);
            if(weight>score){score=weight;best=mob;}
        }
        return best;
    }
    static double visibleWeight(ServerPlayer p,ServerLevel level,Vec3 eye,Mob m){
        if(!m.isAlive()||m.isInvisible()||m instanceof TamableAnimal pet&&pet.isTame()||m.distanceToSqr(eye)>64)return -1;
        if(!Safety.locationReason(p,level,m.blockPosition()).isEmpty())return -1;
        var blocks=new LoadedPocketBlocks(level);var access=new HashMap<BlockPos,Boolean>();
        var lensCell=BlockPos.containing(eye);var end=m.getEyePosition();
        boolean visible=net.minecraft.world.level.BlockGetter.traverseBlocks(eye,end,blocks,(world,pos)->{
            if(!allowed(p,level,pos,access))return Boolean.FALSE;
            var state=world.getBlockState(pos);
            // A swiveling head can briefly cross its own rays. Adjacent walls always occlude.
            if(pos.equals(lensCell)&&CameraDevices.isCamera(state))return null;
            if(state.hasBlockEntity()||state.getCollisionShape(world,pos).clip(eye,end,pos)!=null)return Boolean.FALSE;
            return null;
        },world->Boolean.TRUE);
        if(!visible||!blocks.complete())return -1;
        double weight=8/(1+m.distanceToSqr(eye));
        if(m.getClass().getName().startsWith("com.cobblemon.")){
            try{
                var pokemon=m.getClass().getMethod("getPokemon").invoke(m);
                var api=pokemon.getClass();
                if(!Boolean.TRUE.equals(api.getMethod("isWild").invoke(pokemon)) || api.getMethod("getOwnerUUID").invoke(pokemon)!=null)return -1;
                var species=pokemon.getClass().getMethod("getSpecies").invoke(pokemon);
                if(Boolean.TRUE.equals(api.getMethod("isLegendary").invoke(pokemon))
                        || Boolean.TRUE.equals(api.getMethod("isMythical").invoke(pokemon))
                        || Boolean.TRUE.equals(api.getMethod("isUltraBeast").invoke(pokemon))
                        || CameraRarity.rare(level.getServer(),species)
                        || species.getClass().getMethod("getLabels").invoke(species) instanceof Collection<?> labels
                        && labels.stream().anyMatch(s->Set.of("legendary","mythical","ultra_beast","rare").contains(s)))weight+=20;
            }catch(ReflectiveOperationException unknown){return -1;}
        }
        return weight;
    }
}
