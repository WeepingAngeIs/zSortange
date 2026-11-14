package dev.anya.storage;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.*;
import org.slf4j.Logger;
import java.util.*;

@Mod(AnyaStorage.ID)
public final class AnyaStorage {
    public static final String ID="anyastorage";
    public static final Logger LOG=LogUtils.getLogger();
    public static final DeferredRegister<Block> BLOCKS=DeferredRegister.create(Registries.BLOCK,ID);
    public static final DeferredRegister<Item> ITEMS=DeferredRegister.create(Registries.ITEM,ID);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,ID);
    public static final DeferredRegister<MenuType<?>> MENUS=DeferredRegister.create(Registries.MENU,ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPES=DeferredRegister.create(Registries.RECIPE_SERIALIZER,ID);
    public static final DeferredRegister<CreativeModeTab> TABS=DeferredRegister.create(Registries.CREATIVE_MODE_TAB,ID);
    public static final DeferredHolder<Block,StorageBlock> HUB=BLOCKS.register("storage_hub",()->new StorageBlock(0,props()));
    public static final DeferredHolder<Block,StorageBlock> CRATE=BLOCKS.register("storage_chest",()->new StorageBlock(1,props().noOcclusion()));
    public static final DeferredHolder<Block,StorageBlock> CUBE=BLOCKS.register("uwu_cube",()->new StorageBlock(2,props()));
    public static final DeferredHolder<Item,Item> HUB_ITEM=ITEMS.register("storage_hub",()->new BlockItem(HUB.get(),new Item.Properties()));
    public static final DeferredHolder<Item,Item> CRATE_ITEM=ITEMS.register("storage_chest",()->new BlockItem(CRATE.get(),new Item.Properties()));
    public static final DeferredHolder<Item,Item> CUBE_ITEM=ITEMS.register("uwu_cube",()->new BlockItem(CUBE.get(),new Item.Properties()));
    public static final DeferredHolder<Item,BackpackItem> BACKPACK=ITEMS.register("backpack",()->new BackpackItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item,RemoteItem> REMOTE=ITEMS.register("storage_button",()->new RemoteItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item,Item> CORE=ITEMS.register("storage_core",()->new Item(new Item.Properties()));
    public static final DeferredHolder<Item,Item> TOKEN=ITEMS.register("dragon_victory_token",()->new Item(new Item.Properties().rarity(Rarity.EPIC)));
    public static final Map<String,DeferredHolder<Item,Item>> UPGRADES=new LinkedHashMap<>();
    static { for(String id:Rules.UPGRADE_IDS) UPGRADES.put(id,ITEMS.register(id+"_upgrade",()->new Item(new Item.Properties().stacksTo(1)))); }
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<StorageBlockEntity>> STORAGE_ENTITY=ENTITIES.register("storage",()->BlockEntityType.Builder.of(StorageBlockEntity::new,HUB.get(),CRATE.get(),CUBE.get()).build(null));
    public static final DeferredHolder<MenuType<?>,MenuType<StorageMenu>> STORAGE_MENU=MENUS.register("workbench",()->IMenuTypeExtension.create(StorageMenu::new));
    public static final DeferredHolder<RecipeSerializer<?>,SimpleCraftingRecipeSerializer<StorageRecipe>> STORAGE_RECIPE=RECIPES.register("storage_crafting",()->new SimpleCraftingRecipeSerializer<>(StorageRecipe::new));
    static {
        TABS.register("storage",()->CreativeModeTab.builder().title(Component.literal("Anya's Storage Plus")).icon(()->new ItemStack(BACKPACK.get())).displayItems((p,o)->ITEMS.getEntries().forEach(h->o.accept(h.get()))).build());
    }
    private static BlockBehaviour.Properties props() { return BlockBehaviour.Properties.of().strength(3.5f,1200f).pushReaction(PushReaction.BLOCK); }
    public AnyaStorage(IEventBus bus) {
        BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); MENUS.register(bus); RECIPES.register(bus); TABS.register(bus);
        bus.addListener(Packets::register);
        NeoForge.EVENT_BUS.addListener(AnyaStorage::commands);
        NeoForge.EVENT_BUS.addListener(AnyaStorage::starting);
        NeoForge.EVENT_BUS.addListener(AnyaStorage::stopped);
        NeoForge.EVENT_BUS.addListener(AnyaStorage::login);
        NeoForge.EVENT_BUS.addListener(AnyaStorage::tick);
        NeoForge.EVENT_BUS.addListener(AnyaStorage::breaking);
        NeoForge.EVENT_BUS.addListener(StorageUpgrades::dragonDeath);
    }
    private static void commands(RegisterCommandsEvent e) {
        var d=e.getDispatcher();
        d.register(Commands.literal("anya").then(Commands.literal("storage").executes(c->{StorageMenu.openHome(c.getSource().getPlayerOrException());return 1;})));
        if(d.getRoot().getChild("as")==null) d.register(Commands.literal("as").executes(c->{StorageMenu.openHome(c.getSource().getPlayerOrException());return 1;}));
    }
    private static void starting(ServerAboutToStartEvent e) { Rules.load(); StorageOps.reset(); Claims.reset(); }
    private static void stopped(ServerStoppedEvent e) { StorageOps.reset(); Claims.reset(); }
    private static void login(PlayerEvent.PlayerLoggedInEvent e) { if(e.getEntity() instanceof ServerPlayer p) Packets.sync(p); }
    private static void tick(ServerTickEvent.Post e) {
        StorageOps.tick(e.getServer());
        if(e.getServer().getTickCount()%20==0) for(ServerPlayer p:e.getServer().getPlayerList().getPlayers()) StorageUpgrades.tick(p);
    }
    private static void breaking(BlockEvent.BreakEvent e) {
        if(e.getPlayer() instanceof ServerPlayer p && e.getLevel().getBlockEntity(e.getPos()) instanceof StorageBlockEntity b && !b.canManage(p)) {
            e.setCanceled(true); tell(p,"Only the owner or an operator can break this storage block.");
        }
    }
    public static void tell(ServerPlayer p,String message) { p.sendSystemMessage(Component.literal("[Anya Storage] "+message)); }
}
