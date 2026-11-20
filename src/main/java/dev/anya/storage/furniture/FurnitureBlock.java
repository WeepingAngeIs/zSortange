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

public final class FurnitureBlock extends BaseEntityBlock {
    private static final MapCodec<FurnitureBlock> CODEC = simpleCodec(FurnitureBlock::new);
    private static final VoxelShape GLOBE_SHAPE = Shapes.or(
            Block.box(2.5,0,2.5,13.5,3,13.5), Block.box(3,3,3,13,5,13),
            Block.box(2,5,2,14,7,14), Block.box(1.5,7,1.5,14.5,11,14.5),
            Block.box(2.25,11,2.25,13.75,13,13.75), Block.box(3.5,13,3.5,12.5,14.5,12.5),
            Block.box(5.5,14.5,5.5,10.5,15.25,10.5));
    private static final VoxelShape HATCH_SHAPE = Block.box(1, 0, 1, 15, 15, 15);

    public static FurnitureBlock createGlobe() {
        return new FurnitureBlock(Properties.of().strength(0.6f).sound(SoundType.GLASS)
                .noOcclusion().pushReaction(PushReaction.BLOCK));
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
        return state.is(Content.SHELF.get()) ? GLOBE_SHAPE : HATCH_SHAPE;
    }
    @Override public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        return state.is(Content.SHELF.get()) && level.getBlockEntity(pos) instanceof FurnitureEntity f && !f.sealedKeepsake()
                && super.canEntityDestroy(state,level,pos,entity);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moving) {
        if (!level.isClientSide && state.is(Content.SHELF.get()) && !next.is(this)
                && level.getBlockEntity(pos) instanceof FurnitureEntity globe) {
            ItemStack keepsake=globe.inventory.removeItemNoUpdate(0);
            if (!keepsake.isEmpty()) net.minecraft.world.Containers.dropItemStack(level,pos.getX(),pos.getY(),pos.getZ(),keepsake);
        }
        super.onRemove(state,level,pos,next,moving);
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        // An operator's explicit NBT placement can already carry an owner; preserve that record.
        if (!state.is(Content.SHELF.get()) && placer instanceof ServerPlayer owner
                && level.getBlockEntity(pos) instanceof FurnitureEntity furniture && furniture.owner==null)
            furniture.initialize(owner.getUUID(), null);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (state.is(Content.SHELF.get()) && level.getBlockEntity(pos) instanceof FurnitureEntity globe) {
            // Let the actual offhand click reach claim listeners. Both sides make the same
            // routing decision even when a migrated private display has no client model.
            if (hand==InteractionHand.MAIN_HAND && stack.isEmpty() && !player.getOffhandItem().isEmpty())
                return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
            if (player instanceof ServerPlayer p) {
                var transferHand=hand==InteractionHand.OFF_HAND && p.getMainHandItem().isEmpty() && !globe.inventory.isEmpty()
                        ? InteractionHand.MAIN_HAND : hand;
                Furniture.useGlobe(p,globe,transferHand);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer p && level.getBlockEntity(pos) instanceof FurnitureEntity f)
            Furniture.use(p, f);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer p && level.getBlockEntity(pos) instanceof FurnitureEntity f) Furniture.use(p, f);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
