package dev.anya.storage;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** The packed vanilla inventory in a version-two Rosecourt snapshot is itself a version-one NBT document. */
final class RosecourtInventory {
    private static final int MAX_SNAPSHOT = 4 << 20;
    private static final Set<String> VANILLA_KEYS = Set.of("version", "vanillaSlots", "items");
    private static final Set<String> EQUIPMENT_KEYS = Set.of("version", "dependencies", "policy", "runtime", "selected",
            "vanilla", "holder", "backpack_records");
    private static final Set<String> POLICIES = Set.of("native-default-equipment-v1",
            "native-default-equipment-packed-coins-v2", "native-default-equipment-packed-wallet-v1");
    private static final String DEPENDENCIES =
            "accessories=1.1.0-beta.53+1.21.1;accessories_compat_layer=0.1.12;curios=9.5.1+1.21.1";

    private final UUID bag;
    private final long deadline;
    private final NbtAccounter budget = NbtAccounter.create(16L << 20);
    private int nodes;
    private boolean found;

    private RosecourtInventory(UUID bag, long deadline) { this.bag = bag; this.deadline = deadline; }

    static boolean contains(UUID bagId, byte[] snapshot, long deadlineNanos) throws IOException {
        var scan = new RosecourtInventory(bagId, deadlineNanos);
        try {
            var root = scan.decode(snapshot);
            require(root.contains("version", Tag.TAG_INT));
            switch (root.getInt("version")) {
                case 1 -> scan.vanilla(root);
                case 2 -> scan.equipment(root);
                default -> throw invalid();
            }
            scan.time();
            return scan.found;
        } catch (RuntimeException | StackOverflowError failure) {
            throw new IOException("Rosecourt inventory could not be inspected within the recovery limits.", failure);
        }
    }

    private CompoundTag decode(byte[] data) throws IOException {
        time();
        require(data.length >= 4 && data.length <= MAX_SNAPSHOT && data[0] == Tag.TAG_COMPOUND
                && data[1] == 0 && data[2] == 0);
        try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
            var root = NbtIo.read(in, budget);
            require(in.available() == 0);
            time();
            return root;
        }
    }

    private void vanilla(CompoundTag root) throws IOException {
        require(root.getAllKeys().equals(VANILLA_KEYS) && root.contains("version", Tag.TAG_INT)
                && root.getInt("version") == 1 && root.contains("vanillaSlots", Tag.TAG_INT)
                && root.getInt("vanillaSlots") == 41);
        rows(root, "items", true);
        visit(root, 0);
    }

    private void equipment(CompoundTag root) throws IOException {
        require(EQUIPMENT_KEYS.containsAll(root.getAllKeys()) && DEPENDENCIES.equals(root.getString("dependencies"))
                && POLICIES.contains(root.getString("policy")) && root.getString("runtime").matches("[0-9a-f]{64}")
                && root.contains("selected", Tag.TAG_INT) && root.getInt("selected") >= 0 && root.getInt("selected") < 9
                && root.contains("vanilla", Tag.TAG_BYTE_ARRAY) && root.contains("holder", Tag.TAG_COMPOUND));
        vanilla(decode(root.getByteArray("vanilla")));
        var holder = root.getCompound("holder");
        require(holder.getAllKeys().equals(Set.of("accessories_containers"))
                && holder.contains("accessories_containers", Tag.TAG_COMPOUND));
        var groups = holder.getCompound("accessories_containers");
        for (String name : groups.getAllKeys()) {
            time();
            require(groups.contains(name, Tag.TAG_COMPOUND));
            var group = groups.getCompound(name);
            rows(group, "items", false);
            rows(group, "cosmetics", false);
        }
        if (root.contains("backpack_records")) {
            require(root.contains("backpack_records", Tag.TAG_COMPOUND));
            var records = root.getCompound("backpack_records");
            for (String key : records.getAllKeys()) {
                time();
                require(UUID.fromString(key).toString().equals(key) && records.contains(key, Tag.TAG_COMPOUND));
                var record = records.getCompound(key);
                require(Set.of("contents", "access_log").containsAll(record.getAllKeys())
                        && record.contains("contents", Tag.TAG_COMPOUND)
                        && (!record.contains("access_log") || record.contains("access_log", Tag.TAG_COMPOUND)));
            }
        }
        // Includes native provider contents and accessory stacks, even below a vanilla container component.
        for (String key : root.getAllKeys()) {
            if (!key.equals("vanilla")) visit(root.get(key), 0); // already decoded and inspected above
        }
    }

    private void rows(CompoundTag parent, String name, boolean vanilla) throws IOException {
        require(parent.get(name) instanceof ListTag);
        var items = (ListTag) parent.get(name);
        require((items.isEmpty() || items.getElementType() == Tag.TAG_COMPOUND) && items.size() <= (vanilla ? 41 : 2048));
        var slots = new HashSet<Integer>();
        for (Tag raw : items) {
            time();
            var stack = (CompoundTag) raw;
            require(stack.contains("Slot", vanilla ? Tag.TAG_BYTE : Tag.TAG_INT)
                    && stack.contains("id", Tag.TAG_STRING) && ResourceLocation.tryParse(stack.getString("id")) != null
                    && (!stack.contains("components") || stack.contains("components", Tag.TAG_COMPOUND)));
            int slot = vanilla ? Byte.toUnsignedInt(stack.getByte("Slot")) : stack.getInt("Slot");
            require(slots.add(slot) && (vanilla ? slot < 36 || slot >= 100 && slot <= 103 || slot == 150 : slot >= 0));
        }
    }

    private void visit(Tag tag, int depth) throws IOException {
        if (depth > 256 || ++nodes > 1_000_000) throw invalid();
        if ((nodes & 255) == 0) time();
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("BagId")) {
                require(compound.hasUUID("BagId"));
                if (bag.equals(compound.getUUID("BagId"))) found = true;
            }
            for (String key : compound.getAllKeys()) visit(compound.get(key), depth + 1);
        } else if (tag instanceof ListTag list) {
            for (Tag member : list) visit(member, depth + 1);
        } else if (tag instanceof ByteArrayTag bytes && bytes.size() > 0) {
            // Other packed data can conceal a provider inventory; its absence cannot be established.
            throw new IOException("Rosecourt holds an item with unrecognized packed data. Recovery is paused.");
        }
    }

    private void time() throws IOException {
        if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted())
            throw new IOException("The Rosecourt inventory check exceeded its time limit.");
    }

    private static IOException invalid() {
        return new IOException("Unrecognized Rosecourt inventory. Recovery is paused.");
    }
    private static void require(boolean valid) throws IOException { if (!valid) throw invalid(); }
}
