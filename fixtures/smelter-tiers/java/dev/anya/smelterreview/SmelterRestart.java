package dev.anya.smelterreview;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.BagInventory;
import dev.anya.storage.Transactions;
import dev.anya.storage.Upgrades;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import static dev.anya.smelterreview.SmelterGameTests.*;

@Mod("anyasmelterreview")
@EventBusSubscriber(modid="anyasmelterreview")
public final class SmelterRestart {
    private static final BlockPos CHEST=new BlockPos(3,90,3);
    private static final Path ORACLE=Path.of("smelter-fixture-oracle.nbt");
    private static GroundSmelter ground;
    private static List<String> seedEvidence;

    @SubscribeEvent
    public static void started(ServerStartedEvent event)throws Exception{
        String phase=System.getenv("ANYA_SMELTER_PHASE");
        if(phase==null)return;
        check("true".equals(System.getenv("GITHUB_ACTIONS")) && List.of("seed","verify").contains(phase),
                "Invalid disposable server phase");
        var server=event.getServer();
        var actual=ModList.get().getModContainerById("anyastorage").orElseThrow().getModInfo()
                .getOwningFile().getFile().getFilePath();
        check(Files.isRegularFile(actual)&&Files.isSameFile(actual,Path.of(System.getProperty("anya.smelterPlayable"))),
                "Runtime did not load the frozen candidate JAR");
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(actual)));
        var evidence=new ArrayList<String>();
        evidence.add(phase+" "+digest);
        evidence.add("JVM "+ProcessHandle.current().pid());
        var level=server.overworld();
        level.getChunkAt(CHEST);
        var player=new FakePlayer(level,new GameProfile(UUID.fromString("2c383843-bd5f-4ef4-a780-a4d79e472e0e"),"SmelterTester"));
        if(phase.equals("seed")){
            check(!Files.exists(ORACLE) && level.isEmptyBlock(CHEST),"Fresh seed world required");
            run(player);
            evidence.add("PASS Manual, Auto and legacy real crafting, missing-ingredient refusal and named disc install/removal.");
            evidence.add("PASS Carried automation, manual re-arming, filter order, legacy slot order, component substitution, duplicate identity, family disable, full output, fuel and fractional XP.");
            evidence.add("PASS Server upgrades menu slot 24 queued exactly one cook; retired sessions and stale controls could not arm it.");
            evidence.add("PASS Furnace datapack: both the snowball and bucket remainder must fit before cooking. A recipe producing a prohibited nested backpack spent no inputs, fuel or XP.");
            level.setBlockAndUpdate(CHEST,Blocks.CHEST.defaultBlockState());
            var chest=(ChestBlockEntity)level.getBlockEntity(CHEST);
            var oracle=new CompoundTag();
            for(int i=0;i<TIERS.size();i++)seed(player,chest,oracle,i);
            chest.setChanged();
            NbtIo.writeCompressed(oracle,ORACLE);
            evidence.add("PASS Three distinct partially cooked backpacks saved to an overworld chest, including pre-tier legacy fields.");
            ground=new GroundSmelter(level);seedEvidence=evidence;
            return;
        }else{
            check(Files.isRegularFile(ORACLE) && level.getBlockEntity(CHEST) instanceof ChestBlockEntity,
                    "Second process did not open the saved chest");
            var chest=(ChestBlockEntity)level.getBlockEntity(CHEST);
            var oracle=NbtIo.readCompressed(ORACLE,NbtAccounter.create(2_097_152L));
            for(int i=0;i<TIERS.size();i++)resume(player,chest,oracle,i);
            evidence.add("PASS New server reopened the same chest. All three exact stacks matched the pre-shutdown oracle, including bag ID, module components, order, input, paid progress, fuel and fractional XP.");
            evidence.add("PASS Each partial recipe finished after six more carried steps and spent precisely 120 stored fuel ticks. Manual then waited; Auto and legacy continued.");
        }
        finish(server,phase,evidence);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event)throws Exception{
        if(ground==null || !ground.tick())return;
        ground=null;
        seedEvidence.add("PASS Placed backpack: guest attach/open refused, owner attached the exact disc, out-of-range Start rejected, nearby owner queued one cook. After 220 actual ground ticks unchanged, pickup resumed it once.");
        finish(event.getServer(),"seed",seedEvidence);
    }
    private static void finish(MinecraftServer server,String phase,List<String> evidence)throws Exception{
        server.saveEverything(true,true,true);
        Files.write(Path.of(phase+".pass"),evidence,StandardOpenOption.CREATE_NEW);
        server.halt(false);
    }

    private static void seed(ServerPlayer p,ChestBlockEntity chest,CompoundTag oracle,int index) {
        String tier=TIERS.get(index);
        BagInventory bag=install(p,tier);filter(bag,"raw_iron");filter(bag,"raw_copper");
        bag.setItem(0,named(new ItemStack(Items.RAW_IRON,4),"Restart ore"));
        bag.setItem(2,new ItemStack(Items.COAL,2));
        boolean manual=tier.equals(MANUAL);
        if(manual)start(p);
        carry(p,10);
        if(manual)start(p);
        carry(p,4);
        check(data(p).getInt("SmeltTicks")==80 && data(p).getInt("FuelTicks")==1320
                && bag(p).countItem(Items.RAW_IRON)==3 && bag(p).countItem(Items.IRON_INGOT)==1,
                "Seed "+tier+" did not reach the intended paid checkpoint");
        near(data(p).getFloat("SmeltXP"),.7f,"Seed fractional XP");
        if(tier.equals(LEGACY)){
            // A pre-tier bag persisted only its original recipe ID, exact input, progress, heat and XP.
            var old=data(p);old.remove("SmeltMode");old.remove("SmeltDefinition");old.remove("SmeltSlot");
            BagInventory.data(bag(p).stack,old);
        }
        ItemStack live=p.getInventory().removeItemNoUpdate(0);
        oracle.put(tier,live.save(p.registryAccess()));
        check(chest.getItem(index).isEmpty(),"Unexpected occupied checkpoint slot");
        chest.setItem(index,live);
    }

    private static void resume(ServerPlayer p,ChestBlockEntity chest,CompoundTag oracle,int index){
        String tier=TIERS.get(index);
        p.getInventory().clearContent();p.getInventory().selected=0;
        // Only use the world's real inventory as the input. The separate oracle is read for comparison.
        ItemStack loaded=chest.removeItemNoUpdate(index);
        check(Transactions.same(loaded,ItemStack.parseOptional(p.registryAccess(),oracle.getCompound(tier))),
                "Saved "+tier+" backpack differs from the original on disk");
        p.getInventory().setItem(0,loaded);
        boolean manual=tier.equals(MANUAL);
        check(Upgrades.manualSmeltPending(bag(p))==manual && Upgrades.active(bag(p),tier),"Wrong restored mode for "+tier);
        int xp=p.totalExperience;
        carry(p,5);
        check(data(p).getInt("SmeltTicks")==180 && data(p).getInt("FuelTicks")==1220 &&
                bag(p).countItem(Items.IRON_INGOT)==1 && p.totalExperience==xp,"Partial job lost timing, fuel or XP on restart: "+tier);
        carry(p,1);
        check(bag(p).countItem(Items.IRON_INGOT)==2 && bag(p).countItem(Items.RAW_IRON)==2 &&
                bag(p).countItem(Items.COAL)==1 && data(p).getInt("FuelTicks")==1200 && p.totalExperience==xp+1,
                "Expected one resumed recipe and one whole XP for "+tier);
        near(data(p).getFloat("SmeltXP"),.4f,"Persisted fractional XP");
        carry(p,10);
        check(!Upgrades.manualSmeltPending(bag(p)) && bag(p).countItem(Items.IRON_INGOT)==(manual?2:3)
                && bag(p).countItem(Items.RAW_IRON)==(manual?2:1),"Post-restart idle/auto mode "+tier);
        chest.setItem(index,p.getInventory().removeItemNoUpdate(0));chest.setChanged();
    }
}
