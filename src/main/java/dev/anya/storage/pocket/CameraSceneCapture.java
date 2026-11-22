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
    private static final int REACH=24, CLAIM_BUDGET=3072;
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
        // Broad phase only inspects entities already present on the server. No entity identity is sent.
        var nearby=new ArrayList<LivingEntity>();
        level.getEntities(EntityTypeTest.forClass(LivingEntity.class),new AABB(eye,eye).inflate(REACH),
                e->true,nearby,32);
        nearby.sort(java.util.Comparator.comparingDouble(e->e.distanceToSqr(eye)));
        for(var e:nearby) {
            if(e.distanceToSqr(eye)<REACH*REACH && forward.dot(e.getBoundingBox().getCenter().subtract(eye))>0
                    && eligible(e))candidates.add(e);
            if(candidates.size()==CameraScene.MAX_ACTORS)break;
        }

        int side=CameraScene.SIDE;
        int[] cells=new int[side*side], backdrop=new int[side*side];
        boolean any=false, observedSky=false, observedAtmosphere=false;
        // Prioritize the central vertical sightline from ground to sky, then fill out each row.
        // A crowded middle band cannot spend the whole allowance before even sampling the horizon.
        for(int i=0;i<cells.length;i++) {
            int row=i<side?i:(i-side)/(side-1);
            int col=i<side?0:1+(i-side)%(side-1);
            int x=centerFirst(col,side), y=centerFirst(row,side);
            Vec3 ray=forward.add(right.scale((2.0*x+1)/side-1)).add(up.scale(1-(2.0*y+1)/side)).normalize();
            int code=trace(ray,x,y,sky,false,nearestActor(ray,eye.add(ray.scale(REACH)),false));
            observedSky|=code==1;
            observedAtmosphere|=code==CameraScene.ATMOSPHERE;
            cells[y*side+x]=code;
            any|=code!=0;
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
        List<CameraScene.Actor> actors=new ArrayList<>(visibleActors.size());
        for(LivingEntity entity : visibleActors.keySet()) {
            // A claim callback during observation may have hidden a player or removed a mob.
            if(!eligible(entity) || !Safety.locationReason(viewer,level,entity.blockPosition()).isEmpty())
                return CameraScene.empty(monitor);
            actors.add(actor(entity));
        }
        return new CameraScene(monitor,level.getDayTime(),(int)(level.getGameTime()&0xffff),sky,wet?level.getRainLevel(1):0,
                wet?level.getThunderLevel(1):0,eye.subtract(origin),forward,right,up,
                List.copyOf(faces.keySet()),List.copyOf(actors),cells,backdrop);
    }

    private static int centerFirst(int i,int side) {return side/2+((i&1)==0?i/2:-(i+1)/2);}

    private boolean eligible(LivingEntity e) {
        if(e.level()!=level || !MODELS.contains(e.getType()) || e.isRemoved() || !e.isAlive()
                || e.isSpectator() || e.isInvisible() || e.isInvisibleTo(viewer) || !e.broadcastToPlayer(viewer)
                || e.isPassenger() || e.isVehicle() || e.isBaby() || e.hasCustomName() && !(e instanceof ServerPlayer))return false;
        return blocks.chunk(e.getBlockX()>>4,e.getBlockZ()>>4)!=null && allowed(e.blockPosition());
    }

    private CameraScene.Actor actor(LivingEntity entity) {
        BlockPos pos=entity.blockPosition();
        return new CameraScene.Actor(BuiltInRegistries.ENTITY_TYPE.getId(entity.getType()),entity.position().subtract(origin),
                Mth.wrapDegrees(entity.yBodyRot),Mth.wrapDegrees(entity.getYHeadRot()),Mth.wrapDegrees(entity.getXRot()),
                Mth.positiveModulo(entity.walkAnimation.position(),256f),Mth.clamp(entity.walkAnimation.speed(),0,1),skyLight(pos),level.getBrightness(LightLayer.BLOCK,pos));
    }

    private int skyLight(BlockPos pos) {
        return Math.max(0,level.getBrightness(LightLayer.SKY,pos)-level.getSkyDarken());
    }

    private boolean allowed(BlockPos pos) {
        Boolean previous=access.get(pos); if(previous!=null)return previous;
        if(access.size()>=CLAIM_BUDGET || blocks.chunk(pos.getX()>>4,pos.getZ()>>4)==null) return false;
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
        return BlockGetter.traverseBlocks(eye,end,ray,(direction,pos)->{
            if(!allowed(pos))return 0;
            BlockState state=blocks.getBlockState(pos);
            if(pos.equals(camera.getBlockPos()))return null;
            // Stay opaque even for a sign with no text or an empty container.
            if(state.hasBlockEntity() || !state.getFluidState().isEmpty())return 0;
            BlockHitResult hit=null;
            if(!state.isAir()) hit=state.getShape(geometry,pos).clip(eye,end,pos);
            if(entity!=null && pos.equals(entity.cell) && (hit==null
                    || eye.distanceToSqr(entity.point)<eye.distanceToSqr(hit.getLocation()))) {
                return -1-visibleActors.computeIfAbsent(entity.entity,ignored->visibleActors.size());
            }
            if(hit==null)return null;
            if(state.getRenderShape()!=RenderShape.MODEL)return 0;
            return surface(pos,state,hit,background);
        },direction->skyClearance.wall(x,y,CameraScene.SIDE,direction,sky));
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
