package dev.anya.storage.pocket;

import dev.anya.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Normal block items use the game's placement and loot paths. Validate every captured placement cell. */
final class NativeRoomBuild {
    static boolean special(BlockState state){
        return state.is(Content.CRATE.get()) || dev.anya.storage.furniture.Furniture.isFurniture(state.getBlock())
                || state.is(PocketContent.ENTRANCE.get());
    }
    static boolean useNative(ItemStack stack){
        if(!(stack.getItem() instanceof BlockItem item))return false;
        var state=item.getBlock().defaultBlockState();
        return !special(state);
    }
    static boolean placeableStack(ItemStack stack){
        var plain=new ItemStack(stack.getItem());
        if(stack.is(Blocks.CHEST.asItem())){
            var name=stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
            if(name!=null)plain.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,name);
        }
        return ItemStack.isSameItemSameComponents(stack,plain);
    }
    private static PocketLedger.Pocket room(ServerPlayer p){
        var lease=PocketService.lease(p.getUUID());
        if(lease==null || lease.kind()!=PocketLeases.Kind.VISIT || PocketService.recovering(p.getUUID())
                || !PocketService.present(p) || !PocketService.inPocket(p.level()))return null;
        var room=PocketService.loadedPocket(p.getServer(),lease.pocket());
        return room!=null && room.epoch()==lease.epoch() && room.stage()==PocketLedger.Stage.READY ? room : null;
    }
    static boolean cell(ServerPlayer p,BlockPos pos,BlockState state,boolean remove,boolean storage){
        var room=room(p);
        if(room==null || !p.getAbilities().mayBuild || !p.serverLevel().hasChunkAt(pos)
                || !PocketRoomLayout.registeredPosition(room,pos) || !RoomEditor.active(p))return false;
        int rights=BasePermissions.BUILD | (storage?BasePermissions.LEGACY:0);
        return PocketService.authorize(p,room,PocketLeases.Kind.VISIT,false,rights).isEmpty()
                && Safety.buildReason(p,p.serverLevel(),pos,state,remove).isEmpty() && RoomEditor.active(p);
    }
    private static boolean chestEdit(ServerPlayer p,BlockPos pos,BlockState state,boolean removal){
        var chest=PocketInventories.availableChest(p.serverLevel(),pos);
        if(chest==null || removal && !chest.isEmpty() || !cell(p,pos,state,removal,false))return false;
        if(state.getValue(ChestBlock.TYPE)==ChestType.SINGLE)return true;
        BlockPos other=pos.relative(ChestBlock.getConnectedDirection(state));
        if(!p.serverLevel().hasChunkAt(other))return false;
        var neighbour=p.serverLevel().getBlockState(other);
        // During placement the other half can still be SINGLE until vanilla sends its neighbour updates.
        var second=PocketInventories.availableChest(p.serverLevel(),other);
        if(!neighbour.is(state.getBlock()) || neighbour.getValue(ChestBlock.FACING)!=state.getValue(ChestBlock.FACING)
                || second==null || !cell(p,other,neighbour,removal,!second.isEmpty()))return false;
        return neighbour.getValue(ChestBlock.TYPE)==ChestType.SINGLE ? !removal
                : other.relative(ChestBlock.getConnectedDirection(neighbour)).equals(pos);
    }
    static boolean canAddChest(ServerPlayer p){
        var room=room(p);
        if(room==null || !RoomEditor.active(p))return false;
        try{return RoomEditor.canAddChest(PocketInventories.count(p.serverLevel(),room), ExpansionRules.get().pocketContainers);}
        catch(java.io.IOException problem){return false;}
    }
    private static boolean placedCell(ServerPlayer p,BlockPos pos,BlockState old,BlockState state){
        if(special(old)||special(state)||old.hasBlockEntity()&&!old.is(state.getBlock()))return false;
        if(state.is(Blocks.CHEST)){
            var room=room(p);
            if(room==null || !chestEdit(p,pos,state,false))return false;
            try{return old.is(Blocks.CHEST) || PocketInventories.count(p.serverLevel(),room)<=ExpansionRules.get().pocketContainers;}
            catch(java.io.IOException problem){return false;}
        }
        return cell(p,pos,state,false,state.hasBlockEntity());
    }
    static boolean place(ServerPlayer p,BlockEvent.EntityPlaceEvent e){
        if(e instanceof BlockEvent.EntityMultiPlaceEvent multi){
            for(var snapshot:multi.getReplacedBlockSnapshots()){
                var pos=snapshot.getPos();var state=p.serverLevel().getBlockState(pos);
                if(!placedCell(p,pos,snapshot.getState(),state))return false;
            }
            return true;
        }
        return placedCell(p,e.getPos(),e.getBlockSnapshot().getState(),e.getPlacedBlock());
    }
    static boolean breakable(ServerPlayer p,BlockPos pos){
        var level=p.serverLevel();var state=level.getBlockState(pos);
        if(state.is(Blocks.CHEST))return chestEdit(p,pos,state,true);
        if(special(state) || !cell(p,pos,state,true,state.hasBlockEntity()))return false;
        BlockEntity entity=level.getBlockEntity(pos);
        if(entity instanceof CameraDevices.Device device)return device.owned(p);
        if(entity instanceof Container inventory && !inventory.isEmpty())return false;
        // A non-container modded block entity may keep items in a capability; leave its data intact.
        if(entity!=null && !(entity instanceof Container))return false;
        BlockPos paired=null;
        if(state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
            paired=state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER?pos.above():pos.below();
        if(state.getBlock() instanceof BedBlock)
            paired=pos.relative(state.getValue(BedBlock.PART)==BedPart.FOOT?state.getValue(BedBlock.FACING):state.getValue(BedBlock.FACING).getOpposite());
        return paired==null || cell(p,paired,level.getBlockState(paired),true,state.hasBlockEntity());
    }
    private static boolean canUseChestCell(ServerPlayer p,PocketLedger.Pocket room,BlockPos pos){
        return PocketRoomLayout.registeredPosition(room,pos) && p.serverLevel().hasChunkAt(pos)
                && Safety.locationReason(p,p.serverLevel(),pos).isEmpty();
    }
    private static boolean canOpenChest(ServerPlayer p,BlockPos pos){
        var room=room(p);
        if(room==null || !canUseChestCell(p,room,pos)
                || !PocketService.authorize(p,room,PocketLeases.Kind.VISIT,false,BasePermissions.LEGACY).isEmpty())return false;
        var state=p.serverLevel().getBlockState(pos);
        if(!state.is(Blocks.CHEST))return false;
        if(state.getValue(ChestBlock.TYPE)==ChestType.SINGLE)return true;
        var other=pos.relative(ChestBlock.getConnectedDirection(state));
        if(!canUseChestCell(p,room,other))return false;
        var neighbour=p.serverLevel().getBlockState(other);
        return neighbour.is(Blocks.CHEST) && neighbour.getValue(ChestBlock.TYPE)!=ChestType.SINGLE
                && other.relative(ChestBlock.getConnectedDirection(neighbour)).equals(pos)
                && neighbour.getValue(ChestBlock.FACING)==state.getValue(ChestBlock.FACING);
    }
    private record Open(BlockPos pos,net.minecraft.world.level.Level level,BlockEntity source,BlockEntity partner){}
    private static final java.util.Map<ServerPlayer,Open> opened=new java.util.WeakHashMap<>();
    private static Open capture(ServerPlayer p,BlockPos pos){
        var state=p.serverLevel().getBlockState(pos);
        BlockEntity partner=state.is(Blocks.CHEST) && state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE
                ?p.serverLevel().getBlockEntity(pos.relative(ChestBlock.getConnectedDirection(state))):null;
        return new Open(pos.immutable(),p.level(),p.serverLevel().getBlockEntity(pos),partner);
    }
    static void watch(ServerPlayer p,BlockPos pos){opened.put(p,capture(p,pos));}
    private static boolean sameChest(ServerPlayer p,Open open){
        if(open.level!=p.level() || !canOpenChest(p,open.pos))return false;
        var room=room(p);
        var hub=room==null?null:PocketService.home(p,room);
        return hub!=null && hub.permits(p,BasePermissions.VISIT|BasePermissions.LEGACY) && open.equals(capture(p,open.pos))
                && open.source instanceof ChestBlockEntity chest && !Transactions.quarantined(chest) && chest.canOpen(p)
                && (open.partner==null || open.partner instanceof ChestBlockEntity other && !Transactions.quarantined(other) && other.canOpen(p));
    }
    static boolean openChest(ServerPlayer p,BlockPos pos){
        if(!canOpenChest(p,pos))return false;
        var open=capture(p,pos);
        if(!sameChest(p,open))return false;
        var level=p.serverLevel();var state=level.getBlockState(pos);
        var title=state.getMenuProvider(level,pos);
        Container inventory=ChestBlock.getContainer((ChestBlock)state.getBlock(),state,level,pos,false);
        if(title==null || inventory==null)return false;
        int rows=inventory.getContainerSize()/9;
        if(rows!=(open.partner==null?3:6))return false;
        // Draw from the real vanilla chests. Roll any vanilla loot only on an authorized physical open.
        ((ChestBlockEntity)open.source).unpackLootTable(p);
        if(open.partner instanceof ChestBlockEntity partner)partner.unpackLootTable(p);
        var menu=new SimpleMenuProvider((id,bag,who)->new ChestMenu(rows==3?MenuType.GENERIC_9x3:MenuType.GENERIC_9x6,id,bag,inventory,rows){
            @Override public boolean stillValid(Player actor){return actor==p && super.stillValid(actor) && sameChest(p,open);}
            @Override public void broadcastChanges(){
                if(stillValid(p))super.broadcastChanges();
                else if(p.containerMenu==this)p.closeContainer();
            }
            @Override public void clicked(int slot,int button,ClickType type,Player actor){
                if(stillValid(actor))super.clicked(slot,button,type,actor);else p.closeContainer();
            }
            @Override public ItemStack quickMoveStack(Player actor,int slot){
                return stillValid(actor)?super.quickMoveStack(actor,slot):ItemStack.EMPTY;
            }
        },title.getDisplayName());
        if(!sameChest(p,open)||p.openMenu(menu).isEmpty())return false;
        opened.remove(p);
        p.awardStat(net.minecraft.stats.Stats.OPEN_CHEST);
        return true;
    }
    static void checkMenu(ServerPlayer p){
        var open=opened.get(p);if(open==null)return;
        if(p.containerMenu==p.inventoryMenu || !p.isAlive() || p.level()!=open.level){opened.remove(p);return;}
        boolean valid=p.level().hasChunkAt(open.pos) && p.level().getBlockEntity(open.pos)==open.source;
        if(valid){
            var state=p.level().getBlockState(open.pos);
            valid=cell(p,open.pos,state,false,true);
        }
        if(!valid){
            opened.remove(p);p.closeContainer();
        }
    }
    private NativeRoomBuild(){}
}
