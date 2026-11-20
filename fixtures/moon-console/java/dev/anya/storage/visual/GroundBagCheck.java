package dev.anya.storage.visual;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import dev.anya.storage.appearance.Looks;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Server gameplay paths against the placed bag in the disposable camera world. */
final class GroundBagCheck {
    static final BlockPos BAG = new BlockPos(-1, 80, 0);
    private static final InteractionHand HAND = InteractionHand.MAIN_HAND;

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Ground backpack: " + message);
    }

    private static void click(ServerPlayer player, BlockPos pos, Direction side) {
        var hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(side, .5), side, pos, false);
        player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(), HAND, hit);
    }

    private static void place(ServerPlayer player) {
        player.setShiftKeyDown(true);
        click(player, BAG.below(), Direction.UP);
        player.setShiftKeyDown(false);
        require(player.getMainHandItem().isEmpty() && player.serverLevel().getBlockEntity(BAG) instanceof PlacedBackpackEntity,
                "crouch-place must move the entire bag out of the hand");
    }

    private static PlacedBackpackEntity block(ServerPlayer player) {
        return (PlacedBackpackEntity) player.serverLevel().getBlockEntity(BAG);
    }

    private static ItemStack saved(ServerPlayer player) {
        var data = block(player).saveWithoutMetadata(player.registryAccess());
        return ItemStack.parse(player.registryAccess(), data.get("Bag")).orElseThrow();
    }

    private static void apply(ServerPlayer player, ItemStack material, int cost) {
        var before = saved(player);
        int original = material.getCount();
        player.setItemInHand(HAND, material);
        click(player, BAG, Direction.NORTH);
        require(player.getMainHandItem().getCount() == original - cost, "right-click cost for " + material.getItem());
        if (cost == 0) require(ItemStack.matches(before, saved(player)), "denied upgrade changed the bag");
    }

    private static boolean sameBag(ItemStack stack, UUID id) {
        return stack.is(Content.BACKPACK.get()) && BagInventory.data(stack).hasUUID("BagId")
                && id.equals(BagInventory.data(stack).getUUID("BagId"));
    }

    private static long carried(ServerPlayer player, UUID id) {
        return BackpackAccess.all(player).stream().filter(source -> sameBag(source.stack(), id)).count();
    }

    private static List<ItemEntity> drops(ServerPlayer p, UUID id) {
        return p.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(BAG).inflate(.5),
                entity -> entity.isAlive() && sameBag(entity.getItem(), id));
    }

    static void run(ServerPlayer player) {
        var level = player.serverLevel();
        require(level.isEmptyBlock(BAG) && player.getMainHandItem().isEmpty(), "expected a fresh ground and hand");
        player.setGameMode(GameType.SURVIVAL);
        var original = new ItemStack(Content.BACKPACK.get());
        BagInventory.initialize(original);
        UUID id = BagInventory.data(original).getUUID("BagId");
        var cargo = ItemContainerContents.fromItems(List.of(new ItemStack(Items.EMERALD, 7)));
        original.set(DataComponents.CONTAINER, cargo);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("For the trail"));
        player.setItemInHand(HAND, original);
        player.gameMode.useItem(player, level, original, HAND);
        require(player.containerMenu instanceof StoreMenu initial && initial.backpackView() && initial.storageView(),
                "ordinary standing use opens carried storage");
        player.closeContainer();
        player.setShiftKeyDown(true);
        player.gameMode.useItem(player, level, original, HAND);
        require(player.containerMenu instanceof StoreMenu settings && !settings.storageView() && settings.backpackView()
                && settings.getSlot(10).getItem().getHoverName().getString().contains("pickup"), "held upgrade settings route");
        player.closeContainer();
        place(player);
        require(carried(player, id) == 0 && ItemStack.matches(original, saved(player)), "initial placement changed cargo or identity");

        var visitor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "BagVisitor"));
        visitor.setPos(BAG.getX() + .5, BAG.getY(), BAG.getZ() - 2);
        visitor.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        click(visitor, BAG, Direction.NORTH);
        visitor.gameMode.destroyBlock(BAG);
        require(visitor.containerMenu == visitor.inventoryMenu && block(player) != null && drops(player, id).isEmpty(),
                "other player opened or broke the private bag");

        apply(player, new ItemStack(Content.STYLES.get("element_leaf").get(), 2), 1);
        require(Looks.of(saved(player)).element().equals("leaf"), "leaf appearance");
        apply(player, new ItemStack(Content.STYLES.get("element_leaf").get(), 2), 0);
        apply(player, new ItemStack(Content.STYLES.get("skin_galaxy").get(), 2), 1);
        require(Looks.of(saved(player)).texture().equals("galaxy"), "galaxy skin");
        var markedDisc = new ItemStack(Content.UPGRADES.get("pickup").get(), 2);
        markedDisc.set(DataComponents.CUSTOM_NAME, Component.literal("Exact disc"));
        apply(player, markedDisc, 1);
        apply(player, new ItemStack(Content.UPGRADES.get("pickup").get()), 0);
        apply(player, new ItemStack(Content.UPGRADES.get("feeding").get()), 0);
        apply(player, new ItemStack(Items.IRON_INGOT, 7), 0);
        apply(player, new ItemStack(Items.GOLD_INGOT, 8), 0);
        apply(player, new ItemStack(Items.IRON_INGOT, 10), 8);
        apply(player, new ItemStack(Content.UPGRADES.get("feeding").get()), 1);
        apply(player, new ItemStack(Items.GOLD_INGOT, 8), 8);
        var gold = saved(player);
        var disc = Upgrades.installedItems(gold).getCompound(0).getCompound("Stack");
        require(ItemStack.matches(ItemStack.parse(player.registryAccess(), disc).orElseThrow(), markedDisc.copyWithCount(1))
                && Upgrades.installedItems(gold).size() == 2, "installed disc identity/quantity");
        require(sameBag(gold, id) && cargo.equals(gold.get(DataComponents.CONTAINER))
                && gold.getHoverName().getString().equals("For the trail") && BagInventory.data(gold).getInt("Capacity") == 63
                && BagInventory.data(gold).getString("Tier").equals("gold"), "tier, name, id or cargo");

        var bag = block(player);
        var tag = bag.getUpdateTag(player.registryAccess());
        require(tag.getAllKeys().equals(Set.of("Element", "Skin", "LeafSpecies")), "client tag disclosed private data");
        var display = new PlacedBackpackEntity(BAG, bag.getBlockState());
        display.handleUpdateTag(tag, player.registryAccess());
        require(Looks.of(display.displayStack()).texture().equals("galaxy") && !display.displayStack().has(DataComponents.CONTAINER)
                && !display.displayStack().has(DataComponents.CUSTOM_NAME) && !BagInventory.data(display.displayStack()).contains("BagId"),
                "client render shell exposed private data");
        var restored = BlockEntity.loadStatic(BAG, bag.getBlockState(), bag.saveWithFullMetadata(player.registryAccess()), player.registryAccess());
        require(restored instanceof PlacedBackpackEntity, "world save decode");
        level.removeBlockEntity(BAG);
        level.setBlockEntity(restored);
        require(ItemStack.matches(gold, saved(player)), "real block entity save/load changed the bag");

        // An externally copied ID is intentionally inserted only for this refusal, then removed.
        player.getInventory().setItem(9, gold.copy());
        player.setItemInHand(HAND, ItemStack.EMPTY);
        click(player, BAG, Direction.NORTH);
        player.gameMode.destroyBlock(BAG);
        require(player.containerMenu == player.inventoryMenu && ItemStack.matches(gold, saved(player))
                && drops(player, id).isEmpty(), "duplicate opened or removed world bag");
        player.getInventory().setItem(9, ItemStack.EMPTY);

        click(player, BAG, Direction.NORTH);
        require(player.containerMenu instanceof StoreMenu bagMenu && bagMenu.storageView() && bagMenu.backpackView()
                && bagMenu.stillValid(player) && bagMenu.capacity() == 63 && bagMenu.occupiedSlots() == 1,
                "ordinary empty-hand ground storage");
        var menu = (StoreMenu)player.containerMenu;
        menu.action(new Packets.Action(menu.containerId, menu.session(), menu.revision(), "slot", "", 51));
        require(menu.backpackView() && !menu.storageView() && menu.getSlot(10).getItem().getHoverName().getString().contains("INSTALLED"),
                "ground settings did not use the normal menu");
        player.closeContainer();
        player.setShiftKeyDown(true);
        click(player, BAG, Direction.NORTH);
        player.setShiftKeyDown(false);
        require(level.isEmptyBlock(BAG) && ItemStack.matches(gold, player.getMainHandItem()) && carried(player, id) == 1
                && drops(player, id).isEmpty(), "crouch pickup duplicated or lost a bag");

        place(player);
        player.gameMode.destroyBlock(BAG);
        var dropped = drops(player, id);
        require(level.isEmptyBlock(BAG) && carried(player, id) == 0 && dropped.size() == 1
                && ItemStack.matches(gold, dropped.get(0).getItem()), "survival break must drop exactly the saved bag");
        dropped.get(0).setNoPickUpDelay();
        dropped.get(0).playerTouch(player);
        require(carried(player, id) == 1 && drops(player, id).isEmpty() && ItemStack.matches(gold, player.getMainHandItem()),
                "break drop pickup conservation");

        player.setGameMode(GameType.CREATIVE);
        place(player);
        require(carried(player, id) == 0 && drops(player, id).isEmpty() && ItemStack.matches(gold, saved(player)),
                "creative placement must also move the existing bag");
        System.out.println("PASS: ground backpack placement, owner and duplicate guards, skin, leaf, tiers, direct discs, exact costs, storage/settings, private sync, save/load, survival pickup/break, creative conservation");
    }

    private GroundBagCheck() {}
}
