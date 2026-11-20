package dev.anya.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Function;

/** Personal packing lists contain requests. Every transfer moves actual stacks in a guarded plan. */
final class FieldKit {
    static final int KIT_COUNT = 3;
    private static final int MAX_KEEP_RULES = 128;
    private static final int MAX_SAMPLE_BYTES = 8192;
    private static final int UNDO_TICKS = 20 * 60;
    static final class Preferences {
        final Kit[] kits = new Kit[KIT_COUNT];
        final SortedMap<String,Integer> keep = new TreeMap<>();

        void keep(String id, int count) {
            if (count < 0 || count > 2304) throw new IllegalArgumentException("Choose a keep count from 0 to 2304.");
            if (count == 0) keep.remove(id);
            else {
                if (!keep.containsKey(id) && keep.size() >= MAX_KEEP_RULES) throw new IllegalArgumentException("Remove an old keep rule first.");
                keep.put(id, count);
            }
        }
        void write(CompoundTag entry, HolderLookup.Provider registry) {
            CompoundTag rules = new CompoundTag();
            keep.forEach(rules::putInt);
            entry.put("FieldKitKeep", rules);
            ListTag saved = new ListTag();
            for (int slot = 0; slot < KIT_COUNT; slot++) {
                Kit kit = kits[slot];
                if (kit == null) continue;
                CompoundTag item = new CompoundTag();
                item.putInt("Slot", slot);
                item.putString("Name", kit.name());
                ListTag samples = new ListTag();
                for (ItemStack goal : kit.goals()) samples.add(goal.save(registry));
                item.put("Goals", samples);
                saved.add(item);
            }
            entry.put("FieldKits", saved);
        }
        void read(CompoundTag entry, HolderLookup.Provider registry) {
            CompoundTag rules = entry.getCompound("FieldKitKeep");
            for (String id : rules.getAllKeys()) {
                int count = rules.getInt(id);
                if (keep.size() >= MAX_KEEP_RULES) break;
                if (Config.validId(id) && count > 0 && count <= 2304) keep.put(id,count);
            }
            ListTag saved = entry.getList("FieldKits", Tag.TAG_COMPOUND);
            for (int i=0; i < Math.min(saved.size(), KIT_COUNT); i++) {
                CompoundTag item = saved.getCompound(i);
                int slot = item.getInt("Slot");
                if (slot < 0 || slot >= KIT_COUNT) continue;
                ListTag tags = item.getList("Goals", Tag.TAG_COMPOUND);
                List<ItemStack> goals = new ArrayList<>();
                for (int j=0; j < Math.min(tags.size(), 9); j++) {
                    CompoundTag data = tags.getCompound(j);
                    if (data.sizeInBytes() > MAX_SAMPLE_BYTES) continue;
                    ItemStack sample = ItemStack.parseOptional(registry, data);
                    if (canPack(sample)) goals.add(sample);
                }
                if (!goals.isEmpty()) kits[slot] = new Kit(name(item.getString("Name"), slot), List.copyOf(goals));
            }
        }
    }
    record Kit(String name, List<ItemStack> goals) {}
    static String name(String requested, int slot) {
        String text = requested.strip();
        if (text.length() > 32) text = text.substring(0,32);
        return text.isEmpty() ? "Trip kit " + (slot+1) : text;
    }
    private static boolean canPack(ItemStack s) {
        return !s.isEmpty() && s.getCount() <= s.getMaxStackSize() && s.getItem().canFitInsideContainerItems()
                && !s.is(Content.BACKPACK.get()) && !s.has(DataComponents.BUNDLE_CONTENTS)
                && !s.has(DataComponents.BLOCK_ENTITY_DATA)
                && s.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).equals(ItemContainerContents.EMPTY);
    }
    static Kit capture(Container main, String requestedName, int slot, HolderLookup.Provider registry) {
        List<ItemStack> goals = new ArrayList<>();
        for (int i=0; i<9; i++) {
            ItemStack s = main.getItem(i);
            if (canPack(s) && s.save(registry).sizeInBytes() <= MAX_SAMPLE_BYTES) goals.add(s.copy());
        }
        if (goals.isEmpty()) throw new IllegalArgumentException("Put supplies in your hotbar first. Bags, filled containers and oversized data are skipped.");
        return new Kit(name(requestedName,slot), List.copyOf(goals));
    }
    static boolean canLocate(Container source,int slot,ItemStack shown,java.util.function.BooleanSupplier canView,
                             java.util.function.Supplier<List<Container>> visible,java.util.function.BooleanSupplier loadedAndSame) {
        if(!canView.getAsBoolean() || visible.get().stream().noneMatch(c->c==source) || !loadedAndSame.getAsBoolean())return false;
        return slot>=0 && slot<source.getContainerSize() && !shown.isEmpty() && Transactions.same(shown,source.getItem(slot));
    }

    record Packed(int moved,int missing) {}
    static Packed restock(Transactions.Plan plan, Container main, List<Container> sources, Kit kit) {
        Map<Transactions.Key,Integer> goal = new LinkedHashMap<>();
        for (ItemStack s : kit.goals()) goal.merge(new Transactions.Key(s),s.getCount(),Integer::sum);
        for (ItemStack s : plan.work(main)) if (!s.isEmpty())
            goal.computeIfPresent(new Transactions.Key(s),(k,n)->Math.max(0,n-s.getCount()));
        int moved=0, missing=0;
        for (var entry : goal.entrySet()) {
            int remaining=entry.getValue();
            for (Container source : sources) {
                if (remaining == 0) break;
                ItemStack[] stacks = plan.work(source);
                for (int i=0; i<stacks.length && remaining>0; i++) {
                    ItemStack s=stacks[i];
                    if (!s.isEmpty() && entry.getKey().equals(new Transactions.Key(s))) {
                        int taken=plan.move(source,i,List.of(main),remaining);
                        moved+=taken;
                        remaining-=taken;
                    }
                }
            }
            missing+=remaining;
        }
        return new Packed(moved,missing);
    }

    record Sweep(int moved,int protectedItems,int kept,int noMatch,int full,long leftMask) {
        String report() { return "Moved "+moved+" items. Protected "+protectedItems+", kept by rule "+kept+
                ", no match "+noMatch+", full or filtered "+full+"."; }
    }
    static Sweep sweep(Transactions.Plan plan, Container main, Profiles.PlayerState prefs,
                       Function<ItemStack,List<Container>> destinations) {
        Map<String,Integer> remaining = new HashMap<>(prefs.fieldKit.keep);
        int locked=0, kept=0, noMatch=0, full=0, moved=0;
        long mask=0;
        ItemStack[] contents=plan.work(main);
        for (int i=0;i<contents.length;i++) {
            ItemStack s=contents[i];
            if (s.isEmpty() || !prefs.protectedSlot(i)) continue;
            locked+=s.getCount();
            remaining.computeIfPresent(Categories.id(s),(id,n)->Math.max(0,n-s.getCount()));
        }
        for (int i=0;i<contents.length;i++) {
            ItemStack s=contents[i];
            if (s.isEmpty() || prefs.protectedSlot(i)) continue;
            String id=Categories.id(s);
            int reserved=Math.min(s.getCount(),remaining.getOrDefault(id,0));
            if (reserved>0) remaining.put(id,remaining.get(id)-reserved);
            kept+=reserved;
            int offered=s.getCount()-reserved;
            if (offered==0) continue;
            List<Container> targets=destinations.apply(s);
            int accepted=plan.move(main,i,targets,offered);
            moved+=accepted;
            if (targets.isEmpty()) noMatch+=offered;
            else full+=offered-accepted;
            if (accepted<offered) mask|=1L<<i;
        }
        return new Sweep(moved,locked,kept,noMatch,full,mask);
    }

    // A receipt holds no inventory, player or world alive and expires after one minute.
    // Each line can only withdraw its deposited surplus from the very same physical slot.
    private static final class Line {
        final WeakReference<Object> source;
        final int slot, floor, added;
        final ItemStack sample;
        WeakReference<ItemStack> live;
        WeakReference<ItemContainerContents> bagContents;
        int available;

        Line(Container c,int slot,int floor,int added) {
            source=new WeakReference<>(identity(c)); this.slot=slot; this.floor=floor;
            this.added=added; available=added; sample=c.getItem(slot).copyWithCount(1); follow(c,c.getItem(slot));
        }
        void follow(Container c,ItemStack stack) {
            live=new WeakReference<>(stack);
            if(c instanceof BagInventory bag)bagContents=new WeakReference<>(bag.stack.get(DataComponents.CONTAINER));
        }
        boolean recognizes(Container c,ItemStack current) {
            if(current.isEmpty() || source.get()!=identity(c) || !ItemStack.isSameItemSameComponents(current,sample))return false;
            if(c instanceof BagInventory bag){
                // A new wrapper copies slots from the host. Only the exact last committed
                // immutable component on this exact bag can establish continuity.
                var prior=bagContents.get();
                return prior!=null && prior==bag.stack.get(DataComponents.CONTAINER)
                        && bag.matchesSavedSlot(slot,current,prior);
            }
            return current==live.get();
        }
        void limit(int minimum) {available=Math.min(available,Math.max(0,minimum-floor));}
    }
    private static Object identity(Container c) { return c instanceof BagInventory b ? b.stack : c; }
    private static boolean managed(Container c) {return c instanceof BagInventory || c instanceof StorageEntity be && !be.kind().equals("hub");}
    // A weak host key and weak receipt lines retain neither loaded blocks nor players.
    private static final Map<Object,List<WeakReference<Line>>> watchers=new WeakHashMap<>();
    private static List<Line> watching(Container c){
        if(!managed(c))return List.of();
        var refs=watchers.get(identity(c));
        if(refs==null)return List.of();
        List<Line> active=new ArrayList<>();
        refs.removeIf(ref->{
            Line line=ref.get();
            if(line==null||line.available<=0)return true;
            active.add(line);return false;
        });
        return active;
    }
    static void chestEdited(StorageEntity c) {
        var level = c.getLevel();
        if (level != null && (level.isClientSide || level.getServer() == null || !level.getServer().isSameThread())) return;
        if (Transactions.writing(c)) return;
        for(Line line:watching(c)){
            ItemStack stack=c.getItem(line.slot);
            if(line.recognizes(c,stack))line.limit(stack.getCount());
            else line.available=0;
        }
    }
    static List<Runnable> trackWrites(Transactions.Plan plan,Collection<Container> stores) {
        List<Runnable> updates=new ArrayList<>();
        for(Container c:stores)for(Line line:watching(c)){
            ItemStack before=c.getItem(line.slot),next=plan.work(c)[line.slot];
            if(!line.recognizes(c,before)){line.available=0;continue;}
            int low=ItemStack.isSameItemSameComponents(before,next)?Math.min(before.getCount(),next.getCount()):0;
            updates.add(()->{line.limit(low);if(line.available>0)line.follow(c,c.getItem(line.slot));});
        }
        return updates;
    }
    static final class Receipt {
        private final List<Line> lines=new ArrayList<>();
        private final String scope;
        private final long expires;
        private int eligible, external;
        Receipt(Transactions.Plan plan,List<Container> stores,String scope,long now) {
            this.scope=scope;
            this.expires=now+UNDO_TICKS;
            for (Container store:stores) {
                ItemStack[] before=plan.original(store), after=plan.work(store);
                for (int i=0;i<before.length;i++) {
                    int added=after[i].getCount()-before[i].getCount();
                    if(added<=0)continue;
                    if(!managed(store)){external+=added;continue;}
                    eligible+=added;
                    Line line=new Line(store,i,before[i].getCount(),added);
                    lines.add(line);
                    watchers.computeIfAbsent(identity(store),ignored->new ArrayList<>()).add(new WeakReference<>(line));
                }
            }
        }
        int eligible(){return eligible;}
        int external(){return external;}
        String coverage(){return "Undo eligible "+eligible+" in Anya storage. External storage "+external+".";}
        boolean available(String scope,long now) { return this.scope.equals(scope) && now>=expires-UNDO_TICKS && now<=expires; }
        Packed undo(Transactions.Plan plan,Container main,List<Container> stores) {
            Map<Object,Container> accessible=new IdentityHashMap<>();
            for(Container c:stores) accessible.put(identity(c),c);
            int moved=0,missing=0;
            for(Line line:lines) {
                Container c=accessible.get(line.source.get());
                int taken=0;
                if(c!=null && line.slot<c.getContainerSize()) {
                    ItemStack live=plan.work(c)[line.slot];
                    if(line.available>0 && line.recognizes(c,c.getItem(line.slot))) {
                        int available=Math.min(line.available,Math.max(0,live.getCount()-line.floor));
                        taken=plan.move(c,line.slot,List.of(main),available);
                    }
                }
                moved+=taken;
                missing+=line.added-taken;
            }
            return new Packed(moved,missing);
        }
    }
    private static final Map<ServerPlayer,Receipt> receipts=new WeakHashMap<>();
    static void clear(){receipts.clear();watchers.clear();}
    static String remember(ServerPlayer p,Transactions.Plan plan,List<Container> stores,String scope) {
        Receipt receipt=new Receipt(plan,stores,scope,now(p));
        receipts.put(p,receipt);
        return receipt.coverage();
    }
    static String networkScope(ServerPlayer p,StorageEntity hub) {
        return p.level().dimension().location()+"/"+(hub==null?"nearby":"network/"+hub.getBlockPos().asLong());
    }
    static long now(ServerPlayer p) { return p.getServer().overworld().getGameTime(); }
    static int undo(ServerPlayer p,String scope,List<Container> storage,Container main,Transactions.Plan plan) {
        var state=Profiles.of(p);
        Receipt receipt=receipts.get(p);
        if (receipt==null || !receipt.available(scope,now(p))) throw new IllegalArgumentException("No recent dump here. Undo is available for one minute.");
        if(receipt.eligible()==0)throw new IllegalArgumentException("All "+receipt.external()+" dumped items went to external storage, which cannot be undone.");
        Packed undo=receipt.undo(plan,main,storage);
        String error=undo.moved()>0 ? plan.commit(p.getServer()) : "";
        if (!error.isEmpty()) throw new IllegalArgumentException(error);
        receipts.remove(p);
        state.lastReport="Undo returned "+undo.moved()+" items. "+undo.missing()+" eligible items could no longer be returned. External storage "+receipt.external()+".";
        AnyaStorage.tell(p,state.lastReport);
        return undo.moved();
    }

    private FieldKit() {}
}
