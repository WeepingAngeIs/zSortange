package dev.anya.storage.furniture;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.AnyaStorage;
import dev.anya.storage.Content;
import dev.anya.storage.Network;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(AnyaStorage.ID)
@PrefixGameTestTemplate(false)
public final class FurnitureGameTests {
    private static ServerPlayer viewer(GameTestHelper h,String name,BlockPos pos) {
        var p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),name)) {
            @Override public boolean hasPermissions(int i) { return false; }
        };
        p.setPos(pos.getX()+.5,pos.getY(),pos.getZ()-2);
        Vec3 aim=Vec3.atCenterOf(pos).subtract(p.getEyePosition());
        p.setYRot((float)Math.toDegrees(Math.atan2(-aim.x,aim.z)));
        p.setXRot((float)-Math.toDegrees(Math.atan2(aim.y,aim.horizontalDistance())));
        return p;
    }
    private static void reload(GameTestHelper h,FurnitureEntity f) {
        var lookup=h.getLevel().registryAccess();
        CompoundTag disk=f.saveWithFullMetadata(lookup);
        FurnitureEntity check=new FurnitureEntity(f.getBlockPos(),f.getBlockState());
        check.loadWithComponents(disk,lookup);
        h.assertTrue(check.belongsTo(f.owner,f.pocket) && check.caption.equals(f.caption)
                && check.visible==f.visible && check.invited.equals(f.invited) && Arrays.equals(check.receipts,f.receipts),
                "Owner, room identity, visibility, captions, invitations and notes survive serialization");
        for(int i=0;i<f.inventory.getContainerSize();i++)
            h.assertTrue(ItemStack.matches(f.inventory.getItem(i),check.inventory.getItem(i)),"Every real item and component survives serialization");
        h.assertTrue(check.getUpdateTag(lookup).isEmpty(),"Chunk tracking cannot reveal furniture data");
        h.assertTrue(!Network.supported(f),"Unrelated network scans cannot pull in a shelf or hatch");
    }
    @GameTest(template="empty") public static void invitedDeliveryIsSealed(GameTestHelper h) {
        var block=new BlockPos(1,1,2);
        h.setBlock(block,Content.HATCH.get());
        var pos=h.absolutePos(block);
        var f=(FurnitureEntity)h.getLevel().getBlockEntity(pos);
        var owner=viewer(h,"Recipient",pos);
        var sender=viewer(h,"Sender",pos);
        f.initialize(owner.getUUID(),null);
        var parcel=new ItemStack(Items.EMERALD,7);
        parcel.set(DataComponents.CUSTOM_NAME,Component.literal("Private parcel"));
        sender.getInventory().setItem(0,parcel);
        Furniture.deliver(sender,f,"");
        h.assertTrue(f.emptyForRemoval() && sender.getMainHandItem().getCount()==7,"Uninvited deposit refused");
        f.invited.add(sender.getUUID());
        Furniture.deliver(sender,f,"Happy birthday");
        h.assertTrue(sender.getMainHandItem().isEmpty() && f.inventory.getItem(0).getCount()==7,"Exact stack transferred");
        h.assertTrue(f.receipts[0].contains("Happy birthday") && !f.emptyForRemoval(),"Note retained alongside occupied parcel");
        h.assertTrue(!Furniture.canSee(sender,f),"Hatch never provides a display to a sender");
        Furniture.collect(sender,f);
        h.assertTrue(sender.getMainHandItem().isEmpty() && !f.emptyForRemoval(),"Sender cannot withdraw");
        reload(h,f);
        for(int i=0;i<36;i++) owner.getInventory().setItem(i,new ItemStack(Items.COBBLESTONE,64));
        Furniture.collect(owner,f);
        h.assertTrue(f.inventory.getItem(0).getCount()==7 && f.receipts[0].contains("Happy birthday"),"Full inventory keeps parcel and note");
        owner.getInventory().setItem(0,ItemStack.EMPTY);
        Furniture.collect(owner,f);
        h.assertTrue(f.emptyForRemoval() && f.receipts[0].isEmpty() && owner.getMainHandItem().getCount()==7
                && owner.getMainHandItem().getHoverName().getString().equals("Private parcel"),"Owner receives the item with intact components");
        h.succeed();
    }
    @GameTest(template="empty") public static void globeDisplaysReturnsAndDropsOneItem(GameTestHelper h) {
        var block=new BlockPos(1,1,2);
        h.setBlock(block,Content.SHELF.get());
        var pos=h.absolutePos(block);
        var f=(FurnitureEntity)h.getLevel().getBlockEntity(pos);
        var owner=viewer(h,"Curator",pos);
        var guest=viewer(h,"Visitor",pos);
        f.initialize(owner.getUUID(),null);
        owner.getInventory().setItem(0,new ItemStack(Items.AMETHYST_SHARD,3));
        owner.getMainHandItem().set(DataComponents.CUSTOM_NAME,Component.literal("Personal engraving"));
        Furniture.use(owner,f);
        h.assertTrue(f.inventory.getItem(0).getCount()==1 && owner.getMainHandItem().getCount()==2,"Display holds exactly one real item");
        var tag=f.getUpdateTag(h.getLevel().registryAccess());
        h.assertTrue(tag.getAllKeys().equals(Set.of("GlobeItem")) && tag.getString("GlobeItem").equals("minecraft:amethyst_shard"),
                "Tracking players receive only the base item id");
        Furniture.use(guest,f);
        h.assertTrue(guest.getMainHandItem().getHoverName().getString().equals("Personal engraving") && f.emptyForRemoval(),
                "Second player retrieves the exact item with an empty hand");
        Furniture.use(guest,f);
        reload(h,f);
        h.getLevel().setBlock(pos,f.getBlockState().setValue(dev.anya.storage.StorageBlock.FACING,net.minecraft.core.Direction.WEST),3);
        h.assertTrue(f.inventory.getItem(0).getCount()==1,"Rotating a globe does not drop an item");
        h.getLevel().destroyBlock(pos,true,guest);
        var drops=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(pos).inflate(1));
        int items=0, globes=0;
        for (var drop:drops) {
            var stack=drop.getItem();
            if (stack.is(Items.AMETHYST_SHARD) && stack.getHoverName().getString().equals("Personal engraving")) items+=stack.getCount();
            if (stack.is(Content.SHELF.get().asItem())) globes+=stack.getCount();
        }
        h.assertTrue(items==1 && globes==1,"A broken globe drops exactly its block and its original keepsake");
        h.succeed();
    }
}
