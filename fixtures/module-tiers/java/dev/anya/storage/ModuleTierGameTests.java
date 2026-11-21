package dev.anya.storage;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("anyamoduletest")
@PrefixGameTestTemplate(false)
public final class ModuleTierGameTests {
    private static ItemStack disc(String id) {
        return new ItemStack(Content.UPGRADES.get(id).get());
    }

    @GameTest(template="empty")
    public static void moduleTierTransactions(GameTestHelper h) {
        var player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"DiscTest"));
        var inventory=player.getInventory();
        inventory.setItem(0,new ItemStack(Content.BACKPACK.get()));
        var bag=new BagInventory(player,0);
        var namedDisc=disc("advanced_pickup");
        namedDisc.set(DataComponents.CUSTOM_NAME,Component.literal("Calibrated pickup"));
        inventory.setItem(9,namedDisc.copy());
        h.assertTrue(Upgrades.change(bag,"advanced_pickup",false),"install exact named pickup disc");
        bag=new BagInventory(player,0);
        var preview=BackpackAttachment.preview(player,bag.stack,disc("pickup"));
        h.assertTrue(preview.recognized()&&preview.output().isEmpty()&&preview.message().contains("tier"),"ground install rejects second family tier");
        String missing="#anyamoduletest:removed_from_pack";
        Upgrades.filter(bag,"PickupFilter",missing);
        var data=BagInventory.data(bag.stack);
        var filters=data.getList("PickupFilter",net.minecraft.nbt.Tag.TAG_STRING);
        h.assertTrue(filters.isEmpty(),"new missing tag refused");
        filters.add(net.minecraft.nbt.StringTag.valueOf(missing));
        data.put("PickupFilter",filters);BagInventory.data(bag.stack,data);
        Upgrades.filter(bag,"PickupFilter",missing);
        h.assertTrue(BagInventory.data(bag.stack).getList("PickupFilter",net.minecraft.nbt.Tag.TAG_STRING).isEmpty(),"stale saved tag removable");
        Upgrades.filter(bag,"PickupFilter","#minecraft:logs");
        Upgrades.filter(bag,"PickupFilter","minecraft:emerald");
        var id=BagInventory.data(bag.stack).getUUID("BagId");
        inventory.setItem(0,ItemStack.parseOptional(player.registryAccess(),(net.minecraft.nbt.CompoundTag)bag.stack.save(player.registryAccess())));
        bag=new BagInventory(player,0);
        h.assertTrue(id.equals(BagInventory.data(bag.stack).getUUID("BagId"))&&Upgrades.active(bag,"advanced_pickup"),"module and bag identity survive registry-aware reload");
        inventory.setItem(1,new ItemStack(Items.SPRUCE_LOG,6));
        inventory.setItem(10,new ItemStack(Items.OAK_LOG,4));
        inventory.setItem(11,new ItemStack(Items.EMERALD,3));
        inventory.setItem(12,new ItemStack(Items.DIRT,5));
        inventory.setItem(15,bag.stack.copy());
        Upgrades.pickup(bag);
        h.assertTrue(bag.isEmpty()&&inventory.getItem(10).getCount()==4,"duplicate bag identity blocks pickup");
        inventory.setItem(15,ItemStack.EMPTY);
        var gates=Config.get().upgrades;
        boolean original=gates.get("pickup");
        try {
            gates.put("pickup",false);
            Upgrades.pickup(bag);
            h.assertTrue(bag.isEmpty()&&inventory.getItem(10).getCount()==4,"family switch blocks advanced pickup");
        } finally { gates.put("pickup",original); }
        Upgrades.pickup(bag);
        h.assertTrue(bag.countItem(Items.OAK_LOG)==4&&bag.countItem(Items.EMERALD)==3&&inventory.getItem(10).isEmpty()&&inventory.getItem(11).isEmpty(),"reloaded tag and item filters transfer exact counts");
        h.assertTrue(inventory.getItem(1).getCount()==6&&inventory.getItem(12).getCount()==5,"protected and nonmatching stacks untouched");
        h.assertTrue(Upgrades.change(bag,"advanced_pickup",true),"remove reloaded disc");
        boolean returned=false;
        for(int slot=0;slot<36;slot++)if(Transactions.same(inventory.getItem(slot),namedDisc))returned=true;
        h.assertTrue(returned,"recover exact named module");

        bag=new BagInventory(player,0);
        inventory.setItem(9,disc("pickup"));
        h.assertTrue(Upgrades.change(bag,"pickup",false),"legacy pickup disc still installs");
        bag=new BagInventory(player,0);
        inventory.setItem(10,new ItemStack(Items.BIRCH_LOG,4));
        inventory.setItem(11,new ItemStack(Items.EMERALD,2));
        Upgrades.pickup(bag);
        h.assertTrue(inventory.getItem(10).getCount()==4&&bag.countItem(Items.EMERALD)==5,"basic pickup uses existing item rules only");
        Upgrades.filter(bag,"PickupFilter","minecraft:emerald");
        inventory.setItem(11,new ItemStack(Items.EMERALD,2));
        Upgrades.pickup(bag);
        h.assertTrue(inventory.getItem(11).getCount()==2&&bag.countItem(Items.EMERALD)==5,"saved tag-only criteria never fall back to collecting existing stacks on basic tier");
        h.assertTrue(Upgrades.change(bag,"pickup",true),"recover basic pickup");
        bag=new BagInventory(player,0);
        preview=BackpackAttachment.preview(player,bag.stack,disc("advanced_compacting"));
        h.assertTrue(preview.cost()==1&&!preview.output().isEmpty(),"ground attachment accepts advanced press");
        inventory.setItem(0,preview.output());
        bag=new BagInventory(player,0);
        bag.clearContent();
        bag.setItem(0,new ItemStack(Items.IRON_NUGGET,64));
        bag.setItem(1,new ItemStack(Items.IRON_NUGGET,26));
        bag.setItem(2,new ItemStack(Items.IRON_INGOT,1));
        var named=new ItemStack(Items.IRON_NUGGET,18);
        named.set(DataComponents.CUSTOM_NAME,Component.literal("Keepsake"));
        bag.setItem(3,named.copy());
        Upgrades.compact(bag);
        h.assertTrue(bag.countItem(Items.IRON_NUGGET)==36&&bag.countItem(Items.IRON_INGOT)==9&&bag.countItem(Items.IRON_BLOCK)==0,"eight batches, no recursive craft");
        h.assertTrue(Transactions.same(named,bag.getItem(3)),"modified input excluded");
        bag.clearContent();
        bag.setItem(0,new ItemStack(Items.IRON_NUGGET,30));
        bag.setItem(1,new ItemStack(Items.IRON_INGOT,63));
        for(int i=2;i<bag.getContainerSize();i++)bag.setItem(i,new ItemStack(Items.DIRT,64));
        Upgrades.compact(bag);
        h.assertTrue(bag.countItem(Items.IRON_NUGGET)==21&&bag.countItem(Items.IRON_INGOT)==64,"only one batch fits");
        Upgrades.compact(bag);
        h.assertTrue(bag.countItem(Items.IRON_NUGGET)==21&&bag.countItem(Items.IRON_INGOT)==64,"full destination leaves all materials intact");
        h.assertTrue(Upgrades.change(bag,"advanced_compacting",true),"remove press");
        bag=new BagInventory(player,0);
        bag.clearContent();
        inventory.setItem(9,disc("compacting"));
        h.assertTrue(Upgrades.change(bag,"compacting",false),"legacy press installs");
        bag=new BagInventory(player,0);
        bag.setItem(0,new ItemStack(Items.GOLD_NUGGET,36));
        Upgrades.compact(bag);
        h.assertTrue(bag.countItem(Items.GOLD_NUGGET)==27&&bag.countItem(Items.GOLD_INGOT)==1,"basic press remains single-batch");
        h.succeed();
    }
}
