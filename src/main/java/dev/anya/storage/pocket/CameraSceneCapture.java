package dev.anya.storage.pocket;

import dev.anya.storage.Safety;
import static net.minecraft.world.level.block.state.properties.BlockStateProperties.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Trace the first permitted surface from a physical lens. Nothing in this class acquires a chunk ticket. */
final class CameraSceneCapture {
    private static final int REACH=CameraScene.RANGE;
    private static final int NEAR_CLIP=24, NEAR_CHECKS=18432, FAR_CHECKS=32768, DETAIL_CHECKS=53248;
    private static final long NEAR_TIME=9_000_000L, FAR_TIME=14_000_000L, DETAIL_TIME=20_000_000L;
    // These results never reach a client. Denied and unavailable space produce the same mask.
    private static final int TRACE_CLEAR=0x10000, OUT_OF_CHECKS=0x10001, DENIED=0x10002, UNLOADED=0x10003;
    private static final byte ALLOWED=1, FORBIDDEN=2, CLEAR_AIR=3;
    private static final int[] SAMPLE_ORDER=sampleOrder();
    private enum Pass { NEAR, FAR, BACKDROP, DETAIL }
    // Optional dedicated-server fixture observer. Never serialized, cached between frames or
    // exposed to a player. Counts are aggregate and carry no source coordinates or identities.
    record Work(int positionChecks,int nearBudget,int farBudget,int ambientFallback,int denied,int unloaded,
                int detailRays,int detailHits,int certifiedDetailRays,int certifiedBlockDistance,
                long captureMicros,long permissionMicros) {}
    static java.util.function.Consumer<Work> diagnostics;
    private static final Direction[] FACE_DIRECTIONS=Direction.values();
    private static final Set<EntityType<?>> MODELS=Set.of(EntityType.PLAYER,EntityType.COW,EntityType.CREEPER,
            EntityType.ZOMBIE,EntityType.SKELETON,EntityType.PIG);
    // Send only properties used to select visible geometry. Other state and all block entity data stay on the server.
    private static final Set<Property<?>> VISUAL=java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    static {
        VISUAL.addAll(List.of(AXIS,HORIZONTAL_AXIS,FACING,HORIZONTAL_FACING,FACING_HOPPER,HALF,DOUBLE_BLOCK_HALF,
                SLAB_TYPE,STAIRS_SHAPE,ROTATION_16,BED_PART,NORTH,SOUTH,EAST,WEST,UP,DOWN,
                NORTH_WALL,SOUTH_WALL,EAST_WALL,WEST_WALL,OPEN,DOOR_HINGE,LIT,LAYERS,ATTACH_FACE));
    }
    private final ServerPlayer viewer;
    private final ServerLevel level;
    private final LoadedPocketBlocks blocks;
    private final CameraDevices.Device camera;
    private final Vec3 eye, origin;
    private final CameraSkyClearance skyClearance;
    private final BlockPos anchor;
    private final Long2ByteOpenHashMap access=new Long2ByteOpenHashMap(4096);
    private final boolean localLighting;
    private LevelChunk lastChunk;
    private int lastChunkX,lastChunkZ;
    private final Map<BlockState,Integer> materials=new IdentityHashMap<>();
    private final Map<CameraScene.Surface,Integer> faces=new LinkedHashMap<>();
    private final Map<LivingEntity,Integer> visibleActors=new LinkedHashMap<>();
    private final List<LivingEntity> candidates=new ArrayList<>();
    private int claimLimit=NEAR_CHECKS;
    private long permissionTimeLimit=NEAR_TIME, permissionNanos, slowestPermission;
    private int nearBudget,farBudget,ambientFallback,denied,unloaded,detailRays,detailHits;
    private int certifiedDetailRays,certifiedBlockDistance;

    private CameraSceneCapture(ServerPlayer player,ServerLevel level,CameraDevices.Device camera) {
        this.viewer=player; this.level=level; this.camera=camera;
        localLighting=player.serverLevel()==level && player.connection.hasChannel(CameraScene.Local.TYPE);
        blocks=new LoadedPocketBlocks(level);
        eye=CameraFeed.eye(camera); anchor=BlockPos.containing(eye); origin=Vec3.atLowerCornerOf(anchor);
        skyClearance=new CameraSkyClearance(viewer,level,blocks,camera.getBlockPos(),eye,true);
    }

