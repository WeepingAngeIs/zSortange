package dev.anya.swapreview;
import dev.anya.storage.PlacedBackpackEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.AdvancedFeeding;
import dev.anya.storage.BagInventory;
import dev.anya.storage.Config;
import dev.anya.storage.Content;
import dev.anya.storage.Transactions;
import dev.anya.storage.Upgrades;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@Mod("anyaswapreview")
@EventBusSubscriber(modid="anyaswapreview")
public final class SwapReview {
    private static MinecraftServer server;
    private static final BlockPos POS=new BlockPos(6,81,3);
    private static List<String> passed=new ArrayList<>();
    private static void check(boolean ok,String name){
        if(!ok)throw new IllegalStateException(name);
        passed.add(name);
    }
    private static ServerPlayer join(String name,int x)throws Exception{
        var level=server.overworld();var profile=new GameProfile(UUID.randomUUID(),name);
        var p=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
        var connection=new Connection(PacketFlow.SERVERBOUND){
            @Override public boolean isMemoryConnection(){return true;}
            @Override public void send(Packet<?> packet,PacketSendListener callback,boolean flush){}
        };
        var channel=new EmbeddedChannel(connection);NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection,p,CommonListenerCookie.createInitial(profile,false));
        p.setGameMode(GameType.SURVIVAL);
        var floor=new BlockPos(x,80,3);level.setBlockAndUpdate(floor,Blocks.SMOOTH_STONE.defaultBlockState());
        p.teleportTo(level,x+.5,81,3.5,0,0);p.getInventory().clearContent();p.getInventory().selected=1;
        return p;
    }

    private static ItemStack disc(String id){return new ItemStack(Content.UPGRADES.get(id).get());}
    private static ItemStack style(String id){return new ItemStack(Content.STYLES.get(id).get());}
    private static ItemStack named(ItemStack stack,String name){
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(name));return stack;
    }
    private static void click(ServerPlayer p,ItemStack item){
        p.setItemInHand(InteractionHand.MAIN_HAND,item);
        p.gameMode.useItemOn(p,p.serverLevel(),item,InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(POS),Direction.SOUTH,POS,false));
    }
    private static ItemStack saved(PlacedBackpackEntity be,ServerPlayer p){
        return ItemStack.parseOptional(p.registryAccess(),be.saveWithoutMetadata(p.registryAccess()).getCompound("Bag"));
    }
    @SubscribeEvent
    public static void start(ServerStartedEvent e)throws Exception{
        server=e.getServer();
        var p=join("BagOwner",4);
        var guest=join("Guest",5);
        var level=p.serverLevel();
        level.setBlockAndUpdate(POS,Content.PLACED_BACKPACK.get().defaultBlockState());
        var be=(PlacedBackpackEntity)level.getBlockEntity(POS);
        var bag=new ItemStack(Content.BACKPACK.get());BagInventory.initialize(bag);
        var tag=BagInventory.data(bag);tag.putInt("UpgradeSlots",2);BagInventory.data(bag,tag);
        var place=PlacedBackpackEntity.class.getDeclaredMethod("place",ServerPlayer.class,ItemStack.class);
        place.setAccessible(true);place.invoke(be,p,bag);
        var old=named(disc("feeding"),"Named food disc");
        click(p,old.copy());click(p,disc("pickup"));
        check(Upgrades.installedItems(saved(be,p)).size()==2 && p.getMainHandItem().isEmpty(),"Two free slots install in order");
        var before=saved(be,p);
        click(guest,disc("refill"));
        check(ItemStack.matches(before,saved(be,p)) && guest.getMainHandItem().getCount()==1,"Guest cannot change bag");
        for(String invalid:List.of("pickup","advanced_pickup")){
            click(p,disc(invalid));check(ItemStack.matches(before,saved(be,p)) && p.getMainHandItem().getCount()==1,"No eviction by "+invalid);
        }
        Config.get().upgrades.put("refill",false);click(p,disc("refill"));
        check(ItemStack.matches(before,saved(be,p)) && p.getMainHandItem().getCount()==1,"Disabled disc rejected");
        Config.get().upgrades.put("refill",true);
        for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        click(p,disc("refill").copyWithCount(2));
        check(ItemStack.matches(before,saved(be,p)) && p.getMainHandItem().getCount()==2,"Full inventory blocks swap atomically");
        click(p,disc("refill"));
        var entries=Upgrades.installedItems(saved(be,p));
        check(ItemStack.matches(p.getMainHandItem(),old) && entries.size()==2
                && entries.getCompound(0).getString("Id").equals("pickup") && entries.getCompound(1).getString("Id").equals("refill"),
                "Freed held slot returns exact oldest disc and survivor keeps order");
        var patch=named(style("skin_galaxy"),"Personal galaxy");
        var binding=named(style("element_leaf"),"Personal leaf");
        click(p,patch.copy());click(p,binding.copy());before=saved(be,p);
        click(p,style("skin_cherry").copyWithCount(2));
        check(ItemStack.matches(before,saved(be,p)) && p.getMainHandItem().getCount()==2,"Full inventory blocks cosmetic swap");
        click(p,style("skin_cherry"));check(ItemStack.matches(p.getMainHandItem(),patch),"Skin returns named skin");
        click(p,style("element_magma"));check(ItemStack.matches(p.getMainHandItem(),binding),"Binding returns named binding");
        check(entries.equals(Upgrades.installedItems(saved(be,p))),"Cosmetics preserve gameplay discs");
        var path=ModList.get().getModContainerById("anyastorage").orElseThrow().getModInfo().getOwningFile().getFile().getFilePath();
        check(Files.isSameFile(path,Path.of(System.getProperty("anya.swapPlayable"))),"Tested exact playable");
        Files.write(Path.of("checks.txt"),passed);server.halt(false);
    }
}
