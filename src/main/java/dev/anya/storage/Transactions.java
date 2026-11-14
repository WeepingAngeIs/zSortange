package dev.anya.storage;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Server-thread snapshot transactions over explicitly supported physical inventories. */
public final class Transactions {
    private static boolean committing;
    private static final Set<Container> QUARANTINED=Collections.newSetFromMap(new IdentityHashMap<>());
    public static boolean quarantined(Container c) { return QUARANTINED.contains(c); }
    public static void clear() { QUARANTINED.clear();committing=false; }
    public record Key(Item item,DataComponentMap components) { public Key(ItemStack s) { this(s.getItem(),s.getComponents()); } }
    public static boolean same(ItemStack a,ItemStack b) { return a.getCount()==b.getCount() && ItemStack.isSameItemSameComponents(a,b); }
    public static final class Plan {
        private final LinkedHashMap<Container,ItemStack[]> before=new LinkedHashMap<>(),after=new LinkedHashMap<>();
        private final BooleanSupplier permission;
        private Map<Key,Long> expected;
        private boolean used;
        public Plan(Collection<? extends Container> containers,BooleanSupplier permission) {
            this.permission=permission;int slots=0;
            for(Container c:containers) {
                if(before.containsKey(c)) continue;
                slots=Math.addExact(slots,c.getContainerSize());
                if(slots>Config.get().maxOperationSlots) throw new IllegalArgumentException("Operation exceeds configured slot budget");
                ItemStack[] a=new ItemStack[c.getContainerSize()],b=new ItemStack[a.length];
                for(int i=0;i<a.length;i++) { a[i]=c.getItem(i).copy();b[i]=a[i].copy(); }
                before.put(c,a);after.put(c,b);
            }
        }
        public ItemStack[] work(Container c) { return Objects.requireNonNull(after.get(c),"Untracked inventory"); }
        public ItemStack[] original(Container c) { return before.get(c); }
        public int changedSlots() { int result=0;for(Container c:before.keySet())for(int i=0;i<c.getContainerSize();i++)if(!same(before.get(c)[i],after.get(c)[i]))result++;return result; }
        public boolean current() {
            if(used||!permission.getAsBoolean()) return false;
            for(Container c:before.keySet()) {
                if(QUARANTINED.contains(c)||c.getContainerSize()!=before.get(c).length) return false;
                for(int i=0;i<c.getContainerSize();i++) if(!same(c.getItem(i),before.get(c)[i])) return false;
            }
            return true;
        }
        public int insert(Container c,ItemStack remaining) {
            if(remaining.isEmpty()) return 0;
            int original=remaining.getCount(); ItemStack[] slots=work(c);
            for(int pass=0;pass<2&&!remaining.isEmpty();pass++) for(int i=0;i<slots.length&&!remaining.isEmpty();i++) {
                ItemStack old=slots[i];
                if((pass==0&&old.isEmpty())||(pass==1&&!old.isEmpty())||!c.canPlaceItem(i,remaining)) continue;
                if(!old.isEmpty()&&!ItemStack.isSameItemSameComponents(old,remaining)) continue;
                int max=Math.min(c.getMaxStackSize(),remaining.getMaxStackSize());
                int accepted=Math.min(remaining.getCount(),Math.max(0,max-old.getCount()));
                if(accepted==0) continue;
                if(old.isEmpty()) slots[i]=remaining.copyWithCount(accepted); else old.grow(accepted);
                remaining.shrink(accepted);
            }
            return original-remaining.getCount();
        }
        public int move(Container source,int slot,List<? extends Container> destinations,int amount) {
            ItemStack original=work(source)[slot];
            if(original.isEmpty()||amount<=0) return 0;
            ItemStack remaining=original.copyWithCount(Math.min(amount,original.getCount()));int offered=remaining.getCount();
            for(Container target:destinations) if(target!=source&&!remaining.isEmpty()) insert(target,remaining);
            int moved=offered-remaining.getCount();
            if(moved>0) { original.shrink(moved);if(original.isEmpty())work(source)[slot]=ItemStack.EMPTY; }
            return moved;
        }
        public boolean contains(Container c,ItemStack stack) { for(ItemStack s:work(c)) if(!s.isEmpty()&&ItemStack.isSameItemSameComponents(s,stack))return true;return false; }
        private static Map<Key,Long> counts(Collection<ItemStack[]> arrays) {
            Map<Key,Long> map=new HashMap<>();for(ItemStack[] slots:arrays)for(ItemStack stack:slots)if(!stack.isEmpty())map.merge(new Key(stack),(long)stack.getCount(),Math::addExact);return map;
        }
        /** Called only after a server-side recipe or installed-upgrade operation has validated its exact input and output. */
        void authorizeRecipe(List<ItemStack> consumed,List<ItemStack> produced) {
            if(expected!=null||used)throw new IllegalStateException("A recipe transformation may be authorized only once");
            Map<Key,Long> result=counts(before.values());
            for(ItemStack input:consumed)if(!input.isEmpty()) {
                Key key=new Key(input);long remaining=result.getOrDefault(key,0L)-input.getCount();
                if(remaining<0)throw new IllegalArgumentException("Recipe consumes absent input");
                if(remaining==0)result.remove(key);else result.put(key,remaining);
            }
            for(ItemStack output:produced)if(!output.isEmpty())result.merge(new Key(output),(long)output.getCount(),Math::addExact);
            expected=result;
        }
        public boolean conserved() { return (expected==null?counts(before.values()):expected).equals(counts(after.values())); }
        public String commit(MinecraftServer server) {
            if(!server.isSameThread()) return "Transaction refused off the server thread";
            if(committing) return "Overlapping transaction refused";
            if(!current()) return "Storage, permissions or configuration changed; preview again";
            if(!conserved()) return "Item conservation check failed; no changes made";
            for(Container c:after.keySet())for(int i=0;i<c.getContainerSize();i++) {
                ItemStack next=after.get(c)[i];
                if(!same(before.get(c)[i],next)&&!next.isEmpty()&&(!c.canPlaceItem(i,next)||next.getCount()>Math.min(c.getMaxStackSize(),next.getMaxStackSize()))) return "Invalid destination; no changes made";
            }
            used=true;committing=true;
            try {
                for(Container c:after.keySet()) {
                    for(int i=0;i<c.getContainerSize();i++)if(!same(before.get(c)[i],after.get(c)[i]))c.setItem(i,after.get(c)[i].copy());
                    c.setChanged();
                }
                for(Container c:after.keySet())for(int i=0;i<c.getContainerSize();i++)if(!same(c.getItem(i),after.get(c)[i]))throw new IllegalStateException("Container did not preserve the committed stack");
                return "";
            } catch(Throwable failure) {
                AnyaStorage.LOG.error("Storage transaction failed; restoring snapshots",failure);
                for(Container c:before.keySet()) try {
                    for(int i=0;i<c.getContainerSize();i++)c.setItem(i,before.get(c)[i].copy());c.setChanged();
                    for(int i=0;i<c.getContainerSize();i++)if(!same(c.getItem(i),before.get(c)[i]))throw new IllegalStateException("Rollback mismatch");
                } catch(Throwable rollback) { QUARANTINED.add(c);AnyaStorage.LOG.error("INVENTORY QUARANTINED: manual recovery required",rollback); }
                return "Transaction failed and rollback was attempted. Inspect server log before continuing";
            } finally { committing=false; }
        }
    }
    private Transactions() {}
}
