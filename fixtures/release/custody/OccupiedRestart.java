package dev.unyielding.bundlesmoke;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.BackpackHistory;
import dev.anya.storage.BagInventory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import static dev.unyielding.bundlesmoke.PlayerOnlySmoke.require;

/** Normal admission occurs with the certified predecessor. Two new JVMs read its actual occupied world. */
final class OccupiedRestart {
    private static final Path expectedFile=PlayerOnlySmoke.results.resolve("second-original.nbt");
    private static final Path owners=PlayerOnlySmoke.results.resolve("second-loss.txt");
    private static final List<String> messages=new ArrayList<>();
    private static boolean done;
    private static ServerPlayer visitor,other;
    private static int joined;

    static boolean seed(ServerPlayer first,ServerPlayer second,ItemStack exact,String restore,int copies) throws Exception {
        if (!"seed".equals(System.getenv("INDEPENDENT_PHASE"))) return false;
        require(copies==0,"Existing loss must be pending before the Storage upgrade");
        Files.write(expectedFile,OriginCustodyPort.encode((CompoundTag)exact.save(second.registryAccess())));
        Files.writeString(owners,first.getUUID()+"\n"+second.getUUID()+"\n"+restore+"\n");
        require(first.server.saveEverything(false,true,true),"Occupied baseline world did not save");
        Files.writeString(IndependentLoss.result,"Certified original was admitted and held once; second owner has a witnessed pending loss.\n");
        return true;
    }

    static boolean handle(MinecraftServer server) throws Exception {
        String phase=System.getenv("INDEPENDENT_PHASE");
        if ("seed".equals(phase)) return IndependentLoss.complete;
        if (!"restore".equals(phase) && !"replay".equals(phase)) return false;
        if (done || server.getTickCount()<120) return true;
        var ids=Files.readAllLines(owners);
        if (other==null) {
            CertifiedJailSmoke.world=server.getWorldPath(LevelResource.ROOT);
            require(CertifiedJailSmoke.world.toRealPath().getFileName().toString().equals("jail_certified_smoke"),"Wrong disposable world");
            var loaded=ModList.get().getModFileById("anyastorage").getFile().getFilePath();
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loaded)));
            require(actual.equals(System.getenv("FROZEN_RELEASE_SHA")),"Unexpected installed Storage binary");
            require(dev.unyielding.jail.JailIntegrated.activeTransaction(server,UUID.fromString(ids.get(0)))!=null,
                    "Native custody was not active on restart");
            visitor=IndependentLoss.connect(server,new GameProfile(UUID.fromString(ids.get(0)),"Visitor"),new ArrayList<>());
            other=IndependentLoss.connect(server,new GameProfile(UUID.fromString(ids.get(1)),"OtherBagOwner"),messages);
            joined=server.getTickCount();
            IndependentLoss.note(phase+" opened the occupied published pack with "+actual);
            return true;
        }
        if (server.getTickCount()-joined<30) return true;
        var exact=ItemStack.parse(server.registryAccess(),OriginCustodyPort.decode(Files.readAllBytes(expectedFile))).orElseThrow();
        var id=BagInventory.data(exact).getUUID("BagId");
        boolean replay=phase.equals("replay");
        CombinedStorageProof.held(visitor);
        require(IndependentLoss.count(other,id,exact)==(replay?1:0),"Wrong number of second bags on restart");
        require(!other.hasPermissions(2) && BackpackHistory.page(other,0).isEmpty(),"Non-operator got private history");
        CombinedStorageProof.attemptRecovery(visitor,false);
        String cmd=ids.get(2);
        server.getPlayerList().op(other.getGameProfile());
        try {
            if (!replay) rejectUnknownClaimOption(server, id, exact, cmd);
            messages.clear();
            require(server.getCommands().getDispatcher().execute(cmd,other.createCommandSourceStack())==1,"Restore command unavailable");
            IndependentLoss.note(phase+" operator response "+messages);
            require(IndependentLoss.count(other,id,exact)==1 && IndependentLoss.count(visitor,id,exact)==0,
                    "Independent loss did not return to the correct owner exactly once: "+messages);
            require(server.getCommands().getDispatcher().execute(cmd,other.createCommandSourceStack())==1,"Could not replay same link");
            require(IndependentLoss.count(other,id,exact)==1,"Repeat created another bag");
        } finally { server.getPlayerList().deop(other.getGameProfile()); }
        for(var world:server.getAllLevels()) for(var entity:world.getAllEntities()) {
            if(entity instanceof ItemEntity drop) require(!IndependentLoss.sameId(drop.getItem(),id),"Second bag also exists on the ground");
        }
        CombinedStorageProof.held(visitor);
        String receipt=phase+" passed: one exact second bag with eleven marked emeralds is with its rightful owner. The first bag and its Feeding disc remained in actual custody exactly once during recovery.\n";
        if (replay) {
            int release=server.getCommands().getDispatcher().execute("unjail Visitor",server.createCommandSourceStack());
            if (dev.unyielding.jail.JailIntegrated.activeTransaction(server,visitor.getUUID())==null) {
                require(release==1,"Custody closed with an unexpected command status");
                CombinedStorageProof.returned(visitor);
                require(IndependentLoss.count(other,id,exact)==1,"Releasing the first owner changed the second bag");
                receipt+="The normal Jail release returned the exact original to slot 12 once on the corrected Storage JAR.\n";
            } else {
                CombinedStorageProof.held(visitor);
                receipt+="The normal Jail release kept the original in custody on the changed software pair. Command result "+release+".\n";
            }
        }
        require(server.saveEverything(false,true,true),"Could not save the recovery outcome");
        Files.writeString(Path.of("independent-"+phase+".txt"),receipt);
        IndependentLoss.note(receipt.strip());
        done=true;
        return true;
    }

    private static void rejectUnknownClaimOption(MinecraftServer server,UUID id,ItemStack exact,String cmd) throws Exception {
        var folder=server.getWorldPath(LevelResource.ROOT).resolve("data/openpartiesandclaims/player-configs");
        var fromProvider=folder.resolve(other.getUUID()+".toml");
        require(Files.isRegularFile(fromProvider),"The installed claims mod did not save its player settings");
        var control=folder.resolve("00000000-0000-4000-8000-000000000069.toml");
        Files.writeString(control,Files.readString(fromProvider)+"\n[unreviewed]\nunknown_items = [\"private-test-marker\"]\n",
                java.nio.file.StandardOpenOption.CREATE_NEW);
        try {
            messages.clear();
            require(server.getCommands().getDispatcher().execute(cmd,other.createCommandSourceStack())==1,"Negative control could not reach recovery");
            require(IndependentLoss.count(other,id,exact)==0,"Unknown claim options permitted a return");
            require(messages.stream().anyMatch(message->message.contains("claim settings") || message.contains("Claims player settings")),
                    "Unknown option did not produce a claim settings error: "+messages);
            IndependentLoss.note("Unknown claim settings prevented a return and preserved the authentic loss record.");
        } finally {
            Files.delete(control);
        }
    }
}
