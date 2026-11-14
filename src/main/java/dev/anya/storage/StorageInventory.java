package dev.anya.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.capabilities.Capabilities;
import java.util.*;

/** Stable inventory identity: expansion never replaces the object held by open menus. */
public final class StorageInventory implements Container {
    private final List<ItemStack> items=new ArrayList<>();
    private final Runnable changed;
    public final boolean backpack;
    public StorageInventory(int size,boolean backpack,Runnable changed) { this.backpack=backpack;this.changed=changed;expand(size); }
    public void expand(int size) { if(size<0||size>864) throw new IllegalArgumentException("Invalid inventory capacity"); while(items.size()<size)items.add(ItemStack.EMPTY); }
    @Override public int getContainerSize(){return items.size();}
    @Override public boolean isEmpty(){return items.stream().allMatch(ItemStack::isEmpty);}
    @Override public ItemStack getItem(int slot){return items.get(slot);}
    @Override public ItemStack removeItem(int slot,int count){ItemStack out=items.get(slot).split(count);if(!out.isEmpty())setChanged();return out;}
    @Override public ItemStack removeItemNoUpdate(int slot){return items.set(slot,ItemStack.EMPTY);}
    @Override public void setItem(int slot,ItemStack stack){
        if(stack.getCount()>Math.min(getMaxStackSize(),stack.getMaxStackSize())) throw new IllegalArgumentException("Over-sized stack rejected, not truncated");
        items.set(slot,stack);setChanged();
    }
    @Override public void setChanged(){changed.run();}
    @Override public boolean stillValid(Player p){return true;}
    @Override public void clearContent(){for(int i=0;i<items.size();i++)items.set(i,ItemStack.EMPTY);setChanged();}
    @Override public boolean canPlaceItem(int slot,ItemStack stack){return !backpack||safeInBackpack(stack);}
    public static boolean safeInBackpack(ItemStack s) {
        if(s.isEmpty())return true;
        if(s.getItem() instanceof BackpackItem || s.getItem() instanceof BundleItem) return false;
        if(s.has(DataComponents.CONTAINER)||s.has(DataComponents.BUNDLE_CONTENTS)||s.has(DataComponents.BLOCK_ENTITY_DATA)) return false;
        return s.getCapability(Capabilities.ItemHandler.ITEM)==null;
    }
    public CompoundTag save(HolderLookup.Provider lookup) {
        CompoundTag tag=new CompoundTag();tag.putInt("Size",items.size());ListTag list=new ListTag();
        for(int i=0;i<items.size();i++)if(!items.get(i).isEmpty()) {CompoundTag entry=new CompoundTag();entry.putInt("Slot",i);entry.put("Stack",items.get(i).save(lookup));list.add(entry);}
        tag.put("Items",list);return tag;
    }
    public void load(CompoundTag tag,HolderLookup.Provider lookup) {
        int n=tag.getInt("Size");if(n<0||n>864)throw new IllegalStateException("Invalid saved inventory size; refusing to discard contents");
        expand(n);Set<Integer> seen=new HashSet<>();ListTag list=tag.getList("Items",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++) {CompoundTag entry=list.getCompound(i);int slot=entry.getInt("Slot");
            if(slot<0||slot>=items.size()||!seen.add(slot))throw new IllegalStateException("Corrupt or duplicate saved inventory slot");
            ItemStack stack=ItemStack.parse(lookup,entry.get("Stack")).orElseThrow(()->new IllegalStateException("Unreadable stored item; restore its mod or inspect the backup"));
            if(stack.getCount()>stack.getMaxStackSize())throw new IllegalStateException("Oversized saved stack");items.set(slot,stack);
        }
    }
}
