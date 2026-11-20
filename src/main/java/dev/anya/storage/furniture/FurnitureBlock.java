package dev.anya.storage.furniture;

import com.mojang.serialization.MapCodec;
import dev.anya.storage.Content;
import dev.anya.storage.StorageBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
import java.util.EnumMap;
import java.util.Map;

public final class FurnitureBlock extends BaseEntityBlock {
    private static final MapCodec<FurnitureBlock> CODEC = simpleCodec(FurnitureBlock::new);
    private static final Map<Direction,VoxelShape> SHELF_SHAPES = shelfShapes();
    private static final VoxelShape HATCH_SHAPE = Block.box(1, 0, 1, 15, 15, 15);

    private static Map<Direction,VoxelShape> shelfShapes() {
        VoxelShape north = Shapes.or(
                Block.box(0,0,1,16,3,15), Block.box(0,3,2,16,4,14),
                Block.box(2,4,3,14,4.35,12), Block.box(1,4,12,15,12,14),
                Block.box(0,4,12,1,12,15), Block.box(15,4,12,16,12,15),
                Block.box(0,12,12,16,13,15), Block.box(2,.65,.5,14,2.9,1),
                Block.box(1,.8,.45,2,2.75,1), Block.box(14,.8,.45,15,2.75,1));
        var facings = new EnumMap<Direction,VoxelShape>(Direction.class);
        facings.put(Direction.NORTH,north);
        VoxelShape last = north;
        for (Direction facing : new Direction[]{Direction.EAST,Direction.SOUTH,Direction.WEST}) {
            VoxelShape turned = Shapes.empty();
            for (var box : last.toAabbs())
                turned = Shapes.or(turned, Shapes.create(1-box.maxZ,box.minY,box.minX,1-box.minZ,box.maxY,box.maxX));
            facings.put(facing,turned);
            last=turned;
        }
        return facings;
    }

    public static FurnitureBlock create() {
        return new FurnitureBlock(Properties.of().strength(2.5f, 3600000).sound(SoundType.WOOD)
                .noOcclusion().pushReaction(PushReaction.BLOCK));
    }
    private FurnitureBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(StorageBlock.FACING, Direction.NORTH));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> states) {
        states.add(StorageBlock.FACING);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(StorageBlock.FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new FurnitureEntity(pos,state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.is(Content.SHELF.get()) ? SHELF_SHAPES.get(state.getValue(StorageBlock.FACING)) : HATCH_SHAPE;
    }
    @Override public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, Entity entity) { return false; }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        // An operator's explicit NBT placement can already carry an owner; preserve that record.
        if (placer instanceof ServerPlayer owner && level.getBlockEntity(pos) instanceof FurnitureEntity furniture && furniture.owner==null)
            furniture.initialize(owner.getUUID(), null);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer p && level.getBlockEntity(pos) instanceof FurnitureEntity f)
            Furniture.use(p, f);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer p && level.getBlockEntity(pos) instanceof FurnitureEntity f) Furniture.use(p, f);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
