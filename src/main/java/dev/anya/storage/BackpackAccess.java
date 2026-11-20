package dev.anya.storage;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Resolves actual owned stacks, never temporary copies or another player's inventory. */
public final class BackpackAccess {
    public static final int GROUND_SLOT = -2;
    public record Source(Container inventory,int slot,int playerSlot,BooleanSupplier attached) {
        public ItemStack stack(){return attached.getAsBoolean()&&slot>=0&&slot<inventory.getContainerSize()?inventory.getItem(slot):ItemStack.EMPTY;}
        public BagInventory open(ServerPlayer player){return new BagInventory(player,this);}
    }
    public static boolean accessories(){return ModList.get().isLoaded("accessories");}
    public static List<Source> equipped(ServerPlayer player){
        if(!accessories())return List.of();
        return dev.anya.storage.compat.AccessoriesBridge.equipped(player);
    }
    public static Source inventory(ServerPlayer player,int slot){return new Source(player.getInventory(),slot,slot,()->true);}
    public static boolean isBag(ItemStack stack){return stack.is(Content.BACKPACK.get())&&stack.getCount()==1;}
    public static List<Source> all(ServerPlayer player){
        List<Source> result=new ArrayList<>(equipped(player));
        for(int i=0;i<36;i++){Source s=inventory(player,i);if(isBag(s.stack()))result.add(s);}
        Source off=inventory(player,40);if(isBag(off.stack()))result.add(off);
        return result;
    }
    public static boolean unique(ServerPlayer player,ItemStack stack){
        return carriedCopies(player,stack,1);
    }
    static boolean carriedCopies(ServerPlayer player,ItemStack stack,int expected){
        var data=BagInventory.data(stack);if(!data.hasUUID("BagId"))return true;
        UUID id=data.getUUID("BagId");int copies=0;
        for(Source source:all(player)){var tag=BagInventory.data(source.stack());if(tag.hasUUID("BagId")&&id.equals(tag.getUUID("BagId")))copies++;}
        return copies==expected;
    }
    /** Equipped native back slot, main hand, explicitly selected inventory bag, inventory order, offhand. */
    public static List<Source> priority(ServerPlayer player){
        List<Source> all=all(player),ordered=new ArrayList<>();
        for(Source s:all)if(s.playerSlot()<0)ordered.add(s);
        for(Source s:all)if(s.playerSlot()==player.getInventory().selected)ordered.add(s);
        UUID worn=Profiles.of(player).wornBag;
        for(Source s:all){var d=BagInventory.data(s.stack());if(worn!=null&&d.hasUUID("BagId")&&worn.equals(d.getUUID("BagId"))&&!ordered.contains(s))ordered.add(s);}
        for(Source s:all)if(!ordered.contains(s))ordered.add(s);
        return ordered;
    }
    private BackpackAccess(){}
}
