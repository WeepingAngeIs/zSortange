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
    private PocketGameTests(){}
}
