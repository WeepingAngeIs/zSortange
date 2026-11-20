package dev.anya.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Real mod transactions; the player inventory and represented block slot are in-memory containers. */
public final class ChestTransactions {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void populate(StorageEntity hub, UUID builder, int rights, List<StorageEntity> chests, ItemStack stock) {
        var held = new SimpleContainer(9);
        var cells = new ArrayList<SimpleContainer>();
        var stockInventory = new SimpleContainer(stock.copy());
        held.setItem(0, new ItemStack(Content.CRATE.get(), 4));
        require(held.getItem(0).getCount() == 4, "Registered Anya chest items");
        for (int right : List.of(BasePermissions.VISIT, BasePermissions.BUILD, BasePermissions.VIEW,
                BasePermissions.DEPOSIT, BasePermissions.WITHDRAW)) {
            hub.setMember(builder, rights & ~right);
            require(!hub.permitsMember(builder, rights), "Missing right was accepted: " + right);
        }
        hub.setMember(builder, rights);
        for (int index = 0; index < chests.size(); index++) {
            var cell = new SimpleContainer(1);
            var plan = new Transactions.Plan(List.of(cell, held), () -> hub.permitsMember(builder, rights));
            require(plan.move(held, 0, List.of(cell), 1) == 1 && plan.conserved(), "Prepare one chest");
            if (index == 0) {
                // A second transaction filled the represented physical cell after this snapshot.
                cell.setItem(0, new ItemStack(Content.CRATE.get()));
                require(!plan.commitOnServerThread().isEmpty() && cell.getItem(0).getCount() == 1
                        && held.getItem(0).getCount() == 4, "Stale occupied slot consumed another chest");
                cell.clearContent();
                var staleHand = new Transactions.Plan(List.of(cell, held), () -> hub.permitsMember(builder, rights));
                require(staleHand.move(held, 0, List.of(cell), 1) == 1, "Prepare held slot");
                held.getItem(0).shrink(1);
                require(!staleHand.commitOnServerThread().isEmpty() && cell.isEmpty()
                        && held.getItem(0).getCount() == 3, "Held-stack change after preview");
                held.getItem(0).grow(1);
                plan = new Transactions.Plan(List.of(cell, held), () -> hub.permitsMember(builder, rights));
                require(plan.move(held, 0, List.of(cell), 1) == 1, "Prepare a request before rights change");
                hub.setMember(builder, BasePermissions.BUILD);
                require(!plan.commitOnServerThread().isEmpty() && held.getItem(0).getCount() == 4
                        && cell.isEmpty(), "Revoked rights consumed the placement item");
                hub.setMember(builder, rights);
                // Model the fresh request the player would make after being authorized again.
                plan = new Transactions.Plan(List.of(cell, held), () -> hub.permitsMember(builder, rights));
                require(plan.move(held, 0, List.of(cell), 1) == 1, "Prepare fresh request");
            }
            require(plan.commitOnServerThread().isEmpty(), "Placement-item transaction failed");
            require(!plan.commitOnServerThread().isEmpty(), "Replayed the committed plan");
            require(cell.getItem(0).getCount() == 1 && held.getItem(0).getCount() == 3 - index,
                    "Placement-item counts");
            cells.add(cell);
            StorageEntity chest = chests.get(index);
            var deposit = new Transactions.Plan(List.of(stockInventory, chest), () -> hub.permitsMember(builder, rights));
            require(deposit.move(stockInventory, 0, List.of(chest), index+1) == index+1
                    && deposit.commitOnServerThread().isEmpty(), "Deposit into real registered chest");
            require(chest.getItem(0).getCount() == index+1 && ItemStack.isSameItemSameComponents(chest.getItem(0), stock),
                    "Deposit changed item components or count");
        }
        int placed = cells.stream().mapToInt(c -> c.countItem(Content.CRATE.get().asItem())).sum();
        require(stockInventory.isEmpty() && held.isEmpty() && placed == 4,
                "Conservation across four placements and four deposits");
        var chest = chests.get(0);
        var rejectOnce = new SimpleContainer(1) {
            boolean reject = true;
            @Override public void setItem(int slot, ItemStack stack) {
                if (reject && !stack.isEmpty()) {
                    reject = false;
                    throw new IllegalStateException("Injected single destination write failure");
                }
                super.setItem(slot, stack);
            }
        };
        var failing = new Transactions.Plan(List.of(chest, rejectOnce), () -> true);
        require(failing.move(chest, 0, List.of(rejectOnce), 1) == 1 && !failing.commitOnServerThread().isEmpty(),
                "Injected destination failure must refuse commit");
        require(Transactions.same(chest.getItem(0), stock.copyWithCount(1)) && rejectOnce.isEmpty()
                && !Transactions.quarantined(chest), "Rollback failed to restore original contents");
    }
}
