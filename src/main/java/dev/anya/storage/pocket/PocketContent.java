package dev.anya.storage.pocket;

import com.mojang.serialization.MapCodec;
import dev.anya.storage.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;
import java.util.*;
import java.util.function.Supplier;

/** Separate entrance, not the existing 216-slot UwU inventory cube. */
public final class PocketContent {
    public static final DeferredBlock<EntranceBlock> ENTRANCE=Content.BLOCKS.register("home_void_cube",()->new EntranceBlock(BlockBehaviour.Properties.of().strength(8,3600000).lightLevel(s->6).pushReaction(PushReaction.BLOCK)));
    public static final DeferredItem<BlockItem> ITEM=Content.ITEMS.register("home_void_cube",()->new BlockItem(ENTRANCE.get(),new Item.Properties().stacksTo(1)){
        @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag){
            lines.add(Component.literal("Experimental home entrance, not an inventory cube."));
            lines.add(Component.literal("Look directly at any face to see the starter-room illustration."));
            lines.add(Component.literal("With View access, a loaded pocket can show its chest layout."));
            lines.add(Component.literal("Sneak-use a bound Remote or Cupid Tablet to link. Right-click to visit."));
            lines.add(Component.literal("Requires experimental server opt-in. See Ancient Technology for live status."));
        }
    });
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,AnyaStorage.ID);
    private static final DeferredRegister<RecipeSerializer<?>> RECIPES=DeferredRegister.create(Registries.RECIPE_SERIALIZER,AnyaStorage.ID);
    public static final Supplier<BlockEntityType<EntranceEntity>> ENTITY=ENTITIES.register("home_void_cube",()->BlockEntityType.Builder.of(EntranceEntity::new,ENTRANCE.get()).build(null));
    public static final Supplier<RecipeSerializer<EntranceRecipe>> RECIPE=RECIPES.register("home_void_cube",()->new SimpleCraftingRecipeSerializer<>(EntranceRecipe::new));
    public static void register(IEventBus bus){ENTITIES.register(bus);RECIPES.register(bus);}
    public static final class EntranceBlock extends BaseEntityBlock {
        public static final MapCodec<EntranceBlock> CODEC=simpleCodec(EntranceBlock::new);
        public EntranceBlock(Properties properties){super(properties);}
        @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
        @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
        @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new EntranceEntity(pos,state);}
        @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,LivingEntity placer,ItemStack stack){
            if(!level.isClientSide&&placer instanceof ServerPlayer p&&level.getBlockEntity(pos) instanceof EntranceEntity e){e.owner=p.getUUID();e.setChanged();}
        }
        @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
            if(player instanceof ServerPlayer p&&level.getBlockEntity(pos) instanceof EntranceEntity e)PocketService.enter(p,e);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        @Override public void animateTick(BlockState state,Level level,BlockPos pos,RandomSource random){
            // Local decorative fallback: never requests a destination, network payload or chunk ticket.
            if(level.isClientSide)PocketPreviewParticles.emit(level,pos,random);
        }
    }
    public static final class EntranceEntity extends BlockEntity {
        public UUID owner,pocket;
        public EntranceEntity(BlockPos pos,BlockState state){super(ENTITY.get(),pos,state);}
        @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registry){super.saveAdditional(tag,registry);if(owner!=null)tag.putUUID("Owner",owner);if(pocket!=null)tag.putUUID("PocketId",pocket);}
        @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registry){super.loadAdditional(tag,registry);owner=tag.hasUUID("Owner")?tag.getUUID("Owner"):null;pocket=tag.hasUUID("PocketId")?tag.getUUID("PocketId"):null;}
    }
    /** Nine ordinary ingredients; neither a hub nor a filled container is consumed. */
    public static final class EntranceRecipe extends CustomRecipe {
        public EntranceRecipe(CraftingBookCategory category){super(category);}
        private static String id(ItemStack s){return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString();}
        public static Map<String,Integer> costs(){
            var result=new TreeMap<String,Integer>();result.merge("minecraft:obsidian",4,Integer::sum);result.merge("minecraft:ender_pearl",2,Integer::sum);result.merge("minecraft:amethyst_shard",2,Integer::sum);result.merge(ExpansionRules.get().pocketCoreMaterial,1,Integer::sum);return result;
        }
        @Override public boolean matches(CraftingInput input,Level level){
            if(!PocketService.enabled()||input.size()!=9)return false;var counts=new TreeMap<String,Integer>();
            for(int i=0;i<input.size();i++){
                ItemStack s=input.getItem(i);if(s.isEmpty()||s.getItem() instanceof StorageItem||s.has(net.minecraft.core.component.DataComponents.CONTAINER)||s.has(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA))return false;
                counts.merge(id(s),1,Integer::sum);
            }
            return counts.equals(costs());
        }
        @Override public ItemStack assemble(CraftingInput input,HolderLookup.Provider registry){return new ItemStack(ITEM.get());}
        @Override public boolean canCraftInDimensions(int width,int height){return width==3&&height==3;}
        @Override public RecipeSerializer<?> getSerializer(){return RECIPE.get();}
        @Override public ItemStack getResultItem(HolderLookup.Provider registry){return new ItemStack(ITEM.get());}
    }
    private PocketContent(){}
}
