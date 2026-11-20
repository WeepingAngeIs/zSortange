package dev.anya.storage;

import com.mojang.serialization.MapCodec;
import dev.anya.storage.pocket.PocketService;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

public final class PlacedBackpackBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final MapCodec<PlacedBackpackBlock> CODEC = simpleCodec(PlacedBackpackBlock::new);
    private static final VoxelShape NORTH_SOUTH = Block.box(1,0,3.5,15,15,12.5);
    private static final VoxelShape EAST_WEST = Block.box(3.5,0,1,12.5,15,15);
    public static PlacedBackpackBlock create() {
        return new PlacedBackpackBlock(BlockBehaviour.Properties.of().strength(.8f,1200).noOcclusion()
                .pushReaction(PushReaction.BLOCK).noLootTable().sound(SoundType.WOOL));
    }
    private PlacedBackpackBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));
    }
    @Override protected MapCodec<PlacedBackpackBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING); }
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state) { return new PlacedBackpackEntity(pos,state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.ENTITYBLOCK_ANIMATED; }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) {
        return state.getValue(FACING).getAxis()==Direction.Axis.X ? EAST_WEST : NORTH_SOUTH;
    }
    @Override public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, Entity entity) { return false; }
    @Override protected void onExplosionHit(BlockState state, Level level, BlockPos pos, Explosion explosion, BiConsumer<ItemStack,BlockPos> drops) {}

    /** Run before block use, outside vanilla's creative count restoration. A filled creative bag moves exactly once. */
    public static InteractionResult place(UseOnContext context) {
        var actor = context.getPlayer();
        var level = context.getLevel();
        var held = context.getItemInHand();
        if (actor==null || !actor.isShiftKeyDown() || !BackpackAccess.isBag(held)) return InteractionResult.PASS;
        var placement = new BlockPlaceContext(context);
        BlockPos pos = placement.getClickedPos();
        var state = Content.PLACED_BACKPACK.get().defaultBlockState().setValue(FACING,context.getHorizontalDirection().getOpposite());
        if (PocketService.inPocket(level) || !level.isInWorldBounds(pos) || !level.hasChunkAt(pos) || !placement.canPlace()
                || !level.getFluidState(pos).isEmpty() || level.getBlockEntity(pos)!=null
                || !level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP)
                || !level.isUnobstructed(state,pos,CollisionContext.of(actor))) return InteractionResult.FAIL;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(actor instanceof ServerPlayer player) || !player.getAbilities().mayBuild || player.containerMenu!=player.inventoryMenu
                || !player.canInteractWithBlock(pos,1) || !level.mayInteract(player,pos)) return InteractionResult.FAIL;
        var original = level.getBlockState(pos);
        String reason = Safety.buildReason(player,player.serverLevel(),pos,state,false);
        if (!reason.isEmpty()) { PlacedBackpackEntity.hint(player,reason); return InteractionResult.FAIL; }
        var hand = context.getHand();
        if (player.getItemInHand(hand)!=held || !BackpackAccess.unique(player,held) || level.getBlockState(pos)!=original)
            return InteractionResult.FAIL;
        var snapshot = BlockSnapshot.create(level.dimension(),level,pos);
        if (!level.setBlock(pos,state,Block.UPDATE_CLIENTS)) return InteractionResult.FAIL;
        if (!(level.getBlockEntity(pos) instanceof PlacedBackpackEntity placed)) {
            if (level.getBlockState(pos)==state) snapshot.restore();
            return InteractionResult.FAIL;
        }
        // Other mods get the normal placement veto before the backpack leaves the hand or is visible at this position.
        if (EventHooks.onBlockPlace(player,snapshot,context.getClickedFace()) || level.getBlockEntity(pos)!=placed
                || player.level()!=level || !player.isAlive() || player.isSpectator() || player.getItemInHand(hand)!=held
                || !BackpackAccess.isBag(held) || !BackpackAccess.unique(player,held)) {
            if (level.getBlockEntity(pos)==placed) snapshot.restore();
            return InteractionResult.FAIL;
        }
        player.setItemInHand(hand,ItemStack.EMPTY);
        placed.place(player,held);
        player.getInventory().setChanged();
        level.updateNeighborsAt(pos,state.getBlock());
        level.gameEvent(player,net.minecraft.world.level.gameevent.GameEvent.BLOCK_PLACE,pos);
        level.playSound(null,pos,SoundType.WOOL.getPlaceSound(),net.minecraft.sounds.SoundSource.BLOCKS,.8f,1f);
        return InteractionResult.CONSUME;
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack input,BlockState state,Level level,BlockPos pos,
            Player actor,InteractionHand hand,BlockHitResult hit) {
        if (actor instanceof ServerPlayer player && level.getBlockEntity(pos) instanceof PlacedBackpackEntity bag)
            bag.interact(player,hand);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player actor,BlockHitResult hit) {
        if (actor instanceof ServerPlayer player && level.getBlockEntity(pos) instanceof PlacedBackpackEntity bag)
            bag.interact(player,InteractionHand.MAIN_HAND);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public boolean onDestroyedByPlayer(BlockState state,Level level,BlockPos pos,Player actor,boolean harvest,FluidState fluid) {
        if (actor instanceof ServerPlayer p && (!(level.getBlockEntity(pos) instanceof PlacedBackpackEntity bag) || !bag.allowed(p,true) || !bag.drop()
                || level.getBlockEntity(pos)!=bag))
            return false;
        return super.onDestroyedByPlayer(state,level,pos,actor,harvest,fluid);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moving) {
        if (state.getBlock()!=next.getBlock() && level.getBlockEntity(pos) instanceof PlacedBackpackEntity bag) bag.drop();
        super.onRemove(state,level,pos,next,moving);
    }
}
