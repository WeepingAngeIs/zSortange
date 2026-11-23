package dev.anya.storage;

import java.io.File;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;

/** The delivery receipt and the inventory are serialized in the same vanilla player record. */
final class BagDeliveryReceipt {
    private static final String KEY = "AnyaBagReturn";
    private static final long MAX_COMPRESSED = 16L << 20;
    private static final long MAX_NBT = 64L << 20;

    private static boolean online(ServerPlayer owner) {
        MinecraftServer server = owner.getServer();
        return !(owner instanceof FakePlayer) && server != null && server.isSameThread()
                && owner.connection != null && owner.connection.getConnection().isConnected() && !owner.hasDisconnected()
                && server.getPlayerList().getPlayer(owner.getUUID()) == owner;
    }

    private static void requireOwner(ServerPlayer owner) throws IOException {
        if (owner == null || !online(owner)) throw new IOException("The backpack owner must be online to confirm delivery");
    }

    static boolean matches(ServerPlayer owner, UUID loss, boolean done) {
        if (owner == null || !online(owner)) return false;
        try {
            return matchesData(owner.getPersistentData(), loss, done);
        } catch (IOException invalid) {
            return false;
        }
    }

    static boolean durable(ServerPlayer owner, UUID loss, boolean done) throws IOException {
        requireOwner(owner);
        return checkFiles(owner, loss, done, -1, null);
    }

    static void insertPrepared(ServerPlayer owner, UUID loss, int slot, ItemStack bag) throws IOException {
        requireOwner(owner);
        var inventory = owner.getInventory();
        var items = inventory.items;
        if (bag == null || !BackpackAccess.isBag(bag) || slot < 0 || slot >= items.size() || !items.get(slot).isEmpty())
            throw new IOException("The owner's delivery slot is unavailable");
        CompoundTag prepared = checkedReceipt(child(owner.getPersistentData(), Player.PERSISTED_NBT_TAG));
        if (prepared == null || !prepared.getUUID("Loss").equals(loss) || prepared.getBoolean("Done"))
            throw new IOException("A matching prepared delivery receipt is required");

        // These two plain writes must precede any callback or file access. Any subsequent save sees both.
        prepared.putBoolean("Done", true);
        items.set(slot, bag);
        inventory.setChanged();
    }

    static void save(ServerPlayer owner, UUID loss, boolean done, int slot, ItemStack expected) throws IOException {
        requireOwner(owner);
        if (loss == null) throw new IOException("Missing backpack loss ID");
        if (expected != null && (!BackpackAccess.isBag(expected) || slot < 0 || slot >= owner.getInventory().items.size()
                || !ItemStack.matches(expected, owner.getInventory().getItem(slot))))
            throw new IOException("The recovered backpack is not in the owner's delivery slot");
        CompoundTag intended = expected == null ? null : savedSlot(owner, slot, expected);
        CompoundTag data = owner.getPersistentData();
        CompoundTag persisted = child(data, Player.PERSISTED_NBT_TAG);
        CompoundTag previous = checkedReceipt(persisted);
        boolean completed = previous != null && previous.getUUID("Loss").equals(loss) && previous.getBoolean("Done");
        if (done != completed) throw new IOException("The backpack delivery receipt changed");
        MinecraftServer server = owner.getServer();
        boolean host = server.isSingleplayerOwner(owner.getGameProfile());
        Path playerFile = playerFile(owner);
        checkSavePaths(playerFile);
        if (host) checkSavePaths(levelFile(server));

        if (!done) {
            var receipt = new CompoundTag();
            receipt.putUUID("Loss", loss);
            receipt.putBoolean("Done", false);
            persisted.put(KEY, receipt);
            data.put(Player.PERSISTED_NBT_TAG, persisted);
        }
        // PlayerList.save has an existing access transformer. On an integrated host, level.dat's Data.Player
        // is preferred on the next login, so it must agree with the normal playerdata file as well.
        if (host) server.saveEverything(true, true, true);
        else server.getPlayerList().save(owner);
        requireOwner(owner);
        if ((intended != null && !intended.equals(savedSlot(owner, slot, owner.getInventory().getItem(slot))))
                || !checkFiles(owner, loss, done, slot, intended))
            throw new IOException("The saved owner receipt or backpack could not be confirmed");
    }

    private static boolean checkFiles(ServerPlayer owner, UUID loss, boolean done, int slot, CompoundTag expected) throws IOException {
        MinecraftServer server = owner.getServer();
        var disk = readForced(playerFile(owner));
        boolean playerMatches = checkPlayer(owner, disk, loss, done, slot, expected);
        if (!server.isSingleplayerOwner(owner.getGameProfile())) return playerMatches;
        CompoundTag level = readForced(levelFile(server));
        CompoundTag data = child(level, "Data");
        if (!data.contains("Player", Tag.TAG_COMPOUND)) throw new IOException("Missing single-player host inventory");
        return checkPlayer(owner, data.getCompound("Player"), loss, done, slot, expected) && playerMatches;
    }

