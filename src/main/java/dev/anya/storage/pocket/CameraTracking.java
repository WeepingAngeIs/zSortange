package dev.anya.storage.pocket;

import dev.anya.storage.Safety;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;

/** Only the settings and the manual rest direction persist. Movement samples belong to this loaded camera. */
final class CameraTracking {
    enum Mode { FIXED, GLANCE, MOTION }
    enum Group { ALL, HOSTILE, SELECTED }
    static final int MAX_RULES=32;
    Mode mode=Mode.FIXED;
    Group group=Group.ALL;
    boolean wideLens;
    final Set<String> watch=new TreeSet<>(),ignore=new TreeSet<>();
    int rest=-1;
    private Map<UUID,Vec3> previous=Map.of();
    private long sampled=Long.MIN_VALUE,until;
    private UUID target;
    private static final class Budget { long tick; int scans; }
    private static final Map<ServerLevel,Budget> budgets=new WeakHashMap<>();

    boolean accepts(Mob mob){
        String kind=BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        if(ignore.contains(kind))return false;
        return watch.contains(kind)||group==Group.ALL||group==Group.HOSTILE
                &&(mob instanceof Enemy||mob.getType().getCategory()==MobCategory.MONSTER);
    }
    void clearMotion(){previous=Map.of();target=null;until=0;sampled=Long.MIN_VALUE;}
    String rule(String kind,int choice){
        var key=ResourceLocation.tryParse(kind);
        if(key==null||!BuiltInRegistries.ENTITY_TYPE.containsKey(key)||choice<0||choice>2)return "Unknown mob rule.";
        if(choice!=0&&!watch.contains(kind)&&!ignore.contains(kind)&&watch.size()+ignore.size()>=MAX_RULES)
            return "Remove a rule first. A camera can remember 32 mob types.";
        watch.remove(kind);ignore.remove(kind);
        if(choice==1)watch.add(kind);
        if(choice==2)ignore.add(kind);
        clearMotion();return "";
    }
    void save(CompoundTag root){
        var tag=new CompoundTag();tag.putString("Mode",mode.name());tag.putString("Group",group.name());
        if(wideLens)tag.putBoolean("WideLens",true);
        var rules=new CompoundTag();watch.forEach(k->rules.putByte(k,(byte)1));ignore.forEach(k->rules.putByte(k,(byte)2));
        tag.put("Types",rules);if(rest>=0)tag.putInt("RestPan",rest);
        root.put("Tracking",tag);root.putBoolean("Glances",mode==Mode.GLANCE);
    }
    void load(CompoundTag root){
        clearMotion();watch.clear();ignore.clear();rest=-1;group=Group.ALL;wideLens=false;
        mode=root.getBoolean("Glances")?Mode.GLANCE:Mode.FIXED;
        if(!root.contains("Tracking",Tag.TAG_COMPOUND))return;
        var t=root.getCompound("Tracking");
        wideLens=t.getBoolean("WideLens");
        try{mode=Mode.valueOf(t.getString("Mode"));}catch(IllegalArgumentException ignored){}
        try{group=Group.valueOf(t.getString("Group"));}catch(IllegalArgumentException ignored){}
        if(t.contains("RestPan",Tag.TAG_INT))rest=Math.clamp(t.getInt("RestPan"),0,2);
        var rules=t.getCompound("Types");
        for(String key:rules.getAllKeys()){
            if(watch.size()+ignore.size()>=MAX_RULES)break;
            if(key.length()>128||ResourceLocation.tryParse(key)==null)continue;
            if(rules.getByte(key)==1)watch.add(key);
            if(rules.getByte(key)==2)ignore.add(key);
        }
    }
    static boolean visibleSide(CameraDevices.Device cam,Vec3 at){
        var face=cam.getBlockState().getValue(BlockStateProperties.FACING);
        var from=Vec3.atCenterOf(cam.getBlockPos());
        return at.subtract(from).dot(Vec3.atLowerCornerOf(face.getNormal()))>=-.01;
    }
    Mob visibleTarget(ServerPlayer viewer,ServerLevel level,CameraDevices.Device cam,Vec3 eye){
        if(mode!=Mode.MOTION||target==null||until<level.getGameTime()||!(level.getEntity(target) instanceof Mob mob))return null;
        return accepts(mob)&&visibleSide(cam,mob.getEyePosition())&&CameraFeed.visibleWeight(viewer,level,eye,mob)>0?mob:null;
    }
    static void tick(ServerLevel level,CameraDevices.Device cam){
        var t=cam.tracking;long now=level.getGameTime();
        if(t.mode!=Mode.MOTION||Math.floorMod(cam.getBlockPos().hashCode()+now,12)!=0||cam.owner==null)return;
        var face=cam.getBlockState().getValue(BlockStateProperties.FACING);
        if(!face.getAxis().isHorizontal()||PocketService.inPocket(level))return;
        var owner=level.getServer().getPlayerList().getPlayer(cam.owner);
        if(owner==null||!owner.isAlive()||owner.isSpectator()){t.clearMotion();return;}
        var budget=budgets.computeIfAbsent(level,k->new Budget());
        if(now!=budget.tick){budget.tick=now;budget.scans=0;}
        if(budget.scans>=6)return;
        budget.scans++;
        if(!Safety.locationReason(owner,level,cam.getBlockPos()).isEmpty()){t.clearMotion();return;}
        if(t.rest<0)t.rest=cam.getBlockState().getValue(OutdoorCameraBlock.PAN);
        var eye=OutdoorCameraBlock.lensEye(cam.getBlockPos(),cam.getBlockState());
        var positions=new HashMap<UUID,Vec3>();
        double bestWeight=-1;Mob best=null;
        for(Mob mob:CameraFeed.nearby(level,eye,t::accepts)){
            if(!visibleSide(cam,mob.getEyePosition()))continue;
            double weight=CameraFeed.visibleWeight(owner,level,eye,mob);
            if(weight<0)continue;
            var id=mob.getUUID();Vec3 pos=mob.position(),old=t.previous.get(id);positions.put(id,pos);
            if(old==null||now-t.sampled>36||pos.distanceToSqr(old)<.01)continue;
            if(id.equals(t.target))weight+=.5;
            if(weight>bestWeight){bestWeight=weight;best=mob;}
        }
        t.previous=positions;t.sampled=now;
        if(best!=null){t.target=best.getUUID();t.until=now+36;}
        if(t.target!=null&&(!positions.containsKey(t.target)||now>t.until))t.target=null;
        int pan=t.rest;
        if(t.target!=null){
            var delta=positions.get(t.target).subtract(Vec3.atCenterOf(cam.getBlockPos()));
            double ahead=delta.dot(Vec3.atLowerCornerOf(face.getNormal()));
            double side=delta.dot(Vec3.atLowerCornerOf(face.getClockWise().getNormal()));
            if(Math.abs(side)>ahead)pan=side>0?1:2;else pan=0;
        }
        aim(owner,level,cam,pan);
    }
    static void aim(ServerPlayer p,ServerLevel level,CameraDevices.Device cam,int pan){
        var state=cam.getBlockState();
        if(pan<0||pan>2||state.getValue(OutdoorCameraBlock.PAN)==pan)return;
        var next=state.setValue(OutdoorCameraBlock.PAN,pan);
        if(Safety.buildReason(p,level,cam.getBlockPos(),next,false).isEmpty()
                &&level.getBlockEntity(cam.getBlockPos())==cam&&cam.getBlockState()==state)
            level.setBlockAndUpdate(cam.getBlockPos(),next);
    }
}
