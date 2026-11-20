package dev.anya.storage.appearance;

import dev.anya.storage.StorageItem;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.IdentityHashMap;

/** Local inventory visual state. No stack components, packets, saved data or other players' inventories. */
final class TabletFaces {
    // Main inventory and hotbar, then the offhand (vanilla inventory index 40).
    private static final int SLOTS = 37;
    private static final ItemStack[] slots = new ItemStack[SLOTS];
    private static final ItemStack[] incoming = new ItemStack[SLOTS];
    private static final IdentityHashMap<ItemStack, TabletIdle> visits = new IdentityHashMap<>();
    private static final IdentityHashMap<ItemStack, TabletIdle> moved = new IdentityHashMap<>();
    private static LocalPlayer owner;
    private static int lastTick;
    private static long clock;
    private static ItemStack carried;
    private static TabletIdle.Face carriedFace;

    static void clear() {
        java.util.Arrays.fill(slots, null);
        java.util.Arrays.fill(incoming, null);
        visits.clear();
        moved.clear();
        owner = null;
        carried = null;
        clock = 0;
    }

    static void sample(LocalPlayer player, boolean paused) {
        carried = null;
        if (player != owner) {
            clear();
            if (player != null) {
                owner = player;
                lastTick = player.tickCount;
            }
        }
        if (player == null) return;
        if (!paused) clock += Math.max(0, player.tickCount - lastTick);
        lastTick = player.tickCount;
        var inventory = player.getInventory();
        boolean changed = false;
        for (int i = 0; i < SLOTS; i++) {
            var stack = i == SLOTS - 1 ? player.getOffhandItem() : inventory.getItem(i);
            incoming[i] = StorageItem.isTablet(stack) ? stack : null;
            changed |= slots[i] != incoming[i];
        }
        if (changed) {
            // Match surviving object identities before matching a server-synced copy in the
            // same slot. Two identical tablets which trade places keep their own visits.
            moved.clear();
            for (ItemStack stack : incoming) {
                if (stack == null) continue;
                TabletIdle visit = visits.remove(stack);
                if (visit != null) moved.put(stack, visit);
            }
            for (int i = 0; i < SLOTS; i++) {
                ItemStack stack = incoming[i];
                if (stack == null || moved.containsKey(stack)) continue;
                ItemStack old = slots[i];
                TabletIdle visit = old != null && ItemStack.matches(old, stack) ? visits.remove(old) : null;
                moved.put(stack, visit == null ? new TabletIdle(clock) : visit);
            }
            visits.clear();
            visits.putAll(moved);
            moved.clear();
            System.arraycopy(incoming, 0, slots, 0, SLOTS);
        }
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i] != null) visits.get(slots[i]).select(i == inventory.selected || i == SLOTS - 1, clock);
        }
    }

    static TabletIdle.Face face(ItemStack stack) {
        if (stack == carried && carried != null) return carriedFace;
        TabletIdle visit = visits.get(stack);
        // A world item, display model or creative palette has no local equip history.
        return visit == null ? TabletIdle.Face.SQUINT : visit.frame(clock);
    }

    static void carry(ItemStack stack, TabletIdle.Face face) {
        carried = stack;
        carriedFace = face;
    }

    private TabletFaces() {}
}
