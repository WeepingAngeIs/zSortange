package dev.anya.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Budgeted discovery of positions only. Physical inventories remain the sole source of their items. */
public final class Network {
    public record Target(BlockPos pos,Container inventory) {}
    public enum Mode { MANUAL, QUICK, DUMP }
    private static final Map<ServerLevel,LinkedHashSet<StorageEntity>> HUBS=new HashMap<>();
    private static final Map<StorageEntity,Index> INDEX=new IdentityHashMap<>();
    private static final Map<ServerLevel,Integer> CURSOR=new HashMap<>();
    public static long lastOperationMicros;
    private static final class Index {
        final Set<BlockPos> candidates=new HashSet<>();
        int cursor,radius=-1;
        long lastSweep,nextSweep;
        boolean truncated;
    }
    public static void clear(){HUBS.clear();INDEX.clear();CURSOR.clear();}
    public static void add(StorageEntity hub){if(hub.getLevel() instanceof ServerLevel level)HUBS.computeIfAbsent(level,k->new LinkedHashSet<>()).add(hub);}
    public static void remove(StorageEntity hub){if(hub.getLevel() instanceof ServerLevel level){Set<StorageEntity> set=HUBS.get(level);if(set!=null)set.remove(hub);}INDEX.remove(hub);}
    public static boolean within(BlockPos a,BlockPos b,int radius){return a.distSqr(b)<=(double)radius*radius;}
    public static boolean inRange(ServerPlayer p,StorageEntity hub){return hub.getLevel()==p.level()&&!hub.isRemoved()&&p.serverLevel().hasChunkAt(hub.getBlockPos())&&p.distanceToSqr(hub.getBlockPos().getX()+.5,hub.getBlockPos().getY()+.5,hub.getBlockPos().getZ()+.5)<=(double)hub.radius()*hub.radius();}
    public static String hubReason(ServerPlayer p,StorageEntity hub){if(!Config.get().network)return "Storage networks are disabled";if(!inRange(p,hub))return "Hub unloaded, wrong dimension or outside its radius";return Safety.reason(p,hub,null);}
    public static StorageEntity nearest(ServerPlayer p){
        return HUBS.getOrDefault(p.serverLevel(),new LinkedHashSet<>()).stream().filter(h->hubReason(p,h).isEmpty())
            .min(Comparator.comparingDouble((StorageEntity h)->p.distanceToSqr(h.getBlockPos().getX()+.5,h.getBlockPos().getY()+.5,h.getBlockPos().getZ()+.5)).thenComparingLong(h->h.getBlockPos().asLong())).orElse(null);
    }
    public static void tick(LevelTickEvent.Post event){
        if(!(event.getLevel() instanceof ServerLevel level)||!Config.get().network)return;
        List<StorageEntity> hubs=new ArrayList<>(HUBS.getOrDefault(level,new LinkedHashSet<>()));if(hubs.isEmpty())return;
        int index=Math.floorMod(CURSOR.getOrDefault(level,0),hubs.size());CURSOR.put(level,index+1);
        scan(hubs.get(index));
    }
    private static void scan(StorageEntity hub){
        if(hub.isRemoved()||!(hub.getLevel() instanceof ServerLevel level)||!level.hasChunkAt(hub.getBlockPos()))return;
        Config.Data config=Config.get();Index index=INDEX.computeIfAbsent(hub,h->new Index());int radius=hub.radius();
        if(index.radius!=radius){index.candidates.clear();index.cursor=0;index.radius=radius;index.nextSweep=0;}
        if(level.getGameTime()<index.nextSweep)return;
        BlockPos pos=hub.getBlockPos();int minX=(pos.getX()-radius)>>4,maxX=(pos.getX()+radius)>>4,minZ=(pos.getZ()-radius)>>4,maxZ=(pos.getZ()+radius)>>4;
        int width=maxX-minX+1,total=width*(maxZ-minZ+1);
        for(int budget=0;budget<config.scanChunksPerTick;budget++){
            int cx=minX+index.cursor%width,cz=minZ+index.cursor/width;
            index.candidates.removeIf(p->(p.getX()>>4)==cx&&(p.getZ()>>4)==cz);
            LevelChunk chunk=level.getChunkSource().getChunkNow(cx,cz);
            if(chunk!=null){int examined=0;for(BlockEntity be:chunk.getBlockEntities().values()){
                if(++examined>config.maxScanEntries){index.truncated=true;break;}
                if(within(pos,be.getBlockPos(),radius)&&supported(be)&&index.candidates.size()<config.maxContainers)index.candidates.add(be.getBlockPos().immutable());
                else if(index.candidates.size()>=config.maxContainers)index.truncated=true;
            }}
            if(++index.cursor>=total){index.cursor=0;index.lastSweep=level.getGameTime();index.nextSweep=index.lastSweep+40;break;}
        }
    }
    public static boolean supported(BlockEntity be){
        if(be instanceof StorageEntity storage)return storage.pocketId==null&&!storage.kind().equals("hub");
        Config.Data c=Config.get();
        return be!=null && ((c.vanillaChests&&(be.getClass()==ChestBlockEntity.class||be.getClass()==TrappedChestBlockEntity.class)) || (c.vanillaBarrels&&be.getClass()==BarrelBlockEntity.class) || (c.vanillaShulkers&&be.getClass()==ShulkerBoxBlockEntity.class));
    }
    public static List<Target> targets(ServerPlayer p,StorageEntity hub){
        List<BlockPos> positions=new ArrayList<>();
        if(hub!=null){if(!hubReason(p,hub).isEmpty())return List.of();Index index=INDEX.computeIfAbsent(hub,h->new Index());positions.addAll(index.candidates);}
        else {
            BlockPos center=p.blockPosition();int radius=Config.get().quickRadius;
            for(int cx=(center.getX()-radius)>>4;cx<=(center.getX()+radius)>>4;cx++)for(int cz=(center.getZ()-radius)>>4;cz<=(center.getZ()+radius)>>4;cz++){
                LevelChunk chunk=p.serverLevel().getChunkSource().getChunkNow(cx,cz);if(chunk==null)continue;int examined=0;
                for(BlockEntity be:chunk.getBlockEntities().values())if(++examined<=Config.get().maxScanEntries&&within(center,be.getBlockPos(),radius)&&supported(be))positions.add(be.getBlockPos().immutable());
            }
        }
        BlockPos center=hub==null?p.blockPosition():hub.getBlockPos();positions.sort(Comparator.comparingDouble((BlockPos pos)->pos.distSqr(center)).thenComparingLong(BlockPos::asLong));
        List<Target> result=new ArrayList<>();int slots=36;
        for(BlockPos pos:positions){
            if(result.size()>=Config.get().maxContainers)break;if(!p.serverLevel().hasChunkAt(pos)||!within(center,pos,hub==null?Config.get().quickRadius:hub.radius()))continue;
            BlockEntity be=p.serverLevel().getBlockEntity(pos);
            if(!supported(be)||!Safety.reason(p,be,hub).isEmpty())continue;
            Container container=(Container)be;if(Transactions.quarantined(container))continue;
            if(slots+container.getContainerSize()>Config.get().maxOperationSlots)break;
            slots+=container.getContainerSize();result.add(new Target(pos,container));
        }
        return result;
    }
    public static String permissionReason(ServerPlayer p,StorageEntity hub,int rights){
        if(hub==null||hub.permits(p,rights))return "";
        return "Base permission denied. Needed: "+BasePermissions.describe(rights);
    }
    public static BooleanSupplier guard(ServerPlayer p,StorageEntity hub,List<Target> targets){return guard(p,hub,targets,0);}
    public static BooleanSupplier guard(ServerPlayer p,StorageEntity hub,List<Target> targets,int rights){
        long revision=Config.get().revision,profile=hub==null?0:hub.profileRevision;
        return ()->{
            if(!p.isAlive()||p.isSpectator()||Config.get().revision!=revision||!permissionReason(p,hub,rights).isEmpty())return false;
            if(hub!=null&&(!hubReason(p,hub).isEmpty()||hub.profileRevision!=profile))return false;
            for(Target target:targets){
                if(!p.serverLevel().hasChunkAt(target.pos))return false;
                BlockEntity be=p.serverLevel().getBlockEntity(target.pos);
                if(be!=target.inventory||!supported(be)||!Safety.reason(p,be,hub).isEmpty())return false;
                if(!within(hub==null?p.blockPosition():hub.getBlockPos(),target.pos,hub==null?Config.get().quickRadius:hub.radius()))return false;
            }
            return true;
        };
    }
    public static List<Container> destinations(Transactions.Plan plan,List<Target> targets,StorageEntity hub,ItemStack stack,Mode mode){
        List<Target> eligible=new ArrayList<>();
        for(Target target:targets){
            StorageEntity.Route rule=hub==null?new StorageEntity.Route():hub.route(target.pos);if(!rule.allows(stack))continue;
            boolean exact=plan.contains(target.inventory,stack),sameType=false;
            for(ItemStack existing:plan.work(target.inventory))if(!existing.isEmpty()&&existing.is(stack.getItem())){sameType=true;break;}
            boolean allowed=switch(mode){case QUICK->exact;case DUMP->sameType||(hub!=null&&rule.designated());case MANUAL->exact||rule.designated()||rule.overflow;};
            if(allowed)eligible.add(target);
        }
        eligible.sort(Comparator.comparingInt((Target t)->{StorageEntity.Route r=hub==null?new StorageEntity.Route():hub.route(t.pos);return -100*r.priority-(r.designated()?20:0)-(plan.contains(t.inventory,stack)?10:0);}).thenComparingLong(t->t.pos.asLong()));
        return eligible.stream().map(Target::inventory).toList();
    }
    public static void quick(ServerPlayer player,boolean dump){
        if(!Config.get().quickStack){AnyaStorage.tell(player,"QuickStack is disabled.");return;}if(!Profiles.cooldown(player))return;
        long start=System.nanoTime();StorageEntity hub=nearest(player);
        String denied=permissionReason(player,hub,BasePermissions.VIEW|BasePermissions.DEPOSIT);if(!denied.isEmpty()){AnyaStorage.tell(player,denied);return;}
        List<Target> targets=targets(player,hub);MainInventory main=new MainInventory(player);
        List<Container> all=new ArrayList<>();all.add(main);targets.forEach(t->all.add(t.inventory));
        Transactions.Plan plan=new Transactions.Plan(all,guard(player,hub,targets,BasePermissions.VIEW|BasePermissions.DEPOSIT));
        FieldKit.Sweep result=FieldKit.sweep(plan,main,Profiles.of(player),
                item->destinations(plan,targets,hub,item,dump?Mode.DUMP:Mode.QUICK));
        String error=result.moved()>0?plan.commit(player.getServer()):"";
        String coverage="";
        if(error.isEmpty()&&result.moved()>0){
            if(dump)coverage=" "+FieldKit.remember(player,plan,targets.stream().map(Target::inventory).toList(),FieldKit.networkScope(player,hub));
            if(hub!=null)hub.logTransfer(player,dump?BaseActivity.Action.DUMP:BaseActivity.Action.QUICKSTACK,result.moved());
        }
        lastOperationMicros=(System.nanoTime()-start)/1000;
        String report=error.isEmpty()?result.report()+" "+targets.size()+" accessible containers."+coverage:error;
        if(targets.isEmpty())report+=" Check range, locks, claims, loaded chunks and container-type settings. "+Safety.status();
        Profiles.of(player).lastReport=report;AnyaStorage.tell(player,report);
    }
    public static void undoNearby(ServerPlayer player) {
        if(!Config.get().quickStack || !Profiles.cooldown(player))return;
        StorageEntity hub=nearest(player);
        int rights=BasePermissions.VIEW|BasePermissions.WITHDRAW;
        if(!permissionReason(player,hub,rights).isEmpty()){AnyaStorage.tell(player,"View and Withdraw access are required.");return;}
        List<Target> targets=targets(player,hub);
        List<Container> stores=targets.stream().map(Target::inventory).toList();
        MainInventory main=new MainInventory(player);
        List<Container> all=new ArrayList<>(stores);all.add(main);
        Transactions.Plan plan=new Transactions.Plan(all,guard(player,hub,targets,rights));
        try {
            int moved=FieldKit.undo(player,FieldKit.networkScope(player,hub),stores,main,plan);
            if(moved>0&&hub!=null)hub.logTransfer(player,BaseActivity.Action.WITHDRAW,moved);
        } catch(IllegalArgumentException e){AnyaStorage.tell(player,e.getMessage());}
    }
    public static Transactions.Plan organize(ServerPlayer player,StorageEntity hub,List<Target> targets){
        String denied=permissionReason(player,hub,BasePermissions.LEGACY);if(!denied.isEmpty())throw new IllegalArgumentException(denied);
        List<Container> containers=targets.stream().map(Target::inventory).toList();Transactions.Plan plan=new Transactions.Plan(containers,guard(player,hub,targets,BasePermissions.LEGACY));List<ItemStack> stacks=new ArrayList<>();
        for(Container container:containers){for(ItemStack item:plan.work(container))if(!item.isEmpty())stacks.add(item.copy());Arrays.fill(plan.work(container),ItemStack.EMPTY);}
        stacks.sort(Categories.order());
        for(ItemStack stack:stacks){for(Container destination:destinations(plan,targets,hub,stack,Mode.MANUAL)){plan.insert(destination,stack);if(stack.isEmpty())break;}if(!stack.isEmpty())throw new IllegalArgumentException("Routing rules leave items without a destination. Allow an overflow chest before organizing.");}
        return plan;
    }
    public static String explain(ServerPlayer p,StorageEntity requestedHub,int slot,boolean dump){
        if(slot<0||slot>=36)return "Only main inventory slots participate.";
        ItemStack item=p.getInventory().getItem(slot);if(item.isEmpty())return "That inventory slot is empty.";
        if(!Config.get().quickStack)return "QuickStack and Dump are disabled by the server.";
        if(Profiles.of(p).protectedSlot(slot))return "Slot "+(slot+1)+" is protected from automatic deposits.";
        StorageEntity hub=requestedHub==null?nearest(p):requestedHub;String denied=permissionReason(p,hub,BasePermissions.VIEW|BasePermissions.DEPOSIT);if(!denied.isEmpty())return denied;List<Target> targets=targets(p,hub);
        if(targets.isEmpty()){
            return "No eligible loaded containers in range or within the scan budget. Chest support="+Config.get().vanillaChests+", barrels="+Config.get().vanillaBarrels+", shulkers="+Config.get().vanillaShulkers+". "+Safety.status();
        }
        MainInventory main=new MainInventory(p);List<Container> all=new ArrayList<>();all.add(main);targets.forEach(t->all.add(t.inventory));Transactions.Plan plan=new Transactions.Plan(all,guard(p,hub,targets));
        List<Container> eligible=destinations(plan,targets,hub,item,dump?Mode.DUMP:Mode.QUICK);
        if(eligible.isEmpty()){
            long filtered=targets.stream().filter(t->hub!=null&&!hub.route(t.pos).allows(item)).count();
            return filtered==targets.size()?"All "+filtered+" accessible containers reject this item's category or exact-item filter.":(dump?"No existing item-type match or designated destination.":"No existing exact item-and-component match. Named, enchanted or damaged variants count separately.")+" Filtered targets: "+filtered+".";
        }
        int accepted=plan.move(main,slot,eligible,item.getCount());
        return accepted==0?"Matching destinations are full or their slot filters reject the item.":"Preview only: "+accepted+" of "+item.getCount()+" items fit across "+eligible.size()+" matching destinations. Nothing moved.";
    }
    public static String performance(StorageEntity hub){
        Index index=INDEX.get(hub);if(index==null)return "Index warming up; only loaded chunks are scanned";
        long age=hub.getLevel().getGameTime()-index.lastSweep;
        return "Indexed "+index.candidates.size()+" containers; completed-sweep age "+age+" ticks; global budget "+Config.get().scanChunksPerTick+" chunks/tick; last transfer "+lastOperationMicros+" us"+(index.truncated?"; scan limit reached":"");
    }
    public static String impact(Config.Data draft){int hubs=0,hidden=0;for(var set:HUBS.values())for(StorageEntity hub:set){hubs++;int newRadius=Math.min(draft.maxRadius,draft.initialRadius+hub.rangeUpgrades*draft.radiusStep);Index index=INDEX.get(hub);if(index!=null)for(BlockPos pos:index.candidates)if(!within(hub.getBlockPos(),pos,newRadius))hidden++;}return "Loaded-only estimate: "+hubs+" hubs, "+hidden+" indexed containers outside proposed ranges. Unloaded bases are not scanned.";}
}
