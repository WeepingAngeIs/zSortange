package dev.anya.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.List;

/** Per-backpack controls for an installed Advanced Feeding disc. */
public final class AdvancedFeeding {
    private static final String UPGRADE = "advanced_feeding";
    private static final String MINIMUM = "FeedMinMissing";
    private static final String PRIORITY = "FeedPriority";
    private static final String UNAVAILABLE = "Advanced Feeding must be installed and enabled in this backpack.";

    public static int minimumMissing(BagInventory bag) {
        return minimumMissing(BagInventory.data(bag.stack));
    }

    private static int minimumMissing(CompoundTag data) {
        int value = data.getInt(MINIMUM);
        return value >= 2 && value <= 10 ? value : 4;
    }

    public static List<String> priority(BagInventory bag) {
        return priority(BagInventory.data(bag.stack));
    }

    private static List<String> priority(CompoundTag data) {
        return data.getList(PRIORITY, Tag.TAG_STRING).stream().map(Tag::getAsString).toList();
    }

    private static boolean available(BagInventory bag) {
        return bag.player.serverLevel().getServer().isSameThread() && bag.valid()
                && Upgrades.active(bag, UPGRADE) && !Upgrades.installed(bag, "feeding");
    }

    private static void save(BagInventory bag, CompoundTag data) {
        BagInventory.data(bag.stack, data);
        bag.hostChanged();
    }

    public static String setMinimumMissing(BagInventory bag, int amount) {
        if (!available(bag)) return UNAVAILABLE;
        if (amount < 2 || amount > 10) return "Choose between 2 and 10 missing hunger points.";
        CompoundTag data = BagInventory.data(bag.stack);
        if (minimumMissing(data) != amount) {
            data.putInt(MINIMUM, amount);
            save(bag, data);
        }
        return "";
    }

