package dev.anya.storage.pocket;

import dev.anya.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
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
        return !special(state) && !RoomEditor.supported(item.getBlock());
    }
    static boolean cell(ServerPlayer p,BlockPos pos,BlockState state,boolean remove,boolean storage){
        var lease=PocketService.lease(p.getUUID());
        if(lease==null || !RoomEditor.active(p) || !p.getAbilities().mayBuild || p.isSpectator() || !p.serverLevel().hasChunkAt(pos))return false;
        var room=PocketService.loadedPocket(p.getServer(),lease.pocket());
        boolean wallFace=false;
        if(room!=null&&state.getBlock() instanceof WallMonitorBlock){
            // BlockPlaceContext has not resolved FACING yet in the initial item-use event.
            for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){
                if(WallMonitorBlock.shellFace(room,pos,direction)&&p.serverLevel().hasChunkAt(pos.relative(direction.getOpposite()))
                        &&p.serverLevel().getBlockState(pos.relative(direction.getOpposite())).isFaceSturdy(p.serverLevel(),pos.relative(direction.getOpposite()),direction)){
                    wallFace=true;break;
                }
            }
        }
        if(room==null || !wallFace&&!RoomBounds.decoration(pos.getX()-room.x(),pos.getY(),pos.getZ()-room.z())
                || p.serverLevel().getBlockState(pos.below()).is(Content.CRATE.get()))return false;
        int rights=BasePermissions.BUILD | (storage?BasePermissions.LEGACY:0);
        return PocketService.authorize(p,room,PocketLeases.Kind.VISIT,false,rights).isEmpty()
                && Safety.buildReason(p,p.serverLevel(),pos,state,remove).isEmpty() && RoomEditor.active(p);
    }
    static boolean place(ServerPlayer p,BlockEvent.EntityPlaceEvent e){
        if(e instanceof BlockEvent.EntityMultiPlaceEvent multi){
            for(var snapshot:multi.getReplacedBlockSnapshots()){
                var pos=snapshot.getPos();var state=p.serverLevel().getBlockState(pos);
                if(special(snapshot.getState()) || special(state) || !cell(p,pos,state,false,state.hasBlockEntity()))return false;
            }
            return true;
        }
        return !special(e.getBlockSnapshot().getState()) && !special(e.getPlacedBlock())
                && cell(p,e.getPos(),e.getPlacedBlock(),false,e.getPlacedBlock().hasBlockEntity());
    }
    static boolean breakable(ServerPlayer p,BlockPos pos){
        var level=p.serverLevel();var state=level.getBlockState(pos);
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
    private record Open(BlockPos pos,net.minecraft.world.level.Level level){}
    private static final java.util.Map<ServerPlayer,Open> opened=new java.util.WeakHashMap<>();
    static void watch(ServerPlayer p,BlockPos pos){opened.put(p,new Open(pos.immutable(),p.level()));}
    static void checkMenu(ServerPlayer p){
        var open=opened.get(p);if(open==null)return;
        if(p.containerMenu==p.inventoryMenu || !p.isAlive() || p.level()!=open.level){opened.remove(p);return;}
        if(!p.level().hasChunkAt(open.pos) || !cell(p,open.pos,p.level().getBlockState(open.pos),false,true)){
            opened.remove(p);p.closeContainer();
        }
    }
    private NativeRoomBuild(){}
}
