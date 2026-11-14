package dev.anya.storage;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
public final class StorageBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public final int kind;
    public StorageBlock(int kind,Properties p){super(p);this.kind=kind;registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return simpleCodec(p->new StorageBlock(kind,p));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected RenderShape getRenderShape(BlockState s){return kind==1?RenderShape.ENTITYBLOCK_ANIMATED:RenderShape.MODEL;}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return kind==1?Block.box(1,0,1,15,14,15):Shapes.block();}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new StorageBlockEntity(p,s);}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState state,BlockEntityType<T> type){return createTickerHelper(type,AnyaStorage.STORAGE_ENTITY.get(),StorageBlockEntity::tick);}
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,LivingEntity placer,ItemStack stack){
        if(!level.isClientSide && placer instanceof Player p && level.getBlockEntity(pos) instanceof StorageBlockEntity b){b.owner=p.getUUID();b.name=stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)?stack.getHoverName().getString():"Anya Base";b.changed();}
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player p,InteractionHand hand,BlockHitResult hit){
        if(kind==0 && stack.getItem() instanceof RemoteItem && p.isShiftKeyDown()){
            if(p instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof StorageBlockEntity b)RemoteItem.bind(sp,stack,b);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player p,BlockHitResult hit){
        if(p instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof StorageBlockEntity b){
            if(b.allowed(sp)&&Claims.allowed(sp,pos)){if(b.owner==null){b.owner=p.getUUID();b.changed();}StorageMenu.openBlock(sp,b);}
            else AnyaStorage.tell(sp,"This storage is private or protected by a claim.");
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moved){
        if(!state.is(next.getBlock())){
            if(!level.isClientSide && level.getBlockEntity(pos) instanceof StorageBlockEntity b && kind!=0)Containers.dropContents(level,pos,b.inventory);
            super.onRemove(state,level,pos,next,moved);
        }
    }
}
