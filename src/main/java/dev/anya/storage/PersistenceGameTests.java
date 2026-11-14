package dev.anya.storage;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@GameTestHolder(AnyaStorage.ID)
@PrefixGameTestTemplate(false)
public final class PersistenceGameTests {
    private static void ok(GameTestHelper h,boolean value,String message){h.assertTrue(value,message);}
    private static ServerPlayer player(GameTestHelper h,String name){
        return new FakePlayer(h.getLevel(),new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),"StorageTest")){
            @Override public boolean hasPermissions(int level){return false;}
        };
    }
    @GameTest(template="empty") public static void chestSerialization(GameTestHelper h){
        var registry=h.getLevel().registryAccess();StorageEntity original=new StorageEntity(h.absolutePos(new BlockPos(1,1,1)),Content.CRATE.get().defaultBlockState());
        original.owner=UUID.randomUUID();original.trusted.add(UUID.randomUUID());original.baseName="Serialization Base";original.symbol="star";original.oreProgress=734;original.rangeUpgrades=3;
        ItemStack sword=new ItemStack(Items.DIAMOND_SWORD);sword.setDamageValue(93);sword.set(DataComponents.CUSTOM_NAME,Component.literal("Do not lose me"));original.setItem(53,sword);
        StorageEntity.Route route=new StorageEntity.Route();route.category="equipment";route.priority=7;route.overflow=false;route.items.add("minecraft:diamond_sword");original.routes.put(123L,route);
        CompoundTag saved=new CompoundTag();original.saveAdditional(saved,registry);StorageEntity copy=new StorageEntity(original.getBlockPos(),original.getBlockState());copy.loadAdditional(saved,registry);
        ok(h,Transactions.same(sword,copy.getItem(53)),"Exact item components persisted");ok(h,copy.owner.equals(original.owner)&&copy.trusted.equals(original.trusted),"Owner and trust persisted");ok(h,copy.oreProgress==734&&copy.rangeUpgrades==3&&copy.baseName.equals(original.baseName),"Base progression persisted");ok(h,copy.routes.get(123L).items.equals(route.items)&&copy.routes.get(123L).priority==7&&!copy.routes.get(123L).overflow,"Routing persisted");h.succeed();
    }
    @GameTest(template="empty") public static void cubeCapacitySerialization(GameTestHelper h){
        var registry=h.getLevel().registryAccess();StorageEntity cube=new StorageEntity(BlockPos.ZERO,Content.CUBE.get().defaultBlockState());cube.setItem(215,new ItemStack(Items.NETHER_STAR,11));CompoundTag tag=new CompoundTag();cube.saveAdditional(tag,registry);StorageEntity copy=new StorageEntity(BlockPos.ZERO,Content.CUBE.get().defaultBlockState());copy.loadAdditional(tag,registry);ok(h,copy.getContainerSize()==216&&copy.getItem(215).getCount()==11,"Last cube slot survived persistence");h.succeed();
    }
    @GameTest(template="empty") public static void visualPacketsDoNotEraseStorage(GameTestHelper h){
        StorageEntity source=new StorageEntity(h.absolutePos(new BlockPos(1,1,1)),Content.CRATE.get().defaultBlockState());source.setLevel(h.getLevel());source.symbol="gear";
        StorageEntity destination=new StorageEntity(source.getBlockPos(),source.getBlockState());destination.setItem(0,new ItemStack(Items.DIAMOND,31));destination.owner=UUID.randomUUID();UUID owner=destination.owner;destination.showLabels=false;
        destination.onDataPacket(null,source.getUpdatePacket(),h.getLevel().registryAccess());
        ok(h,destination.symbol.equals("gear")&&destination.showLabels==Config.get().labels,"Visual settings synchronized");ok(h,destination.getItem(0).getCount()==31&&owner.equals(destination.owner),"Visual-only update did not reload or clear private data");h.succeed();
    }
    @GameTest(template="empty") public static void backpackHostSlotIsExcluded(GameTestHelper h){
        ServerPlayer p=player(h,"bag-host");p.getInventory().setItem(9,new ItemStack(Content.BACKPACK.get()));BagInventory bag=new BagInventory(p,9);bag.setItem(0,new ItemStack(Items.DIAMOND,12));MainInventory main=new MainInventory(p,9);
        Transactions.Plan transfer=new Transactions.Plan(List.of(main,bag),bag::valid);ok(h,transfer.move(bag,0,List.of(main),12)==12,"Transfer planned");String error=transfer.commit(h.getLevel().getServer());ok(h,error.isEmpty(),error);ok(h,p.getInventory().getItem(9)==bag.stack&&bag.isEmpty()&&p.getInventory().getItem(0).getCount()==12,"Backpack host and contents were not double-counted");h.succeed();
    }
    @GameTest(template="empty") public static void replacingBackpackInvalidatesView(GameTestHelper h){
        ServerPlayer p=player(h,"bag-identity");p.getInventory().setItem(9,new ItemStack(Content.BACKPACK.get()));BagInventory first=new BagInventory(p,9);first.setItem(4,new ItemStack(Items.EMERALD,19));ItemStack replacement=first.stack.copy();p.getInventory().setItem(9,replacement);ok(h,!first.valid(),"Stale backpack identity is rejected");BagInventory reopened=new BagInventory(p,9);ok(h,reopened.valid()&&reopened.getItem(4).getCount()==19,"Reopened item uses persisted contents");h.succeed();
    }
    @GameTest(template="empty") public static void portableItemSerialization(GameTestHelper h){
        ItemStack backpack=new ItemStack(Content.BACKPACK.get());BagInventory.initialize(backpack);NonNullList<ItemStack> contents=NonNullList.withSize(27,ItemStack.EMPTY);ItemStack item=new ItemStack(Items.PAPER,7);CompoundTag custom=new CompoundTag();custom.putString("Specimen","Component preservation test");custom.putUUID("Identity",UUID.randomUUID());item.set(DataComponents.CUSTOM_DATA,CustomData.of(custom));contents.set(26,item);backpack.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(contents));
        ItemStack restored=ItemStack.parse(h.getLevel().registryAccess(),backpack.save(h.getLevel().registryAccess())).orElseThrow();ok(h,Transactions.same(backpack,restored),"Backpack and nested custom data survived item serialization");h.succeed();
    }
    @GameTest(template="empty") public static void unloadedHubDoesNotForceChunks(GameTestHelper h){
        ServerPlayer p=player(h,"no-force-load");BlockPos far=new BlockPos(1600000,64,1600000);p.setPos(far.getX()+.5,far.getY()+.5,far.getZ()+.5);StorageEntity hub=new StorageEntity(far,Content.HUB.get().defaultBlockState());hub.setLevel(h.getLevel());hub.owner=p.getUUID();ok(h,h.getLevel().getChunkSource().getChunkNow(100000,100000)==null,"Precondition: distant chunk unloaded");ok(h,!Network.hubReason(p,hub).isEmpty(),"Unloaded hub rejected");ok(h,h.getLevel().getChunkSource().getChunkNow(100000,100000)==null,"Access check did not load a chunk");h.succeed();
    }
    @GameTest(template="empty") public static void routingFiltersIntersect(GameTestHelper h){
        StorageEntity.Route rule=new StorageEntity.Route();rule.category="food";rule.items.add("minecraft:apple");ok(h,rule.allows(new ItemStack(Items.APPLE)),"Matching category and item accepted");ok(h,!rule.allows(new ItemStack(Items.BREAD)),"Category alone does not bypass item filter");ok(h,!rule.allows(new ItemStack(Items.DIAMOND)),"Wrong category rejected");h.succeed();
    }
    @GameTest(template="empty") public static void customOwnershipAndTrust(GameTestHelper h){
        ServerPlayer p=player(h,"ownership");StorageEntity chest=new StorageEntity(BlockPos.ZERO,Content.CRATE.get().defaultBlockState());chest.owner=UUID.randomUUID();ok(h,!chest.allowed(p),"Untrusted non-operator rejected");chest.trusted.add(p.getUUID());ok(h,chest.allowed(p)&&!chest.ownedBy(p),"Trusted player can use but not own or break the block");chest.trusted.clear();chest.owner=p.getUUID();ok(h,chest.ownedBy(p),"Actual owner recognized");h.succeed();
    }
    @GameTest(template="empty") public static void doubleChestPartnerLock(GameTestHelper h){
        var state=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.LEFT);BlockPos leftPos=h.absolutePos(new BlockPos(2,1,2)),rightPos=leftPos.relative(ChestBlock.getConnectedDirection(state));h.getLevel().setBlock(leftPos,state,2);h.getLevel().setBlock(rightPos,state.setValue(ChestBlock.TYPE,ChestType.RIGHT),2);
        ChestBlockEntity left=(ChestBlockEntity)h.getLevel().getBlockEntity(leftPos),right=(ChestBlockEntity)h.getLevel().getBlockEntity(rightPos);CompoundTag data=right.saveWithFullMetadata(h.getLevel().registryAccess());data.putString("Lock","Required key name");right.loadWithComponents(data,h.getLevel().registryAccess());ServerPlayer p=player(h,"double-lock");p.setPos(leftPos.getX()+.5,leftPos.getY()+1,leftPos.getZ()+.5);
        String reason=Safety.reason(p,left,null);ok(h,reason.contains("lock"),"A lock on either half must deny the double chest: "+reason);h.succeed();
    }
}
