package dev.anya.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@GameTestHolder(AnyaStorage.ID)
@PrefixGameTestTemplate(false)
public final class StorageGameTests {
    private static void check(boolean value,String message){if(!value)throw new GameTestAssertException(message);}
    @GameTest(template="empty") public static void partialCapacityAndReplay(GameTestHelper h){
        SimpleContainer a=new SimpleContainer(1),b=new SimpleContainer(1);a.setItem(0,new ItemStack(Items.DIAMOND,64));b.setItem(0,new ItemStack(Items.DIAMOND,60));
        StorageOps.Plan p=new StorageOps.Plan();check(p.move(a,0,b,64)==4,"only accepted capacity planned");check(p.commit(),"transfer committed");
        check(a.getItem(0).getCount()==60&&b.getItem(0).getCount()==64,"item conservation");check(!p.commit(),"replay rejected");h.succeed();
    }
    @GameTest(template="empty") public static void componentIdentityPreserved(GameTestHelper h){
        SimpleContainer a=new SimpleContainer(1),b=new SimpleContainer(2);ItemStack named=new ItemStack(Items.DIAMOND,32);named.set(DataComponents.CUSTOM_NAME,Component.literal("Keep my name"));a.setItem(0,named.copy());b.setItem(0,new ItemStack(Items.DIAMOND,63));
        StorageOps.Plan p=new StorageOps.Plan();check(p.move(a,0,b,32)==32,"named stack fits empty slot");check(p.commit(),"component transfer committed");
        check(b.getItem(0).getCount()==63,"unnamed stack was not merged");check(ItemStack.isSameItemSameComponents(named,b.getItem(1)),"name preserved");h.succeed();
    }
    @GameTest(template="empty") public static void staleSnapshotRejected(GameTestHelper h){
        SimpleContainer a=new SimpleContainer(1),b=new SimpleContainer(1);a.setItem(0,new ItemStack(Items.IRON_INGOT,8));StorageOps.Plan p=new StorageOps.Plan();p.move(a,0,b,8);a.getItem(0).shrink(1);
        check(!p.commit(),"stale source rejected");check(a.getItem(0).getCount()==7&&b.isEmpty(),"concurrent change retained without copying");h.succeed();
    }
    @GameTest(template="empty") public static void permissionChangeRejected(GameTestHelper h){
        SimpleContainer a=new SimpleContainer(1),b=new SimpleContainer(1);a.setItem(0,new ItemStack(Items.GOLD_INGOT,5));AtomicBoolean allowed=new AtomicBoolean(true);StorageOps.Plan p=new StorageOps.Plan().guard(allowed::get);p.move(a,0,b,5);allowed.set(false);
        check(!p.commit(),"changed permission rejected");check(a.getItem(0).getCount()==5&&b.isEmpty(),"no movement after permission change");h.succeed();
    }
    @GameTest(template="empty") public static void failedSetterRollsBack(GameTestHelper h){
        SimpleContainer a=new SimpleContainer(1);class Failing extends SimpleContainer {boolean armed=true;Failing(){super(1);}@Override public void setItem(int slot,ItemStack stack){if(armed){armed=false;throw new IllegalStateException("Expected test failure");}super.setItem(slot,stack);}}
        Failing b=new Failing();a.setItem(0,new ItemStack(Items.EMERALD,12));StorageOps.Plan p=new StorageOps.Plan();p.move(a,0,b,12);
        check(!p.commit(),"failed destination did not commit");check(a.getItem(0).getCount()==12&&b.isEmpty(),"source and destination restored");h.succeed();
    }
    @GameTest(template="empty") public static void forgedConservationRejected(GameTestHelper h){
        SimpleContainer a=new SimpleContainer(1);a.setItem(0,new ItemStack(Items.DIAMOND,1));StorageOps.Plan p=new StorageOps.Plan();p.state(a)[0].grow(1);boolean rejected=false;
        try{p.commit();}catch(IllegalStateException expected){rejected=true;}check(rejected,"non-conserving plan rejected");check(a.getItem(0).getCount()==1,"original stack untouched");h.succeed();
    }
    @GameTest(template="empty") public static void backpackIdentityAndPersistence(GameTestHelper h){
        StorageData data=StorageData.get(h.getLevel().getServer());ItemStack item=new ItemStack(AnyaStorage.BACKPACK.get());StorageData.Bag bag=data.bag(item);UUID id=StorageData.bagId(item);
        ItemStack contents=new ItemStack(Items.NETHERITE_PICKAXE);contents.setDamageValue(17);contents.set(DataComponents.CUSTOM_NAME,Component.literal("Persistent tool"));bag.inventory.setItem(3,contents);
        check(data.bag(item.copy())==bag,"creative alias shares one world inventory");CompoundTag saved=data.save(new CompoundTag(),h.getLevel().registryAccess());StorageData restored=StorageData.load(saved,h.getLevel().registryAccess());
        check(restored.bags.containsKey(id),"backpack UUID persisted");check(StorageOps.same(contents,restored.bags.get(id).inventory.getItem(3)),"damage and name persisted");h.succeed();
    }
    @GameTest(template="empty") public static void nestedStorageRejected(GameTestHelper h){
        StorageInventory bag=new StorageInventory(27,true,()->{});check(!bag.canPlaceItem(0,new ItemStack(AnyaStorage.BACKPACK.get())),"backpack nesting forbidden");
        ItemStack container=new ItemStack(Items.CHEST);container.set(DataComponents.BLOCK_ENTITY_DATA,net.minecraft.world.item.component.CustomData.of(new CompoundTag()));check(!bag.canPlaceItem(0,container),"block-entity payload nesting forbidden");
        check(bag.canPlaceItem(0,new ItemStack(Items.DIAMOND)),"ordinary material allowed");h.succeed();
    }
    @GameTest(template="empty") public static void backpackRecipePreservesIdentity(GameTestHelper h){
        Rules.Config old=Rules.current;try{Rules.current=new Rules.Config();ItemStack original=new ItemStack(AnyaStorage.BACKPACK.get());StorageData data=StorageData.get(h.getLevel().getServer());data.bag(original).inventory.setItem(0,new ItemStack(Items.EMERALD,7));original.set(DataComponents.CUSTOM_NAME,Component.literal("My bag"));
            List<ItemStack> grid=new ArrayList<>();grid.add(original);for(int i=0;i<8;i++)grid.add(new ItemStack(Items.IRON_INGOT));CraftingInput input=CraftingInput.of(3,3,grid);StorageRecipe recipe=new StorageRecipe(CraftingBookCategory.MISC);
            check(recipe.matches(input,h.getLevel()),"iron progression recipe matches");ItemStack result=recipe.assemble(input,h.getLevel().registryAccess());check(StorageData.bagId(result).equals(StorageData.bagId(original)),"backpack UUID preserved");check(result.getHoverName().equals(original.getHoverName()),"name preserved");check(StorageData.itemTier(result).equals("iron"),"tier upgraded");
            check(data.bag(result).inventory.getContainerSize()==45&&data.bag(result).inventory.getItem(0).getCount()==7,"existing contents retained during expansion");
        }finally{Rules.current=old;}h.succeed();
    }
    @GameTest(template="empty") public static void optionalMaterialIsNotRequired(GameTestHelper h){
        Rules.Config c=new Rules.Config();c.compatibility=true;Rules.Tier t=new Rules.Tier("missing","Absent optional tier","netherite","absent_test_mod:ingot",126,5,8,true);c.tiers.add(t);c.materials.put(t.material,true);Rules.validate(c);
        check(Rules.unavailable(c,t).startsWith("Missing material"),"missing optional item gives explicit reason");check(Rules.unavailable(c,c.tier("iron")).isEmpty(),"vanilla progression unaffected");h.succeed();
    }
    @GameTest(template="empty") public static void coreRecipeAndEndgameGate(GameTestHelper h){
        Rules.Config c=new Rules.Config();var defs=StorageRecipe.definitions(c);var core=defs.stream().filter(d->d.id().equals("storage_core")).findFirst().orElseThrow();
        check(core.blocked().isEmpty(),"storage core is craftable with default upgrade settings");check(!defs.stream().filter(d->d.id().equals("uwu_cube")).findFirst().orElseThrow().blocked().isEmpty(),"undecided endgame remains uncraftable");
        check(defs.stream().noneMatch(d->d.output().contains("dragonsteel")||d.output().contains("witherite")),"no shortcut ingot manufacturing recipes");h.succeed();
    }
    @GameTest(template="empty") public static void occupiedTierReductionRejected(GameTestHelper h){
        StorageData data=StorageData.get(h.getLevel().getServer());ItemStack item=new ItemStack(AnyaStorage.BACKPACK.get());CompoundTag tag=StorageData.metadata(item);tag.putString("AnyaTier","iron");StorageData.metadata(item,tag);StorageData.Bag bag=data.bag(item);bag.inventory.setItem(40,new ItemStack(Items.DIAMOND));Rules.Config reduced=Rules.current.copy();reduced.tier("iron").slots=27;boolean rejected=false;
        try{data.validateTierChange(reduced);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"occupied out-of-range slot blocks reduction");check(!bag.inventory.getItem(40).isEmpty(),"item retained");h.succeed();
    }
    @GameTest(template="empty") public static void physicalContainerPersistence(GameTestHelper h){
        BlockPos relative=new BlockPos(1,1,1);h.setBlock(relative,AnyaStorage.CRATE.get());StorageBlockEntity original=(StorageBlockEntity)h.getBlockEntity(relative);original.owner=UUID.randomUUID();original.label=5;original.name="Persistent base";original.inventory.setItem(10,new ItemStack(Items.DIAMOND,11));
        CompoundTag saved=new CompoundTag();original.saveAdditional(saved,h.getLevel().registryAccess());StorageBlockEntity loaded=new StorageBlockEntity(h.absolutePos(relative),AnyaStorage.CRATE.get().defaultBlockState());loaded.loadAdditional(saved,h.getLevel().registryAccess());
        check(original.identity.equals(loaded.identity)&&original.owner.equals(loaded.owner),"container identity and ownership saved");check(loaded.label==5&&loaded.inventory.getItem(10).getCount()==11,"label and contents saved");h.succeed();
    }
}
