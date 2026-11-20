package dev.anya.storage.compat;

import dev.anya.storage.*;
import dev.anya.storage.appearance.Wearables;
import io.wispforest.accessories.api.*;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Optional native Accessories 1.1.0-beta.53 adapter. No Curios wrapper or cosmetic inventory traversal. */
public final class AccessoriesBridge {
    public static void register(){
        AccessoriesAPI.registerAccessory(Content.BACKPACK.get(),new Accessory(){
            @Override public boolean canEquip(ItemStack stack,SlotReference slot){return "back".equals(slot.slotName())&&BackpackAccess.isBag(stack);}
            @Override public boolean canEquipFromUse(ItemStack stack){return false;}
            @Override public void onEquip(ItemStack stack,SlotReference slot){if(slot.entity() instanceof ServerPlayer p){BagInventory.initialize(stack);Wearables.refresh(p);}}
            @Override public void onUnequip(ItemStack stack,SlotReference slot){if(slot.entity() instanceof ServerPlayer p)Wearables.strip(p);}
        });
    }
    public static List<BackpackAccess.Source> equipped(ServerPlayer player){
        var capability=AccessoriesCapability.get(player);if(capability==null)return List.of();
        var container=capability.getContainers().get("back");if(container==null)return List.of();
        var inventory=container.getAccessories();List<BackpackAccess.Source> result=new ArrayList<>();
        for(int i=0;i<Math.min(inventory.getContainerSize(),64);i++){
            final int slot=i;
            if(BackpackAccess.isBag(inventory.getItem(i)))result.add(new BackpackAccess.Source(inventory,i,-1,()->{
                var cap=AccessoriesCapability.get(player);if(cap==null)return false;
                var current=cap.getContainers().get("back");return current!=null&&current.getAccessories()==inventory&&slot<inventory.getContainerSize();
            }));
        }
        return result;
    }
    private AccessoriesBridge(){}
}
