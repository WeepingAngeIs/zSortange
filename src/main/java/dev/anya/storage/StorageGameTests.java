package dev.anya.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@GameTestHolder(AnyaStorage.ID)
@PrefixGameTestTemplate(false)
public final class StorageGameTests {
    private static void ok(GameTestHelper h,boolean value,String message){h.assertTrue(value,message);}
    private static Transactions.Plan plan(Container... c){return new Transactions.Plan(List.of(c),()->true);}
    private static void commit(GameTestHelper h,Transactions.Plan plan){String error=plan.commit(h.getLevel().getServer());ok(h,error.isEmpty(),error);}
    @GameTest(template="empty") public static void partialCapacity(GameTestHelper h){
        SimpleContainer from=new SimpleContainer(new ItemStack(Items.DIAMOND,40)),to=new SimpleContainer(new ItemStack(Items.DIAMOND,60));
        Transactions.Plan p=plan(from,to);ok(h,p.move(from,0,List.of(to),40)==4,"Only accepted quantity can leave source");commit(h,p);ok(h,from.getItem(0).getCount()==36&&to.getItem(0).getCount()==64,"Partial transfer counts");h.succeed();
    }
    @GameTest(template="empty") public static void fullDestination(GameTestHelper h){
        SimpleContainer from=new SimpleContainer(new ItemStack(Items.DIAMOND,40)),to=new SimpleContainer(new ItemStack(Items.DIAMOND,64));
        Transactions.Plan p=plan(from,to);ok(h,p.move(from,0,List.of(to),40)==0,"Full destination accepts nothing");commit(h,p);ok(h,from.getItem(0).getCount()==40,"Source unchanged");h.succeed();
    }
    @GameTest(template="empty") public static void preserveComponents(GameTestHelper h){
        ItemStack item=new ItemStack(Items.DIAMOND_SWORD);item.set(DataComponents.CUSTOM_NAME,Component.literal("Anya's exact sword"));item.setDamageValue(123);
        SimpleContainer from=new SimpleContainer(item),to=new SimpleContainer(1);Transactions.Plan p=plan(from,to);p.move(from,0,List.of(to),1);commit(h,p);ok(h,Transactions.same(item,to.getItem(0)),"Name and durability preserved");h.succeed();
    }
    @GameTest(template="empty") public static void noComponentMerging(GameTestHelper h){
        ItemStack named=new ItemStack(Items.DIAMOND,10);named.set(DataComponents.CUSTOM_NAME,Component.literal("Named"));
        SimpleContainer from=new SimpleContainer(named),to=new SimpleContainer(new ItemStack(Items.DIAMOND,10));Transactions.Plan p=plan(from,to);ok(h,p.move(from,0,List.of(to),10)==0,"Different components must not merge");h.succeed();
    }
    @GameTest(template="empty") public static void staleSource(GameTestHelper h){
        SimpleContainer from=new SimpleContainer(new ItemStack(Items.DIAMOND,40)),to=new SimpleContainer(1);Transactions.Plan p=plan(from,to);p.move(from,0,List.of(to),40);from.setItem(0,new ItemStack(Items.DIAMOND,39));ok(h,!p.commit(h.getLevel().getServer()).isEmpty(),"Reject stale source");ok(h,to.isEmpty()&&from.getItem(0).getCount()==39,"No stale write");h.succeed();
    }
    @GameTest(template="empty") public static void staleDestination(GameTestHelper h){
        SimpleContainer from=new SimpleContainer(new ItemStack(Items.DIAMOND,40)),to=new SimpleContainer(1);Transactions.Plan p=plan(from,to);p.move(from,0,List.of(to),40);to.setItem(0,new ItemStack(Items.EMERALD));ok(h,!p.commit(h.getLevel().getServer()).isEmpty(),"Reject stale target");ok(h,to.getItem(0).is(Items.EMERALD)&&from.getItem(0).getCount()==40,"Preserve concurrent edit");h.succeed();
    }
    @GameTest(template="empty") public static void revokedPermission(GameTestHelper h){
        AtomicBoolean permission=new AtomicBoolean(true);SimpleContainer from=new SimpleContainer(new ItemStack(Items.DIAMOND,40)),to=new SimpleContainer(1);Transactions.Plan p=new Transactions.Plan(List.of(from,to),permission::get);p.move(from,0,List.of(to),40);permission.set(false);ok(h,!p.commit(h.getLevel().getServer()).isEmpty(),"Recheck permission at commit");ok(h,from.getItem(0).getCount()==40&&to.isEmpty(),"No denied transfer");h.succeed();
    }
    @GameTest(template="empty") public static void replayRejected(GameTestHelper h){
        SimpleContainer from=new SimpleContainer(new ItemStack(Items.DIAMOND)),to=new SimpleContainer(1);Transactions.Plan p=plan(from,to);p.move(from,0,List.of(to),1);commit(h,p);ok(h,!p.commit(h.getLevel().getServer()).isEmpty(),"A plan commits once only");ok(h,to.getItem(0).getCount()==1,"Replay did not duplicate");h.succeed();
    }
    @GameTest(template="empty") public static void conservationGate(GameTestHelper h){
        SimpleContainer c=new SimpleContainer(new ItemStack(Items.DIAMOND));Transactions.Plan p=plan(c);p.work(c)[0].grow(1);ok(h,!p.commit(h.getLevel().getServer()).isEmpty(),"Unexplained creation is rejected");ok(h,c.getItem(0).getCount()==1,"Original retained");h.succeed();
    }
    @GameTest(template="empty") public static void validatedTransformation(GameTestHelper h){
        SimpleContainer c=new SimpleContainer(new ItemStack(Items.IRON_INGOT,9));Transactions.Plan p=plan(c);p.work(c)[0]=new ItemStack(Items.IRON_BLOCK);p.authorizeRecipe(List.of(new ItemStack(Items.IRON_INGOT,9)),List.of(new ItemStack(Items.IRON_BLOCK)));commit(h,p);ok(h,c.getItem(0).is(Items.IRON_BLOCK),"Exact declared recipe transformation");h.succeed();
    }
    @GameTest(template="empty") public static void faultyContainerRollback(GameTestHelper h){
        SimpleContainer from=new SimpleContainer(new ItemStack(Items.DIAMOND,17));
        class FailOnce extends SimpleContainer {boolean armed=true;FailOnce(){super(1);}@Override public void setItem(int slot,ItemStack stack){if(armed&&!stack.isEmpty()){armed=false;throw new IllegalStateException("Intentional GameTest fault");}super.setItem(slot,stack);}}
        FailOnce to=new FailOnce();Transactions.Plan p=plan(from,to);p.move(from,0,List.of(to),17);ok(h,!p.commit(h.getLevel().getServer()).isEmpty(),"Injected failure reported");ok(h,from.getItem(0).getCount()==17&&to.isEmpty(),"Rollback restored both inventories");h.succeed();
    }
    @GameTest(template="empty") public static void restrictiveContainer(GameTestHelper h){
        SimpleContainer from=new SimpleContainer(new ItemStack(Items.DIAMOND));SimpleContainer to=new SimpleContainer(1){@Override public boolean canPlaceItem(int slot,ItemStack item){return false;}};Transactions.Plan p=plan(from,to);ok(h,p.move(from,0,List.of(to),1)==0,"Container filter honored");commit(h,p);ok(h,to.isEmpty(),"Rejected destination remains empty");h.succeed();
    }
    @GameTest(template="empty") public static void exactRangeMetric(GameTestHelper h){
        ok(h,Network.within(BlockPos.ZERO,new BlockPos(3,4,0),5),"Euclidean boundary included");ok(h,!Network.within(BlockPos.ZERO,new BlockPos(4,4,0),5),"Diagonal outside radius excluded");h.succeed();
    }
    @GameTest(template="empty") public static void backpackRecipePreservesContents(GameTestHelper h){
        ItemStack backpack=new ItemStack(Content.BACKPACK.get());BagInventory.initialize(backpack);net.minecraft.core.NonNullList<ItemStack> content=net.minecraft.core.NonNullList.withSize(27,ItemStack.EMPTY);content.set(0,new ItemStack(Items.EMERALD,37));backpack.set(DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(content));
        List<ItemStack> grid=new ArrayList<>();grid.add(backpack);for(int i=0;i<8;i++)grid.add(new ItemStack(Items.IRON_INGOT));CraftingInput input=CraftingInput.of(3,3,grid);UpgradeRecipe recipe=new UpgradeRecipe(CraftingBookCategory.MISC);ok(h,recipe.matches(input,h.getLevel()),"Starter to iron recipe");ItemStack output=recipe.assemble(input,h.getLevel().registryAccess());ok(h,BagInventory.data(output).getInt("Capacity")==45,"Iron capacity");ok(h,output.get(DataComponents.CONTAINER).equals(backpack.get(DataComponents.CONTAINER)),"All stored contents preserved");ok(h,BagInventory.data(output).getUUID("BagId").equals(BagInventory.data(backpack).getUUID("BagId")),"Identity retained across upgrade");ok(h,BagInventory.data(backpack).getInt("Capacity")==27,"Crafting preview did not modify input");h.succeed();
    }
    @GameTest(template="empty") public static void missingMaterialsFailClosed(GameTestHelper h){
        Config.Data d=Config.copy(Config.get());Config.Tier tier=new Config.Tier("missing","Missing","netherite","definitely_missing:ingot",144,6,8,true);d.tiers.add(tier);d.compatibility=true;ok(h,Config.unavailable(d,tier).startsWith("Missing material"),"Absent mods never create material recipes");h.succeed();
    }
    @GameTest(template="empty") public static void endgameDefaultOff(GameTestHelper h){
        List<ItemStack> grid=new ArrayList<>();grid.add(new ItemStack(Content.CORE.get()));for(int i=0;i<4;i++)grid.add(new ItemStack(Items.NETHER_STAR));for(int i=0;i<4;i++)grid.add(new ItemStack(Items.DRAGON_BREATH));ok(h,!new CubeRecipe(CraftingBookCategory.MISC).matches(CraftingInput.of(3,3,grid),h.getLevel()),"Undecided cube recipe remains off");h.succeed();
    }
    @GameTest(template="empty") public static void ownershipAndAutomation(GameTestHelper h){
        StorageEntity be=new StorageEntity(h.absolutePos(new BlockPos(1,1,1)),Content.CRATE.get().defaultBlockState());ok(h,be.getContainerSize()==54,"Chest capacity");ok(h,be.getSlotsForFace(net.minecraft.core.Direction.UP).length==0,"Unowned hopper access is disabled");ok(h,!be.canTakeItemThroughFace(0,new ItemStack(Items.DIAMOND),net.minecraft.core.Direction.DOWN),"Automation cannot bypass permissions");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200) public static void randomizedConservation(GameTestHelper h){
        Random random=new Random(0xA11A);SimpleContainer a=new SimpleContainer(9),b=new SimpleContainer(9);int total=0;for(int i=0;i<9;i++){int count=1+random.nextInt(64);a.setItem(i,new ItemStack(Items.DIAMOND,count));total+=count;}
        for(int i=0;i<500;i++){SimpleContainer from=random.nextBoolean()?a:b,to=from==a?b:a;Transactions.Plan p=plan(a,b);p.move(from,random.nextInt(9),List.of(to),1+random.nextInt(64));commit(h,p);int observed=0;for(int j=0;j<9;j++)observed+=a.getItem(j).getCount()+b.getItem(j).getCount();ok(h,observed==total,"Conservation after randomized transfer "+i);}
        h.succeed();
    }
}
