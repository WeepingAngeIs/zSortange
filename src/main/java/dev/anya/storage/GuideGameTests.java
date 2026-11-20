package dev.anya.storage;

import dev.anya.storage.guide.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;
import com.mojang.authlib.GameProfile;

@GameTestHolder(AnyaStorage.ID) @PrefixGameTestTemplate(false)
public final class GuideGameTests {
    private static net.minecraft.server.level.ServerPlayer player(GameTestHelper h){var p=FakePlayerFactory.get(h.getLevel(),new GameProfile(UUID.randomUUID(),"GuideTest"));p.getInventory().clearContent();return p;}
    @GameTest(template="empty") public static void tutorialGrantExactlyOnce(GameTestHelper h)throws Exception {
        var p=player(h);Path root=Files.createTempDirectory("anya-guide-once");GuideJournal journal=new GuideJournal(root);
        h.assertTrue(GuideService.grantOnce(p,journal)==GuideService.Grant.GIVEN,"first grant delivers");
        h.assertTrue(p.getInventory().getItem(0).getHoverName().getString().equals("Ancient Technology"),"exact tutorial title");
        p.getInventory().clearContent();h.assertTrue(GuideService.grantOnce(p,new GuideJournal(root))==GuideService.Grant.ALREADY,"death and journal reload never regrant");
        h.assertTrue(p.getInventory().isEmpty(),"lost book is not silently duplicated");h.succeed();
    }
    @GameTest(template="empty") public static void tutorialFullInventoryDefers(GameTestHelper h)throws Exception {
        var p=player(h);for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.STONE,64));var journal=new GuideJournal(Files.createTempDirectory("anya-guide-full"));
        h.assertTrue(GuideService.grantOnce(p,journal)==GuideService.Grant.FULL,"full inventory defers");h.assertTrue(!journal.claimed(p.getUUID()),"deferred grant not consumed");
        p.getInventory().setItem(10,ItemStack.EMPTY);h.assertTrue(GuideService.grantOnce(p,journal)==GuideService.Grant.GIVEN,"space permits grant");h.assertTrue(p.getInventory().getItem(10).is(Content.GUIDE.get()),"free slot used");h.succeed();
    }
    @GameTest(template="empty") public static void tutorialExistingCopyNoDuplicate(GameTestHelper h)throws Exception {
        var p=player(h);p.getInventory().setItem(5,new ItemStack(Content.GUIDE.get()));var journal=new GuideJournal(Files.createTempDirectory("anya-guide-existing"));
        h.assertTrue(GuideService.grantOnce(p,journal)==GuideService.Grant.ALREADY,"existing guide recognized");h.assertTrue(p.getInventory().getItem(0).isEmpty(),"no second copy");h.assertTrue(journal.claimed(p.getUUID()),"recognition persisted");h.succeed();
    }
    @GameTest(template="empty") public static void guidePreservesExistingPlayerData(GameTestHelper h){
        var p=player(h);var state=Profiles.of(p);state.guideGranted=true;state.protectedSlots=321;state.favorites.add("minecraft:diamond");UUID worn=UUID.randomUUID();state.wornBag=worn;
        CompoundTag saved=Profiles.get(p.getServer()).save(new CompoundTag(),h.getLevel().registryAccess());Profiles loaded=Profiles.load(saved,h.getLevel().registryAccess());CompoundTag again=loaded.save(new CompoundTag(),h.getLevel().registryAccess());
        CompoundTag found=null;for(Tag t:again.getList("Players",Tag.TAG_COMPOUND)){CompoundTag v=(CompoundTag)t;if(v.getUUID("Id").equals(p.getUUID()))found=v;}
        h.assertTrue(found!=null&&found.getBoolean("GuideGranted"),"guide flag persisted");h.assertTrue(found.getLong("Protected")==321&&found.getUUID("WornBag").equals(worn),"old preferences intact");h.assertTrue(found.getList("Favorites",Tag.TAG_STRING).getString(0).equals("minecraft:diamond"),"favorites intact");h.succeed();
    }
    @GameTest(template="empty") public static void guideReadsLiveRecipeAndRules(GameTestHelper h){
        var pages=GuideContent.build(h.getLevel());h.assertTrue(pages.size()>40&&pages.size()<=128,"bounded illustrated chapters");
        var starter=pages.stream().filter(p->p.title().equals("Your first leather backpack")).findFirst().orElseThrow();
        h.assertTrue(starter.cells().stream().filter(c->c.id().equals("minecraft:leather")).count()==6,"recipe loaded from actual current recipe manager");
        var stone=pages.stream().filter(p->p.title().equals("Stone binding")).findFirst().orElseThrow();h.assertTrue(stone.body().contains("2 armor points"),"actual default armor value described");
        var limits=pages.stream().filter(p->p.title().equals("What this milestone does not do")).findFirst().orElseThrow();
        h.assertTrue(limits.body().contains("Live portal rendering and unrestricted pocket building are NOT implemented"),"unavailable live renderer and unrestricted construction explicitly excluded");
        var builder=pages.stream().filter(p->p.title().equals("Build without granting storage")).findFirst().orElseThrow();
        String editing=ExpansionRules.get().pocketEditing?"enabled for this test server":"OFF";
        h.assertTrue(builder.body().contains("Room editing is "+editing)&&builder.body().contains("both Visit and Build"),"guide uses live edit gate and independent delegated rights");
        var pads=pages.stream().filter(p->p.title().equals("Optional chest pads")).findFirst().orElseThrow();
        h.assertTrue(pads.body().contains("ONLY when empty")&&pads.body().contains("original four chests cannot be removed"),"guide preserves occupied and mandatory chest restrictions");
        h.succeed();
    }
    @GameTest(template="empty") public static void guideRecoveryProtectsNamedItems(GameTestHelper h){
        var p=player(h);ItemStack named=new ItemStack(Items.BOOK);named.set(DataComponents.CUSTOM_NAME,Component.literal("Precious notes"));p.getInventory().setItem(0,named);
        h.assertTrue(GuideService.recover(p)==0,"named book not consumed");h.assertTrue(p.getInventory().getItem(0).getHoverName().getString().equals("Precious notes"),"name preserved");
        p.getInventory().setItem(1,new ItemStack(Items.BOOK,2));h.assertTrue(GuideService.recover(p)==1,"explicit recovery succeeds");h.assertTrue(p.getInventory().getItem(1).getCount()==1,"exactly one plain book consumed");h.assertTrue(GuideService.hasGuide(p),"recovered guide present");h.succeed();
    }
}
