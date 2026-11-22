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
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Trace the first permitted surface from a physical lens. Nothing in this class acquires a chunk ticket. */
final class CameraSceneCapture {
    private static final int REACH=CameraScene.RANGE;
    private static final int[] RANGES={12,24,REACH};
    private static final int NEAR_CHECKS=3072, ALL_CHECKS=7168;
    // Internal results only; neither value is a picture cell or an actor reference.
    private static final int TRACE_CLEAR=0x10000, OUT_OF_CHECKS=0x10001;
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
    private final Map<BlockPos,Boolean> access=new HashMap<>();
    private final Map<BlockState,Integer> materials=new IdentityHashMap<>();
    private final Map<CameraScene.Surface,Integer> faces=new LinkedHashMap<>();
    private final Map<LivingEntity,Integer> visibleActors=new LinkedHashMap<>();
    private final List<LivingEntity> candidates=new ArrayList<>();
    private int claimLimit=NEAR_CHECKS;

    private CameraSceneCapture(ServerPlayer player,ServerLevel level,CameraDevices.Device camera) {
        this.viewer=player; this.level=level; this.camera=camera;
        blocks=new LoadedPocketBlocks(level);
        eye=CameraFeed.eye(camera); anchor=BlockPos.containing(eye); origin=Vec3.atLowerCornerOf(anchor);
        skyClearance=new CameraSkyClearance(viewer,level,blocks,camera.getBlockPos(),eye,true);
    }

    static CameraScene capture(ServerPlayer player, CameraDevices.Device monitor, ServerLevel level,
                               CameraDevices.Device camera, SourceSky sky, Mob subject) {
        return new CameraSceneCapture(player,level,camera).observe(monitor.getBlockPos(),sky,subject);
    }

    private CameraScene observe(BlockPos monitor,SourceSky sky,Mob target) {
        Vec3 forward=target==null ? Vec3.atLowerCornerOf(CameraFeed.direction(camera).getNormal())
                : target.getEyePosition().subtract(eye).normalize();
        if(forward.lengthSqr()<.99) return CameraScene.empty(monitor);
        Vec3 reference=Math.abs(forward.y)>.999 ? new Vec3(0,0,-1) : new Vec3(0,1,0);
        Vec3 right=forward.cross(reference).normalize(), up=right.cross(forward).normalize();
        if(camera.getBlockState().is(CameraDevices.SKY_CAMERA.get())) {
            double aperture=CameraOptics.skyAperture(level,eye);
            right=right.scale(aperture); up=up.scale(aperture);
        }
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
        int[] atmosphereAtNearLimit=new int[cells.length];
        for(int range:RANGES){
            claimLimit=range==REACH?ALL_CHECKS:NEAR_CHECKS;
            for(int sequence=0;sequence<cells.length;sequence++){
                int index=sampleOrder(sequence,side);
                if(cells[index]!=TRACE_CLEAR)continue;
                int x=index%side,y=index/side;
                Vec3 ray=rays[index], end=eye.add(ray.scale(range));
                int result=trace(ray,end,x,y,sky,false,nearestActor(ray,end,false),range==REACH);
                if(result==TRACE_CLEAR && range==24)atmosphereAtNearLimit[index]=skyClearance.wall(x,y,side,ray,sky);
                if(result==OUT_OF_CHECKS){
                    // Only retain atmosphere already observed across the original 24 blocks.
                    // An actual denial or missing foreground chunk always yields a masked cell.
                    cells[index]=range==REACH?atmosphereAtNearLimit[index]:TRACE_CLEAR;
                }else cells[index]=result;
            }
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
                int back=trace(ray,x,y,(observedSky||observedAtmosphere)?sky:null,true,null);
                if(back==1 && !observedSky)back=observedAtmosphere?CameraScene.ATMOSPHERE:0;
                if(back==CameraScene.ATMOSPHERE && !observedAtmosphere && !observedSky)back=0;
                backdrop[i]=back;
            }else{
                EntityHit extended=nearestActor(ray,eye.add(ray.scale(REACH)),true);
                if(extended==null)continue;
                int actor=trace(ray,x,y,null,true,extended);
                if(actor<0){backdrop[i]=cells[i];cells[i]=actor;}
            }
        }
        List<CameraScene.Surface> palette=List.copyOf(faces.keySet());
        int[] edge=cornerBackdrops(cells,backdrop,palette,forward,right,up);
        List<CameraScene.Actor> actors=new ArrayList<>(visibleActors.size());
        for(LivingEntity entity : visibleActors.keySet()) {
            // A claim callback during observation may have hidden a player or removed a mob.
            if(!eligible(entity) || !Safety.locationReason(viewer,level,entity.blockPosition()).isEmpty())
                return CameraScene.empty(monitor);
            actors.add(actor(entity));
        }
        return new CameraScene(monitor,level.getDayTime(),(int)(level.getGameTime()&0xffff),sky,wet?level.getRainLevel(1):0,
                wet?level.getThunderLevel(1):0,eye.subtract(origin),forward,right,up,
                palette,List.copyOf(actors),cells,backdrop,edge);
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
                int proof=trace(ray,x,y,null,true,actor);
                // Only look up the previously exposed backdrop behind this very actor. An
                // unrelated body in front of a neighbouring tile is a conservative mask.
                if(proof<0 && proof==cells[i])proof=trace(ray,x,y,null,true,null);
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