    static CameraScene capture(ServerPlayer player, CameraDevices.Device monitor, ServerLevel level,
                               CameraDevices.Device camera, SourceSky sky, Mob subject) {
        var capture=new CameraSceneCapture(player,level,camera);
        long start=System.nanoTime();
        var scene=capture.observe(monitor.getBlockPos(),sky,subject);
        var observer=diagnostics;
        if(observer!=null)observer.accept(new Work(capture.access.size(),capture.nearBudget,capture.farBudget,
                capture.ambientFallback,capture.denied,capture.unloaded,capture.detailRays,capture.detailHits,
                capture.certifiedDetailRays,capture.certifiedBlockDistance,
                (System.nanoTime()-start)/1000,capture.permissionNanos/1000));
        return scene;
    }

    private CameraScene observe(BlockPos monitor,SourceSky sky,Mob target) {
        Vec3 forward=target==null ? Vec3.atLowerCornerOf(CameraFeed.direction(camera).getNormal())
                : target.getEyePosition().subtract(eye).normalize();
        if(forward.lengthSqr()<.99) return CameraScene.empty(monitor);
        Vec3 reference=Math.abs(forward.y)>.999 ? new Vec3(0,0,-1) : new Vec3(0,1,0);
        Vec3 right=forward.cross(reference).normalize(), up=right.cross(forward).normalize();
        double aperture=camera.getBlockState().is(CameraDevices.SKY_CAMERA.get())
                ?CameraOptics.skyAperture(level,eye):CameraOptics.wallAperture(camera);
        right=right.scale(aperture); up=up.scale(aperture);
        BlockPos outside=camera.getBlockPos().above();
        boolean outdoors=sky!=null && skyClearance.overhead();
        boolean wet=outdoors && level.isRaining() && allowed(outside) && level.isRainingAt(outside);
        // Broad phase only inspects entities already present on the server.
        var nearby=new ArrayList<LivingEntity>();
        level.getEntities(EntityTypeTest.forClass(LivingEntity.class),new AABB(eye,eye).inflate(REACH),
                e->MODELS.contains(e.getType()) && e.isAlive() && !e.isInvisible()
                        && e.distanceToSqr(eye)<REACH*REACH
                        && forward.dot(e.getBoundingBox().getCenter().subtract(eye))>0,nearby,32);
        nearby.sort(java.util.Comparator.comparingDouble(e->e.distanceToSqr(eye)));
        for(var e:nearby) {
            if(eligible(e))candidates.add(e);
            if(candidates.size()==CameraScene.MAX_ACTORS)break;
        }

        int side=CameraScene.SIDE;
        int[] cells=new int[side*side], backdrop=new int[side*side];
        Vec3[] rays=new Vec3[cells.length];
        for(int i=0;i<cells.length;i++){
            int x=i%side,y=i/side;
            rays[i]=forward.add(right.scale((2.0*x+1)/side-1)).add(up.scale(1-(2.0*y+1)/side)).normalize();
            cells[i]=TRACE_CLEAR;
        }
        // On a street, give the whole lens a chance to resolve the nearby porch, walls
        // and road before any ray spends its budget far down the street. Both segments
        // trace the original physical lens ray. A denial in the first segment stays masked.
        final int close=8;
        for(int i:SAMPLE_ORDER){
            Vec3 ray=rays[i], end=eye.add(ray.scale(close));
            int result=trace(eye,ray,end,sky,nearestActor(ray,end,false),Pass.NEAR);
            if(result==OUT_OF_CHECKS)nearBudget++;
            countMask(result);
            cells[i]=result==TRACE_CLEAR?TRACE_CLEAR:pictureCode(result);
        }
        for(int i:SAMPLE_ORDER){
            if(cells[i]!=TRACE_CLEAR)continue;
            Vec3 ray=rays[i],end=eye.add(ray.scale(NEAR_CLIP));
            int result=trace(eye.add(ray.scale(close-.001)),ray,end,sky,nearestActor(ray,end,false),Pass.NEAR);
            if(result==OUT_OF_CHECKS)nearBudget++;
            countMask(result);
            cells[i]=result==TRACE_CLEAR?TRACE_CLEAR:pictureCode(result);
        }
        claimLimit=FAR_CHECKS;
        permissionTimeLimit=FAR_TIME;
        for(int i:SAMPLE_ORDER){
            if(cells[i]!=TRACE_CLEAR)continue;
            Vec3 ray=rays[i], end=eye.add(ray.scale(REACH));
            int result=trace(eye.add(ray.scale(NEAR_CLIP-.001)),ray,end,sky,nearestActor(ray,end,false),Pass.FAR);
            // Exhaustion contributes only the public climate at the lens after this exact ray
            // cleared the entire near clip. A real claim denial or unloaded chunk stays masked.
            cells[i]=result==OUT_OF_CHECKS?skyClearance.climate(ray,sky):pictureCode(result);
            if(result==OUT_OF_CHECKS){farBudget++;if(cells[i]==CameraScene.ATMOSPHERE)ambientFallback++;}
            countMask(result);
        }
        boolean any=false, observedSky=false, observedAtmosphere=false;
        for(int code:cells){
            any|=code!=0; observedSky|=code==1; observedAtmosphere|=code==CameraScene.ATMOSPHERE;
        }
        if(!any) return CameraScene.empty(monitor);
        // The native head/arms can extend beyond a standing collision box. Only actors already
        // seen by an ordinary body ray can contribute limbs. Unavailable cells remain masked.
        for(int i=0;!visibleActors.isEmpty() && i<cells.length;i++) {
            if(cells[i]==0)continue;
            int x=i%side,y=i/side;
            Vec3 ray=forward.add(right.scale((2.0*x+1)/side-1)).add(up.scale(1-(2.0*y+1)/side)).normalize();
            if(cells[i]<0) {
                // A hole between limbs may show only a surface or sky independently visible on another ray.
                int back=backdrop(ray,(observedSky||observedAtmosphere)?sky:null,null);
                if(back==1 && !observedSky)back=observedAtmosphere?CameraScene.ATMOSPHERE:0;
                if(back==CameraScene.ATMOSPHERE && !observedAtmosphere && !observedSky)back=0;
                backdrop[i]=back;
            }else{
                EntityHit extended=nearestActor(ray,eye.add(ray.scale(REACH)),true);
                if(extended==null)continue;
                int actor=backdrop(ray,null,extended);
                if(actor<0){backdrop[i]=cells[i];cells[i]=actor;}
            }
        }
        int[] edge=cornerBackdrops(cells,backdrop,List.copyOf(faces.keySet()),forward,right,up);
        claimLimit=DETAIL_CHECKS;
        permissionTimeLimit=DETAIL_TIME;
        int[] detail=distantSurfaces(cells,forward,right,up,sky);
        List<CameraScene.Surface> palette=List.copyOf(faces.keySet());
        List<CameraScene.Actor> actors=new ArrayList<>(visibleActors.size());
        for(LivingEntity entity : visibleActors.keySet()) {
            // A claim callback during observation may have hidden a player or removed a mob.
            if(!eligible(entity) || !Safety.locationReason(viewer,level,entity.blockPosition()).isEmpty())
                return CameraScene.empty(monitor);
            actors.add(actor(entity));
        }
        return new CameraScene(monitor,level.getDayTime(),(int)(level.getGameTime()&0xffff),sky,wet?level.getRainLevel(1):0,
                wet?level.getThunderLevel(1):0,eye.subtract(origin),forward,right,up,
                palette,List.copyOf(actors),cells,backdrop,edge,detail,localLighting);
    }

