package dev.anya.storage.compat;
import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import dev.anya.storage.appearance.*;
import io.wispforest.accessories.api.*;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.util.FakePlayer;
import java.util.*;

/** Called only by the optional-dependency GameTest using the real published Accessories binary. */
public final class AccessoriesChecks {
    public static void run(GameTestHelper h){
        FakePlayer p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"native-back"));var cap=AccessoriesCapability.get(p);
        h.assertTrue(cap!=null,"actual Accessories capability exists");var back=cap.getContainers().get("back");h.assertTrue(back!=null&&back.getAccessories().getContainerSize()>0,"native player back slot exists");
        var inventory=back.getAccessories();var reference=SlotReference.of(p,"back",0);
        for(int capacity:new int[]{27,45,63,81,108,144,216}){
            ItemStack b=new ItemStack(Content.BACKPACK.get());BagInventory.initialize(b);Looks.apply(b,"stone",true);var tag=BagInventory.data(b);tag.putInt("Capacity",capacity);BagInventory.data(b,tag);
            h.assertTrue(AccessoriesAPI.canInsertIntoSlot(b,reference),"starter and capacity "+capacity+" pass native slot insertion API");
            inventory.setItem(0,b);Wearables.refresh(p);
            h.assertTrue(BackpackAccess.equipped(p).size()==1&&Wearables.selected(p)==b,"equipped source is the actual stack, not a copy");
            BagInventory contents=BackpackAccess.priority(p).getFirst().open(p);contents.setItem(capacity-1,new ItemStack(Items.DIAMOND,9));
            h.assertTrue(contents.valid()&&contents.getContainerSize()==capacity,"live external backing valid");
            h.assertTrue(p.getAttribute(Attributes.ARMOR).getModifier(Wearables.ARMOR).amount()==2,"single stone bonus in native slot");Wearables.refresh(p);
            h.assertTrue(p.getAttribute(Attributes.ARMOR).getModifier(Wearables.ARMOR).amount()==2,"repeated native refresh cannot stack");
            var reloaded=BackpackAccess.equipped(p).getFirst().open(p);h.assertTrue(reloaded.getItem(capacity-1).getCount()==9,"content write persisted to the equipped real item");
            p.getInventory().setItem(0,b.copy());Wearables.refresh(p);h.assertTrue(p.getAttribute(Attributes.ARMOR).getModifier(Wearables.ARMOR)==null&&!contents.valid(),"duplicate identity fails closed across storage locations");p.getInventory().clearContent();
            inventory.setItem(0,ItemStack.EMPTY);Wearables.refresh(p);
            h.assertTrue(!contents.valid()&&p.getAttribute(Attributes.ARMOR).getModifier(Wearables.ARMOR)==null,"unequip invalidates contents view and removes bonus");
        }
        ItemStack b=new ItemStack(Content.BACKPACK.get());h.assertTrue(!AccessoriesAPI.getOrDefaultAccessory(b).canEquipFromUse(b),"right-click crafting/use path is not replaced by auto-equip");
        AnyaStorage.LOG.info("ANYA_ACCESSORIES_CONTRACT_OK: real Accessories native back slot; all seven capacities, effects, persistence and removal");h.succeed();
    }
    private AccessoriesChecks(){}
}
