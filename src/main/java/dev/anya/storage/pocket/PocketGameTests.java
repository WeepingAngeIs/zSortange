package dev.anya.storage.pocket;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(AnyaStorage.ID) @PrefixGameTestTemplate(false)
public final class PocketGameTests {
    private static void check(GameTestHelper h,boolean b,String message){h.assertTrue(b,message);}
    private static ServerPlayer player(GameTestHelper h,String name){return new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),name)){@Override public boolean hasPermissions(int ignored){return false;}};}
    @GameTest(template="empty") public static void legacyVisitIsNeverImplicit(GameTestHelper h){
        var hub=new StorageEntity(BlockPos.ZERO,Content.HUB.get().defaultBlockState());var p=player(h,"PocketRights");hub.owner=UUID.randomUUID();hub.trusted.add(p.getUUID());
        check(h,hub.permits(p,BasePermissions.LEGACY)&&!hub.permits(p,BasePermissions.VISIT),"Old storage trust must not grant entry");
        hub.setMember(p.getUUID(),BasePermissions.VISIT);check(h,hub.permits(p,BasePermissions.VISIT)&&!hub.permits(p,BasePermissions.VIEW|BasePermissions.WITHDRAW),"Visit does not grant storage");h.succeed();
    }
    @GameTest(template="empty") public static void entranceRecipeCannotEatContainers(GameTestHelper h){
        boolean old=ExpansionRules.get().pocketStorage;ExpansionRules.get().pocketStorage=true;
        try{
            var recipe=new PocketContent.EntranceRecipe(CraftingBookCategory.MISC);List<ItemStack> items=new ArrayList<>();
            for(int i=0;i<4;i++)items.add(new ItemStack(Items.OBSIDIAN));for(int i=0;i<2;i++)items.add(new ItemStack(Items.ENDER_PEARL));for(int i=0;i<2;i++)items.add(new ItemStack(Items.AMETHYST_SHARD));items.add(new ItemStack(Items.DIAMOND));
            check(h,recipe.matches(CraftingInput.of(3,3,items),h.getLevel())==Boolean.getBoolean("anyastorage.pocketPrototype"),"Recipe requires explicit prototype launch as well as config");
            items.set(8,new ItemStack(Content.BACKPACK.get()));check(h,!recipe.matches(CraftingInput.of(3,3,items),h.getLevel()),"Never consume a stored backpack as a core");
            check(h,recipe.assemble(CraftingInput.of(3,3,items),h.getLevel().registryAccess()).is(PocketContent.ITEM.get()),"Distinct entrance result");
        }finally{ExpansionRules.get().pocketStorage=old;}h.succeed();
    }
    @GameTest(template="empty") public static void pocketChestNotScannedByOrdinaryNetwork(GameTestHelper h){
        var chest=new StorageEntity(BlockPos.ZERO,Content.CRATE.get().defaultBlockState());check(h,Network.supported(chest),"Ordinary chest stays supported");chest.pocketId=UUID.randomUUID();
        check(h,!Network.supported(chest),"No nearby QuickStack bypass into protected pocket chest");check(h,chest.getSlotsForFace(net.minecraft.core.Direction.DOWN).length==0,"No sided automation exposure");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=800) public static void gatedPocketLifecycle(GameTestHelper h)throws Exception {
        if(!Boolean.getBoolean("anyastorage.pocketTests")){AnyaStorage.LOG.info("ANYA_POCKET_RUNTIME_SKIPPED: requires isolated -PpocketTest run");h.succeed();return;}
        ExpansionRules.get().pocketStorage=true;
        var server=h.getLevel().getServer();ServerPlayer owner=player(h,"PocketOwner"),visitor=player(h,"PocketGuest");
        BlockPos hp=h.absolutePos(new BlockPos(1,1,1)),ep=h.absolutePos(new BlockPos(3,1,1));
        h.setBlock(1,1,1,Content.HUB.get());h.setBlock(3,1,1,PocketContent.ENTRANCE.get());h.setBlock(3,0,3,Blocks.STONE);h.setBlock(4,0,3,Blocks.STONE);
        StorageEntity hub=(StorageEntity)h.getLevel().getBlockEntity(hp);hub.owner=owner.getUUID();
        var entrance=(PocketContent.EntranceEntity)h.getLevel().getBlockEntity(ep);entrance.owner=owner.getUUID();
        owner.setPos(ep.getX()+.5,ep.getY(),ep.getZ()+2.5);visitor.setPos(ep.getX()+1.5,ep.getY(),ep.getZ()+2.5);
        ItemStack remote=new ItemStack(Content.REMOTE.get());var data=BagInventory.data(remote);data.putString("HubDimension",h.getLevel().dimension().location().toString());data.putLong("HubPos",hp.asLong());BagInventory.data(remote,data);owner.getInventory().setItem(0,remote);
        ItemStack named=new ItemStack(Items.DIAMOND,17);named.set(DataComponents.CUSTOM_NAME,Component.literal("Conserved pocket diamonds"));owner.getInventory().setItem(5,named.copy());
        PocketService.testPlayer(owner);PocketService.testPlayer(visitor);PocketService.link(owner,entrance,remote);check(h,entrance.pocket!=null,"Persistent entrance linked");
        UUID id=entrance.pocket;var r=PocketService.ledger(server).get(id);hub.setMember(visitor.getUUID(),BasePermissions.VIEW);
        check(h,!PocketService.authorize(visitor,r,PocketLeases.Kind.VISIT,true,0).isEmpty(),"Storage viewer cannot enter without Visit");
        PocketService.enter(owner,entrance);
        h.runAfterDelay(150,()->{
            try{
                check(h,PocketService.inPocket(owner.level()),"Owner actually crossed into registered pocket dimension");
                var record=PocketService.ledger(server).get(id);check(h,record.stage()==PocketLedger.Stage.READY,"Preparation reached READY");
                check(h,PocketService.ledger(server).returning(owner.getUUID())!=null,"Return marker exists during visit");
                check(h,Transactions.same(owner.getInventory().getItem(5),named),"Teleport did not mutate item components or count");
                var view=new PocketView(owner,record,PocketLeases.Kind.VISIT);check(h,view.containers().size()==4,"Four real physical containers");
                StorageEntity chest=(StorageEntity)view.containers().getFirst();var main=new MainInventory(owner);
                var deposit=new Transactions.Plan(List.of(main,chest),view.guard(BasePermissions.DEPOSIT));check(h,deposit.move(main,5,List.of(chest),17)==17,"Deposit fit");
                check(h,deposit.commit(server).isEmpty()&&owner.getInventory().getItem(5).isEmpty()&&Transactions.same(chest.getItem(0),named),"Real inventory conservation");
                // Menu factory uses the existing accurate-capacity UI without an invented virtual store.
                StoreMenu.pocket(owner,view);check(h,owner.containerMenu instanceof StoreMenu m&&m.capacity()==216,"Real 4x54 physical capacity reported");owner.closeContainer();
                hub.setMember(visitor.getUUID(),BasePermissions.VISIT);PocketService.enter(visitor,entrance);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(175,()->{
            try{
                check(h,PocketService.inPocket(visitor.level()),"Second identity entered independently");var record=PocketService.ledger(server).get(id);
                var visitorView=new PocketView(visitor,record,PocketLeases.Kind.VISIT);check(h,!visitorView.permits(BasePermissions.VIEW)&&!visitorView.permits(BasePermissions.WITHDRAW),"Visit-only cannot inspect or withdraw");
                StoreMenu.pocket(visitor,visitorView);check(h,visitor.containerMenu instanceof StoreMenu m&&m.privateView()&&m.resultCount()==0,"Private physical chest data not sent to Visit-only menu");visitor.closeContainer();
                PocketService.recover(owner);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(200,()->{
            try{
                check(h,!PocketService.inPocket(owner.level()),"Owner returned without stranding");check(h,PocketService.ledger(server).returning(owner.getUUID())==null,"Completed return cleared only after exit");
                check(h,PocketService.lease(visitor.getUUID())!=null&&PocketService.ownedTicketRequests()>0,"Second visitor retains pocket requests");
                // Revocation must invalidate an already constructed view before any transaction commits.
                hub.setMember(visitor.getUUID(),BasePermissions.VISIT|BasePermissions.LEGACY);var record=PocketService.ledger(server).get(id);var v=new PocketView(visitor,record,PocketLeases.Kind.VISIT);StorageEntity chest=(StorageEntity)v.containers().getFirst();
                var destination=new SimpleContainer(1);var take=new Transactions.Plan(List.of(chest,destination),v.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));take.move(chest,0,List.of(destination),17);
                hub.removeMember(visitor.getUUID());check(h,!take.commit(server).isEmpty()&&destination.isEmpty()&&Transactions.same(chest.getItem(0),named),"Revoked queued transfer changes no inventory");
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(230,()->{
            try{
                check(h,!PocketService.inPocket(visitor.level()),"Revoked visitor recovered automatically");
                check(h,PocketService.ownedTicketRequests()==0,"Last visitor released every Anya-owned ticket request");
                var record=PocketService.ledger(server).get(id);var pocket=PocketService.dimension(server);var chest=(StorageEntity)pocket.getBlockEntity(PocketService.chestPositions(record).getFirst());
                check(h,chest!=null&&Transactions.same(chest.getItem(0),named),"Chest contents stayed physical and unchanged");
                PocketService.remote(owner,remote,hub);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(260,()->{
            try{
                check(h,owner.containerMenu instanceof StoreMenu m&&m.capacity()==216,"Home-radius remote opens pocket's physical chests");
                var record=PocketService.ledger(server).get(id);var remoteView=new PocketView(owner,record,PocketLeases.Kind.REMOTE);StorageEntity chest=(StorageEntity)remoteView.containers().getFirst();
                SimpleContainer destination=new SimpleContainer(1);var plan=new Transactions.Plan(List.of(chest,destination),remoteView.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));plan.move(chest,0,List.of(destination),17);
                h.getLevel().removeBlock(ep,false);check(h,!plan.commit(server).isEmpty()&&destination.isEmpty()&&Transactions.same(chest.getItem(0),named),"Destroyed entrance invalidates remote transfer without inventory loss");
                owner.closeContainer();
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(280,()->{
            try{
                check(h,PocketService.ownedTicketRequests()==0,"Remote closure releases owned requests");
                var before=PocketService.ledger(server).get(id);BlockPos replacement=h.absolutePos(new BlockPos(5,1,1));
                h.setBlock(5,1,1,PocketContent.ENTRANCE.get());var restored=(PocketContent.EntranceEntity)h.getLevel().getBlockEntity(replacement);restored.owner=owner.getUUID();
                PocketService.reanchor(owner,id,restored,remote);var after=PocketService.ledger(server).get(id);
                check(h,after.cell()==before.cell()&&after.epoch()>before.epoch()&&id.equals(restored.pocket),"Reanchor retained the original physical cell");
                PocketService.enter(owner,restored);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(310,()->{
            try{
                check(h,PocketService.inPocket(owner.level()),"Reanchored entrance actually works");
                PocketService.logout(owner);check(h,PocketService.lease(owner.getUUID())==null,"Logout releases lease immediately");
                check(h,PocketService.ledger(server).returning(owner.getUUID())!=null,"Logout retains unfinished return record");
                PocketService.testPlayer(owner);PocketService.login(owner);
            }catch(Exception e){throw new AssertionError(e);}
        });
        h.runAfterDelay(340,()->{
            try{
                check(h,!PocketService.inPocket(owner.level())&&PocketService.ledger(server).returning(owner.getUUID())==null,"Login hook recovers an unfinished visit without a new lease");
                check(h,PocketService.ownedTicketRequests()==0,"Recovery tickets also expired");
                var record=PocketService.ledger(server).get(id);var level=PocketService.dimension(server);var chest=(StorageEntity)level.getBlockEntity(PocketService.chestPositions(record).getFirst());
                check(h,Transactions.same(chest.getItem(0),named),"Reanchor/logout/recovery never copied or removed chest contents");
                AnyaStorage.LOG.info("ANYA_POCKET_RUNTIME_OK: actual dimension crossings; two FakePlayer identities; four physical chests; components; Visit; revocation; home remote; anchor destruction/reanchor; logout/login hooks; zero final Anya-owned requests. Not real-client multiplayer or forced socket disconnect testing.");
                PocketService.logout(owner);PocketService.logout(visitor);h.succeed();
            }catch(Exception e){throw new AssertionError(e);}
        });
    }
    private PocketGameTests(){}
}
