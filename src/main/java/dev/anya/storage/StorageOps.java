package dev.anya.storage;

import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded loaded-chunk discovery and component-preserving, server-thread transactions. */
public final class StorageOps {
    private static final Set<StorageBlockEntity> LOADED=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<StorageBlockEntity,Index> INDICES=new IdentityHashMap<>();
    private static boolean committing,recoveryLocked;
    private static int cursor;
    private StorageOps(){}
    public static void reset(){LOADED.clear();INDICES.clear();committing=false;recoveryLocked=false;cursor=0;}
    public static void register(StorageBlockEntity b){if(b.kind()==0)LOADED.add(b);}
    public static void unregister(StorageBlockEntity b){LOADED.remove(b);INDICES.remove(b);}
    public static void invalidateAll(){INDICES.values().forEach(i->i.nextScan=0);}
    public static List<StorageBlockEntity> loadedHubs(){return List.copyOf(LOADED);}
    public static String itemId(ItemStack s){return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();}
    public static boolean same(ItemStack a,ItemStack b){return a.getCount()==b.getCount()&&(a.isEmpty()&&b.isEmpty()||ItemStack.isSameItemSameComponents(a,b));}
    public static boolean identity(ItemStack a,ItemStack b){return !a.isEmpty()&&!b.isEmpty()&&ItemStack.isSameItemSameComponents(a,b);}
    public static String category(ItemStack s){
        String override=Rules.current.categories.get(itemId(s));if(override!=null)return override;
        if(s.has(DataComponents.FOOD))return "food";
        if(tag(s,"c:ores")||tag(s,"c:raw_materials"))return "ores";
        if(tag(s,"c:ingots")||tag(s,"c:gems")||tag(s,"c:nuggets"))return "materials";
        if(s.is(Items.REDSTONE)||s.is(Items.REPEATER)||s.is(Items.COMPARATOR)||s.is(Items.REDSTONE_TORCH))return "redstone";
        if(s.isDamageableItem()||s.getItem() instanceof ArmorItem)return "equipment";
        if(s.is(Items.WHEAT_SEEDS)||s.is(Items.BEETROOT_SEEDS)||s.is(Items.PUMPKIN_SEEDS)||s.is(Items.MELON_SEEDS)||s.is(Items.WHEAT))return "farming";
        if(s.getItem() instanceof BlockItem)return "building";
        if(s.is(Items.BONE)||s.is(Items.ROTTEN_FLESH)||s.is(Items.GUNPOWDER)||s.is(Items.STRING)||s.is(Items.ENDER_PEARL)||s.is(Items.BLAZE_ROD))return "mob_drops";
        return "misc";
    }
    private static boolean tag(ItemStack s,String id){return s.is(TagKey.create(Registries.ITEM,ResourceLocation.parse(id)));}
    private static boolean candidate(BlockEntity b){
        if(b instanceof StorageBlockEntity s)return s.kind()!=0;
        Class<?> c=b.getClass();
        return Rules.current.allowChests&&(c==ChestBlockEntity.class||c==TrappedChestBlockEntity.class)||Rules.current.allowBarrels&&c==BarrelBlockEntity.class||Rules.current.allowShulkers&&c==ShulkerBoxBlockEntity.class;
    }
    public record Ref(BlockPos pos,BlockEntity block,Container inventory,BooleanSupplier permission) {
        public boolean valid(){return permission.getAsBoolean();}
    }
    private static boolean hubValid(ServerPlayer p,StorageBlockEntity hub){return !hub.isRemoved()&&hub.getLevel()==p.serverLevel()&&p.serverLevel().hasChunkAt(hub.getBlockPos())&&p.serverLevel().getBlockEntity(hub.getBlockPos())==hub&&hub.allowed(p)&&hub.inRange(p.blockPosition())&&Claims.allowed(p,hub.getBlockPos());}
    public static boolean belongs(StorageBlockEntity hub,BlockPos pos){
        return LOADED.stream().filter(h->h.getLevel()==hub.getLevel()&&!h.isRemoved()&&h.inRange(pos)).min(Comparator.comparingDouble((StorageBlockEntity h)->h.getBlockPos().distSqr(pos)).thenComparingLong(h->h.getBlockPos().asLong())).orElse(hub)==hub;
    }
    public static Ref reference(ServerPlayer p,BlockPos pos,StorageBlockEntity hub){
        ServerLevel level=p.serverLevel();if(!level.hasChunkAt(pos))return null;BlockEntity b=level.getBlockEntity(pos);if(b==null||!candidate(b))return null;
        Container inventory=b instanceof StorageBlockEntity s?s.inventory:b instanceof Container c?c:null;if(inventory==null)return null;
        BooleanSupplier permission=()->{
            if(recoveryLocked||p.isSpectator()||!p.isAlive()||p.serverLevel()!=level||!level.hasChunkAt(pos)||level.getBlockEntity(pos)!=b||!candidate(b)||!Claims.allowed(p,pos))return false;
            if(hub!=null&&(!Rules.current.network||!hubValid(p,hub)||!hub.inRange(pos)||!belongs(hub,pos)))return false;
            if(hub==null&&!cubeRange(p.blockPosition(),pos,Rules.current.outsideRadius))return false;
            if(b instanceof StorageBlockEntity own&&!own.allowed(p))return false;
            if(b instanceof RandomizableContainerBlockEntity loot&&loot.getLootTable()!=null)return false;
            return !(b instanceof BaseContainerBlockEntity locked)||locked.canOpen(p);
        };
        Ref ref=new Ref(pos.immutable(),b,inventory,permission);return ref.valid()?ref:null;
    }
    public static boolean cubeRange(BlockPos a,BlockPos b,int radius){return Math.abs((long)a.getX()-b.getX())<=radius&&Math.abs((long)a.getY()-b.getY())<=radius&&Math.abs((long)a.getZ()-b.getZ())<=radius;}
    public static StorageBlockEntity nearestHub(ServerPlayer p){return LOADED.stream().filter(h->h.kind()==0&&hubValid(p,h)).min(Comparator.comparingDouble((StorageBlockEntity h)->h.getBlockPos().distSqr(p.blockPosition())).thenComparingLong(h->h.getBlockPos().asLong())).orElse(null);}
    public static final class Index {
        final StorageBlockEntity hub;
        List<ChunkPos> chunks=List.of();
        final List<BlockPos> pending=new ArrayList<>();
        public List<BlockPos> positions=List.of();
        int offset;
        long activeUntil,nextScan,completedAt,lastNanos;
        boolean scanning,ready;
        Index(StorageBlockEntity hub){this.hub=hub;}
        void start(){chunks=chunks(hub.getBlockPos(),hub.radius());offset=0;pending.clear();scanning=true;}
        void scan(){
            long start=System.nanoTime();ServerLevel level=(ServerLevel)hub.getLevel();
            if(offset<chunks.size()){
                ChunkPos p=chunks.get(offset++);LevelChunk chunk=level.getChunkSource().getChunkNow(p.x,p.z);
                if(chunk!=null)for(var entry:chunk.getBlockEntities().entrySet())if(candidate(entry.getValue())&&hub.inRange(entry.getKey())&&pending.size()<Rules.current.maxContainers)pending.add(entry.getKey().immutable());
            }
            if(offset>=chunks.size()||pending.size()>=Rules.current.maxContainers){
                positions=pending.stream().distinct().sorted(Comparator.comparingLong(BlockPos::asLong)).toList();ready=true;scanning=false;completedAt=level.getGameTime();nextScan=completedAt+Rules.current.scanInterval;
            }
            lastNanos=System.nanoTime()-start;
        }
    }
    private static List<ChunkPos> chunks(BlockPos pos,int radius){
        List<ChunkPos> out=new ArrayList<>();for(int x=(pos.getX()-radius)>>4;x<=(pos.getX()+radius)>>4;x++)for(int z=(pos.getZ()-radius)>>4;z<=(pos.getZ()+radius)>>4;z++)out.add(new ChunkPos(x,z));
        out.sort(Comparator.comparingDouble(p->p.getMiddleBlockPosition(pos.getY()).distSqr(pos)));return out;
    }
    public static Index request(StorageBlockEntity hub){Index i=INDICES.computeIfAbsent(hub,Index::new);i.activeUntil=hub.getLevel().getGameTime()+200;return i;}
    public static void tick(MinecraftServer server){
        LOADED.removeIf(b->b.isRemoved()||b.getLevel()==null);INDICES.keySet().removeIf(b->!LOADED.contains(b));
        List<Index> active=INDICES.values().stream().filter(i->i.hub.getLevel() instanceof ServerLevel&&i.activeUntil>=i.hub.getLevel().getGameTime()).toList();if(active.isEmpty())return;
        for(int budget=0;budget<Rules.current.scanBudget;budget++){
            Index i=active.get(Math.floorMod(cursor++,active.size()));long now=i.hub.getLevel().getGameTime();
            if(!i.scanning&&now>=i.nextScan)i.start();if(i.scanning)i.scan();
        }
    }
    public static List<Ref> references(ServerPlayer p,StorageBlockEntity hub){
        List<BlockPos> positions;
        if(hub!=null){if(!hubValid(p,hub))return List.of();positions=request(hub).positions;}
        else {
            List<BlockPos> nearby=new ArrayList<>();
            for(ChunkPos cp:chunks(p.blockPosition(),Rules.current.outsideRadius)){
                LevelChunk chunk=p.serverLevel().getChunkSource().getChunkNow(cp.x,cp.z);
                if(chunk!=null)for(var e:chunk.getBlockEntities().entrySet())if(candidate(e.getValue())&&cubeRange(p.blockPosition(),e.getKey(),Rules.current.outsideRadius)&&nearby.size()<Rules.current.maxContainers)nearby.add(e.getKey().immutable());
            }
            positions=nearby.stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
        }
        List<Ref> result=new ArrayList<>();Set<Container> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(BlockPos pos:positions){Ref ref=reference(p,pos,hub);if(ref!=null&&seen.add(ref.inventory))result.add(ref);}return result;
    }
    public static List<String> metrics(ServerPlayer p,StorageBlockEntity hub){
        List<String> out=new ArrayList<>();out.add(Claims.status(p.getServer()));out.add("Global scan budget: "+Rules.current.scanBudget+" chunks/tick");out.add("Container limit: "+Rules.current.maxContainers);out.add("Transfers: "+(recoveryLocked?"LOCKED after transaction recovery failure":"enabled"));
        if(hub!=null){Index i=request(hub);out.add("Indexed candidates: "+i.positions.size());out.add("Accessible now: "+references(p,hub).size());out.add("Index age: "+(i.ready?(hub.getLevel().getGameTime()-i.completedAt)+" ticks":"not ready"));out.add("Scanning: "+i.scanning+" ("+i.offset+"/"+i.chunks.size()+")");out.add("Last chunk scan: "+String.format(Locale.ROOT,"%.3f ms",i.lastNanos/1000000.0));}
        out.add("Unloaded chunks are never forced open.");return out;
    }

