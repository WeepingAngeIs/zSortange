package dev.anya.storage;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.*;
import java.util.*;
import java.util.function.Supplier;

public final class Content {
    public static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(AnyaStorage.ID);
    public static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(AnyaStorage.ID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, AnyaStorage.ID);
    public static final Supplier<SoundEvent> CHEST_OPEN = chestSound("chest_open");
    public static final Supplier<SoundEvent> CHEST_CLOSE = chestSound("chest_close");
    public static final Supplier<SoundEvent> CUBE_OPEN = chestSound("cube_open");
    public static final Supplier<SoundEvent> CUBE_CLOSE = chestSound("cube_close");

    private static Supplier<SoundEvent> chestSound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createFixedRangeEvent(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, name), 7));
    }
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,AnyaStorage.ID);
    private static final DeferredRegister<MenuType<?>> MENUS=DeferredRegister.create(Registries.MENU,AnyaStorage.ID);
    private static final DeferredRegister<RecipeSerializer<?>> RECIPES=DeferredRegister.create(Registries.RECIPE_SERIALIZER,AnyaStorage.ID);
    private static final DeferredRegister<CreativeModeTab> TABS=DeferredRegister.create(Registries.CREATIVE_MODE_TAB,AnyaStorage.ID);
    private static final DeferredRegister<MapCodec<? extends ICondition>> CONDITIONS=DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS,AnyaStorage.ID);
    public static final DeferredBlock<StorageBlock> HUB=BLOCKS.register("hub",()->new StorageBlock("hub",BlockBehaviour.Properties.of().strength(4,1200).noOcclusion()));
    public static final DeferredBlock<StorageBlock> CRATE=BLOCKS.register("chest",()->new StorageBlock("chest",BlockBehaviour.Properties.of().strength(3,1200).noOcclusion()));
    public static final DeferredBlock<StorageBlock> CUBE=BLOCKS.register("uwu_cube",()->new StorageBlock("cube",BlockBehaviour.Properties.of().strength(6,1200).noOcclusion()));
    public static final DeferredBlock<dev.anya.storage.furniture.FurnitureBlock> SHELF = BLOCKS.register("keepsake_shelf", dev.anya.storage.furniture.FurnitureBlock::createGlobe);
    public static final DeferredBlock<dev.anya.storage.furniture.FurnitureBlock> HATCH = BLOCKS.register("delivery_hatch", dev.anya.storage.furniture.FurnitureBlock::create);
    public static final Supplier<BlockEntityType<dev.anya.storage.furniture.FurnitureEntity>> FURNITURE = ENTITIES.register("furniture",
            () -> BlockEntityType.Builder.of(dev.anya.storage.furniture.FurnitureEntity::new, SHELF.get(), HATCH.get()).build(null));
    public static final DeferredItem<StorageItem> BACKPACK=ITEMS.register("backpack",()->new StorageItem("backpack",new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<PlacedBackpackBlock> PLACED_BACKPACK = BLOCKS.register("placed_backpack", PlacedBackpackBlock::create);
    public static final Supplier<BlockEntityType<PlacedBackpackEntity>> PLACED_BAG_ENTITY = ENTITIES.register("placed_backpack",
            () -> BlockEntityType.Builder.of(PlacedBackpackEntity::new, PLACED_BACKPACK.get()).build(null));
    // Old chests and player saves still deserialize this ID. It converts on first carry or use.
    public static final DeferredItem<StorageItem> REMOTE=ITEMS.register("remote",()->new StorageItem("legacy_remote",new Item.Properties().stacksTo(1)));
    public static final DeferredItem<StorageItem> CUPID_TABLET = ITEMS.register("cupid_tablet",
            () -> new StorageItem("tablet", new Item.Properties().stacksTo(1)));
    public static final DeferredItem<StorageItem> CREATIVE_TABLET = ITEMS.register("creative_tablet",
            () -> new StorageItem("creative_tablet", new Item.Properties().stacksTo(1)));
    public static final DeferredItem<dev.anya.storage.guide.GuideItem> GUIDE=ITEMS.register("ancient_technology",()->new dev.anya.storage.guide.GuideItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> CORE=ITEMS.registerSimpleItem("storage_core");
    public static final DeferredItem<Item> DRAGON_TOKEN=ITEMS.registerSimpleItem("dragon_victory_token");
    public static final Map<String,DeferredItem<Item>> STYLES=new LinkedHashMap<>();
    public static final Map<String,DeferredItem<Item>> UPGRADES=new LinkedHashMap<>();
    public static final Supplier<BlockEntityType<StorageEntity>> STORAGE=ENTITIES.register("storage",()->BlockEntityType.Builder.of(StorageEntity::new,HUB.get(),CRATE.get(),CUBE.get()).build(null));
    public static final Supplier<MenuType<StoreMenu>> MENU=MENUS.register("storage",()->new MenuType<>(StoreMenu::new,FeatureFlags.DEFAULT_FLAGS));
    public static final Supplier<RecipeSerializer<UpgradeRecipe>> UPGRADE_RECIPE=RECIPES.register("backpack_upgrade",()->new SimpleCraftingRecipeSerializer<>(UpgradeRecipe::new));
    public static final Supplier<RecipeSerializer<CubeRecipe>> CUBE_RECIPE=RECIPES.register("cube",()->new SimpleCraftingRecipeSerializer<>(CubeRecipe::new));
    public static final Supplier<RecipeSerializer<dev.anya.storage.appearance.StyleRecipe>> STYLE_RECIPE=RECIPES.register("backpack_style",()->new SimpleCraftingRecipeSerializer<>(dev.anya.storage.appearance.StyleRecipe::new));
    private static final DeferredRegister<net.minecraft.core.particles.ParticleType<?>> PARTICLES=DeferredRegister.create(Registries.PARTICLE_TYPE,AnyaStorage.ID);
    public static final Supplier<net.minecraft.core.particles.SimpleParticleType> LEAF_MOTE=PARTICLES.register("leaf_mote",()->new net.minecraft.core.particles.SimpleParticleType(false));
    public static final Supplier<net.minecraft.core.particles.SimpleParticleType> CHERRY_MOTE=PARTICLES.register("cherry_mote",()->new net.minecraft.core.particles.SimpleParticleType(false));
    public static final Supplier<net.minecraft.core.particles.SimpleParticleType> STAR_MOTE=PARTICLES.register("star_mote",()->new net.minecraft.core.particles.SimpleParticleType(false));
    static {
        for(String element:dev.anya.storage.appearance.Looks.ELEMENTS) if(!element.equals("plain"))STYLES.put("element_"+element,ITEMS.registerSimpleItem("element_"+element));
        for(String skin:dev.anya.storage.appearance.Looks.SKINS) if(!skin.equals("elemental"))STYLES.put("skin_"+skin,ITEMS.registerSimpleItem("skin_"+skin));
        ITEMS.registerSimpleBlockItem(HUB); ITEMS.registerSimpleBlockItem(CRATE); ITEMS.registerSimpleBlockItem(CUBE);
        ITEMS.registerSimpleBlockItem(SHELF); ITEMS.registerSimpleBlockItem(HATCH);
        for(String id:Config.DISC_IDS) UPGRADES.put(id,ITEMS.registerSimpleItem(id+"_upgrade",new Item.Properties().stacksTo(16)));
        CONDITIONS.register("recipe_group",()->RecipeCondition.CODEC);
        TABS.register("storage",()->CreativeModeTab.builder().title(Component.literal("Anya Storage"))
            .icon(()->new ItemStack(BACKPACK.get())).displayItems((parameters,out)->{
                ITEMS.getEntries().stream().filter(item -> item.get()!=REMOTE.get()).forEach(item->out.accept(item.get()));
            }).build());
    }
    private Content() {}
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); MENUS.register(bus); RECIPES.register(bus); CONDITIONS.register(bus); TABS.register(bus); PARTICLES.register(bus); SOUNDS.register(bus); }
}