    public static String toggleFood(BagInventory bag, String itemId) {
        if (!available(bag)) return UNAVAILABLE;
        if (!Config.validId(itemId) || !Upgrades.SAFE_FOOD.contains(BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId))))
            return "Choose a plain food from the safe vanilla foods list.";
        CompoundTag data = BagInventory.data(bag.stack);
        ListTag foods = data.getList(PRIORITY, Tag.TAG_STRING).copy();
        StringTag rule = StringTag.valueOf(itemId);
        if (!foods.remove(rule)) foods.add(rule);
        if (foods.isEmpty()) data.remove(PRIORITY);
        else data.put(PRIORITY, foods);
        save(bag, data);
        return "";
    }

    public static String clearPriority(BagInventory bag) {
        if (!available(bag)) return UNAVAILABLE;
        CompoundTag data = BagInventory.data(bag.stack);
        if (data.contains(PRIORITY)) {
            data.remove(PRIORITY);
            save(bag, data);
        }
        return "";
    }

    private static boolean canFeed(BagInventory bag) {
        ServerPlayer player = bag.player;
        return bag.playerSlot != BackpackAccess.GROUND_SLOT && player.containerMenu == player.inventoryMenu
                && !player.isUsingItem() && available(bag);
    }

    private static boolean fits(ServerPlayer player, ItemStack food, int missing) {
        if (food.isEmpty() || !Upgrades.SAFE_FOOD.contains(food.getItem())
                || !ItemStack.isSameItemSameComponents(food, new ItemStack(food.getItem()))) return false;
        var properties = food.getFoodProperties(player);
        return properties != null && properties.nutrition() > 0 && properties.nutrition() <= missing
                && properties.effects().isEmpty() && properties.usingConvertsTo().isEmpty();
    }

    private static int choose(BagInventory bag, List<String> rules, int missing) {
        int bestSlot = -1, bestRank = Integer.MAX_VALUE;
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            ItemStack food = bag.getItem(slot);
            if (!fits(bag.player, food, missing)) continue;
            int rank = rules.isEmpty() ? 0 : rules.indexOf(Categories.id(food));
            if (rank == 0) return slot;
            if (rank > 0 && rank < bestRank) {
                bestRank = rank;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    static void feed(BagInventory bag) {
        if (!canFeed(bag)) return;
        ServerPlayer player = bag.player;
        CustomData previous = bag.stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag settings = BagInventory.data(bag.stack);
        int minimum = minimumMissing(settings);
        int missing = 20 - player.getFoodData().getFoodLevel();
        if (missing < minimum) return;
        int slot = choose(bag, priority(settings), missing);
        if (slot < 0) return;
        ItemStack meal = bag.getItem(slot).copyWithCount(1);
        Transactions.Plan plan = new Transactions.Plan(List.of(bag), () -> {
            int stillMissing = 20 - player.getFoodData().getFoodLevel();
            return canFeed(bag) && bag.stack.get(DataComponents.CUSTOM_DATA) == previous && stillMissing >= minimum
                    && ItemStack.isSameItemSameComponents(bag.getItem(slot), meal) && fits(player, meal, stillMissing);
        });
        plan.work(bag)[slot].shrink(1);
        plan.authorizeRecipe(List.of(meal), List.of());
        if (!plan.commit(player.getServer()).isEmpty()) return;
        OnionHistory history = Onion156.before(player);
        // The committed withdrawal pays for one standard food finish. Keep the exact meal for the history entry.
        meal.copy().finishUsingItem(player.serverLevel(), player);
        if (history != null) Onion156.record(player, meal, history);
    }

    private record OnionHistory(Object data, ItemStack newest) {}

    /**
     * In solonion 1.5.6 only, SOLOnionEvent.onFoodEaten delegates to EVENT.eat(stack,player).
     * That records history, updates benefits and syncs. It neither eats a second meal nor posts a Finish event.
     * Keep this provider optional on both the client and server classpaths.
     */
    private record Onion156(Method active, Method capability, Object events, Method eat) {
        private static boolean warned;
        private static final Onion156 PROVIDER = load();

        private static Onion156 load() {
            try {
                var mod = ModList.get().getModContainerById("solonion");
                if (mod.isEmpty() || !mod.get().getModInfo().getVersion().toString().equals("1.5.6")) return null;
                Class<?> onion = Class.forName("team.creative.solonion.common.SOLOnion");
                Class<?> api = Class.forName("team.creative.solonion.api.SOLOnionAPI");
                Object events = onion.getField("EVENT").get(null);
                if (events == null) return null;
                return new Onion156(onion.getMethod("isActive", Player.class),
                        api.getMethod("getFoodCapability", Player.class), events,
                        events.getClass().getMethod("eat", ItemStack.class, Player.class));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                warn(error);
                return null;
            }
        }

        private static void warn(Throwable error) {
            if (!warned) {
                warned = true;
                AnyaStorage.LOG.warn("Advanced Feeding could not use Spice of Life Onion 1.5.6 food history", error);
            }
        }

        private static OnionHistory before(ServerPlayer player) {
            if (PROVIDER == null) return null;
            try {
                if (!Boolean.TRUE.equals(PROVIDER.active.invoke(null, player))) return null;
                Object data = PROVIDER.capability.invoke(null, player);
                var foods = ((Iterable<?>) data).iterator();
                return new OnionHistory(data, foods.hasNext() ? (ItemStack) foods.next() : null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                warn(error);
                return null;
            }
        }

        private static void record(ServerPlayer player, ItemStack meal, OnionHistory prior) {
            OnionHistory current = before(player);
            // The provider inserts a fresh copy at the head even when two consecutive meals look identical.
            if (current == null || current.data() != prior.data() || current.newest() != prior.newest()) return;
            try {
                PROVIDER.eat.invoke(PROVIDER.events, meal, player);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                // The provider may already have recorded the meal before a benefit or network call failed.
                warn(error);
            }
        }
    }

    private AdvancedFeeding() {}
}
