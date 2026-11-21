package dev.anya.storage.pocket;
import net.minecraft.core.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.*;

final class SkyCameraBlock extends CameraDevices.DeviceBlock {
    private static final VoxelShape SHAPE=Shapes.or(Block.box(4.5,0,4.5,11.5,3.5,11.5),Block.box(5.5,3.5,5.5,10.5,4.5,10.5));
    SkyCameraBlock(){registerDefaultState(defaultBlockState().setValue(BlockStateProperties.FACING,Direction.UP));}
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx){
        var support=ctx.getClickedPos().below();
        return ctx.getClickedFace()==Direction.UP&&ctx.getLevel().getBlockState(support).isFaceSturdy(ctx.getLevel(),support,Direction.UP)?defaultBlockState():null;
    }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter level,BlockPos pos,CollisionContext ctx){return SHAPE;}
}
