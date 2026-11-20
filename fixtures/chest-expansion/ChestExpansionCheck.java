package dev.anya.storage.pocket;

import dev.anya.storage.BasePermissions;
import dev.anya.storage.ChestTransactions;
import dev.anya.storage.Content;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.Transactions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.registries.OfflineRegistries;

/** No level or server. The scanner reads a position-indexed fixture of real mod block entities. */
public final class ChestExpansionCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static StorageEntity chest(PocketLedger.Pocket room, BlockPos pos) {
        var result = new StorageEntity(pos, Content.CRATE.get().defaultBlockState());
        result.owner = room.owner();
        result.pocketId = room.id();
        result.storageIdentity();
        return result;
    }

    private static List<StorageEntity> scan(PocketLedger.Pocket room, Map<BlockPos,StorageEntity> cells)
            throws IOException {
        return PocketRoomLayout.present(room, pos -> true, pos -> !cells.containsKey(pos), cells::get);
    }

    private static void refused(PocketLedger.Pocket room, Map<BlockPos,StorageEntity> cells, String fragment)
            throws IOException {
        try {
            scan(room, cells);
            throw new AssertionError("Accepted bad registered chest: " + fragment);
        } catch (IOException expected) {
            require(expected.getMessage().contains(fragment), "Unexpected refusal: " + expected.getMessage());
        }
    }

    private static StorageEntity saveReopen(StorageEntity entity, RegistryAccess registries, Path file)
            throws IOException {
        NbtIo.writeCompressed(entity.saveWithFullMetadata(registries), file);
        var tag = NbtIo.readCompressed(file, NbtAccounter.create(2_097_152L));
        var opened = BlockEntity.loadStatic(entity.getBlockPos(), entity.getBlockState(), tag, registries);
        require(opened instanceof StorageEntity && opened != entity, "Expected a new mod block entity");
        return (StorageEntity) opened;
    }

    public static void main(String[] args) throws IOException {
        Path scratch = Path.of(args[0]);
        RegistryAccess registries = OfflineRegistries.setup(scratch);
        var ledger = new PocketLedger(scratch);
        UUID owner = UUID.randomUUID(), builder = UUID.randomUUID();
        var hub = new StorageEntity(BlockPos.ZERO, Content.HUB.get().defaultBlockState());
        hub.owner = owner;
        var anchor = new PocketLedger.Anchor("minecraft:overworld", 0, hub.storageIdentity(), 5);
        var room = ledger.reserve(owner, anchor, "galaxy", 8);
        ledger.stage(room.id(), PocketLedger.Stage.RESERVED, PocketLedger.Stage.BUILDING);
        room = ledger.stage(room.id(), PocketLedger.Stage.BUILDING, PocketLedger.Stage.READY);
        var cells = new HashMap<BlockPos,StorageEntity>();
        var starters = PocketService.chestPositions(room);
        for (var pos : starters) cells.put(pos, chest(room, pos));
        var pads = PocketRoomLayout.pads(room);
        require(pads.size() == 4 && scan(room, cells).size() == 4, "Four starters and four optional pads");
        require(!RoomEditor.canAddChest(3, 8) && !RoomEditor.canAddChest(4, 4) && RoomEditor.canAddChest(4, 5),
                "Initial placement budget");
        var optional = new ArrayList<StorageEntity>();
        int[] xs = {6, 10, 22, 26};
        for (int i = 0; i < pads.size(); i++) {
            var pad = pads.get(i);
            require(pad.equals(new BlockPos(room.x()+xs[i], 65, room.z()+14)), "Wrong pad " + i);
            require(RoomBounds.chestPad(xs[i], 65, 14) && ChestPadGuide.nearestFloor(room, pad).equals(pad.below()),
                    "Guide points at the wrong floor");
            optional.add(chest(room, pad));
        }
        // Leave visible holes before filling the intervening pads.
        int expectedMask = 0x0f;
        for (int i : List.of(3, 0, 2, 1)) {
            var pad = pads.get(i);
            cells.put(pad, optional.get(i));
            var registered = scan(room, cells);
            require(registered.size() == cells.size() && registered.contains(optional.get(i)), "Registration after add");
            int mask = 0x0f;
            for (int padIndex = 0; padIndex < pads.size(); padIndex++) {
                if (registered.contains(optional.get(padIndex))) mask |= 1 << (padIndex+4);
            }
            expectedMask |= 1 << (i+4);
            require(mask == expectedMask, "Physical occupancy for sparse example");
            var drawn = new ArrayList<Integer>();
            ChestLayoutProjection.forEachChest(mask, (bit, x, y) -> drawn.add(bit));
            require(drawn.size() == registered.size() && drawn.contains(i+4), "Projected registered pad");
            if (i == 3) {
                require(!RoomEditor.canAddChest(registered.size(), 5), "Cap five after adding the fourth optional pad");
                require(drawn.equals(List.of(0, 1, 2, 3, 7)) && registered.get(4) == cells.get(pads.get(3)),
                        "Sparse example uses the real fourth position");
            }
            if (i == 2) require(RoomEditor.canAddChest(registered.size(), 8), "Seventh chest leaves a free slot");
        }
        require(!RoomEditor.canAddChest(scan(room, cells).size(), 32), "Four optional pads are the physical ceiling");
        var stock = new ItemStack(Items.DIAMOND, 10);
        stock.set(DataComponents.CUSTOM_NAME, Component.literal("Offline sample"));
        require(RoomEditor.CHEST_EDIT_RIGHTS == (BasePermissions.BUILD | BasePermissions.VIEW
                | BasePermissions.DEPOSIT | BasePermissions.WITHDRAW), "Production chest edit rights");
        int rights = BasePermissions.VISIT | RoomEditor.CHEST_EDIT_RIGHTS;
        ChestTransactions.populate(hub, builder, rights, optional, stock);

        var restoredRoom = new PocketLedger(scratch).get(room.id());
        require(room.equals(restoredRoom), "Reopen room identity, owner, stage and anchor");
        var restoredHub = saveReopen(hub, registries, scratch.resolve("hub.nbt"));
        require(restoredHub.permitsMember(builder, rights) && !restoredHub.permitsMember(UUID.randomUUID(), rights),
                "Reopened hub permissions");
        var restored = new HashMap<BlockPos,StorageEntity>();
        for (var entry : cells.entrySet()) {
            var pos = entry.getKey();
            var original = entry.getValue();
            var reopened = saveReopen(original, registries, scratch.resolve("chest-" + pos.asLong() + ".nbt"));
            require(original.storageIdentity().equals(reopened.existingStorageIdentity()) && reopened.getContainerSize() == 54,
                    "Reopened 54-slot physical chest identity");
            for (int slot = 0; slot < 54; slot++) {
                require(Transactions.same(original.getItem(slot), reopened.getItem(slot)), "Lost item components/count/slot");
            }
            restored.put(pos, reopened);
        }
        require(scan(restoredRoom, restored).size() == 8, "Reopened physical layout");
        // Put a copied chest record at another physical position, as opposed to aliasing a Java reference.
        var copy = chest(room, pads.get(0));
        copy.loadWithComponents(restored.get(pads.get(3)).saveWithFullMetadata(registries), registries);
        var good = restored.put(pads.get(0), copy);
        refused(room, restored, "Duplicate physical chest identity");
        restored.put(pads.get(0), chest(room, pads.get(0).above()));
        refused(room, restored, "Registered pocket chest changed");
        restored.put(pads.get(0), good);
        try {
            PocketRoomLayout.present(room, pos -> true, pads.get(0)::equals, restored::get);
            throw new AssertionError("Accepted a block entity on a pad reporting air");
        } catch (IOException expected) {
            require(expected.getMessage().contains("still has a block entity"), "Unexpected orphan result");
        }
        var unavailable = pads.get(2);
        try {
            PocketRoomLayout.present(room, pos -> !pos.equals(unavailable),
                    pos -> { require(!pos.equals(unavailable), "Queried unloaded pad air"); return !restored.containsKey(pos); },
                    pos -> { require(!pos.equals(unavailable), "Queried unloaded pad entity"); return restored.get(pos); });
            throw new AssertionError("Accepted an unavailable registered pad");
        } catch (IOException expected) {
            require(expected.getMessage().contains("not loaded"), "Unexpected unloaded result");
        }
        good.owner = UUID.randomUUID();
        refused(room, restored, "Registered pocket chest changed");
        good.owner = owner;
        restored.remove(starters.get(0));
        refused(room, restored, "Registered pocket chest changed");
        System.out.println("PASS: four registered pads, sparse guide/preview, capacity and identity checks; real "
                + "transaction commit/replay/rollback, delegated rights, and eight physical chest NBT round trips");
    }
}