    private static final class Key {
        final ItemStack stack;
        Key(ItemStack stack){this.stack=stack.copyWithCount(1);}
        @Override public int hashCode(){return 31*stack.getItem().hashCode()+stack.getComponents().hashCode();}
        @Override public boolean equals(Object o){return o instanceof Key k&&identity(stack,k.stack);}
    }
    public static final class Plan {
        private final IdentityHashMap<Container,ItemStack[]> before=new IdentityHashMap<>(),after=new IdentityHashMap<>();
        private final List<Container> order=new ArrayList<>();
        private final List<BooleanSupplier> guards=new ArrayList<>();
        private boolean used;
        public Plan guard(BooleanSupplier guard){guards.add(guard);return this;}
        public ItemStack[] state(Container c){
            if(!after.containsKey(c)){
                if(c.getContainerSize()>864)throw new IllegalArgumentException("Unsupported container size");
                ItemStack[] a=new ItemStack[c.getContainerSize()],b=new ItemStack[a.length];for(int i=0;i<a.length;i++){a[i]=c.getItem(i).copy();b[i]=a[i].copy();}
                before.put(c,a);after.put(c,b);order.add(c);
            }return after.get(c);
        }
        public int move(Container source,int slot,Container target,int max){
            if(source==target||max<=0)return 0;ItemStack[] from=state(source);ItemStack s=from[slot];if(s.isEmpty())return 0;
            int accepted=insert(target,s,Math.min(max,s.getCount()));if(accepted>0){s.shrink(accepted);if(s.isEmpty())from[slot]=ItemStack.EMPTY;}return accepted;
        }
        public int insert(Container target,ItemStack stack,int amount){
            ItemStack[] to=state(target);int remaining=amount,limit=target instanceof Inventory?Math.min(36,to.length):to.length;
            for(int pass=0;pass<2&&remaining>0;pass++)for(int i=0;i<limit&&remaining>0;i++){
                ItemStack old=to[i];if((pass==0?old.isEmpty():!old.isEmpty())||!target.canPlaceItem(i,stack)||!old.isEmpty()&&!identity(old,stack))continue;
                int room=Math.min(target.getMaxStackSize(),stack.getMaxStackSize())-(old.isEmpty()?0:old.getCount());int n=Math.min(remaining,Math.max(0,room));
                if(n>0){if(old.isEmpty())to[i]=stack.copyWithCount(n);else old.grow(n);remaining-=n;}
            }return amount-remaining;
        }
        public boolean contains(Container c,ItemStack s){return Arrays.stream(state(c)).anyMatch(x->identity(x,s));}
        public boolean originallyContains(Container c,ItemStack s){state(c);return Arrays.stream(before.get(c)).anyMatch(x->identity(x,s));}
        public int changes(){int n=0;for(Container c:order){ItemStack[] a=before.get(c),b=after.get(c);for(int i=0;i<a.length;i++)if(!same(a[i],b[i]))n++;}return n;}
        public boolean isCurrent(){
            if(used||recoveryLocked||guards.stream().anyMatch(g->!g.getAsBoolean()))return false;
            for(Container c:order){ItemStack[] a=before.get(c);if(c.getContainerSize()!=a.length)return false;for(int i=0;i<a.length;i++)if(!same(a[i],c.getItem(i)))return false;}return true;
        }
        private static Map<Key,Long> totals(Collection<ItemStack[]> inventories){Map<Key,Long> totals=new HashMap<>();for(ItemStack[] inv:inventories)for(ItemStack s:inv)if(!s.isEmpty())totals.merge(new Key(s),(long)s.getCount(),Long::sum);return totals;}
        public boolean commit(){
            if(committing||!isCurrent())return false;
            if(!totals(before.values()).equals(totals(after.values())))throw new IllegalStateException("Item conservation check failed");
            committing=true;used=true;
            try {
                for(Container c:order){ItemStack[] a=before.get(c),b=after.get(c);for(int i=0;i<a.length;i++)if(!same(a[i],b[i]))c.setItem(i,b[i].copy());c.setChanged();}
                return true;
            }catch(RuntimeException failure){
                boolean restored=true;
                for(Container c:order)try{ItemStack[] original=before.get(c);for(int i=0;i<original.length;i++)c.setItem(i,original[i].copy());c.setChanged();}catch(RuntimeException rollback){restored=false;failure.addSuppressed(rollback);}
                recoveryLocked=!restored;AnyaStorage.LOG.error("Storage transfer aborted; rollback successful={}",restored,failure);
                return false;
            }finally{committing=false;}
        }
    }
    public static Plan guardedPlan(List<Ref> refs){Plan p=new Plan();for(Ref r:refs)p.guard(r::valid);return p;}
    public static List<Ref> ordered(List<Ref> refs,StorageBlockEntity hub,ItemStack s,Plan plan){
        String category=category(s);
        return refs.stream().filter(r->{String rule=hub==null?"any":hub.destinations.getOrDefault(r.pos.asLong(),"any");return rule.equals("any")||rule.equals(category);})
                .sorted(Comparator.comparingInt((Ref r)->hub!=null&&category.equals(hub.destinations.get(r.pos.asLong()))?0:plan.originallyContains(r.inventory,s)?1:2).thenComparingLong(r->r.pos.asLong())).toList();
    }
    private static boolean matchesCategory(Container c,ItemStack stack){String category=category(stack);for(int i=0;i<c.getContainerSize();i++)if(!c.getItem(i).isEmpty()&&category(c.getItem(i)).equals(category))return true;return false;}
    public static int deposit(ServerPlayer player,int slot,List<Ref> refs,StorageBlockEntity hub,boolean dump,boolean manual){
        if(slot<0||slot>=36)return 0;ItemStack stack=player.getInventory().getItem(slot);if(stack.isEmpty())return 0;
        Plan plan=guardedPlan(refs);int total=0;
        for(Ref ref:ordered(refs,hub,stack,plan)){
            boolean designated=hub!=null&&category(stack).equals(hub.destinations.get(ref.pos.asLong()));
            boolean eligible=manual||plan.originallyContains(ref.inventory,stack)||dump&&(designated||matchesCategory(ref.inventory,stack));
            if(eligible)total+=plan.move(player.getInventory(),slot,ref.inventory,stack.getCount());
        }
        return total>0&&plan.commit()?total:0;
    }
    public static int extract(ServerPlayer p,ItemStack exemplar,List<Ref> refs,int requested){
        Plan plan=guardedPlan(refs);int total=0;
        for(Ref ref:refs)for(int slot=0;slot<ref.inventory.getContainerSize()&&total<requested;slot++)if(identity(ref.inventory.getItem(slot),exemplar))total+=plan.move(ref.inventory,slot,p.getInventory(),requested-total);
        return total>0&&plan.commit()?total:0;
    }
    public static void quickStack(ServerPlayer p,boolean dump){
        StorageData data=StorageData.get(p.getServer());StorageData.Prefs prefs=data.prefs(p.getUUID());long now=p.serverLevel().getGameTime();
        if(!Rules.current.quickStack||p.isSpectator()||!p.isAlive()||now-prefs.lastAction<Rules.current.cooldown)return;prefs.lastAction=now;prefs.skipped.clear();
        StorageBlockEntity hub=Rules.current.network?nearestHub(p):null;List<Ref> refs=references(p,hub);int moved=0;
        if(hub!=null&&!request(hub).ready){AnyaStorage.tell(p,"Base index is warming up. No items were moved.");return;}
        for(int slot=0;slot<36;slot++){
            ItemStack stack=p.getInventory().getItem(slot);if(stack.isEmpty())continue;
            if(prefs.locked.get(slot)){prefs.skipped.put(slot,"Protected slot");continue;}
            int before=stack.getCount(),n=deposit(p,slot,refs,hub,dump,false);moved+=n;
            if(n<before)prefs.skipped.put(slot,refs.isEmpty()?"No eligible loaded destination in range; check claims, ownership and container-type settings":n==0?"No matching/category destination, destination full, filter rejected, or access changed":"Destination capacity exhausted; remainder stayed in your inventory");
        }
        p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();AnyaStorage.tell(p,(dump?"Category dump":"QuickStack")+": moved "+moved+" items. Use /as > Why skipped for details.");
    }
    public static Plan organize(ServerPlayer p,StorageBlockEntity hub,List<Ref> refs){
        if(!Rules.current.organize)throw new IllegalArgumentException("Organization is disabled");
        int slots=refs.stream().mapToInt(r->r.inventory.getContainerSize()).sum();if(slots>4096)throw new IllegalArgumentException("Preview is limited to 4096 slots per operation");
        Plan plan=guardedPlan(refs);long revision=Rules.current.revision;plan.guard(()->Rules.current.revision==revision);
        List<ItemStack> stacks=new ArrayList<>();
        for(Ref r:refs){ItemStack[] state=plan.state(r.inventory);for(ItemStack s:state)if(!s.isEmpty())stacks.add(s.copy());Arrays.fill(state,ItemStack.EMPTY);}
        stacks.sort(Comparator.comparing((ItemStack s)->category(s)).thenComparing(StorageOps::itemId).thenComparing(s->s.getComponentsPatch().toString()));
        for(ItemStack stack:stacks){int remaining=stack.getCount();for(Ref r:ordered(refs,hub,stack,plan)){
                boolean designated=hub!=null&&category(stack).equals(hub.destinations.get(r.pos.asLong()));
                if(hub!=null&&!hub.overflow&&!designated&&!plan.originallyContains(r.inventory,stack))continue;
                remaining-=plan.insert(r.inventory,stack,remaining);if(remaining==0)break;
            }
            if(remaining!=0)throw new IllegalArgumentException("Destination rules leave items without space. Nothing was moved.");
        }
        return plan;
    }
    public record Entry(ItemStack icon,long count){}
    public static List<Entry> entries(List<? extends Container> inventories,String search,Set<String> favorites){
        Map<Key,Long> counts=new LinkedHashMap<>();for(Container inv:inventories)for(int slot=0;slot<inv.getContainerSize();slot++){ItemStack s=inv.getItem(slot);if(!s.isEmpty())counts.merge(new Key(s),(long)s.getCount(),Long::sum);}
        String q=search.toLowerCase(Locale.ROOT).strip();List<Entry> out=new ArrayList<>();
        for(var e:counts.entrySet()){ItemStack s=e.getKey().stack;String id=itemId(s);boolean match=q.isEmpty()||q.equals("*")&&favorites.contains(id)||q.startsWith("@")&&id.startsWith(q.substring(1))||q.startsWith("#")&&category(s).contains(q.substring(1))||!q.startsWith("@")&&!q.startsWith("#")&&!q.equals("*")&&(id.contains(q)||s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q));if(match)out.add(new Entry(s,e.getValue()));}
        out.sort(Comparator.comparingInt((Entry e)->favorites.contains(itemId(e.icon))?0:1).thenComparing(e->itemId(e.icon)).thenComparing(e->e.icon.getComponentsPatch().toString()));return out;
    }
}
