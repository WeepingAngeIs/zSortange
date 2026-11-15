package dev.anya.storage;

import dev.anya.storage.appearance.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(AnyaStorage.ID) @PrefixGameTestTemplate(false)
public final class ExpansionGameTests {
    private static ItemStack bag(){ItemStack s=new ItemStack(Content.BACKPACK.get());BagInventory.initialize(s);return s;}
    @GameTest(template="empty") public static void cosmeticUpgradePreservesAll(GameTestHelper h){
        ItemStack b=bag();Looks.apply(b,"magma",true);Looks.apply(b,"bow_cat",false);b.set(DataComponents.CUSTOM_NAME,Component.literal("My favorite bow"));b.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND,17))));
        CompoundTag t=BagInventory.data(b);t.putString("Interior","galaxy");UUID image=UUID.randomUUID();t.putUUID("InteriorImage",image);t.putString("OpaqueFutureAddonData","retained");BagInventory.data(b,t);
        List<ItemStack> grid=new ArrayList<>(List.of(b));for(int i=0;i<8;i++)grid.add(new ItemStack(Items.IRON_INGOT));ItemStack out=new UpgradeRecipe(CraftingBookCategory.MISC).assemble(CraftingInput.of(3,3,grid),h.getLevel().registryAccess());
        h.assertTrue(Looks.of(out).equals(Looks.of(b)),"appearance survives tier upgrade");h.assertTrue(out.getHoverName().equals(b.getHoverName()),"custom name survives");h.assertTrue(out.get(DataComponents.CONTAINER).equals(b.get(DataComponents.CONTAINER)),"contents survive");h.assertTrue(BagInventory.data(out).getUUID("InteriorImage").equals(image),"private image reference survives");h.assertTrue(BagInventory.data(out).getString("OpaqueFutureAddonData").equals("retained"),"unknown data survives");h.succeed();
    }
    @GameTest(template="empty") public static void stylesCannotChangeCapacity(GameTestHelper h){
        ItemStack b=bag();Looks.apply(b,"stone",true);String id=BagInventory.data(b).getUUID("BagId").toString();
        for(String skin:Looks.SKINS){ItemStack out=Looks.styledCopy(b,skin,false);h.assertTrue(BagInventory.data(out).getInt("Capacity")==27,"skins are not storage tiers");h.assertTrue(Looks.of(out).element().equals("stone"),"skins cannot replace the elemental path");h.assertTrue(BagInventory.data(out).getUUID("BagId").toString().equals(id),"skin application preserves identity");}
        h.succeed();
    }
    @GameTest(template="empty") public static void styleRecipeCopiesNotMutates(GameTestHelper h){
        ItemStack b=bag(),patch=new ItemStack(Content.STYLES.get("skin_galaxy").get());var input=CraftingInput.of(2,1,List.of(b,patch));StyleRecipe recipe=new StyleRecipe(CraftingBookCategory.EQUIPMENT);
        h.assertTrue(recipe.matches(input,h.getLevel()),"patch recipe recognized");ItemStack out=recipe.assemble(input,h.getLevel().registryAccess());h.assertTrue(Looks.of(out).skin().equals("galaxy"),"skin applied");h.assertTrue(Looks.of(b).skin().equals("elemental"),"craft preview does not mutate input");h.assertTrue(Looks.unlocked(out,"galaxy",false),"unlocked look retained");h.succeed();
    }
    @GameTest(template="empty") public static void singleWornBagAndRemoval(GameTestHelper h){
        var p=FakePlayerFactory.getMinecraft(h.getLevel());p.getInventory().clearContent();ItemStack b=bag();Looks.apply(b,"stone",true);p.getInventory().setItem(0,b);Profiles.of(p).wornBag=BagInventory.data(b).getUUID("BagId");Wearables.refresh(p);
        h.assertTrue(p.getAttribute(Attributes.ARMOR).getModifier(Wearables.ARMOR).amount()==2,"one stone bag adds exactly two armor");
        Wearables.refresh(p);h.assertTrue(p.getAttribute(Attributes.ARMOR).getModifier(Wearables.ARMOR).amount()==2,"repeated ticks cannot stack modifiers");
        p.getInventory().setItem(1,b.copy());Wearables.refresh(p);h.assertTrue(p.getAttribute(Attributes.ARMOR).getModifier(Wearables.ARMOR)==null,"duplicate BagIds disable effects");
        p.getInventory().clearContent();Wearables.refresh(p);h.assertTrue(p.getAttribute(Attributes.ARMOR).getModifier(Wearables.ARMOR)==null,"removal clears the attribute");Profiles.of(p).wornBag=null;h.succeed();
    }
    @GameTest(template="empty") public static void savedAppearanceRoundtrip(GameTestHelper h){
        ItemStack b=bag();Looks.apply(b,"water",true);Looks.apply(b,"cute_spooky",false);CompoundTag t=BagInventory.data(b);t.putString("Interior","ocean");BagInventory.data(b,t);ItemStack restored=ItemStack.parse(h.getLevel().registryAccess(),b.save(h.getLevel().registryAccess())).orElseThrow();h.assertTrue(Looks.of(b).equals(Looks.of(restored)),"appearance survives NBT persistence");h.assertTrue(BagInventory.data(b).getUUID("BagId").equals(BagInventory.data(restored).getUUID("BagId")),"identity survives persistence");h.succeed();
    }
    @GameTest(template="empty") public static void uploadPolicyDefaultsClosed(GameTestHelper h){h.assertTrue(!ExpansionRules.get().imagesEnabled&&!ExpansionRules.get().imageUploadsForAll&&!ExpansionRules.get().imageSharing,"uploads and sharing default closed");h.assertTrue(ExpansionRules.get().imageModeration,"moderation required by default");h.succeed();}
}
