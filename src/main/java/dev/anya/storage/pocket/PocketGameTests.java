package dev.anya.storage.pocket;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
        boolean old=ExpansionRules.get().pocketStorage;
        try{
            var recipe=new PocketContent.EntranceRecipe(CraftingBookCategory.MISC);List<ItemStack> items=new ArrayList<>();
            for(int i=0;i<4;i++)items.add(new ItemStack(Items.OBSIDIAN));for(int i=0;i<2;i++)items.add(new ItemStack(Items.ENDER_PEARL));for(int i=0;i<2;i++)items.add(new ItemStack(Items.AMETHYST_SHARD));items.add(new ItemStack(Items.DIAMOND));
            ExpansionRules.get().pocketStorage=false;
            check(h,!recipe.matches(CraftingInput.of(3,3,items),h.getLevel()),"Recipe requires the operator pocket storage opt-in");
            ExpansionRules.get().pocketStorage=true;
            check(h,recipe.matches(CraftingInput.of(3,3,items),h.getLevel()),"The operator can enable the entrance recipe");
            items.set(8,new ItemStack(Content.BACKPACK.get()));check(h,!recipe.matches(CraftingInput.of(3,3,items),h.getLevel()),"Never consume a stored backpack as a core");
            check(h,recipe.assemble(CraftingInput.of(3,3,items),h.getLevel().registryAccess()).is(PocketContent.ITEM.get()),"Distinct entrance result");
        }finally{ExpansionRules.get().pocketStorage=old;}h.succeed();
    }
    @GameTest(template="empty") public static void pocketChestNotScannedByOrdinaryNetwork(GameTestHelper h){
        var chest=new StorageEntity(BlockPos.ZERO,Content.CRATE.get().defaultBlockState());check(h,Network.supported(chest),"Ordinary chest stays supported");chest.pocketId=UUID.randomUUID();
        check(h,!Network.supported(chest),"No nearby QuickStack bypass into protected pocket chest");check(h,chest.getSlotsForFace(net.minecraft.core.Direction.DOWN).length==0,"No sided automation exposure");h.succeed();
    }
    @GameTest(template="empty") public static void eightPadWireFormat(GameTestHelper h) {
        var pos = new BlockPos(-322, 71, 900);
        long token = 0x100000001L;
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            for (int mask = -1; mask <= 255; mask++) {
                var sent = new PocketPadPackets.Update(pos, token, mask);
                PocketPadPackets.Update.CODEC.encode(buf, sent);
                check(h, buf.readableBytes() == (mask == -1 ? 17 : 19), "Only a granted diagram carries a pad byte");
                check(h, sent.equals(PocketPadPackets.Update.CODEC.decode(buf)) && !buf.isReadable(),
                        "Clear and all eight-bit layouts round-trip exactly");
                buf.clear();
            }
            for (boolean watching : List.of(false, true)) {
                var focus = new PocketPadPackets.Focus(pos, token, watching);
                PocketPadPackets.Focus.CODEC.encode(buf, focus);
                check(h, buf.readableBytes() == 17 && focus.equals(PocketPadPackets.Focus.CODEC.decode(buf)),
                        "Focus and cancellation carry only the entrance position, nonce and intent");
                buf.clear();
            }
        } finally {
            buf.release();
        }
        h.succeed();
    }
    private PocketGameTests(){}
}
