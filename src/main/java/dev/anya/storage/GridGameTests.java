package dev.anya.storage;
import com.mojang.authlib.GameProfile;
import dev.anya.storage.ui.StorageGrid;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(AnyaStorage.ID) @PrefixGameTestTemplate(false)
public final class GridGameTests {
    private static FakePlayer player(GameTestHelper h,String name){return new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),name));}
    private static ItemStack bag(int capacity){ItemStack b=new ItemStack(Content.BACKPACK.get());BagInventory.initialize(b);var tag=BagInventory.data(b);tag.putInt("Capacity",capacity);BagInventory.data(b,tag);return b;}
    private static void capacity(GameTestHelper h,int count){
        FakePlayer p=player(h,"grid"+count);ItemStack stack=bag(count);p.getInventory().setItem(0,stack);var id=BagInventory.data(stack).getUUID("BagId");
        BagInventory inventory=new BagInventory(p,0);inventory.setItem(count-1,new ItemStack(Items.DIAMOND,7));StoreMenu menu=StoreMenu.bagMenu(81,p,inventory);p.containerMenu=menu;
        h.assertTrue(menu.capacity()==count&&menu.resultCount()==count&&menu.occupiedSlots()==1,"all real empty and occupied slots represented");
        int row=StorageGrid.maxStartRow(count,3);menu.action(new Packets.Action(menu.containerId,menu.session(),menu.revision(),"viewport","",(row<<3)|3));
        h.assertTrue(menu.firstSlot()==row*9&&menu.shownSlots()==count-row*9,"last row exact, no phantom cells");
        int displayIndex=count-1-menu.firstSlot();h.assertTrue(menu.getSlot(displayIndex).getItem().is(Items.DIAMOND),"last real slot maps to its displayed stack");
        h.runAfterDelay(2,()->{
            menu.action(new Packets.Action(menu.containerId,menu.session(),menu.revision(),"slot","",displayIndex));
            h.assertTrue(inventory.getItem(count-1).isEmpty()&&p.getInventory().countItem(Items.DIAMOND)==7,"actual intent withdraws correct physical slot exactly once");
            h.assertTrue(BagInventory.data(stack).getUUID("BagId").equals(id),"scroll and transfer preserve bag identity");
            p.containerMenu=p.inventoryMenu;h.succeed();
        });
    }
    @GameTest(template="empty") public static void capacity27(GameTestHelper h){capacity(h,27);}
    @GameTest(template="empty") public static void capacity45(GameTestHelper h){capacity(h,45);}
    @GameTest(template="empty") public static void capacity63(GameTestHelper h){capacity(h,63);}
    @GameTest(template="empty") public static void capacity81(GameTestHelper h){capacity(h,81);}
    @GameTest(template="empty") public static void capacity108(GameTestHelper h){capacity(h,108);}
    @GameTest(template="empty") public static void capacity144(GameTestHelper h){capacity(h,144);}
    @GameTest(template="empty") public static void capacity216(GameTestHelper h){capacity(h,216);}
    @GameTest(template="empty") public static void movedHostInvalidatesView(GameTestHelper h){
        FakePlayer p=player(h,"host-move");ItemStack b=bag(27);p.getInventory().setItem(0,b);BagInventory inventory=new BagInventory(p,0);inventory.setItem(2,new ItemStack(Items.EMERALD,6));StoreMenu menu=StoreMenu.bagMenu(82,p,inventory);
        p.getInventory().setItem(0,ItemStack.EMPTY);p.getInventory().setItem(1,b);h.assertTrue(!inventory.valid()&&!menu.stillValid(p),"moving a real host invalidates old view");h.assertTrue(new BagInventory(p,1).getItem(2).getCount()==6,"contents remain on the moved bag");h.succeed();
    }
    @GameTest(template="empty") public static void keyPriorityAndStaleRevision(GameTestHelper h){
        FakePlayer p=player(h,"priority");p.getInventory().setItem(0,bag(27));p.getInventory().setItem(4,bag(45));p.getInventory().selected=4;Profiles.of(p).wornBag=BagInventory.data(p.getInventory().getItem(0)).getUUID("BagId");
        h.assertTrue(BackpackAccess.priority(p).getFirst().playerSlot()==4,"main hand before inventory-selected bag");p.getInventory().selected=2;h.assertTrue(BackpackAccess.priority(p).getFirst().playerSlot()==0,"selected bag before inventory order");
        BagInventory b=new BagInventory(p,0);b.setItem(0,new ItemStack(Items.DIAMOND,3));StoreMenu m=StoreMenu.bagMenu(83,p,b);p.containerMenu=m;
        m.action(new Packets.Action(m.containerId,m.session(),m.revision()-1,"slot","",0));h.assertTrue(b.getItem(0).getCount()==3&&p.getInventory().countItem(Items.DIAMOND)==0,"stale display intent never transfers");p.containerMenu=p.inventoryMenu;Profiles.of(p).wornBag=null;h.succeed();
    }
    @GameTest(template="empty") public static void nativeAccessoriesContract(GameTestHelper h){
        if(!BackpackAccess.accessories()){h.assertTrue(!Boolean.getBoolean("anyastorage.requireAccessories"),"required Accessories dependency must actually load");h.succeed();return;}
        dev.anya.storage.compat.AccessoriesChecks.run(h);
    }
}
