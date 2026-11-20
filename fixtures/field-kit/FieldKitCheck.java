package dev.anya.storage;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.OfflineRegistries;

public final class FieldKitCheck {
    private static ItemStack berries(int n){return new ItemStack(Items.SWEET_BERRIES,n);}
    private static ItemStack named(int n){ItemStack s=berries(n);s.set(DataComponents.CUSTOM_NAME,Component.literal("Prize"));return s;}
    private static void check(boolean pass,String name){if(!pass)throw new AssertionError(name);System.out.println("PASS "+name);}
    // BagInventory only needs the owner's inventory dirty notification for these tests.
    // Construct an inert player shell so no server, connection, dimension or world is started.
    private static net.minecraft.server.level.ServerPlayer inventoryOwner() throws ReflectiveOperationException {
        Class<?> allocator=Class.forName("sun.misc.Unsafe");
        var instance=allocator.getDeclaredField("theUnsafe");instance.setAccessible(true);
        var p=(net.minecraft.server.level.ServerPlayer)allocator.getMethod("allocateInstance",Class.class)
                .invoke(instance.get(null),net.minecraft.server.level.ServerPlayer.class);
        var field=net.minecraft.world.entity.player.Player.class.getDeclaredField("inventory");
        field.setAccessible(true);field.set(p,new net.minecraft.world.entity.player.Inventory(p));return p;
    }
    public static void main(String[] args) throws ReflectiveOperationException {
        var registry=OfflineRegistries.setup(Path.of(args[0]));
        var state=new Profiles.PlayerState();
        var player=new SimpleContainer(36);
        player.setItem(0,berries(6));player.setItem(1,berries(2));player.setItem(2,named(2));
        var pack=FieldKit.capture(player,"  Forest outing  ",0,registry);
        state.fieldKit.kits[0]=pack;
        state.fieldKit.keep("minecraft:sweet_berries",12);
        var tag=new CompoundTag();state.fieldKit.write(tag,registry);
        var reopened=new Profiles.PlayerState();reopened.fieldKit.read(tag,registry);
        check(reopened.fieldKit.keep.equals(state.fieldKit.keep)&&reopened.fieldKit.kits[0].name().equals("Forest outing")
                &&reopened.fieldKit.kits[0].goals().size()==3,"named kit, duplicate goals and keep rule survive NBT");
        state=new Profiles.PlayerState();state.fieldKit.read(new CompoundTag(),registry);
        check(state.fieldKit.keep.isEmpty()&&state.fieldKit.kits[0]==null,"existing profile with no fields");
        var chest=new SimpleContainer(4);
        chest.setItem(0,berries(32));chest.setItem(1,named(3));
        player.clearContent();player.setItem(0,berries(3));player.setItem(9,named(1));
        var put=new Transactions.Plan(List.of(chest,player),()->true);
        var packed=FieldKit.restock(put,player,List.of(chest),pack);
        check(packed.moved()==6&&packed.missing()==0&&put.commitOnServerThread().isEmpty()
                &&chest.getItem(0).getCount()==27&&chest.getItem(1).getCount()==2,"kit tops up totals and exact components");
        var second=new Transactions.Plan(List.of(chest,player),()->true);
        check(FieldKit.restock(second,player,List.of(chest),pack).moved()==0,"already stocked kit moves zero");
        player.clearContent();chest.clearContent();chest.setItem(0,berries(62));
        player.setItem(0,berries(3));player.setItem(9,berries(15));
        state.fieldKit.keep("minecraft:sweet_berries",12);
        var small=new StorageEntity(net.minecraft.core.BlockPos.ZERO,Content.CRATE.get().defaultBlockState());
        for(int i=1;i<small.getContainerSize();i++)small.setItem(i,new ItemStack(Items.STONE,64));
        small.setItem(0,berries(62));
        var sweep=new Transactions.Plan(List.of(small,player),()->true);
        var result=FieldKit.sweep(sweep,player,state,s->List.of(small));
        check(result.protectedItems()==3&&result.kept()==9&&result.moved()==2&&result.full()==4&&result.leftMask()==(1L<<9)
                &&sweep.commitOnServerThread().isEmpty()&&player.getItem(9).getCount()==13,"partial transfer marks only remainder, locked slots count first");
        var receipt=new FieldKit.Receipt(sweep,List.of(small),"room A",100);
        check(!receipt.available("room B",100)&&!receipt.available("room A",1301)&&receipt.available("room A",101),"undo bound to storage scope and lifetime");

        var friend=new SimpleContainer(36);
        var takeOne=new Transactions.Plan(List.of(small,friend),()->true);
        check(takeOne.move(small,0,List.of(friend),1)==1&&takeOne.commitOnServerThread().isEmpty(),"another player takes one of the deposited items");
        var replaceOne=new Transactions.Plan(List.of(small,friend),()->true);
        check(replaceOne.move(friend,0,List.of(small),1)==1&&replaceOne.commitOnServerThread().isEmpty(),"friend returns an identical item");
        var back=new Transactions.Plan(List.of(small,player),()->true);
        var undone=receipt.undo(back,player,List.of(small));
        check(undone.moved()==1&&undone.missing()==1&&back.commitOnServerThread().isEmpty()&&small.getItem(0).getCount()==63,"replacement does not replenish earlier owner's undo");
        // Simulate a vanilla/external replacement with identical type, components, and count.
        small.setItem(0,berries(60));player.setItem(12,berries(3));
        var fresh=new Transactions.Plan(List.of(small,player),()->true);
        check(fresh.move(player,12,List.of(small),3)==3&&fresh.commitOnServerThread().isEmpty(),"fresh physical dump");
        var replaced=new FieldKit.Receipt(fresh,List.of(small),"room A",101);
        small.setItem(0,small.getItem(0).copy());
        var denied=new Transactions.Plan(List.of(small,player),()->true);
        check(replaced.undo(denied,player,List.of(small)).moved()==0,"external equal stack replacement invalidates the receipt");
        small.setItem(0,berries(60));player.setItem(12,berries(3));
        var nativeDeposit=new Transactions.Plan(List.of(small,player),()->true);
        nativeDeposit.move(player,12,List.of(small),3);nativeDeposit.commitOnServerThread();
        var nativeReceipt=new FieldKit.Receipt(nativeDeposit,List.of(small),"room A",101);
        small.getItem(0).shrink(3);small.setChanged();small.getItem(0).grow(3);small.setChanged();
        var inPlace=new Transactions.Plan(List.of(small,player),()->true);
        check(nativeReceipt.undo(inPlace,player,List.of(small)).moved()==0,"Anya chest dirty notifications catch in-place shrink then regrow");
        var other=new SimpleContainer(1);other.setItem(0,berries(63));
        var distinct=new Transactions.Plan(List.of(other,player),()->true);
        check(replaced.undo(distinct,player,List.of(other)).moved()==0,"same-looking other pocket never used");
        var shown=small.getItem(0).copy();
        check(FieldKit.canLocate(small,0,shown,()->true,()->List.of(small),()->true),"locate exact current permitted stack");
        check(!FieldKit.canLocate(small,0,shown,()->false,()->{throw new AssertionError("enumerated without View");},()->true),"revoked view never enumerates physical sources");
        check(!FieldKit.canLocate(small,0,shown,()->true,()->List.of(other),()->{throw new AssertionError("queried another pocket");}),"locator excludes foreign pocket before querying it");
        check(!FieldKit.canLocate(small,0,shown,()->true,()->List.of(small),()->false)
                &&!FieldKit.canLocate(small,0,named(63),()->true,()->List.of(small),()->true),"unloaded/replaced chest and stale item cannot be marked");
        var vanilla=new net.minecraft.world.level.block.entity.BarrelBlockEntity(net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState());
        player.setItem(12,berries(10));small.setItem(0,berries(60));
        var mixed=new Transactions.Plan(List.of(small,vanilla,player),()->true);
        mixed.move(player,12,List.of(small,vanilla),10);check(mixed.commitOnServerThread().isEmpty(),"mixed dump commits to Anya and vanilla");
        var mixedReceipt=new FieldKit.Receipt(mixed,List.of(small,vanilla),"network",110);
        check(mixedReceipt.eligible()==4&&mixedReceipt.external()==6,"receipt separates four eligible and six external items");
        var mixedUndo=new Transactions.Plan(List.of(small,vanilla,player),()->true);
        check(mixedReceipt.undo(mixedUndo,player,List.of(vanilla,small)).moved()==4&&mixedUndo.commitOnServerThread().isEmpty()
                &&vanilla.getItem(0).getCount()==6&&small.getItem(0).getCount()==60,"unchanged vanilla stack never reclaimed by mixed undo");
        player.setItem(12,berries(2));
        var external=new Transactions.Plan(List.of(vanilla,player),()->true);external.move(player,12,List.of(vanilla),2);
        external.commitOnServerThread();var externalReceipt=new FieldKit.Receipt(external,List.of(vanilla),"network",110);
        check(externalReceipt.external()==2&&externalReceipt.eligible()==0,"external-only dump records zero undoable items");
        var p=inventoryOwner();
        var holder=new SimpleContainer(new ItemStack(Content.BACKPACK.get()));
        var spot=new BackpackAccess.Source(holder,0,0,()->true);var bag=new BagInventory(p,spot);
        player.setItem(12,berries(9));var bagDump=new Transactions.Plan(List.of(bag,player),()->true);
        bagDump.move(player,12,List.of(bag),9);check(bagDump.commitOnServerThread().isEmpty(),"physical backpack deposit committed");
        var bagReceipt=new FieldKit.Receipt(bagDump,List.of(bag),"bag",120);
        var reopenedBag=new BagInventory(p,spot);
        var friendBag=new Transactions.Plan(List.of(reopenedBag,friend),()->true);
        friendBag.move(reopenedBag,0,List.of(friend),4);friendBag.commitOnServerThread();
        var returnedBag=new Transactions.Plan(List.of(reopenedBag,friend),()->true);
        returnedBag.move(friend,0,List.of(reopenedBag),4);returnedBag.commitOnServerThread();
        var latestBag=new BagInventory(p,spot);
        var bagUndo=new Transactions.Plan(List.of(latestBag,player),()->true);
        check(bagReceipt.undo(bagUndo,player,List.of(latestBag)).moved()==5&&bagUndo.commitOnServerThread().isEmpty()
                &&new BagInventory(p,spot).getItem(0).getCount()==4,"bag wrapper reopen, guarded withdraw and replacement reclaim only original five");
        var oldWrapper=new BagInventory(p,spot);player.setItem(12,berries(5));
        var again=new Transactions.Plan(List.of(oldWrapper,player),()->true);again.move(player,12,List.of(oldWrapper),5);again.commitOnServerThread();
        var oldReceipt=new FieldKit.Receipt(again,List.of(oldWrapper),"bag",121);
        var externalBag=new BagInventory(p,spot);externalBag.setItem(0,berries(9));
        var nowBag=new BagInventory(p,spot);var refuseBag=new Transactions.Plan(List.of(nowBag,player),()->true);
        check(oldReceipt.undo(refuseBag,player,List.of(nowBag)).moved()==0,"equal replacement of the sealed bag component invalidates old wrapper identity");
        player.clearContent();var revoked=new Transactions.Plan(List.of(small,player),()->false);
        check(FieldKit.restock(revoked,player,List.of(small),pack).moved()>0&&!revoked.commitOnServerThread().isEmpty()
                &&player.isEmpty()&&small.getItem(0).getCount()==60,"revoked access cancels a planned withdrawal");
    }
}