    /** Refine only cells beside a visible actor and an already seen physical wall/floor corner.
     * These are backdrop queries: terrain must already have been seen independently, all claims
     * on this actual quadrant ray must also pass and occluding terrain cannot be traversed. */
    private int[] cornerBackdrops(int[] cells,int[] backs,List<CameraScene.Surface> palette,Vec3 forward,Vec3 right,Vec3 up) {
        int side=CameraScene.SIDE, used=0;
        int[] edge=new int[cells.length*4];
        if(visibleActors.isEmpty())return edge;
        var coplanar=new HashMap<CameraScene.Surface,List<CameraScene.Surface>>();
        for(int i=0;i<cells.length && used<CameraScene.MAX_EDGE_CELLS;i++){
            int ref=cells[i]<0?backs[i]:cells[i];
            if(ref<2 || ref==CameraScene.ATMOSPHERE)continue;
            int x=i%side, y=i/side, minX=Math.max(0,x-1), maxX=Math.min(side-1,x+1);
            int minY=Math.max(0,y-1), maxY=Math.min(side-1,y+1);
            boolean besideActor=false;
            for(int r=minY;r<=maxY && !besideActor;r++)for(int c=minX;c<=maxX;c++)
                if(cells[r*side+c]<0){besideActor=true;break;}
            if(!besideActor)continue;
            var base=palette.get(ref-2);
            var adjoining=coplanar.computeIfAbsent(base,a->palette.stream().filter(b->CameraScene.adjacentOnPlane(a,b)).toList());
            boolean corner=false;
            for(int r=minY;r<=maxY && !corner;r++)for(int c=minX;c<=maxX;c++){
                int n=r*side+c, other=cells[n]<0?backs[n]:cells[n];
                if(other>=2 && other!=CameraScene.ATMOSPHERE && touchesCorner(adjoining,palette.get(other-2))){
                    corner=true;break;
                }
            }
            if(!corner)continue;
            used++;
            for(int q=0;q<4;q++){
                double sx=x+((q&1)==0?1.0/6:5.0/6), sy=y+(q<2?1.0/6:5.0/6);
                Vec3 ray=forward.add(right.scale(2*sx/side-1)).add(up.scale(1-2*sy/side)).normalize();
                var actor=nearestActor(ray,eye.add(ray.scale(REACH)),false);
                int proof=backdrop(ray,null,actor);
                // Only look up the previously exposed backdrop behind this very actor. An
                // unrelated body in front of a neighbouring tile is a conservative mask.
                if(proof<0 && proof==cells[i])proof=backdrop(ray,null,null);
                if(proof<2 || proof==CameraScene.ATMOSPHERE){edge[4*i+q]=-1;continue;}
                var surface=palette.get(proof-2);
                edge[4*i+q]=CameraScene.adjacentOnPlane(base,surface) || touchesCorner(adjoining,surface)?proof:-1;
            }
        }
        return edge;
    }