    private static boolean checkPlayer(ServerPlayer owner, CompoundTag disk, UUID loss, boolean done,
                                       int slot, CompoundTag expected) throws IOException {
        if (!disk.hasUUID("UUID") || !disk.getUUID("UUID").equals(owner.getUUID()))
            throw new IOException("Unexpected owner in saved player data");
        if (!matchesData(child(disk, "NeoForgeData"), loss, done)) return false;
        if (expected == null) return true;
        var bagData = expected.getCompound("components").getCompound("minecraft:custom_data");
        if (!bagData.hasUUID("BagId") || !disk.contains("Inventory", Tag.TAG_LIST))
            throw new IOException("Missing backpack identity or saved inventory");
        UUID id = bagData.getUUID("BagId");
        ListTag inventory = disk.getList("Inventory", Tag.TAG_COMPOUND);
        int copies = 0, atSlot = 0;
        for (int i = 0; i < inventory.size(); i++) {
            var stored = inventory.getCompound(i);
            if (!stored.contains("Slot", Tag.TAG_BYTE)) throw new IOException("Invalid saved inventory slot");
            if (Byte.toUnsignedInt(stored.getByte("Slot")) == slot) {
                atSlot++;
                if (!stored.equals(expected)) return false;
            }
            if (!stored.getString("id").equals("anyastorage:backpack")) continue;
            var custom = stored.getCompound("components").getCompound("minecraft:custom_data");
            if (custom.hasUUID("BagId") && custom.getUUID("BagId").equals(id)) copies++;
        }
        return copies == 1 && atSlot == 1;
    }

    private static CompoundTag savedSlot(ServerPlayer owner, int slot, ItemStack stack) throws IOException {
        var destination = new CompoundTag();
        destination.putByte("Slot", (byte)slot);
        try {
            if (stack.save(owner.registryAccess(), destination) instanceof CompoundTag encoded) return encoded.copy();
            throw new IOException("Unrecognized backpack inventory encoding");
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot verify the recovered backpack's full components", invalid);
        }
    }

    private static boolean matchesData(CompoundTag data, UUID loss, boolean done) throws IOException {
        CompoundTag receipt = checkedReceipt(child(data, Player.PERSISTED_NBT_TAG));
        return receipt != null && receipt.getUUID("Loss").equals(loss) && receipt.getBoolean("Done") == done;
    }

    private static CompoundTag checkedReceipt(CompoundTag persisted) throws IOException {
        if (!persisted.contains(KEY)) return null;
        CompoundTag receipt = child(persisted, KEY);
        if (!receipt.hasUUID("Loss") || !receipt.contains("Done", Tag.TAG_BYTE)
                || (receipt.getByte("Done") != 0 && receipt.getByte("Done") != 1))
            throw new IOException("Invalid existing backpack delivery receipt");
        return receipt;
    }

    private static CompoundTag child(CompoundTag parent, String key) throws IOException {
        if (parent.contains(key) && !parent.contains(key, Tag.TAG_COMPOUND))
            throw new IOException("Invalid player receipt container");
        return parent.getCompound(key);
    }

    private static Path playerFile(ServerPlayer owner) throws IOException {
        Path root = checkedWorld(owner.getServer());
        Path directory = root.resolve("playerdata");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe or missing playerdata directory");
        return directory.resolve(owner.getUUID() + ".dat");
    }

    private static Path levelFile(MinecraftServer server) throws IOException { return checkedWorld(server).resolve("level.dat"); }

    private static Path checkedWorld(MinecraftServer server) throws IOException {
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe or missing recovery world");
        return root;
    }

    private static void checkSavePaths(Path file) throws IOException {
        for (Path candidate : new Path[]{file, file.resolveSibling(file.getFileName() + "_old")}) {
            if (!Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Unsafe player save target");
        }
    }

    private static CompoundTag readForced(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Saved player data is unavailable");
        final CompoundTag data;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() <= 0 || channel.size() > MAX_COMPRESSED) throw new IOException("Player save exceeds verification limits");
            channel.force(true);
            data = NbtIo.readCompressed(Channels.newInputStream(channel), NbtAccounter.create(MAX_NBT));
        } catch (RuntimeException invalid) {
            throw new IOException("Unreadable saved player receipt", invalid);
        }
        try (var directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        catch (AccessDeniedException ex) { if (File.separatorChar != '\\') throw ex; }
        catch (UnsupportedOperationException ignored) {}
        return data;
    }

    private BagDeliveryReceipt() {}
}