    private static int centerFirst(int i,int side) {return side/2+((i&1)==0?i/2:-(i+1)/2);}

    // The central four rays include the aimed distant object. Then sample the vertical
    // sightline from horizon to ground/sky, ahead of the remaining near-to-center rows.
    private static int sampleOrder(int i,int side){
        int col=i<2*side?i%2:2+(i-2*side)%(side-2);
        int row=i<2*side?i/2:(i-2*side)/(side-2);
        return centerFirst(row,side)*side+centerFirst(col,side);
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
        return Math.max(0,level.getBrightness(LightLayer.SKY,pos)-level.getSkyDarken());
    }

    private boolean allowed(BlockPos pos) {
        Boolean previous=access.get(pos); if(previous!=null)return previous;
        if(access.size()>=claimLimit || blocks.chunk(pos.getX()>>4,pos.getZ()>>4)==null) return false;
        boolean ok=Safety.locationReason(viewer,level,pos).isEmpty();
        access.put(pos.immutable(),ok);
        return ok;
    }

    // Some blocks consult neighboring states while calculating shape or emitted light. They may
    // read only the same already-loaded, authorized geometry as a direct lens ray.
    private final BlockGetter geometry=new BlockGetter() {
        @Override public BlockState getBlockState(BlockPos pos) {
            if(!allowed(pos))return Blocks.BARRIER.defaultBlockState();
            BlockState state=blocks.getBlockState(pos);
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

    private int trace(Vec3 ray,int x,int y,SourceSky sky,boolean background,EntityHit entity) {
        Vec3 end=eye.add(ray.scale(REACH));
        int code=trace(ray,end,x,y,sky,background,entity,true);
        return code==OUT_OF_CHECKS?0:code;
    }

    private int trace(Vec3 ray,Vec3 end,int x,int y,SourceSky sky,boolean background,EntityHit entity,boolean finalRange) {
        return BlockGetter.traverseBlocks(eye,end,ray,(direction,pos)->{
            if(access.size()>=claimLimit && !access.containsKey(pos)){
                return blocks.chunk(pos.getX()>>4,pos.getZ()>>4)==null?0:OUT_OF_CHECKS;
            }
            if(!allowed(pos))return 0;
            BlockState state=blocks.getBlockState(pos);
            if(pos.equals(camera.getBlockPos()))return null;
            // Stay opaque even for a sign with no text or an empty container.
            if(state.hasBlockEntity() || !state.getFluidState().isEmpty())return 0;
            BlockHitResult hit=null;
            if(!state.isAir()) {
                var shape=state.getShape(geometry,pos);
                hit=shape.clip(eye,end,pos);
                if(hit!=null)hit=exposedEdgeFace(pos,shape,hit,ray);
            }
            if(entity!=null && pos.equals(entity.cell) && (hit==null
                    || eye.distanceToSqr(entity.point)<eye.distanceToSqr(hit.getLocation()))) {
                if(background && !visibleActors.containsKey(entity.entity))return 0;
                return -1-visibleActors.computeIfAbsent(entity.entity,ignored->visibleActors.size());
            }
            if(hit==null)return null;
            if(state.getRenderShape()!=RenderShape.MODEL)return 0;
            return surface(pos,state,hit,background);
        },direction->finalRange?skyClearance.wall(x,y,CameraScene.SIDE,direction,sky):TRACE_CLEAR);
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