    private static boolean touchesCorner(List<CameraScene.Surface> adjoining,CameraScene.Surface other) {
        for(var known:adjoining)if(CameraScene.sharesEdge(known,other))return true;
        return false;
    }

    // Start at the aim point. Spread farther queries along the central horizon and over the
    // entire sensor together: a row of distant buildings can occupy only a few sensor rows.
    private static int[] sampleOrder(){
        int side=CameraScene.SIDE, at=0, mid=side/2;
        var seen=new boolean[side*side];
        var order=new int[seen.length];
        for(int radius=1;radius<=4;radius++)for(int y=mid-radius;y<mid+radius;y++)
            for(int x=mid-radius;x<mid+radius;x++){
                int i=y*side+x;
                if(!seen[i]){seen[i]=true;order[at++]=i;}
            }
        int horizon=0, bits=Integer.numberOfTrailingZeros(side);
        int[] centralRows={mid-1,mid,mid-2,mid+1};
        for(int s=0;s<seen.length;s++){
            for(int k=0;k<3 && horizon<4*side;k++,horizon++){
                int col=Integer.reverse(horizon/4) >>> (32-bits);
                int i=centralRows[horizon%4]*side+col;
                if(!seen[i]){seen[i]=true;order[at++]=i;}
            }
            int x=0,y=0;
            for(int bit=side/2, shift=0;bit>0;bit>>=1,shift+=2){
                if((s&(1<<shift))!=0)x|=bit;
                if((s&(2<<shift))!=0)y|=bit;
            }
            int i=y*side+x;
            if(!seen[i]){seen[i]=true;order[at++]=i;}
        }
        return order;
    }

