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
    public static final DeferredBlock<DeviceBlock> CAMERA=device("outdoor_camera",OutdoorCameraBlock::new), WALL=device("wall_monitor",()->new WallMonitorBlock(3,3)),
            WIDE=device("wide_wall_monitor",()->new WallMonitorBlock(9,5)),CINEMA=device("cinema_wall_monitor",()->new WallMonitorBlock(32,32)),
            SKY_CAMERA=device("sky_camera",SkyCameraBlock::new), SKY=device("skyglass",DeviceBlock::new),
            TV=device("flat_screen_tv",FlatScreenTvBlock::new);
    private static DeferredBlock<DeviceBlock> device(String id,java.util.function.Supplier<DeviceBlock> factory){
        var block=Content.BLOCKS.register(id,factory);
        Content.ITEMS.registerSimpleBlockItem(block);return block;
    }
    private static final DeferredRegister<BlockEntityType<?>> TYPES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,AnyaStorage.ID);
    public static final java.util.function.Supplier<BlockEntityType<Device>> TYPE=TYPES.register("camera_device",()->BlockEntityType.Builder.of(Device::new,CAMERA.get(),WALL.get(),WIDE.get(),CINEMA.get(),SKY_CAMERA.get(),SKY.get(),TV.get()).build(null));
    public static void register(IEventBus bus){TYPES.register(bus);}
    public static class DeviceBlock extends Block implements EntityBlock{
        DeviceBlock(){super(BlockBehaviour.Properties.of().strength(2).noOcclusion());registerDefaultState(defaultBlockState().setValue(BlockStateProperties.FACING,Direction.NORTH));}
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(BlockStateProperties.FACING);}
        @Override public BlockState getStateForPlacement(BlockPlaceContext c){
            Direction face=this==SKY.get()?Direction.DOWN:this==SKY_CAMERA.get()?Direction.UP:c.getClickedFace();
            if(this==WALL.get() && !face.getAxis().isHorizontal())face=c.getHorizontalDirection().getOpposite();
            return defaultBlockState().setValue(BlockStateProperties.FACING,face);
        }
        public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new Device(p,s);}
        @Override public void setPlacedBy(Level l,BlockPos pos,BlockState s,LivingEntity user,ItemStack stack){
            if(user instanceof ServerPlayer p && l.getBlockEntity(pos) instanceof Device d){d.owner=p.getUUID();d.identity=UUID.randomUUID();
                if(s.is(CAMERA.get())&&s.getValue(BlockStateProperties.FACING).getAxis().isHorizontal()){
                    d.tracking.mode=CameraTracking.Mode.MOTION;d.tracking.group=CameraTracking.Group.HOSTILE;d.tracking.rest=0;
                }
                d.setChanged();}
        }
    }
    public static final class Device extends BlockEntity{
        UUID owner,identity,linked;String dimension="";BlockPos camera=BlockPos.ZERO;final CameraTracking tracking=new CameraTracking();
        WallPanel panel=WallPanel.ONE;
        void panel(WallPanel next){if(next.equals(panel))return;WallPanel.index(this,false);panel=next;WallPanel.index(this,true);}
        @Override public void onLoad(){super.onLoad();WallPanel.index(this,true);}
        @Override public void setRemoved(){WallPanel.index(this,false);super.setRemoved();}
        @Override public CompoundTag getUpdateTag(HolderLookup.Provider r){
            var n=new CompoundTag();if(getBlockState().getBlock() instanceof WallMonitorBlock)n.put("Panel",panel.save());return n;
        }
        @Override public void handleUpdateTag(CompoundTag n,HolderLookup.Provider r){
            if(getBlockState().getBlock() instanceof WallMonitorBlock t)panel(WallPanel.read(n.getCompound("Panel"),t));
        }
        @Override public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket(){
            return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
        }
        @Override public void onDataPacket(net.minecraft.network.Connection c,net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket packet,HolderLookup.Provider r){
            if(packet.getTag()!=null)handleUpdateTag(packet.getTag(),r);
        }
        public Device(BlockPos p,BlockState s){super(TYPE.get(),p,s);}
        public boolean owned(ServerPlayer p){return p.getUUID().equals(owner);}
        @Override public boolean onlyOpCanSetNbt(){return true;}
        protected void saveAdditional(CompoundTag n,HolderLookup.Provider r){
            super.saveAdditional(n,r);if(owner!=null)n.putUUID("Owner",owner);if(identity!=null)n.putUUID("Identity",identity);
            if(linked!=null){n.putUUID("Camera",linked);n.putLong("Position",camera.asLong());n.putString("Level",dimension);}tracking.save(n);if(getBlockState().getBlock() instanceof WallMonitorBlock)n.put("Panel",panel.save());
        }
        protected void loadAdditional(CompoundTag n,HolderLookup.Provider r){
            super.loadAdditional(n,r);owner=n.hasUUID("Owner")?n.getUUID("Owner"):null;identity=n.hasUUID("Identity")?n.getUUID("Identity"):null;
            linked=n.hasUUID("Camera")?n.getUUID("Camera"):null;dimension=n.getString("Level");camera=BlockPos.of(n.getLong("Position"));tracking.load(n);if(getBlockState().getBlock() instanceof WallMonitorBlock t)panel(WallPanel.read(n.getCompound("Panel"),t));
        }
    }
    private record Link(String world,BlockPos pos,UUID camera){}
    private static final Map<ServerPlayer,Link> selected=new WeakHashMap<>();
    static String linkSelected(ServerPlayer p,Device d){
        var link=selected.get(p);
        if(link==null)return "Sneak-use your tablet on your outdoor camera first.";
        var cam=SkyCeiling.resolve(p,link.world,link.pos,link.camera);
        if(!d.owned(p)||d.isRemoved()||d.getLevel()!=p.level()||!Safety.buildReason(p,p.serverLevel(),d.getBlockPos(),d.getBlockState(),false).isEmpty()
                ||cam==null)return "That camera or monitor is unavailable.";
        if(d.getBlockState().is(SKY.get())&&CameraFeed.direction(cam)!=Direction.UP)return "Choose an upward camera for Skyglass.";
        d.linked=link.camera;d.dimension=link.world;d.camera=link.pos;d.setChanged();return "Monitor linked to your camera.";
    }
    static boolean isCamera(BlockState s){return s.is(CAMERA.get())||s.is(SKY_CAMERA.get());}
    static boolean use(ServerPlayer p,Device d,InteractionHand hand){
        if(!d.owned(p) || !Safety.locationReason(p,p.serverLevel(),d.getBlockPos()).isEmpty())return false;
        if(PocketService.inPocket(p.level())){
            var l=PocketService.lease(p.getUUID());if(l==null || !PocketService.visitingOwner(p,l.pocket()))return false;
        }
        String msg;
        if(StorageItem.isTablet(p.getItemInHand(hand)) && p.isShiftKeyDown()){
            if(isCamera(d.getBlockState())){
                if(PocketService.inPocket(p.level()))msg="Place the camera outdoors.";
                else if(d.getBlockState().is(SKY_CAMERA.get()) && !SkyCeiling.remember(p,d))msg="Sky Camera unavailable.";
                else{
                    selected.put(p,new Link(p.level().dimension().location().toString(),d.getBlockPos(),d.identity));
                    msg=d.getBlockState().is(SKY_CAMERA.get())
                            ?"Sky Camera selected. In Skygrid, open Cosmetics on your tablet and turn on Live ceiling."
                            :"Camera selected. Sneak-use your tablet on a monitor to link it.";
                }
            }else{
                var link=selected.get(p);
                if(link==null)msg="Sneak-use your tablet on your outdoor camera first.";
                else msg=linkSelected(p,d);
            }
        }else if(d.getBlockState().is(CAMERA.get()) && p.isShiftKeyDown() && p.getItemInHand(hand).isEmpty()){
            CameraConsole.open(p,d);return true;
        }else if(d.getBlockState().is(CAMERA.get()) && p.getItemInHand(hand).isEmpty()){
            var state=d.getBlockState();
            if(PocketService.inPocket(p.level())){
                msg="Mount this camera outdoors to aim it.";
            }else if(!state.getValue(BlockStateProperties.FACING).getAxis().isHorizontal()){
                msg="This camera looks "+OutdoorCameraBlock.lensDirection(state).getName()+". Mount it on a wall to swivel it.";
            }else{
                int home=d.tracking.rest<0?state.getValue(OutdoorCameraBlock.PAN):d.tracking.rest;
                var next=state.setValue(OutdoorCameraBlock.PAN,(home+1)%3);
                String denied=Safety.buildReason(p,p.serverLevel(),d.getBlockPos(),next,false);
                if(!denied.isEmpty())msg=denied;
                else if(next==state||p.serverLevel().setBlockAndUpdate(d.getBlockPos(),next)){
                    d.tracking.clearMotion();d.tracking.rest=next.getValue(OutdoorCameraBlock.PAN);d.setChanged();
                    msg="Lens facing "+OutdoorCameraBlock.lensDirection(next).getName()+".";
                }
                else msg="The camera could not turn.";
            }
        }else if(d.getBlockState().getBlock() instanceof WallMonitorBlock
                && (p.getItemInHand(hand).isEmpty() || StorageItem.isTablet(p.getItemInHand(hand)))){
            CameraConsole.open(p,d);return true;
        }else if(d.getBlockState().is(SKY_CAMERA.get())){
            msg="Sneak-use your tablet on this Sky Camera to select it.";
        }else msg=d.getBlockState().is(CAMERA.get())?"Right-click a wall camera with an empty hand to swivel. Sneak-use a tablet to link it, or sneak-use an empty hand for tracking settings.":"Look to view. Right-click a wall monitor for settings. Sneak-use your tablet to link a camera.";
        p.displayClientMessage(Component.literal(msg),true);return true;
    }
}
