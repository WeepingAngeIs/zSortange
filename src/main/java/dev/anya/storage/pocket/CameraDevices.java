package dev.anya.storage.pocket;

import dev.anya.storage.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class CameraDevices {
    public static final DeferredBlock<DeviceBlock> CAMERA=device("outdoor_camera"), WALL=device("wall_monitor"), SKY=device("skyglass");
    private static DeferredBlock<DeviceBlock> device(String id){
        var block=Content.BLOCKS.register(id,()->new DeviceBlock());
        Content.ITEMS.registerSimpleBlockItem(block);return block;
    }
    private static final DeferredRegister<BlockEntityType<?>> TYPES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,AnyaStorage.ID);
    public static final java.util.function.Supplier<BlockEntityType<Device>> TYPE=TYPES.register("camera_device",()->BlockEntityType.Builder.of(Device::new,CAMERA.get(),WALL.get(),SKY.get()).build(null));
    public static void register(IEventBus bus){TYPES.register(bus);}
    public static final class DeviceBlock extends Block implements EntityBlock{
        DeviceBlock(){super(BlockBehaviour.Properties.of().strength(2).noOcclusion());registerDefaultState(defaultBlockState().setValue(BlockStateProperties.FACING,Direction.NORTH));}
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(BlockStateProperties.FACING);}
        @Override public BlockState getStateForPlacement(BlockPlaceContext c){
            Direction face=this==SKY.get()?Direction.DOWN:c.getClickedFace();
            if(this==WALL.get() && !face.getAxis().isHorizontal())face=c.getHorizontalDirection().getOpposite();
            return defaultBlockState().setValue(BlockStateProperties.FACING,face);
        }
        public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new Device(p,s);}
        @Override public void setPlacedBy(Level l,BlockPos pos,BlockState s,LivingEntity user,ItemStack stack){
            if(user instanceof ServerPlayer p && l.getBlockEntity(pos) instanceof Device d){d.owner=p.getUUID();d.identity=UUID.randomUUID();d.setChanged();}
        }
    }
    public static final class Device extends BlockEntity{
        UUID owner,identity,linked;String dimension="";BlockPos camera=BlockPos.ZERO;boolean glances;
        public Device(BlockPos p,BlockState s){super(TYPE.get(),p,s);}
        public boolean owned(ServerPlayer p){return p.getUUID().equals(owner);}
        @Override public boolean onlyOpCanSetNbt(){return true;}
        protected void saveAdditional(CompoundTag n,HolderLookup.Provider r){
            super.saveAdditional(n,r);if(owner!=null)n.putUUID("Owner",owner);if(identity!=null)n.putUUID("Identity",identity);
            if(linked!=null){n.putUUID("Camera",linked);n.putLong("Position",camera.asLong());n.putString("Level",dimension);}n.putBoolean("Glances",glances);
        }
        protected void loadAdditional(CompoundTag n,HolderLookup.Provider r){
            super.loadAdditional(n,r);owner=n.hasUUID("Owner")?n.getUUID("Owner"):null;identity=n.hasUUID("Identity")?n.getUUID("Identity"):null;
            linked=n.hasUUID("Camera")?n.getUUID("Camera"):null;dimension=n.getString("Level");camera=BlockPos.of(n.getLong("Position"));glances=n.getBoolean("Glances");
        }
    }
    private record Link(String world,BlockPos pos,UUID camera){}
    private static final Map<ServerPlayer,Link> selected=new WeakHashMap<>();
    static boolean use(ServerPlayer p,Device d,InteractionHand hand){
        if(!d.owned(p) || !Safety.locationReason(p,p.serverLevel(),d.getBlockPos()).isEmpty())return false;
        if(PocketService.inPocket(p.level())){
            var l=PocketService.lease(p.getUUID());if(l==null || !PocketService.visitingOwner(p,l.pocket()))return false;
        }
        String msg;
        if(StorageItem.isTablet(p.getItemInHand(hand)) && p.isShiftKeyDown()){
            if(d.getBlockState().is(CAMERA.get())){
                if(PocketService.inPocket(p.level()))msg="Place the camera outdoors.";
                else{selected.put(p,new Link(p.level().dimension().location().toString(),d.getBlockPos(),d.identity));msg="Camera selected. Sneak-use your tablet on a monitor.";}
            }else{
                var link=selected.get(p);
                if(link==null)msg="Sneak-use your tablet on your outdoor camera first.";
                else{d.linked=link.camera;d.dimension=link.world;d.camera=link.pos;d.setChanged();msg="Monitor linked to your camera.";}
            }
        }else if(d.getBlockState().is(CAMERA.get()) && p.isShiftKeyDown() && p.getItemInHand(hand).isEmpty()){
            d.glances=!d.glances;d.setChanged();msg="Nearby mob glances "+(d.glances?"on":"off")+".";
        }else msg=d.getBlockState().is(CAMERA.get())?"Sneak-use a tablet to select this camera. Sneak-use an empty hand to toggle mob glances.":"Look at the screen to view. Sneak-use your tablet to link a camera.";
        p.displayClientMessage(Component.literal(msg),true);return true;
    }
}
