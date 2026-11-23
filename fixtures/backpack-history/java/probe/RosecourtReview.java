package probe;

import java.io.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;

/** Test-only historical import, using the pinned Rosecourt binary's exact journal writers. */
final class RosecourtReview {
    static final String JAIL = "d7cd43db7d6c4ea88372a1a7e09faee843d51d566a03c398dc999505a8053243";
    private static final String PROVIDERS = "accessories=1.1.0-beta.53+1.21.1;accessories_compat_layer=0.1.12;curios=9.5.1+1.21.1";
    private static ClassLoader loader;

    private static void check(boolean value, String message) throws IOException {
        if (!value) throw new IOException(message);
    }
    private static Path world(MinecraftServer server) throws IOException {
        var path = server.getWorldPath(LevelResource.ROOT).toRealPath();
        check("true".equals(System.getenv("GITHUB_ACTIONS")) && "github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))
                && Set.of("history-world", "jail_certified_smoke").contains(path.getFileName().toString()), "Expected disposable hosted world");
        return path;
    }
    private static byte[] digest(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    private static Class<?> type(String name) throws Exception {
        if (loader == null) {
            var jail = Path.of(System.getenv("ANYA_ROSE_JAR")).toRealPath();
            check(HexFormat.of().formatHex(digest(Files.readAllBytes(jail))).equals(JAIL), "Incorrect Rosecourt writer binary");
            if (ModList.get().isLoaded("minecraftjail")) {
                check(Files.isSameFile(jail, ModList.get().getModFileById("minecraftjail").getFile().getFilePath()),
                        "Production admission and journal writers must share the verified JAR");
                loader = RosecourtReview.class.getClassLoader();
            } else {
                loader = new URLClassLoader(new java.net.URL[]{jail.toUri().toURL()}, RosecourtReview.class.getClassLoader());
            }
        }
        return Class.forName("dev.unyielding.jail." + name, true, loader);
    }
    static void verifyFullPack(MinecraftServer server) throws Exception {
        Path w = world(server);
        check(ModList.get().isLoaded("minecraftjail") && ModList.get().isLoaded("cobblemon")
                && ModList.get().isLoaded("twilightforest") && ModList.get().getMods().size() > 296, "Dedicated pack registry incomplete");
        check(Files.size(w.resolve("integrated-jail-v1/escrow.v1")) > 8 &&
                Files.size(w.resolve("custody-interface-v1/custody.v1")) > 8 &&
                Files.size(w.resolve("data/unyielding-jail-placement.before")) > 0, "Missing prior native admission and facility");
        check(Files.exists(Path.of("native-occupied.pass")), "Expected independent full-pack receipt");
        type("JailEscrowJournal");
    }
    private static byte[] encode(CompoundTag nbt) throws IOException {
        var out = new ByteArrayOutputStream();
        NbtIo.write(nbt, new DataOutputStream(out));
        return out.toByteArray();
    }
    private static CompoundTag decode(byte[] nbt) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbt)), NbtAccounter.create(8L << 20));
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void hold(Path dir, UUID tx, UUID owner, byte[] snapshot) throws Exception {
        var journal = type("JailEscrowJournal");
        var state = type("JailEscrowJournal$State");
        journal.getMethod("provisionNew", Path.class).invoke(null, dir);
        try (var active = (AutoCloseable)journal.getConstructor(Path.class).newInstance(dir)) {
            journal.getMethod("begin", UUID.class, UUID.class, byte[].class).invoke(active, tx, owner, snapshot);
            var step = journal.getMethod("transition", UUID.class, state, state);
            Object prepared = Enum.valueOf((Class)state, "PREPARED");
            Object started = Enum.valueOf((Class)state, "REMOVAL_STARTED");
            Object held = Enum.valueOf((Class)state, "HELD");
            step.invoke(active, tx, prepared, started);
            step.invoke(active, tx, started, held);
        }
    }
    private static String dir(UUID tx) { return "jail-import/" + tx; }
    static void checkRejectedJournals(MinecraftServer server, UUID tx, java.util.function.Consumer<String> tryRestore) throws Exception {
        Path w = world(server);
        check("history-world".equals(w.getFileName().toString()), "Deliberate damage is restricted to scoped synthetic records");
        Path file = w.resolve(dir(tx)).resolve("items/escrow.v1");
        byte[] original = Files.readAllBytes(file);
        byte[] broken = original.clone();
        broken[broken.length - 1] ^= 0x40;
        try {
            Files.write(file, broken, StandardOpenOption.TRUNCATE_EXISTING);
            tryRestore.accept("custody data is incomplete or unrecognized");
        } finally {
            Files.write(file, original, StandardOpenOption.TRUNCATE_EXISTING);
        }
        Path orphan = Files.createDirectory(w.resolve("jail-import/unbound-fixture")).resolve("escrow.v1");
        try {
            Files.write(orphan, original, StandardOpenOption.CREATE_NEW);
            tryRestore.accept("unindexed journal");
        } finally {
            Files.deleteIfExists(orphan);
            Files.delete(orphan.getParent());
        }
        String owner = UUID.randomUUID().toString();
        Path data = Files.createDirectories(w.resolve("cobblemonplayerdata").resolve(owner.substring(0, 2)))
                .resolve(owner + ".json");
        String empty = "{\"uuid\":\"" + owner + "\",\"starterPrompted\":false,\"starterSelected\":false,"
                + "\"starterLocked\":false,\"partySelectTutorialDone\":false,\"keyItems\":[],\"extraData\":{}}";
        try {
            Files.writeString(data, empty.replace("\"extraData\":{}", "\"extraData\":{\"test:unknown\":{}}"),
                    StandardOpenOption.CREATE_NEW);
            tryRestore.accept("unverified Cobblemon player-data extension");
        } finally {
            // The ordinary, supported player-data shape remains on disk through restart.
            Files.writeString(data, empty, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
    static void importHeld(MinecraftServer server, ServerPlayer owner, UUID tx, ItemStack bag, boolean full) throws Exception {
        Path w = world(server);
        var ledger = type("JailCustodyLedger");
        var journals = type("JailEscrowJournal");
        if (!full) {
            journals.getMethod("provisionNew", Path.class).invoke(null, w.resolve("integrated-jail-v1"));
            ledger.getMethod("provisionNew", Path.class).invoke(null, w.resolve("custody-interface-v1"));
        }
        Path root = Files.createDirectories(w.resolve(dir(tx)));
        String items = dir(tx) + "/items", origins = dir(tx) + "/origins";
        // The test inserts a synthetic historical original into a disposable player slot for one
        // snapshot, then removes it before binding. This is an import, never a new jail admission.
        int slot = owner.getInventory().getFreeSlot();
        check(slot >= 0, "Missing input slot for historical source");
        owner.getInventory().setItem(slot, bag.copy());
        Object port = type("JailVanillaPort").getConstructor(ServerPlayer.class, UUID.class, UUID.class).newInstance(owner, tx, null);
        Object view = port.getClass().getMethod("current").invoke(port);
        byte[] vanilla = (byte[])view.getClass().getMethod("inventory").invoke(view);
        check(ItemStack.matches(owner.getInventory().removeItemNoUpdate(slot), bag), "Imported source contents changed");
        var holder = new CompoundTag();
        holder.put("accessories_containers", new CompoundTag());
        var equipment = new CompoundTag();
        equipment.putInt("version", 2);
        equipment.putString("dependencies", PROVIDERS);
        equipment.putString("policy", "native-default-equipment-packed-coins-v2");
        equipment.putString("runtime", HexFormat.of().formatHex(digest("synthetic historical fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        equipment.putInt("selected", owner.getInventory().selected);
        equipment.putByteArray("vanilla", vanilla);
        equipment.put("holder", holder);
        byte[] snapshot = encode(equipment), checksum = digest(snapshot);
        hold(root.resolve("items"), tx, owner.getUUID(), snapshot);
        var intent = new CompoundTag();
        intent.putUUID("owner", owner.getUUID());
        intent.putUUID("transaction", tx);
        intent.putByteArray("custody", checksum);
        intent.putByteArray("before", encode(new CompoundTag()));
        byte[] body = encode(intent);
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(0x4a4f4c31); output.write(checksum); output.writeInt(body.length); output.write(body);
        }
        hold(root.resolve("origins"), tx, owner.getUUID(), bytes.toByteArray());
        BlockPos anchor = new BlockPos(owner.getBlockX(), server.overworld().getMaxBuildHeight() - 5, owner.getBlockZ());
        if (full) {
            String command = "jailcustody bind " + tx + " " + owner.getUUID() + " \"" + items + "\" \"" + origins + "\" "
                    + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ();
            check(server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack()) == 1,
                    "Native historical custody bind refused");
            var block = server.overworld().getBlockState(anchor).getBlock();
            check(BuiltInRegistries.BLOCK.getKey(block).toString().equals("unyieldingquests:custody_vault"),
                    "Native bind did not leave a physical custody vault");
        } else {
            var anchorType = type("JailCustodyLedger$Anchor");
            Object position = anchorType.getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance("minecraft:overworld", anchor.getX(), anchor.getY(), anchor.getZ());
            try (var active = (AutoCloseable)ledger.getConstructor(Path.class).newInstance(w.resolve("custody-interface-v1"))) {
                ledger.getMethod("bind", UUID.class, UUID.class, String.class, String.class, byte[].class, anchorType, String.class)
                        .invoke(active, tx, owner.getUUID(), items, origins, checksum, position, "hosted format fixture");
            }
        }
    }
    static void verifyHeld(MinecraftServer server, UUID tx, UUID owner, ItemStack bag, boolean full) throws Exception {
        Path w = world(server);
        var journal = type("JailEscrowJournal");
        try (var reader = (AutoCloseable)journal.getConstructor(Path.class).newInstance(w.resolve(dir(tx)).resolve("items"))) {
            Object entry = journal.getMethod("read", UUID.class).invoke(reader, tx);
            check(entry != null && owner.equals(entry.getClass().getMethod("owner").invoke(entry))
                    && "HELD".equals(entry.getClass().getMethod("state").invoke(entry).toString()), "Native held entry disappeared");
            byte[] bytes = (byte[])entry.getClass().getMethod("snapshot").invoke(entry);
            var items = decode(decode(bytes).getByteArray("vanilla")).getList("items", Tag.TAG_COMPOUND);
            long copies = items.stream().map(tag -> ItemStack.parseOptional(server.registryAccess(), (CompoundTag)tag))
                    .filter(stack -> ItemStack.matches(bag, stack)).count();
            check(copies == 1, "Native held bag components, upgrades or count changed");
        }
        if (full) check(server.getCommands().getDispatcher().execute("jailcustody inspect " + tx, server.createCommandSourceStack()) == 1,
                "Original custody index no longer authenticates its item journal");
    }
    private RosecourtReview() {}
}
