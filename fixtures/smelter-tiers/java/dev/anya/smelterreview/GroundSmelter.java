package dev.anya.smelterreview;

import dev.anya.storage.BagInventory;
import dev.anya.storage.Content;
import dev.anya.storage.Packets;
import dev.anya.storage.PlacedBackpackEntity;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.Transactions;
import dev.anya.storage.Upgrades;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import static dev.anya.smelterreview.SmelterGameTests.*;

/** Ordinary block use and actual world ticks in the disposable seed server. */
final class GroundSmelter {
    private static final BlockPos POS=new BlockPos(9,90,4);
    private final ServerPlayer owner;
    private final long opened;
    private long queuedAt;
    private ItemStack queued=ItemStack.EMPTY;

    GroundSmelter(ServerLevel level){
        owner=menuTester(level,"GroundOwner");
        var guest=menuTester(level,"GroundGuest");
        owner.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        guest.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        nearby(owner);nearby(guest);level.getChunkAt(POS);
        check(level.isEmptyBlock(POS) && level.isEmptyBlock(POS.below()),"Expected fresh ground test space");
        level.setBlockAndUpdate(POS.below(),Blocks.STONE.defaultBlockState());
        owner.getInventory().selected=0;owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Content.BACKPACK.get()));
        var bag=bag(owner);filter(bag,"raw_iron");
        bag.setItem(0,new ItemStack(Items.RAW_IRON,2));bag.setItem(1,new ItemStack(Items.COAL,2));
        var before=bag.stack.copy();
        owner.setShiftKeyDown(true);use(owner,POS.below(),Direction.UP);owner.setShiftKeyDown(false);
        check(owner.getMainHandItem().isEmpty() && Transactions.same(before,saved()),"Ground placement did not move the exact bag");
        var module=named(disc(MANUAL),"Workshop ground smelter");
        guest.setItemInHand(InteractionHand.MAIN_HAND,module.copy());use(guest,POS,Direction.NORTH);
        check(Transactions.same(guest.getMainHandItem(),module) && Transactions.same(before,saved()),"Guest attached a module or lost theirs");
        guest.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);use(guest,POS,Direction.NORTH);
        check(guest.containerMenu==guest.inventoryMenu && Transactions.same(before,saved()),"Guest opened the owner’s bag");
        owner.setItemInHand(InteractionHand.MAIN_HAND,module.copy());use(owner,POS,Direction.NORTH);
        var installed=Upgrades.installedItems(saved());
        check(owner.getMainHandItem().isEmpty() && installed.size()==1 && installed.getCompound(0).getString("Id").equals(MANUAL)
                && Transactions.same(module,ItemStack.parseOptional(owner.registryAccess(),installed.getCompound(0).getCompound("Stack"))),
                "Owner attachment did not retain the single named manual disc");
        var menu=openSettings();var unarmed=saved();
        owner.setPos(POS.getX()+16,POS.getY(),POS.getZ());
        check(!menu.stillValid(owner),"Out-of-reach owner retained a valid ground menu");
        press(menu,24);
        check(owner.containerMenu==owner.inventoryMenu && Transactions.same(unarmed,saved()),
                "Out-of-range manual Start kept the menu open or changed the placed bag");
        nearby(owner);openSettings();opened=level.getGameTime();
    }

    private static void nearby(ServerPlayer p){p.setPos(POS.getX()+.5,POS.getY(),POS.getZ()-2);}
    private static void use(ServerPlayer p,BlockPos pos,Direction side){
        var hit=new BlockHitResult(Vec3.atCenterOf(pos).relative(side,.5),side,pos,false);
        p.gameMode.useItemOn(p,p.serverLevel(),p.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
    }
    private static void press(StoreMenu menu,int id){
        menu.action(new Packets.Action(menu.containerId,menu.session(),menu.revision(),"slot","",id));
    }
    private ItemStack saved(){
        var entity=owner.serverLevel().getBlockEntity(POS);
        check(entity instanceof PlacedBackpackEntity,"Expected real placed backpack block");
        return ItemStack.parseOptional(owner.registryAccess(),entity.saveWithoutMetadata(owner.registryAccess()).getCompound("Bag"));
    }
    private StoreMenu openSettings(){
        use(owner,POS,Direction.NORTH);
        check(owner.containerMenu instanceof StoreMenu,"Owner could not open own placed backpack");
        var menu=(StoreMenu)owner.containerMenu;
        check(menu.storageView() && menu.stillValid(owner),"Owner's attached bag menu was invalid");
        press(menu,51);
        check(!menu.storageView() && menu.getSlot(24).getItem().getHoverName().getString().equals("Start one furnace cook"),
                "Missing manual control in the ground backpack settings");
        return menu;
    }

    boolean tick(){
        long now=owner.serverLevel().getGameTime();
        if(now<=opened)return false;
        if(queued.isEmpty()){
            var menu=(StoreMenu)owner.containerMenu;press(menu,24);
            check(menu.getSlot(24).getItem().getHoverName().getString().equals("One furnace cook queued"),
                    "Nearby owner could not arm their installed manual disc from ground settings");
            owner.closeContainer();queued=saved();queuedAt=now;
            return false;
        }
        long elapsed=now-queuedAt;
        if(elapsed%20==0)carry(owner,1);
        if(elapsed<220)return false;
        check(Transactions.same(queued,saved()),"Ground backpack advanced its cook or spent fuel during 220 real world ticks");
        owner.setShiftKeyDown(true);use(owner,POS,Direction.NORTH);owner.setShiftKeyDown(false);
        check(owner.serverLevel().isEmptyBlock(POS) && Transactions.same(queued,owner.getMainHandItem()),
                "Pickup did not return the same queued bag");
        carry(owner,15);
        check(!Upgrades.manualSmeltPending(bag(owner)) && bag(owner).countItem(Items.RAW_IRON)==1 &&
                bag(owner).countItem(Items.IRON_INGOT)==1 && bag(owner).countItem(Items.COAL)==1 &&
                BagInventory.data(bag(owner).stack).getInt("FuelTicks")==1400,"Carrying the picked-up bag did not complete exactly one cook");
        return true;
    }
}
