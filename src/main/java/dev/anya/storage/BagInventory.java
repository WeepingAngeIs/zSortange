package dev.anya.storage;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import java.util.*;

/** Backpack contents live on the actual item and are written on every change, not just on menu close. */
public final class BagInventory implements Container {
    public final ServerPlayer player;
    public final int playerSlot;
    public final ItemStack stack;
    private final NonNullList<ItemStack> items;
    private ItemContainerContents observed;
    public BagInventory(ServerPlayer player,int slot) {
        this.player=player;this.playerSlot=slot;this.stack=player.getInventory().getItem(slot);
        if(!stack.is(Content.BACKPACK.get())||stack.getCount()!=1)throw new IllegalArgumentException("A single backpack is required");
        initialize(stack);CompoundTag data=data(stack);
        int size=data.getInt("Capacity");
        observed=stack.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY);
        long storedSize=observed.stream().count();
        if(size<9||size>216||storedSize>216)throw new IllegalArgumentException("Invalid backpack capacity; contents left untouched for recovery");
        size=Math.max(size,(int)storedSize);items=NonNullList.withSize(size,ItemStack.EMPTY);observed.copyInto(items);
    }
    public static CompoundTag data(ItemStack stack) { return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag(); }
    public static void data(ItemStack stack,CompoundTag tag) { stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag)); }
    public static void initialize(ItemStack stack) {
        CompoundTag tag=data(stack);boolean changed=false;
        if(!tag.hasUUID("BagId")){tag.putUUID("BagId",UUID.randomUUID());changed=true;}
        if(!tag.contains("Tier")){Config.Tier t=Config.get().tier("starter");tag.putString("Tier",t.id);tag.putString("TierName",t.name);tag.putInt("Capacity",t.slots);tag.putInt("UpgradeSlots",t.upgrades);changed=true;}
        if(changed)data(stack,tag);
    }
    public boolean valid() { return player.isAlive()&&!player.isSpectator()&&player.getInventory().getItem(playerSlot)==stack&&stack.getCount()==1&&Objects.equals(observed,stack.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY)); }
    public String name() { return data(stack).getString("TierName")+" Backpack"; }
    @Override public int getContainerSize(){return items.size();}
    @Override public boolean isEmpty(){return items.stream().allMatch(ItemStack::isEmpty);}
    @Override public ItemStack getItem(int slot){return items.get(slot);}
    @Override public ItemStack removeItem(int slot,int amount){ItemStack result=ContainerHelper.removeItem(items,slot,amount);if(!result.isEmpty())setChanged();return result;}
    @Override public ItemStack removeItemNoUpdate(int slot){ItemStack result=ContainerHelper.takeItem(items,slot);setChanged();return result;}
    @Override public void setItem(int slot,ItemStack item){items.set(slot,item);setChanged();}
    @Override public void setChanged(){observed=ItemContainerContents.fromItems(items);stack.set(DataComponents.CONTAINER,observed);player.getInventory().setChanged();}
    @Override public boolean stillValid(Player p){return p==player&&valid();}
    @Override public void clearContent(){items.replaceAll(s->ItemStack.EMPTY);setChanged();}
    @Override public boolean canPlaceItem(int slot,ItemStack item){return slot>=0&&slot<items.size()&&item.getItem().canFitInsideContainerItems()&&!item.is(Content.BACKPACK.get())&&!item.has(DataComponents.BUNDLE_CONTENTS)&&!item.has(DataComponents.BLOCK_ENTITY_DATA)&&item.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).equals(ItemContainerContents.EMPTY);}
}