    // A coarse sensor cell subtends about six whole blocks at the horizon. Recover their
    // actual geometry by tracing independent rays from the lens, including every intervening
    // permission check. The renderer receives no material inferred from neighbouring blocks.
    private int[] distantSurfaces(int[] cells,Vec3 forward,Vec3 right,Vec3 up,SourceSky sky){
        int size=CameraScene.DETAIL_SIDE, side=CameraScene.SIDE, tiles=0, closeTiles=0;
        int[] detail=new int[0];
        var coarse=List.copyOf(faces.keySet());
        Vec3 offset=eye.subtract(origin);
        for(int cell:SAMPLE_ORDER){
            if(cells[cell]<2 || cells[cell]==CameraScene.ATMOSPHERE)continue;
            var face=coarse.get(cells[cell]-2);
            double dx=face.x()+.5-offset.x,dy=face.y()+.5-offset.y,dz=face.z()+.5-offset.z;
            double distanceSquared=dx*dx+dy*dy+dz*dz;
            if(distanceSquared<20*20){
                // A nearby wall seen at a grazing angle can be as thin on the screen as
                // a distant wall. Give these faces some of the existing fine-ray budget.
                double normalDistance=Math.abs(face.face().getAxis().choose(dx,dy,dz));
                if(closeTiles>=CameraScene.MAX_DETAIL_CELLS/4 || distanceSquared<7*7
                        || distanceSquared < 18*normalDistance)continue;
                closeTiles++;
            }
            if(tiles==CameraScene.MAX_DETAIL_CELLS || spent() || faces.size()>=CameraScene.MAX_SURFACES)break;
            if(detail.length==0)detail=new int[cells.length*CameraScene.DETAIL_AREA];
            tiles++;
            var prefix=clearDetailPrefix(cell,forward,right,up,face,offset);
            for(int j=0;j<CameraScene.DETAIL_AREA;j++){
                double x=cell%side+((j%size)+.5)/size, y=cell/side+((j/size)+.5)/size;
                Vec3 ray=forward.add(right.scale(2*x/side-1)).add(up.scale(1-2*y/side)).normalize();
                Vec3 end=eye.add(ray.scale(REACH));
                EntityHit actor=nearestActor(ray,end,false);
                double skip=prefix.depth()==0?0:Math.min(REACH-.001,
                        prefix.depth()/Math.abs(component(ray,prefix.axis())));
                if(skip>=1){certifiedDetailRays++;certifiedBlockDistance+=(int)skip;}
                // Actors move through empty blocks. Neither a near nor a distant body may
                // reveal terrain behind it, even when the whole ray bundle proved clear air.
                boolean hidden=actor!=null && eye.distanceToSqr(actor.point)<=skip*skip;
                Vec3 start=skip>.002?eye.add(ray.scale(skip-.001)):eye;
                int got=hidden?0:trace(start,ray,end,sky,actor,Pass.DETAIL);
                detailRays++;
                countMask(got);
                if(got==OUT_OF_CHECKS)return detail;
                detail[cell*CameraScene.DETAIL_AREA+j]=got>=2 && got<TRACE_CLEAR?got:-1;
                if(got>=2 && got<TRACE_CLEAR)detailHits++;
            }
        }
        return detail;
    }

    private record AirPrefix(int axis,double depth){}

    private static double component(Vec3 v,int axis){
        return switch(axis){case 0 -> v.x;case 1 -> v.y;default -> v.z;};
    }

    // All 36 rays in a sensor tile share a narrow pyramid starting at the lens. Certify its
    // consecutive empty slices once. One unknown block anywhere in a slice ends the prefix:
    // each individual ray then uses the ordinary shape, actor and permission traversal.
    private AirPrefix clearDetailPrefix(int cell,Vec3 forward,Vec3 right,Vec3 up,CameraScene.Surface face,Vec3 offset){
        int side=CameraScene.SIDE,col=cell%side,row=cell/side;
        Vec3 mid=forward.add(right.scale((2.0*col+1)/side-1)).add(up.scale(1-(2.0*row+1)/side));
        int axis=Math.abs(mid.x)>=Math.abs(mid.y)&&Math.abs(mid.x)>=Math.abs(mid.z)?0:
                Math.abs(mid.y)>=Math.abs(mid.z)?1:2;
        int a=(axis+1)%3,b=(axis+2)%3, sign=component(mid,axis)>0?1:-1;
        double lowA=Double.POSITIVE_INFINITY,lowB=lowA,highA=Double.NEGATIVE_INFINITY,highB=highA;
        int grid=CameraScene.DETAIL_SIDE;
        for(int ky=0;ky<2;ky++)for(int kx=0;kx<2;kx++){
            double x=col+(kx==0?.5:grid-.5)/grid,y=row+(ky==0?.5:grid-.5)/grid;
            Vec3 d=forward.add(right.scale(2*x/side-1)).add(up.scale(1-2*y/side));
            double major=sign*component(d,axis);
            if(major<=.1)return new AirPrefix(axis,0);
            double slopeA=component(d,a)/major,slopeB=component(d,b)/major;
            lowA=Math.min(lowA,slopeA);highA=Math.max(highA,slopeA);
            lowB=Math.min(lowB,slopeB);highB=Math.max(highB,slopeB);
        }
        // Direction ratios are monotone on each edge of the tile while its denominator
        // keeps sign. Its four corners therefore bound every interior ray on each slice.
        double m=component(eye,axis),pa=component(eye,a),pb=component(eye,b);
        double faceCenter=(axis==0?face.x():axis==1?face.y():face.z())+.5;
        double limit=Math.max(0,sign*(faceCenter-component(offset,axis))-1.5);
        var pos=new BlockPos.MutableBlockPos();
        int block=Mth.floor(m),last=block+sign*(int)Math.ceil(limit+2);
        double start=0;
        for(;block!=last && start<limit;block+=sign){
            double end=Math.min(limit,sign>0?block+1-m:m-block);
            if(end<=start)continue;
            int x0=Mth.floor(pa+Math.min(start*lowA,end*lowA)-.0001);
            int x1=Mth.floor(pa+Math.max(start*highA,end*highA)+.0001);
            int y0=Mth.floor(pb+Math.min(start*lowB,end*lowB)-.0001);
            int y1=Mth.floor(pb+Math.max(start*highB,end*highB)+.0001);
            // Minecraft's DDA extends infinitesimally behind its starting point. If the
            // lens sits exactly on a grid boundary, include that initial adjoining slice.
            boolean boundary=start==0 && Math.abs(m-Math.rint(m))<.0001;
            for(int plane=0;plane<=(boundary?1:0);plane++)for(int v=y0;v<=y1;v++)for(int u=x0;u<=x1;u++){
                int w=block-plane*sign;
                if(axis==0)pos.set(w,u,v);
                else if(axis==1)pos.set(v,w,u);
                else pos.set(u,v,w);
                if(!publicAir(pos))return new AirPrefix(axis,start);
            }
            start=end;
        }
        return new AirPrefix(axis,start);
    }

