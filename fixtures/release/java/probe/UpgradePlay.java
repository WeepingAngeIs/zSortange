package probe;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.BagInventory;
import dev.anya.storage.Content;
import dev.anya.storage.Packets;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.Upgrades;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@Mod("anyareleasecheck")
@EventBusSubscriber(modid="anyareleasecheck")
public final class UpgradePlay {
    private static final BlockPos CHEST = new BlockPos(5, 81, 3);
    private static final BlockPos HUB = new BlockPos(3, 81, 3);
    private static final Path ORIGINAL = Path.of("before-upgrade.nbt");
    private static final InteractionHand HAND = InteractionHand.MAIN_HAND;
    private static ServerPlayer player;
    private static int step;
    private static String phase;
    private static String hash;

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static void join(MinecraftServer server) throws Exception {
        phase = System.getenv("STORAGE_UPGRADE_PHASE");
        Path jar = ModList.get().getModFileById("anyastorage").getFile().getFilePath();
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        require(hash.equals(System.getenv("STORAGE_UPGRADE_SHA")), "Wrong loaded Storage JAR");
        var profile = new GameProfile(UUID.fromString("f10765bc-a495-411a-acd3-982a8f111aa0"), "StorageOwner");
        var connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public boolean isMemoryConnection() { return true; }
            @Override public void send(Packet<?> p, PacketSendListener done, boolean flush) {}
        };
        player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection,player,CommonListenerCookie.createInitial(profile,false));
        require(!player.hasPermissions(2), "Player must have ordinary permissions");
        if (phase.equals("seed")) {
            require(!Files.exists(ORIGINAL), "World must be new");
            player.setGameMode(GameType.SURVIVAL);
            var level = server.overworld();
            for (int x=2;x<=6;x++) for (int z=2;z<=4;z++) level.setBlockAndUpdate(new BlockPos(x,80,z), Blocks.STONE.defaultBlockState());
            player.teleportTo(level,4.5,81,3.5,0,0);
            var inv = player.getInventory();
            inv.clearContent();
            inv.setItem(0,new ItemStack(Content.BACKPACK.get()));
            inv.setItem(1,new ItemStack(Content.CRATE.get()));
            inv.setItem(2,new ItemStack(Content.HUB.get()));
            inv.setItem(3,new ItemStack(Content.CUPID_TABLET.get()));
            inv.setItem(9,marked(Items.AMETHYST_SHARD,13,"Legacy bag marker"));
            inv.setItem(10,marked(Items.EMERALD,7,"Legacy chest marker"));
            inv.setItem(11,new ItemStack(Content.UPGRADES.get("feeding").get()));
            require(Upgrades.change(new BagInventory(player,0),"feeding",false),"Could not install Feeding");
        }
        player.getInventory().selected=0;
    }

    private static ItemStack marked(net.minecraft.world.item.Item item,int count,String name) {
        var stack = new ItemStack(item,count);
        var tag = new CompoundTag();
        tag.putString("Marker",name);
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(name));
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
        return stack;
    }

    private static void air(int slot) {
        player.getInventory().selected=slot;
        player.gameMode.useItem(player,player.serverLevel(),player.getMainHandItem(),HAND);
        menu();
    }

    private static void useAt(int slot, BlockPos pos) {
        player.getInventory().selected=slot;
        var hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0,0.5,0),Direction.UP,pos,false);
        player.gameMode.useItemOn(player,player.serverLevel(),player.getMainHandItem(),HAND,hit);
    }

    private static StoreMenu menu() {
        require(player.containerMenu instanceof StoreMenu, "Normal use failed to open storage at step "+step);
        return (StoreMenu)player.containerMenu;
    }

    private static void click(int slot,boolean one) {
        var menu=menu();
        menu.action(new Packets.Action(menu.containerId,menu.session(),menu.revision(),"slot","",slot+(one?256:0)));
    }

    private static int count(net.minecraft.world.Container container,ItemStack sample) {
        int n=0;
        for(int i=0;i<container.getContainerSize();i++) {
            ItemStack s=container.getItem(i);
            if (ItemStack.isSameItemSameComponents(s,sample)) n+=s.getCount();
        }
        return n;
    }

    private static void expectCount(net.minecraft.world.Container container,ItemStack sample,int wanted) {
        require(count(container,sample)==wanted,"Unexpected count of "+sample.getHoverName().getString()+" at step "+step);
    }
    private static net.minecraft.world.Container chest() {
        return (net.minecraft.world.Container)player.serverLevel().getBlockEntity(CHEST);
    }
    private static int painted(ItemStack wanted) {
        for(int i=0;i<45;i++) if (menu().getSlot(i).getItem().is(wanted.getItem())) return i;
        throw new IllegalStateException("Stored stack missing from normal view at step "+step);
    }
    private static int carried(ItemStack wanted) {
        for(int i=0;i<36;i++) if (ItemStack.isSameItemSameComponents(player.getInventory().getItem(i),wanted)) return i;
        throw new IllegalStateException("Withdrawn stack missing at step "+step);
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server=event.getServer();
        if(step<0 || server.getTickCount()<10) return;
        try {
            if(step==0) join(server);
            var shards=marked(Items.AMETHYST_SHARD,13,"Legacy bag marker");
            var gems=marked(Items.EMERALD,7,"Legacy chest marker");
            var bag=new BagInventory(player,0);
            if(phase.equals("seed")) {
                switch(step) {
                    case 1 -> { useAt(1,CHEST.below()); require(chest()!=null,"Labelled chest placement"); }
                    case 2 -> { useAt(2,HUB.below()); require(player.serverLevel().getBlockState(HUB).is(Content.HUB.get()),"Hub placement"); }
                    case 3 -> air(0);
                    case 4 -> { click(54,false); expectCount(new BagInventory(player,0),shards,13); expectCount(player.getInventory(),shards,0); }
                    case 5 -> { player.closeContainer(); useAt(1,CHEST); menu(); }
                    case 6 -> { click(55,false); expectCount(chest(),gems,7); }
                    case 7 -> { player.closeContainer(); player.setShiftKeyDown(true);useAt(3,HUB);player.setShiftKeyDown(false);
                        require(BagInventory.data(player.getInventory().getItem(3)).contains("HubPos"),"Normal tablet binding failed"); }
                    case 8 -> { air(3);painted(gems);player.closeContainer();
                        var tag = (CompoundTag)player.getInventory().getItem(0).save(server.registryAccess());
                        require(BagInventory.data(player.getInventory().getItem(0)).hasUUID("BagId"),"Missing bag ID");
                        NbtIo.writeCompressed(tag,ORIGINAL);finish(server,"Legacy player saved filled upgraded backpack, bound tablet and filled placed chest"); }
                }
            } else {
                switch(step) {
                    case 1 -> { require(Upgrades.installed(bag,"feeding"),"Saved Feeding upgrade missing");
                        var tag = NbtIo.readCompressed(ORIGINAL,NbtAccounter.create(4L<<20));
                        require(tag.equals(player.getInventory().getItem(0).save(server.registryAccess())),"Backpack differs from legacy stack");
                        expectCount(chest(),gems,7); air(0); }
                    case 2 -> { click(painted(shards),true); expectCount(new BagInventory(player,0),shards,12);expectCount(player.getInventory(),shards,1); }
                    case 3 -> { int slot=carried(shards);
                        click(slot<9?81+slot:54+slot-9,false);expectCount(new BagInventory(player,0),shards,13);expectCount(player.getInventory(),shards,0);player.closeContainer(); }
                    case 4 -> { air(3); click(painted(gems),true);expectCount(chest(),gems,6);expectCount(player.getInventory(),gems,1); }
                    case 5 -> { int slot=carried(gems);
                        click(slot<9?81+slot:54+slot-9,false);expectCount(chest(),gems,7);expectCount(player.getInventory(),gems,0);player.closeContainer(); }
                    case 6 -> { require(NbtIo.readCompressed(ORIGINAL,NbtAccounter.create(4L<<20)).equals(player.getInventory().getItem(0).save(server.registryAccess())),
                            "Final bag component mismatch"); finish(server,"Exact legacy bag and Feeding upgrade survived; ordinary bag and bound network withdrawal/deposit conserved both marked stacks"); }
                }
            }
            if(step>=0)step++;
        } catch(Throwable failure) {failure.printStackTrace();step=-1;server.halt(false);}
    }
    private static void finish(MinecraftServer server,String details) throws Exception {
        require(server.saveEverything(false,true,true),"World save failed");
        Files.writeString(Path.of(phase+".pass"),ProcessHandle.current().pid()+"\n"+phase+" "+hash+" "+details+"\n");
        step=-1;
        server.halt(false);
    }
}
