package dev.anya.storage.visual;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.Content;
import dev.anya.storage.StorageBlock;
import dev.anya.storage.furniture.FurnitureEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Exercises the ordinary vanilla/NeoForge interaction and break paths on the runner's disposable integrated server. */
@EventBusSubscriber(modid="anyamoonreview",value=Dist.CLIENT)
public final class GlobeCheck {
    private static final BlockPos GLOBE = new BlockPos(1,80,0);
    private static ServerPlayer deniedPlayer;
    private static int cancelledUses,cancelledBreaks;

    @SubscribeEvent public static void denyUse(PlayerInteractEvent.RightClickBlock event) {
        if (deniedPlayer!=null && event.getEntity()==deniedPlayer && event.getPos().equals(GLOBE)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            cancelledUses++;
        }
    }
    @SubscribeEvent public static void denyBreak(BlockEvent.BreakEvent event) {
        if (deniedPlayer!=null && event.getPlayer()==deniedPlayer && event.getPos().equals(GLOBE)) {
            event.setCanceled(true);
            cancelledBreaks++;
        }
    }
    private static void require(boolean ok,String problem) {
        if (!ok) throw new IllegalStateException("Keepsake Globe: "+problem);
    }
    private static ServerPlayer visitor(ServerPlayer p,String name) {
        var visitor=new FakePlayer(p.serverLevel(),new GameProfile(UUID.randomUUID(),name));
        visitor.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        visitor.setPos(GLOBE.getX()+.5,GLOBE.getY(),GLOBE.getZ()-2);
        return visitor;
    }
    private static void click(ServerPlayer visitor,BlockPos pos,Direction side) {
        var hit=new BlockHitResult(Vec3.atCenterOf(pos).relative(side,.5),side,pos,false);
        visitor.gameMode.useItemOn(visitor,visitor.serverLevel(),visitor.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
    }
    public static void run(ServerPlayer p) {
        var level=p.serverLevel();
        require(level.isEmptyBlock(GLOBE),"expected an empty spot on the disposable stage");
        var crafter=visitor(p,"GlobeMaker");
        var guest=visitor(p,"GlobeVisitor");
        var grid=new ArrayList<>(Collections.nCopies(9,ItemStack.EMPTY));
        for (int i:new int[]{1,3,5}) grid.set(i,new ItemStack(Items.GLASS));
        grid.set(7,new ItemStack(Items.SPRUCE_PLANKS));
        var input=CraftingInput.of(3,3,grid);
        var recipe=level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,level).orElseThrow();
        var globe=recipe.value().assemble(input,level.registryAccess());
        require(globe.is(Content.SHELF.get().asItem()) && globe.getCount()==1,"three glass and one plank must craft one globe");
        crafter.setItemInHand(InteractionHand.MAIN_HAND,globe);
        click(crafter,GLOBE.below(),Direction.UP);
        require(crafter.getMainHandItem().isEmpty() && level.getBlockEntity(GLOBE) instanceof FurnitureEntity,
                "ordinary survival placement must consume the crafted globe");
        var furniture=(FurnitureEntity)level.getBlockEntity(GLOBE);
        ItemStack marked=new ItemStack(Items.AMETHYST_SHARD,3);
        marked.set(DataComponents.CUSTOM_NAME,Component.literal("Inside the keepsake"));
        guest.setItemInHand(InteractionHand.MAIN_HAND,marked.copy());
        deniedPlayer=guest;
        try {
            click(guest,GLOBE,Direction.NORTH);
            require(cancelledUses==1 && guest.getMainHandItem().getCount()==3 && furniture.emptyForRemoval(),"claim-style use cancellation");
        } finally { deniedPlayer=null; }
        click(guest,GLOBE,Direction.NORTH);
        require(guest.getMainHandItem().getCount()==2 && !furniture.emptyForRemoval(),"second player can insert");
        var visible=furniture.getUpdateTag(level.registryAccess());
        require(visible.getAllKeys().equals(Set.of("GlobeItem")) && visible.getString("GlobeItem").equals("minecraft:amethyst_shard"),
                "public model sends only the item type");
        click(guest,GLOBE,Direction.NORTH);
        require(guest.getMainHandItem().getCount()==2,"occupied globe must not consume a second item");
        click(crafter,GLOBE,Direction.NORTH);
        require(ItemStack.matches(crafter.getMainHandItem(),marked.copyWithCount(1)) && furniture.emptyForRemoval(),"empty hand gets original components");
        click(crafter,GLOBE,Direction.NORTH);
        level.setBlockAndUpdate(GLOBE,furniture.getBlockState().cycle(StorageBlock.FACING));
        deniedPlayer=guest;
        try {
            require(!guest.gameMode.destroyBlock(GLOBE) && cancelledBreaks==1 && !furniture.emptyForRemoval(),"claim-style break cancellation");
        } finally { deniedPlayer=null; }
        require(guest.gameMode.destroyBlock(GLOBE) && level.isEmptyBlock(GLOBE),"a second survival player can break an occupied globe");
        int keepsakes=0,globes=0;
        var drops=level.getEntitiesOfClass(ItemEntity.class,new AABB(GLOBE).inflate(1));
        for (var drop:drops) {
            if (ItemStack.isSameItemSameComponents(drop.getItem(),marked)) keepsakes+=drop.getItem().getCount();
            if (drop.getItem().is(Content.SHELF.get().asItem())) globes+=drop.getItem().getCount();
        }
        require(keepsakes==1 && globes==1,"rotation, denied break and permitted break must drop exactly one of each");
        for (var drop:drops) drop.discard();
        // Reset the isolated stage display for the existing camera.
        crafter.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Content.SHELF.get()));
        click(crafter,GLOBE.below(),Direction.UP);
        crafter.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.AMETHYST_SHARD));
        click(crafter,GLOBE,Direction.NORTH);
        System.out.println("PASS public globe survival crafting, placement, two actors, exact components, claimed-event denial, single drop and visible re-placement.");
    }
}