    private boolean publicAir(BlockPos pos){
        long key=pos.asLong();
        byte prior=access.get(key);
        if(prior==CLEAR_AIR)return true;
        if(permission(pos,key,prior)!=0)return false;
        if(pos.equals(camera.getBlockPos()))return true;
        BlockState state=stateAt(pos);
        if(!state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty())return false;
        access.put(key,CLEAR_AIR);
        return true;
    }

    private boolean eligible(LivingEntity e) {
        if(e.level()!=level || !MODELS.contains(e.getType()) || e.isRemoved() || !e.isAlive()
                || e.isSpectator() || e.isInvisible() || e.isInvisibleTo(viewer) || !e.broadcastToPlayer(viewer)
                || e.isPassenger() || e.isVehicle() || e.isBaby() || e.hasCustomName() && !(e instanceof ServerPlayer))return false;
        return blocks.chunk(e.getBlockX()>>4,e.getBlockZ()>>4)!=null && allowed(e.blockPosition());
    }

    private CameraScene.Actor actor(LivingEntity entity) {
        BlockPos pos=entity.blockPosition();
        int appearance = 0, parts = 0;
        if (entity instanceof ServerPlayer player) {
            appearance = CameraAppearanceHistory.appearance(viewer, player);
            if (appearance != 0) for (var part : net.minecraft.world.entity.player.PlayerModelPart.values())
                if (player.isModelPartShown(part)) parts |= part.getMask();
        }
        return new CameraScene.Actor(BuiltInRegistries.ENTITY_TYPE.getId(entity.getType()),entity.position().subtract(origin),
                Mth.wrapDegrees(entity.yBodyRot),Mth.wrapDegrees(entity.getYHeadRot()),Mth.wrapDegrees(entity.getXRot()),
                Mth.positiveModulo(entity.walkAnimation.position(),256f),Mth.clamp(entity.walkAnimation.speed(),0,1),
                skyLight(pos),level.getBrightness(LightLayer.BLOCK,pos),appearance,parts);
    }

    private int skyLight(BlockPos pos) {
        int sky=level.getBrightness(LightLayer.SKY,pos);
        return localLighting?sky:Math.max(0,sky-level.getSkyDarken());
    }

    private boolean allowed(BlockPos pos) {
        return permission(pos)==0;
    }

    private void countMask(int result){
        if(result==DENIED)denied++;
        if(result==UNLOADED)unloaded++;
    }

    private boolean spent(){
        long charged=permissionNanos-Math.max(0,slowestPermission-35_000L);
        return access.size()>=claimLimit || charged>=permissionTimeLimit;
    }

    private int permission(BlockPos pos) {
        long key=pos.asLong();
        return permission(pos,key,access.get(key));
    }

    private int permission(BlockPos pos,long key,byte previous) {
        if(previous!=0)return previous==FORBIDDEN?DENIED:0;
        if(chunkAt(pos)==null)return UNLOADED;
        if(spent())return OUT_OF_CHECKS;
        long start=System.nanoTime();
        boolean ok=Safety.locationReason(viewer,level,pos).isEmpty();
        long elapsed=System.nanoTime()-start;
        permissionNanos+=elapsed;
        // Discount at most one isolated JIT/GC/descheduling outlier. All other latency
        // counts in full, so sustained claim cost reduces the remaining sampling work.
        slowestPermission=Math.max(slowestPermission,elapsed);
        access.put(key,ok?ALLOWED:FORBIDDEN);
        return ok?0:DENIED;
    }

