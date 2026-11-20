package dev.anya.storage.furniture;

import dev.anya.storage.Content;
import dev.anya.storage.Network;
import dev.anya.storage.Transactions;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.registries.OfflineRegistries;
import java.nio.file.Path;
import java.util.*;

/** Exercises registered blocks, real stacks, the same transaction plans as the action handlers, and disk NBT. */
public final class FurnitureInventoryCheck {
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static boolean commit(Transactions.Plan plan) throws ReflectiveOperationException {
        var method=Transactions.Plan.class.getDeclaredMethod("commitOnServerThread");
        method.setAccessible(true);
        return ((String)method.invoke(plan)).isEmpty();
    }
    private static FurnitureEntity again(RegistryAccess lookup,FurnitureEntity f) {
        var saved=f.saveWithFullMetadata(lookup);
        var copy=new FurnitureEntity(f.getBlockPos(),f.getBlockState());
        copy.loadWithComponents(saved,lookup);
        check(copy.getUpdateTag(lookup).isEmpty(),"Tracking tag must contain no contents or captions");
        return copy;
    }
    public static void main(String[] args) throws Exception {
        RegistryAccess registry=OfflineRegistries.setup(Path.of(args[0]));
        ShelfGeometryCheck.run();
        UUID owner=UUID.randomUUID(),guest=UUID.randomUUID(),other=UUID.randomUUID(),room=UUID.randomUUID();
        var hand=new SimpleContainer(2);
        ItemStack named=new ItemStack(Items.EMERALD,7);
        named.set(DataComponents.CUSTOM_NAME,Component.literal("A secret from the sender"));
        hand.setItem(0,named.copy());
        var f=new FurnitureEntity(BlockPos.ZERO,Content.HATCH.get().defaultBlockState());
        f.initialize(owner,room);
        check(!f.maySend(guest) && !f.mayView(guest) && !Network.supported(f),"No implicit access or network route");
        f.invited.add(guest);
        long revision=f.revision();
        var stale=Furniture.depositPlan(hand,0,f,0,()->f.maySend(guest) && f.revision()==revision);
        f.invited.clear();f.setChanged();
        check(!commit(stale) && f.emptyForRemoval() && ItemStack.matches(named,hand.getItem(0)),"Revoked sender cannot transfer");
        f.invited.add(guest);
        var fresh=Furniture.depositPlan(hand,0,f,0,()->f.maySend(guest));
        check(commit(fresh) && !commit(fresh) && hand.isEmpty(),"One whole parcel, no replay");
        f.receipts[0]="Sender — Happy birthday";
        hand.setItem(0,named.copyWithCount(3));
        check(commit(Furniture.depositPlan(hand,0,f,1,()->f.maySend(guest))),"Identical item can be a second parcel");
        f.receipts[1]="Sender — For later";
        var recovered=again(registry,f);
        var malformed=f.saveWithFullMetadata(registry);
        malformed.putString("Caption","line\u202e"+"x".repeat(120));
        malformed.getList("Invited",net.minecraft.nbt.Tag.TAG_INT_ARRAY).add(new net.minecraft.nbt.IntArrayTag(new int[]{42}));
        malformed.getList("Receipts",net.minecraft.nbt.Tag.TAG_STRING).set(0,net.minecraft.nbt.StringTag.valueOf("a".repeat(200)));
        var repaired=new FurnitureEntity(f.getBlockPos(),f.getBlockState());
        repaired.loadWithComponents(malformed,registry);
        check(repaired.caption.length()==80 && !repaired.caption.contains("\u202e") && repaired.receipts[0].length()==104
                && repaired.invited.equals(Set.of(guest)) && ItemStack.matches(named,repaired.inventory.getItem(0)),"Malformed decoration metadata cannot discard other items");
        check(recovered.belongsTo(owner,room) && !recovered.belongsTo(owner,other) && recovered.maySend(guest)
                && !recovered.mayView(guest) && !recovered.maySend(other),"Membership and room identity round-trip");
        check(ItemStack.matches(named,recovered.inventory.getItem(0)) && recovered.inventory.getItem(1).getCount()==3
                && recovered.receipts[0].endsWith("Happy birthday") && recovered.receipts[1].endsWith("For later"),"Distinct components/counts/notes after load");
        hand.setItem(0,new ItemStack(Items.DIRT,64));
        hand.setItem(1,new ItemStack(Items.DIRT,64));
        check(Furniture.collectPlan(hand,recovered,0,()->true)==null && !recovered.emptyForRemoval(),"Full recipient leaves parcel intact");
        hand.clearContent();
        check(!commit(Furniture.collectPlan(hand,recovered,0,()->false)) && hand.isEmpty(),"Collection requires live authorization");
        check(commit(Furniture.collectPlan(hand,recovered,0,()->true)) && ItemStack.matches(named,hand.getItem(0))
                && recovered.inventory.getItem(0).isEmpty() && recovered.inventory.getItem(1).getCount()==3,"Collect returns exact components without touching neighbor");
        var shelf=new FurnitureEntity(BlockPos.ZERO,Content.SHELF.get().defaultBlockState());
        shelf.initialize(owner,null);
        check(shelf.maySend(guest) && shelf.mayView(guest),"Globe is public on placement");
        var insert=Furniture.globePlan(hand,0,shelf,()->shelf.maySend(guest));
        check(commit(insert) && !commit(insert) && hand.getItem(0).getCount()==6 && shelf.inventory.getItem(0).getCount()==1,
                "A non-owner inserts exactly one item once");
        check(Furniture.globePlan(hand,0,shelf,()->true)==null,"An occupied globe cannot replace or consume the next item");
        var reloaded=again(registry,shelf);
        check(reloaded.mayView(other) && !reloaded.sealedKeepsake(),"Public status survives reload");
        check(!commit(Furniture.globePlan(hand,1,reloaded,()->false)) && hand.getItem(1).isEmpty(),"Denied withdrawal keeps item");
        check(commit(Furniture.globePlan(hand,1,reloaded,()->reloaded.mayView(other))) && reloaded.emptyForRemoval()
                && ItemStack.matches(named.copyWithCount(1),hand.getItem(1)),"A second non-owner retrieves exact components into chosen hand");
        var old=shelf.saveWithFullMetadata(registry);
        old.remove("LegacyPrivateGlobe");
        old.putString("Caption","From our first cave");
        var migrated=new FurnitureEntity(shelf.getBlockPos(),shelf.getBlockState());
        migrated.loadWithComponents(old,registry);
        check(!migrated.mayView(guest) && !migrated.maySend(guest) && migrated.sealedKeepsake() && migrated.mayView(owner)
                && migrated.caption.equals("From our first cave"),"An occupied private pre-globe shelf remains sealed");
        var migratedAgain=again(registry,migrated);
        check(migratedAgain.sealedKeepsake(),"A second save does not publish a legacy private item");
        hand.setItem(1,ItemStack.EMPTY);
        check(commit(Furniture.globePlan(hand,1,migratedAgain,()->migratedAgain.mayView(owner))),"Original owner reclaims old item");
        migratedAgain.finishCollection();
        check(migratedAgain.mayView(other) && !migratedAgain.sealedKeepsake() && migratedAgain.caption.isEmpty()
                && ItemStack.matches(named.copyWithCount(1),hand.getItem(1)),"Private contents recovered and empty globe becomes public");
        old.putBoolean("GuestsSeeShelf",true);
        migrated.loadWithComponents(old,registry);
        check(!migrated.sealedKeepsake() && migrated.mayView(other),"An old shared display stays public");
        System.out.println("PASS parcel privacy, two-player globe transfers, no overwrite, exact components, replay refusal, legacy private and shared shelf migration");
    }
}
