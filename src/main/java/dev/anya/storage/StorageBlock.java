package dev.anya.storage;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class StorageBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING=HorizontalDirectionalBlock.FACING;
    public static final MapCodec<StorageBlock> CODEC=RecordCodecBuilder.mapCodec(i->i.group(Codec.STRING.fieldOf("kind").forGetter(b->b.kind),propertiesCodec()).apply(i,StorageBlock::new));
    public final String kind;
    public StorageBlock(String kind,Properties properties){super(properties);this.kind=kind;registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new StorageEntity(pos,state);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext context){return defaultBlockState().setValue(FACING,context.getHorizontalDirection().getOpposite());}
    @Override protected RenderShape getRenderShape(BlockState state){return kind.equals("chest")?RenderShape.ENTITYBLOCK_ANIMATED:RenderShape.MODEL;}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return kind.equals("chest")?Block.box(1,0,1,15,14,15):Shapes.block();}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState state,BlockEntityType<T> type){return createTickerHelper(type,Content.STORAGE.get(),StorageEntity::tick);}
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,LivingEntity placer,ItemStack stack){
        super.setPlacedBy(level,pos,state,placer,stack);
        if(!level.isClientSide&&level.getBlockEntity(pos) instanceof StorageEntity be&&placer instanceof ServerPlayer p){be.owner=p.getUUID();be.baseName=p.getGameProfile().getName()+"'s Base";be.profileChanged();}
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(!level.isClientSide&&player instanceof ServerPlayer p&&level.getBlockEntity(pos) instanceof StorageEntity be){
            String reason=Safety.reason(p,be,null);if(!reason.isEmpty()){AnyaStorage.tell(p,reason);return InteractionResult.CONSUME;}
            if(kind.equals("hub"))StoreMenu.network(p,be);else StoreMenu.container(p,be);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moving){
        if(state.getBlock()!=next.getBlock()&&!level.isClientSide&&level.getBlockEntity(pos) instanceof StorageEntity be){Containers.dropContents(level,pos,be);level.updateNeighbourForOutputSignal(pos,this);}
        super.onRemove(state,level,pos,next,moving);
    }
}
