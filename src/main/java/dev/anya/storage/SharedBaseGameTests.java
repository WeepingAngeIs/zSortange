package dev.anya.storage;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.ui.ControlGlyph;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@GameTestHolder(AnyaStorage.ID)
@PrefixGameTestTemplate(false)
public final class SharedBaseGameTests {
    private static void check(GameTestHelper h,boolean b,String message){h.assertTrue(b,message);}
    private static ServerPlayer member(GameTestHelper h,String key){
        ServerPlayer p=new FakePlayer(h.getLevel(),new GameProfile(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),"BasePolicyTest")){
            @Override public boolean hasPermissions(int level){return false;}
            @Override public OptionalInt openMenu(MenuProvider provider){
                containerMenu=provider.createMenu(81,getInventory(),this);
                return OptionalInt.of(81);
            }
        };
        BlockPos pos=h.absolutePos(new BlockPos(1,2,1));p.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);return p;
    }
    private static StorageEntity hub(GameTestHelper h){
        h.setBlock(1,1,1,Content.HUB.get());StorageEntity hub=(StorageEntity)h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(1,1,1)));hub.owner=UUID.randomUUID();return hub;
    }
    private static StorageEntity chest(GameTestHelper h,StorageEntity hub){
        h.setBlock(2,1,1,Content.CRATE.get());StorageEntity c=(StorageEntity)h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2,1,1)));c.owner=hub.owner;return c;
    }
    private static StorageEntity reload(GameTestHelper h,StorageEntity original){
        CompoundTag data=new CompoundTag();original.saveAdditional(data,h.getLevel().registryAccess());StorageEntity copy=new StorageEntity(original.getBlockPos(),original.getBlockState());copy.loadAdditional(data,h.getLevel().registryAccess());return copy;
    }
    @GameTest(template="empty") public static void legacyTrustKeepsAccess(GameTestHelper h){
        StorageEntity hub=hub(h);ServerPlayer p=member(h,"legacy");hub.trusted.add(p.getUUID());
        check(h,hub.permits(p,BasePermissions.LEGACY),"Old trust must retain view/deposit/withdraw");check(h,!hub.permits(p,BasePermissions.LOG),"Old trust must not silently grant private log access");
        StorageEntity copy=reload(h,hub);check(h,copy.memberMask(p.getUUID())==7,"Old trust survives NBT with no migration");h.succeed();
    }
    @GameTest(template="empty") public static void independentRights(GameTestHelper h){
        StorageEntity hub=hub(h);ServerPlayer p=member(h,"independent");
        for(int mask=0;mask<16;mask++){hub.setMember(p.getUUID(),mask);for(int bit:List.of(1,2,4,8))check(h,hub.permits(p,bit)==((mask&bit)!=0),"Rights must be independent");}
        hub.setMember(p.getUUID(),0);check(h,!hub.allowed(p),"Explicit zero denies hub entry");h.succeed();
    }
    @GameTest(template="empty") public static void permissionsAndOldDataRoundTrip(GameTestHelper h){
        StorageEntity hub=hub(h);ServerPlayer p=member(h,"roundtrip");hub.setMember(p.getUUID(),BasePermissions.VIEW);hub.oreProgress=567;hub.rangeUpgrades=2;hub.baseName="Preserved Base";
        hub.setItem(0,new ItemStack(Items.DIAMOND,19));StorageEntity.Route route=new StorageEntity.Route();route.category="food";hub.routes.put(15L,route);
        StorageEntity copy=reload(h,hub);check(h,copy.memberMask(p.getUUID())==BasePermissions.VIEW,"Rights persisted");check(h,copy.oreProgress==567&&copy.rangeUpgrades==2&&copy.baseName.equals(hub.baseName),"Progression persisted");check(h,copy.getItem(0).getCount()==19&&copy.routes.get(15L).category.equals("food"),"Inventory and routes preserved");h.succeed();
    }
    @GameTest(template="empty") public static void malformedRightsFailClosed(GameTestHelper h){
        StorageEntity hub=hub(h);ServerPlayer p=member(h,"malformed");hub.trusted.add(p.getUUID());CompoundTag tag=new CompoundTag();hub.saveAdditional(tag,h.getLevel().registryAccess());
        ListTag list=new ListTag();CompoundTag right=new CompoundTag();right.putUUID("Id",p.getUUID());right.putInt("Mask",999);list.add(right);tag.put("MemberPermissions",list);
        StorageEntity copy=new StorageEntity(hub.getBlockPos(),hub.getBlockState());copy.loadAdditional(tag,h.getLevel().registryAccess());check(h,copy.memberMask(p.getUUID())==0,"Invalid override must not restore legacy full access");h.succeed();
    }
    @GameTest(template="empty") public static void conflictingRightsIntersect(GameTestHelper h){
        StorageEntity hub=hub(h);ServerPlayer p=member(h,"conflicting");hub.trusted.add(p.getUUID());CompoundTag tag=new CompoundTag();hub.saveAdditional(tag,h.getLevel().registryAccess());ListTag list=new ListTag();
        for(int mask:List.of(BasePermissions.LEGACY,BasePermissions.VIEW)){CompoundTag t=new CompoundTag();t.putUUID("Id",p.getUUID());t.putInt("Mask",mask);list.add(t);}tag.put("MemberPermissions",list);
        StorageEntity copy=new StorageEntity(hub.getBlockPos(),hub.getBlockState());copy.loadAdditional(tag,h.getLevel().registryAccess());check(h,copy.memberMask(p.getUUID())==BasePermissions.VIEW,"Duplicate rights cannot widen access");h.succeed();
    }
    @GameTest(template="empty") public static void revokeCancelsRealTransfer(GameTestHelper h){
        StorageEntity hub=hub(h),chest=chest(h,hub);ServerPlayer p=member(h,"revoked");hub.setMember(p.getUUID(),BasePermissions.LEGACY);chest.setItem(0,new ItemStack(Items.DIAMOND,9));
        SimpleContainer destination=new SimpleContainer(1);var guard=Network.guard(p,hub,List.of(new Network.Target(chest.getBlockPos(),chest)),BasePermissions.VIEW|BasePermissions.WITHDRAW);
        check(h,guard.getAsBoolean(),"Initial production guard allowed");Transactions.Plan plan=new Transactions.Plan(List.of(chest,destination),guard);plan.move(chest,0,List.of(destination),9);
        hub.setMember(p.getUUID(),BasePermissions.DEPOSIT);check(h,!plan.commit(h.getLevel().getServer()).isEmpty(),"Revoked permission rejected at commit");check(h,chest.getItem(0).getCount()==9&&destination.isEmpty(),"Rejected transfer changed no items");h.succeed();
    }
    @GameTest(template="empty") public static void depositOnlyDoesNotGrantWithdrawal(GameTestHelper h){
        StorageEntity hub=hub(h),chest=chest(h,hub);ServerPlayer p=member(h,"deposit-only");hub.setMember(p.getUUID(),BasePermissions.DEPOSIT);
        var targets=List.of(new Network.Target(chest.getBlockPos(),chest));SimpleContainer source=new SimpleContainer(new ItemStack(Items.EMERALD,12));
        Transactions.Plan plan=new Transactions.Plan(List.of(source,chest),Network.guard(p,hub,targets,BasePermissions.DEPOSIT));check(h,plan.move(source,0,List.of(chest),12)==12,"Blind manual deposit fits");check(h,plan.commit(h.getLevel().getServer()).isEmpty(),"Deposit-only production guard committed");
        check(h,chest.getItem(0).getCount()==12&&source.isEmpty(),"Exact deposit conservation");check(h,!Network.guard(p,hub,targets,BasePermissions.VIEW|BasePermissions.WITHDRAW).getAsBoolean(),"Deposit does not grant withdrawal/view");h.succeed();
    }
    @GameTest(template="empty") public static void viewOnlyCannotOrganize(GameTestHelper h){
        StorageEntity hub=hub(h),chest=chest(h,hub);ServerPlayer p=member(h,"view-only");hub.setMember(p.getUUID(),BasePermissions.VIEW);chest.setItem(0,new ItemStack(Items.DIAMOND,8));boolean rejected=false;
        try{Network.organize(p,hub,List.of(new Network.Target(chest.getBlockPos(),chest)));}catch(IllegalArgumentException expected){rejected=true;}
        check(h,rejected&&chest.getItem(0).getCount()==8,"Read-only cannot mutate via Organize");check(h,!Network.permissionReason(p,hub,BasePermissions.VIEW|BasePermissions.DEPOSIT).isEmpty(),"QuickStack/Dump denied");h.succeed();
    }
    @GameTest(template="empty") public static void membersRemainIndependent(GameTestHelper h){
        StorageEntity hub=hub(h);ServerPlayer a=member(h,"member-a"),b=member(h,"member-b");hub.setMember(a.getUUID(),BasePermissions.LEGACY);hub.setMember(b.getUUID(),BasePermissions.VIEW);
        hub.removeMember(a.getUUID());check(h,!hub.allowed(a)&&hub.permits(b,BasePermissions.VIEW)&&!hub.permits(b,BasePermissions.DEPOSIT),"One member's removal cannot change another's rights");h.succeed();
    }
    private static Packets.Action click(StoreMenu menu,int slot){
        return new Packets.Action(menu.containerId,menu.session(),menu.revision(),"slot","",slot);
    }
    @GameTest(template="empty") public static void heldMemberMenuTracksConcurrentEdits(GameTestHelper h){
        StorageEntity hub=hub(h);
        ServerPlayer manager=member(h,"held-member-menu");
        hub.owner=manager.getUUID();
        UUID guest=UUID.randomUUID();
        hub.setMember(guest,BasePermissions.VIEW);
        StoreMenu.network(manager,hub);
        check(h,manager.containerMenu instanceof StoreMenu,"The owner opened the real hub menu");
        StoreMenu menu=(StoreMenu)manager.containerMenu;
        menu.action(click(menu,51));
        h.runAfterDelay(1,()->menu.action(click(menu,12)));
        h.runAfterDelay(2,()->{
            check(h,menu.accessView(),"The owner reached member permissions");
            menu.action(click(menu,0));
            check(h,menu.controlSlot(29)&&ControlGlyph.read(menu.getSlot(29).getItem()).state()==ControlGlyph.State.OFF,"Guest starts with Deposit off");
        });
        h.runAfterDelay(3,()->{
            Packets.Action oldDeposit=click(menu,29);
            hub.setMember(guest,BasePermissions.VIEW|BasePermissions.DEPOSIT);
            menu.action(oldDeposit);
            check(h,hub.memberMask(guest)==(BasePermissions.VIEW|BasePermissions.DEPOSIT),"An older click preserves the newer Deposit grant");
            check(h,menu.revision()!=oldDeposit.revision()&&ControlGlyph.read(menu.getSlot(29).getItem()).state()==ControlGlyph.State.ON,"The owner sees the current grant");
        });
        h.runAfterDelay(4,()->{
            int previous=menu.revision();
            hub.setMember(guest,BasePermissions.VIEW);
            menu.broadcastChanges();
            check(h,menu.revision()!=previous&&ControlGlyph.read(menu.getSlot(29).getItem()).state()==ControlGlyph.State.OFF,"An idle open menu reflects an external revocation");
            menu.action(click(menu,30));
            check(h,hub.memberMask(guest)==(BasePermissions.VIEW|BasePermissions.WITHDRAW),"A fresh control still edits only the selected right");
        });
        h.runAfterDelay(5,()->{
            Packets.Action oldWithdraw=click(menu,30);
            hub.removeMember(guest);
            menu.action(oldWithdraw);
            check(h,!hub.trusted.contains(guest)&&!hub.memberPermissions.containsKey(guest),"An old control cannot re-add a removed member");
            check(h,menu.getSlot(27).getItem().isEmpty()&&!menu.controlSlot(30),"Removed member selection and rights controls were cleared");
            boolean rejected=false;
            try{hub.toggleMemberRight(guest,BasePermissions.DEPOSIT);}catch(IllegalArgumentException expected){rejected=true;}
            check(h,rejected&&!hub.trusted.contains(guest)&&!hub.memberPermissions.containsKey(guest),"Rights edits require a current member");
            hub.setMember(guest,BasePermissions.VIEW);
            menu.broadcastChanges();
            check(h,menu.controlSlot(0)&&menu.getSlot(27).getItem().isEmpty(),"Explicitly re-adding a guest requires a new selection");
            manager.containerMenu=manager.inventoryMenu;
            h.succeed();
        });
    }
    @GameTest(template="empty") public static void activityBoundAndPrivacy(GameTestHelper h){
        BaseActivity activity=new BaseActivity();var o=new ExpansionRules.Options();o.activityLogging=true;o.activityActorIds=true;o.activityLogEntries=32;UUID id=UUID.randomUUID();
        for(int i=1;i<=200;i++)activity.record(o,i,id,BaseActivity.Action.DEPOSIT,i);
        var entries=activity.visible(o);check(h,entries.size()==32&&entries.get(0).count()==200&&entries.get(31).count()==169,"Log ring is bounded and newest-first");
        o.activityActorIds=false;check(h,activity.visible(o).stream().allMatch(e->e.actor()==null),"Policy redacts already recorded actors immediately");activity.prune(o);
        o.activityActorIds=true;check(h,activity.visible(o).stream().allMatch(e->e.actor()==null),"Scrubbed actor IDs cannot reappear");h.succeed();
    }
    @GameTest(template="empty") public static void activityDisabledAndZeroMoves(GameTestHelper h){
        BaseActivity activity=new BaseActivity();var o=new ExpansionRules.Options();check(h,!activity.record(o,1,UUID.randomUUID(),BaseActivity.Action.DEPOSIT,4),"Off by default");o.activityLogging=true;
        check(h,!activity.record(o,2,null,BaseActivity.Action.WITHDRAW,0)&&activity.visible(o).isEmpty(),"No entry for zero-move operations");
        activity.record(o,3,null,BaseActivity.Action.QUICKSTACK,5);BaseActivity restored=new BaseActivity();restored.load(activity.save(o),o);check(h,restored.visible(o).size()==1&&restored.visible(o).get(0).count()==5,"Log persistence");restored.clear();check(h,restored.visible(o).isEmpty(),"Explicit erasure");h.succeed();
    }
    @GameTest(template="empty") public static void privateMetadataNotInBlockPackets(GameTestHelper h){
        StorageEntity hub=hub(h);ServerPlayer p=member(h,"public-packet");hub.setMember(p.getUUID(),BasePermissions.VIEW);var o=new ExpansionRules.Options();o.activityLogging=true;o.activityActorIds=true;
        hub.activity.record(o,1,p.getUUID(),BaseActivity.Action.DEPOSIT,6);CompoundTag publicData=hub.getUpdateTag(h.getLevel().registryAccess());
        check(h,!publicData.contains("MemberPermissions")&&!publicData.contains("Activity")&&!publicData.contains("Trusted")&&!publicData.contains("Owner"),"Public block packets exclude private permissions/history");h.succeed();
    }
}
