package dev.anya.storage.pocket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A seated display with the same source controls as a wall panel. North is its model's front. */
final class FlatScreenTvBlock extends WallMonitorBlock {
    private static final VoxelShape FOOT = Block.box(3, 0, 5, 13, 1, 11);
    private static final VoxelShape POST = Block.box(7, 1, 7, 9, 4, 9);

    FlatScreenTvBlock() { super(1, 1); }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var level = context.getLevel();
        var pos = context.getClickedPos();
        if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return null;
        for (var screen : WallPanel.at(level, pos))
            if (screen.panel.covers(screen, pos)) return null;
        return defaultBlockState().setValue(BlockStateProperties.FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        boolean eastWest = state.getValue(BlockStateProperties.FACING).getAxis() == Direction.Axis.X;
        var face = eastWest ? Block.box(7, 4, 0, 9, 14, 16) : Block.box(0, 4, 7, 16, 14, 9);
        var foot = eastWest ? Block.box(5, 0, 3, 11, 1, 13) : FOOT;
        return Shapes.or(face, foot, POST);
    }
}
