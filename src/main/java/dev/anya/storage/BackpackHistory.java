package dev.anya.storage;

import java.io.IOException;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import static dev.anya.storage.BagHistoryStore.State.*;

/** Recovery owns an exact stack only after the server witnesses its terminal loss. A sighting is never a claim. */
@EventBusSubscriber(modid = AnyaStorage.ID)
public final class BackpackHistory {
    public record Row(UUID id, UUID owner, String ownerName, String bagName, long time, String status,
                      boolean restorable, UUID lossId) {}

    private static final String OWNER = "AnyaBagDropOwner", NAME = "AnyaBagDropName";
    private static final Set<UUID> queued = new LinkedHashSet<>();
    private static BagHistoryStore active;
    private static boolean processing;
    private static long lastError;

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        queued.clear();
        try {
            active = BagHistoryStore.openIfExists(event.getServer());
            if (active != null) for (var row : active.entries())
                if (row.state() == PENDING || row.state() == DELIVERING) queued.add(row.id());
        } catch (IOException ex) { report(ex); }
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent event) {
        queued.clear(); active = null; processing = false; lastError = 0;
    }

    static void markDrop(ItemEntity entity, UUID owner, String name) {
        if (owner == null || !BackpackAccess.isBag(entity.getItem())) return;
        BagInventory.initialize(entity.getItem());
        entity.getPersistentData().putUUID(OWNER, owner);
        entity.getPersistentData().putString(NAME, name);
    }
    @SubscribeEvent public static void toss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && !(player instanceof FakePlayer))
            markDrop(event.getEntity(), player.getUUID(), player.getGameProfile().getName());
    }
    @SubscribeEvent public static void deathDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer))
            for (ItemEntity drop : event.getDrops()) markDrop(drop, player.getUUID(), player.getGameProfile().getName());
    }

    /** Invoked at the final discard for fatal item damage, void loss or expiration after all extensions. */
    public static void onLoss(ItemEntity entity, Runnable discard) {
        ItemStack stack = entity.getItem();
        if (!(entity.level() instanceof ServerLevel level) || entity.isRemoved() || !BackpackAccess.isBag(stack)) {
            discard.run(); return;
        }
        var data = entity.getPersistentData();
        if (!data.hasUUID(OWNER) && entity.getOwner() instanceof ServerPlayer owner && !(owner instanceof FakePlayer))
            markDrop(entity, owner.getUUID(), owner.getGameProfile().getName());
        if (!data.hasUUID(OWNER)) { discard.run(); return; }
        UUID owner = data.getUUID(OWNER);
        var bagData = BagInventory.data(stack);
        if (!bagData.hasUUID("BagId")) { discard.run(); return; }
        UUID id = bagData.getUUID("BagId");
        BagHistoryStore.Entry capturing;
        try {
            active = BagHistoryStore.open(level.getServer());
            var old = active.get(id);
            long now = System.currentTimeMillis();
            var proof = new CompoundTag();
            proof.putUUID("Entity", entity.getUUID());
            proof.putString("Dimension", level.dimension().location().toString());
            if (old != null && old.state() == CAPTURING && old.proof().equals(proof)
                    && old.owner().equals(owner) && ItemStack.matches(stack, old.restoreStack(level.registryAccess()))) {
                capturing = old;
            } else if (old == null || old.state() == OBSERVED || old.state() == LOCATED
                    || old.state() == RETURNED && !old.proof().equals(proof)) {
                capturing = BagHistoryStore.Entry.observed(id, owner, data.getString(NAME), stack.getHoverName().getString(), now)
                        .capture(stack, level.registryAccess(), now, UUID.randomUUID()).withProof(proof);
                active.put(old, capturing);
            } else {
                // A replay of the same lost entity, or another copy during a pending loss, cannot mint a return.
                if (old.state() != CONFLICT) active.put(old, old.conflict());
                queued.remove(id);
                capturing = null;
            }
        } catch (IOException | RuntimeException ex) {
            // The dropped bag is still physically present. Preserve it when the journal cannot take custody.
            report(ex); entity.setExtendedLifetime(); return;
        }
        discard.run();
        if (capturing == null) return;
        try {
            if (entity.getRemovalReason() == Entity.RemovalReason.DISCARDED
                    && ItemStack.matches(stack, capturing.restoreStack(level.registryAccess()))) {
                active.put(capturing, capturing.withState(AVAILABLE));
            } else {
                active.put(capturing, capturing.conflict());
            }
        } catch (IOException | RuntimeException ex) { report(ex); }
    }

    private static boolean operator(ServerPlayer player) {
        return player != null && !(player instanceof FakePlayer) && player.getServer() != null
                && player.getServer().isSameThread() && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && player.hasPermissions(2);
    }

    public static List<Row> page(ServerPlayer operator, int page) {
        if (!operator(operator) || page < 0) return List.of();
        try {
            active = BagHistoryStore.openIfExists(operator.getServer());
            if (active == null) return List.of();
            return active.entries().stream().filter(r -> r.state() == AVAILABLE || r.state() == CAPTURING
                            || (r.state() == PENDING || r.state() == DELIVERING) && !queued.contains(r.id()))
                    .sorted(Comparator.comparingLong(BagHistoryStore.Entry::time).reversed().thenComparing(BagHistoryStore.Entry::id))
                    .skip((long)page * 6).limit(6)
                    .map(r -> new Row(r.id(), r.owner(), r.ownerName(), r.bagName(), r.time(),
                            r.state() == AVAILABLE ? "Ready to return" : "Check interrupted return", true, r.transaction())).toList();
        } catch (IOException ex) { report(ex); AnyaStorage.tell(operator, "Backpack history is unavailable."); return List.of(); }
    }

    public static String restore(ServerPlayer operator, UUID id, UUID loss) {
        if (!operator(operator)) return "Operator permission required.";
        if (processing) return "A backpack return is already being checked.";
        processing = true;
        try {
            var server = operator.getServer();
            active = BagHistoryStore.openIfExists(server);
            var row = active == null ? null : active.get(id);
            if (row == null || !loss.equals(row.transaction())
                    || !(row.state() == CAPTURING || row.state() == AVAILABLE || row.state() == PENDING || row.state() == DELIVERING))
                return "That recovery link is no longer available.";
            if (row.state() == CAPTURING) {
                // A server may stop between the final vanilla discard and the loss confirmation.
                // Resume custody only when this particular entity was journaled and no live bag remains.
                if (!row.proof().hasUUID("Entity") || row.proof().getString("Dimension").isBlank())
                    return "The server could not confirm this loss.";
                row.restoreStack(server.registryAccess());
                verifyAbsent(server, id);
                if (!operator(operator)) return "Operator permission required.";
                var confirmed = row.withState(AVAILABLE);
                active.put(row, confirmed);
                row = confirmed;
            }
            if (row.state() == AVAILABLE) {
                row.restoreStack(server.registryAccess());
                var owner = server.getPlayerList().getPlayer(row.owner());
                if (owner == null || !ready(owner) || blockedByEarlierDelivery(row)) verifyAbsent(server, id);
                if (!operator(operator)) return "Operator permission required.";
                var approved = row.withState(PENDING);
                active.put(row, approved);
                row = approved;
            }
            queued.add(id);
            return deliver(server, row, operator);
        } catch (BagHistoryPresence.Present found) {
            return cancelExisting(id);
        } catch (IOException | RuntimeException ex) {
            queued.remove(id); report(ex);
            return "Return paused. " + reason(ex);
        } finally { processing = false; }
    }

    private static String cancelExisting(UUID id) {
        queued.remove(id);
        try {
            var row = active.get(id);
            if (row != null) active.put(row, row.withState(LOCATED));
        } catch (IOException ex) { report(ex); return "The original backpack still exists. Return paused."; }
        return "The original backpack still exists. Its recovery link is closed.";
    }

    private static void verifyAbsent(MinecraftServer server, UUID id) throws IOException {
        if (!server.saveEverything(true, true, true)) throw new IOException("The world save could not be confirmed.");
        // Open menu cursors and crafting slots can hold a stack outside persisted inventories.
        var live = new BagHistoryPresence.LiveStacks(id);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            live.check(player.containerMenu.getCarried(), server.registryAccess());
            live.check(player.inventoryMenu.getCarried(), server.registryAccess());
            for (var menuSlot : player.containerMenu.slots)
                live.check(menuSlot.getItem(), server.registryAccess());
            for (var source : BackpackAccess.all(player))
                live.check(source.stack(), server.registryAccess());
        }
        BagHistoryPresence.verifyAbsent(server, id);
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (active == null || processing) return;
        if (server.getTickCount() % 1200 == 0) {
            try { active.prune(); } catch (IOException ex) { report(ex); }
        }
        if (server.getTickCount() % 20 != 0 || queued.isEmpty()) return;
        processing = true;
        try {
            for (UUID id : List.copyOf(queued)) {
                try {
                    var row = active.get(id);
                    if (row == null || !(row.state() == PENDING || row.state() == DELIVERING)) { queued.remove(id); continue; }
                    var owner = server.getPlayerList().getPlayer(row.owner());
                    if (owner == null || row.state() != DELIVERING && !ready(owner)) continue;
                    // A full inventory may still have an already-committed receipt to acknowledge.
                    if (!ready(owner) && !BagDeliveryReceipt.matches(owner, row.transaction(), true)) continue;
                    if (blockedByEarlierDelivery(row)) continue;
                    deliver(server, row, null);
                    break;
                } catch (BagHistoryPresence.Present found) {
                    cancelExisting(id); break;
                } catch (IOException | RuntimeException ex) {
                    queued.remove(id); report(ex); break; // operator can explicitly retry a failed disk check.
                }
            }
        } finally { processing = false; }
    }

    private static boolean ready(ServerPlayer owner) {
        return owner.isAlive() && !owner.isSpectator() && owner.containerMenu == owner.inventoryMenu
                && owner.inventoryMenu.getCarried().isEmpty() && owner.getInventory().getFreeSlot() >= 0;
    }
    private static boolean blockedByEarlierDelivery(BagHistoryStore.Entry row) throws IOException {
        if (row.state() == DELIVERING) return false;
        for (var other : active.entries())
            if (other.state() == DELIVERING && other.owner().equals(row.owner())) return true;
        return false;
    }

    private static String deliver(MinecraftServer server, BagHistoryStore.Entry row, ServerPlayer requestingOperator) throws IOException {
        UUID loss = row.transaction(), id = row.id();
        ServerPlayer owner = server.getPlayerList().getPlayer(row.owner());
        if (owner == null) return "Queued for " + row.ownerName() + " when they come online.";
        if (row.state() == DELIVERING && BagDeliveryReceipt.matches(owner, loss, true)) {
            // The receipt was written alongside the item, even if the owner moved it afterwards.
            if (!BagDeliveryReceipt.durable(owner, loss, true)) BagDeliveryReceipt.save(owner, loss, true, -1, null);
            active.put(row, row.withState(RETURNED)); queued.remove(id);
            return "Delivery to " + row.ownerName() + " confirmed.";
        }
        if (!ready(owner)) return "Queued for " + row.ownerName() + " until a backpack slot is free in their inventory.";
        if (blockedByEarlierDelivery(row)) return "Queued behind an earlier return to this owner.";
        if (row.state() == PENDING) {
            BagDeliveryReceipt.save(owner, loss, false, -1, null);
            var intent = row.withState(DELIVERING);
            active.put(row, intent); row = intent;
        } else if (!BagDeliveryReceipt.matches(owner, loss, false) || !BagDeliveryReceipt.durable(owner, loss, false)) {
            throw new IOException("The owner's saved delivery receipt could not be confirmed.");
        }
        // Saving can call other mods. Inspect the saved and live inventories after recording intent.
        verifyAbsent(server, id);
        if (requestingOperator != null && !operator(requestingOperator)) {
            queued.remove(id);
            throw new IOException("Operator permission changed during recovery.");
        }
        if (server.getPlayerList().getPlayer(row.owner()) != owner || !ready(owner))
            return "Queued until the owner is available.";
        if (active.get(id) != row) throw new IOException("That return changed or expired.");
        ItemStack bag = row.restoreStack(server.registryAccess());
        int slot = owner.getInventory().getFreeSlot();
        BagDeliveryReceipt.insertPrepared(owner, loss, slot, bag);
        BagDeliveryReceipt.save(owner, loss, true, slot, bag);
        active.put(row, row.withState(RETURNED)); queued.remove(id);
        owner.inventoryMenu.broadcastChanges();
        AnyaStorage.tell(owner, "Your backpack was returned to your inventory.");
        return "Returned " + row.bagName() + " to " + row.ownerName() + ".";
    }

    private static String reason(Exception ex) {
        return ex instanceof IOException && ex.getMessage() != null ? ex.getMessage() : "The server could not verify a safe return.";
    }
    private static void report(Exception ex) {
        long now = System.currentTimeMillis();
        if (now - lastError > 10_000) {
            AnyaStorage.LOG.warn("Backpack history: {}", reason(ex));
            lastError = now;
        }
    }
    private BackpackHistory() {}
}