    // Consecutive voxels usually share a chunk. The handle comes only from this observation's
    // loaded-only resolver, and disappears with the rest of the observation.
    private LevelChunk chunkAt(BlockPos pos){
        int x=pos.getX()>>4,z=pos.getZ()>>4;
        if(lastChunk!=null && x==lastChunkX && z==lastChunkZ)return lastChunk;
        lastChunkX=x;lastChunkZ=z;
        return lastChunk=blocks.chunk(x,z);
    }

    private BlockState stateAt(BlockPos pos){
        LevelChunk chunk=chunkAt(pos);
        return chunk==null?Blocks.BARRIER.defaultBlockState():chunk.getBlockState(pos);
    }

    // Some blocks consult neighboring states while calculating shape or emitted light. They may
    // read only the same already-loaded, authorized geometry as a direct lens ray.
    private final BlockGetter geometry=new BlockGetter() {
        @Override public BlockState getBlockState(BlockPos pos) {
            if(!allowed(pos))return Blocks.BARRIER.defaultBlockState();
            BlockState state=stateAt(pos);
            return state.hasBlockEntity()?Blocks.BARRIER.defaultBlockState():state;
        }
        @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos){return null;}
        @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        @Override public int getHeight(){return blocks.getHeight();}
        @Override public int getMinBuildHeight(){return blocks.getMinBuildHeight();}
    };

    private record EntityHit(LivingEntity entity, Vec3 point, BlockPos cell) {}
    private EntityHit nearestActor(Vec3 ray,Vec3 end,boolean limbs) {
        EntityHit nearest=null;
        double distance=Double.POSITIVE_INFINITY;
        for(LivingEntity actor : limbs?visibleActors.keySet():candidates) {
            AABB box=actor.getBoundingBox();
            if(limbs){
                boolean quadruped=actor.getType()==EntityType.COW || actor.getType()==EntityType.PIG;
                double reach=quadruped?.72:.42;
                box=box.inflate(reach,.2,reach);
            }
            var point=box.clip(eye,end).orElse(null);
            if(point==null)continue;
            double current=eye.distanceToSqr(point);
            if(current<distance) {
                distance=current;
                nearest=new EntityHit(actor,point,BlockPos.containing(point.add(ray.scale(.00001))));
            }
        }
        return nearest;
    }

    private static int pictureCode(int result){
        return result>=TRACE_CLEAR?0:result;
    }

    private int backdrop(Vec3 ray,SourceSky sky,EntityHit entity) {
        return pictureCode(trace(eye,ray,eye.add(ray.scale(REACH)),sky,entity,Pass.BACKDROP));
    }

    private int trace(Vec3 start,Vec3 ray,Vec3 end,SourceSky sky,EntityHit entity,Pass pass) {
        return BlockGetter.traverseBlocks(start,end,ray,(direction,pos)->{
            long key=pos.asLong();
            byte prior=access.get(key);
            boolean actorHere=entity!=null && pos.equals(entity.cell);
            if(prior==CLEAR_AIR && !actorHere)return null;
            int result=permission(pos,key,prior);
            if(result!=0)return result;
            if(direction.y>0 && pos.getY()>=level.getMaxBuildHeight())
                return pass==Pass.DETAIL || sky==null?0:1;
            BlockState state=stateAt(pos);
            if(pos.equals(camera.getBlockPos()))return null;
            // Even modded air-like blocks may carry private data or a fluid.
            if(state.hasBlockEntity() || !state.getFluidState().isEmpty())return 0;
            if(state.isAir() && !actorHere){
                access.put(key,CLEAR_AIR);
                return null;
            }
            BlockHitResult hit=null;
            if(!state.isAir()) {
                var shape=state.getShape(geometry,pos);
                hit=shape.clip(eye,end,pos);
                if(hit!=null)hit=exposedEdgeFace(pos,shape,hit,ray);
            }
            if(actorHere && (hit==null
                    || eye.distanceToSqr(entity.point)<eye.distanceToSqr(hit.getLocation()))) {
                if(pass==Pass.DETAIL || pass==Pass.BACKDROP && !visibleActors.containsKey(entity.entity))return 0;
                return -1-visibleActors.computeIfAbsent(entity.entity,ignored->visibleActors.size());
            }
            if(hit==null)return null;
            if(state.getRenderShape()!=RenderShape.MODEL)return 0;
            return surface(pos,state,hit,pass==Pass.BACKDROP);
        },direction->switch(pass){
            case NEAR -> TRACE_CLEAR;
            case DETAIL -> 0;
            case FAR,BACKDROP -> skyClearance.wall(direction,sky);
        });
    }

    // At the exact edge shared by two cubes AABB.clip can pick the internal X face
    // before the exposed top face. Both are at the identical ray depth. Retain the hit
    // point, and select an exposed entering face only when both neighbors can be checked.
    private BlockHitResult exposedEdgeFace(BlockPos pos,net.minecraft.world.phys.shapes.VoxelShape shape,
                                           BlockHitResult hit,Vec3 ray) {
        Direction original=hit.getDirection();
        Vec3 local=hit.getLocation().subtract(pos.getX(),pos.getY(),pos.getZ());
        for(Direction other:FACE_DIRECTIONS) {
            if(other.getAxis()==original.getAxis())continue;
            var axis=other.getAxis();
            int sign=other.getAxisDirection().getStep();
            if(axis.choose(ray.x,ray.y,ray.z)*sign>=0)continue;
            double plane=sign>0?1:0;
            if(Math.abs(axis.choose(local.x,local.y,local.z)-plane)>1e-7)continue;
            if(!Block.isShapeFullBlock(shape))return hit;
            BlockPos seam=pos.relative(original);
            if(!allowed(seam))return hit;
            var neighbor=blocks.getBlockState(seam);
            if(neighbor.hasBlockEntity() || !neighbor.isSolidRender(geometry,seam))return hit;
            BlockPos open=pos.relative(other);
            if(allowed(open) && blocks.getBlockState(open).isAir())return hit.withDirection(other);
        }
        return hit;
    }

    private int surface(BlockPos pos,BlockState state,BlockHitResult hit,boolean background) {
        var face=hit.getDirection();
        // The light belongs on the exposed side of a solid block. Recheck the sampled neighbor first.
        BlockPos exterior=pos.relative(face);
        BlockPos lightPos=allowed(exterior)?exterior:pos;
        int sky=skyLight(lightPos),light=level.getBrightness(LightLayer.BLOCK,lightPos);
        light=Math.max(light,state.getLightEmission(geometry,pos));
        var point=hit.getLocation().subtract(origin);
        float plane=(float)(Math.rint(face.getAxis().choose(point.x,point.y,point.z)*4096)/4096);
        var surface=new CameraScene.Surface(materials.computeIfAbsent(state,CameraSceneCapture::material),
                pos.getX()-anchor.getX(),pos.getY()-anchor.getY(),pos.getZ()-anchor.getZ(),face,plane,sky,Math.clamp(light,0,15),
                tint(state,pos));
        Integer idx=faces.get(surface);
        if(idx!=null)return 2+idx;
        if(background || faces.size()>=CameraScene.MAX_SURFACES)return 0;
        idx=faces.size(); faces.put(surface,idx);return 2+idx;
    }

    private int tint(BlockState state,BlockPos pos) {
        if(!state.is(Blocks.GRASS_BLOCK) && !state.is(BlockTags.LEAVES) && !state.is(Blocks.VINE)
                && !state.is(Blocks.SHORT_GRASS) && !state.is(Blocks.TALL_GRASS))return 0xffffff;
        var chunk=blocks.chunk(pos.getX()>>4,pos.getZ()>>4);
        var biome=chunk.getNoiseBiome(pos.getX()>>2,pos.getY()>>2,pos.getZ()>>2).value();
        boolean leaves=state.is(BlockTags.LEAVES) || state.is(Blocks.VINE);
        if(state.is(Blocks.SPRUCE_LEAVES))return 0x619961;
        if(state.is(Blocks.BIRCH_LEAVES))return 0x80a755;
        int color=leaves?biome.getFoliageColor():biome.getGrassColor(pos.getX(),pos.getZ());
        // The dedicated server has no client resource-pack colormap.
        return color==0 || color==-65281 ? (leaves?0x77ab2f:0x91bd59) : color&0xffffff;
    }

    private static int material(BlockState state) {
        BlockState visual=state.getBlock().defaultBlockState();
        for(var property:state.getProperties())
            if(VISUAL.contains(property))visual=copy(visual,state,property);
        return Block.getId(visual);
    }
    private static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from, Property<T> property) {
        return to.setValue(property,from.getValue(property));
    }
}
